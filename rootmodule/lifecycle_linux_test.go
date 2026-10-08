//go:build linux

// @author 雾晚
package module

import (
	"context"
	"encoding/json"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// A real child process fixture, without VPN/Root routing. Device tests remain separate.
func TestSupervisorCancelReapsCoreAndAppPIDIsNotOwner(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); e != nil {
		t.Fatal(e)
	}
	script := `#!/bin/sh
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do
  echo 'WANBOX_STATS:{"tag":"proxy","tx":0,"rx":0,"directTx":0,"directRx":0}'
  sleep 0.1
done
rm -f "$4" "$3"
`
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte(script), 0700); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Supervise(ctx) }()
	deadline := time.Now().Add(5 * time.Second)
	var identity ProcessIdentity
	for time.Now().Before(deadline) {
		state := r.Status()
		if state.Phase == "connected" && len(state.Stats) > 0 {
			identity = state.Core
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if identity.PID == 0 {
		t.Fatal("never became ready", r.Status())
	}
	if !SameProcess(identity) {
		t.Fatal("unverified core")
	}
	// Pass the five-second statistics throttle: identical idle samples must not
	// rewrite the persisted state, while the child remains connected and alive.
	before, e := os.Stat(r.path("state.json"))
	if e != nil {
		t.Fatal(e)
	}
	time.Sleep(5500 * time.Millisecond)
	after, e := os.Stat(r.path("state.json"))
	if e != nil || !after.ModTime().Equal(before.ModTime()) || !SameProcess(identity) {
		t.Fatal("idle samples rewrote state or stopped the child", e)
	}
	cancel()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancellation hung")
	}
	if SameProcess(identity) {
		t.Fatal("child leaked")
	}
	if r.Status().Phase == "connected" {
		t.Fatal("stale connected")
	}
}
func TestPIDIdentityRejectsReusedPID(t *testing.T) {
	p, e := Identity(os.Getpid())
	if e != nil {
		t.Fatal(e)
	}
	if !SameProcess(p) {
		t.Fatal("own identity missing")
	}
	p.Start = "old-start-token"
	if SameProcess(p) {
		t.Fatal("reused PID accepted")
	}
	if e = terminate(p); e != nil {
		t.Fatal(e)
	}
	if e = forceTerminate(p); e != nil {
		t.Fatal(e)
	} // Must not signal this test process.
}

// This subprocess is the real supervisor fixture, not an Android/root-network test.
func TestModuleWorkerFixture(t *testing.T) {
	if os.Getenv("WANBOX_TEST_WORKER") != "1" {
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM)
	defer cancel()
	r := New(os.Getenv("WANBOX_TEST_ROOT"), os.Getenv("WANBOX_TEST_MODULE"))
	err := r.Supervise(ctx)
	if err != nil {
		os.Exit(1)
	}
	os.Exit(0)
}
func TestActiveFailedApplyRestoresRunningRevision(t *testing.T) {
	r, s := fixture(t)
	initial, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	bin := filepath.Join(r.ModuleDir, "bin")
	if e = os.MkdirAll(bin, 0700); e != nil {
		t.Fatal(e)
	}
	testExe, e := os.Executable()
	if e != nil {
		t.Fatal(e)
	}
	t.Setenv("WANBOX_TEST_WORKER", "1")
	t.Setenv("WANBOX_TEST_ROOT", r.Root)
	t.Setenv("WANBOX_TEST_MODULE", r.ModuleDir)
	cli := "#!/bin/sh\nexec '" + strings.ReplaceAll(testExe, "'", "'\"'\"'") + "' -test.run=^TestModuleWorkerFixture$\n"
	core := `#!/bin/sh
if grep -q 'fail_start' "$1"; then exit 1; fi
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do sleep 0.1; done
rm -f "$4" "$3"
`
	for name, body := range map[string]string{"wanboxctl": cli, "rootbox": core} {
		if e = os.WriteFile(filepath.Join(bin, name), []byte(body), 0700); e != nil {
			t.Fatal(e)
		}
	}
	if e = r.Start(context.Background()); e != nil {
		t.Fatal(e)
	}
	defer r.Stop(context.Background())
	// Re-applying an unchanged connected snapshot must preserve the real child PID.
	before := r.Status()
	s.ExpectedRevision = initial.Revision
	unchanged, e := r.Apply(context.Background(), s)
	if e != nil || unchanged.Core != before.Core || unchanged.Supervisor != before.Supervisor ||
		unchanged.Phase != "connected" || unchanged.RunningRevision != initial.Revision {
		t.Fatal("unchanged apply interrupted running core", e)
	}
	if _, e = os.Stat(r.path("previous")); !os.IsNotExist(e) {
		t.Fatal("unchanged apply replaced rollback pointer", e)
	}
	s.ExpectedRevision = initial.Revision
	s.Config = json.RawMessage(`{"fail_start":true}`)
	if _, e = r.Apply(context.Background(), s); e == nil || e.Error() != "new_core_failed_rolled_back" {
		t.Fatal("missing rollback", e)
	}
	state := r.Status()
	if state.Phase != "connected" || state.RunningRevision != initial.Revision || state.Revision != initial.Revision {
		t.Fatal("wrong live revision", state)
	}
	if e = r.Stop(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e = r.Stop(context.Background()); e != nil {
		t.Fatal("second stop", e)
	}
}
