"""Read-only checks of APK native entries, ELF LOAD alignment and ZIP offsets.

Run from this project: python scripts/verify_pdfium_apk.py
This does not load native libraries, install the APK, or certify device behavior.
"""

from pathlib import Path
import hashlib
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PREBUILT = ROOT / "third_party/pdfiumandroid/prebuilt"
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}


def elf_load_alignments(data):
    assert data[:4] == b"\x7fELF" and data[5] == 1, "Expected little-endian ELF"
    if data[4] == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        alignment_offset, alignment_format = 48, "<Q"
    else:
        assert data[4] == 1, "Unsupported ELF class"
        offset = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        alignment_offset, alignment_format = 28, "<I"
    result = []
    for index in range(count):
        base = offset + index * entry_size
        if struct.unpack_from("<I", data, base)[0] == 1:  # PT_LOAD
            alignment = struct.unpack_from(alignment_format, data, base + alignment_offset)[0]
            if data[4] == 2:
                file_offset, address = struct.unpack_from("<QQ", data, base + 8)
            else:
                file_offset, address = struct.unpack_from("<II", data, base + 4)
            assert alignment > 0 and address % alignment == file_offset % alignment
            result.append(alignment)
    assert result, "ELF has no LOAD segments"
    return result


def main():
    for line in (PREBUILT / "SHA256SUMS.txt").read_text().splitlines():
        digest, name = line.split()
        assert hashlib.sha256((PREBUILT / name).read_bytes()).hexdigest() == digest, name
    with zipfile.ZipFile(APK) as archive, APK.open("rb") as raw:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries"
        for abi in ABIS:
            for library in ("libpdfium.so", "libpdfiumandroid.so"):
                assert names.count(f"lib/{abi}/{library}") == 1, (abi, library)
        native = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        for entry in sorted(native, key=lambda item: item.filename):
            _, abi, library = entry.filename.split("/")
            assert abi in ABIS, f"Unexpected ABI: {abi}"
            data = archive.read(entry)
            alignments = elf_load_alignments(data)
            # The compiled bridge in all ABIs, and every packaged 64-bit native library.
            if library == "libpdfiumandroid.so" or abi in {"arm64-v8a", "x86_64"}:
                assert min(alignments) >= 16384, f"ELF alignment below 16 KB: {entry.filename}"
            raw.seek(entry.header_offset)
            header = raw.read(30)
            assert header[:4] == b"PK\x03\x04"
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            data_offset = entry.header_offset + 30 + name_length + extra_length
            assert entry.compress_type == zipfile.ZIP_STORED, f"Expected uncompressed native library: {entry.filename}"
            assert data_offset % 16384 == 0, f"ZIP data alignment below 16 KB: {entry.filename}"
            print(f"{entry.filename}: min_LOAD={min(alignments)} ZIP_offset_mod_16384={data_offset % 16384}")
    print("PASS: prebuilt hashes, unique native entries, expected ABI, ELF and ZIP alignment")


if __name__ == "__main__":
    main()
