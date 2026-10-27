"""Validate downloaded Actions artifact and install only into the separate engine."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

PROJECT = Path(__file__).resolve().parents[2]
REVISION = '5f7608ba4a634c94cd718067d6b678a45ba89088'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('artifact', type=Path)
    a = p.parse_args()
    if os.name == 'nt':
        a.artifact = Path('\\\\?\\' + str(a.artifact.resolve()))
    manifest = json.loads((a.artifact / 'ENGINE.json').read_text())
    assert manifest['engine_revision'] == REVISION and manifest['abi'] == 'arm64-v8a'
    assert manifest['min_sdk'] == 28 and manifest['complete_archive'] and manifest['thread_local_globals']
    for name, digest in manifest['files'].items():
        path = (a.artifact / name).resolve()
        assert path.is_relative_to(a.artifact.resolve()), name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
    archive = a.artifact / 'lib/arm64-v8a/libembedpdf_engine.a'
    with archive.open('rb') as stream:
        assert stream.read(8) == b'!<arch>\n'
    assert (a.artifact / 'lib/arm64-v8a/libc++_static.a').is_file()
    assert (a.artifact / 'lib/arm64-v8a/libc++abi.a').is_file()
    assert (a.artifact / 'lib/arm64-v8a/libclang_rt.builtins-aarch64-android.a').is_file()
    root = PROJECT / 'third_party/embedpdf'
    if os.name == 'nt':
        root = Path('\\\\?\\' + str(root.resolve()))
    # Exact same source headers as the desktop reference, compiled for Android ABI.
    for existing in (root / 'include').glob('*.h'):
        # Git autocrlf can change checkout newlines on Windows, not header contents.
        assert existing.read_bytes().replace(b'\r\n', b'\n') == (a.artifact / 'include' / existing.name).read_bytes().replace(b'\r\n', b'\n'), existing.name
    shutil.copytree(a.artifact, root / 'prebuilt', dirs_exist_ok=True)
    shutil.copytree(a.artifact / 'include', root / 'include', dirs_exist_ok=True)
    # Keep the original tree in ignored prebuilt, but flatten the tracked legal
    # copy so a normal Windows Git checkout doesn't require global long-path setup.
    legal_index = {}
    for path in sorted((a.artifact / 'licenses').rglob('*')):
        if path.is_file():
            name = str(path.relative_to(a.artifact / 'licenses')).replace('\\', '/')
            flat = path.name if '/' not in name else hashlib.sha256(name.encode()).hexdigest()[:16] + '-' + path.name
            shutil.copy2(path, root / 'licenses' / flat)
            legal_index[name] = flat
    (root / 'licenses/INDEX.json').write_text(json.dumps(legal_index, indent=2) + '\n')
    for name in ('ENGINE.json', 'args.gn', 'DEPS', 'dependency-revisions.log', 'gn-effective.log', 'gn-target.log', 'build-patches.log'):
        shutil.copy2(a.artifact / name, root / name)
    print('Validated and imported separate Android arm64 engine from', manifest['run_url'])


if __name__ == '__main__':
    main()
