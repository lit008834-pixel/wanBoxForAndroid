// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	module "wanbox/module"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	args := os.Args[1:]
	if len(args) == 3 && args[0] == "__internal" && args[1] == "volume-key" {
		seconds, e := strconv.Atoi(args[2])
		if os.Geteuid() != 0 || e != nil || strconv.Itoa(seconds) != args[2] {
			os.Exit(1)
		}
		key, e := module.ReadVolumeKey(ctx, seconds)
		if e != nil {
			os.Exit(1)
		}
		fmt.Fprintln(os.Stdout, key)
		return
	}
	exe, e := os.Executable()
	if e != nil {
		os.Exit(1)
	}
	dir := filepath.Dir(filepath.Dir(exe))
	// Installed modules and manager staging paths are root-owned. No App-supplied
	// --module-dir/data-root/command arguments, env overrides or shell execution.
	r := module.New("/data/adb/wanbox", dir)
	var err error
	if os.Geteuid() != 0 {
		err = errors.New("root_required")
	} else if err = module.IndependentCgroup(); err == nil {
		err = run(ctx, r, os.Args[1:])
	}
	state := r.Status()
	if len(os.Args) == 2 && os.Args[1] == "logs" {
		state.Events = r.Events()
	}
	module.Response(os.Stdout, state, err)
	if err != nil {
		os.Exit(1)
	}
}
func run(ctx context.Context, r *module.Runtime, args []string) error {
	if len(args) == 3 && args[0] == "data" && args[1] == "selection-finish" {
		return r.FinishInstallSelection(args[2])
	}
	if len(args) == 4 && args[0] == "__internal" && args[1] == "schedule-install" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] {
			return errors.New("arguments_invalid")
		}
		return r.ScheduleInstallMode(ctx, pid, args[3])
	}
	if len(args) == 6 && args[0] == "__internal" && args[1] == "finish-install" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] || len(args[4]) != 64 || strings.Trim(args[4], "0123456789abcdef") != "" {
			return errors.New("arguments_invalid")
		}
		if _, e = strconv.ParseUint(args[3], 10, 64); e != nil {
			return errors.New("arguments_invalid")
		}
		return r.FinishInstallMode(ctx, pid, args[3], args[4], args[5])
	}
	if (len(args) == 3 || len(args) == 5) && args[0] == "__internal" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] {
			return errors.New("arguments_invalid")
		}
		if len(args) == 3 && args[1] == "schedule-install" {
			return r.ScheduleInstall(ctx, pid)
		}
		if len(args) == 5 && args[1] == "finish-install" {
			if _, e = strconv.ParseUint(args[3], 10, 64); e != nil || len(args[4]) != 64 || strings.Trim(args[4], "0123456789abcdef") != "" {
				return errors.New("arguments_invalid")
			}
			return r.FinishInstall(ctx, pid, args[3], args[4])
		}
	}
	if len(args) == 2 && args[0] == "data" {
		switch args[1] {
		case "prepare":
			return r.PrepareDataUpdate(ctx)
		case "finish":
			return r.FinishDataUpdate()
		case "rollback":
			return r.RollbackDataUpdate()
		}
	}
	if len(args) == 2 && args[0] == "config" && args[1] == "rollback" {
		return r.Rollback(ctx)
	}
	if len(args) == 1 {
		switch args[0] {
		case "status", "module", "logs":
			return nil
		case "start":
			return r.Start(ctx)
		case "stop":
			return r.Stop(ctx)
		case "restart", "reload":
			return r.Restart(ctx)
		}
	}
	if len(args) == 2 && args[0] == "config" && (args[1] == "apply" || args[1] == "validate") {
		snapshot, e := module.DecodeSnapshot(os.Stdin)
		if e != nil {
			return e
		}
		if args[1] == "validate" {
			return r.ValidateSnapshot(ctx, snapshot)
		}
		_, e = r.Apply(ctx, snapshot)
		return e
	}
	if len(args) == 2 && args[0] == "autostart" && (args[1] == "on" || args[1] == "off") {
		return r.SetAutoStart(args[1] == "on")
	}
	if len(args) == 2 && args[0] == "__internal" {
		switch args[1] {
		case "supervise":
			return r.Supervise(ctx)
		case "activate":
			return r.Activate(ctx, "/data/adb/modules/wanbox")
		case "install-manager":
			return r.InstallManager(ctx)
		case "boot":
			return r.Boot(ctx, func() bool {
				t, c := context.WithTimeout(ctx, 2*time.Second)
				defer c()
				b, e := exec.CommandContext(t, "/system/bin/getprop", "sys.boot_completed").Output()
				return e == nil && strings.TrimSpace(string(b)) == "1"
			})
		}
	}
	return errors.New("arguments_invalid")
}
