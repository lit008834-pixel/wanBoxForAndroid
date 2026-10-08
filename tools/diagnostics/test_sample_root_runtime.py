# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from sample_root_runtime import sample


class RuntimeSampleTest(unittest.TestCase):
    def response(self, start="99"):
        state = {"ok": True, "state": {"phase": "connected", "profileName": "fake-private-name",
                  "supervisor": {"pid": 7, "start": "99"}}}
        fields = ["S"] + ["0"] * 19
        fields[11], fields[12], fields[19] = "10", "20", start
        return [SimpleNamespace(stdout=json.dumps(state)),
                SimpleNamespace(stdout="7 (core with spaces) " + " ".join(fields)),
                SimpleNamespace(stdout="VmRSS:\t123 kB\nThreads:\t2\nName:\tprivate\n")]

    def test_collects_only_metrics_and_checks_root_process_start(self):
        with patch("sample_root_runtime.subprocess.run", side_effect=self.response()) as run:
            result = sample("adb", "synthetic-device")
        self.assertEqual(30, result["processes"]["supervisor"]["cpu_ticks"])
        self.assertEqual("123 kB", result["processes"]["supervisor"]["VmRSS"])
        self.assertNotIn("private", json.dumps(result))
        self.assertEqual(3, run.call_count)
        for call in run.call_args_list:
            self.assertIn(call.args[0][-1].split()[0], ("cat", "/data/adb/modules/wanbox/bin/wanboxctl"))

    def test_reused_pid_is_rejected_before_reading_memory(self):
        with patch("sample_root_runtime.subprocess.run", side_effect=self.response(start="100")) as run:
            with self.assertRaisesRegex(RuntimeError, "process changed"):
                sample("adb", "synthetic-device")
        self.assertEqual(2, run.call_count)

    def test_failed_status_is_not_a_success_sample(self):
        with patch("sample_root_runtime.subprocess.run", return_value=SimpleNamespace(stdout='{"ok":false}')):
            with self.assertRaisesRegex(RuntimeError, "status failed"):
                sample("adb", "synthetic-device")


if __name__ == "__main__":
    unittest.main()
