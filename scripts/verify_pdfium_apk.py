"""Read-only checks of APK native entries, ELF LOAD alignment and ZIP offsets.

Run from this project: python scripts/verify_pdfium_apk.py
This does not load native libraries, install the APK, or certify device behavior.
"""

from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PREBUILT = ROOT / "third_party/pdfiumandroid/prebuilt"
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}


def verify_embedpdf(archive, names, ndk):
    root = ROOT / "third_party/embedpdf"
    if os.name == "nt":
        root = Path("\\\\?\\" + str(root.resolve()))
    manifest = json.loads((root / "ENGINE.json").read_text())
    assert manifest["engine_revision"] == "5f7608ba4a634c94cd718067d6b678a45ba89088"
    assert manifest["abi"] == "arm64-v8a" and manifest["min_sdk"] == 28
    for library in ("libembedpdf_engine.a", "libc++_static.a", "libc++abi.a", "libclang_rt.builtins-aarch64-android.a"):
        assert "lib/arm64-v8a/" + library in manifest["files"], "Incomplete engine provenance: " + library
    for name, digest in manifest["files"].items():
        path = (root / "prebuilt" / name).resolve()
        assert path.is_relative_to((root / "prebuilt").resolve())
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
        if name.startswith("include/"):
            assert (root / name).read_bytes().replace(b"\r\n", b"\n") == path.read_bytes().replace(b"\r\n", b"\n"), "Bridge header differs from fixed engine: " + name
    native = "lib/arm64-v8a/libembedpdf_signature.so"
    assert names.count(native) == 1, "Missing isolated ARM64 bridge"
    assert not any(name.endswith("/libembedpdf_signature.so") and name != native for name in names), "Unexpected EmbedPDF ABI"
    assert not any("libc++_shared.so" in name for name in names), "Unexpected shared C++ runtime"
    readelf = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    target = ROOT / ".gradle/embedpdf-apk-audit/libembedpdf_signature.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    data = archive.read(native)
    target.write_bytes(data)

    def run(*arguments):
        return subprocess.check_output([str(readelf), *map(str, arguments)], text=True)

    def symbols(file):
        result = []
        for line in run("--dyn-syms", "-W", file).splitlines():
            p = line.split()
            if len(p) >= 8 and p[0].endswith(":") and p[0][:-1].isdigit():
                result.append({"bind": p[4], "visibility": p[5], "index": p[6], "name": p[7]})
        return result

    dynamic = run("-d", target)
    headers = run("-h", target)
    assert "AArch64" in headers, "Not an ARM64 ELF"
    assert "TEXTREL" not in dynamic, "Non-PIC input in the isolated engine"
    assert "SYMBOLIC" in dynamic, "Missing -Bsymbolic binding"
    needed = re.findall(r"\(NEEDED\).*?\[([^]]+)\]", dynamic)
    allowed = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libjnigraphics.so"}
    assert set(needed) <= allowed, needed
    entries = symbols(target)
    exported = sorted(s["name"] for s in entries if s["index"] != "UND" and s["bind"] in {"GLOBAL", "WEAK"} and s["visibility"] in {"DEFAULT", "PROTECTED"})
    expected_entry = "Java_com_zonadev_firmarpdf_data_pdf_EmbedPdfNativeBridge_nativeExport"
    assert exported == [expected_entry], exported
    assert not any(s["index"] == "UND" and s["name"].startswith(("FPDF", "EPDF")) for s in entries), "Cross-engine resolution possible"
    available = set()
    for library in needed:
        stub = ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/28" / library
        assert stub.is_file(), library
        available.update(s["name"].replace("@@", "@") for s in symbols(stub) if s["index"] != "UND")
    unresolved = sorted(s["name"] for s in entries if s["index"] == "UND" and s["bind"] != "WEAK" and s["name"].replace("@@", "@") not in available)
    assert not unresolved, unresolved
    optional = sorted(s["name"] for s in entries if s["index"] == "UND" and s["bind"] == "WEAK" and s["name"].replace("@@", "@") not in available)
    # compiler-rt's Android FMV resolver checks only its address as an API>=30
    # marker; when absent it returns without calling it. Source and actual
    # resolver/GOT disassembly were reviewed; see the build recipe.
    assert set(optional) <= {"memfd_create"}, "Unreviewed weak API imports: " + str(optional)
    report = {"engine_revision": manifest["engine_revision"], "engine_run": manifest["run_url"],
              "bridge_sha256": hashlib.sha256(data).hexdigest(), "apk_sha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
              "exports": exported, "needed": needed, "unresolved_strong_imports_api28": unresolved,
              "optional_api_marker_imports": optional,
              "load_alignments": elf_load_alignments(data), "symbolic": True, "textrel": False,
              "source_sha256": hashlib.sha256((root / "jni/embedpdf_signature.cpp").read_bytes()).hexdigest(),
              "version_script_sha256": hashlib.sha256((root / "jni/exports.map").read_bytes()).hexdigest(),
              "engine_manifest_sha256": hashlib.sha256((root / "ENGINE.json").read_bytes()).hexdigest()}
    report["static_archive_sha256"] = manifest["files"]["lib/arm64-v8a/libembedpdf_engine.a"]
    report["link_runtime_sha256"] = {name: value for name, value in manifest["files"].items() if name.startswith("lib/") and not name.endswith("libembedpdf_engine.a")}
    (target.parent / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: EmbedPDF ARM64 provenance, JNI-only exports, local binding, PIC, platform-only dependencies, API 28 strong imports and reviewed optional API marker")


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
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-embedpdf", action="store_true")
    parser.add_argument("--ndk", type=Path, default=Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/ndk/26.3.11579264")
    args = parser.parse_args()
    expected_hashes = {}
    for line in (PREBUILT / "SHA256SUMS.txt").read_text().splitlines():
        digest, name = line.split()
        assert hashlib.sha256((PREBUILT / name).read_bytes()).hexdigest() == digest, name
        expected_hashes[name] = digest
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
            if library == "libpdfium.so":
                assert hashlib.sha256(data).hexdigest() == expected_hashes[f"{abi}/{library}"], f"APK engine differs from pinned prebuilt: {entry.filename}"
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
        if args.require_embedpdf or (ROOT / "third_party/embedpdf/ENGINE.json").is_file():
            verify_embedpdf(archive, names, args.ndk)
        else:
            assert not any("libembedpdf_signature.so" in name for name in names), "EmbedPDF present without provenance"
    print("PASS: prebuilt/APK engine hashes, unique native entries, expected ABI, ELF and ZIP alignment")


if __name__ == "__main__":
    main()
