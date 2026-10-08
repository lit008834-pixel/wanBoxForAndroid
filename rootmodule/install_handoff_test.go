// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestInstallWaitRequiresExitedInstallerAndFinalMarker(t *testing.T) {
	for _, tc := range []struct{ alive, marker bool }{{true, false}, {true, true}, {false, false}} {
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Millisecond)
		e := waitInstaller(ctx, func() bool { return tc.alive }, func() (bool, error) { return tc.marker, nil }, time.Millisecond, time.Millisecond)
		cancel()
		if !errors.Is(e, context.DeadlineExceeded) {
			t.Fatalf("accepted unfinished installer: %+v: %v", tc, e)
		}
	}
	if e := waitInstaller(context.Background(), func() bool { return false }, func() (bool, error) { return true, nil }, time.Millisecond, time.Millisecond); e != nil {
		t.Fatal(e)
	}
}

func TestInstallWaitCancellationAndMarkerRemovedDuringSettle(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if e := waitInstaller(ctx, func() bool { return false }, func() (bool, error) { return true, nil }, time.Millisecond, time.Millisecond); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	ctx, cancel = context.WithTimeout(context.Background(), 8*time.Millisecond)
	defer cancel()
	calls := 0
	e := waitInstaller(ctx, func() bool { return false }, func() (bool, error) {
		calls++
		return calls == 1, nil
	}, time.Millisecond, time.Millisecond)
	if !errors.Is(e, context.DeadlineExceeded) {
		t.Fatal("accepted removed marker", e)
	}
	if e = waitInstaller(context.Background(), func() bool { return false }, func() (bool, error) { return false, errors.New("read_failed") }, time.Millisecond, time.Millisecond); e == nil {
		t.Fatal("ignored marker read error")
	}
}

func TestInstallUpdateMarkerRejectsDirectoryAndSymlink(t *testing.T) {
	dir := t.TempDir()
	if ready, e := updateMarker(dir); e != nil || ready {
		t.Fatal(ready, e)
	}
	path := filepath.Join(dir, "update")
	if e := os.Mkdir(path, 0700); e != nil {
		t.Fatal(e)
	}
	if _, e := updateMarker(dir); e == nil {
		t.Fatal("accepted directory marker")
	}
	os.Remove(path)
	if e := os.WriteFile(path, nil, 0600); e != nil {
		t.Fatal(e)
	}
	if ready, e := updateMarker(dir); e != nil || !ready {
		t.Fatal(ready, e)
	}
	if e := os.Symlink(path, filepath.Join(dir, "link")); e == nil {
		os.Remove(path)
		os.Rename(filepath.Join(dir, "link"), path)
		if _, e = updateMarker(dir); e == nil {
			t.Fatal("accepted symlink marker")
		}
	}
}

func TestInstallerFinalWritesHappenBeforeActivationAndStagingRetires(t *testing.T) {
	r, s := fixture(t)
	state, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	digest, e := manifestDigest(r.ModuleDir)
	if e != nil {
		t.Fatal(e)
	}
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "module.prop"), []byte("old"), 0600)
	e = r.completeInstall(context.Background(), func(ctx context.Context) error {
		b, _ := os.ReadFile(filepath.Join(target, "module.prop"))
		if string(b) != "old" {
			t.Fatal("activated before manager finished")
		}
		// Manager writes its old live metadata and update flag after customize returns.
		os.WriteFile(filepath.Join(target, "module.prop"), []byte("manager final write"), 0600)
		os.WriteFile(filepath.Join(target, "update"), nil, 0600)
		// These files are intentionally removed by the real Magisk installer.
		os.Remove(filepath.Join(r.ModuleDir, "customize.sh"))
		os.Remove(filepath.Join(r.ModuleDir, "README.md"))
		return waitInstaller(ctx, func() bool { return false }, func() (bool, error) { return updateMarker(target) }, time.Millisecond, time.Millisecond)
	}, target, digest)
	if e != nil {
		t.Fatal(e)
	}
	b, _ := os.ReadFile(filepath.Join(target, "module.prop"))
	if string(b) != "id=wanbox\nversion=new\n" {
		t.Fatal("manager overwrote final package")
	}
	if _, e = os.Stat(filepath.Join(target, "update")); !os.IsNotExist(e) {
		t.Fatal("retained stale update flag", e)
	}
	if _, e = os.Stat(r.ModuleDir); !os.IsNotExist(e) {
		t.Fatal("manager would apply staging again", e)
	}
	manifest, e := packageManifest(target)
	if e != nil {
		t.Fatal("installed runtime manifest invalid", e)
	}
	if _, ok := manifest.Files["customize.sh"]; ok {
		t.Fatal("runtime manifest retains manager-removed file")
	}
	if revision, _, e := r.Current(); e != nil || revision != state.Revision {
		t.Fatal("lost persistent snapshot", e)
	}
}

func TestManagerCleanupNeverPermitsMissingRuntimePayload(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	digest, _ := manifestDigest(r.ModuleDir)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600)
	e := r.completeInstall(context.Background(), func(context.Context) error {
		return os.Remove(filepath.Join(r.ModuleDir, "bin/rootbox"))
	}, target, digest)
	if e == nil {
		t.Fatal("accepted missing runtime binary")
	}
	if _, e = os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code changed", e)
	}
	if _, e = os.Stat(r.ModuleDir); e != nil {
		t.Fatal("lost staging", e)
	}
}

func TestFailedOrChangedInstallerKeepsOldCodeAndStaging(t *testing.T) {
	for _, changed := range []bool{false, true} {
		r, _ := fixture(t)
		packageFixture(t, r)
		digest, _ := manifestDigest(r.ModuleDir)
		target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
		os.Mkdir(target, 0700)
		os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600)
		e := r.completeInstall(context.Background(), func(context.Context) error {
			if !changed {
				return context.DeadlineExceeded
			}
			return os.WriteFile(filepath.Join(r.ModuleDir, "package-manifest.json"), []byte("changed"), 0600)
		}, target, digest)
		if e == nil {
			t.Fatal("accepted incomplete or replaced installation")
		}
		if _, e = os.Stat(filepath.Join(target, "old")); e != nil {
			t.Fatal("old code changed", e)
		}
		if _, e = os.Stat(r.ModuleDir); e != nil {
			t.Fatal("lost manager staging", e)
		}
	}
}

func TestScheduleRejectsNonManagerDirectoryWithoutMutation(t *testing.T) {
	r, _ := fixture(t)
	if e := r.ScheduleInstall(context.Background(), os.Getpid()); e == nil {
		t.Fatal("accepted arbitrary staging")
	}
	if _, e := os.Stat(r.Root); !os.IsNotExist(e) {
		t.Fatal("modified data before staging validation", e)
	}
}
