#!/usr/bin/env bash
set -euo pipefail
work="${EMBEDPDF_BUILD_ROOT:?}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
export PATH="$work/depot_tools:$PATH"
cd "$work/runtime"
# A disposable host build is only a test harness for the same patched writer.
# Only the ARM64 archive produced by build.sh is packaged/imported into Android.
out=out/firmarpdf-writer-test
mkdir -p "$out" "$work/writer-regressions"
exec > >(tee "$work/writer-regressions/build.log") 2>&1
# This standalone fork's gn_check group references the disabled full Skia
# renderer on Linux. Gate only that unused metadata dependency for the harness.
# C++ sources remain byte-identical to the Android writer already packaged.
python3 - <<'PY'
from pathlib import Path
import hashlib, json
p = Path('BUILD.gn')
old = 'if (defined(checkout_skia) && checkout_skia && !is_android && !is_wasm) {'
new = 'if (pdf_use_skia && defined(checkout_skia) && checkout_skia && !is_android && !is_wasm) {'
text = p.read_text()
assert text.count(old) == 1
text = text.replace(old, new)
# The fork relies on transitive stdint.h includes from Android's libc++.
# Force the standard header for the system-libstdc++ host harness only.
old_config = 'config("pdfium_common_config") {\n  cflags = []'
assert text.count(old_config) == 1
text = text.replace(old_config, 'config("pdfium_common_config") {\n  cflags = [ "-include", "stdint.h" ]')
p.write_text(text)
sources = ('core/fpdfapi/edit/cpdf_creator.cpp', 'core/fpdfapi/edit/cpdf_creator.h')
Path('../writer-regressions/writer-source-hashes.json').write_text(json.dumps(
    {name: hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in sources}, indent=2) + '\n')
PY
sed -e 's/target_os="android"/target_os="linux"/' \
    -e 's/target_cpu="arm64"/target_cpu="x64"/' \
    -e '/default_min_sdk_version/d' out/firmarpdf-arm64/args.gn > "$out/args.gn"
cat >> "$out/args.gn" <<EOF
use_sysroot=false
use_glib=false
icu_use_data_file=false
EOF
gn gen "$out" --fail-on-unused-args
cp "$out/args.gn" "$work/writer-regressions/host-args.gn"
git diff > "$work/writer-regressions/host-build-patches.log"
ninja -C "$out" -j "${EMBEDPDF_BUILD_JOBS:-2}" pdfium
cp "$out/obj/libembedpdf.a" "$work/writer-regressions/libwriter_test.a"
# Match the Android bridge's reachability: retain only the C APIs exercised
# here. Do not pull the fork's unused redaction entry points into the harness.
third_party/llvm-build/Release+Asserts/bin/clang++ -fuse-ld=lld \
    -Wl,--gc-sections,--no-undefined,-Bsymbolic \
    -Wl,--version-script="$script_dir/writer-test-exports.map" \
    -shared -o "$out/libwriter_test.so" -Wl,--whole-archive "$out/obj/libembedpdf.a" \
    -Wl,--no-whole-archive -ldl -lpthread -lm -latomic
cp "$out/libwriter_test.so" "$work/writer-regressions/"
python3 -m venv "$work/writer-venv"
"$work/writer-venv/bin/pip" install --disable-pip-version-check pypdf==6.21.2 pillow==12.0.0
"$work/writer-venv/bin/python" "$script_dir/test_selective_xref.py" \
    --engine "$PWD/$out/libwriter_test.so" --output "$work/writer-regressions" --download-readers
