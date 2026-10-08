// @author 雾晚
package module

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func fixture(t *testing.T) (*Runtime, Snapshot) {
	t.Helper()
	dir := t.TempDir()
	mod := filepath.Join(dir, "module")
	if e := os.Mkdir(mod, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(mod, "module.prop"), []byte("id=wanbox"), 0600); e != nil {
		t.Fatal(e)
	}
	r := New(filepath.Join(dir, "data"), mod)
	r.Validate = func(context.Context, string, string) error { return nil }
	return r, Snapshot{SchemaVersion: Version, Config: json.RawMessage(`{"inbounds":[{"type":"tun","interface_name":"tun0","auto_route":true}],"outbounds":[{"type":"direct","tag":"direct"}],"experimental":{"cache_file":{"enabled":true,"path":"cache.db"}}}`), Files: map[string][]byte{"assets/example.srs": {1, 2}}, ProfileID: 7, ProfileName: "fixture", AutoStart: true}
}
func TestActualSnapshotRoundTripAndConflict(t *testing.T) {
	r, s := fixture(t)
	encoded, _ := json.Marshal(s)
	decoded, e := DecodeSnapshot(bytes.NewReader(encoded))
	if e != nil {
		t.Fatal(e)
	}
	out, e := r.Apply(context.Background(), decoded)
	if e != nil {
		t.Fatal(e)
	}
	if out.Revision == "" {
		t.Fatal("missing revision")
	}
	rev, got, e := r.Current()
	if e != nil || got.ProfileID != s.ProfileID || !bytes.Equal(got.Config, s.Config) {
		t.Fatal("round trip", e)
	}
	if _, e = r.Apply(context.Background(), s); e == nil || e.Error() != "revision_conflict" {
		t.Fatal("lost conflict", e)
	}
	s.ExpectedRevision = rev
	s.ProfileName = "changed"
	if _, e = r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	old, _, e := r.Current()
	if e != nil || old == rev {
		t.Fatal("no new generation")
	}
	if _, e = os.Stat(r.path("generations", rev, "snapshot.json")); e != nil {
		t.Fatal("rollback snapshot lost")
	}
}
func TestValidationFailureLeavesCommittedSnapshotUntouched(t *testing.T) {
	r, s := fixture(t)
	out, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	r.Validate = func(context.Context, string, string) error { return errors.New("core_config_invalid") }
	s.ExpectedRevision = out.Revision
	s.Config = json.RawMessage(`{"broken":true}`)
	if _, e = r.Apply(context.Background(), s); e == nil {
		t.Fatal("accepted invalid core config")
	}
	rev, _, e := r.Current()
	if e != nil || rev != out.Revision {
		t.Fatal("overwrote last valid config")
	}
	dirs, _ := os.ReadDir(r.path("generations"))
	for _, d := range dirs {
		if strings.HasPrefix(d.Name(), ".stage-") {
			t.Fatal("stage leaked")
		}
	}
}
func TestConcurrentApplyOneWriterWins(t *testing.T) {
	r, s := fixture(t)
	var wg sync.WaitGroup
	var mu sync.Mutex
	success, conflict := 0, 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, e := r.Apply(context.Background(), s)
			mu.Lock()
			defer mu.Unlock()
			if e == nil {
				success++
			} else if e.Error() == "revision_conflict" {
				conflict++
			} else {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	if success != 1 || conflict != 7 {
		t.Fatal(success, conflict)
	}
}
func TestRejectTraversalAndUnsupportedInputs(t *testing.T) {
	_, base := fixture(t)
	for _, name := range []string{"../evil", "assets/../../evil", "/data/evil", "assets\\evil", "assets//evil", "assets/./evil", "runtime/ready"} {
		s := base
		s.Files = map[string][]byte{name: {1}}
		if CheckSnapshot(s) == nil {
			t.Errorf("accepted %q", name)
		}
	}
	for _, input := range []string{"{}", `{"schemaVersion":2}`, `{} {}`, `{"schemaVersion":1,"unknown":true}`} {
		if _, e := DecodeSnapshot(strings.NewReader(input)); e == nil {
			t.Error("accepted", input)
		}
	}
}
func TestFileLimitAndMissingOrAppResource(t *testing.T) {
	_, s := fixture(t)
	s.Files = map[string][]byte{}
	for i := 0; i <= MaxFiles; i++ {
		s.Files["assets/"+strings.Repeat("a", i+1)] = []byte{1}
	}
	if CheckSnapshot(s) == nil {
		t.Fatal("file limit ignored")
	}
	_, s = fixture(t)
	for _, p := range []string{"/data/user/0/private/key", "../files/missing", "../../secret"} {
		s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"` + p + `"}]}}`)
		if CheckSnapshot(s) == nil {
			t.Error("accepted", p)
		}
	}
	s.Config = json.RawMessage(`{"dns":{"servers":[{"type":"https","path":"/dns-query"}]},"outbounds":[{"type":"vless","transport":{"type":"ws","path":"/socket"}}]}`)
	if e := CheckSnapshot(s); e != nil {
		t.Fatal("confused HTTP URL path with filesystem path", e)
	}
}
func TestPersistedAutoStartOptOutAndNoConfigBoot(t *testing.T) {
	r, s := fixture(t)
	if e := r.Boot(context.Background(), func() bool { return true }); e != nil {
		t.Fatal(e)
	}
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e := r.SetAutoStart(false); e != nil {
		t.Fatal(e)
	}
	r.Validate = func(context.Context, string, string) error { t.Fatal("opted out but tried boot startup"); return nil }
	if e := r.Boot(context.Background(), func() bool { return true }); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "disable"), nil, 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.Start(context.Background()); e == nil {
		t.Fatal("started disabled module")
	}
}
func TestCancelledBootAndStaleConnectedState(t *testing.T) {
	r, _ := fixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if r.Boot(ctx, func() bool { return false }) == nil {
		t.Fatal("ignored cancel")
	}
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	_ = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "connected", Stats: json.RawMessage(`{"tx":999}`), Supervisor: ProcessIdentity{PID: 2147483647, Start: "invalid"}})
	state := r.Status()
	if state.Phase == "connected" || state.Stats != nil {
		t.Fatal("stale success")
	}
}
func TestPrivatePermissionsAndUpgradePersistence(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	before, _, _ := r.Current()
	upgraded := New(r.Root, r.ModuleDir)
	after, _, e := upgraded.Current()
	if e != nil || before != after {
		t.Fatal("update discarded data", e)
	}
	if e := r.Stop(context.Background()); e != nil {
		t.Fatal(e)
	}
	after, _, _ = r.Current()
	if before != after {
		t.Fatal("stop discarded user config")
	}
	info, e := os.Stat(r.Root)
	if e != nil {
		t.Fatal(e)
	}
	if info.Mode().Perm()&0077 != 0 && os.PathSeparator == '/' {
		t.Fatal("data not root private")
	}
}
func TestErrorResponseNeverExposesCoreCredentials(t *testing.T) {
	var out bytes.Buffer
	Response(&out, State{}, errors.New("token=fake-secret.example"))
	if strings.Contains(out.String(), "fake-secret") {
		t.Fatal("leaked secret")
	}
	if !strings.Contains(out.String(), "module_operation_failed") {
		t.Fatal("missing safe error")
	}
}

// @author 雾晚
func TestCancellationBeforeCommitAndRollbackCorruptPointer(t *testing.T) {
	r, s := fixture(t)
	first, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	s.ExpectedRevision = first.Revision
	s.ProfileName = "second"
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = r.Apply(ctx, s); e == nil {
		t.Fatal("cancelled update committed")
	}
	rev, _ := r.CurrentRevision()
	if rev != first.Revision {
		t.Fatal("cancel discarded original")
	}
	if _, e = r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(r.path("current"), []byte("corrupt"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = r.Rollback(context.Background()); e != nil {
		t.Fatal(e)
	}
	rev, _, e = r.Current()
	if e != nil || rev != first.Revision {
		t.Fatal("rollback did not recover", e)
	}
}
func TestFailureStateAndBoundedRedactedEvents(t *testing.T) {
	r, _ := fixture(t)
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	_ = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "failed", Error: "cgroup_move_failed", Stats: json.RawMessage(`{"tx":999}`)})
	state := r.Status()
	if state.Phase != "failed" || state.Error != "cgroup_move_failed" || state.Stats != nil {
		t.Fatal("lost actionable failure", state)
	}
	for i := 0; i < 45; i++ {
		r.recordEvent("config_applied")
	}
	r.recordEvent("password=fake-secret")
	events := r.Events()
	if len(events) != 40 {
		t.Fatal("unbounded events", len(events))
	}
	for _, event := range events {
		if event.Code != "config_applied" {
			t.Fatal("unsafe event")
		}
	}
}
func TestNonObjectConfigIsRejected(t *testing.T) {
	_, s := fixture(t)
	for _, config := range []string{"null", "[]", "true", "1"} {
		s.Config = json.RawMessage(config)
		if CheckSnapshot(s) == nil {
			t.Fatal("accepted", config)
		}
	}
}

func TestGeoAliasesRequirePortableBackingAssets(t *testing.T) {
	_, s := fixture(t)
	for _, alias := range []string{"geoip:cn", "geosite-cn", "geosite:cn@ads", "geoip-cn.srs"} {
		s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"` + alias + `"}]}}`)
		if CheckSnapshot(s) == nil {
			t.Fatal("missing geo asset accepted")
		}
		s.Files = map[string][]byte{"assets/geoip.db": {1}, "assets/geosite.db": {1}}
		if e := CheckSnapshot(s); e != nil {
			t.Fatal("legacy geo alias rejected", alias, e)
		}
		s.Files = map[string][]byte{}
	}
	s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"geoip:../../evil"}]}}`)
	if CheckSnapshot(s) == nil {
		t.Fatal("traversal geo alias accepted")
	}
}

func TestCacheLogAndDashboardResourcesRemainInsideSnapshot(t *testing.T) {
	_, s := fixture(t)
	for _, cfg := range []string{`{"experimental":{"cache_file":{"enabled":true,"path":"../../other.db"}}}`, `{"log":{"output":"/data/adb/other/file"}}`, `{"experimental":{"clash_api":{"external_ui":"../files/yacd"}}}`} {
		s.Config = json.RawMessage(cfg)
		if CheckSnapshot(s) == nil {
			t.Fatal("unsafe or missing resource accepted")
		}
	}
	s.Config = json.RawMessage(`{"experimental":{"clash_api":{"external_ui":"../files/yacd"}}}`)
	s.Files["files/yacd/index.html"] = []byte("<!doctype html>")
	if e := CheckSnapshot(s); e != nil {
		t.Fatal(e)
	}
}

func TestPluginBinaryAbiAndResourceContract(t *testing.T) {
	_, s := fixture(t)
	bin := make([]byte, 64)
	copy(bin, []byte{127, 'E', 'L', 'F', 2, 1})
	binary.LittleEndian.PutUint16(bin[18:20], 183)
	s.Files["plugins/mieru/core"] = bin
	s.Files["files/plugin.json"] = []byte(`{}`)
	s.Plugins = []Plugin{{Kind: "mieru", Binary: "plugins/mieru/core", Config: "files/plugin.json"}}
	if err := CheckSnapshot(s); err != nil {
		t.Fatal(err)
	}
	binary.LittleEndian.PutUint16(bin[18:20], 62)
	if CheckSnapshot(s) == nil {
		t.Fatal("accepted x86 helper in ARM64 module")
	}
	binary.LittleEndian.PutUint16(bin[18:20], 183)
	delete(s.Files, "files/plugin.json")
	if CheckSnapshot(s) == nil {
		t.Fatal("accepted missing helper config")
	}
}
