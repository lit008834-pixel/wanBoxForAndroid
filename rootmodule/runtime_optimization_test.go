// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestUnchangedApplyDoesNotStageOrReplaceRollback(t *testing.T) {
	r, snapshot := fixture(t)
	validations := 0
	r.Validate = func(context.Context, string, string) error { validations++; return nil }
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	events := len(r.Events())
	snapshot.ExpectedRevision = initial.Revision
	result, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	if result.Revision != initial.Revision || validations != 1 {
		t.Fatalf("identical configuration was staged: revision changed=%v validations=%d", result.Revision != initial.Revision, validations)
	}
	if _, err = os.Stat(r.path("previous")); !os.IsNotExist(err) {
		t.Fatal("no-op changed rollback pointer", err)
	}
	if len(r.Events()) != events {
		t.Fatal("no-op emitted a configuration event")
	}
	entries, err := os.ReadDir(r.path("generations"))
	if err != nil || len(entries) != 1 {
		t.Fatal("no-op added a generation", err)
	}
	stale := snapshot
	stale.ExpectedRevision = ""
	if _, err = r.Apply(context.Background(), stale); err == nil || err.Error() != "revision_conflict" {
		t.Fatal("no-op bypassed revision conflict", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = r.Apply(ctx, snapshot); err != context.Canceled {
		t.Fatal("cancellation ignored", err)
	}
	if validations != 1 {
		t.Fatal("cancelled no-op staged a snapshot")
	}
}

// @author 雾晚: editing while stopped commits the candidate but does not create core processes.
func TestStoppedEditCommitsAndInvalidCandidatePreservesPriorConfig(t *testing.T) {
	r, snapshot := fixture(t)
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.ExpectedRevision = initial.Revision
	snapshot.ProfileName = "edited while stopped"
	changed, err := r.Apply(context.Background(), snapshot)
	if err != nil || changed.Revision == initial.Revision || changed.Phase != "stopped" || changed.Core.PID != 0 || changed.Supervisor.PID != 0 {
		t.Fatal("stopped apply started/ignored config", changed, err)
	}
	r.Validate = func(context.Context, string, string) error { return fmt.Errorf("fixture_invalid") }
	snapshot.ExpectedRevision = changed.Revision
	snapshot.ProfileName = "invalid edit"
	if _, err = r.Apply(context.Background(), snapshot); err == nil {
		t.Fatal("invalid candidate accepted")
	}
	if current := r.Status(); current.Revision != changed.Revision || current.Phase != "stopped" || current.Core.PID != 0 || current.Supervisor.PID != 0 {
		t.Fatal("invalid edit changed stopped state", current)
	}
}

func TestSnapshotComparisonCoversEveryContentField(t *testing.T) {
	base := Snapshot{SchemaVersion: Version, ExpectedRevision: "old", Config: json.RawMessage(`{"a":1}`),
		Files: map[string][]byte{"asset": {1}}, Plugins: []Plugin{{"kind", "bin", "config", "certificate"}},
		ProfileID: 7, ProfileName: "fixture", AutoStart: true}
	cases := map[string]func(*Snapshot){
		"SchemaVersion":       func(s *Snapshot) { s.SchemaVersion++ },
		"Config":              func(s *Snapshot) { s.Config = json.RawMessage(`{"a":2}`) },
		"Files":               func(s *Snapshot) { s.Files = map[string][]byte{"asset": {2}} },
		"Plugins":             func(s *Snapshot) { s.Plugins = []Plugin{{"other", "bin", "config", "certificate"}} },
		"PerformancePriority": func(s *Snapshot) { s.PerformancePriority = true },
		"AutoStart":           func(s *Snapshot) { s.AutoStart = false },
		"ProfileID":           func(s *Snapshot) { s.ProfileID++ },
		"ProfileName":         func(s *Snapshot) { s.ProfileName = "other" },
	}
	for i := 0; i < reflect.TypeOf(base).NumField(); i++ {
		field := reflect.TypeOf(base).Field(i).Name
		if field != "ExpectedRevision" && cases[field] == nil {
			t.Fatalf("new snapshot field needs comparison coverage: %s", field)
		}
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			other := base
			change(&other)
			if identicalSnapshotContent(base, other) {
				t.Fatal("changed content ignored")
			}
		})
	}
	other := base
	other.ExpectedRevision = "next"
	if !identicalSnapshotContent(base, other) {
		t.Fatal("concurrency token treated as content")
	}
	other.Files = map[string][]byte{"renamed": {1}}
	if identicalSnapshotContent(base, other) {
		t.Fatal("renamed file ignored")
	}
	for i := 0; i < reflect.TypeOf(Plugin{}).NumField(); i++ {
		other = base
		other.Plugins = append([]Plugin(nil), base.Plugins...)
		reflect.ValueOf(&other.Plugins[0]).Elem().Field(i).SetString("changed")
		if identicalSnapshotContent(base, other) {
			t.Fatalf("plugin field %d ignored", i)
		}
	}
}

func TestChangedSnapshotStillValidatesAndCanRollback(t *testing.T) {
	r, snapshot := fixture(t)
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.ExpectedRevision = initial.Revision
	snapshot.Files["assets/example.srs"] = []byte{3, 4}
	changed, err := r.Apply(context.Background(), snapshot)
	if err != nil || changed.Revision == initial.Revision {
		t.Fatal("changed resource ignored", err)
	}
	previous, err := os.ReadFile(r.path("previous"))
	if err != nil || string(previous) != initial.Revision {
		t.Fatal("rollback pointer lost", err)
	}
	snapshot.ExpectedRevision = changed.Revision
	snapshot.SchemaVersion++
	if _, err = r.Apply(context.Background(), snapshot); err == nil {
		t.Fatal("invalid schema accepted")
	}
	if r.Status().Revision != changed.Revision {
		t.Fatal("invalid input replaced current")
	}
}

func TestStatsDedupPreservesChangesAndClearing(t *testing.T) {
	s := State{}
	zero := json.RawMessage(`{"tag":"proxy","tx":0,"rx":0,"directTx":0,"directRx":0}`)
	if !replaceStats(&s, zero) {
		t.Fatal("first zero sample lost")
	}
	if replaceStats(&s, append(json.RawMessage(nil), zero...)) {
		t.Fatal("idle sample rewrites state")
	}
	changed := json.RawMessage(`{"tag":"proxy","tx":10,"rx":20,"directTx":0,"directRx":0}`)
	if !replaceStats(&s, changed) {
		t.Fatal("traffic change lost")
	}
	if !replaceStats(&s, nil) || s.Stats != nil {
		t.Fatal("disconnect/error cannot clear sample")
	}
	if replaceStats(&s, nil) {
		t.Fatal("empty sample rewrites state")
	}
}

func TestStartupProbeStopsAllStartupWorkAfterReadyOrCancellation(t *testing.T) {
	for _, name := range []string{"ready", "cancel", "failure"} {
		t.Run(name, func(t *testing.T) {
			p := newStartupProbe(time.Millisecond, time.Hour)
			defer p.stop()
			select {
			case <-p.tick:
			case <-time.After(time.Second):
				t.Fatal("startup never probes")
			}
			p.stop()
			p.stop() // repeated lifecycle cleanup is safe
			if p.tick != nil || p.deadline != nil {
				t.Fatal("select still schedules startup work")
			}
		})
	}
	p := newStartupProbe(time.Hour, time.Millisecond)
	defer p.stop()
	select {
	case <-p.deadline:
	case <-time.After(time.Second):
		t.Fatal("unready core no longer times out")
	}
}
