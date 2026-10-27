"""Download and inspect a fixed official Windows package. Never builds the engine."""

import hashlib
import json
from pathlib import Path
import platform
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
TAG = "runtime-5f7608ba4a634c94cd718067d6b678a45ba89088"
REVISION = "5f7608ba4a634c94cd718067d6b678a45ba89088"
PACKAGE = "libembedpdf-runtime-win32-x64-5f7608ba4.tar.gz"
PACKAGE_SHA256 = "4455b048953c3cc7411a47d2036ec1fb7d2740c6b0c596b9d51b6fdf6049b2d0"
DLL_SHA256 = "dd6c2e9839f0bfbf23e45dae46879ec8eff92d5719bfbe785175c1b245a616df"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "FirmarPDF-desktop-experiment"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def main():
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise SystemExit("This pinned package requires Windows x64; no automatic engine fallback.")
    local = ROOT / "_local"
    local.mkdir(exist_ok=True)
    release = json.loads(fetch(f"https://api.github.com/repos/embedpdf/runtime/releases/tags/{TAG}"))
    asset = next(a for a in release["assets"] if a["name"] == PACKAGE)
    if release["tag_name"] != TAG or release["id"] != 423363428:
        raise RuntimeError("Unexpected fixed release")
    if asset.get("digest") != f"sha256:{PACKAGE_SHA256}":
        raise RuntimeError("Release asset no longer matches the fixed digest")
    archive = local / PACKAGE
    if not archive.exists():
        archive.write_bytes(fetch(asset["browser_download_url"]))
    if hashlib.sha256(archive.read_bytes()).hexdigest() != PACKAGE_SHA256:
        raise RuntimeError("Package checksum mismatch")
    destination = local / "runtime"
    if not destination.exists():
        destination.mkdir()
        with tarfile.open(archive) as tar:
            for item in tar.getmembers():
                if not (destination / item.name).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Archive entry leaves isolated runtime directory")
            tar.extractall(destination, filter="data")
    if hashlib.sha256((destination / "bin/embedpdf.dll").read_bytes()).hexdigest() != DLL_SHA256:
        raise RuntimeError("Extracted DLL checksum mismatch")
    metadata = json.loads((destination / "BUILD-METADATA.json").read_text())
    if metadata["sha"] != REVISION or metadata["target"] != "win32-x64":
        raise RuntimeError("Package revision/target mismatch")
    provenance = ROOT / "provenance"
    provenance.mkdir(exist_ok=True)
    (provenance / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
    for name, url in {
        "commit.json": f"https://api.github.com/repos/embedpdf/runtime/commits/{REVISION}",
        "tag.json": f"https://api.github.com/repos/embedpdf/runtime/git/ref/tags/{TAG}",
        "tree.json": f"https://api.github.com/repos/embedpdf/runtime/git/trees/{REVISION}?recursive=1",
        "pdf-runtime-build.generated.json": f"https://github.com/embedpdf/runtime/releases/download/{TAG}/pdf-runtime-build.generated.json",
    }.items():
        (local / name).write_bytes(fetch(url))
    tag = json.loads((local / "tag.json").read_text())
    build = json.loads((local / "pdf-runtime-build.generated.json").read_text())
    if tag["object"]["sha"] != REVISION or build["sha"] != REVISION or build["artifacts"]["win32-x64"]["sha256"] != PACKAGE_SHA256:
        raise RuntimeError("Tag/build manifest mismatch")
    print("Verified fixed package SHA-256:", PACKAGE_SHA256)
    for path in destination.rglob("*"):
        if path.is_file():
            print(path.relative_to(destination), path.stat().st_size)


if __name__ == "__main__":
    main()
