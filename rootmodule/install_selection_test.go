// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstallerSelectionsRetainDataUntilAppBackupAndBlockOldAutostart(t *testing.T) {
	for _, mode := range []string{"fresh", "nodes"} {
		r, source := fixture(t)
		state, e := r.Apply(context.Background(), source)
		if e != nil {
			t.Fatal(e)
		}
		packageFixture(t, r)
		digest, _ := manifestDigest(r.ModuleDir)
		if e = r.writeInstallChoice(mode, digest); e != nil {
			t.Fatal(e)
		}
		choice, _ := r.readInstallChoice()
		starts := 0
		r.installRunning = func() bool { return true }
		r.installStart = func(context.Context) error { starts++; return nil }
		target := filepath.Join(filepath.Dir(r.ModuleDir), "installed")
		if e = r.activateMode(context.Background(), target, false, mode); e != nil {
			t.Fatal(e)
		}
		installed := New(r.Root, target)
		selected, e := installed.installSelection()
		if e != nil || selected == nil || selected.Mode != mode || !validSelectionID(selected.ID) {
			t.Fatal(selected, e)
		}
		if selected.ID != choice.ID {
			t.Fatal("hot handoff changed durable selection token")
		}
		if revision, _, e := installed.Current(); e != nil || revision != state.Revision {
			t.Fatal("cleared before App backup", e)
		}
		if starts != 0 {
			t.Fatal("restarted discarded config")
		}
		for _, operation := range []func() error{
			func() error { return installed.Start(context.Background()) },
			func() error { return installed.Boot(context.Background(), func() bool { return true }) },
			func() error { _, e := installed.Apply(context.Background(), source); return e },
		} {
			if e = operation(); e == nil || e.Error() != "install_data_update_pending" {
				t.Fatal("pending selection did not block", e)
			}
		}
		if e = installed.FinishInstallSelection(strings.Repeat("0", 32)); e == nil {
			t.Fatal("accepted wrong token")
		}
		if e = installed.PrepareDataUpdate(context.Background()); e != nil {
			t.Fatal(e)
		}
		if e = installed.FinishInstallSelection(selected.ID); e == nil {
			t.Fatal("acknowledged incomplete module transaction")
		}
		if e = installed.FinishDataUpdate(); e != nil {
			t.Fatal(e)
		}
		if e = installed.FinishInstallSelection(selected.ID); e != nil {
			t.Fatal(e)
		}
		if e = installed.FinishInstallSelection(selected.ID); e != nil {
			t.Fatal("ack not idempotent", e)
		}
		if installed.Status().InstallData != nil {
			t.Fatal("selection retained")
		}
	}
}

func TestPreserveInstallerSelectionDoesNotChangeCurrentData(t *testing.T) {
	r, s := fixture(t)
	before, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	if e = r.withDataLock(func() error { return r.queueInstallSelection("preserve") }); e != nil {
		t.Fatal(e)
	}
	if r.Status().InstallData != nil {
		t.Fatal("preserve queued reset")
	}
	if current, _, e := r.Current(); e != nil || current != before.Revision {
		t.Fatal("changed current data", e)
	}
}

func TestInstallerSelectionCorruptionAndReentryCannotOverwriteIntent(t *testing.T) {
	r, _ := fixture(t)
	if e := r.withDataLock(func() error { return r.queueInstallSelection("nodes") }); e != nil {
		t.Fatal(e)
	}
	first, _ := r.installSelection()
	if e := r.withDataLock(func() error { return r.queueInstallSelection("fresh") }); e == nil {
		t.Fatal("overwrote pending intent")
	}
	second, _ := r.installSelection()
	if first.ID != second.ID || second.Mode != "nodes" {
		t.Fatal("changed intent")
	}
	if e := os.WriteFile(r.path("install-data.json"), []byte(`{"id":"../unsafe","mode":"fresh"}`), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e := r.installSelection(); e == nil {
		t.Fatal("accepted malformed intent")
	}
	if e := r.Start(context.Background()); e == nil {
		t.Fatal("corrupt intent unblocked connections")
	}
	if e := r.FinishInstallSelection("bad;clear"); e == nil {
		t.Fatal("accepted untrusted token")
	}
}

func TestStandardRebootInstallRetainsChoiceWithoutDeletingData(t *testing.T) {
	for _, mode := range []string{"fresh", "nodes"} {
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
		if e = r.writeInstallChoice(mode, digest); e != nil {
			t.Fatal(e)
		}
		if e = r.Boot(context.Background(), func() bool { return true }); e == nil || e.Error() != "install_data_update_pending" {
			t.Fatal("lost fallback choice", e)
		}
		selected, e := r.installSelection()
		if e != nil || selected == nil || selected.Mode != mode {
			t.Fatal(selected, e)
		}
		if _, e = os.Stat(filepath.Join(r.ModuleDir, "install-choice.json")); !os.IsNotExist(e) {
			t.Fatal("fallback choice not consumed")
		}
		if current, _, e := r.Current(); e != nil || current != state.Revision {
			t.Fatal("deleted before App backup", e)
		}
		if e = r.Boot(context.Background(), func() bool { return true }); e == nil || e.Error() != "install_data_update_pending" {
			t.Fatal("boot bypassed pending App transaction", e)
		}
	}
}

func TestRebootChoiceRejectsChangedPackageAndPreserveDoesNotQueueReset(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	digest, _ := manifestDigest(r.ModuleDir)
	if e := r.writeInstallChoice("preserve", digest); e != nil {
		t.Fatal(e)
	}
	if e := r.consumeBootInstallChoice(); e != nil {
		t.Fatal(e)
	}
	if r.Status().InstallData != nil {
		t.Fatal("preserve queued reset")
	}
	if e := r.writeInstallChoice("fresh", digest); e != nil {
		t.Fatal(e)
	}
	file := filepath.Join(r.ModuleDir, "package-manifest.json")
	content, _ := os.ReadFile(file)
	if e := os.WriteFile(file, append(content, '\n'), 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.consumeBootInstallChoice(); e == nil || e.Error() != "install_choice_changed" {
		t.Fatal("accepted replaced package", e)
	}
	if r.Status().InstallData != nil {
		t.Fatal("queued unverified choice")
	}
}

func TestCompletedInstallChoiceCannotResetNewDataOnReboot(t *testing.T) {
	for _, mode := range []string{"fresh", "nodes"} {
		r, source := fixture(t)
		packageFixture(t, r)
		digest, _ := manifestDigest(r.ModuleDir)
		if e := r.writeInstallChoice(mode, digest); e != nil {
			t.Fatal(e)
		}
		path := filepath.Join(r.ModuleDir, "install-choice.json")
		stale, e := os.ReadFile(path)
		if e != nil {
			t.Fatal(e)
		}
		if e = r.consumeBootInstallChoice(); e != nil {
			t.Fatal(e)
		}
		selected, _ := r.installSelection()
		if e = r.PrepareDataUpdate(context.Background()); e != nil {
			t.Fatal(e)
		}
		if e = r.FinishDataUpdate(); e != nil {
			t.Fatal(e)
		}
		if e = r.FinishInstallSelection(selected.ID); e != nil {
			t.Fatal(e)
		}
		source.ProfileName = "added after completed update"
		current, e := r.Apply(context.Background(), source)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, stale, 0600); e != nil {
			t.Fatal(e)
		}
		if e = r.consumeBootInstallChoice(); e != nil {
			t.Fatal(e)
		}
		if r.Status().InstallData != nil {
			t.Fatal("replayed completed reset")
		}
		if rev, _, e := r.Current(); e != nil || rev != current.Revision {
			t.Fatal("changed newer data", e)
		}
		if _, e = os.Stat(path); !os.IsNotExist(e) {
			t.Fatal("stale choice retained")
		}
	}
}

// An unfinished App transaction must not prevent installing its recovery code.
// It still blocks network commands and retains the original confirmed intent.
// @author 雾晚
func TestCodeUpdateCarriesPendingSelectionWithoutResetOrRestart(t *testing.T) {
	for _, requested := range []string{"preserve", "nodes"} {
		r, source := fixture(t)
		state, e := r.Apply(context.Background(), source)
		if e != nil {
			t.Fatal(e)
		}
		packageFixture(t, r)
		if e = r.queueInstallSelection("nodes"); e != nil {
			t.Fatal(e)
		}
		before, _ := r.installSelection()
		digest, _ := manifestDigest(r.ModuleDir)
		if e = r.writeInstallChoice(requested, digest); e != nil {
			t.Fatal(e)
		}
		choice, e := r.readInstallChoice()
		if e != nil || choice.Mode != before.Mode || choice.ID != before.ID {
			t.Fatal("changed pending intent", choice, e)
		}
		starts := 0
		r.installRunning = func() bool { return true }
		r.installStart = func(context.Context) error { starts++; return nil }
		target := filepath.Join(filepath.Dir(r.ModuleDir), "recovery")
		if e = r.completeInstallMode(context.Background(), func(context.Context) error { return nil }, target, digest, choice.Mode); e != nil {
			t.Fatal(e)
		}
		installed := New(r.Root, target)
		after, e := installed.installSelection()
		if e != nil || *after != *before || starts != 0 {
			t.Fatal("lost intent or restarted before App recovery", after, starts, e)
		}
		if revision, _, e := installed.Current(); e != nil || revision != state.Revision {
			t.Fatal("modified user data", e)
		}
		if e = installed.Start(context.Background()); e == nil || e.Error() != "install_data_update_pending" {
			t.Fatal("unblocked pending connection", e)
		}
		if e = installed.Boot(context.Background(), func() bool { return true }); e == nil || e.Error() != "install_data_update_pending" {
			t.Fatal("lost pending choice on reboot", e)
		}
		if e = installed.PrepareDataUpdate(context.Background()); e != nil {
			t.Fatal(e)
		}
		if e = installed.FinishDataUpdate(); e != nil {
			t.Fatal(e)
		}
		if e = installed.FinishInstallSelection(before.ID); e != nil {
			t.Fatal("original acknowledgement rejected", e)
		}
		if installed.Status().InstallData != nil {
			t.Fatal("recovery did not finish")
		}
	}
}

func TestPendingInstallCannotChangeIntentOrInterleaveDataTransaction(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	digest, _ := manifestDigest(r.ModuleDir)
	if e := r.withDataLock(func() error { return r.queueInstallSelection("nodes") }); e != nil {
		t.Fatal(e)
	}
	before, _ := r.installSelection()
	if e := r.writeInstallChoice("fresh", digest); e == nil {
		t.Fatal("changed pending nodes selection to fresh")
	}
	target := filepath.Join(filepath.Dir(r.ModuleDir), "recovery")
	if e := r.activateMode(context.Background(), target, false, "fresh"); e == nil {
		t.Fatal("replaced conflicting intent")
	}
	if e := r.PrepareDataUpdate(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "data_update_pending" {
		t.Fatal("interleaved active data transaction", e)
	}
	after, _ := r.installSelection()
	if *after != *before {
		t.Fatal("changed durable selection")
	}
	if _, e := os.Stat(target); !os.IsNotExist(e) {
		t.Fatal("replaced code on rejected update", e)
	}
}

func TestSelectionQueueAcceptsOnlyExactPendingReplay(t *testing.T) {
	r, _ := fixture(t)
	id := strings.Repeat("a", 32)
	if e := r.withDataLock(func() error { return r.queueInstallSelectionID("nodes", id) }); e != nil {
		t.Fatal(e)
	}
	if e := r.queueInstallSelectionID("nodes", id); e != nil {
		t.Fatal("exact replay rejected", e)
	}
	if e := r.queueInstallSelectionID("nodes", strings.Repeat("b", 32)); e == nil {
		t.Fatal("different token overwrote intent")
	}
	if e := r.queueInstallSelectionID("fresh", id); e == nil {
		t.Fatal("different mode overwrote intent")
	}
}
