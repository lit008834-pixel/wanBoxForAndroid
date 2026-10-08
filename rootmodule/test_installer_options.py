# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Execute the actual POSIX installer menus with controlled volume DOWN events."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SHELL = os.environ.get("ROOT_MODULE_TEST_SHELL") or shutil.which("sh")

@unittest.skipUnless(SHELL, "POSIX shell is required to execute installer menus")
class InstallerOptionsTest(unittest.TestCase):
    def choose(self, events, with_apk=True, installed=False):
        with tempfile.TemporaryDirectory() as temp:
            temp = pathlib.Path(temp)
            if with_apk:
                (temp / "manager.apk").write_bytes(b"fixture")
            events_file = temp / "events"
            events_file.write_text("\n".join(events) + "\n")
            script = pathlib.Path(__file__).parent / "package" / "installer-options.sh"
            command = r'''
MODPATH=$1
exec 7<"$2"
. "$3" || exit 90
ui_print() { printf '%s\n' "$*"; }
wanbox_volume_key() {
  local key
  IFS= read -r key <&7 || key=timeout
  printf '%s\n' "$key"
}
INSTALLED=$4
wanbox_manager_installed() { [ "$INSTALLED" = installed ]; }
if wanbox_install_options; then
 printf 'RESULT=%s,%s\n' "$WANBOX_DATA_MODE" "$WANBOX_APK_MODE"
else
 printf 'RESULT=cancelled\n'
fi
'''
            result = subprocess.run([SHELL, "-c", command, "installer-test", temp.as_posix(),
                events_file.as_posix(), script.resolve().as_posix(), "installed" if installed else "absent"],
                text=True, encoding="utf-8", capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout

    def test_default_preserve_and_install_or_update(self):
        self.assertIn("RESULT=preserve,install", self.choose(["timeout", "timeout"]))
        self.assertIn("覆盖更新已有管理 APK", self.choose(["down", "down"], installed=True))
        self.assertIn("安装管理 APK", self.choose(["down", "down"]))

    def test_up_cycles_down_confirms_and_apk_can_be_skipped(self):
        self.assertIn("RESULT=nodes,skip", self.choose(["up", "down", "down", "up", "down"]))
        self.assertIn("RESULT=fresh,install", self.choose(["up", "up", "down", "down", "down"]))
        self.assertIn("RESULT=preserve,install", self.choose(["up", "up", "up", "down", "down"]))

    def test_destructive_choices_need_second_down_and_never_time_out_to_confirmation(self):
        for events in (["up", "timeout"], ["up", "down", "timeout"], ["up", "up", "down", "up"], ["unavailable"]):
            self.assertIn("RESULT=cancelled", self.choose(events))

    def test_without_apk_never_attempts_install_and_still_allows_nodes_choice(self):
        result = self.choose(["up", "down", "down"], with_apk=False)
        self.assertIn("RESULT=nodes,skip", result)
        self.assertIn("手动更新配套管理 APK", result)

if __name__ == "__main__":
    unittest.main()
