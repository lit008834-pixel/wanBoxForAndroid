// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

const installedModule = "/data/adb/modules/wanbox"
const stagedModule = "/data/adb/modules_update/wanbox"

func manifestDigest(dir string) (string, error) {
	path := filepath.Join(dir, "package-manifest.json")
	info, e := os.Lstat(path)
	if e != nil || !info.Mode().IsRegular() || info.Size() > 64<<10 {
		return "", errors.New("package_manifest_invalid")
	}
	f, e := os.Open(path)
	if e != nil {
		return "", errors.New("package_manifest_invalid")
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, (64<<10)+1))
	if e != nil || len(b) > 64<<10 {
		return "", errors.New("package_manifest_invalid")
	}
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:]), nil
}

func stagingDirectory(dir string) error {
	if dir != stagedModule {
		return errors.New("install_requires_manager_staging")
	}
	resolved, e := filepath.EvalSymlinks(dir)
	if e != nil || resolved != dir {
		return errors.New("unsafe_directory")
	}
	return nil
}

// ScheduleInstall detaches a finite completion task from the installer. The
// manager still owns staging until its process exits and writes the update flag.
// No code switch or network stop takes place inside customize.sh. @author 雾晚
func (r *Runtime) ScheduleInstall(ctx context.Context, installerPID int) error {
	return r.ScheduleInstallMode(ctx, installerPID, "preserve")
}

func (r *Runtime) ScheduleInstallMode(ctx context.Context, installerPID int, mode string) error {
	if !validInstallMode(mode) {
		return errors.New("arguments_invalid")
	}
	if e := stagingDirectory(r.ModuleDir); e != nil {
		return e
	}
	return r.withLock(func() error {
		installer, e := Identity(installerPID)
		if e != nil {
			return errors.New("installer_identity_invalid")
		}
		manifest, e := packageManifest(r.ModuleDir)
		if e != nil {
			return e
		}
		if _, ok := manifest.Files["customize.sh"]; !ok {
			return errors.New("package_file_missing")
		}
		for name, file := range manifest.Files {
			if e = verifyPackageFile(r.ModuleDir, name, file, ""); e != nil {
				return e
			}
		}
		digest, e := manifestDigest(r.ModuleDir)
		if e != nil {
			return e
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if e = r.writeInstallChoice(mode, digest); e != nil {
			return e
		}
		cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), "__internal", "finish-install",
			strconv.Itoa(installer.PID), installer.Start, digest, mode)
		null, e := os.OpenFile(os.DevNull, os.O_RDWR, 0)
		if e != nil {
			return errors.New("install_completion_start_failed")
		}
		defer null.Close()
		// Real OS descriptors, not exec-managed copy pipes: completion must
		// survive the scheduling CLI/installer closing all their descriptors.
		cmd.Stdin, cmd.Stdout, cmd.Stderr = null, null, null
		detach(cmd)
		if e = cmd.Start(); e != nil {
			return errors.New("install_completion_start_failed")
		}
		_ = cmd.Process.Release()
		r.recordEvent("module_update_scheduled")
		return nil
	})
}

func updateMarker(target string) (bool, error) {
	info, e := os.Lstat(filepath.Join(target, "update"))
	if os.IsNotExist(e) {
		return false, nil
	}
	if e != nil || !info.Mode().IsRegular() {
		return false, errors.New("install_update_marker_invalid")
	}
	return true, nil
}

func sleepInstall(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// Wait without a lifecycle lock: an active core and App commands keep working.
func waitInstaller(ctx context.Context, alive func() bool, marker func() (bool, error), poll, settle time.Duration) error {
	for {
		if e := ctx.Err(); e != nil {
			return e
		}
		ready, e := marker()
		if e != nil {
			return e
		}
		if !alive() && ready {
			if e = sleepInstall(ctx, settle); e != nil {
				return e
			}
			ready, e = marker()
			if e != nil {
				return e
			}
			if !alive() && ready {
				return nil
			}
		}
		if e = sleepInstall(ctx, poll); e != nil {
			return e
		}
	}
}

// FinishInstall follows the manager's post-install handoff rather than racing
// its module.prop/update writes. Failure leaves staging for standard reboot
// activation; successful switching retires staging to prevent a second update.
// @author 雾晚
func (r *Runtime) FinishInstall(ctx context.Context, installerPID int, installerStart, digest string) error {
	return r.FinishInstallMode(ctx, installerPID, installerStart, digest, "preserve")
}

func (r *Runtime) FinishInstallMode(ctx context.Context, installerPID int, installerStart, digest, mode string) error {
	if !validInstallMode(mode) {
		return errors.New("arguments_invalid")
	}
	if e := stagingDirectory(r.ModuleDir); e != nil {
		return e
	}
	choice, e := r.readInstallChoice()
	if e != nil {
		return e
	}
	if choice == nil || choice.Mode != mode || choice.Digest != digest {
		return errors.New("install_choice_changed")
	}
	return r.completeInstallMode(ctx, func(waiting context.Context) error {
		return waitInstaller(waiting, func() bool {
			identity, err := Identity(installerPID)
			return err == nil && identity.Start == installerStart
		}, func() (bool, error) { return updateMarker(installedModule) }, time.Second, 3*time.Second)
	}, installedModule, digest, mode)
}

// Testable orchestration; only FinishInstall supplies production paths/identity.
func (r *Runtime) completeInstall(ctx context.Context, wait func(context.Context) error, target, digest string) error {
	return r.completeInstallMode(ctx, wait, target, digest, "preserve")
}

func (r *Runtime) completeInstallMode(ctx context.Context, wait func(context.Context) error, target, digest, mode string) error {
	waiting, cancel := context.WithTimeout(ctx, time.Minute)
	e := wait(waiting)
	cancel()
	if e != nil {
		r.installEvent("module_hot_update_deferred")
		return errors.New("installer_handoff_incomplete")
	}
	// Re-check identity of the package after the manager finishes. A subsequent
	// installation must not accidentally be completed by an older waiting task.
	if actual, e := manifestDigest(r.ModuleDir); e != nil || actual != digest {
		return errors.New("install_stage_changed")
	}
	operation, stop := context.WithTimeout(ctx, 2*time.Minute)
	defer stop()
	if e = r.activateMode(operation, target, true, mode); e != nil {
		r.installEvent("module_hot_update_deferred")
		return e
	}
	if actual, e := manifestDigest(r.ModuleDir); e != nil || actual != digest {
		r.installEvent("module_stage_cleanup_failed")
		return errors.New("install_stage_changed")
	}
	if e = retireStaging(r.ModuleDir); e != nil {
		r.installEvent("module_stage_cleanup_failed")
		return e
	}
	return nil
}

func (r *Runtime) installEvent(code string) {
	_ = r.withDataLock(func() error { r.recordEvent(code); return nil })
}

func retireStaging(dir string) error {
	parent := filepath.Dir(dir)
	retired, e := os.MkdirTemp(parent, ".wanbox-applied-")
	if e != nil {
		return errors.New("install_stage_cleanup_failed")
	}
	if e = os.Remove(retired); e != nil {
		return errors.New("install_stage_cleanup_failed")
	}
	if e = os.Rename(dir, retired); e != nil {
		return errors.New("install_stage_cleanup_failed")
	}
	if e = syncDir(parent); e != nil {
		return errors.New("install_stage_cleanup_failed")
	}
	if e = os.RemoveAll(retired); e != nil {
		return errors.New("install_stage_cleanup_failed")
	}
	return nil
}
