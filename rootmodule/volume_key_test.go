// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestVolumeEventsRequireDownAndIgnoreOtherKeys(t *testing.T) {
	for _, tc := range []struct{ line, want string }{
		{"/dev/input/event0: EV_KEY KEY_VOLUMEUP DOWN", "up"},
		{"/dev/input/event0: EV_KEY KEY_VOLUMEDOWN 00000001", "down"},
		{"/dev/input/event0: 0001 0072 00000001", "down"},
		{"/dev/input/event0: EV_KEY KEY_VOLUMEUP UP", ""},
		{"/dev/input/event0: EV_KEY KEY_VOLUMEDOWN REPEAT", ""},
		{"/dev/input/event0: EV_KEY KEY_VOLUMEDOWN 00000002", ""},
		{"/dev/input/event0: EV_KEY KEY_POWER DOWN", ""},
		{"/dev/input/event0: EV_ABS KEY_VOLUMEUP DOWN", ""},
	} {
		if got := volumeEvent(tc.line); got != tc.want {
			t.Fatal(tc, got)
		}
	}
}

func TestVolumeHelperProcess(t *testing.T) {
	if len(os.Args) < 2 || os.Args[len(os.Args)-1] != "volume-helper" {
		return
	}
	fmt.Println("/dev/input/event0: EV_KEY KEY_VOLUMEUP UP")
	fmt.Println("/dev/input/event0: EV_KEY KEY_VOLUMEDOWN DOWN")
	time.Sleep(time.Minute)
	os.Exit(0)
}

func TestVolumeReaderClosesChildAndRejectsUnboundedWait(t *testing.T) {
	makeCommand := func(ctx context.Context) *exec.Cmd {
		return exec.CommandContext(ctx, os.Args[0], "-test.run=^TestVolumeHelperProcess$", "--", "volume-helper")
	}
	started := time.Now()
	key, e := readVolumeCommand(context.Background(), 2, makeCommand)
	if e != nil || key != "down" || time.Since(started) > 3*time.Second {
		t.Fatal(key, e, "child not reaped")
	}
	if _, e = readVolumeCommand(context.Background(), 21, makeCommand); e == nil {
		t.Fatal("unbounded wait accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = readVolumeCommand(ctx, 1, makeCommand); e == nil {
		t.Fatal("cancelled request accepted")
	}
}
