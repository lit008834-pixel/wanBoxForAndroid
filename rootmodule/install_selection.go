// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// Room data stays App-owned. The installer records confirmed intent; the App
// backs up and applies both storage domains before acknowledging this token.
type InstallDataSelection struct {
	ID   string `json:"id"`
	Mode string `json:"mode"`
}

type installChoice struct {
	Mode   string `json:"mode"`
	Digest string `json:"digest"`
	ID     string `json:"id"`
}

// Kept in manager staging so standard reboot activation retains the choice if
// hot handoff times out or the phone reboots before the detached worker finishes.
func (r *Runtime) writeInstallChoice(mode, digest string) error {
	if !validInstallMode(mode) || !revisionName.MatchString(digest) {
		return errors.New("arguments_invalid")
	}
	pending, e := r.installSelection()
	if e != nil {
		return e
	}
	if pending != nil {
		if mode != "preserve" && mode != pending.Mode {
			return errors.New("install_data_selection_changed")
		}
		// Installing recovery code must retain the original App journal token.
		return jsonWrite(filepath.Join(r.ModuleDir, "install-choice.json"), installChoice{pending.Mode, digest, pending.ID})
	}
	var token [16]byte
	if _, e := rand.Read(token[:]); e != nil {
		return errors.New("install_data_selection_failed")
	}
	return jsonWrite(filepath.Join(r.ModuleDir, "install-choice.json"), installChoice{mode, digest, hex.EncodeToString(token[:])})
}

func (r *Runtime) readInstallChoice() (*installChoice, error) {
	path := filepath.Join(r.ModuleDir, "install-choice.json")
	info, e := os.Lstat(path)
	if os.IsNotExist(e) {
		return nil, nil
	}
	if e != nil || !info.Mode().IsRegular() {
		return nil, errors.New("install_choice_invalid")
	}
	var choice installChoice
	if e = readJSON(path, &choice, 4096); e != nil || !validInstallMode(choice.Mode) || !revisionName.MatchString(choice.Digest) || !validSelectionID(choice.ID) {
		return nil, errors.New("install_choice_invalid")
	}
	if digest, e := manifestDigest(r.ModuleDir); e != nil || digest != choice.Digest {
		return nil, errors.New("install_choice_changed")
	}
	return &choice, nil
}

func (r *Runtime) consumeBootInstallChoice() error {
	return r.withDataLock(func() error {
		choice, e := r.readInstallChoice()
		if e != nil {
			return e
		}
		if choice == nil {
			return nil
		}
		if choice.Mode != "preserve" {
			pending, e := r.installSelection()
			if e != nil {
				return e
			}
			if pending == nil {
				if e = r.queueInstallSelectionID(choice.Mode, choice.ID); e != nil {
					return e
				}
			} else if pending.Mode != choice.Mode || pending.ID != choice.ID {
				return errors.New("install_data_selection_changed")
			}
		}
		if e = os.Remove(filepath.Join(r.ModuleDir, "install-choice.json")); e != nil {
			return e
		}
		return syncDir(r.ModuleDir)
	})
}

func validInstallMode(mode string) bool {
	return mode == "preserve" || mode == "fresh" || mode == "nodes"
}
func validSelectionID(id string) bool {
	return len(id) == 32 && strings.Trim(id, "0123456789abcdef") == ""
}

func (r *Runtime) installSelection() (*InstallDataSelection, error) {
	info, e := os.Lstat(r.path("install-data.json"))
	if os.IsNotExist(e) {
		return nil, nil
	}
	if e != nil || !info.Mode().IsRegular() {
		return nil, errors.New("install_data_selection_invalid")
	}
	var selection InstallDataSelection
	if e = readJSON(r.path("install-data.json"), &selection, 4096); e != nil ||
		!validSelectionID(selection.ID) || (selection.Mode != "fresh" && selection.Mode != "nodes") {
		return nil, errors.New("install_data_selection_invalid")
	}
	return &selection, nil
}

// Called under the activation lock after verified code switch and core stop.
// This only journals intent: it never clears the App or module's user data.
func (r *Runtime) queueInstallSelection(mode string) error {
	var token [16]byte
	if _, e := rand.Read(token[:]); e != nil {
		return errors.New("install_data_selection_failed")
	}
	return r.queueInstallSelectionID(mode, hex.EncodeToString(token[:]))
}

func (r *Runtime) queueInstallSelectionID(mode, id string) error {
	if mode == "preserve" {
		return nil
	}
	if (mode != "fresh" && mode != "nodes") || !validSelectionID(id) {
		return errors.New("arguments_invalid")
	}
	if previous, e := r.installSelection(); e != nil {
		return e
	} else if previous != nil {
		if previous.ID == id && previous.Mode == mode {
			return nil
		}
		return errors.New("install_data_update_pending")
	}
	var completed InstallDataSelection
	if e := readJSON(r.path("install-data-completed.json"), &completed, 4096); e == nil {
		if !validSelectionID(completed.ID) || (completed.Mode != "fresh" && completed.Mode != "nodes") {
			return errors.New("install_data_selection_invalid")
		}
		if completed.ID == id {
			return nil
		} // Never re-apply a completed hot update on reboot.
	} else if !os.IsNotExist(e) {
		return errors.New("install_data_selection_invalid")
	}
	return jsonWrite(r.path("install-data.json"), InstallDataSelection{id, mode})
}

// Code updates may carry an unfinished installer selection, but may neither
// change its intent nor race an active App/module data transaction. @author 雾晚
func (r *Runtime) withInstallLock(mode string, fn func(*InstallDataSelection) error) error {
	return r.withDataLock(func() error {
		if _, e := os.Lstat(r.path("data-reset.json")); e == nil {
			return errors.New("data_update_pending")
		} else if !os.IsNotExist(e) {
			return e
		}
		pending, e := r.installSelection()
		if e != nil {
			return e
		}
		if pending != nil && mode != "preserve" && mode != pending.Mode {
			return errors.New("install_data_selection_changed")
		}
		return fn(pending)
	})
}

// An old or forged acknowledgement cannot clear a newer selection. @author 雾晚
func (r *Runtime) FinishInstallSelection(id string) error {
	if !validSelectionID(id) {
		return errors.New("arguments_invalid")
	}
	return r.withDataLock(func() error {
		selection, e := r.installSelection()
		if e != nil {
			return e
		}
		if selection == nil {
			return nil
		} // Idempotent after a committed App journal.
		if selection.ID != id {
			return errors.New("install_data_selection_changed")
		}
		if _, e = os.Lstat(r.path("data-reset.json")); !os.IsNotExist(e) {
			return errors.New("data_update_pending")
		}
		if e = jsonWrite(r.path("install-data-completed.json"), selection); e != nil {
			return e
		}
		if e = os.Remove(r.path("install-data.json")); e != nil {
			return e
		}
		return syncDir(r.Root)
	})
}
