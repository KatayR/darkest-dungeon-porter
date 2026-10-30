"""Turns a `adb logcat -v threadtime` capture into a timeline relative to the last Play.

usage: python timeline.py <logcat.txt> [--all]
Prints the milestone lines (seconds since Play), then one row per span: its length, frames, stalls,
average CPU, MB read. A span runs from one press to the next, or, when the log has ddglue's screen lines
(a run by flow.py, which taps many times), from one screen to the next; presses are then only counted.
"""
import re
import sys
from datetime import datetime

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+\d+\s+\d+\s+\w\s+(\S+)\s*:\s?(.*)$")
SAMPLE = re.compile(r"frames (\d+) \(longest (\d+) ms\), cpu (\d+)% \((.*)\), read ([\d.]+) MB "
                    r"\(([\d.]+) MB from storage\) in (\d+) calls, (\d+) major faults")
TAGS = {"DDActivity", "ddglue", "ddview", "ddbench"}


def main():
    path = sys.argv[1]
    show_all = "--all" in sys.argv
    rows = []
    for raw in open(path, encoding="utf-8", errors="replace"):
        m = LINE.match(raw.rstrip("\n"))
        if not m or m.group(2) not in TAGS:
            continue
        t = datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f")
        rows.append((t, m.group(2), m.group(3)))
    plays = [i for i, r in enumerate(rows) if r[1] == "DDActivity" and r[2].startswith("Play:")]
    if not plays:
        sys.exit("no Play line")
    rows = rows[plays[-1]:]
    t0 = rows[0][0]
    by_screen = any(tag == "ddbench" and msg.startswith("screen:") for _, tag, msg in rows)

    marks = []   # (seconds, label) of the span boundaries: Play, then each press
    spans = []
    cur = {"start": 0.0, "label": "Play", "frames": 0, "stalls": 0, "stall_ms": 0, "cpu": [], "mb": 0.0,
           "disk": 0.0, "faults": 0, "presses": 0}
    for t, tag, msg in rows:
        s = (t - t0).total_seconds()
        sample = SAMPLE.match(msg) if tag == "ddbench" else None
        if sample:
            cur["frames"] += int(sample.group(1))
            cur["cpu"].append(int(sample.group(3)))
            cur["mb"] += float(sample.group(5))
            cur["disk"] += float(sample.group(6))
            cur["faults"] += int(sample.group(8))
            if show_all:
                print(f"{s:8.2f}  {tag:10} {msg}")
            continue
        if tag == "ddbench" and msg.startswith("stall"):
            cur["stalls"] += 1
            cur["stall_ms"] += int(re.search(r"(\d+) ms", msg).group(1))
            if not show_all and int(re.search(r"(\d+) ms", msg).group(1)) < 500:
                continue
        if tag in ("ddglue", "ddview") and re.match(r"fps |fps \d", msg) and not show_all:
            continue
        press = tag == "ddbench" and msg.startswith("press:")
        if press:
            cur["presses"] += 1
            if by_screen and not show_all:
                continue
        if by_screen and not show_all and tag == "DDActivity" and msg.startswith("touch "):
            continue
        print(f"{s:8.2f}  {tag:10} {msg}")
        boundary = "screen: " if by_screen else "press: "
        if tag == "ddbench" and msg.startswith(boundary):
            cur["end"] = s
            spans.append(cur)
            cur = {"start": s, "label": msg[len(boundary):], "frames": 0, "stalls": 0, "stall_ms": 0,
                   "cpu": [], "mb": 0.0, "disk": 0.0, "faults": 0, "presses": 0}
    cur["end"] = (rows[-1][0] - t0).total_seconds()
    spans.append(cur)

    print()
    print(f"{'from':>8} {'to':>8} {'secs':>6}  {'after':28} {'frames':>6} {'stalls (ms)':>12} {'cpu avg':>7} "
          f"{'MB read':>8} {'storage':>8} {'majflt':>7} {'presses':>7}")
    for sp in spans:
        cpu = sum(sp["cpu"]) / len(sp["cpu"]) if sp["cpu"] else 0
        print(f"{sp['start']:8.2f} {sp['end']:8.2f} {sp['end'] - sp['start']:6.2f}  {sp['label'][:28]:28} "
              f"{sp['frames']:6} {sp['stalls']:4} ({sp['stall_ms']:5}) {cpu:6.0f}% {sp['mb']:8.1f} "
              f"{sp['disk']:8.1f} {sp['faults']:7} {sp['presses']:7}")


main()
