# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only Root runtime samples; never save configs, node names or logcat."""
import argparse
import json
import pathlib
import subprocess
import time


def sample(adb, serial):
    def shell(command):
        result = subprocess.run([adb, "-s", serial, "shell", "su", "-c", command],
                                check=True, capture_output=True, text=True, timeout=15)
        return result.stdout

    response = json.loads(shell("/data/adb/modules/wanbox/bin/wanboxctl status"))
    if not response.get("ok"):
        raise RuntimeError("module status failed")
    state = response["state"]
    result = {"time": time.time(), "phase": state["phase"], "processes": {}}
    for name in ("supervisor", "core"):
        identity = state.get(name, {})
        pid = identity.get("pid", 0)
        if not isinstance(pid, int) or pid <= 0:
            continue
        # Match the module's PID start token before collecting unrelated data.
        stat = shell(f"cat /proc/{pid}/stat")
        fields = stat[stat.rfind(")") + 2:].split()
        if fields[19] != str(identity.get("start")):
            raise RuntimeError("process changed during sampling")
        status = shell(f"cat /proc/{pid}/status")
        allowed = ("VmRSS", "VmHWM", "Threads", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches")
        info = {key: value.strip() for line in status.splitlines() if ":" in line
                for key, value in [line.split(":", 1)] if key in allowed}
        info.update(pid=pid, start=fields[19], cpu_ticks=int(fields[11]) + int(fields[12]))
        result["processes"][name] = info
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--samples", type=int, default=12)
    parser.add_argument("--interval", type=float, default=10)
    args = parser.parse_args()
    if not 1 <= args.samples <= 360 or not 1 <= args.interval <= 300:
        parser.error("samples must be 1..360 and interval 1..300 seconds")
    # Refuse to overwrite any previous measurements.
    with args.output.open("x", encoding="utf-8") as output:
        for index in range(args.samples):
            output.write(json.dumps(sample(args.adb, args.serial), ensure_ascii=True) + "\n")
            output.flush()
            if index + 1 < args.samples:
                time.sleep(args.interval)


if __name__ == "__main__":
    main()
