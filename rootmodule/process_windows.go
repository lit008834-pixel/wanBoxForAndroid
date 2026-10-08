//go:build windows

// @author 雾晚
// Windows runs portable contract/transaction tests only, never Root lifecycle.
package module

import (
	"errors"
	"os"
	"os/exec"
	"sync"
)

var windowsLock sync.Mutex

func fileLock(string) (func(), error) { windowsLock.Lock(); return windowsLock.Unlock, nil }
func syncDir(string) error            { return nil }
func Identity(int) (ProcessIdentity, error) {
	return ProcessIdentity{}, errors.New("root_requires_android_or_linux")
}
func SameProcess(ProcessIdentity) bool     { return false }
func terminate(ProcessIdentity) error      { return errors.New("root_requires_android_or_linux") }
func detach(*exec.Cmd)                     {}
func IndependentCgroup() error             { return os.ErrPermission }
func parentDeathSignal(*exec.Cmd)          {}
func forceTerminate(ProcessIdentity) error { return errors.New("root_requires_android_or_linux") }
