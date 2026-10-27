"""Package the actual Android build, never a desktop binary or an empty stub."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    for name in ('source', 'out', 'destination'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--depot-revision', required=True)
    a = parser.parse_args()
    src, out, dest = a.source, a.out, a.destination
    revision = subprocess.check_output(['git', '-C', str(src), 'rev-parse', 'HEAD'], text=True).strip()
    assert revision == '5f7608ba4a634c94cd718067d6b678a45ba89088'
    archive = out / 'obj/libembedpdf.a'
    assert archive.read_bytes()[:8] == b'!<arch>\n', 'Requires complete non-thin static archive'
    lib = dest / 'lib/arm64-v8a'
    lib.mkdir(parents=True)
    shutil.copy2(archive, lib / 'libembedpdf_engine.a')
    ndk = src / 'third_party/android_toolchain/ndk'
    candidates = list(ndk.rglob('aarch64-linux-android/libc++_static.a'))
    assert len(candidates) == 1, candidates
    for name in ('libc++_static.a', 'libc++abi.a', 'libunwind.a'):
        file = candidates[0].parent / name
        if file.exists():
            shutil.copy2(file, lib / name)
    shutil.copytree(src / 'public', dest / 'include')
    licenses = dest / 'licenses'
    licenses.mkdir()
    for name in ('LICENSE', 'NOTICE'):
        shutil.copy2(src / name, licenses / name)
    for path in (src / 'third_party').rglob('LICENSE*'):
        if path.is_file() and '.git' not in path.parts:
            target = licenses / path.relative_to(src)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    shutil.copy2(src / 'DEPS', dest / 'DEPS')
    shutil.copy2(out / 'args.gn', dest / 'args.gn')
    for name in ('gn-effective.log', 'gn-target.log', 'dependency-revisions.log'):
        shutil.copy2(src.parent / name, dest / name)
    nm = src / 'third_party/llvm-build/Release+Asserts/bin/llvm-nm'
    symbols = subprocess.check_output([str(nm), '--defined-only', str(archive)], text=True, stderr=subprocess.DEVNULL)
    required = ['EPDF_InitThread', 'EPDF_ShutdownThread', 'EPDF_LoadBaseDocument', 'EPDFLayer_OpenLayer',
                'EPDFLayer_BeginTransaction', 'EPDFLayer_CommitTransaction', 'EPDFLayer_SaveDelta',
                'FPDFPage_InsertObject', 'FPDFPage_GenerateContent']
    assert all(any(line.split()[-1:] == [name] for line in symbols.splitlines()) for name in required)
    manifest = {'engine_revision': revision, 'abi': 'arm64-v8a', 'min_sdk': 28,
                'depot_tools_revision': a.depot_revision, 'thread_local_globals': True,
                'complete_archive': True, 'required_symbols_checked': required,
                'run_url': 'https://github.com/' + os.environ.get('GITHUB_REPOSITORY', '') + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', ''),
                'compiler': subprocess.check_output([str(nm.parent / 'clang++'), '--version'], text=True),
                'files': {str(p.relative_to(dest)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(dest.rglob('*')) if p.is_file()}}
    (dest / 'ENGINE.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Packaged complete Android arm64 archive:', archive.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
