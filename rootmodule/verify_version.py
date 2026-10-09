# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reject mismatched published module versions before creating a Release. @author 雾晚"""
import pathlib
import sys
import zipfile
from pack import module_properties

def verify(path):
    root = pathlib.Path(__file__).parent
    expected = module_properties((root / 'package/module.prop').read_bytes(),
        (root.parent / 'nb4a.properties').read_text(encoding='utf-8'))
    with zipfile.ZipFile(path) as bundle:
        entries = [i for i in bundle.infolist() if i.filename == 'module.prop']
        if len(entries) != 1 or entries[0].file_size > 4096:
            raise ValueError('invalid module metadata')
        if bundle.read(entries[0]) != expected: raise ValueError('module version/identity mismatch')

if __name__ == '__main__':
    for filename in sys.argv[1:]:
        verify(filename)
        print('Verified module version and identity:', filename)
