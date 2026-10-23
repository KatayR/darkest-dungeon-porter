#!/bin/bash
# Launch Darkest Dungeon (Linux x86_64 build) under Box64 inside the chroot. Logs to /root/dd.log.
export DISPLAY=:0
export GALLIUM_DRIVER=virpipe                 # GL via virgl_test_server_android (Termux side)
export PULSE_SERVER=unix:/tmp/pulse.sock      # audio via Termux pulseaudio
export BOX64_LOG=${BOX64_LOG:-1}
export LD_LIBRARY_PATH=/usr/local/lib/dd-tapfix  # SDL2 shim: stops instant taps being lost (sdl-tapfix/)
export GALLIUM_HUD=fps GALLIUM_HUD_VISIBLE=false GALLIUM_HUD_DUMP_DIR=/root/hud GALLIUM_HUD_PERIOD=1
                                              # FPS log, 1 sample/s, read with dd-fps
                                              # (a *visible* HUD crashes virgl on Adreno: its shader fails)
mkdir -p /root/hud && rm -f /root/hud/fps
# Pin the GPU clock while playing: the stock governor sees low load and idles at 257 MHz,
# which makes every frame wait longer in the virgl chain (~40 -> ~52 FPS at 720p). Restored on exit.
GPU_GOV=/sys/class/kgsl/kgsl-3d0/devfreq/governor
OLD_GOV=$(cat $GPU_GOV)
echo performance > $GPU_GOV
cd /opt/darkest
box64 ./_linuxnosteam/darkest.bin.x86_64 "$@" >/root/dd.log 2>&1
echo $OLD_GOV > $GPU_GOV
