# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package only explicit ARM64 Android ELF inputs; no stale/generated binaries tracked."""
import argparse
import pathlib
import struct
import zipfile

def check_elf(path, abi):
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4:6] != b'\x02\x01':
        raise ValueError('expected 64-bit little-endian ELF')
    if abi != 'arm64-v8a' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('ABI mismatch')
    if struct.unpack_from('<H', data, 16)[0] != 3:
        raise ValueError('Android executable must be PIE')
    return data

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=['arm64-v8a'], required=True)
    for name in ('core', 'cli', 'output'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    core, cli = check_elf(args.core, args.abi), check_elf(args.cli, args.abi)
    if b'core_config_invalid' not in core:
        raise ValueError('rootbox predates module validation; rebuild native core first')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as out:
        for item in sorted((pathlib.Path(__file__).parent / 'package').iterdir()):
            out.writestr(item.name, item.read_bytes().replace(b"\r\n", b"\n"))
        out.write(pathlib.Path(__file__).parent.parent / 'LICENSE', 'LICENSE')
        out.write(pathlib.Path(__file__).parent.parent / 'libcore' / 'LICENSE', 'LIBCORE-LICENSE')
        out.writestr('bin/rootbox', core)
        out.writestr('bin/wanboxctl', cli)

if __name__ == '__main__':
    main()
