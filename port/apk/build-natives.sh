#!/bin/bash
# Fetches pinned sources into $DD_BUILD, applies our patches, and builds the prebuilt pieces the
# Gradle app packages:
#   $DD_BUILD/out/jniLibs/arm64-v8a/libbox64.so   box64 as a shared library (glibc-on-bionic patch)
#   $DD_BUILD/out/jniLibs/arm64-v8a/libGL.so      gl4es
#   $DD_BUILD/out/assets/x64lib/                   x86_64 libstdc++.so.6, libgcc_s.so.1 (Debian 12)
# SDL2 is only fetched (and patched) here; Gradle builds it with the app.
# Needs: Android SDK with NDK 27.1.12297006 and CMake 3.22.1 + 4.1.2, git, curl, Python 3 (box64's
# wrapper generator), Windows bsdtar for .deb files.
set -euo pipefail

DD_BUILD=${DD_BUILD:-/c/dd-build}
SDK=${ANDROID_SDK:-$(cygpath "$LOCALAPPDATA")/Android/Sdk}
NDK=$SDK/ndk/27.1.12297006
CMAKE_BIN=$SDK/cmake/4.1.2/bin
PATCHES=$(cd "$(dirname "$0")/patches" && pwd)

BOX64_COMMIT=d96343fbb          # same as the chroot build on the phone
GL4ES_COMMIT=a025615b           # master after the Adreno highp fixes (PR #509)
SDL2_TAG=release-2.32.10
DEB=https://snapshot.debian.org/archive/debian/20250415T084322Z/pool/main/g/gcc-12   # permanent; the live pool drops old versions
DEB_VER=12.2.0-14+deb12u1

mkdir -p "$DD_BUILD/src" "$DD_BUILD/build" "$DD_BUILD/out/jniLibs/arm64-v8a" "$DD_BUILD/out/assets/x64lib"
cd "$DD_BUILD/src"

fetch() {  # fetch NAME URL COMMIT [PATCH...]: clean checkout of COMMIT with the patches applied
    local name=$1 url=$2 commit=$3; shift 3
    [ -d "$name" ] || git -c core.autocrlf=false clone -q "$url" "$name"
    git -C "$name" config core.autocrlf false
    git -C "$name" fetch -q origin "$commit" 2>/dev/null || true
    git -C "$name" checkout -q -f "$commit"
    git -C "$name" clean -q -fdx
    for p in "$@"; do git -C "$name" apply "$PATCHES/$p"; done
}
fetch box64 https://github.com/ptitSeb/box64.git $BOX64_COMMIT box64-glibc-on-bionic.patch box64-shared-lib.patch \
    box64-android-qsort.patch
fetch gl4es https://github.com/ptitSeb/gl4es.git $GL4ES_COMMIT gl4es-android.patch gl4es-log-link-errors.patch \
    gl4es-3d-textures.patch gl4es-negative-box-cache.patch gl4es-client-array-range.patch \
    gl4es-pvr-packed-depth-stencil.patch
fetch SDL2 https://github.com/libsdl-org/SDL.git $SDL2_TAG sdl2-no-java.patch

ndk_cmake() {  # ndk_cmake SRC BUILD [cmake args...]
    local src=$1 build=$2; shift 2
    PATH="$CMAKE_BIN:$PATH" cmake.exe -G Ninja -S "$src" -B "$build" -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja.exe" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=31 -DANDROID=1 -DCMAKE_BUILD_TYPE=RelWithDebInfo "$@" > "$build.cmake.log"
    PATH="$CMAKE_BIN:$PATH" ninja.exe -C "$build" > "$build.build.log" || { tail -20 "$build.build.log"; exit 1; }
}
# generic ARM64 dynarec: -DSD845 would emit ARMv8.2 code, and the Snapdragon 835 is ARMv8.0
ndk_cmake "$DD_BUILD/src/box64" "$DD_BUILD/build/box64-lib" -DARM_DYNAREC=1 -DBAD_SIGNAL=1 -DBOX64_SHARED=ON
ndk_cmake "$DD_BUILD/src/gl4es" "$DD_BUILD/build/gl4es" -DNOX11=ON -DNOEGL=ON -DNO_LOADER=ON \
    -DNO_INIT_CONSTRUCTOR=ON -DUSE_ANDROID_LOG=ON
cp "$DD_BUILD/build/box64-lib/libbox64.so" "$DD_BUILD/src/gl4es/lib/libGL.so" "$DD_BUILD/out/jniLibs/arm64-v8a/"

# x86_64 C++ runtime for the game: box64 doesn't wrap libstdc++/libgcc_s on Android
for pkg in libstdc++6 libgcc-s1; do
    deb="$DD_BUILD/build/${pkg}_amd64.deb"
    [ -s "$deb" ] || curl -sfLo "$deb" "$DEB/${pkg}_${DEB_VER}_amd64.deb"
    rm -rf "$DD_BUILD/build/$pkg" && mkdir -p "$DD_BUILD/build/$pkg"
    (cd "$DD_BUILD/build/$pkg" && /c/Windows/System32/tar.exe -xf "$deb" && /c/Windows/System32/tar.exe -xf data.tar.xz)
done
cp -L "$DD_BUILD/build/libstdc++6/usr/lib/x86_64-linux-gnu/libstdc++.so.6" \
      "$DD_BUILD/build/libgcc-s1/lib/x86_64-linux-gnu/libgcc_s.so.1" "$DD_BUILD/out/assets/x64lib/"

ls -la "$DD_BUILD/out/jniLibs/arm64-v8a" "$DD_BUILD/out/assets/x64lib"
