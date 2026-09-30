#!/usr/bin/env python3
# @author 雾晚
"""APK download/payload measurements; never infer runtime memory from ZIP size."""
import collections
import hashlib
import json
import sys
import zipfile
from pathlib import Path

def report(path):
    groups = collections.defaultdict(lambda: {'raw': 0, 'compressed': 0, 'entries': 0})
    libraries = []
    duplicates = collections.defaultdict(list)
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            kind = 'native' if name.startswith('lib/') else 'dex' if name.endswith('.dex') else 'assets' if name.startswith('assets/') else 'resources' if name.startswith('res/') or name == 'resources.arsc' else 'other'
            group = groups[kind]
            group['raw'] += entry.file_size
            group['compressed'] += entry.compress_size
            group['entries'] += 1
            if kind == 'native':
                libraries.append({'name': name, 'raw': entry.file_size, 'compressed': entry.compress_size})
            if kind == 'resources':
                duplicates[hashlib.sha256(archive.read(entry)).hexdigest()].append({'name': name, 'compressed': entry.compress_size})
    return {'file': path.name, 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'payload': dict(groups),
            'libraries': libraries, 'identical_resource_groups': [v for v in duplicates.values() if len(v) > 1]}

if __name__ == '__main__':
    print(json.dumps({'measurement': 'APK ZIP bytes only; not installed size or RSS/PSS',
                      'apks': [report(Path(p)) for p in sys.argv[1:]]}, ensure_ascii=False, indent=2))
