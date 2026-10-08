// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type PackageFile struct {
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}
type PackageManifest struct {
	SchemaVersion int                    `json:"schemaVersion"`
	WithManager   bool                   `json:"withManager"`
	Files         map[string]PackageFile `json:"files"`
}

var packageFiles = map[string]bool{
	"module.prop": true, "customize.sh": true, "service.sh": true, "uninstall.sh": true,
	"README.md": true, "example.snapshot.json": true, "LICENSE": true, "LIBCORE-LICENSE": true,
	"bin/rootbox": true, "bin/wanboxctl": true, "manager.apk": true,
}

func packageManifest(dir string) (PackageManifest, error) {
	var m PackageManifest
	if e := readJSON(filepath.Join(dir, "package-manifest.json"), &m, 64<<10); e != nil || m.SchemaVersion != 1 || len(m.Files) > len(packageFiles) {
		return m, errors.New("package_manifest_invalid")
	}
	for _, name := range []string{"module.prop", "service.sh", "uninstall.sh", "customize.sh", "bin/rootbox", "bin/wanboxctl", "LICENSE", "LIBCORE-LICENSE"} {
		if _, ok := m.Files[name]; !ok {
			return m, errors.New("package_file_missing")
		}
	}
	_, hasAPK := m.Files["manager.apk"]
	if hasAPK != m.WithManager {
		return m, errors.New("package_manifest_invalid")
	}
	var total int64
	for name, file := range m.Files {
		if !packageFiles[name] || file.Size <= 0 || file.Size > 128<<20 || !revisionName.MatchString(file.SHA256) {
			return m, errors.New("package_manifest_invalid")
		}
		total += file.Size
		if total > 256<<20 {
			return m, errors.New("package_too_large")
		}
	}
	return m, nil
}
func verifyPackageFile(dir, name string, want PackageFile, destination string) error {
	for parent := filepath.Dir(filepath.Join(dir, name)); parent != filepath.Clean(dir); parent = filepath.Dir(parent) {
		info, e := os.Lstat(parent)
		if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe_directory")
		}
	}
	path := filepath.Join(dir, name)
	info, e := os.Lstat(path)
	if e != nil || !info.Mode().IsRegular() || info.Size() != want.Size {
		return errors.New("package_file_invalid")
	}
	input, e := os.Open(path)
	if e != nil {
		return e
	}
	defer input.Close()
	hash := sha256.New()
	var output *os.File
	writer := io.Writer(hash)
	if destination != "" {
		if e = privateDirectory(filepath.Dir(destination)); e != nil {
			return e
		}
		mode := os.FileMode(0600)
		if strings.HasPrefix(name, "bin/") || strings.HasSuffix(name, ".sh") {
			mode = 0700
		}
		output, e = os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
		if e != nil {
			return e
		}
		defer output.Close()
		writer = io.MultiWriter(hash, output)
	}
	count, e := io.Copy(writer, io.LimitReader(input, want.Size+1))
	if e != nil || count != want.Size || hex.EncodeToString(hash.Sum(nil)) != want.SHA256 {
		return errors.New("package_checksum_failed")
	}
	if output != nil {
		return output.Sync()
	}
	return nil
}
func checkPackageELF(path string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	header := make([]byte, 64)
	if _, e = io.ReadFull(f, header); e != nil || string(header[:6]) != "\x7fELF\x02\x01" || binary.LittleEndian.Uint16(header[16:18]) != 3 || binary.LittleEndian.Uint16(header[18:20]) != 183 {
		return errors.New("package_abi_invalid")
	}
	return nil
}

// Activate copies a verified manager staging package and atomically switches code.
// Persistent snapshots are untouched; an active core is drained and restarted.
// Target is supplied ONLY by the fixed CLI installer branch, never App input.
// @author 雾晚
func (r *Runtime) Activate(ctx context.Context, target string) error {
	return r.withLock(func() error {
		if filepath.Clean(target) == filepath.Clean(r.ModuleDir) {
			return errors.New("install_requires_staging")
		}
		manifest, e := packageManifest(r.ModuleDir)
		if e != nil {
			return e
		}
		parent := filepath.Dir(target)
		// Do not chmod the manager's global modules directory.
		info, e := os.Lstat(parent)
		if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe_directory")
		}
		if info, e := os.Lstat(target); e == nil {
			if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
				return errors.New("unsafe_directory")
			}
		} else if !os.IsNotExist(e) {
			return e
		}
		stage, e := os.MkdirTemp(parent, ".wanbox-install-")
		if e != nil {
			return e
		}
		defer os.RemoveAll(stage)
		for name, file := range manifest.Files {
			if e = verifyPackageFile(r.ModuleDir, name, file, filepath.Join(stage, name)); e != nil {
				return e
			}
		}
		if e = jsonWrite(filepath.Join(stage, "package-manifest.json"), manifest); e != nil {
			return e
		}
		prop, e := os.ReadFile(filepath.Join(stage, "module.prop"))
		if e != nil || !strings.Contains("\n"+string(prop), "\nid=wanbox\n") {
			return errors.New("package_identity_invalid")
		}
		for _, name := range []string{"rootbox", "wanboxctl"} {
			if e = checkPackageELF(filepath.Join(stage, "bin", name)); e != nil {
				return e
			}
		}
		old := New(r.Root, target)
		_, snapshot, e := old.Current()
		if e != nil {
			return e
		}
		if snapshot.SchemaVersion != 0 {
			check := New(r.Root, stage)
			// Recreate a private validation generation: do not modify a running one's resources.
			if r.installValidate != nil {
				check.Validate = r.installValidate
			}
			if _, e = check.Stage(ctx, snapshot); e != nil {
				return e
			}
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if _, e = os.Stat(filepath.Join(target, "remove")); e == nil {
			return errors.New("module_removing")
		}
		disabled := false
		if _, e = os.Stat(filepath.Join(target, "disable")); e == nil {
			disabled = true
			if e = AtomicWrite(filepath.Join(stage, "disable"), nil, 0600); e != nil {
				return e
			}
		}
		wasRunning := SameProcess(old.Status().Supervisor) || SameProcess(old.Status().Core)
		if r.installRunning != nil {
			wasRunning = r.installRunning()
		}
		start := old.start
		if r.installStart != nil {
			start = r.installStart
		}
		if e = old.stop(ctx); e != nil {
			return e
		}
		backup, e := os.MkdirTemp(parent, ".wanbox-previous-")
		if e != nil {
			return e
		}
		if e = os.Remove(backup); e != nil {
			return e
		}
		hadOld := false
		if _, e = os.Stat(target); e == nil {
			if e = os.Rename(target, backup); e != nil {
				return errors.New("module_replace_failed")
			}
			hadOld = true
		}
		rollback := func() error {
			if e = old.stop(context.Background()); e != nil {
				return errors.New("install_rollback_cleanup_failed")
			}
			if e = os.RemoveAll(target); e != nil {
				return errors.New("install_rollback_failed")
			}
			if hadOld {
				if e = os.Rename(backup, target); e != nil {
					return errors.New("install_rollback_failed")
				}
			}
			if wasRunning && hadOld {
				if e = start(context.Background()); e != nil {
					return errors.New("install_rollback_start_failed")
				}
			}
			return errors.New("module_update_failed_rolled_back")
		}
		if e = os.Rename(stage, target); e != nil {
			if hadOld {
				if restore := os.Rename(backup, target); restore != nil {
					return errors.New("install_rollback_failed")
				}
			}
			if wasRunning && hadOld {
				_ = start(context.Background())
			}
			return errors.New("module_replace_failed")
		}
		if e = syncDir(parent); e != nil {
			return rollback()
		}
		if wasRunning && !disabled {
			if e = start(ctx); e != nil {
				return rollback()
			}
		}
		if hadOld {
			if e = os.RemoveAll(backup); e != nil {
				return errors.New("module_backup_cleanup_failed")
			}
		}
		r.recordEvent("module_updated")
		return nil
	})
}

// InstallManager uses Android's public package shell command with an APK stream;
// a separate optional step never rolls back a successfully activated module.
// @author 雾晚
func (r *Runtime) InstallManager(ctx context.Context) error {
	m, e := packageManifest(r.ModuleDir)
	if e != nil {
		return e
	}
	if !m.WithManager {
		return errors.New("manager_apk_missing")
	}
	file := m.Files["manager.apk"]
	if e = verifyPackageFile(r.ModuleDir, "manager.apk", file, ""); e != nil {
		return e
	}
	f, e := os.Open(filepath.Join(r.ModuleDir, "manager.apk"))
	if e != nil {
		return e
	}
	defer f.Close()
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "/system/bin/cmd", "package", "install", "-r", "-S", strconv.FormatInt(file.Size, 10))
	cmd.Stdin = f
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if e = cmd.Run(); e != nil {
		return errors.New("manager_apk_install_failed")
	}
	return nil
}
