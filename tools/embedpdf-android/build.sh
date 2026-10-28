#!/usr/bin/env bash
set -euo pipefail

revision=5f7608ba4a634c94cd718067d6b678a45ba89088
depot_revision=a03dfbf7f2d1cefa2795d81d59ca4f16c8616a8f
work="${EMBEDPDF_BUILD_ROOT:?set a Linux working directory without spaces}"
package="${EMBEDPDF_PACKAGE:?set an artifact directory}"
jobs="${EMBEDPDF_BUILD_JOBS:-2}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
[[ "$(uname -s)" == Linux && "$work" != *' '* && "$work" = /* ]] || {
  echo 'A Linux build host and absolute path without spaces are required.' >&2; exit 2;
}
mkdir -p "$work"
exec > >(tee "$work/build.log") 2>&1
free -h
df -h "$work"
[[ $(df --output=avail -k "$work" | tail -1) -gt 20000000 ]] || {
  echo 'At least 20 GB free are required before syncing PDFium dependencies.'; exit 2;
}
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
if [[ ! -d "$work/depot_tools/.git" ]]; then
  git init "$work/depot_tools"
  git -C "$work/depot_tools" remote add origin https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
git -C "$work/depot_tools" fetch --depth=1 origin "$depot_revision"
git -C "$work/depot_tools" checkout --detach "$depot_revision"
export PATH="$work/depot_tools:$PATH"
# DEPOT_TOOLS_UPDATE=0 deliberately skips the usual bootstrap too. Initialize
# from this exact checkout, without updating its source revision.
"$work/depot_tools/ensure_bootstrap"
if [[ ! -d "$work/runtime/.git" ]]; then
  git init "$work/runtime"
  git -C "$work/runtime" remote add origin https://github.com/embedpdf/runtime.git
fi
git -C "$work/runtime" fetch --depth=1 origin "$revision"
git -C "$work/runtime" checkout --detach "$revision"
[[ "$(git -C "$work/runtime" rev-parse HEAD)" == "$revision" ]]
cat > "$work/.gclient" <<EOF
solutions = [{"name": "runtime", "url": "https://github.com/embedpdf/runtime.git", "deps_file": "DEPS", "managed": False,
              "custom_vars": {"checkout_configuration": "minimal", "checkout_skia": True, "checkout_android": True, "checkout_android_native_support": True}}]
target_os = ["android"]
EOF
cd "$work"
gclient sync --nohooks --no-history --shallow --jobs=2 --revision "runtime@$revision"
cd "$work/runtime"
# The skia_source_set template already applies optimize_max inside source_set.
# Its Android invoker duplicates that mutation before configs exists (GN error).
# Also gate the unused full renderer target on pdf_use_skia: its Android Expat
# dependency is absent from this fork's DEPS. Keep redaction_pathops unaltered.
# Build metadata only; preserve the template defaults and all engine algorithms.
git apply --check "$script_dir/android-skia-gn.patch"
git apply "$script_dir/android-skia-gn.patch"
git apply --check "$script_dir/selective-xref-format.patch"
git apply "$script_dir/selective-xref-format.patch"
# The broad Chromium installer fetched an unrelated 32-bit/desktop stack for
# >30 min in run 40444678413. Here only a native ARM64 library is built, with
# NDK/sysroot/Clang pinned by DEPS. Inspect and install the host essentials only.
for tool in git python3 gcc g++ pkg-config; do command -v "$tool"; done
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends build-essential pkg-config zlib1g-dev libglib2.0-dev ninja-build
gclient runhooks --jobs=2
out=out/firmarpdf-arm64
mkdir -p "$out"
cat > "$out/args.gn" <<EOF
target_os="android"
target_cpu="arm64"
default_min_sdk_version=28
is_debug=false
is_component_build=false
is_official_build=false
pdf_is_standalone=true
pdf_is_complete_lib=true
pdf_use_partition_alloc=false
pdf_use_skia=false
pdf_enable_xfa=false
pdf_enable_v8=false
embedpdf_thread_local_globals=true
clang_use_chrome_plugins=false
treat_warnings_as_errors=false
use_debug_fission=false
symbol_level=0
use_remoteexec=false
use_thin_lto=false
use_custom_libcxx=false
use_custom_libcxx_for_host=false
EOF
gn gen "$out" --fail-on-unused-args
gn args "$out" --list --short > "$work/gn-effective.log"
gn desc "$out" //:pdfium > "$work/gn-target.log"
git diff --binary > "$work/build-patches.log"
ninja -C "$out" -j "$jobs" pdfium
gclient revinfo -a > "$work/dependency-revisions.log"
python3 "$script_dir/package_engine.py" --source "$work/runtime" --out "$work/runtime/$out" --destination "$package" --depot-revision "$depot_revision"
