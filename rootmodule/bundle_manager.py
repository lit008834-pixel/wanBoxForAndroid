# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Add only the already signature/identity verified release APK to a verified module."""
import argparse
import hashlib
import json
import pathlib
import zipfile
from pack import manifest

ALLOWED = {'module.prop', 'service.sh', 'customize.sh', 'installer-options.sh', 'uninstall.sh', 'README.md',
 'example.snapshot.json', 'LICENSE', 'LIBCORE-LICENSE', 'bin/rootbox', 'bin/wanboxctl', 'package-manifest.json'}

def bundle(source, apk, output):
    if apk.stat().st_size > 128 * 1024 * 1024: raise ValueError('APK too large')
    with zipfile.ZipFile(source) as z:
        entries = z.infolist()
        if len(entries) != len(set(i.filename for i in entries)) or {i.filename for i in entries} != ALLOWED:
            raise ValueError('unexpected module entries')
        if any(i.file_size > 128 * 1024 * 1024 for i in entries) or sum(i.file_size for i in entries) > 256 * 1024 * 1024:
            raise ValueError('module too large')
        files = {i.filename: z.read(i) for i in entries}
    m = json.loads(files.pop('package-manifest.json'))
    if m['schemaVersion'] != 1 or m['withManager'] or set(m['files']) != set(files): raise ValueError('invalid manifest')
    for name, data in files.items():
        if m['files'][name] != {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}: raise ValueError('checksum mismatch')
    with zipfile.ZipFile(apk) as z:
        if 'AndroidManifest.xml' not in z.namelist(): raise ValueError('invalid APK')
        if not any(i.startswith('lib/arm64-v8a/') for i in z.namelist()): raise ValueError('missing ARM64 libraries')
        if any(i.startswith('lib/') and not i.startswith('lib/arm64-v8a/') for i in z.namelist()): raise ValueError('unexpected APK ABI')
    files['manager.apk'] = apk.read_bytes()
    files['package-manifest.json'] = manifest(files, True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items(): z.writestr(name, data)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('source', 'apk', 'output'): p.add_argument('--' + name, type=pathlib.Path, required=True)
    args = p.parse_args()
    bundle(args.source, args.apk, args.output)
