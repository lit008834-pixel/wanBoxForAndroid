# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
import pathlib
import struct
import tempfile
import unittest
import zipfile

from verify_apk_abi import verify


class ManagerNativePayloadTest(unittest.TestCase):
    def apk(self, tmp, names, compression=zipfile.ZIP_DEFLATED):
        path = pathlib.Path(tmp) / "synthetic.apk"
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", header, 16, 3, 183)
        with zipfile.ZipFile(path, "w", compression=compression) as apk:
            for name in names:
                apk.writestr(name, header)
        return path

    def test_jni_and_plugin_library_remain_without_duplicate_module_core(self):
        with tempfile.TemporaryDirectory() as tmp:
            verify(self.apk(tmp, ["lib/arm64-v8a/libgojni.so", "lib/arm64-v8a/libplugin.so"]))

    def test_duplicate_core_missing_jni_extra_abi_and_uncompressed_payload_fail(self):
        cases = [(["lib/arm64-v8a/libgojni.so", "lib/arm64-v8a/librootbox.so"], zipfile.ZIP_DEFLATED),
                 (["lib/arm64-v8a/libplugin.so"], zipfile.ZIP_DEFLATED),
                 (["lib/arm64-v8a/libgojni.so", "lib/x86_64/libplugin.so"], zipfile.ZIP_DEFLATED),
                 (["lib/arm64-v8a/libgojni.so"], zipfile.ZIP_STORED)]
        for names, compression in cases:
            with self.subTest(names=names, compression=compression), tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(AssertionError):
                    verify(self.apk(tmp, names, compression))


if __name__ == "__main__":
    unittest.main()
