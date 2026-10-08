# @author 雾晚
import pathlib
import struct
import tempfile
import unittest
import subprocess
import sys
import zipfile
import json
import hashlib
from bundle_manager import bundle
from pack import check_elf

class PackageTest(unittest.TestCase):
    def test_abi_and_pie_are_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'core'
            data = bytearray(64)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, 3, 183)
            path.write_bytes(data)
            self.assertEqual(bytes(data), check_elf(path, 'arm64-v8a'))
            struct.pack_into('<H', data, 18, 62)
            path.write_bytes(data)
            with self.assertRaises(ValueError): check_elf(path, 'arm64-v8a')
            data[:4] = b'nope'; path.write_bytes(data)
            with self.assertRaises(ValueError): check_elf(path, 'arm64-v8a')

    def test_scripts_are_module_owned_and_do_not_flush_other_routes(self):
        source = pathlib.Path(__file__).parent / 'package'
        service = (source / 'service.sh').read_text()
        self.assertIn('__internal boot', service)
        self.assertNotIn('/data/adb/service.d', service)
        for file in source.glob('*.sh'):
            text = file.read_text()
            self.assertNotIn('iptables -F', text)
            self.assertNotIn('ip rule flush', text)
            self.assertNotIn('rm -rf /data/adb/wanbox', text)
        self.assertIn('ARCH" = arm64', (source / 'customize.sh').read_text())

    def test_actual_packager_preserves_scripts_licenses_and_non_applied_example(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            data = bytearray(64)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, 3, 183)
            core, cli, output = tmp / 'core', tmp / 'cli', tmp / 'out.zip'
            core.write_bytes(data + b'core_config_invalid'); cli.write_bytes(data)
            subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('pack.py')),
                '--abi', 'arm64-v8a', '--core', str(core), '--cli', str(cli), '--output', str(output)], check=True)
            with zipfile.ZipFile(output) as z:
                self.assertIsNone(z.testzip())
                for name in ('LICENSE', 'LIBCORE-LICENSE', 'README.md', 'example.snapshot.json', 'bin/rootbox', 'bin/wanboxctl'):
                    self.assertIn(name, z.namelist())
                for name in ('service.sh', 'customize.sh', 'uninstall.sh'):
                    self.assertNotIn(b'\r', z.read(name))
                    self.assertNotIn(b'example.snapshot.json', z.read(name))
                m = json.loads(z.read('package-manifest.json'))
                self.assertFalse(m['withManager'])
                for name, spec in m['files'].items():
                    data = z.read(name)
                    self.assertEqual({'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}, spec)
            apk, with_apk = tmp / 'manager.apk', tmp / 'with-manager.zip'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('AndroidManifest.xml', b'fixture')
                z.writestr('lib/arm64-v8a/libfixture.so', data)
            bundle(output, apk, with_apk)
            with zipfile.ZipFile(with_apk) as z:
                self.assertTrue(json.loads(z.read('package-manifest.json'))['withManager'])
                self.assertEqual(apk.read_bytes(), z.read('manager.apk'))
            with zipfile.ZipFile(output, 'a') as z: z.writestr('../escape', b'x')
            with self.assertRaises(ValueError): bundle(output, apk, with_apk)

if __name__ == '__main__': unittest.main()
