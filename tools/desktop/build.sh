#!/usr/bin/env bash
# Build the resident macOS desktop worker against the real engine sources.
#
# This is the host build, not the Android NDK build: no JNI, no Gradle, no
# libspektra.so. The source set is the same one tools/parity uses, compiled with
# the shipping release flags so host behavior matches the release engine.
#
# Usage: bash tools/desktop/build.sh
# Output: tools/desktop/.build/spektra_worker
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESK="$ROOT/tools/desktop"
CPP="$ROOT/engine/spektra-core/src/main/cpp"
OUT="$DESK/.build"
CXX="${CXX:-clang++}"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "build.sh: macOS is required (ImageIO/CoreGraphics worker)" >&2
  exit 1
fi
if ! command -v "$CXX" >/dev/null 2>&1; then
  echo "build.sh: $CXX not found" >&2
  exit 1
fi
for header in "$CPP/spektra.h" "$CPP/profiles/json_min.h"; do
  if [ ! -f "$header" ]; then
    echo "build.sh: missing engine header: $header" >&2
    exit 1
  fi
done

mkdir -p "$OUT/tmp"
export TMPDIR="$OUT/tmp"

cd "$CPP"
SRC=(
  spektra.cpp
  gpu/*.cpp
  kernels/*.cpp
  io/*.cpp
  model/*.cpp
  profiles/*.cpp
  runtime/*.cpp
  runtime/stages/*.cpp
)

echo "build.sh: compiling spektra_worker (host C++17, release flags)" >&2
"$CXX" -std=c++17 -O3 -ffast-math -fno-finite-math-only -pthread \
  -I"$CPP" -I"$DESK/native" \
  "$DESK/native/main.cpp" \
  "$DESK/native/worker.cpp" \
  "$DESK/native/params_json.cpp" \
  "$DESK/native/image_io_mac.mm" \
  "${SRC[@]}" \
  -framework ImageIO \
  -framework CoreGraphics \
  -framework CoreFoundation \
  -lz \
  -o "$OUT/spektra_worker"

echo "build.sh: built $OUT/spektra_worker" >&2
