// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bufio"
	"context"
	"errors"
	"os/exec"
	"strings"
	"time"
)

// Only DOWN events count. UP and held-key repeats cannot confirm another menu.
func volumeEvent(line string) string {
	fields := strings.Fields(line)
	if len(fields) < 3 {
		return ""
	}
	f := fields[len(fields)-3:]
	if (f[0] != "EV_KEY" && f[0] != "0001") || (f[2] != "DOWN" && f[2] != "00000001") {
		return ""
	}
	switch f[1] {
	case "KEY_VOLUMEUP", "0073":
		return "up"
	case "KEY_VOLUMEDOWN", "0072":
		return "down"
	}
	return ""
}

// ReadVolumeKey owns one bounded getevent process. No disk event log, shell,
// FIFO, persistent listener or manager output descriptor is retained. @author 雾晚
func ReadVolumeKey(parent context.Context, seconds int) (string, error) {
	return readVolumeCommand(parent, seconds, func(ctx context.Context) *exec.Cmd {
		return exec.CommandContext(ctx, "/system/bin/getevent", "-lq")
	})
}

func readVolumeCommand(parent context.Context, seconds int, command func(context.Context) *exec.Cmd) (string, error) {
	if e := parent.Err(); e != nil {
		return "", e
	}
	if seconds < 1 || seconds > 20 {
		return "", errors.New("arguments_invalid")
	}
	ctx, cancel := context.WithTimeout(parent, time.Duration(seconds)*time.Second)
	defer cancel()
	cmd := command(ctx)
	parentDeathSignal(cmd) // An installer/CLI SIGKILL must not orphan getevent.
	pipe, e := cmd.StdoutPipe()
	if e != nil {
		return "unavailable", nil
	}
	defer pipe.Close()
	if e = cmd.Start(); e != nil {
		if parent.Err() != nil {
			return "", parent.Err()
		}
		return "unavailable", nil
	}
	events := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		scanner := bufio.NewScanner(pipe)
		scanner.Buffer(make([]byte, 4096), 64<<10)
		for scanner.Scan() {
			if key := volumeEvent(scanner.Text()); key != "" {
				select {
				case events <- key:
				default:
				}
				return
			}
		}
	}()
	key := "unavailable"
	select {
	case key = <-events:
	case <-done:
		select {
		case key = <-events:
		default:
		}
	case <-ctx.Done():
		key = "timeout"
	}
	cancel()
	_ = cmd.Wait()
	_ = pipe.Close()
	<-done
	if parent.Err() != nil {
		return "", parent.Err()
	}
	return key, nil
}
