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
    # New libyuv SME code uses support functions absent from host NDK r26d.
    # DEPS already downloads this exact Android compiler-rt alongside Clang.
    builtins = list((src / 'third_party/llvm-build/Release+Asserts').rglob('libclang_rt.builtins-aarch64-android.a'))
    assert len(builtins) == 1, builtins
    assert hashlib.sha256(builtins[0].read_bytes()).hexdigest() == '32faf9cae893d732d45799f18040b90c69f847bc408d8207c6f65dd2c2f940e4'
    shutil.copy2(builtins[0], lib / builtins[0].name)
    shutil.copytree(src / 'public', dest / 'include')
    licenses = dest / 'licenses'
    licenses.mkdir()
    shutil.copy2(Path(__file__).with_name('COMPILER_RT_LICENSE.txt'), licenses / 'COMPILER_RT_LICENSE.txt')
    for name in ('LICENSE', 'NOTICE'):
        shutil.copy2(src / name, licenses / name)
    for path in (src / 'third_party').rglob('*'):
        legal = path.name.lower().startswith(('license', 'copying', 'copyright')) or path.name in ('FTL.TXT', 'GPLv2.TXT', 'NOTICE', 'NOTICE.txt')
        if legal and path.is_file() and '.git' not in path.parts:
            target = licenses / path.relative_to(src)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    shutil.copy2(src / 'DEPS', dest / 'DEPS')
    shutil.copy2(out / 'args.gn', dest / 'args.gn')
    for name in ('gn-effective.log', 'gn-target.log', 'dependency-revisions.log', 'build-patches.log'):
        shutil.copy2(src.parent / name, dest / name)
    nm = src / 'third_party/llvm-build/Release+Asserts/bin/llvm-nm'
    symbols = subprocess.check_output([str(nm), '--defined-only', str(archive)], text=True, stderr=subprocess.DEVNULL)
    required = ['EPDF_InitThread', 'EPDF_ShutdownThread', 'EPDF_LoadBaseDocument', 'EPDFLayer_OpenLayer',
                'EPDFLayer_BeginTransaction', 'EPDFLayer_CommitTransaction', 'EPDFLayer_SaveDelta',
                'FPDFPage_InsertObject', 'FPDFPage_GenerateContent']
    assert all(any(line.split()[-1:] == [name] for line in symbols.splitlines()) for name in required)
    runtime_symbols = subprocess.check_output([str(nm), '--defined-only', str(builtins[0])], text=True, stderr=subprocess.DEVNULL)
    assert all(any(line.split()[-1:] == [name] for line in runtime_symbols.splitlines()) for name in ('__arm_get_current_vg', '__arm_tpidr2_save'))
    manifest = {'engine_revision': revision, 'abi': 'arm64-v8a', 'min_sdk': 28,
                'depot_tools_revision': a.depot_revision, 'thread_local_globals': True,
                'complete_archive': True, 'required_symbols_checked': required,
                'compiler_runtime_origin': {
                    'url': 'https://storage.googleapis.com/chromium-browser-clang/Linux_x64/clang-android-runtime-library-llvmorg-24-init-7747-g62397f8b-57.tar.xz?generation=1790797384572756',
                    'archive_sha256': '9551f7013b0210e4d291bfe09191682796240d93002b4317673c7fc280045e62',
                    'entry': 'lib/clang/24/lib/linux/libclang_rt.builtins-aarch64-android.a',
                    'license_source': 'https://raw.githubusercontent.com/llvm/llvm-project/62397f8b3c3986f54187ce08f00b3448ea1f8880/compiler-rt/LICENSE.TXT',
                    'license_sha256': '1a8f1058753f1ba890de984e48f0242a3a5c29a6a8f2ed9fd813f36985387e8d'},
                'run_url': 'https://github.com/' + os.environ.get('GITHUB_REPOSITORY', '') + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', ''),
                'compiler': subprocess.check_output([str(nm.parent / 'clang++'), '--version'], text=True),
                'files': {str(p.relative_to(dest)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(dest.rglob('*')) if p.is_file()}}
    (dest / 'ENGINE.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Packaged complete Android arm64 archive:', archive.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
