#!/system/bin/sh
# Mount the Debian chroot's pseudo filesystems. Idempotent. Run as root (su -M).
R=/data/local/dd/debian
T=/data/data/com.termux/files/usr/tmp   # Termux tmp: X11, virgl and pulse sockets live here

is_mounted() { grep -q " $1 " /proc/mounts; }

is_mounted $R/proc     || mount -t proc proc $R/proc
is_mounted $R/sys      || mount -t sysfs sysfs $R/sys
is_mounted $R/dev      || mount -o bind /dev $R/dev
is_mounted $R/dev/pts  || mount -o bind /dev/pts $R/dev/pts
mkdir -p /dev/shm
is_mounted $R/dev/shm  || mount -t tmpfs -o size=1G,mode=1777 tmpfs $R/dev/shm
is_mounted $R/tmp      || mount -o bind $T $R/tmp
