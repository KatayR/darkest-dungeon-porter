#!/bin/bash
# Print FPS of the running game: the last N one-second samples (default 10) and their min/avg/max.
# dd-run logs one sample per second to /root/hud/fps.
N=${1:-10}
tail -n "$N" /root/hud/fps | awk '{ printf "%.1f ", $1; s += $1; if (min == "" || $1 < min) min = $1; if ($1 > max) max = $1 }
  END { if (NR) printf "\nmin %.1f  avg %.1f  max %.1f  (%d s)\n", min, s / NR, max, NR; else print "no samples yet" }'
