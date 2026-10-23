#!/system/bin/sh
# dd-bench.sh LABEL [VAR=VAL ...]: launch the game with extra env, skip the intro, sample 30 s on the title screen.
# Run as root (su -M). Prints FPS, per-thread CPU, GPU busy/clock, CPU clocks, temps; saves /sdcard/bench_LABEL.png.
L=$1; shift
R=/data/local/dd/debian
G=/sys/class/kgsl/kgsl-3d0
FPS=$R/root/hud/fps
X() { chroot $R /usr/bin/env DISPLAY=:0 /usr/bin/"$@"; }

pkill -f darkest.bin; sleep 2; rm -f $FPS
am start -n com.termux.x11/.MainActivity >/dev/null
setsid nohup sh /data/local/dd/bin/dd-enter.sh "env $* dd-run" >/dev/null 2>&1 &
i=0; while [ ! -s $FPS ] && [ $i -lt 90 ]; do sleep 1; i=$((i+1)); done
[ -s $FPS ] || { echo "$L: no FPS samples after 90 s"; tail -5 $R/root/dd.log; exit 1; }
echo "$L: first frame after ${i}s"
sleep 8
for n in 1 2 3 4 5 6 7 8 9 10 11 12; do X xdotool mousemove 640 360 click 1; sleep 1; done
sleep 12
dumpsys window | grep -q "mCurrentFocus.*com.termux.x11" || echo "WARNING: Termux:X11 not in front"
start=$(wc -l < $FPS)
B=""; C=""
for n in 1 2 3 4 5 6; do
  sleep 5
  B="$B $(cat $G/gpu_busy_percentage | tr -d ' %')"
  C="$C $(($(cat $G/gpuclk)/1000000))"
done
tail -n +$((start+1)) $FPS | awk -v L="$L" '{ s+=$1; if (min==""||$1<min) min=$1; if ($1>max) max=$1 }
  END { printf "%s: FPS min %.1f avg %.1f max %.1f (%d s)\n", L, min, s/NR, max, NR }'
echo "gpu busy%:$B   gpu MHz:$C"
echo "cpu MHz: little $(($(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq)/1000)) big $(($(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq)/1000))"
echo "max tsens: $(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)"
top -H -b -n 2 -d 4 -m 14 -s 3 -o PID,TID,%CPU,CPU,NAME,CMD | tail -15
screencap -p /sdcard/bench_$L.png
pkill -f darkest.bin
