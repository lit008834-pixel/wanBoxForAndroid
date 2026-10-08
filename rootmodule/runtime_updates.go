// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"time"
)

// identicalSnapshotContent is deliberately byte-exact. A changed resource,
// plugin, policy or profile must still take the validated transaction path.
func identicalSnapshotContent(a, b Snapshot) bool {
	if a.SchemaVersion != b.SchemaVersion || a.PerformancePriority != b.PerformancePriority ||
		a.AutoStart != b.AutoStart || a.ProfileID != b.ProfileID || a.ProfileName != b.ProfileName ||
		!bytes.Equal(a.Config, b.Config) || len(a.Files) != len(b.Files) || len(a.Plugins) != len(b.Plugins) {
		return false
	}
	for name, data := range a.Files {
		other, exists := b.Files[name]
		if !exists || !bytes.Equal(data, other) {
			return false
		}
	}
	for i, plugin := range a.Plugins {
		if plugin != b.Plugins[i] {
			return false
		}
	}
	return true
}

// replaceStats suppresses identical periodic samples, not state transitions.
// The caller owns the state lock and the freshly marshalled sample.
func replaceStats(state *State, sample json.RawMessage) bool {
	if bytes.Equal(state.Stats, sample) {
		return false
	}
	state.Stats = sample
	return true
}

// startupProbe owns only startup timers. Nil channels disable both select cases
// after readiness; process exit and cancellation keep their original owners.
type startupProbe struct {
	ticker   *time.Ticker
	timer    *time.Timer
	tick     <-chan time.Time
	deadline <-chan time.Time
}

func newStartupProbe(interval, timeout time.Duration) *startupProbe {
	ticker, timer := time.NewTicker(interval), time.NewTimer(timeout)
	return &startupProbe{ticker: ticker, timer: timer, tick: ticker.C, deadline: timer.C}
}

func (p *startupProbe) stop() {
	p.ticker.Stop()
	p.timer.Stop()
	p.tick, p.deadline = nil, nil
}
