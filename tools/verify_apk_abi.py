# @author 雾晚
"""Verify the public artifact's exact ABI, compressed JNI payload and ARM64 ELF."""
import struct
import sys
import zipfile

for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as apk:
        native = [i for i in apk.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        assert {i.filename.split("/")[1] for i in native} == {"arm64-v8a"}, "unexpected public ABI"
        names = {i.filename for i in native}
        assert {"lib/arm64-v8a/libgojni.so", "lib/arm64-v8a/librootbox.so"} <= names
        for info in native:
            assert info.compress_type == zipfile.ZIP_DEFLATED, "JNI must remain compressed for extracted Root loading"
            with apk.open(info) as lib:
                header = lib.read(20)
            assert header[:4] == b"\x7fELF" and header[4] == 2 and header[5] == 1
            assert struct.unpack_from("<H", header, 18)[0] == 183, "native library is not ARM64"
    print(f"Verified ARM64-only compressed native payload: {path}")
