#!/data/data/com.termux/files/usr/bin/sh
# Start display, GL and audio servers on the Termux side. Run as the Termux user.
# Sockets land in $TMPDIR, which dd-mount.sh binds to the chroot's /tmp.
pgrep -f com.termux.x11.CmdEntryPoint >/dev/null || \
  setsid nohup termux-x11 :0 -ac >"$TMPDIR/x11.log" 2>&1 &
pgrep -x virgl_test_server_android >/dev/null || \
  setsid nohup virgl_test_server_android >"$TMPDIR/virgl.log" 2>&1 &
pulseaudio --check 2>/dev/null || \
  pulseaudio --start --exit-idle-time=-1 \
    --load="module-native-protocol-unix socket=$TMPDIR/pulse.sock auth-anonymous=1"
sleep 2
am start -n com.termux.x11/.MainActivity >/dev/null 2>&1
ls -a "$TMPDIR" "$TMPDIR/.X11-unix" 2>/dev/null | tr '\n' ' '; echo
