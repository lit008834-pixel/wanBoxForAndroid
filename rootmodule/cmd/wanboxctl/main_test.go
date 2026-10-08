// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"path/filepath"
	"testing"
	module "wanbox/module"
)

func TestCommandAllowlistRejectsExtraArgumentsAndShellInput(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "data"), t.TempDir())
	for _, args := range [][]string{
		{}, {"status", "extra"}, {"start", "extra"}, {"sh", "-c", "echo unsafe"},
		{"config", "apply", "../file"}, {"config", "erase"}, {"autostart", "yes"},
		{"__internal", "unknown"}, {"stop; echo unsafe"}, {"--data-root", "/tmp"},
		{"__internal", "schedule-install", "1"}, {"__internal", "schedule-install", "2;stop"},
		{"__internal", "schedule-install", "02"}, {"__internal", "schedule-install", "2", "extra"},
		{"__internal", "finish-install", "2", "bad", "bad"},
		{"__internal", "finish-install", "2", "0", "../file"},
	} {
		if err := run(context.Background(), r, args); err == nil || err.Error() != "arguments_invalid" {
			t.Fatalf("unexpected command acceptance: %q: %v", args, err)
		}
	}
}

func TestReadOnlyCommandsDoNotStartRuntime(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "absent"), t.TempDir())
	for _, command := range []string{"status", "module", "logs"} {
		if err := run(context.Background(), r, []string{command}); err != nil {
			t.Fatal(err)
		}
	}
}
