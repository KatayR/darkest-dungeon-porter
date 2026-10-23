#!/system/bin/sh
# Enter the chroot (mounting first). Extra args run as one command: dd-enter.sh 'apt update'
D=$(dirname $0)
R=/data/local/dd/debian
sh $D/dd-mount.sh
ENV="HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
if [ $# -eq 0 ]; then
  exec chroot $R /usr/bin/env -i $ENV /bin/bash -l
else
  exec chroot $R /usr/bin/env -i $ENV /bin/bash -lc "$*"
fi
