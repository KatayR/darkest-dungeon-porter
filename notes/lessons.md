# Lessons

Project rules learned from mistakes made during the port.

## Git
- Every commit here is by `KatayR <rasitarslan@outlook.com>`. The repo sets it in `.git/config` too, so a different global identity can't leak in. Check `git log -1 --format='%an <%ae>'` before pushing; a commit once went out under the global identity.

## Verifying input
- When a phone test needs a long trackpad walk through menus (blind swipes, screenshots, re-aiming), ask the owner to do those steps by hand rather than driving them over adb. Typing and clicking through the game by adb took many slow rounds that a person does in seconds; keep adb for logs, screenshots and short exact checks.
- A screenshot of the game running does not prove input works. Before calling controls done, test an *instant* click (e.g. `xdotool click 1`) and confirm the game reacts, since real taps on a touch layer are often zero-length.

## Measuring FPS
- A benchmark run counts only if a screenshot taken during it shows the intended scene with Termux:X11 in front. The first baseline ran with the home screen up and was meaningless.
- Check a measurement tool's sample period before converting sample counts to seconds. Gallium HUD defaults to 0.5 s, not 1 s.
- When several processes each sit well below 100% and FPS is still low, the stages are taking turns. Look at clocks: the GPU governor idled at its minimum because each stage alone looked lightly loaded.
- Before calling a lower FPS a regression, A/B the committed build in the same scene. The Ruins room stood at 51–54 after a change, but the committed build did 48–52 there: the room was slow before.

## Plan gates
- A gate whose result goes past its threshold (the plan said "a handful of missing symbols"; the result was ~35 plus ABI differences) stops the work until it is re-planned, with the options written down. It is not quietly judged "still patchable" while building goes on: the gate exists so that choice is made deliberately.

## Android storage and shell
- Edit files in an app's external storage through the FUSE path (`/storage/emulated/0/...`), never underneath it in `/data/media`. Changes made below FUSE left the app seeing the old file sizes, so the game read truncated shaders and failed with no useful error.
- `cmd f | ... > f` empties `f` before `cmd` reads it, because the shell opens the redirect first. Make any backup of `f` in a separate step before the pipeline. This wiped four shaders once; the chroot copy restored them.
- Toybox `grep` on the phone doesn't support `\|` alternation in basic regex and fails silently. Use `grep -E 'a|b'`.

## Debugging a closed-source binary
- When a fix "doesn't work", test the hypothesis before building the workaround. The shader-type theory was wrong, and a one-file experiment showed it before any gl4es work was done. If the binary has symbols, disassembling the failing function (`llvm-objdump -C`) found the real cause, the file size and focus checks, faster than guessing.

## Emulation bugs
- If emulated code computes garbage the same way under the dynarec, with exact float flags, and in the interpreter (`BOX64_NODYNAREC=start-end` runs just one address range interpreted), the bug is not in the x86 code. It is in a wrapped native function it calls. Look at Box64's `#ifdef ANDROID` stand-ins first (`qsort_r` was a broken quicksort).
- To judge "garbled" audio, dump the PCM at the last point the game touches it and diff it sample by sample against a PC decode of the source. That separates wrong content from wrong timing (gaps) from wrong delivery (late callbacks).

## gl4es
- A shader that compiles and links can still sample nothing: gl4es binds textures at draw time (`realize_textures()`) and, upstream, picks each unit's target from the fixed-function `glEnable` bits. Any non-2D texture a shader-only game binds must be checked for reaching the GPU, not only the shader source.
- When a whole layer renders black with no GL errors, look at what a post-process pass multiplies the scene by (a LUT, a mask) before suspecting the scene itself.
- Emulating a missing feature (3D textures as a 2D slice atlas) can go wrong in driver-specific ways that a CPU reference never reproduces. When the driver has the real extension (`GL_OES_texture_3D` here), switch to it before debugging the emulation further. Four disproven hypotheses went into the atlas; real 3D textures fixed it in one go.
- When the main thread waits in the driver (`waittimestamp`) under a draw call, time each draw call on the CPU and log the slow ones, with their textures and index ranges. Here one draw per frame waited ~9 ms. The cause was not its texture but client arrays: Adreno copies vertices from the attribute pointer up to the highest index, and the game indexes deep into one big ring buffer.

## Recording experiments
- Every setting tried gets its source written next to it (its documentation, the profile line it targets) before the run. A bare "try X=0" reads as a random guess even when it's the one switch for the measured cause.

## Frame pacing
- An FPS locked to an exact divisor of the refresh rate (30, 20) means frames are losing vsync slots to something, not running out of CPU or GPU. Look at who calls `eglSwapBuffers` and how often (here: one cursor redraw per touch event).

## Rendering differences between resolutions
- When something renders at one size and not another, trace the GL calls of one frame at both sizes and diff them. Identical calls with different results point at state caching (calls skipped as "unchanged"), not at the game.

## Build hygiene
- `cmd | tail` hides `cmd`'s exit code: check `$?` of the build itself (write the log to a file, then tail it), or a failed build gets installed and tested as if it were new.
- Restoring sources with `mv`/`cp -p` from a backup keeps the old timestamps, so ninja doesn't rebuild them; after temporary instrumentation, restore with `git checkout` or delete the build directory.
- With `MSYS_NO_PATHCONV=1`, `adb push` needs a Windows path (`cygpath -m`); a `/c/...` path fails, the old file stays on the phone, and the next step runs against it. Read the push output before going on.
- Without `MSYS_NO_PATHCONV=1`, Git Bash rewrites every phone path (`/sdcard/...`) in an adb command into `C:/Program Files/Git/sdcard/...`. `adb push` then fails yet still exits 0 (a 1.9 GB push "succeeded" this way), and `adb shell run-as ... mv` fails. Set it on every adb command that names a phone path.
- Python edits sent through a Bash heredoc turn `\n` inside C strings into real newlines, even when escaped. Build the escape with `chr(92)+'n'`, or edit C code directly in an editor.
- Python's `open(p, "w")` on Windows writes CRLF and turns a whole LF file into CRLF (git warns "CRLF will be replaced by LF"). Pass `newline="\n"` or edit the file directly; check with `file`.
- Before applying a temporary instrumentation script to `$DD_BUILD/src`, reset that tree to the committed patches (`git checkout -f` + `git apply` of the patch list): it can still carry an earlier session's instrumentation, and a second run then adds duplicates or stops half-way on a failed anchor.
- In a chain like `script && A=x; B=$A; build`, a failed script skips only `A=x`; the rest runs with empty variables. Join every step with `&&` or check each exit code.

## Power measurement on the phone
- `battery/current_now` is negative both on battery and on USB (net drain after the USB supply), so its sign doesn't show charging. Something on the phone also turns `input_suspend` back to 0 during a long run. A power run re-sets `input_suspend=1` every sample and logs when it found charging back on; a run with such samples is invalid.
- GPU timer queries on the Adreno don't show everything: an 8 MB copy measured 8 µs, and a 0.78 ms ddview draw didn't show up in kgsl busy. Judge a GPU-side change by GPU busy with the clock pinned and by a power run, not by the timers alone.

## Installing on a phone without root
- The normal way is the app's own import: open it, tap "Import game" and pick GOG's Linux installer. It unpacks the game and patches the shaders itself. The steps below are the fallback by adb.
- `adb push` into `/sdcard/Android/data/<pkg>/files/` leaves folders owned by `shell` with mode `rws---`, which the app can't open ("game data missing"), although `run-as` can read them. Push, then copy the folder as the app: `run-as org.ddport.darkest cp -r <pushed> <files>/game` (2.3 GB in ~14 s), and delete the pushed copy. Then run `patch-shaders.sh` (now in `port/apk/app/src/main/assets/`) the same way (`run-as ... sh /data/local/tmp/patch-shaders.sh <game>`), or shaders fail to compile.
- `run-as` starts in the app's data folder, whatever the shell's `cd` was, so a relative path there points into `/data/data/<pkg>`. Give it absolute paths for external storage; a relative listing once made an installed DLC look missing.
- Read the game log with `run-as org.ddport.darkest cat files/dd.log` (debug builds only; release builds are not debuggable, so `run-as` refuses them).

## Windows scripts (port/steam)
- `get-steam-game.bat` is plain batch, with no PowerShell, so that antiviruses don't mistake a harmless script for malware.
  - The first version was a `.bat` that started PowerShell with `-ExecutionPolicy Bypass` and ran its own script text through `iex`. That is the usual way to give people a PowerShell script they can just double-click, since Windows doesn't run `.ps1` files by default. But malware loaders use the same wrapper, so antivirus heuristics often flag it by that shape alone, whatever the script actually does. A flagged download looks dangerous, and the people it's meant for can't easily tell that it's a false positive.
  - Plain batch needs nothing beyond what Windows ships (reg, tasklist, findstr, choice and tar), needs no execution-policy override, and at ~120 lines is short enough to read through before running it.
- Call `find` and `tar` by full path (`%SystemRoot%\System32\...`). Run from Git Bash, or on a PC whose PATH puts Git, Cygwin or MSYS first, a bare `find` is the Unix one; the Steam check then fails ("find: 'REG_SZ': No such file").
- Inside a `( )` block, a `%path%` that holds `Program Files (x86)` ends the block at its `)`. Write such lines unblocked, e.g. `>> file echo(%PATH_VAR%`.
- Windows `tar` (bsdtar) writes zip entries with data descriptors (flag 8). Java's `ZipInputStream` rejects STORED entries that have one, so `zip:compression=store` can't be imported; deflate entries are fine. Use `zip:compression-level=1`: 49 s and 2.38 GB for the whole Steam game with two DLCs, against 55 s at the default level. Check any new zip writer with `ZipInputStream` on the PC.
- `tar -T list` accepts `-C` and a folder on their own lines, so one list can merge several depot folders into one zip.
- Run a `.bat` from Git Bash as `cmd //c "$(cygpath -w <path>)"`; a bare name fails with "not recognized" when the bat isn't in cmd's current folder.
- Python edits to Windows paths: a backslash-t in a normal string literal is a tab (`System32\tar.exe` broke this way, twice). Use raw strings, or edit the file directly in an editor.

## Release notes
- A release's notes hold only what is new in it and the signing certificate's SHA-256, nothing else: no intro line ("Runs your own GOG or Steam copy…"), no install steps, no test list, no README pointer. Those belong in the README; copied into each release, they repeat down the whole Releases page.

## Audio latency
- Check where a stream lands before tuning its buffer: `dumpsys media.audio_flinger` names the output thread and gives each track's latency. FMOD's stream was on the deep-buffer output (233 ms) and setting `LOW_LATENCY` changed nothing, because AAudio's legacy path never gives a write-driven stream a fast track (`AUDIO_OUTPUT_FLAG_FAST denied by client ... TRANSFER_SYNC`). Only a data-callback stream gets one.

## Controller support
- Controller presses can't be faked on the unrooted A34. `sendevent` into the controller's `/dev/input` node is denied for the shell user (SELinux), even though shell is in the `input` group. `input keyevent` comes from a virtual device, which the app doesn't count as a controller. Ask for real presses and check the result with screenshots and logcat.
- Before overriding an SDL call in the glue, check Box64's `wrappedsdl2_private.h`. A plain `GO` entry means the game reaches the glue's definition. A `GOM` entry means Box64's own wrapper runs first.
- Check whether the real SDL function already works before overriding it. `SDL_GameControllerEventState` only sets event states, so it needed no override.
