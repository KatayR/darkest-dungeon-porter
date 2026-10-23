#!/system/bin/sh
# Unmount everything under the chroot (deepest first) and fail loudly if anything is left.
R=/data/local/dd/debian
for m in $(grep " $R/" /proc/mounts | cut -d' ' -f2 | sort -r); do
  umount $m || umount -l $m
done
if grep -q " $R/" /proc/mounts; then echo "STILL MOUNTED:"; grep " $R/" /proc/mounts; exit 1; fi
echo "chroot unmounted"
