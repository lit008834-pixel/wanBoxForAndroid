// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// clashProxySelector is the top-level selector tag the manager always builds
// into module configs (see ConfigBuilder: buildSelector is forced on).
const clashProxySelector = "proxy"

// profileTagFile is staged via the snapshot Files channel under files/
// (not the strict Snapshot schema) so older modules accept new snapshots.
const profileTagFile = "profile_tags.json"

// ProfileTag is the runtime identity of one selectable node.
type ProfileTag struct {
	Tag  string `json:"tag"`
	Name string `json:"name"`
}

// SelectNode hot-switches the running core to another node without restart.
// It moves the top-level "proxy" selector through the config's own Clash API,
// then confirms the switch by reading the selector back. The persisted manager
// selection is the source of truth; this only changes runtime state.
// Any failure must make the caller fall back to a full config apply.
func (r *Runtime) SelectNode(ctx context.Context, profileID int64) error {
	if profileID <= 0 {
		return errors.New("arguments_invalid")
	}
	return r.withLock(func() error {
		st := r.Status()
		if st.Phase != "connected" {
			return errors.New("module_not_running")
		}
		rev := st.RunningRevision
		if rev == "" {
			return errors.New("module_not_running")
		}
		var tags map[string]ProfileTag
		if e := readJSON(r.path("generations", rev, "files", profileTagFile), &tags, 1<<20); e != nil {
			return errors.New("node_tag_not_found")
		}
		pt, ok := tags[strconv.FormatInt(profileID, 10)]
		if !ok || pt.Tag == "" {
			return errors.New("node_tag_not_found")
		}
		var cfg struct {
			Experimental struct {
				ClashAPI *struct {
					ExternalController string `json:"external_controller"`
					Secret             string `json:"secret"`
				} `json:"clash_api"`
			} `json:"experimental"`
		}
		if e := readJSON(r.path("generations", rev, "run", "config.json"), &cfg, MaxConfigBytes); e != nil {
			return errors.New("clash_api_disabled")
		}
		api := cfg.Experimental.ClashAPI
		if api == nil || api.ExternalController == "" {
			return errors.New("clash_api_disabled")
		}
		_, port, e := net.SplitHostPort(api.ExternalController)
		if e != nil || port == "" {
			return errors.New("clash_api_disabled")
		}
		// Always dial loopback: the API never binds wider for the hot-switch path.
		base := "http://127.0.0.1:" + port
		client := &http.Client{Timeout: 10 * time.Second}
		call := func(method, url string, body []byte) (int, []byte, error) {
			var reader io.Reader
			if body != nil {
				reader = bytes.NewReader(body)
			}
			req, e := http.NewRequestWithContext(ctx, method, url, reader)
			if e != nil {
				return 0, nil, e
			}
			if api.Secret != "" {
				req.Header.Set("Authorization", "Bearer "+api.Secret)
			}
			if body != nil {
				req.Header.Set("Content-Type", "application/json")
			}
			resp, e := client.Do(req)
			if e != nil {
				return 0, nil, e
			}
			defer resp.Body.Close()
			data, e := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
			if e != nil {
				return 0, nil, e
			}
			return resp.StatusCode, data, nil
		}
		// Read the selector first: only selector outbounds accept a runtime switch.
		// URLTest / loadbalance groups keep the previous full-apply path.
		code, data, e := call(http.MethodGet, base+"/proxies/"+clashProxySelector, nil)
		if e != nil || code != http.StatusOK {
			return errors.New("node_select_failed")
		}
		var current struct {
			Type string `json:"type"`
			Now  string `json:"now"`
		}
		if e := json.Unmarshal(data, &current); e != nil {
			return errors.New("node_select_failed")
		}
		if !strings.EqualFold(current.Type, "selector") {
			return errors.New("node_select_unsupported")
		}
		if current.Now != pt.Tag {
			body, _ := json.Marshal(map[string]string{"name": pt.Tag})
			code, _, e = call(http.MethodPut, base+"/proxies/"+clashProxySelector, body)
			if e != nil || (code != http.StatusOK && code != http.StatusNoContent) {
				return errors.New("node_select_failed")
			}
		}
		code, data, e = call(http.MethodGet, base+"/proxies/"+clashProxySelector, nil)
		if e != nil || code != http.StatusOK {
			return errors.New("node_select_not_confirmed")
		}
		var confirmed struct {
			Now string `json:"now"`
		}
		if e := json.Unmarshal(data, &confirmed); e != nil || confirmed.Now != pt.Tag {
			return errors.New("node_select_not_confirmed")
		}
		// Publish the actually running node for UI/notifications; the manager's
		// persisted selection stays the source of truth for the next apply.
		var s State
		if e := readJSON(r.path("state.json"), &s, 1<<20); e == nil {
			s.ProfileID = profileID
			if pt.Name != "" {
				s.ProfileName = pt.Name
			}
			_ = jsonWrite(r.path("state.json"), s)
		}
		r.recordEvent("node_hot_switched")
		return nil
	})
}
