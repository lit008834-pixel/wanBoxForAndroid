// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

func (r *Runtime) Supervise(ctx context.Context) error {
	if e := r.init(); e != nil {
		return e
	}
	unlock, e := fileLock(r.path("supervisor.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	identity, e := Identity(os.Getpid())
	if e != nil {
		return e
	}
	rev, snapshot, e := r.Current()
	if e != nil || rev == "" {
		return errors.New("config_missing")
	}
	// Resource bytes have already been validated and committed to private files.
	// Do not keep up to 128 MiB of decoded Base64 resources alive in the supervisor.
	snapshot.Files = nil
	snapshot.Config = nil
	s := State{SchemaVersion: Version, Phase: "starting", Revision: rev, RunningRevision: rev, ProfileID: snapshot.ProfileID, ProfileName: snapshot.ProfileName, Supervisor: identity}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var mu sync.Mutex
	persist := func() {
		if jsonWrite(r.path("state.json"), s) != nil {
			cancel()
		}
	}
	if e = jsonWrite(r.path("state.json"), s); e != nil {
		return errors.New("state_write_failed")
	}
	if e = IndependentCgroup(); e != nil {
		s.Phase = "failed"
		s.Error = "cgroup_move_failed"
		s.Supervisor = ProcessIdentity{}
		persist()
		return e
	}
	defer func() {
		mu.Lock()
		defer mu.Unlock()
		if !SameProcess(s.Core) {
			s.Core = ProcessIdentity{}
		}
		s.Supervisor = ProcessIdentity{}
		s.Stats = nil
		if s.Phase != "failed" {
			s.Phase = "stopped"
		}
		persist()
		_ = os.Remove(r.path("runtime", "ready"))
	}()
	// Stop on module disable/remove even when the management APK has been removed.
	done := make(chan struct{})
	defer func() { cancel(); <-done }()
	go func() {
		defer close(done)
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if !r.Enabled() {
					cancel()
					return
				}
			}
		}
	}()
	for attempt := 0; attempt <= 3; attempt++ {
		if ctx.Err() != nil {
			return nil
		}
		if _, err := os.Stat(r.path("runtime", "stop")); err == nil {
			return nil
		}
		_ = os.Remove(r.path("runtime", "ready"))
		mu.Lock()
		s.Phase = "starting"
		s.Error = ""
		persist()
		mu.Unlock()
		err := r.runGeneration(ctx, rev, snapshot, &s, &mu, persist)
		if ctx.Err() != nil {
			return nil
		}
		if _, e := os.Stat(r.path("runtime", "stop")); e == nil {
			return nil
		}
		if err == nil {
			return nil
		}
		if err.Error() == "cleanup_timeout_no_restart" {
			mu.Lock()
			s.Phase = "failed"
			s.Error = "cleanup_required"
			persist()
			mu.Unlock()
			return err
		}
		mu.Lock()
		s.Phase = "starting"
		s.Error = "core_retry"
		s.Stats = nil
		persist()
		mu.Unlock()
		if attempt == 3 {
			mu.Lock()
			s.Phase = "failed"
			s.Error = "retry_limit_reached"
			persist()
			mu.Unlock()
			r.recordEvent("retry_limit_reached")
			return errors.New("retry_limit_reached")
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(time.Duration(1<<attempt) * time.Second):
		}
	}
	return nil
}
func (r *Runtime) runGeneration(ctx context.Context, rev string, snapshot Snapshot, s *State, mu *sync.Mutex, persist func()) error {
	dir := r.path("generations", rev)
	childCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	identities := make([]ProcessIdentity, 0, len(snapshot.Plugins))
	waiters := make([]chan error, 0, len(snapshot.Plugins))
	defer func() {
		for _, identity := range identities {
			_ = terminate(identity)
		}
		var cleanup sync.WaitGroup
		for i, wait := range waiters {
			cleanup.Add(1)
			go func() {
				defer cleanup.Done()
				select {
				case <-wait:
				case <-time.After(2 * time.Second):
					_ = forceTerminate(identities[i])
					select {
					case <-wait:
					case <-time.After(2 * time.Second):
					}
				}
			}()
		}
		cleanup.Wait()
	}()
	for _, plugin := range snapshot.Plugins {
		binary := filepath.Join(dir, plugin.Binary)
		config := filepath.Join(dir, plugin.Config)
		var args []string
		env := append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Dir(binary))
		switch plugin.Kind {
		case "trojan-go":
			args = []string{"-config", config}
		case "mieru":
			args = []string{"run"}
			env = append(env, "MIERU_CONFIG_JSON_FILE="+config)
		case "naive":
			args = []string{config}
			if plugin.Certificate != "" {
				env = append(env, "SSL_CERT_FILE="+filepath.Join(dir, plugin.Certificate))
			}
		case "hysteria":
			args = []string{"--no-check", "--config", config, "--log-level", "warn", "client"}
		default:
			return errors.New("plugin_unsupported")
		}
		cmd := exec.Command(binary, args...)
		parentDeathSignal(cmd)
		cmd.Dir = filepath.Join(dir, "run")
		cmd.Env = env
		cmd.Stdout = io.Discard
		cmd.Stderr = io.Discard
		if e := cmd.Start(); e != nil {
			return errors.New("plugin_start_failed")
		}
		identity, e := Identity(cmd.Process.Pid)
		if e != nil {
			cmd.Process.Kill()
			cmd.Wait()
			return errors.New("plugin_identity_failed")
		}
		identities = append(identities, identity)
		wait := make(chan error, 1)
		waiters = append(waiters, wait)
		go func() { err := cmd.Wait(); wait <- err; cancel() }()
	}
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "rootbox"), filepath.Join(dir, "run", "config.json"), filepath.Join(dir, "assets"), r.path("runtime", "core.pid"), r.path("runtime", "ready"), r.path("runtime", "stop"), strconv.Itoa(os.Getpid()))
	parentDeathSignal(cmd)
	cmd.Dir = filepath.Join(dir, "run")
	cmd.Env = append(os.Environ(), "WANBOX_MODULE=1", "WANBOX_PERFORMANCE="+strconv.FormatBool(snapshot.PerformancePriority))
	output, e := cmd.StdoutPipe()
	if e != nil {
		return e
	}
	cmd.Stderr = io.Discard
	if e = cmd.Start(); e != nil {
		return errors.New("core_start_failed")
	}
	p, e := Identity(cmd.Process.Pid)
	if e != nil {
		cmd.Process.Kill()
		cmd.Wait()
		return errors.New("core_identity_failed")
	}
	mu.Lock()
	s.Core = p
	persist()
	mu.Unlock()
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		scanner := bufio.NewScanner(output)
		scanner.Buffer(make([]byte, 4096), 8192)
		last := time.Time{}
		for scanner.Scan() {
			line := scanner.Text()
			if !strings.HasPrefix(line, "WANBOX_STATS:") {
				continue
			}
			raw := strings.TrimPrefix(line, "WANBOX_STATS:")
			var stats struct {
				Tag      string `json:"tag"`
				Tx       int64  `json:"tx"`
				Rx       int64  `json:"rx"`
				DirectTx int64  `json:"directTx"`
				DirectRx int64  `json:"directRx"`
			}
			if json.Unmarshal([]byte(raw), &stats) != nil || len(stats.Tag) > 512 || stats.Tx < 0 || stats.Rx < 0 || stats.DirectTx < 0 || stats.DirectRx < 0 {
				continue
			}
			if time.Since(last) < 5*time.Second {
				continue
			}
			last = time.Now()
			b, _ := json.Marshal(stats)
			mu.Lock()
			if replaceStats(s, b) {
				persist()
			}
			mu.Unlock()
		}
		if scanner.Err() != nil {
			mu.Lock()
			s.Stats = nil
			persist()
			mu.Unlock()
			_, _ = io.Copy(io.Discard, output) // Drain a malformed/oversized raw log instead of blocking the core.
		}
	}()
	wait := make(chan error, 1)
	go func() { wait <- cmd.Wait() }()
	probe := newStartupProbe(100*time.Millisecond, 60*time.Second)
	defer probe.stop()
	startupFailed := false
	for {
		select {
		case err := <-wait:
			<-readerDone
			if err == nil {
				err = errors.New("core_exited")
			}
			return err
		case <-childCtx.Done():
			_ = terminate(p)
			select {
			case <-wait:
				<-readerDone
				if ctx.Err() != nil {
					return nil
				}
				if startupFailed {
					return errors.New("startup_timeout")
				}
				return errors.New("plugin_exited")
			case <-time.After(20 * time.Second):
				return errors.New("cleanup_timeout_no_restart")
			}
		case <-probe.deadline:
			startupFailed = true
			cancel()
		case <-probe.tick:
			if _, e = os.Stat(r.path("runtime", "ready")); e == nil {
				mu.Lock()
				if s.Phase != "connected" {
					s.Phase = "connected"
					if jsonWrite(r.path("state.json"), s) != nil {
						s.Phase = "failed"
						startupFailed = true
						cancel()
					}
					probe.stop()
				}
				mu.Unlock()
			}
		}
	}
}
