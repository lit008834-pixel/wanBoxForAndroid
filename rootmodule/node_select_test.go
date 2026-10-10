// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// fakeClash mimics the sing-box Clash API surface used by SelectNode.
type fakeClash struct {
	t        *testing.T
	selector string
	secret   string
	mu       sync.Mutex
	now      string
	putCount int
}

func (f *fakeClash) getNow() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

func (f *fakeClash) getPutCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.putCount
}

func (f *fakeClash) setSecret(secret string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.secret = secret
}

func (f *fakeClash) getSecret() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.secret
}

func (f *fakeClash) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !strings.HasPrefix(r.URL.Path, "/proxies/"+f.selector) {
		http.NotFound(w, r)
		return
	}
	if secret := f.getSecret(); secret != "" && r.Header.Get("Authorization") != "Bearer "+secret {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	switch r.Method {
	case http.MethodGet:
		w.Header().Set("Content-Type", "application/json")
		f.mu.Lock()
		now := f.now
		f.mu.Unlock()
		fmt.Fprintf(w, `{"name":%q,"type":"Selector","now":%q}`, f.selector, now)
	case http.MethodPut:
		var body struct {
			Name string `json:"name"`
		}
		if e := json.NewDecoder(r.Body).Decode(&body); e != nil || body.Name == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		f.mu.Lock()
		f.now = body.Name
		f.putCount++
		f.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// stageSelectNode builds generations/<rev>/{profile_tags.json,run/config.json}
// plus a connected state.json pointing at the fake Clash API.
func stageSelectNode(t *testing.T, r *Runtime, fake *fakeClash, tags map[string]ProfileTag, withClashAPI bool) string {
	t.Helper()
	srv := httptest.NewServer(fake)
	t.Cleanup(srv.Close)
	port := strings.TrimPrefix(srv.URL, "http://127.0.0.1:")
	rev := "rev-select-node"
	gen := r.path("generations", rev)
	if e := os.MkdirAll(filepath.Join(gen, "run"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.MkdirAll(filepath.Join(gen, "files"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := jsonWrite(filepath.Join(gen, "files", "profile_tags.json"), tags); e != nil {
		t.Fatal(e)
	}
	clash := ""
	if withClashAPI {
		clash = fmt.Sprintf(`,"experimental":{"clash_api":{"external_controller":"127.0.0.1:%s","secret":%q}}`, port, fake.getSecret())
	}
	config := fmt.Sprintf(`{"outbounds":[{"type":"selector","tag":"proxy","outbounds":["a","b"]}]%s}`, clash)
	if e := os.WriteFile(filepath.Join(gen, "run", "config.json"), []byte(config), 0600); e != nil {
		t.Fatal(e)
	}
	id, e := Identity(os.Getpid())
	if e != nil {
		t.Fatal(e)
	}
	st := State{SchemaVersion: Version, Phase: "connected", Revision: rev, RunningRevision: rev,
		ProfileID: 1, ProfileName: "a", Supervisor: id, Core: id}
	if e := jsonWrite(r.path("state.json"), st); e != nil {
		t.Fatal(e)
	}
	// Status() treats a live core as connected only with the ready marker.
	if e := os.MkdirAll(r.path("runtime"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(r.path("runtime", "ready"), []byte("1"), 0600); e != nil {
		t.Fatal(e)
	}
	return rev
}

func TestSelectNodeHotSwitch(t *testing.T) {
	r, _ := fixture(t)
	fake := &fakeClash{selector: "proxy", now: "a", secret: "s3cret"}
	tags := map[string]ProfileTag{"1": {Tag: "a", Name: "node-a"}, "2": {Tag: "b", Name: "node-b"}}
	stageSelectNode(t, r, fake, tags, true)

	if e := r.SelectNode(context.Background(), 2); e != nil {
		t.Fatal("select:", e)
	}
	if fake.getPutCount() != 1 || fake.getNow() != "b" {
		t.Fatal("clash api not switched", fake.getPutCount(), fake.getNow())
	}
	var st State
	if e := readJSON(r.path("state.json"), &st, 1<<20); e != nil {
		t.Fatal(e)
	}
	if st.ProfileID != 2 || st.ProfileName != "node-b" {
		t.Fatal("state not published", st.ProfileID, st.ProfileName)
	}
	if got := r.Status().ProfileID; got != 2 {
		t.Fatal("status profile not updated", got)
	}
}

func TestSelectNodeAlreadyCurrentSkipsPut(t *testing.T) {
	r, _ := fixture(t)
	fake := &fakeClash{selector: "proxy", now: "b", secret: "s3cret"}
	tags := map[string]ProfileTag{"2": {Tag: "b", Name: "node-b"}}
	stageSelectNode(t, r, fake, tags, true)

	if e := r.SelectNode(context.Background(), 2); e != nil {
		t.Fatal("select:", e)
	}
	if fake.getPutCount() != 0 {
		t.Fatal("redundant PUT issued")
	}
}

func TestSelectNodeFailures(t *testing.T) {
	newStaged := func(t *testing.T, withClashAPI bool, tags map[string]ProfileTag) (*Runtime, *fakeClash) {
		r, _ := fixture(t)
		fake := &fakeClash{selector: "proxy", now: "a", secret: "s3cret"}
		stageSelectNode(t, r, fake, tags, withClashAPI)
		return r, fake
	}
	tags := map[string]ProfileTag{"1": {Tag: "a", Name: "node-a"}}

	r, _ := newStaged(t, true, tags)
	if e := r.SelectNode(context.Background(), 99); e == nil || e.Error() != "node_tag_not_found" {
		t.Fatal("unknown profile:", e)
	}

	r, _ = newStaged(t, false, tags)
	if e := r.SelectNode(context.Background(), 1); e == nil || e.Error() != "clash_api_disabled" {
		t.Fatal("missing clash api:", e)
	}

	r, _ = newStaged(t, true, tags)
	// Break the generation: profile_tags.json removed -> not found.
	if e := os.Remove(r.path("generations", "rev-select-node", "files", "profile_tags.json")); e != nil {
		t.Fatal(e)
	}
	if e := r.SelectNode(context.Background(), 1); e == nil || e.Error() != "node_tag_not_found" {
		t.Fatal("missing tags file:", e)
	}

	// Module not running: rewrite state as stopped.
	r, _ = newStaged(t, true, tags)
	id, _ := Identity(os.Getpid())
	_ = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "stopped", Supervisor: id})
	if e := r.SelectNode(context.Background(), 1); e == nil || e.Error() != "module_not_running" {
		t.Fatal("stopped module:", e)
	}

	// Wrong secret -> clash api rejects -> not confirmed path surfaces failure.
	r, fake := newStaged(t, true, tags)
	fake.setSecret("other")
	if e := r.SelectNode(context.Background(), 1); e == nil {
		t.Fatal("expected auth failure")
	}
}

func TestStageMaterializesProfileTagsFile(t *testing.T) {
	r, s := fixture(t)
	// profile_tags.json travels via the snapshot Files channel under files/
	// (not the strict schema) so older modules keep accepting new snapshots.
	s.Files["files/profile_tags.json"] = []byte(`{"7":{"tag":"node-x","name":"x"}}`)
	rev, e := r.Stage(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	var tags map[string]ProfileTag
	if e := readJSON(r.path("generations", rev, "files", "profile_tags.json"), &tags, 1<<20); e != nil {
		t.Fatal("profile_tags.json not staged:", e)
	}
	if tags["7"].Tag != "node-x" || tags["7"].Name != "x" {
		t.Fatal("tag map corrupted", tags)
	}
}
