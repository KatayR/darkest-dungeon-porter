# Darkest Dungeon Porter: development log
A dated development log: plans, measurements and results, oldest first. Later sections supersede earlier plans; file and patch names in old sections may no longer exist.

It started as a plan to port the GOG Linux build to a OnePlus 5 through Box64.

## Context
The aim is to run the owned GOG copy of Darkest Dungeon on an Android phone without a Windows emulator, and to end up with a standalone APK. The approach chosen earlier: run the official **Linux x86_64 build** through **Box64**, which translates x86_64 to ARM64. SDL2 and GL are passed through to native ARM libraries.

**Facts verified so far:**
- **Installer:** the GOG Linux installer (`darkest_dungeon_27988_linux_93838.sh`). Build **27988** (same as the Windows install), 9,529 files, about 2.3 GB.
- **Binaries:** `game/_linuxnosteam/darkest.bin.x86_64` plus a bundled `lib64/` containing `libSDL2-2.0.so.0`, `libfmod.so.14` and `libfmodstudio.so.14`.
- **Dependencies:**
  - libGL.so.1, SDL2, FMOD, libstdc++ (GLIBCXX_3.4.29), libgcc_s, libm, libc (GLIBC_2.34).
  - Debian 12 provides all of these. Box64 has native wrappers for SDL2, GL, libstdc++, libgcc and libm.
- **GPU needs:** **OpenGL 2.0+, GLSL `#version 120`** (from README.linux). That fits within GL 2.1, which is what VirGL and gl4es can deliver.
- **Phone:** OnePlus 5, model A5000.
  - Hardware: msm8998 (Snapdragon 835), **Adreno 540**, about 6 GB RAM.
  - Software: Android 15 (LineageOS 22.2), kernel 4.4.302, rooted with Magisk.
  - Apps: Termux is installed; Termux:X11 is not.
- **Driver limits:** Adreno 540 is a5xx. **Turnip/Zink does not support it**, and freedreno-on-KGSL is still a work-in-progress merge request. Hardware GL is therefore limited to:
  - **VirGL**, which forwards GL to Android's own Adreno GLES driver; this is the Phase 1 route.
  - **gl4es on bionic** directly; this is the Phase 2 route.
- **adb:** adb (Android SDK platform-tools) is available, and the device is authorized.

## Phase 0: Housekeeping (first execution step)
- [x] Plan written to `notes/todo.md`; `notes/lessons.md` created.
- [x] Extracted the archive to a local copy of the Linux game (a filtered extract only got top-level files, so the whole archive was extracted). Verified: all 8,280 `data/` files present, `unzip -t` clean. (9,529 entries = 8,339 files + dirs; the other 59 files are installer themes under `meta/`.)

## Phase 1: Proof of life (Termux + root chroot + VirGL)
Goal: reach the title screen, then town, then one dungeon battle. Measure FPS and RAM before investing in an APK.

1. **Phone apps**
   - [x] Termux was the Play build (googleplay.2026.10.09, empty). Replaced with the GitHub v0.118.3 arm64 build.
   - [x] Installed Termux:X11 nightly (universal, non-sharedUid).
2. **Termux side** (native bionic; the display, GL server and audio run here)
   - [x] Installed x11-repo, termux-x11-nightly, virglrenderer-android, pulseaudio.
   - [x] `port/phone/termux-services.sh` (copied to Termux $HOME) starts all three; sockets: `.X11-unix/X0`, `.virgl_test`, `pulse.sock`.
   - [x] Termux is driven over adb with `su` switching to Termux's UID and groups, plus the inet group 3003 (needed for network).
3. **Debian chroot** (root, via `adb shell su -c`; Magisk will ask once to allow root for shell)
   - [x] Debian 12.15 arm64 (LXC image 20261021) unpacked to `/data/local/dd/debian`.
   - [x] `port/phone/dd-{mount,umount,enter}.sh` (on the phone in `/data/local/dd/bin/`):
     - mount proc, sys, dev and dev/pts;
     - bind-mount Termux's `$PREFIX/tmp` → chroot `/tmp`, which shares the X11, virgl and pulse sockets.
   - [x] Safety rule: never `rm` inside the chroot while mounts are active. The umount script runs first.
   - [x] apt packages installed (DNS via `/etc/resolv.conf`, apt sandbox user = root, `aid_inet` group).
   - [x] Box64 v0.4.5 (d96343f) built on-device with `-DARM_DYNAREC=ON` and installed in the chroot.
4. **Game files**
   - [x] Pushed 8,261 files (2.2 GB) → chroot `/opt/darkest`.
5. **First run** (inside the chroot)
   - [x] `port/phone/dd-run.sh` (→ chroot `/usr/local/bin/dd-run`) launches it; log in chroot `/root/dd.log`.
   - [x] Native-wrapped SDL2 works out of the box; FMOD (emulated) loads banks and outputs to Pulse.
   - [x] Diagnostics: `GALLIUM_HUD` must stay hidden (see Findings); FPS comes from the HUD dump file.
6. **Measure and record** in `notes/todo.md`:
   - [ ] FPS at title, town and battle; RAM peak; load times; audio; video cutscenes (.ogv); rendering glitches.

**Gate:**
- Playable (roughly 25+ fps in battle, correct rendering): go to Phase 2.
- VirGL too slow or buggy: try gl4es in the chroot, with Termux:X11 and the gl4es framebuffer mode, before re-planning.

## Phase 2: Standalone APK (first outline; superseded by "Phase 2 plan" at the end)
- **App layout:**
  - SDL2's Android Activity provides the window, EGL surface and touch input.
  - Box64 is built with `-DANDROID=1` (bionic).
  - gl4es acts as `libGL.so` on top of Adreno GLES2. No X11, no chroot, no root needed.
- **Box64 wiring:** wrapped `libSDL2` maps to the app's SDL2 and `libGL` maps to gl4es. The game binary and FMOD run as emulated x86_64.
- **Open question: audio.** Linux FMOD expects PulseAudio or ALSA. Options:
  - a small native shim that forwards to AAudio or SDL audio;
  - a Box64 wrapper so the ARM64 Linux FMOD build can be used instead.
- **Controls:** tap = left click, long-press or two-finger tap = right click, plus an on-screen key strip for keys like Esc and map.
- **Game data:** never bundled in the APK. The launcher points at a folder copied to app storage; this keeps the APK shareable without the game.

## Verification
- Phase 0: compare the file count and size of the staging folder with the archive listing (9,529 files).
- Phase 1: logs plus screenshots pulled over adb, with each milestone (title, town, battle) confirmed by screenshot.
- Manual testing on the phone is needed for touch, audio quality and how smoothly the game feels. The numbers get logged in `notes/todo.md` under a Review section.

## Phase 1 findings (2026-10-21)
- **Milestones:** intro cinematic ✅, title screen ✅. Town and battle are untested and need manual play.
- **GL:** `virgl (Adreno (TM) 540)`, GL 2.1 Mesa 22.3.6. glxgears runs at about 275 FPS. No shader errors from the game's own shaders.
- **Visible `GALLIUM_HUD` crashes the game.**
  - Cause: VirGL translates the HUD's shader into GLSL ES with an input array, which Adreno rejects. The server then drops the client, and Box64 segfaults in `SDL_GL_SwapWindow`.
  - Workaround: `GALLIUM_HUD=fps GALLIUM_HUD_VISIBLE=false GALLIUM_HUD_DUMP_DIR=/root/hud`, then read `/root/hud/fps`.
  - `LIBGL_SHOW_FPS` prints nothing on this path.
- **FPS:**

  | Scene | Resolution | FPS |
  |---|---|---|
  | Intro video | 1920×1080 | ~23.6 |
  | Title | 1920×1080 | ~14.5 |
  | Title | 1280×720 | ~20 |

  Termux:X11 is now set to exact 1280×720, landscape, fullscreen, simulated touchscreen.
- **Bottleneck is per-frame round-trip latency, not compute.** At 1080p, top showed virgl ~38% of a core, the game ~27% and X ~19%; the GPU was 42% busy at 257 MHz. Every frame is read back from the GPU, sent over a socket and copied through X. This is exactly the overhead the Phase 2 design (gl4es straight onto the Adreno EGL surface) removes.
- **RAM:** game RSS ~826 MB, about 2.4 GB still available.
- **Audio:** FMOD → Pulse → OpenSL ES sink connects. Not yet heard; needs manual check.

### How to start it (until there's a launcher)
1. Termux: `sh ~/termux-services.sh` (X11, VirGL, PulseAudio; opens Termux:X11).
2. Termux: `su -c "sh /data/local/dd/bin/dd-enter.sh dd-run"`.
3. Stop: `su -c "pkill -f darkest.bin"`. Before deleting anything under `/data/local/dd`: `su -c "sh /data/local/dd/bin/dd-umount.sh"`.

## Touch input fix (2026-10-21)
- **Problem:** taps rarely registered; a short tap-and-hold worked.
  - Cause: Termux:X11 sends a tap's press and release back to back with no gap.
  - The game only uses `SDL_PollEvent` and folds events into per-frame state, so a press and release drained in the same frame cancel out.
- **Control scheme:**
  - Termux:X11 `touchMode:Trackpad tapToMove:false`.
  - Dragging moves the cursor relatively; it never jumps to the finger.
  - Tap = left click, two-finger tap = right click, long-press then drag = hold the button.
- [x] `port/phone/sdl-tapfix/`: a stand-in `libSDL2-2.0.so.0` that wraps only `SDL_PollEvent`.
  - A release that arrives in the same frame as its press is held, with everything after it to keep order, until 2 frames and 60 ms have passed.
  - `dd-run` loads it via `LD_LIBRARY_PATH`. The real SDL is copied as `libSDL2-real.so` with its SONAME changed (patchelf); otherwise the shim's NEEDED entry would name itself.
- [x] Verified with `tapfix-test.c` (50 ms frames, `xdotool click`): without the shim, press and release land in the same frame. With it, the release comes 2 frames later.
- [x] Verified in-game: Box64 maps the shim; an instant xdotool click on "Campaign" opens the save list.
- [x] Manual: confirmed "works perfectly" on device (taps + trackpad).
- **Phase 2 note:** the APK's own touch→mouse layer must enforce the same minimum press length (at least 2 frames).

## Gameplay FPS (2026-10-21, 1280×720, VirGL)
- `dd-run` now always logs FPS (hidden Gallium HUD → `/root/hud/fps`); `dd-fps [N]` prints the last N samples plus min/avg/max.

| Scene | FPS |
|---|---|
| Intro cinematic | ~38 |
| The Old Road, exploring with 2 heroes | 35–40 steady (avg 37.7–38.5), brief 51 peak, one 14.9 dip while loading |
| Title screen (fog and particles) | ~20 |

- **Gate:** passed (25+ fps in gameplay). Next: profile what limits FPS before deciding how much Phase 2 will gain.

## FPS investigation (2026-10-21)
Goal: measure what each stage of the VirGL chain costs, grab any free wins for playing now, and size the Phase 2 gain.
Test scene: the title screen (repeatable, worst case at ~20 FPS, doesn't touch the save). 30 s of `dd-fps` per run.
The screen stays on and unlocked with Termux:X11 in front (`svc power stayon usb` while testing).

- [x] Baseline: title at 1280×720, stock clocks. Also per-thread CPU (`top -H`), GPU busy and clock, and temperature.
- [x] Clocks locked to max (root; resets at reboot): CPU governor `performance` on both clusters, GPU devfreq `performance`. Record the original values first and restore them afterwards.
- [x] Resolution sweep: 960×540, 1280×720, 1920×1080 (Termux:X11 `displayResolutionExact`, game restarted each time).
- [x] ~~Box64 tuning~~: skipped. The game's threads use only ~20% of a core even at 50+ FPS, so it is waiting on the VirGL chain and faster translation can't show.
- [x] Decide: the GPU lock goes into `dd-run`. Next: Phase 2.

`port/phone/dd-bench.sh LABEL [VAR=VAL ...]` (on the phone at `/data/local/dd/bin/`) does one run: it launches, clicks through the intro, then samples 30 s on the title screen. It saves a screenshot to `/sdcard/bench_LABEL.png`; check that it shows the title screen, because a run with Termux:X11 in the background is invalid.

### Results (title screen)
| Run | FPS avg (min–max) | GPU | Notes |
|---|---|---|---|
| Stock, 720p | 39.7 (34–43) | 33% busy @ 257 MHz | GPU governor never clocks up. The "~20" earlier was an unreliable reading. |
| CPU + GPU locked, 720p | 54.3 (50–58) | 14% @ 710 | |
| GPU only locked, 720p | 52.0 (49–58) | 14% @ 710 | Nearly all of the gain. The big cores were already at 2457 MHz. |
| GPU locked, 540p (custom mode) | 71.7 (66–76) | 14% @ 670 | 960×540 is only available as `displayResolutionMode:custom`. |
| GPU locked, 1080p | 29.3 (27–31) | 14% @ 670–710 | |
| `dd-run` final (stock settings) | 51.3 (49–54) | 14% @ 670 | Governor restored to `msm-adreno-tz` on exit. |

### Review
- **Frame time ≈ 7 ms fixed + ~13 ms per megapixel.** It is linear across all three resolutions.
  - At 720p, ~12 of the ~19 ms is pixel-proportional. The GPU is only 14% busy, so this is the frame being read back and copied through X11 (~300 MB/s), not drawing.
  - The fixed ~7 ms covers game logic plus GL command transport; that alone would allow ~140 FPS.
- **No single thread is pegged.** At 720p with the GPU locked: virgl server ~50%, X server ~20%, game main thread ~19%. The stages run one after another.
- **Kept:** `dd-run` pins the GPU devfreq governor to `performance` while the game runs and restores it on exit, taking 720p from ~40 to ~51 FPS. Kernel thermal limits still apply; one run settled at 670 MHz. Costs some battery. If `dd-run`'s bash is killed rather than the game, the governor stays pinned until reboot.
- **Also:** `GALLIUM_HUD_PERIOD=1`. The HUD default is 0.5 s, so `dd-fps` windows had been half as long as labelled.
- **Phase 2 estimate:** gl4es renders straight to the EGL surface, so the per-pixel term largely disappears. Only the fixed term, minus VirGL encode/decode, is left, so 60 FPS (vsync) at 720p and likely 1080p is realistic. This is an estimate until measured with `dd-bench`-style runs.

## Phase 2 plan (2026-10-21)
### Context
**Phases 0–1 are done** (committed, plus the then-uncommitted FPS work). The game runs in a rooted Debian chroot through Box64 (glibc build), VirGL and Termux:X11, at about 51 FPS at 720p.

**Why that path is capped:** every frame is read back from the GPU and copied through X11. That costs about 13 ms per megapixel, while the GPU is only 14% busy.

**Phase 2 goal:** one APK, with no chroot, no Termux and no X11. The game renders straight to the Android surface through gl4es, so the target is 60 FPS at 720p and 1080p. The APK never contains game data.

**Target architecture** (everything runs in one app process):
```
SDLActivity (Java, SDL2 2.30.x)
 └ libmain.so  SDL_main: set env, chdir to game dir, call box64_run(darkest.bin.x86_64)
    └ libbox64.so  (bionic NDK build, patched into a shared library)
        ├ wrapped libc/libm/libdl/libpthread/librt → bionic
        ├ wrapped "libSDL2-2.0.so.0" → libddglue.so (SONAME libSDL2-2.0.so.0) → real libSDL2.so
        │    glue: forces ES2 context, initialises gl4es, trackpad touch layer, cursor, back→Esc
        ├ wrapped "libGL.so.1" → gl4es libGL.so (BOX64_LIBGL) → Adreno GLES2
        ├ wrapped "libasound.so.2" → libddaudio.so (ALSA PCM subset on AAudio)
        └ emulated x86_64: darkest.bin, libfmod*.so.14, libstdc++.so.6 + libgcc_s.so.1 (Debian 12 amd64)
```

**Research findings that shape this plan:**
- **Biggest risk:** upstream Box64 says its bionic build *"won't run linux binaries"*. It is tested only on x86_64 Android binaries, and the game is a glibc binary.
  - Neither libstdc++ nor libgcc_s is wrapped on bionic, so x86_64 copies of both must be shipped.
  - Plan: test this first and cheaply (step 2.1). If it fails, stop and re-plan.
- **No prior art for Box64 running inside the app process.** Winlator and Mobox exec it as a child process. HarmonyBox turns it into a shared library with `box64_run()`, which is easy because `main()` is just `initialize()` + `emulate()`.
- **Box64 build flags:** `-DANDROID=1 -DARM_DYNAREC=1 -DBAD_SIGNAL=1`. **Not `-DSD845`:** that targets ARMv8.2, and the Cortex-A73 in this phone is ARMv8.0, so it would crash with SIGILL.
- **GL lookups:** the game loads GL through `glXGetProcAddressARB` and never calls `SDL_GL_GetProcAddress`. Its own GL calls therefore go through gl4es, provided gl4es exports the `glX*GetProcAddress*` names.
- **SDL on Android can't create a desktop-GL context**, because `eglBindAPI(EGL_OPENGL_API)` fails. The glue must force an ES 2.0 context, then run `initialize_gl4es()` on it (the xash3d/OpenMW pattern). It wraps the swap with `gl4es_pre_swap`/`gl4es_post_swap`.
- **gl4es emits GLSL ES 1.00.** The game's `shaders/blur_horiz.glsl` and `blur_vert.glsl` use array initialisers that ES 1.00 forbids. Patch them in the phone's copy only; the PC install is never touched.
- **Cursor:** the game sets a colour cursor (`SDL_CreateColorCursor`), which SDL on Android doesn't draw for touch. Draw it as an Android view on top of the game, positioned from the glue. That avoids touching gl4es's GL state.
- **Audio:** FMOD tries libpulse, then ALSA. Its ALSA path uses 31 callback-free `snd_*` calls, including device hints for "sysdefault". If neither library loads, the game logs "audio disabled" and runs silently. So video comes first and audio second.
- **Android packaging:** only `lib*.so` files are extracted from an APK. So gl4es ships as `libGL.so`, found through `BOX64_LIBGL`. The shims carry the SONAMEs Box64 asks for, and are loaded before Box64 starts. **Unverified:** that bionic matches an already-loaded library by its SONAME. Fallback: an `ALTNAME` patch in Box64.

**Build host:** the PC already has NDK 27/28/29, SDK CMake 3.22/4.1, JDK 17 and Android Studio.
- Native sources and build trees live in `$DD_BUILD` (default `C:\dd-build`), outside the repo: the `®` in the repo path is risky for CMake and Ninja, and keeping the trees out keeps the repo small.
- Only scripts, patches and our own C/Java code go in the repo.
- Box64 and gl4es build with the Windows NDK. If Box64's CMake fails on Windows, fall back to a Docker Linux NDK.

### Repo layout (new)
```
port/apk/
  fetch-and-build-natives.sh   pins + fetches box64 (release tag), gl4es (master ≥ PR #509), SDL2 2.30.x
                               into $DD_BUILD, applies patches, builds libbox64.so + libGL.so
  patches/box64-*.patch        shared-lib entry box64_run(); box64lib = own handle; (ALTNAMEs if needed)
  patches/gl4es-*.patch        export glXGetProcAddress/ARB aliases
  native/launcher/main.c       SDL_main: env, paths, first-run copy of binaries, box64_run
  native/ddglue/               successor of port/phone/sdl-tapfix/tapfix.c (reuses its tap-hold logic)
  native/ddaudio/              ALSA→AAudio shim
  app/                         Gradle project from SDL2 android-project (org.libsdl.app + our Activity subclass)
  x64lib/README                which Debian 12 amd64 .debs libstdc++6/libgcc-s1 to fetch (not committed)
```

### Steps

#### 2.0 Housekeeping
- [x] Phase 2 plan added to `notes/todo.md` as its own section (the pending FPS work to be committed before it).

#### 2.1 Feasibility gate: bionic Box64 vs. glibc binaries (no APK yet, adb shell only)
- [x] Build Box64 with the NDK (generic ARM64 dynarec, not SD845), as a plain executable.
- [x] Get x86_64 test programs and libraries (done with the chroot's `g++-x86-64-linux-gnu` instead of Docker; sources in `port/apk/tests/`):
  - test programs built with Docker `debian:12` amd64: C hello, and C++ with iostream, locale, threads and exceptions;
  - `libstdc++.so.6` and `libgcc_s.so.1` from the Debian 12 amd64 `.deb`s.
- [x] Run them from `/data/local/tmp` as the shell user, with `BOX64_LOG=1`.
- [x] Run `darkest.bin.x86_64` from the phone's game copy with no SDL present. See how far it gets before it needs SDL, and record every missing symbol.
- **GATE:**
  - Tests pass, or only a handful of libc symbols are missing and can be added to the wrappedlibc patch: continue.
  - Breakage is deep (pthread, locale or stdio ABI): **stop and re-plan with options.** One fallback is a glibc Box64 child process rendering into an in-app surface.

#### 2.2 Native pieces
- [ ] **Box64 patch:** `add_library(SHARED)` and an exported `box64_run(argc, argv, envp)`. `box64lib` gets its own `dlopen("libbox64.so")` handle, because inside an app `dlopen(NULL)` returns `app_process64`. Also fix any `/proc/self/exe` assumption.
- [ ] **gl4es:** build with `-DNOX11 -DNOEGL -DNO_LOADER -DNO_INIT_CONSTRUCTOR -DUSE_ANDROID_LOG` as a shared `libGL.so`, plus the `glX*GetProcAddress*` aliases.
- [ ] **ddglue** (`libddglue.so`, SONAME `libSDL2-2.0.so.0`, linked against `libSDL2.so` and `libGL.so`):
  - `SDL_GL_SetAttribute`/`ResetAttributes`: force an ES 2.0 profile.
  - `SDL_GL_CreateContext`: create the real context, then `set_getprocaddress(real SDL lookup)`, `set_getmainfbsize`, then `initialize_gl4es()`.
  - `SDL_GL_GetProcAddress`: return `gl4es_GetProcAddress`.
  - `SDL_GL_SwapWindow`: `gl4es_pre_swap`, the real swap, `gl4es_post_swap`; also count frames and log FPS to logcat once a second, replacing the Gallium HUD.
  - `SDL_GetBasePath`: point at the game's `_linuxnosteam/`.

#### 2.3 First light: minimal APK, video only
- [ ] **Gradle app** from SDL2 android-project:
  - Libraries load in the order SDL2, GL, ddglue, box64, main.
  - minSdk 29, targetSdk 34, landscape, immersive fullscreen.
  - `extractNativeLibs=true` with `useLegacyPackaging`.
- [ ] **Game location:**
  - Data lives in app-specific external storage, `/sdcard/Android/data/<pkg>/files/game`, which needs no permission and can be filled with `adb push`. Here, a root `cp` from the chroot copy is faster.
  - The binaries (`darkest.bin`, `lib64/`) and the x86_64 libraries go to internal `filesDir` on first launch, in case `/sdcard` is noexec for mmap.
- [ ] **Patch the two blur shaders** in the phone copy; keep `.orig` files.
- [ ] Debug-build, `adb install`, launch, collect logcat and the Box64 log.
- **Milestone:** title screen rendered, with a screenshot plus logcat FPS. Silent is fine at this point.

#### 2.4 Input and cursor (port tapfix into ddglue)
- [ ] `SDL_TOUCH_MOUSE_EVENTS=0`. Turn finger events into a trackpad:
  - relative motion moves a virtual cursor (motion events from `which=0`);
  - one-finger tap is a left click, with tapfix's minimum hold of ≥2 frames and 60 ms;
  - two-finger tap is a right click;
  - tap-then-hold drags.
- [ ] Override `SDL_GetMouseState` and `SDL_WarpMouseInWindow` to match the virtual cursor.
- [ ] Draw the cursor as an overlay view, using the bitmap from `SDL_CreateColorCursor` and moved through JNI.
- [ ] `SDL_ANDROID_TRAP_BACK_BUTTON=1`; the back button sends Esc.
- [ ] **Verify** (per lessons.md): an *instant* tap registers, a right-click works, and dragging works in the inventory.

#### 2.5 Audio
- [ ] **ddaudio** (`libddaudio.so`, SONAME `libasound.so.2`, about 500 lines): the 31 `snd_*` calls FMOD uses, on AAudio.
  - S16 and FLOAT samples, 48 kHz, 2 channels (downmix if more are requested), about 80–100 ms of buffer.
  - Blocking `writei` and non-blocking `-EAGAIN`, with an accurate `avail_update`.
  - Device hints "default" and "sysdefault:CARD=Android"; reopen the stream if the device disconnects.
- [ ] **Verify** from FMOD's/the game's log lines ("Picking Output[...]"), then by ear (needs manual testing).

#### 2.6 Usability and measurement
- [ ] Migrate the saves from the chroot to wherever `SDL_GetPrefPath` points on Android.
- [ ] Pause/resume (app switching) without losing the GL context.
- [ ] **FPS** at title, town and battle, at 720p and native 1080p, compared with the VirGL numbers (51 FPS at 720p). Re-check whether the GPU governor still idles low; locking the clock needs root, so add that only if it's needed.
- [ ] A short how-to in `notes/` covering building the APK and putting game data on the phone.

### Verification
- **Each gate is proven** with logs, a screenshot pulled over adb, and logcat FPS (one sample per second from the glue's swap counter).
- **Needs manual testing on the phone:** how input feels, how audio sounds, longer play sessions, heat and battery.
- **Results** go in a review table in `notes/todo.md`. Lessons go in `notes/lessons.md`.

### Risks (and mitigations)
1. **Bionic Box64 can't run the glibc game.** Gate 2.1; patch wrappedlibc, or re-plan.
2. **Running in the app process** (ART's signal chain on dynarec SIGSEGVs, seccomp SIGSYS e.g. on `statx`, a non-PIE load at 0x400000). Mitigations:
   - log with `BOX64_SHOWSEGV`;
   - install a SIGSYS logger;
   - check `/proc/self/maps`;
   - use `setenforce 0` only to rule SELinux in or out while debugging.
3. **Bionic doesn't resolve by SONAME:** add `ALTNAME`s in the Box64 patch.
4. **gl4es shader or precision problems on Adreno 540:** use gl4es master from PR #509 or later; `LIBGL_LOGSHADERERROR=1`.
5. **App storage:** FUSE slowing down asset loading, or noexec on `/sdcard`: measure load time, and move binaries (or everything) internal.

#### 2.1 gate result (2026-10-21): FAILED the "handful" threshold, so re-planning
- Box64 (main @ d96343f, same as the phone) builds for Android with the Windows NDK r27 and SDK CMake 4.1.2 on the first try. Build tree: `$DD_BUILD/build/box64-exe`.
- Its Android libc wrapper imitates **bionic** for x86_64 Android programs: they start through `__libc_init`, and there's no `__libc_start_main`. So no glibc program starts at all; that covers smoke-c, smoke-cpp and the game.
- glibc-only symbols bionic lacks (from the smoke tests and the game):
  - startup and errors: `__libc_start_main`, `__errno_location`, `__assert_fail`;
  - ctype tables: `__ctype_b_loc`, `__ctype_tolower_loc`, `__ctype_toupper_loc`;
  - locale: `__newlocale`, `__duplocale`, `__freelocale`, `__uselocale`, and the `__*_l` functions (`strcoll`, `strftime`, `strtod`, `strtof`, `strxfrm`, `towlower`, `towupper`, `wcscoll`, `wcsftime`, `wcsxfrm`, `wctype`, `iswctype`, `nl_langinfo`);
  - gettext family;
  - misc: `__xpg_basename`, `__xpg_strerror_r`, `bcmp`, `explicit_bzero`, `glob`.
- Functions present in bionic that behave differently from glibc:
  - `sysconf` and `nl_langinfo` use different numbers;
  - `strerror_r` and `basename` are the POSIX versions, where glibc programs expect GNU;
  - `glob_t` layout and the GLOB_* flag/error values differ;
  - libstdc++ reads glibc's `locale_t` struct fields (the ctype tables) directly.
- Unknown remainder: anything resolved later with dlsym, and anything only found after the above is fixed.
- **Decision (re-plan):** write the glibc layer ourselves, time-boxed. The fallbacks are glibc Box64 as a child process plus Gladio GL forwarding, or packaging the VirGL pipeline.

#### 2.1b glibc-on-bionic layer for Box64 (time-boxed)
The work happens on a branch `dd-glibc-on-bionic` in `$DD_BUILD/src/box64` (main @ d96343f). It is exported as `port/apk/patches/box64-glibc-on-bionic.patch`. Everything is gated `#ifdef ANDROID` and written in upstream style, so existing Android-guest support stays intact.
- [x] **Constants:** fix `port/apk/patches/gen-glibc-consts.sh`; it currently pushes to a bad path and should use `/data/local/tmp`. Generate `glibc_consts.h` (glibc x86_64 value → bionic constant for `_SC_*`, `nl_langinfo` items and `GLOB_*`).
- [x] **`src/emu/entrypoint.c`:** compile `my___libc_start_main` on Android as well; keep `__libc_init`.
- [x] **`wrappedlibc_private.h`, Android branch:**
  - Renames with `GO2`, no new code:
    - `__errno_location`→`__errno`, `bcmp`→`memcmp`;
    - `__xpg_basename`→`basename`, while GNU `basename`→`__gnu_basename`;
    - `__xpg_strerror_r`→`strerror_r`, while GNU `strerror_r`/`__strerror_r`→`__gnu_strerror_r`;
    - `__X_l`→bionic `X_l` for strcoll, strftime, strtod, strtof, strxfrm, towlower, towupper, wcscoll, wcsftime, wcsxfrm, wctype, iswctype.
  - New `GOM` entries implemented in `wrappedlibc.c`:
    - `__assert_fail`, which goes to `__assert2` with its arguments reordered;
    - `__ctype_b_loc`/`_tolower_loc`/`_toupper_loc`, from C-locale tables in glibc format built in `ctSetup()` (empty on Android today);
    - `(__)newlocale`/`duplocale`/`freelocale`/`uselocale`, which return a fake glibc `__locale_struct` whose `__ctype_*` fields point at those tables (bionic only has C/C.UTF-8 anyway);
    - `nl_langinfo` and `(__)nl_langinfo_l`, with item translation;
    - `glob`/`glob64`/`globfree(64)`, converting `glob_t` layout plus flags and error codes;
    - `explicit_bzero`;
    - the gettext family (`gettext`, `dgettext`, `dcgettext`, `ngettext`, `dngettext`, `dcngettext`, `bindtextdomain`, `bind_textdomain_codeset`, `textdomain`) as identity stubs.
  - Translate `sysconf` names inside the existing `my_sysconf`.
- [x] Rebuild, then run smoke-c, smoke-cpp and the game as in 2.1. Fix whatever surfaces.
- **Time box (stop and re-plan if exceeded):**
  - **Done when:** `SMOKE-C OK` and `SMOKE-CPP OK`, and the game runs through its startup to its own SDL video init error. No native SDL is present yet, so reaching its error message is the pass.
  - **Stop when:** after this list, two more fix rounds still leave over 10 new gaps, or a gap needs structural Box64 changes (TLS, signals, pthread internals, FILE internals). Then come back with options; the fallbacks are glibc Box64 as a child process plus Gladio GL forwarding, or packaging the VirGL pipeline.


#### 2.1b result (2026-10-21): PASSED inside the time box
- `SMOKE-C OK` and `SMOKE-CPP OK`, run as the shell user from `/data/local/tmp`.
- The game runs `plinth_main`: it reads its options, sets its random seed, creates `~/.local/share/Red Hook Studios/Darkest`, and stops at its own "Video subsystem has not been initialized" error with exit code 0.
- The only unresolved symbols left are two `glX*` functions, missing only because the stand-in GL library (`libGLESv2.so`) lacks them; gl4es provides them.
- **Patch:** `port/apk/patches/box64-glibc-on-bionic.patch`, 7 files, about 1,060 lines, of which 625 are the generated constants header. Applies cleanly to box64 d96343f; exclude the build-generated `src/wrapped/generated/*` and `src/emu/x64printer.c`. Beyond the planned list it contains:
  - stubs for `pthread_setcancelstate`, `pthread_setcanceltype`, `pthread_testcancel` and `pthread_cancel`, since bionic has no thread cancellation;
  - **an upstream Box64 bug fix:** the libGL wrapper's `PRE_INIT` opened `BOX64_LIBGL` but then still required `libGL.so.1` (a missing `else`), so `BOX64_LIBGL` alone never worked.
- **Found along the way:**
  - The game needs `HOME` set. With `HOME=/` (as under `su`), a storage device is never created, and it crashes in `strlcpy` through `StorageDevice::GetName()` on a null device. The APK must set `HOME`.
  - The phone's kernel has a 39-bit address space; Box64 notices this and adapts.
  - Git on this PC uses `core.autocrlf=true`, so the box64 clone is set to `false` to keep the patch LF-only.

#### 2.2 / 2.3 progress (2026-10-21)
- [x] **Box64 as a shared library** (`patches/box64-shared-lib.patch`, CMake option `BOX64_SHARED`):
  - exports `box64_run()`;
  - gives initialize() a writable, contiguous copy of argv, because it shifts the strings in place;
  - gets `box64lib` and `box64path` from the library itself, because `dlopen(NULL)` and argv[0] point at the host process.
  - Smoke tests pass through `tests/box64-lib-runner.c`.
- [x] **gl4es** (`patches/gl4es-android.patch`) builds as `libGL.so` with `glXGetProcAddress(ARB)`, `glXQueryVersion` and `glXGetClientString`.
- [x] **ddglue**, the **launcher**, **DDActivity** and the Gradle project build into an APK of about 7.9 MB.
  - `build-natives.sh` rebuilds everything from pinned sources plus our patches; `build-apk.sh` copies the project to `$DD_BUILD/apk`, because Gradle refuses non-ASCII paths.
  - Game data goes in `/sdcard/Android/data/org.ddport.darkest/files/game`: a root copy from the chroot takes 32 s; set ownership to the media_rw UID and the app's external-data GID, and directory mode 2770.
  - `patch-shaders.sh` fixes the two blur shaders and keeps `.orig` copies.
- **Verified in the app:**
  - Box64 loads the glue as "native(wrapped) libSDL2-2.0.so.0", so bionic does match an already-loaded library by SONAME.
  - gl4es loads as libGL through `BOX64_LIBGL`.
  - External storage isn't executable, so the binary and `lib64/` are copied into private storage.
- **BLOCKER (risk 2):** `darkest.bin.x86_64` is non-PIE and must load at 0x400000–0x3903a10.
  - Every zygote-forked app process has ART's Java heap ("dalvik-main space") reserved from 0x02000000, which overlaps that range.
  - Box64 logs "cannot create memory map (@0x400000 0x3503a10)", maps the binary elsewhere, and the game crashes on its absolute addresses.
  - The heap comes from zygote, so it can't be moved per app. **So in-process Box64 can't run this game.** Re-planning.

## Phase 2 re-plan: two-process design (2026-10-21)
### Architecture
```
App process (Java, no SDL)                          Child process (exec'd: libddhost.so from nativeLibraryDir)
 DDActivity: fullscreen SurfaceView, trackpad      libddhost (main): env, connect socket, PR_SET_PDEATHSIG, box64_run
   input → JNI → socket                              └ libbox64.so → x86_64 game + FMOD + libstdc++
 libddview.so (JNI + render thread):                    ├ "libSDL2-2.0.so.0" = libddglue.so → libSDL2.so (offscreen video,
   listens on abstract unix socket                      │     no joystick/haptic/sensor: no Java needed)
   receives AHardwareBuffers (sendHandleToUnixSocket)   │   glue: EGL pbuffer + GLES2 ctx + gl4es, AHB triple buffer,
   EGLImage → texture → fullscreen quad + cursor        │   display/window size from app, input → SDL events (tapfix),
   eglSwapBuffers (vsync) → RELEASE(buffer)             │   cursor bitmap/visibility → app
          ⇅ socket: SURFACE w,h · INPUT · BUFFERS · PRESENT i · RELEASE i · CURSOR
                                                        ├ "libGL.so.1" = gl4es libGL.so → Adreno GLES
                                                        └ "libasound.so.2" = libddaudio.so → AAudio (2.5)
```
- **Per frame:** at `SDL_GL_SwapWindow`, the glue copies the pbuffer into a free AHB texture (`glCopyTexSubImage2D`, about 1 ms at 1080p, with the GL state it touches saved and restored around gl4es). It then sends PRESENT. The app draws that buffer and the cursor, swaps with vsync, and RELEASEs the previous buffer. With no free buffer, the child waits, so the app's vsync paces the game.
- **Facts checked:**
  - The NDK has `AHardwareBuffer_sendHandleToUnixSocket` (API 26).
  - SDL2 has an offscreen driver; its GL path needs Mesa EGL device extensions, so the glue makes the EGL context itself.
  - SDL's AAudio backend only calls Java for capture.
  - On this phone, exec'd app children already use EGL and AAudio (Termux's virgl server, PulseAudio).
  - Exec from `nativeLibraryDir` is allowed at targetSdk 34.

### Reused / changed pieces
- **Kept as is:** `patches/box64-glibc-on-bionic.patch`, `patches/box64-shared-lib.patch` (the host exe links libbox64.so), `patches/gl4es-android.patch`, `build-natives.sh`, `build-apk.sh`, `patch-shaders.sh`, the game copy on the phone and the shader patch.
- `native/launcher/main.c` becomes `native/host/main.c`, an executable named `libddhost.so`:
  - same env setup;
  - socket name from `DD_SOCKET`;
  - stdout and stderr to `dd.log`.
- `native/ddglue/ddglue.c` is extended:
  - GL and window functions on EGL plus AHB;
  - display queries;
  - `SDL_PollEvent` fed from the socket, keeping tapfix's minimum hold from `port/phone/sdl-tapfix/tapfix.c`;
  - `SDL_CreateColorCursor`, `SetCursor`, `FreeCursor`, `ShowCursor` and `WarpMouseInWindow`;
  - `SDL_GetPrefPath`, `SDL_ShowSimpleMessageBox` and `SDL_OpenURL`, which would otherwise reach SDL's Java side.
- New `native/ddview/ddview.c`: the app-side socket server, AHB import and presentation.
- `DDActivity.java` is rewritten as a plain Activity:
  - SurfaceView;
  - spawns the child with ProcessBuilder (env, nativeLibraryDir on `LD_LIBRARY_PATH`);
  - trackpad gestures, ported from what works in Termux:X11: one finger moves the cursor, a tap is a left click, a two-finger tap is a right click, and tap-then-hold drags;
  - the back button sends Esc.
- `app/jni/CMakeLists.txt`:
  - builds SDL2 with `SDL_JOYSTICK`, `SDL_HAPTIC`, `SDL_SENSOR` and `SDL_HIDAPI` OFF;
  - builds ddglue, ddhost (`add_executable` with `OUTPUT_NAME`/`SUFFIX` set so the file is `libddhost.so`) and ddview.
- The SDL Java sources are no longer compiled into the app.

### Steps
#### 2.3' First light (video only)
- [x] ddview plus the new DDActivity: socket server, surface lifecycle, and spawning the child (log to `dd.log`). *(written and builds; not run yet)*
- [x] Host and glue: EGL pbuffer and context, gl4es init, AHB triple buffer sent to the app, PRESENT/RELEASE in the swap, window and display size from the app. *(written and builds; not run yet)*
- [ ] Build, install and launch. **Milestone:** a screenshot of the title screen, plus logcat FPS from the glue (one per second) and from ddview.

#### 2.4 Input and cursor
- [ ] Trackpad in DDActivity; INPUT messages; the glue turns them into SDL mouse and key events with tapfix's minimum hold.
- [ ] Cursor bitmap and hotspot sent to ddview, drawn over the frame; `WarpMouseInWindow` keeps both sides in sync.
- [ ] **Verify:** an instant tap registers, right-click works, and inventory drag works.

#### 2.5 Audio
- [ ] `libddaudio.so` (SONAME `libasound.so.2`): the 31 `snd_*` calls on AAudio (design as before), loaded in the child before Box64.
  - [x] `native/ddaudio/ddaudio.c`, built by CMake with SONAME `libasound.so.2`, preloaded by `host/main.c` like the glue.
  - [x] **Verify:** FMOD lists the device and opens it (`dd.log`, logcat `ddaudio`), and a second AudioFlinger track appears.
  - [ ] **Verify:** the cinematic still plays, and the first dungeon loads without the FMOD SIGSEGV.
  - [ ] **Manual:** title music, effects in town and battle, plugging headphones in and out.

#### 2.6 Usability and measurement
- [ ] Pause and resume: the surface is destroyed and recreated while the child keeps running, blocked on buffers.
- [ ] Migrate the chroot saves into `$HOME/.local/share/Red Hook Studios/Darkest`.
- [ ] **FPS** at title, town and battle, at 720p and 1080p, against the 51 FPS VirGL baseline; check the GPU governor.
- [ ] A how-to in `notes/`.

### Verification
- **Each milestone:** an adb screenshot, logcat FPS, and `dd.log` from the child.
- **Needs manual testing:** how input feels, audio, long sessions, heat and battery.
- **Results** go in a review table in `notes/todo.md`. Lessons go in `notes/lessons.md`.

### Risks
1. **SDL's Android build calling into Java in the child**, where there's no JVM. Disable those subsystems, override the remaining entry points in the glue, and watch for crashes in `Android_JNI_*`.
2. **gl4es state corrupted by the copy at swap time.** Save and restore the active texture, the 2D binding and the framebuffer binding. Fallback: render through gl4es's main-FBO mode.
3. **Synchronisation.** Start with `glFinish` before PRESENT; move to native fence fds (`EGL_ANDROID_native_fence_sync`) if FPS needs it.
4. **SELinux or seccomp in the exec'd child.** It's the same domain as the app (`untrusted_app`), and Termux proves EGL and AAudio work there.

#### Two-process build status (2026-10-21, paused)
- **Written, builds, NOT TESTED on the phone:**
  - `native/common/ddproto.h`;
  - `native/ddview/ddview.c` (app side: socket server, AHB import, draws frame + cursor, vsync, RELEASE);
  - `native/host/main.c` (`libddhost.so`, an executable; env setup, connects, dlopens the glue, `box64_run`);
  - the reworked `native/ddglue/ddglue.c`:
    - EGL pbuffer, gl4es, the AHB triple buffer and the copy at swap;
    - display and window sizes;
    - socket input through tapfix's minimum hold;
    - cursor forwarding;
    - joystick/haptic/sensor masked out of SDL_Init;
    - pref path, message box and URL;
  - `DDActivity.java` (plain Activity, SurfaceView at a fixed 1280×720, trackpad, back → Esc, child process).
  - Input and cursor (2.4) are in this code too.
- **Build fixes along the way:**
  - SDL's Android core won't compile without joystick/haptic, so they stay built and the glue masks those init flags instead.
  - The host must not link the glue: its DT_NEEDED would name the SONAME `libSDL2-2.0.so.0`, a file that doesn't exist.
- **Next, with the phone:**
  1. `adb install -r $DD_BUILD/gradle/app/outputs/apk/debug/app-debug.apk`. The game copy and the shader patch are already on the phone.
  2. Launch, then collect `logcat -s ddview ddglue DDActivity` and `/data/data/org.ddport.darkest/files/dd.log` (root), plus a screenshot.
  3. Expected first issues: image orientation (flip v in ddview's `quad()`), the gl4es state around the swap copy, and SDL calling into Java somewhere.

#### 2.3' / 2.4 results (2026-10-22): the game runs and responds in the APK
- **Rendering:** the intro, the title screen and the splash screens render through gl4es at a steady **60 FPS**, measured both in the game process (ddglue) and in the app (ddview). It was about 51 at 720p in the chroot. Image orientation and the cursor overlay are correct.
- **Fixes needed on the way:**
  - **Game folder:** the game finds its data next to its binary, so it now runs from a private mirror: a real `_linuxnosteam/` plus symlinks to everything else (`DDActivity.mirrorGame`). It replaces some files with its own (`app.log`, `crash_detection`), and those stay in the mirror.
  - **SDL parts that need Java, in the game process:** `SDL_RWFromFile` is overridden with plain `fopen`. `SDL_SetMainReady()` is called. SDL audio uses `openslES`, because AAudio lists devices through Java. `patches/sdl2-no-java.patch` makes `Android_JNI_AudioSetThreadPriority` do nothing when there's no Java VM.
  - **GL attributes:** `SDL_GL_GetAttribute` asks GL through `SDL_GL_GetProcAddress`, which is gl4es, before gl4es exists. The glue now records depth and stencil from `SDL_GL_SetAttribute` instead.
  - **Shaders** (`patch-shaders.sh`): 3D colour lookups go through `lut_texture3D()` over gl4es's slice atlas (`patches/gl4es-3d-texture-atlas.patch`); integer literals in float math become floats. All 6 patched shaders compile and link. `patches/gl4es-log-link-errors.patch` makes gl4es log link failures.
  - **Focus:** without SDL_WINDOWEVENT_FOCUS_GAINED the game ignores all mouse and keyboard input, through its `g_windowfocused` check. The app sends `DD_FOCUS` when its surface appears or goes away, and the glue turns it into window events.
- **Saves:** the chroot profile was copied to `files/.local/share/Red Hook Studios/Darkest`, owned by the app, with label `app_data_file` plus the app's SELinux categories. `restorecon` alone gave plain `s0`.
- **Open:**
  - FMOD has no output, because there's no `libasound.so.2` yet (2.5). The cinematics' SDL audio should play through OpenSL; needs a manual check.
  - Trackpad feel, right-click and drag need manual testing.
  - The town and battle shaders (colour lookup, character tint) render, but haven't been compared by eye with the chroot version yet.

#### Cinematic audio (2026-10-22): garbled → fixed; stutter → cause found, fix proposed
- **What played:** only the cinematics' SDL audio (theoraplay → `SDL_OpenAudio`, 44.1 kHz S16 stereo → OpenSL). FMOD found neither libpulse nor libasound, so it is silent (2.5).
- **Method:** a temporary glue override of `SDL_OpenAudio` dumped every buffer the game's callback wrote. The dump was compared with `ffmpeg`'s decode of `video/house_of_ruin.ogv`.
- **Garbled. Cause:** Box64's Android stand-in for `qsort_r` (bionic has none before API 36) was a broken quicksort that didn't sort. libvorbis sorts its codebooks and floor1 posts with `qsort`. That gave garbage floats (14% clipped, NaN → 0 samples) and, in the interpreter, a divide by zero in `floor1_inverse2`.
  - **Fix:** `patches/box64-android-qsort.patch` uses bionic's `qsort`, with the comparator in a thread-local. After it, the audio is **sample-exact** with the reference.
  - Every `qsort` the game and FMOD make went through this, so the FMOD crash at dungeon start (SIGSEGV in `libfmod.so.14 + 0x18e9e9`) needs re-testing.
- **Stutter. Cause:** the cinematics are 1920×1080 Theora, decoded under emulation by the same thread that decodes the audio. In heavy scenes it falls behind, and the game's audio queue runs dry for 70–335 ms at a time: 27 gaps in 85 s. SDL's callback timing was clean (46.4 ms mean, 60.9 ms max), and AudioFlinger reported 0 track underruns.
  - **Experiment:** re-encoded to 1280×720 (`ffmpeg -vf scale=1280:720 -c:v libtheora -q:v 7 -c:a copy`; the game renders at 720p anyway). The result: 3 blips of ≤ 23 ms in 60 s, otherwise sample-exact. Only `house_of_ruin.ogv` is swapped on the phone so far (original kept as `.ogv.orig`).
- [ ] Decide: make the 720p transcode a script for all three cinematics.
- [x] Re-test the first dungeon for the FMOD crash, with the qsort fix in. (Loaded about ten times since, with no crash.)

#### 2.5 result (2026-10-22): FMOD plays through the ALSA shim
- **`dd.log`:** "= ALSA =", then `0 - "default"`, then "Picking Output[0] Driver[0] and SpeakerMode[3]", then "Initialized Audio".
- **What FMOD asks for** (logcat `ddaudio`):
  - It probes with 4 open/close pairs, then opens "default" non-blocking.
  - S16 stereo at 48 kHz, period 1024 frames, buffer 10240 (213 ms); start threshold 10240, avail_min 1024.
  - It writes 1024 frames at a time.
- **AudioFlinger:** two tracks from the game process, the cinematic (44.1 kHz) and FMOD (48 kHz), both with 0 underruns. The game holds a steady 60 FPS.
- **Open:** listening on the phone (music, effects, how latency feels with a 213 ms buffer); the first dungeon (the earlier FMOD SIGSEGV); headphones in and out.

#### Black dungeon/battle world (2026-10-22): 3D LUT binds never reach the GPU
- **Seen:** in the dungeon only the UI draws; background, heroes and enemies are black. No shader errors.
- **Cause:** the game binds its colour-grade LUTs with `Texture_Bind3D()` (`glBindTexture(GL_TEXTURE_3D)` on units 1–3, never `glEnable(GL_TEXTURE_3D)`). gl4es defers binds to draw time, and `realize_textures()` picks each unit's target from the fixed-function `glEnable` bits. So the LUT units get their empty 2D slot, which samples black; `film.glsl` then blacks out the whole world.
- [x] `patches/gl4es-3d-texture-units.patch`: `sampler3D` → `sampler2D` in shader conversion; at link, samplers declared `sampler3D` become `TU_TEX3D`; `realize_textures()` binds a program's 3D-sampled units from their 3D slot and covers every unit the program samples.
- [x] `patch-shaders.sh`: keep `uniform sampler3D` (gl4es rewrites it and learns from it).
- [x] Build (all patches apply), install, re-patch shaders, no shader errors; intro at 60 FPS.
- [x] Dungeon renders (confirmed on the phone, screenshot). FPS there is ~15: separate investigation below.

#### Dungeon FPS (2026-10-22): ~15 → ~51
- **Seen:** the dungeon ran at ~15 FPS (the title at 60). GPU 34% busy at 257 MHz; pinning it to 710 MHz gave only ~16.5.
- **Cause** (`/proc` wchan sampling, then `simpleperf` on `sched_switch` with DWARF stacks): the main thread's waits were ~93% `mmap`/`munmap` from Adreno's GL driver (`gsl_memory_alloc_pure`, `freememontimestamp`), queued on the address-space lock.
  - The game streams vertices through a ring buffer, mapped with `0x32` (WRITE | FLUSH_EXPLICIT | UNSYNCHRONIZED) in `DrawManager` / `VertexStreamBuffer`.
  - gl4es turns every flush into `glBufferSubData` on the real GLES buffer (`buffers.c`). The buffer is always in use, so Adreno copies it whole and frees the old one later, once per draw batch.
- **Fix:** `LIBGL_USEVBO=0` (`native/host/main.c`), gl4es's documented switch: buffers stay in CPU memory and draws use client arrays, so the driver copies only what each draw uses.
- **Result**, same scene: ~47–55 FPS (mostly ~51). No `mmap` waits left; GPU 51% busy, still at 257 MHz.
- [ ] Next, separately: the remaining gap to 60. The GPU governor stays at its lowest clock and the main thread waits on the GPU part of the time (`adreno_drawctxt_wait`).

#### Dungeon FPS ~51 → 60 (2026-10-22): GPU fences instead of glFinish
- **Cause:** 43% of the main thread's blocking is in `SDL_GL_SwapWindow`, and 27.5% of it is the per-frame `glFinish`. CPU and GPU take turns, the GPU looks half idle, and the governor stays at 257 MHz. Pinned, the GPU gives a solid 60.
- [x] `ddproto.h`: `dd_send` / `dd_recv`, which carry a fence fd as `SCM_RIGHTS`.
- [x] ddglue: present fence instead of `glFinish` (fallback: `glFinish`, no fd); GPU-side wait on a buffer's release fence before copying into it.
- [x] ddview: GPU-side wait on the present fence; one release fence per draw, attached to the RELEASE messages.
  - The fence code shared by both sides is in `common/ddfence.h`; if GPU-side waits are missing it falls back to `poll` on the fd.
- [x] Verify: dungeon at a steady 59.8–60.1 FPS with the default governor (GPU 52% busy at 257 MHz). The main thread now waits mostly for a free buffer (vsync pacing); no `waittimestamp` left. Hiding and resuming works. fd counts flat (game 27, app 155) and no fence fds pile up.
- [x] Manual: no flicker, tearing or old frames while walking (confirmed on the phone).

#### FPS 20 while the trackpad is used (2026-10-22): one draw per refresh in ddview
- **Seen:** walking (tap, then hold) gives exactly 20.0 FPS; moving the cursor alone gives the same. The game's main thread waits 91% of the time for a free buffer.
- **Cause:** each touch move wakes ddview's render loop, which handles one wake byte or one message per pass. Each one is a draw plus an `eglSwapBuffers` (vsync wait), so cursor redraws take the refresh slots and the game's frames and buffer releases wait behind them.
- [x] `render_thread`: read the wake pipe dry, handle every pending message, then draw once (newest pending frame, else a cursor redraw). An older undrawn frame is released at once.
- [x] Verify (adb, driven to the dungeon over adb input): cursor-only swipes 59.9–60 (was 20.0); walking right and left 59–60.1, with the party moving both ways; the cursor lands where expected; Home and back keeps 60; fd counts unchanged (game 27, app 155).
- [x] Manual: trackpad feel and walking confirmed fine on the phone.

#### 1080p test (2026-10-22): `GAME_W/GAME_H` = 1920×1080 in `DDActivity.java`
- **FPS (ddglue), dungeon at The Old Road:** standing 59.9–60.0; cursor-only swipes 59.8–60.1; walking right 59.9–60.9; walking left 59.8–59.9. Title and intro also 60.
- **GPU:** 78–82% busy at 342 MHz (one step up from 257; max 710), default governor. At 720p it was ~52% at 257 MHz. So the headroom is in the clock, at some cost in battery and heat.
- **Bug, 1080p only:** the Campaign save list draws nothing. The entries are there and clickable (clicking where the top save sits loads it). At 720p the list draws.
- [ ] Decide: keep 1080p or stay at 720p.
- [x] Find why the save list doesn't draw at 1080p.
  - **Method:** a temporary gl4es trace (binds, viewport, scissor, draws) of one frame on the Campaign screen at both sizes. The calls and values were identical (scaled), and the list draws into render target 2 (always 1920×1080) with scissor (200, 98, 1800, 434).
  - **Cause (gl4es):** `glScissor`/`glViewport` skip the GLES call when the box equals the cached one, but they only update the cache when x and y are ≥ 0. The game sets (200, 98, …), then (1700, −576, …) for a sliding panel, then (200, 98, …) again on target 1. The negative box reaches the GPU but not the cache, so the third call is skipped. At 720p target 2's (200, 98, …) differs from target 1's (133, 65, …) and repairs the state by luck; at 1080p it matches and is skipped too, so every list draw is clipped away.
  - **Fix:** `patches/gl4es-negative-box-cache.patch`: cache any box GLES accepts (only a negative width/height is rejected).
  - **Verified:** the save list draws at 1080p and still at 720p; the 1080p dungeon renders as before at 59.8–59.9 FPS. `build-natives.sh` applies all patches on a clean checkout.
- [ ] FPS in heavier scenes at 1080p: battle, town.

#### Green outlines in the Ruins (2026-10-22): fixed with real 3D textures
- **Seen:** bright green 1–2 px outlines along dark line art in the Ruins (and faintly elsewhere).
- **Measured:**
  - Without the colour lookup in `film.glsl` the scene is clean. All three lookups (torch start, torch end, presentation override) produce the outlines.
  - A CPU reference of `lut_texture3D()` with the real `colour_grade_4.png` matches the phone to within 1/255, except at pixels where neighbours fall in different slices. There the phone reads about two atlas rows too high (green 57 instead of 22).
  - A smooth grey ramp through the lookup shows one green column exactly at the slice-0/1 boundary. The same ramp in 4-pixel steps (no 2×2 quad straddling a boundary) is clean. So the error depends on neighbouring pixels: implicit texture-coordinate derivatives, which jump by 1/16 across a slice boundary in the atlas.
  - The game sets `GL_LINEAR`/`GL_LINEAR` and clamp-to-edge on all 35 LUTs (16×16×16 RGBA8, level 0, no `glGenerateMipmap`).
  - **GPU-side state verified (option 1 of the plan, disproven):** reading the GLES parameters back at draw time (`glGetTexParameteriv` on each atlas bound to a 3D unit) gives min/mag `GL_LINEAR`, wrap clamp-to-edge, no mipmaps (`mipmap_auto/need/done` 0), no sampler object. gl4es pushes the game's state correctly, so forcing `GL_LINEAR` cannot change anything. With LINEAR and no mips, derivatives shouldn't affect sampling, yet the error is neighbour-dependent: the next step is option 2 (real 3D textures via `GL_OES_texture_3D`), which removes the atlas and its slice maths entirely.
- **Ruled out:** LUT size (16×256 PNGs), 16-bit render targets, a dynarec bug (no Box64 involvement in the shader).
- **Not a valid test:** a `texture2D` bias of −16 and `texture2DLod`. The driver has no `GL_EXT_shader_texture_lod`, so gl4es falls back to plain `texture2D`.
- **Driver:** has `GL_OES_texture_3D` (real 3D textures in ES2).
- **Plan (option 2, decided): real 3D textures through `GL_OES_texture_3D`.** One `gl4es-3d-textures.patch` replaces the atlas and units patches; the game's own `texture3D` code runs unchanged.
  - [x] gl4es: bind 3D textures right away on the GLES 3D target; `map_tex_target` keeps 3D; `realize_textures` skips the 2D bind for 3D units but applies their parameters (with `WRAP_R`)
  - [x] gl4es: `glTexImage3D`/`glTexSubImage3D` upload through `glTexImage3DOES`/`glTexSubImage3DOES`
  - [x] gl4es: shaders keep `sampler3D` and get `#extension GL_OES_texture_3D : enable`; `GL_SAMPLER_3D` uniforms handled like 2D samplers; extension detected in hardext
  - [x] `patch-shaders.sh`: drop `lut_texture3D()`; `build-natives.sh`: new patch list; old patches deleted
  - [x] Verify: no shader errors in `dd.log`; no green outlines in the Ruins; FPS no worse; title and dungeon look right
  - [ ] Manual checks: the town (`town_post_effects`) and stealthed heroes (`character_stealth`), which aren't reachable without changing a save; the colour grading overall compared with the PC
- **Review:**
  - **Result:** gl4es now uploads 3D textures with `glTexImage3DOES` and binds them to the GLES 3D target immediately (like cube maps). Shaders keep `sampler3D`/`texture3D` with `#extension GL_OES_texture_3D : enable`, and `GL_SAMPLER_3D` uniforms are handled like 2D samplers. `patch-shaders.sh` no longer touches the lookups. `gl4es-3d-textures.patch` replaces the atlas and units patches.
  - **A/B at the same Ruins spot** (committed build vs new, 720p): greenish pixels outside the Plague Doctor's green robe trim go from 48,563 to 0, and the crops show clean line art. Standing FPS is 48–52 with the committed build and 51–54 with the new one, so it's no regression; this room was already under 60. Walking holds about 60.
  - **Shaders:** `dd.log` has no gl4es link or compile errors; all four lookup shaders (`film`, `character`, `character_stealth`, `town_post_effects`) link. The dungeon (`film`), the party (`character`) and the title render correctly.
  - **Not done:** the planned smooth-ramp test. The same-scene A/B was the stronger check. The town can't be reached without retreating from an expedition, so it's left to a manual check.
  - **Open:** this Ruins room stands at 51–54 FPS at 720p, with the GPU about 62% busy at 257 MHz. A separate item.

#### 60 FPS at 1080p in the dungeon (2026-10-22): draws stall on client-array copies
- **Seen (1080p, Ruins room, committed build):** standing 43–47 FPS. GPU ~75% busy at 342 MHz (default governor). With the GPU pinned to 710 MHz, ~59.
- **Measured:**
  - **GPU work per frame** (timer queries per render pass, `GL_EXT_disjoint_timer_query`):
    - 13.6 ms at 257 MHz, 6.5 ms at 596, 5.4 ms at 710. It scales with the clock, so it is not bandwidth-bound.
    - Largest passes: the scene (1920×720) 31%, the final copy into the pbuffer 23%, the film/colour-lookup pass 19%.
    - It fits a 60 FPS frame at the default clock.
  - **CPU:** the main thread sits in `adreno_drawctxt_wait` (driver `waittimestamp` under `glDrawElements`) about 30–40% of the time. CPU and GPU take turns again.
  - **One draw per frame blocks ~9 ms:** the 124th, a 6-vertex UI quad. Its texture is not the cause (the same with mipmaps off, or with BGRA converted to RGBA).
  - **Cause:**
    - The game streams all vertices through one ring buffer and draws with indices far into it (e.g. 3988..3991). With `LIBGL_USEVBO=0` these are client arrays whose pointers start at the buffer base, and the driver copies vertices 0..max index for every draw.
    - That is ~17 MB per frame for 154 draws that use 1,404 vertices (45 KB). Its staging memory runs out once per frame, and it waits for the GPU.
- **Experiment (temporary build):** before each client-array `glDrawElements`, attribute pointers start at the lowest index and the indices are rebased. Result: 59.8–60.0 FPS standing in the same room (GPU ~79% at 414 MHz), no stalled draws left, and the frame looks the same.
- **Plan:**
  - [x] `patches/gl4es-client-array-range.patch`, in `fpe_glDrawElements`:
    - when the indices and every enabled attribute are client memory (no real VBO, no integer attributes) and the lowest index is > 0, move the GLES attribute pointers to that index and draw with rebased indices (a reused buffer);
    - restore the pointers afterwards, so gl4es's cached GLES state stays true.
    - `glDrawArrays` stays as it is: no stalls were seen there.
  - [x] `build-natives.sh`: add the patch; comment at `LIBGL_USEVBO=0` in `native/host/main.c` (client arrays rely on it).
  - [x] `DDActivity.java`: `GAME_W/GAME_H` 1920×1080.
  - [x] Verify at 1080p, from a clean `build-natives.sh`:
    - Ruins standing and walking at ~60;
    - title and intro at 60;
    - save list draws;
    - `dd.log` has no GL errors;
    - screenshots look right.
    - Plus a quick 720p check that nothing regressed there.
  - [ ] Manual checks: battle and town at 1080p, heat and battery over a longer session.
- **Review:**
  - **Result:** client-array `glDrawElements` calls pass only the vertex range they use. The game's main thread no longer waits in the driver, and CPU and GPU overlap again.
  - **1080p, clean build** (all patches applied by `build-natives.sh`):
    - Ruins standing: 59.8–59.9 FPS (was 43–47), GPU ~79% busy at 414 MHz.
    - Walking: 59.9.
    - Title and intro: 58.8–61.1.
    - The save list works (the script loads the top save from it).
    - `dd.log`: only the known DLC hero-class messages.
  - **720p** (next room; the test save had moved on): 59.7–60.1 FPS, GPU ~65% at 257 MHz. The Ruins room stood at 51–54 there before.
  - **Hardware after ~8 minutes standing at 1080p** (on USB power, so no drain figure):
    - surface (`quiet_therm`) 41 °C, board 47 °C, CPU/GPU sensors 50–62 °C, battery 35.9 °C;
    - no CPU throttling; the GPU's thermal cap was one step below max, unused at 414 MHz.
  - **Seen once:** walking into a trap dipped to 45–54 FPS for about two seconds during its animation. It was back at 60 right after. Not investigated.
  - **Open:** battle and town at 1080p; heat and battery over a longer session unplugged (manual check).

#### Less GPU work per frame (2026-10-22): cooler and less battery at 1080p/60
- **Goal:** same picture and 60 FPS for less work, so the GPU governor settles at 342 or 257 MHz (it holds 414 MHz now, ~78% busy).
- **Where the time goes** (1080p, timer queries at 710 MHz, ≈ 5.4 ms/frame): scene 1.7 ms, characters 0.58, film/colour lookup 0.95, UI 0.62, UI composite 0.33, **final pass into the pbuffer + ddglue copy 1.15 (ours)**, plus ddview drawing the shared buffer to its window (other process, ours).
- The earlier "drop depth/stencil" idea is moot: the pbuffer already has none ("depth 0 stencil 0"). Step 1 measures the fb0 cost instead.
- **Step 0: baseline (before any change)**
- [x] Fixed test spot: a save standing in a dungeon room (the room after the Ruins room). Stand there, no input.
- [x] A local measurement script `power.sh` (not in the repo), 15 min, charging off through `input_suspend` instead of Wi-Fi adb (re-set every sample):
  - every 10 s: FPS (ddglue), GPU MHz and busy%, `quiet_therm`/`msm_therm`/battery temperature, `current_now`;
  - the result is averages plus the last 5 minutes (temperatures settle late).
  - Adb over Wi-Fi (`adb tcpip`) so the phone is not charging during the run.
- [x] Baseline run with the committed build. The same run follows each step below.
  - **Baseline** (15 min standing at the fixed test spot, phone without cover, brightness 204 manual, charging held off; game warm for ~1 h before): **2.36 W** (last 5 min 2.34), GPU 414 MHz (3 of 90 samples at 515) at 78% busy, CPU little ~1.8 GHz / big ~2.2 GHz, surface 39 °C, board 44 °C, battery 34–35 °C, FPS median 59.9 (58.8–61.9).

- **Step 1: what does the fb0 pass cost, and why (measure only, temporary builds)**
- [x] GPU timers again with three temporary variants: (a) as committed; (b) ddglue's copy skipped (the screen freezes, timers only); (c) the game's frame-start clear of the default framebuffer dropped (timers only).
- [x] Check whether the shared buffers (AHB) are UBWC (`dumpsys SurfaceFlinger` / gralloc debug, or compare a copy into an AHB-backed FBO with one into a plain texture).
- **Expected:** most of the fb0 cost is the pbuffer load/store plus the copy. If (b) and (c) show something else, stop and re-plan before step 2.
- **Step 1 results** (temporary build: gl4es per-pass timers, switch files `nocopy`/`noclear`/`vclear`/`ubwc` in the app's files dir; ddview GPU timer around its draw + swap):
  - Timers at 710 MHz, µs/frame: frame-start clear 33, scene 1600, characters 577, film 950, UI 570, UI composite 335, **fb0 1155**, copy 8; ddview's draw **~780**.
  - (b) copy skipped: fb0 still 1155. (c) clear skipped: the clear segment goes to 1 µs, fb0 unchanged. So **fb0's 1.15 ms is the game's last pass itself**, not the clear or the copy.
  - The copy's real cost is outside the timers (8 µs for 8 MB). GPU busy pinned at 414 MHz: as is 78% (three runs 78/78/78), copy skipped 77%, clear skipped 81% (worse). **Copy ≈ 1 point (~0.2 ms at 414), clear ≈ 0.**
  - kgsl memory: the game's own FBO textures and the window buffers are UBWC; the pbuffer and the three shared AHBs are plain linear (exactly 1920×1080×4).
  - **UBWC shared buffers** (`AHARDWAREBUFFER_USAGE_VENDOR_0`, Qualcomm gralloc's "allocate UBWC"): buffers become 8,392,704 bytes (UBWC), picture bit-identical in static regions, ddview's draw **780 → 290 µs**, but busy at 414 MHz **81%** (copying the linear pbuffer into a UBWC buffer costs more than ddview saves). Power 5 min 2.23 W vs baseline 2.34 W, not trusted (temperatures not comparable after restarts).
  - **Outcome:** the expectation was wrong: the pbuffer/copy path is cheap. What is ours to cut is ddview's draw (~0.78 ms at 710 ≈ 1.3 ms at 414) and the copy (~0.2 ms at 414). Step 2 alone saves only the copy, plus ddview's read if the buffers become UBWC; step 4 removes ddview's draw entirely. Re-plan before step 2.

- **Step 2: the game draws its last pass straight into the shared buffer**
**Idea:** the game's "framebuffer 0" becomes an FBO on the shared buffer that ddview will show next. No pbuffer surface, no `glCopyTexSubImage2D`.

**gl4es already has the hook:** every bind of framebuffer 0 goes to `glstate->fbo.mainfbo_fbo` (framebuffers.c), so the game needs no changes.

- [ ] **gl4es** (new small patch): export `gl4es_set_main_fbo(GLuint fbo)`. It sets `mainfbo_fbo`, and if framebuffer 0 is currently bound it rebinds the new one. gl4es's viewport/scissor cache stays valid because the size is the same.
- [ ] **ddglue:**
  - one FBO per shared buffer (`DD_BUFFERS` = 3), each with the buffer's texture as colour attachment;
  - the pbuffer shrinks to 1×1 (only to make the context current) or goes, with `EGL_KHR_surfaceless_context`.
- [ ] **ddglue `SDL_GL_SwapWindow`:**
  - no copy: flush, make the present fence, send `DD_PRESENT` for the current buffer;
  - then pick the next free buffer (wait as now: cond var, then a GPU-side wait on its release fence) and `gl4es_set_main_fbo` it;
  - the first frame gets a buffer at context creation.
  - The wait moves from the end of a frame to the start of the next one. With 3 buffers that is the same pipelining as now.
- [ ] **Orientation:** an FBO texture has row 0 at the bottom, like the pbuffer copy now, so ddview's texture coordinates stay as they are. Check this with a screenshot.
- [ ] **Edge cases:**
  - anything the game reads from framebuffer 0 (`glReadPixels`, `glCopyTexSubImage2D` with fb 0 bound) must still work on the FBO: check the trace for such calls in the town, menus and the screenshot key;
  - hiding and resuming the app (ddview holds buffers while it has no window);
  - the resolution switch (720p).
- [ ] **Verify:**
  - timers show the fb0 + copy segments shrink;
  - 60 FPS in the test room, title, save list, walking;
  - screenshots match the committed build pixel for pixel (same spot, same frame type);
  - hide and resume works; fd counts stay flat;
  - the power run from step 0.

- **Step 4: ddview hands the frame to the display hardware (no GPU pass in ddview)**
**Idea:** instead of drawing the shared buffer into its window with GL, ddview puts the buffer on its own layer with `ASurfaceControl`/`ASurfaceTransaction` (NDK, API 29+; the app's minSdk is 31). The display hardware (HWC overlay) scans it out directly, the way SurfaceFlinger shows the window today ("DEVICE" composition). The cursor becomes a second, small layer that is only moved, never redrawn.

- [x] **Feasibility check first (small test in ddview, no game changes):**
  - **Result (temporary `sc` switch in ddview, DDTEMP):** a child `ASurfaceControl` of the SurfaceView window gets the shared buffer with the game's present fence as acquire fence, `MIRROR_VERTICAL`, and releases through `setOnComplete` / `getPreviousReleaseFenceFd`.
    - `dumpsys SurfaceFlinger`: `dd-game` is **DEVICE** with transform 0x5 (the display hardware rotates and flips the linear RGBA buffer), plus SurfaceView's black `SOLID_COLOR` background layer. The picture is right (static UI bit-identical to the GL path).
    - Unpaced, the game ran at 80–90 FPS (SurfaceFlinger releases frames it drops). Paced by applying the next transaction only after the previous one completes: 59.8–60.0.
    - NDK 27's `android/surface_control.h` is not valid C (`const ARect&` in two declarations): the test declares the functions itself.
    - **Gain, 5 min each, back to back, same spot and temperatures (surface 39 °C, board 44 °C):** display hardware 2.28 W, GPU 414 MHz 77% busy; GL path 2.36 W, 414–431 MHz 78.5% busy. **About −85 mW (−3.6%)**; the GPU governor stays at 414 MHz either way.
    - The ddview timer's 0.78 ms didn't show up as 8 points of busy (pinned at 414: 77% vs 78%). kgsl busy doesn't scale with the clock (58% at 670 MHz vs 77% at 414), so it counts memory stalls too; power is the measure.
  - one `ASurfaceControl` child of the SurfaceView window;
  - set a shared buffer on it;
  - `dumpsys SurfaceFlinger` must show the layer as **DEVICE** composition (not CLIENT/GPU), also with the display's rotation: the panel is natively portrait 1080×1920 and the game is landscape. If HWC can't take it (rotation, or format without `COMPOSER_OVERLAY` usage), SurfaceFlinger would fall back to the GPU and this step gains nothing: stop and report.
  - The shared buffers may need `AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY` in ddglue's allocation.
- [ ] **ddview frame path:**
  - on `DD_PRESENT`: a transaction with `setBuffer(layer, ahb[i], present_fence)`; the game's present fence becomes the acquire fence, so ddview no longer waits on the GPU at all;
  - `setOnComplete`: `ASurfaceTransactionStats_getPreviousReleaseFenceFd` for the buffer that left the screen, which is sent to the game as `DD_RELEASE` with that fence (same protocol as now);
  - pacing: at most one transaction per vsync (`AChoreographer`), always the newest frame; an older undrawn frame is released at once (the same rule as the current `render_thread`).
- [ ] **Cursor:**
  - a second `ASurfaceControl` above the game layer, holding a small AHB with the cursor bitmap (redrawn only when the game changes the cursor);
  - a move is `setPosition` in a transaction with no GPU work;
  - hide/show via `setVisibility`.
- [ ] **Geometry:** scale the game buffer to the window with `setCrop`/`setScale`/`setPosition` (needed at 720p; at 1080p 1:1). Letterboxing as now, if any.
- [ ] **Lifecycle:** create the layers in `surfaceCreated`; on `surfaceDestroyed`, reparent them to null and release them, keeping the "hold buffers while hidden" behaviour.
- [ ] **ddview's EGL context:** no longer needed for frames. Keep it only if something else still needs it; otherwise remove that code.
- [ ] **Fallback:** if `ASurfaceControl_createFromWindow` fails, keep the current GL path (don't delete it until step 4 is verified).
- [ ] **Verify:**
  - `dumpsys SurfaceFlinger`: both layers DEVICE;
  - 60 FPS, cursor feel and latency, trackpad walking;
  - hide/resume, notification shade over the game, screen off and on;
  - fd counts flat;
  - the power run from step 0;
  - manual check: input latency feel compared with the current build.

- **Order:** 0 → 1 → 2 → 4, one commit each after its verification. Risks: a game read from framebuffer 0 the trace missed, or a gl4es path assuming fb 0 is the pbuffer (step 2); HWC refusing the layer, making SurfaceFlinger composite on the GPU (step 4, so its feasibility check comes first).

#### Re-plan after step 1 and the step 4 test (2026-10-22): step 4 for real, step 2 dropped, then the CPU
- **Decision:** finish step 4 (the display hardware shows the frames; −85 mW measured with the prototype), drop step 2 (the copy is ~0.2 ms at 414 MHz), then look at the CPU: the game's main thread keeps a big core at ~2.3 GHz, probably a larger share of the 2.3 W than anything left on the GPU side.
- The prototype is not in the tree (reverted; the diff was kept as a local, untracked file, `step4-prototype.diff`). The phone runs the committed build again.

**Step 4: ddview presents through SurfaceControl (no GL in ddview)**
- [ ] **Header:** NDK 27's `android/surface_control.h` doesn't compile as C. Declare the dozen functions used at the top of `ddview.c` with a one-line comment why (the prototype's approach); no C++.
- [ ] **Game layer:** one `ASurfaceControl` child of the SurfaceView window (`ASurfaceControl_createFromWindow`), created when the window arrives.
  - `DD_PRESENT` keeps the game's present fence and passes it as the acquire fence of `setBuffer` (ddview no longer waits on the GPU at all); `MIRROR_VERTICAL` (GL row 0 is the bottom).
  - The display hardware rotates and flips it (seen as DEVICE, transform 0x5).
  - **Pacing:** the next transaction is applied only once the previous one's `setOnComplete` has arrived (at most one per refresh; measured 59.8–60.0). A newer frame replaces an older undrawn one, which is released at once (same rule as now).
  - **Release:** `setOnComplete` → `getPreviousReleaseFenceFd` → `DD_RELEASE` for the buffer that left the screen, with that fence (same protocol as now).
- [ ] **Cursor layer:** a second child `ASurfaceControl` above the game layer.
  - Its buffer is a small AHB (cursor size, RGBA8, CPU-write + composer usage), filled with `AHardwareBuffer_lock`/`unlock` on `DD_CURSOR` and set once; no GPU.
  - Moves and warps: a transaction with `setPosition` only, independent of the frame pacing, so the cursor follows at the refresh rate even while the game shows a still frame (what ddview's redraw does now).
  - `DD_CURSOR_SHOW`: `setVisibility`.
- [ ] **Geometry (720p):** child layers live in the view's pixels, not the fixed-size buffer's. Java passes the view size with the surface; the game layer gets `setScale` (view / game size), and the cursor position is scaled the same way. At 1080p both scales are 1.
- [ ] **Hide / resume:**
  - On `surfaceDestroyed`: reparent both layers to null, release them; once that transaction completes, release the shown buffer without a fence (it's off screen).
  - Frames that arrive while hidden are held as now (the newest kept, the game stops when all are held); a new window shows the newest and frees the rest.
- [ ] **Remove the GL path:** EGL/GLES, the pbuffer, `import_buffer`, `quad`, `draw` and the EGL fences go from ddview (`minSdk` 31 always has SurfaceControl, and this port targets this phone). `EGL GLESv2` leave ddview's link line in `app/jni/CMakeLists.txt`. The file's header comment is updated.
- [ ] **Verify:**
  - `dumpsys SurfaceFlinger`: `dd-game` and `dd-cursor` DEVICE;
  - 60 FPS standing and walking; the static UI matches the GL path pixel for pixel (screenshot crop);
  - cursor visible, follows trackpad moves, hides when the game hides it, right shape in menus;
  - home and back, notification shade, screen off/on; fd counts of both processes flat after 5 hide/resume rounds;
  - 720p: the picture fills the screen, the cursor lands where the clicks go;
  - 5-min power run against the GL path's 2.36 W (same spot);
  - **manual check:** cursor and input latency feel.
- [ ] Commit.

**Step 5: CPU (measure first, then decide)**
- [x] **Where the main thread's time goes:** `simpleperf` on the game's main thread for 10 s in the dungeon (DWARF callchains as before).
  - Look for waiting that spins instead of sleeping (box64 futex/`sched_yield` loops, a busy frame limiter, `SDL_Delay`); that would be a root-cause fix.
  - Also note what runs on the little cores (they sit at ~1.8 GHz).
- [x] **What a lower big-core clock costs** (experiment only, root, restored after): `scaling_max_freq` of the big cluster at 1.9 and 1.5 GHz, 1 min FPS each, then a 5-min power run for the lowest step that holds 60.
  - This shows how much is to gain; a root clock cap is not something the APK can ship.
- [ ] ~~**Shippable way to let the clocks drop (if the cap shows a real gain):**~~ not done: the cap shows no real gain (results below).
  - Android's `APerformanceHint` (API 33; the phone runs Android 15). ddglue reports each frame's CPU work time against a 16.6 ms target, so the scheduler can pick lower clocks.
  - First check that this LineageOS power HAL supports hint sessions (`APerformanceHint_createSession` returns non-NULL).
- [x] Results and the decision go here before any change; one commit per change, verified with the same 5-min run.

**Step 5 results (2026-10-23, dungeon corridor at rest, phone without cover, charging held off)**
- **Profile** (flat `cpu-clock` at 2 kHz, 10 s, main thread; the main thread uses 67% of a core):
  - 57%: box64-translated game code, spread over ~4,300 addresses; the hottest takes 0.3%.
  - 25%: the Adreno GL driver.
  - 10%: gl4es (top symbol `glGetUniformLocationARB` 1.6%, plus `strlen`/`strncmp` from its name lookups).
  - 7%: libc (memmove 1.9%).
  - 2.5%: the kernel.
  - Sleep-related symbols (`clock_gettime`, `sched_yield`, futex, poll) total under 0.1%, and the sleeps are plain `futex_wait`.
  - **No spin-wait and no hot spot.**
- **Placement:**
  - The main thread runs on the big cores 86% of the time and on the little cores 14% (`top-app` cpuset, schedtune boost 10, prefer_idle).
  - The little cores carry surfaceflinger (~10%), the HWC service (~8%), the game's audio threads (5–6% each) and ddview.
- **Big-cluster cap, 1 min each:**

  | Cap (MHz) | Main thread CPU % | Big clock (MHz) | mW | FPS min/median |
  |---|---|---|---|---|
  | 2457 (none) | 78–79 | 2252–2272 | 2221–2226 | 59.6/59.9 |
  | 1958 | 74 | 1911 | 2268 | 58.8/59.9 |
  | 1497 | 85 | 1446 | 2184 | 58.9/59.9 |
  | 1190 | 96 | 1190 | 2177 | 58.2/59.9 |

  - CPU time barely grows as the clock halves, so the main thread mostly waits on memory and the driver rather than computing.
  - A 902 MHz cap is accepted by sysfs but overridden within seconds (the clock stays ~2.26 GHz), probably by the power HAL.
- **5-min runs, back to back:**
  - Capped at 1190 MHz: **2135 mW**, surface 36.1 °C, FPS min 57.4.
  - Uncapped: **2192 mW**, surface 37.0 °C, FPS min 59.7.
  - The difference, **−57 mW (2.6%)**, is an upper bound: the uncapped run came second on a warmer phone. The cap also costs occasional dips to 57.
- **Decision (pending a final call):** the big-core clock is not a lever.
  - `APerformanceHint` could at most reach the cap's ~55 mW, and with the same dips, so it is not worth adding.
  - The GPU (83% busy at 342 MHz) and the display remain; step 4 (display-hardware presentation, −85 mW measured) is the next real saving.

#### Back button and a launch screen for resolution and FPS (2026-10-23)
- [x] **Back opens the game's Escape menu.** A tapped Back sends key down and up together, and the game folds input into per-frame state, so they cancelled out (only a long press worked). ddglue now holds key releases like mouse-tap releases (at least 2 frames and 60 ms); a held Back sends Escape once.
- [x] **Launch screen** (plain Android views, before the game starts, every launch, last choice preselected): resolution 1920×1080 / 1600×900 / 1280×720 / 960×540, frame rate 60 / 30 / 20 / 15 (only rates that divide 60 Hz, so frames stay up for whole refreshes), Play set well below the choices. The choice is saved in SharedPreferences. Intent extras `height` and `fps` skip it (for scripts).
  - 900 and 540 are new sizes: keep each only if title, save list and dungeon render correctly (a gl4es bug once showed at one size only).
- [x] **Size:** the chosen size replaces the `GAME_W/GAME_H` constants (SurfaceView fixed size, `nativeStart`, cursor-speed scaling).
- [x] **Frame rate cap**, so the game does half the work rather than drawing frames nobody sees:
  - `DD_SURFACE` carries `c` = display refreshes per game frame (60 / FPS).
  - ddview: above 1, a vsync thread (`AChoreographer`) counts refreshes, and a game frame is shown only once that many have passed since the last one. The cursor still redraws at every move.
  - ddglue: above 1, a swap waits until the previous frame is on screen (2 buffers free) before copying, so the game cannot render ahead.
- [x] **Verify on the phone:** FPS 60 and 30 at both sizes (ddglue and ddview logs agree), the screen and cursor are right at 720p, the save list draws, Back still works; a 5-min power run for 1080p/30, 720p/60 and 720p/30 against today's 1080p/60.
- [ ] **Manual check:** how the lower sizes and rates look and feel.

**Results (2026-10-23, dungeon room at rest, phone without cover, charging held off)**
- **Pacing:** FPS holds at each cap (59.9 / 29.9–30.0 / 19.9–20.0 / 14.9–15.0). SurfaceFlinger timestats at 720p/30: all 300 frames 33 ms apart, none dropped.
- **Rendering:** title, save list and dungeon correct at all four sizes; cursor and taps land right. Back still opens the Escape menu; on the launch screen it leaves the app. The last choice comes back preselected.
- **Power, 1 minute each** (a first look, not 5-minute runs):

  | Setting | mW | vs 1080p/60 | GPU | Surface °C |
  |---|---|---|---|---|
  | 1080p/60 | 2007 | | 82% @ 342 MHz | 36.0 |
  | 1080p/30 | 1258 | −37% | 59% @ 257 | 35.5 |
  | 720p/60 | 1561 | −22% | 61% @ 257 | 35.5 |
  | 720p/30 | 1084 | −46% | 39% @ 257 | 34.7 |

- `godungeon.sh` (local helper, not in the repo) stopped on the title screen at 540p/15 both times: its Campaign click comes too soon after the cursor move at that rate. A click by hand works.

#### Galaxy A34 (no root) and pacing at 60 FPS (2026-10-23)
- [x] Installed on the Galaxy A34 (Mali-G68, Android 16, 2340×1080 screen at 60 Hz "Standard" mode, not rooted).
  - The pushed data needed a copy made by the app, plus the shader patch (see lessons). The title, a new campaign, the first dungeon, Back, 1080p/60 and 720p/30 all work.
- **Findings at 1080p/60, walking the first corridor:**
  - **On screen:** 511 frames, all 16–17 ms apart, 0 dropped. Over the whole session there was one 33 ms gap in about 1,600 frames.
  - **Rendered:** the game drew 61–82 frames a second, about 70 while walking. At 60 FPS nothing makes it wait, so the extra frames are thrown away. That wastes power, and each shown frame covers an uneven slice of game time, which can look like micro-stutter.
  - **Latency:** about 58 ms from ddview's post to the screen (`post2present`). Frames pile up in the window's queue because ddview presents every new frame as soon as it arrives.
- **Plan:** make 60 FPS use the same pacing as the lower rates, with `refreshes = 1`.
  - [x] ddview: run the vsync thread at every rate. Show at most one game frame per refresh, and swap at most once per refresh (cursor redraws included).
  - [x] ddglue: always wait until the previous frame is on screen (`need = DD_BUFFERS - 1`).
  - [x] ddview logs how long each shown frame waited to be drawn (`fps N, waited X ms`).
  - [x] Verified on the A34 (1080p/60, intro):
    - The game draws exactly 60.
    - All 611 frames are 16 ms apart.
    - Delay: frames wait ~12 ms to be drawn, then `post2present` is ~25 ms (was ~58 ms), about 37 ms in total.
  - [x] Verified on the OnePlus 5 (1080p/60, in the dungeon). It still holds 60, although the game no longer renders a frame ahead.
    - **Standing in a room:** 1,207 frames 16 ms apart and 4 at 17 ms, game at 59.9. Frames wait ~14 ms to be drawn, then `post2present` is ~30 ms.
    - **Walking to the next room:** 846 frames 16 ms apart, 1 at 17 ms, and one 50 ms gap (the game dropped to 57.9 that second, likely the room loading).
- **Still open on the A34:** 2–3% of frames reach the screen half a refresh off (8 ms after the previous one, then 24 ms before the next).
  - It happens only while playing (attacks, notifications on the phone), never in quiet windows.
  - `refreshRateSwitches = 0`.
- **Tried and reverted: a frame queue at 60 FPS.** The game could be one frame ahead, and ddview showed the oldest frame and never dropped one.
  - The A34 renders faster than 60, so the queue stayed full: frames waited ~28 ms, ~53 ms in total.
  - The off-by-half-a-refresh frames stayed at the same rate (131 + 156 in 150 s). Their GPU work was long finished, so the game isn't the cause.
  - The likely cause is ddview's swap sometimes reaching the compositor after its deadline.
  - Possible next step: AChoreographer frame timelines plus `eglPresentationTimeANDROID`.

#### Installing from the GOG installer (2026-10-23)
Goal: any phone needs only the APK and GOG's Linux installer (`darkest_dungeon_27988_linux_93838.sh`); no PC, adb or root.
- **Installer facts:** a 937 KB shell script followed by a 1.9 GB zip. `data/noarch/game/` holds exactly the 8,261 files (2.35 GB) the port uses. Entries are deflated or stored, with sizes in the local headers (no data descriptors), no symlinks, no zip64.
- **Plan:**
  - [x] **Launch screen:** when `files/game/_linuxnosteam/darkest.bin.x86_64` is missing, show "Import game" instead of the choices. It opens the system file picker (`ACTION_OPEN_DOCUMENT`, no storage permission).
  - [x] **Import (background thread, progress on screen):**
    - Check free space first (~2.4 GB needed).
    - Find where the zip starts from its end record (central directory offset vs. position), seek there, and stream it with `ZipInputStream`. That needs no 1.9 GB copy of the installer.
    - Unpack only `data/noarch/game/*` into `files/game.import/`, keeping file times. Reject the file if it has no `_linuxnosteam/darkest.bin.x86_64`.
    - Patch the shaders, then rename `game.import` → `game`. A half-finished import is deleted on the next try.
  - [x] **Shader patch:** move `port/apk/patch-shaders.sh` into the APK's assets and run it with `/system/bin/sh` after unpacking. It stays the one copy of the patch and can still be run by hand.
  - [x] **Errors:** a short message on screen (not a zip, no game inside, out of space), and back to "Import game".
  - [x] Notes: replace the adb/run-as steps in lessons with the new way; keep run-as as a fallback.
- **Verify on the A34:** move `files/game` aside via run-as (keeps the saves, no data lost), push the installer to `Download/`, import, play to the title and town, check `dd.log` for shader errors, delete the moved-aside copy. Picking the file in the system picker may need a tap by hand.
- **Not doing:** a separate zip format, a "re-import" button, importing while the app is in the background (leaving the app during import may cancel it; the next launch starts again).
- **Results on the A34 (2026-10-23):**
  - The installer was picked in the system picker (by hand). The import unpacked, patched the shaders and went straight to the resolution screen.
  - The imported folder matches the earlier working copy byte for byte: all 8,267 files (8,261 + 6 `.glsl.orig`) have the same md5.
  - The game starts and plays the intro, with 0 shader errors in `dd.log`. The town and dungeon weren't replayed; the data is identical to the copy that already ran them.
  - The import screen's button first stretched across the screen; it now fits its text like the Play button.
  - **Not tested:** the error messages (wrong file, no space), and leaving the app mid-import. They need picking a wrong file by hand.
- **How to install on another device:** copy the APK and the GOG Linux installer to it, install the APK, open it, tap "Import game" and pick the installer. The installer can be deleted afterwards.

## Public release as "Darkest Dungeon Porter" (2026-10-23)
- [x] New launcher icon from the project's own artwork (`port/apk/icon/ddp-icon.png`); the game's icon is gone.
  - The artwork has a baked-in frame. `port/apk/icon/make-icon.py` crops inside it and scales the logo into the adaptive icon's 66 dp safe circle. It then fades the art into its own edge colours out to the 108 dp canvas.
  - Previews with circle, squircle and rounded-square masks show the logo whole, with no frame and no white corners.
- [x] App label "Darkest Dungeon Porter". The package id stays `org.ddport.darkest`, so existing installs keep their saves.
- [x] MIT `LICENSE`; `THIRD_PARTY_NOTICES.txt` in the APK's assets (Box64, gl4es, SDL2, GCC runtime with the Debian source link); `README.md`.
- [x] Release signing from `DD_KEYSTORE`/`DD_KEYSTORE_PASSWORD`/`DD_KEY_ALIAS` (`DD_KEY_PASSWORD`). Without them the release APK is unsigned; the debug-key fallback is gone.
  - `build-apk.sh release` selects it.
  - `build-apk.sh` now passes `-PddBuild`, so a custom `DD_BUILD` reaches Gradle.
- [x] Personal paths, the device serial, IDs and save state removed from the scripts and notes.
- **Review:**
  - The debug and release builds exit 0.
  - `aapt` shows the new label and the adaptive icon, and the notices are in the APK.
  - Signed with a throwaway test key, `apksigner` verifies the release APK; without the key the release build is `app-release-unsigned.apk`.
  - Greps over every published file find no personal strings.
  - **Not verified on a phone yet** (none was connected): the launcher icon on the home screen, and an update install over the existing one.

## 1440p and 4K rendering (2026-10-23)
- [x] The launch screen's resolution is now a dropdown: 4K, 1440p, 1080p (default), 900p, 720p and 540p. Frames above the screen's size are scaled down by the display.
- [x] Galaxy A34, standing in a dungeon room for 30 s per setting (game / shown FPS, GPU busy and clock; the GPU tops out at 950 MHz):
  | Setting | FPS | GPU | AP / BAT / SKIN °C |
  |---|---|---|---|
  | 1440p / 60 | 59.5–60.3 / 59–61 | 72–100%, 420–563 MHz | 33.4 / 26.5 / 33.7 |
  | 1440p / 30 | 29.6–30.2 / 29–31 | mostly 10–20%, 385 MHz | 32.2 / 26.9 / 34.4 |
  | 4K / 60 | 59.8–60.3 / 59–61 | 75–100%, 683–950 MHz | 35.7 / 27.4 / 36.1 |
  | 4K / 30 | 29.8–30.2 / 30 | 19–100%, 385–634 MHz | 33.4 / 28.2 / 35.1 |
- [ ] Not measured: the OnePlus 5 at 1440p and 4K, battles at these sizes, and longer sessions (4K/60 runs the A34's GPU near its top clock).
- Battle at 1080p on the OnePlus 5 (2026-10-23, about 3 minutes): walking holds 60, but battles run mostly 49–58 FPS, with dips about every 5 s and three one-second drops to ~30. During the dips the GPU runs at 515–710 MHz and is 94–98% busy. Not investigated yet.

## Import and export saves (2026-10-25)
Goal: copy the game's saves (`files/.local/share/Red Hook Studios/Darkest`) out of and into the app through the system file picker, so they survive an uninstall and can move between devices. Background, the hand test and the open questions are in the handover (`saaaavveee/dd-save-import-export-handover.md`, not in the repo): Windows Steam saves loaded unchanged on both phone builds, so the feature only copies files, byte for byte.

**Decided at check-in:**
- Import replaces the whole `Darkest/` folder (no per-profile merge).
- The zip's `persist.options.json` (a PC resolution and fullscreen) is never imported; the phone's own copy is kept.
- Import takes a zip only, with the save folder (the one holding `profile_<n>/`) at any depth in it. A new Windows bat zips `Documents\Darkest`; a hint dialog on Import says how to get the zip.

**Plan:**
- [x] Launch screen: a "Save file" card under the Settings card, with Import and Export side by side. The left column scrolls if the two cards are taller than the screen.
- [x] Export: `ACTION_CREATE_DOCUMENT` (`application/zip`, suggested `darkest-dungeon-saves-<date>.zip`); zips the contents of `Darkest/` (entries `global.json`, `profile_0/…` at the root). Disabled (grey, like Play) while there are no saves. A failed export deletes the half-written file.
- [x] Import: the hint dialog (where the zip comes from; "this replaces the saves on this phone" when there are some), then `ACTION_OPEN_DOCUMENT`.
  - A file over 100 MB is refused before reading (saves are about 2 MB; this stops a game zip picked by mistake from being read through).
  - First pass over the entry names: the save root is the shallowest folder holding a `profile_<n>/` folder. None: "not a zip of saves". Two different ones at the same depth: refused.
  - Second pass unpacks only what is under the root into `Darkest.import/` (zip-slip check as in `unpackGame`), without `persist.options.json`; the phone's copy is put in instead.
  - Swap: `Darkest/` → `Darkest.before-import/`, `Darkest.import/` → `Darkest/`, then delete `Darkest.before-import/`.
  - `recoverSaves` at start: `Darkest.before-import/` with no `Darkest/` (killed between the renames) is renamed back; with `Darkest/` there (killed while deleting), it's deleted. A leftover `Darkest.import/` is deleted by the next import.
- [x] A shared progress screen for the game import and the save import/export, so Play can't start mid-copy.
- [x] `port/saves/get-saves.bat`: plain batch, zips `%USERPROFILE%\Documents\Darkest` into `darkest-dungeon-saves.zip` next to the bat (deflate level 1, see lessons on bsdtar zips).
- [x] README: the save section.

**Changes against the plan:**
- `get-saves.bat` also takes a save folder dropped onto it, for Steam's `userdata\<number>\262060\remote`, which it can't find by itself. It finds Documents through `Shell Folders\Personal`, since Documents can be moved to OneDrive.
- No separate confirmation dialog: the Import hint says "This replaces the saves on this phone. Export them first to keep a copy." when there are saves, and its "Pick zip" button is the go-ahead.
- `persist.options.json` from the zip is never taken, even when the phone has none (then the game writes its defaults).

**Review (PC only so far):**
- The debug APK builds.
- **The bat** (Windows 11, run from Git Bash): the default path found this PC's `Documents\Darkest` and zipped it. With the Steam saves dropped from a folder path that has spaces and `(x86)`, it wrote `dd save/…`, 47 entries. A folder without saves gives "There are no saves (profile_0, profile_1 and so on) in …".
- **The Java logic** (`recoverSaves`, `exportSaves`, `unpackSaves`, `entryName`, `saveRoot`, `names`, `deleteTree` and `copy`, copied into a test class with the JBR; only `Uri` became `File`, and the zip-slip base uses `File.separator` because canonical paths have `\` on Windows):
  - the bat's zip (saves under `dd save/`): the same 43 files with the same bytes as the Windows Steam saves, and no `Darkest.import/` or `Darkest.before-import/` left;
  - export: `ZipInputStream` reads it back with the same files and bytes, at the zip's root. Importing it over other saves replaces the whole folder (an extra `profile_5` is gone) and keeps the phone's `persist.options.json`;
  - refused, with the saves unchanged and nothing left over: random bytes, a text file, an empty zip, a zip without `profile_<n>/`, a `../` name (nothing written outside), two save folders at the same depth (a/ and b/), and a file over 100 MB;
  - `.\Saves\profile_0\…` and `./Saves/…` names, plus a deeper `profile_0/backup/`: the root is `Saves/`;
  - killed between the renames: the old saves come back. Killed while deleting them: the leftover is deleted. A half-unpacked `Darkest.import/` is thrown away by the next import.
- [x] Release: publish `get-saves.bat` with the next release, since the Import hint points at the Releases page.
- [x] **Verify on the Galaxy A34** (debug build, installed fresh; GOG 27988 base game only; picks made over adb with uiautomator):
  - **Launch screen:** the three cards fit at 2340×1080 without scrolling. Export is grey until there are saves. The hint dialog scrolls, and its last line ("This replaces the saves on this phone…") shows only when there are saves.
  - **PC saves in:** the bat's zip (saves under `dd save/`) imported. All 43 files have the same md5 as the Windows originals, folders are 0700, files 0600 with the app's SELinux label, and nothing is left over. The campaign shows in the game's list (Week 92) and loads into the hamlet; `dd.log` has `as_load_campaign - LoadProfile()` and `Darkest::LoadCampaign …`. The game first asked "Required DLC is missing" (the save has DLC content and only the base game was imported), and Continue was picked for the phone copy.
  - **Export:** `darkest-dungeon-saves-2026-10-26.zip` holds 44 entries (with the `persist.options.json` the game wrote), all deflated, at the root, the same bytes as a `run-as tar` of the folder.
  - **Import over saves:** the PC zip over the game-rewritten saves: 43 files equal to the originals again, and the phone's `persist.options.json` kept (same md5).
  - **Refused, saves unchanged (md5 of the folder):** a random file and a zip without `profile_<n>/` ("isn't a zip of saves"), two save folders ("a/ and b/"), and the 2.38 GB Steam game zip ("too big", at once). The `../` zip is also refused as "isn't a zip of saves": since Android 14, `ZipInputStream` itself throws on `..` names, so the first pass stops before the app's own check.
  - **Stopped mid-import** (states made with `run-as`, then the app started): `Darkest.before-import/` alone came back as `Darkest/`; with both there, the old copy was deleted. The leftover `Darkest.import/` went away with the next import.
  - **Uninstall round trip:** export, uninstall (the app's private and external folders are gone), install, import the game (85 s), import the export: the 43 save files equal the export, and the campaign (Hamlet, Week 92, 12,837 gold) loads.
  - Seen: after a reinstall the game asks the analytics question again, because `persist.options.json` (where that answer and the volumes live) is never imported.
- [ ] Not tested: a release build (same code; `run-as` needed the debug build for the checks), the OnePlus 5, a zip from Windows Explorer's "Compressed folder", and the bat on another PC.
- [x] **Reported: stuck on a black screen after importing saves** (a save made on the phone, exported, the app wiped, the game started to the menu and quit, the app reopened, the saves imported, then Play). The import was right (all 14 files equal to the zip); the bug is older than it.
  - **Cause:** quitting the game finishes the activity, but Android keeps the app process, and reopening the app reuses it. ddview serves one game per process (its listening socket, named after the pid, and its render threads live until the process ends), so the second Play logged "cannot listen on the game socket" / "cannot start the frame receiver" and showed the empty game view. Any game exit followed by a reopen within the process's life hit it.
  - **Fix:** when the game process exits, the app finishes and ends its own process at once (`killProcess` on the main thread right after `finish()`). Ending it in `onDestroy` was tried first: Android ran it about 2 s or more after `finish()`, and a launch in that gap landed in the dying process and vanished with it.
  - **Checked on the A34** (game ended with `run-as kill`, the same exit path as Quit): before the fix, the reopen kept the pid and the second Play failed as reported; after it, the process is gone within 2 s, the reopen gets a new pid, and the second Play starts the game (intro shown). A reopen 0.3 s after the kill reached the still-running game screen and closed with it; a person can't tap the launcher that fast while the game is on screen.
- [x] Follow-up: the import hint's Steam line was shortened by hand (no "or zip it"), and the gaps under the "Settings" and "Game files" headings are 4 dp smaller.
- [x] Release 0.4.6 (versionCode 10), signed with the upload key (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), published with `get-steam-game.bat` and `get-saves.bat`. Export → zip on the PC has the same files and bytes; uninstall, reinstall, import game and saves, the campaign loads. The Windows Steam saves wrapped in a folder. Import over existing saves. A non-save zip, an empty zip, a `../` zip: clear message, nothing changed. App killed mid-import: the old saves still load. The bat on a Windows PC with GOG saves, and its zip read by `ZipInputStream`.

## Steam copy (2026-10-23)
Steam's Linux depots ship the same DRM-free `_linuxnosteam` build as GOG (Build 28212, GOG's is 27988). Copied to the OnePlus 5 by hand, it reached the title, the save list and the dungeon at 60 FPS on 0.3.1, and it reads the GOG saves.
- **Depots (app 262060):**
  - 262065 holds the binaries: `_linux` (needs Steam) and `_linuxnosteam`. Its lib64 is byte-identical to GOG's.
  - 262066 holds the data, plus the Windows/Xbox-only `__build` and `_xb1`. The six patched shaders are byte-identical to GOG's.
  - The DLC depots unpack into `dlc/<id>_<name>/`: 445702 Musketeer, 580102 Crimson Court, 702542 Shieldbreaker, 735732 Color of Madness, 1117862 The Pit and 4964111 Fire's Edge.
- **Driving Steam from a script:**
  - `steam.exe +download_depot 262060 <id>` hands the command to the running, logged-in client; no console, no typing.
  - The download lands in `<Steam>\steamapps\content\app_262060\depot_<id>`.
  - `<Steam>\logs\console_log.txt` gets `Depot download complete : "<path>" …` or `Depot download failed : <reason>`.
- [x] `port/steam/get-steam-game.bat`: downloads the depots through the running Steam, then writes `darkest-dungeon-steam.zip` (the game at its root).
  - Plain batch, using reg, tasklist, findstr, choice and `tar`. A first version in PowerShell (`iex` with `-ExecutionPolicy Bypass`) was dropped: malware uses the same wrapper, so antiviruses can flag the harmless script as a false positive.
  - `tar` deflates at level 1, because its stored entries carry data descriptors, which `ZipInputStream` rejects.
  - Unowned DLC depots fail with `missing license for depot (No subscription)` and are skipped.
  - 1117862 is The Butcher's Circus (`dlc/1117860_arena_mp`).
- [x] Import (`DDActivity.unpackGame`/`zipStart`) also takes a plain zip: the game at the root, zip64-safe start, and free space by the zip's size.
- [x] README: a Steam route.
- **Review (OnePlus 5, debug 0.3.1 build):**
  - **The bat:** on this account it got the base game, The Musketeer and The Butcher's Circus; the other four DLCs aren't owned. The batch version took 1 min 46 s in total, including a fresh download of the 1.5 GB data depot and about 50 s of packing. Its zip holds the same 9,706 files (2.91 GB unpacked) in 2.38 GB. The PowerShell version's uncompressed zip was 2.91 GB.
  - **The zip:** Java's `ZipInputStream` on the PC reads all 9,706 files, and the folders the phone doesn't need (`__build`, `_xb1`, `_linux`) aren't in it.
  - **Phone tests:** the `tar` zip imported in 2 min 46 s (9,712 files, both DLC folders); the title showed Build 28212, and the save list showed "DLC Disabled". The rest below used the PowerShell version's zip.
  - **Steam import on the phone:**
    - the zip, picked in the system file picker, imported in 2 min 52 s (9,706 files plus the 6 shader backups);
    - the title showed Build 28212;
    - on the earlier hand-copied files, the dungeon ran at 60 FPS.
  - **DLC:** the saves now carry "DLC Disabled", and loading one opens the game's "Activate DLC" dialog with The Butcher's Circus and Musketeer. It was closed without activating; playing with DLC is not tested.
  - **GOG import still works:** 2 min 14 s, 8,267 files, title Build 27987.
  - **Saves:** a save rewritten by Steam's 28212 loads on GOG's 27987.
  - **Not verified:**
    - the bat's Y/N cleanup question in a real console (the test ran with empty stdin);
    - a clean double-click on another PC;
    - DLCs in play, and The Butcher's Circus PvP, which normally goes through Steam.
- [x] Release 0.4.0 (versionCode 4).
  - Signed with the same upload key as 0.3.1 (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), so 0.3.1 installs update in place.
  - Published on GitHub with `get-steam-game.bat`.
  - Not installed on a phone: both test phones run debug builds, and switching signatures means an uninstall, which deletes the saves.

## Keyboard for text fields (2026-10-23)
Goal: Android's on-screen keyboard opens while the game wants text (naming the hamlet, renaming a hero, the hamlet's rename/add dialogs), typed text reaches the game, and the keyboard closes when the game stops asking. Hardware keyboards type too, and outside text fields their keys reach the game as normal keys.

**How the game reads text** (static disassembly of `darkest.bin.x86_64` with `llvm-objdump -d -C`):
- `Input_StartTextEdit` / `Input_BeginRawTextInput` call `SDL_StartTextInput` when the first field opens. `Input_FinishTextEdit` / `Input_EndRawTextInput` call `SDL_StopTextInput` when the last one closes.
- `SDL_SetTextInputRect` always gets {0,0,500,500}, so it says nothing about where the field is.
- `SDL_TEXTINPUT` only appends its bytes to a 256-byte ring, which is never drained.
- The editors (`GUI_Text_Editable`, `TextBoxWidget::UpdateInput`) act once per frame on the newest KEYDOWN's **sym**:
  - Backspace (8), Delete (0x7F), Left/Right, Return or keypad Enter (submit), Escape (cancel, `GUI_Text_Editable` only);
  - any other sym from 1 to 0xFF inserts the **last UTF-8 character in the ring**.
  - So text alone types nothing, a key alone repeats the previous character, and several characters in one frame give one.
- A KEYDOWN counts only after that key's KEYUP; scancodes above 0xFF are ignored. Home, End and Ctrl+V do nothing.
- A non-empty `SDL_TEXTEDITING` (composition) blocks typing, so the port never sends one.
- `TextBoxWidget` ends editing on any mouse press.
- Hotkeys seem suppressed only for `GUI_Text_Editable` fields, so text must not travel as letter scancodes.

**Plan:**
- [x] `ddproto.h`: `DD_TEXT` (app → game, a = one Unicode code point) and `DD_TEXT_INPUT` (game → app, a = 1 wants text / 0 doesn't).
- [x] ddglue:
  - `SDL_StartTextInput` / `SDL_StopTextInput` send `DD_TEXT_INPUT`.
  - A `DD_TEXT` becomes `SDL_TEXTINPUT` (UTF-8), followed by a KEYDOWN and KEYUP of a "text key": scancode `SDL_SCANCODE_INTERNATIONAL1` (no keyboard sends it, no hotkey uses it), sym `'a'` (printable, so the editor inserts the character).
  - tapfix already holds each KEYUP for 2 frames and 60 ms, with everything after it, so the characters reach the game one per frame or slower, in order.
- [x] ddview: `nativeStart` keeps the activity (global ref) and the JavaVM; `DD_TEXT_INPUT` calls `DDActivity.onTextInput(boolean)` from the render thread (attached to the VM once). `nativeText(codePoint)` sends `DD_TEXT`.
- [x] DDActivity:
  - A `SurfaceView` subclass that is a focused text editor: `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` (no autocorrect or suggestions) and no fullscreen keyboard in landscape.
  - Its input connection never fills its own text, so the keyboard sees an empty field (Backspace arrives as a key). Commit and composition both send the difference to the last composing text: Backspaces, then the new characters. Deleting around the cursor sends Backspace/Delete; the Done action sends Return.
  - `onTextInput` shows or hides the keyboard. A tap while the game wants text shows it again (after Back hid it).
  - The keyboard never resizes or pans the game view (`SOFT_INPUT_ADJUST_NOTHING`).
  - `onKeyDown`/`onKeyUp`: Back as before. While the game wants text, printable keys become `DD_TEXT`. Other keys go through an Android keycode → SDL scancode table (letters, digits, F-keys, editing keys, arrows, punctuation, modifiers), with repeats, because the game repeats held Backspace/Delete/arrows.
- [x] README Controls.
- **Not doing:** moving the game above the keyboard (the game gives no field position; if a field ends up hidden, stop and decide); the clipboard (only the Dueling Grounds copy/paste buttons use it). The name field did end up hidden, so the game now pans (see the review).
- [x] **Verify on the Galaxy A34** (debug build; 0.4.0 uninstalled, the Steam zip re-imported, a new campaign): the hamlet name in all three slots, by adb and by hand.
- [ ] Not verified: hero rename and the hamlet dialogs (not reached), Bluetooth/USB keyboards.

**Review:**
- **Works on the A34** (SwiftKey): the keyboard opens when the game opens the name field and closes on Enter; letters (also non-ASCII, e.g. "ı"), Backspace and Done reach the game one each; Back outside fields still opens the Escape menu; the trackpad still works; 60 FPS on the title screen.
- **Fixed on the way:**
  - SDL's own video init calls `SDL_StartTextInput` (with the screen-keyboard hint off, meaning "no keyboard"), which popped the keyboard up at launch. The glue now follows that hint, like the real SDL.
  - `showSoftInput` was ignored when the view had lost focus; the view now takes focus right before.
  - Android outlined the focused game view in green after a key press; the focus highlight is off for it.
- **Panning:** the keyboard covers the lower half of the screen, and the save-name field sits in the slot row clicked before the mode dialog, not where the last click was. ddview keeps the left clicks of the last 30 s. When the keyboard opens, the game slides up until the lowest of them is just above the keyboard, but never so far that the last click leaves the screen. The bars the keyboard brings overlay the game instead of moving it (`setDecorFitsSystemWindows(false)`). Confirmed by hand in slots 1–3.

### Audio latency (2026-10-23)
Reported: "there is lag on audio". Measured on the Galaxy A34 (0.4.0 debug, game running, `dumpsys media.audio_flinger`): FMOD's track has **404 ms** latency, from two delays stacked:
- **Deep-buffer output, ~233 ms.** With `AAUDIO_PERFORMANCE_MODE_NONE` and no usage, the stream lands on `AudioOut_15` (HAL frame count 2048, threadloop write latency 233 ms), the output meant for music playback. The cinematics' OpenSL track is on the primary output (FastMixer, 32 ms; track latency 66 ms).
- **The shim's own queue, ~170–213 ms.** FMOD asks for a 10240-frame buffer (213 ms). The shim makes that the AAudio buffer size, and FMOD keeps it nearly full (`FrmRdy 8192`).

**Plan:**
- [x] `open_stream`: `AAUDIO_USAGE_GAME` and `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`, so the stream goes to the primary output instead of deep buffer.
- [x] Keep FMOD's 10240 frames as the capacity, but start the buffer size (what `avail_update` reports and `writei` fills) at 2 periods (2048 frames, 43 ms).
- [x] ~~If AAudio counts underruns (`AAudioStream_getXRunCount`)~~ (replaced by the shim's own count, below), grow the buffer size by one period, up to the capacity, and log it. The emulated FMOD mixer's timing is unknown on slower phones (OnePlus 5).
- [x] The start threshold (FMOD sets 10240) is capped at the buffer size, or a smaller buffer would never reach it.
- [ ] **Verify on the A34:** the track's output thread and latency in `dumpsys`, underruns (AudioFlinger and the shim's log) over title, town and a dungeon; then by ear (manual: does sound follow clicks/attacks closely, any crackle).

**First try (usage GAME + LOW_LATENCY, write-driven): only half works.** The track is still on `AudioOut_15`, now at 304 ms; the smaller queue took the 100 ms. AAudio opened on its legacy path (`got Legacy ... perf = NO, burst = 3413`), and AudioTrack logged `AUDIO_OUTPUT_FLAG_FAST denied by client, not shared buffer and transfer = TRANSFER_SYNC`. A legacy AAudio stream fed by `AAudioStream_write` is never a fast track; only one with a data callback is (AOSP `AudioStreamTrack::open`). Without the fast flag, this phone's policy puts it on deep buffer again. The cinematics' OpenSL track is callback-driven and so gets the primary output.

**Re-plan: a callback stream behind a ring buffer.**
- [x] `open_stream` sets a data callback. The shim keeps its own ring of FMOD's buffer (10240 frames): `writei` copies into it, the callback copies out and pads with silence when it runs dry.
- [x] Frames written and read are the shim's own counters (one writer, FMOD's thread, and one reader, the callback; atomics, no locks in the callback). `avail_update` and the start threshold use them.
- [x] What FMOD may queue (`buffer_size`) starts at 2 periods. A callback that finds the ring short while running counts an underrun; `writei` then grows `buffer_size` by a period (as planned, but counting the shim's underruns instead of AAudio's).
- [x] A blocking `writei` on a full ring sleeps a few ms and tries again (FMOD opens non-blocking, so this path is for completeness).
- [x] `drop` pauses and flushes the stream, then empties the ring.
- [x] A disconnect (headphones) shows up as the stream's state; `writei` reopens it there, as now. Writes are never made from the callback.
- [x] Verify as planned. Expected: the primary output's FastMixer (32 ms) plus 21–43 ms of ring, about 70 ms in all, like the cinematics' 66 ms. Cost: the fast mixer wakes every 5.3 ms while the game plays.

**Review (Galaxy A34, debug build, intro cinematic and title, 1.5 min):**
- AAudio now gives a fast track (`got Legacy ... perf = LL, burst = 256`; `AUDIO_OUTPUT_FLAG_FAST successful`). FMOD's track is `F3` on the primary output `AudioOut_1D` (FastMixer, 32 ms).
- AudioTrack's own buffer came as 2048 frames (track latency 74.67 ms). The shim now sets it to two bursts (512), and the track latency is **42.67 ms**. The ring adds 1024–2048 frames (21–43 ms) on top: about **65–85 ms** from FMOD to the speaker, down from 404 ms.
- No ring underruns (FMOD still queues 2048), 0 AudioFlinger underruns, steady 60 FPS.
- [ ] **Manual:** by ear in town and in a dungeon (sound follows clicks and attacks closely, no crackle), and that `logcat -s ddaudio` shows few or no `underruns, FMOD now queues` lines after a long session. The queue only grows, so frequent stalls would bring latency back up; if they do, rethink the growth.
- [ ] **Manual:** headphones in and out (now goes through `check_stream`, also from `avail_update`).
- [ ] Not tested: the OnePlus 5 (slower CPU, the most likely to grow the queue).

## Controller support (2026-10-23)
Reported: an Xbox Wireless Controller connected to the A34 isn't detected. The game supports controllers (it imports `SDL_NumJoysticks`, `SDL_IsGameController`, `SDL_GameControllerOpen/Close/GetAttached/GetButton/GetAxis/GetJoystick/EventState/Update`, `SDL_JoystickIsHaptic`, `SDL_NumHaptics`, `SDL_HapticOpenFromJoystick/Close`, and has controller UI states and button prompts). Two things hide it:
- The glue masks `SDL_INIT_JOYSTICK | SDL_INIT_GAMECONTROLLER`: SDL's Android joystick driver needs Java, which the game process doesn't have.
- `DDActivity` forwards only touch, Back and keyboard keys.

The phone reports this controller (`dumpsys input`) with sources `KEYBOARD | GAMEPAD | JOYSTICK`, `Generic.kl`, sticks on X/Y and Z/RZ, the d-pad on HAT_X/HAT_Y, and the triggers on both LTRIGGER/RTRIGGER and BRAKE/GAS.

**Plan:** the route mouse and keys already use. The app reads the controller and sends its state over the socket; the glue answers the game's controller calls itself. SDL's joystick code stays off.
- [x] `ddproto.h`: `DD_PAD` (a=1 connected / 0 not), `DD_PAD_BUTTON` (a=SDL_GameControllerButton, b=down), `DD_PAD_AXIS` (a=SDL_GameControllerAxis, b=SDL value).
- [x] `DDActivity`: one virtual controller, connected while any device has a gamepad or joystick source (scan at start, `InputDeviceListener` for hotplug). Gamepad keys become buttons (L2/R2 keys become full triggers). `onGenericMotionEvent` sends the sticks, the triggers (the larger of LTRIGGER/BRAKE and RTRIGGER/GAS) and the hat as d-pad buttons, each only when it changed.
- [x] `ddview`: JNI for the three messages; remembers whether a pad is connected and tells the game when it connects.
- [x] `ddglue`: the messages become SDL device/button/axis events; the state (attached, buttons, axes) changes when an event reaches the game, so polling agrees with events; `SDL_GameControllerEventState(SDL_IGNORE)` is honoured. Tapfix covers controller buttons. Overrides for every controller and haptic function the game imports (no rumble).
- [x] Check which keycode the View button sends on this controller: `getevent -lp` lists `BTN_SELECT` (and A, B, X, Y as 304, 305, 307, 308, which `Generic.kl` maps to BUTTON_A/B/X/Y), so no `KEYCODE_BACK` special case is needed. Share is `KEY_RECORD` and stays unmapped.
- [x] README: the controller line.
- [x] **Verify on the A34:** the game opens the controller (logcat `ddglue`); the title menu, town and a battle with the controller; button prompts; controller off and on mid-game, and on at launch; touch and the phone's Back still work.

**Changes against the plan:**
- `SDL_GameControllerEventState` and `SDL_GameControllerUpdate` stay SDL's own. The first only sets the event states of the controller event types, and the second returns at once without the joystick subsystem. Before a controller event is handed over, the glue asks `SDL_EventState(type, SDL_QUERY)`, so ignoring events through either API works. `SDL_NumHaptics` and `SDL_HapticClose` stay SDL's too: without haptic init they report none and reject a NULL handle.
- `BUTTON_L2`/`R2` keys only count as triggers on a controller without analog trigger axes. Otherwise a controller that sends both would make the trigger jump.
- Box64 passes every one of these calls straight through (`GO` in `wrappedsdl2_private.h`), so the glue's definitions are the ones the game reaches.

**Review (Galaxy A34, debug build, Xbox Wireless Controller over Bluetooth):**
- The game opens the controller at start (`ddglue: game opens the controller`). A skips the intro, and the d-pad, left stick and A work on the title menu. The hamlet switches to controller mode: buildings are highlighted, and the game's Xbox prompts (RT, RB, Y) show. B and Menu respond.
- Controller off mid-game: `disconnected` on both sides, and touch and the phone's Back (Escape menu) still work. Back on: `connected`, and the game opens it again without a restart.
- 60 FPS, no errors in logcat.
- Couldn't be automated: `sendevent` into the controller's node is denied for the shell user, so the presses were done by hand.
- [ ] Not tested: other controllers (PlayStation, generic), a USB controller, the OnePlus 5, and a battle with the controller.

## GOG DLC installers (2026-10-24)
Goal: GOG's Linux DLC installers can be imported together with the base installer (one pick), or later on top of an installed game. Tested with build 27988 of the base game, The Musketeer and The Color of Madness.

**What a DLC installer holds** (listed with Python's `zipfile` on the PC):
- The same MojoSetup shell script + zip as the base installer. In `data/noarch/game/` it holds only `dlc/<id>_<name>/` (`445700_musketeer`, 181 files, 21 MB; `735730_color_of_madness`, 1,283 files, 653 MB unpacked from 536 MB) and its own `goggame-<id>.info/.hashdb`.
- No file in `data/noarch/game/` overlaps the base installer, and the base has no `dlc/` folder. No shaders, binaries or split files (`postinst.sh` only merges `*.split` parts, and there are none). MojoSetup's own install is a plain copy of `noarch/*`.
- This is the layout of the Steam DLC depots, which the game already found (the "Activate DLC" dialog, see Steam copy).
- Today the import only runs while no game is installed, and needs `_linuxnosteam/darkest.bin.x86_64` in the file, so a DLC installer fails with "this file doesn't hold the Linux Darkest Dungeon".

**Plan:**
- [x] The picker allows several files (`EXTRA_ALLOW_MULTIPLE`). All picked files unpack into `game.import/` (GOG's from `data/noarch/game/`, the Steam zip from its root), with one progress bar over all of them.
- [x] Then:
  - with the game binary in `game.import/` (a base was picked): patch shaders and replace `game/`, as now;
  - without it, and `game/` installed: move what was unpacked into `game/` (a DLC folder already there is replaced), so a half-done DLC import never leaves a partial `dlc/` folder in the game;
  - without it, and no game: the error as now, naming the base installer.
- [x] The launch screen gets an "Add DLC" button next to Play that opens the same picker.
- [x] Free space: ~~the base needs `GAME_BYTES` as now; a DLC needs its unpacked size~~ every picked file needs its exact unpacked size, read from its zip's central directory (decided at check-in; see the changes).
- [x] The mirror needs no change: `dlc/` is one symlink to `game/dlc`, so later DLCs show through it.
- [x] README: the GOG route and the import screen mention the DLC installers.
- [x] **Verify on the A34** (debug build): base + both DLCs in one pick; then base alone, then each DLC through "Add DLC". The DLC folders are in `game/`, and the game offers both DLCs (the new campaign's DLC options or the "Activate DLC" dialog; manual by hand).

**Changes against the plan:**
- `GAME_BYTES` (a fixed 2.5 GB) is gone. `GameZip` reads each picked file's end record and central directory once: where the zip starts, what the files under the game folder unpack to, and whether they hold the game binary or a `dlc/` folder. The guess was already short for the Steam zip, which `tar` deflates (2.91 GB unpacked, see Steam copy); its old comment still called it uncompressed.
- `GameZip` also reads zip64: past 4 GB, the end record holds 0xffffffff and the zip64 end record (through its locator) has the directory; an entry over 4 GB has its size in the zip64 extra field. Only a Steam zip with many DLCs can get there.
- The files are checked before anything unpacks: one copy of the game at most, a game only while none is installed, a DLC only with a game, and nothing that is neither.
- A failed import goes back to the launch screen (or the import screen while no game is installed), with the error.

**Review:**
- **On the PC** (`GameZip` copied into a test class, run with the JBR): the unpacked sizes equal Python's `zipfile` sums: base 2,347,414,674 bytes, The Color of Madness 651,786,565 (536 MB installer), Steam zip 2,912,192,153 (game and DLC). A 4.36 GB zip64 test zip (one stored 4.36 GB entry, so the zip64 end record and size extra field are both used) read right too. Random bytes and a text file are rejected ("no zip inside").
- **On the Galaxy A34** (debug build, picks done by hand):
  - base + both DLCs in one pick: `game/dlc/` holds `445700_musketeer` (149 files) and `735730_color_of_madness` (1,040), both as many as in the installers. The new campaign offers both DLCs.
  - after wiping `game/`: the base alone, then Add DLC with The Color of Madness, then with The Musketeer (so the second merges into an existing `dlc/`). Same file counts, `game.import/` cleaned up, and both DLCs offered in a new campaign.
  - the guards: the base through Add DLC gives "the game is installed already: pick only DLC installers"; a DLC on the import screen alone gives "pick GOG's installer of the game or the Steam zip too".
- [ ] Not tested: playing with the DLCs beyond the new-campaign dialog, the other GOG DLCs (Crimson Court, Shieldbreaker, The Butcher's Circus), GOG DLCs on top of the Steam game (build 28212 against GOG's 27988), and a DLC imported again over itself.

### Launch screen redesign (2026-10-24)
Asked for: the first screen in two halves (resolution and frame rate on the left, importing on the right, with what is already imported under the import button), Play at the bottom centre, easier-to-read type and colours, and friendlier messages. The texts were agreed before writing.
- [x] One screen for both states: two rounded cards ("Settings", "Game files") on a dark background, left-aligned text, a heading over grey labels and white values; Play (amber) at the bottom centre. Before the game is imported, Play is grey and disabled (a line under it saying so was dropped on request), and the button reads "Import game" instead of "Add DLC".
- [x] "Imported" lists the game (GOG when `goggame-1450711444.info` is there, else Steam) and each `dlc/` folder by name. The folders hold no name of their own, so `dlcName` knows the six DLC keys and capitalises any other.
- [x] Messages are sentences shown as "Couldn't import: …" in the right card. Free space says how much is needed and how much is free.
- [x] The right card scrolls when its content is taller, so the long hint and many DLCs never push Play off the screen.
- **Review (Galaxy A34, screenshots):** both states (game folder moved aside for the no-game one) fit at 2340×1080 without scrolling. Not checked on the phone yet: opening the dropdowns, Play starting the game, an error message in place and the import progress screen (restyled the same way).
- [x] Follow-up: the hint before the game is imported is a short list ("Pick one of these:", GOG's Linux installer and the Steam zip as bullets with their file names in monospace, then "For GOG, DLC installers can be picked together with the game. The Steam zip already has your DLCs."). "Nothing yet." is 14 sp italic, and the DLC lines are italic. To keep everything on screen, the import button moved into the "Game files" header row and the cards split 2:3 in its favour.
- [x] Follow-up: no "Imported" section before the game is imported (so no "Nothing yet."). The import button sits at the bottom right of the card: under the hint before the game is imported, and under the imported list and "Pick one or more of GOG's DLC installers." after. "Import the game first, then you can play." under Play was removed. The DLC error no longer mentions the Steam zip, and the import message is centred.
- [x] Follow-up: import errors open a dialog ("Couldn't import", the message, OK) instead of a red line in the card, which pushed the button out of view.
- [x] Reported: the same DLC could be imported again and again (it silently replaced the copy). Then asked: picking A, B and C with A imported should still import B and C. `GameZip` now lists the DLC folders each file holds. Before unpacking, a DLC installer is skipped when all its DLCs are imported already or come in another picked file (the game's zip counts first, then the picks in order). The rest is imported, and a dialog ("Already imported") names the skipped ones that were imported already: "The Musketeer was already imported, so it was skipped.", or "... is already imported, so there was nothing new to add." when nothing was left. A DLC that only came twice in one pick (the Steam zip and a GOG installer) is skipped without a note. Checked on the PC: the base installer holds no DLC folder, each DLC installer one.
- [x] Release 0.4.4 (versionCode 8), signed with the upload key (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), published with `get-steam-game.bat`.

## Shift button in the trinket inventory (2026-10-25)
Asked for: the game sells trinkets on Shift-click, which touch can't do. Detect the trinket inventory and show a Shift button over it. Only the trinket inventory for now, to test.

**What the game does** (static disassembly of both `darkest.bin.x86_64` builds, `llvm-objdump -d -C`):
- No SDL keyboard-state imports (`SDL_GetModState`, `SDL_GetKeyboardState`). `TrackKeyEvent` keeps the game's own key table by `keysym.scancode` (up to 0xFF); `keysym.mod` is never read. So a `DD_KEY` of scancode 225 (Left Shift) going down holds Shift, and going up releases it. The key path needs nothing new.
- `TownUI::Panel::RealmInventory::PreChildrenShowInternal` asks `Input_IsAnyKeyDown({SDLK_LSHIFT, SDLK_RSHIFT})` every frame it draws. While that is true (and a flag in `App` is off), the grid is in sell mode, and a click asks "Really sell X for N gold?" (Yes / No / Always).
- Shift matters elsewhere too (the game's help texts, and the code that references `SDLK_LSHIFT`/`RSHIFT`): Shift-click discards an item in the dungeon inventory (`Inventory::Display::InventoryButtonInGrid`), sends a hero out of an Abbey/Tavern/Sanitarium slot early (`HeroSlot`, `ItemContainerDisplay`), and lowers the torch, with Ctrl snuffing it (`LightTorchOverlay`). Not part of this step.
- **Is the trinket inventory open:** `TownDisplay::GetIsRealmInventoryVisible()` reads `TownDisplay::s_Instance` (a pointer: NULL outside the hamlet, set by `TownDisplay::Create`, cleared by `Destroy`), then the panel at +0x2f70: `UI::Panel::Base::GetIsActive()` is the int at +0x38 being non-zero. Its states: 0 closed, 1–4 opening or open (4 = `IsFullyActive`), 5–7 closing (`GetIsExiting`).
- The GOG 27988 and Steam 28212 binaries differ. `s_Instance` is at 0x38410a0 (GOG) and 0x3841090 (Steam), but both have `add $0x2f70,%rdi` (`48 81 c7 70 2f 00 00`) at `GetIsRealmInventoryVisible`+8 and `cmpl $0,0x38(%rdi)` (`83 7f 38 00`) at `GetIsActive`.

**Plan:**
- [x] `ddproto.h`: `DD_SHIFT_SCREEN` (game → app, a=1 the trinket inventory is open / 0 it isn't).
- [x] ddglue:
  - At the first swap, map `$DD_BASE_PATH/darkest.bin.x86_64` and find in `.symtab`: `_ZN6TownUI6Screen11TownDisplay10s_InstanceE`, `_ZNK6TownUI6Screen11TownDisplay26GetIsRealmInventoryVisibleEb`, `_ZNK2UI5Panel4Base11GetIsActiveEv`. Check the two instructions above in the file's `.text`. If a symbol is missing or the bytes differ, log why and never send `DD_SHIFT_SCREEN`, so the button never shows on an unknown build.
  - At each swap (the game's thread, between its frames): open = `s_Instance` is set and the panel state at +0x2f70+0x38 is 1–4. Send `DD_SHIFT_SCREEN` when that changes, and log it.
- [x] ddview: `DD_SHIFT_SCREEN` calls `DDActivity.onShiftScreen(boolean)` from the render thread, attached to the VM like `onTextInput` (one shared helper).
- [x] DDActivity:
  - The game view and a Shift button share a `FrameLayout`. The button keeps its own touches (no cursor move, no click), and the keyboard pan still moves only the game view.
  - A toggle: a tap holds Shift (`nativeKey(225, true)`, button amber), the next releases it (grey). Hidden unless the trinket inventory is open.
  - Shift is released whenever the button hides and when the surface goes away (app in the background), so it can never stay held into a dungeon, where Shift-click discards items.
  - Placement: the left edge, halfway down. Check on screen that it covers nothing in the trinket inventory.
- [x] README Controls: one line on the button.
- [x] Verify:
  - On the PC: the lookup finds the three symbols and both instructions in both binaries (Python, the same steps as the C code).
  - On the A34 (debug build; GOG 27988 with The Musketeer and The Color of Madness is installed): `logcat -s ddglue` shows the lookup, then "trinket inventory open/closed" as the panel opens and closes (by hand); the button shows only then; Shift on + tap on a trinket opens the sell dialog; closing the panel with Shift on releases it. A screenshot shows what the button covers.
- **Not doing:** the other Shift uses; a hardware keyboard's Shift (goes through the existing key path, untested).

**Review (Galaxy A34, debug build):**
- **GOG 27988** (with The Musketeer and The Color of Madness): the glue found `s_Instance` at 0x38410a0 at the first frame. Opening the trinket inventory logged "open" and showed the button; with Shift on, the panel switched to sell mode ("Sell trinket for: 750"). Closing it logged "closed", the button went away, and the gold was unchanged.
- **Steam 28212** (imported from the zip, with the GOG game moved aside to `files/game.gog`): `s_Instance` at 0x3841090; the same open/closed, button and sell mode, and a Stun Stone sold for 750 gold (3,395 to 4,145).
- On the PC, a Python copy of the lookup found the three symbols and both instructions in both binaries.
- The button covers only town scenery at the left, not the panel, the roster or the bottom bar (screenshots).
- [ ] Not tested: going to the background with Shift on; the trinket panel opened from a hero's screen or the embark screen; a hardware keyboard's Shift.
- [x] Release 0.4.5 (versionCode 9), signed with the upload key (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), published with `get-steam-game.bat`.

## Walk buttons in the dungeon (2026-10-27)
Asked for: detect when the party is in a dungeon and not in a fight, and show left and right arrow buttons that walk it.

**Findings (GOG 27988, llvm-nm/objdump):**
- `Raid::s_Instance` (`_ZN4Raid10s_InstanceE`) is a pointer: `Raid::Create()` sets it, `Raid::Destroy()` sets it back to NULL, so it is non-NULL only during an expedition.
- `DungeonController::IsInCombat()` is `Raid::IsInBattle()` on it: `add $0x4778,%rdi` (`48 81 c7 78 47 00 00`, at +1 after `push %rax`), then `Battle::GetState()`, which is `mov 0x248(%rdi),%eax` (`8b 87 48 02 00 00`). The state is 0 outside a battle.
- `RaidUI::Screen::RaidDisplay::ProcessMovementInput()` walks left while `Input_IsAnyKeyDown({SDLK_a, SDLK_LEFT})` and right on `{SDLK_d, SDLK_RIGHT}`. The app already sends Left/Right as scancodes 80/79, so holding those keys walks the party; the key path needs nothing new. (`DungeonController::UpdatePartyMoveDirection` reads only a controller's stick.)
- Steam 28212 (from `darkest-dungeon-steam.zip`): `Raid::s_Instance` at 0x37ba370 (GOG 0x37ba380), and the same two instructions.
- Rule: the buttons show while `Raid::s_Instance` is set and its battle state is 0. They may also show over the map, a curio or loot popup and the camp; the game's own checks keep the party still there.
- The first build also showed them on the dungeon's loading screen: the raid exists while it loads. So the rule also needs "no loading screen": `LoadingScreen::s_Instance` (`_ZN13LoadingScreen10s_InstanceE`) and `LoadingScreen::GetIsActive()`, `movzbl 0x2cc(%rdi),%eax` (`0f b6 87 cc 02 00 00`). `StartScreen` sets that byte to 1, and `FinishScreen` clears it (with the 16 bytes from +0x2c0). Steam 28212 has it at 0x38b22a8 (GOG 0x38b22b8), with the same instruction.

**Plan:**
- [x] `ddproto.h`: `DD_WALK_SCREEN` (game → app, a=1 the party is in a dungeon and not in a battle / 0 it isn't).
- [x] ddglue: one lookup in the game binary for both features (one mmap and `.symtab` pass, each feature checked and logged on its own), and at each swap send `DD_WALK_SCREEN` when the state changes, and log it.
- [x] ddview: `DD_WALK_SCREEN` calls `DDActivity.onWalkScreen(boolean)`, like `onShiftScreen`.
- [x] DDActivity: ◀ and ▶ buttons at the left and right edges of the corridor, holding Left/Right while pressed. A held key is released when the buttons hide and when the surface goes away.
- [x] README Controls: one line.
- [x] Verify:
  - On the PC: a Python copy of the lookup finds the eight symbols and five instructions in both binaries.
  - On the A34 (debug build): `logcat -s ddglue` shows the lookup and "walk buttons shown/hidden"; no buttons in town; they show in a corridor and a room, holding one walks the party, and letting go stops it; they hide in a battle and come back after it; screenshots show what they cover.

**Review (OnePlus 5, debug build, Steam 28212):**
- The glue found `Raid::s_Instance` at 0x37ba370 and `LoadingScreen::s_Instance` at 0x38b22a8 at the first frame.
- On the PC, a Python copy of the lookup found the eight symbols and the five instructions in both GOG 27988 and Steam 28212.
- Holding ▶ walked the party along the corridor (screenshots before and after); the owner confirmed it on the phone.
- The first build showed the arrows on the dungeon's loading screen (the owner spotted it); with the loading-screen check they stay hidden there, as the screenshot of the "The Old Road" loading screen shows.
- Entering the room into a battle: no arrows. After the battle (played by the owner) the log said "walk buttons shown" and they came back.
- They sit at the left and right edges, a little above the middle of the corridor, and cover only scenery. When the party stands at the far left, the left one can overlap the last hero a little (it's 85% opaque).
- Asked after the test: taller, narrower and more see-through. Then: half the gap between the buttons and the hero panel (the scene above, the panel below), closer to the edges, a lighter grey instead of amber while held, and fainter. Now 56×94 dp (a third shorter, asked after that), 8 dp from the edges, centred at 43.5% of the screen height, 30% opaque, grey 0xFF4A4A53 while held. The Shift button stays amber. (First read as "halve the gaps to the screen's top and bottom", which made them 67% of the screen tall.)
- [ ] Not tested: going back to the hamlet (should log "hidden"); the map, curios and camp with the arrows up; going to the background with an arrow held; GOG 27988 on a phone; ◀.

## Shift and walk buttons on other game builds (2026-10-27)
Reported: two players on GOG build 26186 never see the Shift button. The owner found a copy of that build's Linux installer (`darkest_dungeon_26186_81633.sh`; only its zip was read, nothing run).

**Findings (Python, the same steps as ddglue's lookup, GOG 26186 / GOG 27988 / Steam 28212):**
- All eight symbols exist in all three. The instructions are the same apart from their offsets, and 26186 has different ones:
  - `GetIsRealmInventoryVisible`+8: `add $0x2fe0,%rdi` in 26186, `$0x2f70` in 27988/28212.
  - `Raid::IsInBattle`+1: `add $0x4748,%rdi` in 26186, `$0x4778` in 27988/28212.
  - `Battle::GetState`: `mov 0x228(%rdi),%eax` in 26186, `0x248` in 27988/28212.
  - `UI::Panel::Base::GetIsActive` (`cmpl $0,0x38(%rdi)`) and `LoadingScreen::GetIsActive` (`movzbl 0x2cc(%rdi),%eax`) are the same in all three.
- The glue compared the whole instructions, offsets included, so on 26186 it logged "keeps the trinket inventory elsewhere" and showed no Shift button (and the walk buttons would never show there either).

**Plan:**
- [x] ddglue: check each instruction's bytes except its offset, and take the offset from the game's code instead of a constant. A build whose code differs elsewhere still gets no button. Log the offsets that were read.
- [x] Verify: a Python copy of the new lookup reads 0x2fe0/0x38, 0x4748/0x228, 0x2cc from 26186 and the old constants from 27988 and 28212; the natives build.

**Review:**
- `code_offset()` compares an instruction with its template except for the offset bytes, and returns the offset. The five offsets are now read from the game's code (panel + its state, battle + its state, the loading flag) and logged.
- On the PC, a Python copy reads 0x2fe0/0x38, 0x4748/0x228, 0x2cc from GOG 26186 and 0x2f70/0x38, 0x4778/0x248, 0x2cc from GOG 27988 and Steam 28212. A wrong address, a changed byte outside the offset, or an address outside `.text` gives -1.
- The debug APK builds without warnings. On the OnePlus 5 (Steam 28212) the glue logged "the panel at +0x2f70, its state at +0x38" and "the battle at +0x4778, its state at +0x248 ... its flag at +0x2cc", the old constants.
- [ ] Not tested: GOG 26186 on a phone. The installer comes from an unknown website, so it is never run or imported; it waits for a player on 26186 to confirm (the logcat line should show +0x2fe0).
- From now on, anything read from the game binary is checked against all three builds (26186 statically only).
- [x] Release 0.4.7 (versionCode 11) with the walk buttons and this fix, signed with the upload key (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), published with `get-steam-game.bat` and `get-saves.bat`.

## Shift button on the Provision screen, and a pinch to show or hide it (2026-10-27)
Asked: the Shift button should also show on the Provision screen (before embarking), and a pinch (zoom in) should show or hide it.

**Findings (llvm-objdump, GOG 26186 / GOG 27988 / Steam 28212):**
- `TownDisplay::GetIsProvisionSelectVisible` is `add $off,%rdi; jmp UI::Panel::Base::GetIsActive` in all three: the provision panel sits at +0x28d0 in 26186 and +0x2888 in 27988/28212, and its state is read like the trinket panel's.

**Plan:**
- [x] ddglue: also read the provision panel's offset from `GetIsProvisionSelectVisible` (same `code_offset`), checked and logged on its own; the Shift button shows while either panel is opening or open.
- [x] DDActivity: two fingers spreading apart (zoom in) by more than 64 dp shows the Shift button if hidden and hides it (letting Shift go) if shown, once per gesture, anywhere in the game. Such a gesture is never a two-finger tap (no right-click). The game still shows or hides the button when the trinket inventory or the provisions open or close.
- [x] Comments (ddglue, ddview, ddproto, DDActivity) and README Controls.
- [x] Verify: the Python lookup reads the provision offset from all three builds; on the phone (Steam 28212) the button shows on the Provision screen, a pinch toggles it in the hamlet and the dungeon, and a two-finger tap still right-clicks.

**Progress:**
- On the PC, the Python lookup reads the provision panel at +0x28d0 from GOG 26186 (statically) and +0x2888 from GOG 27988 and Steam 28212; a wrong address or a changed opcode byte gives -1.
- The debug APK builds. On the OnePlus 5 (Steam 28212) the glue logged "provisions: ... the panel at +0x2888, its state at +0x38" next to the trinket and dungeon lines.
- Asked next: zoom out hides the button (zoom in only shows it), the button's middle at 2/3 of the screen height (it was centred), and 25% taller. The extra height is a quarter of its visible height (the button's background has transparent insets above and below).
- [x] The owner tested on the phone (adb can't pinch, and the Provision screen is a long walk through menus): "works".
- [x] Release 0.4.8 (versionCode 12) with these, signed with the upload key and checked with `apksigner`.

## Touch controls, picked on the game's Escape menu (2026-10-28)
Asked: touch controls as an alternative to the trackpad, selectable on the game's Escape menu: when that menu shows, the port's control options show too.

**Decided at check-in:**
- In Touch mode the finger is the mouse: touching presses the left button where the finger is, sliding drags, lifting releases. A two-finger tap right-clicks; the pinch still shows or hides Shift.
- A "Controls" card with Trackpad and Touch sits in the top-left corner while the menu shows. The choice is remembered.

**What the game does** (llvm-objdump and Python; GOG 27988 and Steam 28212 have the same code here):
- Six classes derive from `UI::Screen::Base` (found through their typeinfo): `TownUI::Screen::TownDisplay`, `RaidUI::Screen::RaidDisplay`, `RaidResultsUI::Screen::RaidResultsDisplay`, `FrontEndFlow::Screen::FrontEndDisplay`, `GameOverUI::Screen::GameOverDisplay` and `VideoScreen`. Each `Create()` sets its `s_Instance` to `new X` (the base at offset 0); `Destroy()` deletes it and sets it back to NULL.
- The Escape menu is a `UI::Panel::Menu` in each screen: `UI::Screen::Base::IsMenuActive` is `add $0x708,%rdi` (`48 81 c7 08 07 00 00`), then `jmp UI::Panel::Base::GetIsActive` (the panel state at +0x38: 0 closed, 1–4 opening or open, 5–7 closing).
- The menu's page: `UI::Panel::Menu::IsInChildPanelState` is `mov 0x60(%rdi),%eax; add $-2; cmp $3; setb` (`8b 47 60 83 c0 fe 83 f8 03`). `PreChildrenShowInternal` shows 0 = the main list (`ShowBase`), 1 = Options (`ShowOptions`), 2–4 = child panels over the main list. The card shows only on page 0, so it never covers Options or the child panels.
- GOG 26186's installer is no longer on this PC, so it isn't checked this time.

**Plan:**
- [x] `ddproto.h`: `DD_MENU_SCREEN` (game → app, a=1 the Escape menu shows its main list / 0 it doesn't).
- [x] ddglue: in the same lookup, the five other screens' `s_Instance`, `IsMenuActive` and `IsInChildPanelState`; the menu's offset and its page's offset are read from the code (`code_offset`), checked and logged on their own. At each swap: the menu shows when a screen exists, its menu panel is opening or open and the page is 0; send `DD_MENU_SCREEN` on a change, and log it.
- [x] ddview: `DD_MENU_SCREEN` calls `DDActivity.onMenuScreen(boolean)`. New `nativeSetCursor(x, y)` (absolute, game pixels); it and `nativeMoveCursor` share one `move_cursor`.
- [x] DDActivity:
  - `touchMode` from the preferences (key `touch`, default trackpad); `onTouch` hands the event to `trackpad(e)` (today's code) or `touch(e)`.
  - `touch(e)`: the cursor jumps to the finger. The left press waits until the finger moves past `TAP_SLOP`, stays down `TAP_MS` or lifts (then press and release together), so a second finger landing first makes a two-finger gesture (right click or pinch) with no stray left click. Once pressed, the cursor follows that finger, other fingers are ignored, and the button is released when it lifts or the gesture is cancelled.
  - The card (heading "Controls", Trackpad and Touch side by side, the chosen one amber), at the top left, not focusable, shown by `onMenuScreen`. A held press is released on a switch and when the surface goes away.
  - Found on the way: the trackpad's `onTouch` ignores `ACTION_CANCEL`, so a tap-then-hold drag the system cancels leaves the left button held. It now releases it too (approved with the plan).
- [x] README Controls; the class comments.
- [ ] Verify: the Python lookup on both builds; the natives and the APK build; on the phone, the card with the menu (hamlet, corridor, battle), not in Options, gone when the menu closes; Touch: instant `input tap` clicks, slide drags, two-finger tap right-clicks only, pinch, keyboard pan; Trackpad unchanged; the choice survives a restart.

**Progress (PC only so far):**
- A Python copy of the lookup finds the six screens' `s_Instance` and reads the menu at +0x708, its state at +0x38 and its page at +0x60 from GOG 27988 and Steam 28212. A wrong address, a changed byte outside the offset (`add $-3` in `IsInChildPanelState`) or an address outside `.text` gives -1.
- `build-apk.sh` (debug) exits 0. `ddglue.c` and `ddview.c` compile with the build's flags plus `-Wall -Wextra` without warnings. The built `libddview.so` exports `nativeSetCursor` and names `onMenuScreen`; `libddglue.so` holds the new symbol names and log lines. `build-natives.sh` wasn't rerun: Box64 and gl4es didn't change, and the glue and ddview build in Gradle.
- [ ] Not tested yet: everything on the phone (none was connected).
- **GOG 26186** (the owner found its installer again; only the binary was copied out of its zip with Python's `zipfile` and read, nothing run): the Python lookup finds all six screens' `s_Instance` and reads the menu at **+0x728** (+0x708 in 27988/28212), its state at +0x38 and its page at +0x60; the failure cases give -1. Beyond the templates, llvm-objdump shows the same six classes derive from `UI::Screen::Base` (typeinfo, single inheritance), each `Create()`/`Destroy()` sets and clears its `s_Instance`, `IsMenuActive` jumps to `GetIsActive` (`cmpl $0,0x38(%rdi)`), and `PreChildrenShowInternal` uses the pages the same way (2–4 over `ShowBase`, 0 `ShowBase`, 1 `ShowOptions`). So the card should work there too; it waits for a player on 26186 to confirm (logcat: "the menu at +0x728").
- **Changed after a look at the official tablet edition:** reviews say a tap acts there and a long press stands in for hover; there's no official write-up of its controls. In Touch mode, a two-finger tap became a **hold**: one finger held still for the system's long-press time (`ViewConfiguration.getLongPressTimeout()`, 400 ms by default) right-clicks under it, and the rest of that touch does nothing. A tap still clicks, sliding still presses where the finger touched and drags. A second finger that lands before either still makes it a pinch, with no click. The trackpad keeps its two-finger tap.
- **Then asked: both right clicks, picked on the card.** Under Touch, the card shows "Right click" with **Hold** and **Two-finger tap** (only in touch mode), and under them the chosen way's pros and cons:
  - Hold: "+ One finger does everything. − A tap held too long right-clicks."
  - Two-finger tap: "+ Right-clicks at once. − Takes two fingers, and right-clicks under the one that touched first." (", and a long tap still clicks" was dropped on request.)
  - Two-finger tap is the earlier behaviour: a finger held still for `TAP_MS` presses the left button, so a long tap is a click. Hold is the default; the choice is kept (key `holdRightClick`). The note wraps at the buttons' width rather than widening the card.
- **Then asked: a "Show cursor" checkbox under Touch.** It sits right under Trackpad/Touch, above "Right click", on by default (key `touchCursor`). Unticked, ddview stops drawing the cursor (`nativeShowCursor`; drawn only when both the game and the app show it). The game still gets every move, so hover works the same. The trackpad always draws it.
- [x] Release 0.5.0 (versionCode 13), signed with the upload key (certificate SHA-256 `963301da…3196934`, checked with `apksigner`), published with `get-steam-game.bat` and `get-saves.bat`. Released before the phone checks above (asked to release); they are still open.
