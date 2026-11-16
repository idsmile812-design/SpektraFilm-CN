#!/usr/bin/env bash
# Build and run the real worker end-to-end test suite.
#
# The suite generates its own JPEG/PNG fixtures with ImageIO, starts the built
# worker as a child process, and re-decodes every output with ImageIO. No Python
# image libraries and no HTTP server.
#
# Usage: bash tools/desktop/tests/run_native_tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DESK="$ROOT/tools/desktop"
CPP="$ROOT/engine/spektra-core/src/main/cpp"
BUILD="$DESK/.build"
CXX="${CXX:-clang++}"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "run_native_tests.sh: macOS is required (ImageIO/CoreGraphics)" >&2
  exit 1
fi
# Rebuild by default so a stale binary can never make this suite green after a
# source edit. Set SPK_SKIP_WORKER_BUILD=1 only when build.sh was just run.
if [ "${SPK_SKIP_WORKER_BUILD:-0}" != "1" ]; then
  bash "$DESK/build.sh"
fi
if [ ! -x "$BUILD/spektra_worker" ]; then
  echo "run_native_tests.sh: worker binary missing: $BUILD/spektra_worker" >&2
  exit 1
fi

mkdir -p "$BUILD/tmp"
export TMPDIR="$BUILD/tmp"

echo "run_native_tests.sh: compiling test_native" >&2
"$CXX" -std=c++17 -O2 -pthread \
  -I"$DESK/native" -I"$CPP" \
  -x objective-c++ \
  "$DESK/tests/test_native.cpp" \
  "$DESK/native/image_io_mac.mm" \
  -framework ImageIO \
  -framework CoreGraphics \
  -framework CoreFoundation \
  -o "$BUILD/test_native"

echo "run_native_tests.sh: running $BUILD/test_native" >&2
"$BUILD/test_native" "$ROOT" "$BUILD"
