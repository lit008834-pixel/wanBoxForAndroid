// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func packageFixture(t *testing.T, r *Runtime) {
	t.Helper()
	manifest := PackageManifest{SchemaVersion: 1, Files: map[string]PackageFile{}}
	elf := make([]byte, 64)
	copy(elf, []byte("\x7fELF\x02\x01"))
	binary.LittleEndian.PutUint16(elf[16:18], 3)
	binary.LittleEndian.PutUint16(elf[18:20], 183)
	for _, name := range []string{"module.prop", "service.sh", "uninstall.sh", "customize.sh", "LICENSE", "LIBCORE-LICENSE", "bin/rootbox", "bin/wanboxctl"} {
		data := []byte("fixture\n")
		if name == "module.prop" {
			data = []byte("id=wanbox\nversion=new\n")
		}
		if filepath.Dir(name) == "bin" {
			data = elf
		}
		path := filepath.Join(r.ModuleDir, name)
		if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(path, data, 0600); e != nil {
			t.Fatal(e)
		}
		hash := sha256.Sum256(data)
		manifest.Files[name] = PackageFile{int64(len(data)), hex.EncodeToString(hash[:])}
	}
	if e := jsonWrite(filepath.Join(r.ModuleDir, "package-manifest.json"), manifest); e != nil {
		t.Fatal(e)
	}
	r.installValidate = func(context.Context, string, string) error { return nil }
}
func TestHotInstallPreservesDataAndDoesNotStartInactiveCore(t *testing.T) {
	r, s := fixture(t)
	out, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e = r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if rev, _, e := r.Current(); e != nil || rev != out.Revision {
		t.Fatal("lost data", e)
	}
	if b, e := os.ReadFile(filepath.Join(target, "module.prop")); e != nil || string(b) != "id=wanbox\nversion=new\n" {
		t.Fatal("missing package", e)
	}
	if SameProcess(r.Status().Core) {
		t.Fatal("unexpected start")
	}
	if e = r.Activate(context.Background(), r.ModuleDir); e == nil {
		t.Fatal("accepted active directory as staging")
	}
}
func TestHotInstallCorruptPackageNeverReplacesCode(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600)
	os.WriteFile(filepath.Join(r.ModuleDir, "bin/rootbox"), []byte("corrupt"), 0600)
	if e := r.Activate(context.Background(), target); e == nil {
		t.Fatal("accepted corrupt package")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("replaced old code")
	}
}
func TestPackageELFRejectsWrongAbiAndManifestRejectsTraversal(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	path := filepath.Join(r.ModuleDir, "bin", "rootbox")
	data, _ := os.ReadFile(path)
	binary.LittleEndian.PutUint16(data[18:20], 62)
	os.WriteFile(path, data, 0600)
	if e := checkPackageELF(path); e == nil {
		t.Fatal("accepted AMD64")
	}
	m, _ := packageManifest(r.ModuleDir)
	m.Files["../escape"] = m.Files["bin/rootbox"]
	jsonWrite(filepath.Join(r.ModuleDir, "package-manifest.json"), m)
	if _, e := packageManifest(r.ModuleDir); e == nil {
		t.Fatal("accepted traversal")
	}
}
func TestHotInstallValidationFailureBeforeStoppingOldCore(t *testing.T) {
	r, s := fixture(t)
	r.Apply(context.Background(), s)
	packageFixture(t, r)
	r.installValidate = func(context.Context, string, string) error { return errors.New("incompatible") }
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600)
	if e := r.Activate(context.Background(), target); e == nil {
		t.Fatal("accepted incompatible config")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("lost old code")
	}
}
func TestHotInstallFailedRestartRestoresOldCodeAndSnapshot(t *testing.T) {
	r, s := fixture(t)
	out, _ := r.Apply(context.Background(), s)
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600)
	r.installRunning = func() bool { return true }
	attempts := 0
	r.installStart = func(context.Context) error {
		attempts++
		if attempts == 1 {
			return errors.New("new_failed")
		}
		return nil
	}
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "module_update_failed_rolled_back" {
		t.Fatal(e)
	}
	if attempts != 2 {
		t.Fatal("old restart not attempted")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("lost old code")
	}
	if rev, _, e := r.Current(); e != nil || rev != out.Revision {
		t.Fatal("lost snapshot", e)
	}
}
func TestHotInstallKeepsDisabledAndRejectsRemoval(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	os.Mkdir(target, 0700)
	os.WriteFile(filepath.Join(target, "remove"), nil, 0600)
	if e := r.Activate(context.Background(), target); e == nil {
		t.Fatal("activated removing module")
	}
	os.Remove(filepath.Join(target, "remove"))
	os.WriteFile(filepath.Join(target, "disable"), nil, 0600)
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(filepath.Join(target, "disable")); e != nil {
		t.Fatal("lost disable")
	}
}
func TestDataPreparationBlocksReconnectAndCanRollback(t *testing.T) {
	r, s := fixture(t)
	out, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	if e = r.PrepareDataUpdate(context.Background()); e != nil {
		t.Fatal(e)
	}
	if _, _, e = r.Current(); e != nil {
		t.Fatal(e)
	}
	if e = r.Start(context.Background()); e == nil || e.Error() != "data_update_pending" {
		t.Fatal("start not blocked", e)
	}
	if _, e = r.Apply(context.Background(), s); e == nil {
		t.Fatal("apply not blocked")
	}
	if e = r.PrepareDataUpdate(context.Background()); e != nil {
		t.Fatal("retry failed", e)
	}
	if e = r.RollbackDataUpdate(); e != nil {
		t.Fatal(e)
	}
	if rev, _, e := r.Current(); e != nil || rev != out.Revision {
		t.Fatal("lost rollback", e)
	}
}
func TestDataCommitKeepsArchiveAndLeavesNoCurrentConfiguration(t *testing.T) {
	r, s := fixture(t)
	r.Apply(context.Background(), s)
	if e := r.PrepareDataUpdate(context.Background()); e != nil {
		t.Fatal(e)
	}
	j, e := r.resetJournal()
	if e != nil {
		t.Fatal(e)
	}
	if e = r.FinishDataUpdate(); e != nil {
		t.Fatal(e)
	}
	if e = r.FinishDataUpdate(); e != nil {
		t.Fatal("retry finish failed", e)
	}
	if rev, _, e := r.Current(); e != nil || rev != "" {
		t.Fatal("old configuration revived", e)
	}
	if _, e = os.Stat(r.path("archives", j.Archive, "current")); e != nil {
		t.Fatal("lost archive", e)
	}
}
