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
sed -e 's/target_os="android"/target_os="linux"/' \
    -e 's/target_cpu="arm64"/target_cpu="x64"/' \
    -e '/default_min_sdk_version/d' out/firmarpdf-arm64/args.gn > "$out/args.gn"
cat >> "$out/args.gn" <<EOF
use_sysroot=false
use_glib=false
EOF
gn gen "$out" --fail-on-unused-args
ninja -C "$out" -j "${EMBEDPDF_BUILD_JOBS:-2}" pdfium
g++ -shared -o "$out/libwriter_test.so" -Wl,--whole-archive "$out/obj/libembedpdf.a" \
    -Wl,--no-whole-archive -ldl -lpthread -lm
python3 -m venv "$work/writer-venv"
"$work/writer-venv/bin/pip" install --disable-pip-version-check pypdf==6.21.2 pillow==12.0.0
"$work/writer-venv/bin/python" "$script_dir/test_selective_xref.py" \
    --engine "$PWD/$out/libwriter_test.so" --output "$work/writer-regressions" --download-readers
