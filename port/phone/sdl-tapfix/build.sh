#!/bin/bash
# Build the tapfix stand-in libSDL2 inside the chroot. Needs: build-essential libsdl2-dev patchelf.
# Result: /usr/local/lib/dd-tapfix/{libSDL2-2.0.so.0 (shim), libSDL2-real.so (copy of system SDL2)}.
# dd-run puts that directory first on LD_LIBRARY_PATH so Box64's native SDL2 load picks the shim.
# The copy gets its own SONAME; otherwise the shim's NEEDED entry would name itself.
set -e
OUT=/usr/local/lib/dd-tapfix
REAL=$(readlink -f /usr/lib/aarch64-linux-gnu/libSDL2-2.0.so.0)
mkdir -p $OUT
cp "$REAL" $OUT/libSDL2-real.so
patchelf --set-soname libSDL2-real.so $OUT/libSDL2-real.so
gcc -O2 -Wall -shared -fPIC "$(dirname "$0")/tapfix.c" -o $OUT/libSDL2-2.0.so.0 \
    -Wl,-soname,libSDL2-2.0.so.0 -L$OUT -l:libSDL2-real.so -Wl,-rpath,'$ORIGIN' -ldl
echo "built $OUT/libSDL2-2.0.so.0 -> $REAL"
