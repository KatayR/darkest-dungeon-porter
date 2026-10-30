"""Drives the load-time flow on the phone and records it: Play, the splashes and intro, the info dialog,
Campaign, the first save, its loading, then the hamlet, or the dungeon after a click on its loading screen.
It taps by the ddbench "screen:" lines of a debug build (ddglue's bench_screen), at once, so a run measures
the game rather than whoever taps.

usage: python flow.py [label]
Needs adb with one phone and a save in the first slot. Sets the app's controls to Touch (in Trackpad mode a
tap clicks where the cursor is) in its preferences, which run-as can write in a debug build. Tap positions are the A34's screen (2340x1080, landscape; see notes/todo.md).
Saves the log to notes/bench/<date>-<label>.log and prints its timeline. Stops with a screenshot on a
screen it doesn't know or when nothing changes for STUCK_S seconds.
"""
import datetime
import os
import re
import subprocess
import sys
import threading
import time

PACKAGE = "org.ddport.darkest"
TAGS = ["DDActivity:V", "ddglue:V", "ddview:V", "ddbench:V"]
HERE = os.path.dirname(os.path.abspath(__file__))
STUCK_S = 30
AFTER_S = 3   # recording after the hamlet shows, for the load's last samples

PLAY = (1681, 666)
MIDDLE = (1170, 540)
CAMPAIGN = (1131, 943)
FIRST_SAVE = (858, 641)
# the game's dialogs (ddglue logs their text and answers): the answers' places, first to last (the same in
# the analytics dialog and the DLC warning), and which answer to tap, by words in the text
ANSWERS = [(1117, 584), (1117, 639)]
DIALOGS = [
    ("analytics", "Disable Data Collection"),   # first start after a wipe
    ("required dlc is missing", "Continue"),    # a save made with DLC that isn't imported; strips it from the save
]

# front end states tapped through in the middle (the splashes, the intro movie, the info dialog)
SKIP = {"SPLASH0", "APPLE_DEMO_SPLASH", "HOR_TRAILER", "PREAMBLE", "INTRO_MOVIE", "POST_INTRO_MOVIE"}


def adb(*args, **kw):
    return subprocess.run(["adb", *args], capture_output=True, **kw)


def tap(xy):
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))


def ensure_touch():
    """Sets "touch" in the activity's preferences (getPreferences: DDActivity.xml), keeping the other keys.
    The app must be stopped, or it writes its own copy back."""
    path = "shared_prefs/DDActivity.xml"
    r = adb("exec-out", "run-as", PACKAGE, "cat", path, text=True)
    xml = r.stdout if r.returncode == 0 and "<map" in r.stdout else ""
    entry = '<boolean name="touch" value="true" />'
    if entry in xml:
        return
    if xml:
        xml = re.sub(r'\s*<boolean name="touch" value="false" />', "", xml)
        xml = xml.replace("</map>", f"    {entry}\n</map>")
    else:
        xml = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    {entry}\n</map>\n"
    adb("shell", f"run-as {PACKAGE} sh -c 'mkdir -p shared_prefs && cat > {path}'", input=xml, text=True)
    print("controls set to Touch")


def dismiss_system_dialog():
    """Taps "Don't Show Again" on Android's App Compatibility warning, which a debuggable app gets on its first
    launch after an install or a wipe (here: its native libraries aren't 16 KB aligned). True if it was there."""
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
    xml = adb("exec-out", "cat", "/sdcard/window.xml", text=True, encoding="utf-8", errors="replace").stdout
    m = re.search(r'text="Don.t Show Again"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    tap(((x1 + x2) // 2, (y1 + y2) // 2))
    time.sleep(0.5)
    return True


def screenshot(path):
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p").stdout)


class Log:
    """Follows logcat on a thread; keeps every line and the latest screen line."""

    def __init__(self):
        adb("logcat", "-c")
        self.proc = subprocess.Popen(["adb", "logcat", "-v", "threadtime", "-s", *TAGS],
                                     stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        self.lines = []
        self.screen = None
        self.screen_since = time.monotonic()
        self.played = False
        self.lock = threading.Lock()
        threading.Thread(target=self.follow, daemon=True).start()

    def follow(self):
        for line in self.proc.stdout:
            with self.lock:
                self.lines.append(line)
                if "DDActivity: Play:" in line:
                    self.played = True
                m = re.search(r"ddbench : screen: (.*)$", line.rstrip("\n"))
                if m:
                    self.screen = m.group(1)
                    self.screen_since = time.monotonic()

    def state(self):
        with self.lock:
            return self.screen, self.screen_since

    def stop(self):
        self.proc.terminate()
        with self.lock:
            return list(self.lines)


def action(screen):
    """What to do on a screen: (position to tap, seconds between taps), "wait", "done", or None (unknown)."""
    m = re.search(r', dialog "(.*)"(?:: (.*))?$', screen)
    if m:
        text, answers = m.group(1).lower(), (m.group(2) or "").split(" | ")
        for words, answer in DIALOGS:
            if words in text:
                for i, a in enumerate(answers):
                    if a.startswith(answer) and i < len(ANSWERS):
                        return ANSWERS[i], 1.0
        return None
    if screen.startswith("loading") and screen.endswith(", done") and "to the hamlet" not in screen:
        return MIDDLE, 0.3   # a dungeon's loading screen waits for a click; the hamlet's goes on by itself
    if screen.startswith("loading") or screen == "none":
        return "wait"
    if screen.startswith("hamlet") or screen.startswith("dungeon"):
        return "done"
    if ", video" in screen:
        return MIDDLE, 0.3
    m = re.match(r"front end (\S+)", screen)
    if m:
        name = m.group(1)
        if name in SKIP:
            return MIDDLE, 0.3
        if name == "START":
            return CAMPAIGN, 0.3
        if name == "SAVES":
            return FIRST_SAVE, 0.3
        if name in ("STEAM_SAVE_CHECK", "INSANITY"):   # INSANITY: the fade from the save list into loading
            return "wait"
    return None


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "flow"
    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M%S")
    out = os.path.join(HERE, f"{stamp}-{label}.log")
    adb("shell", "am", "force-stop", PACKAGE)
    ensure_touch()
    log = Log()
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.DDActivity")
    t0 = time.monotonic()

    def say(msg):
        print(f"{time.monotonic() - t0:7.2f}  {msg}", flush=True)

    # Play, again each second until the app logs it (the launch screen takes a moment to show). From the
    # second try on, first Android's own warning, which covers Play
    tries = 0
    while not log.played:
        if time.monotonic() - t0 > 20:
            say("no Play after 20 s")
            break
        time.sleep(1.0)
        if log.played:
            break
        if tries == 1 and dismiss_system_dialog():
            say("tap Don't Show Again on Android's App Compatibility warning")
        tap(PLAY)
        tries += 1
        say("tap Play")
    result = "stopped"
    last_tap, seen = 0.0, None
    while log.played:
        screen, since = log.state()
        now = time.monotonic()
        if screen != seen:
            seen = screen
            say(f"screen: {screen}")
            last_tap = 0.0   # a new screen gets its first tap at once
        if screen is None:
            todo = "wait"
        else:
            todo = action(screen)
        if todo == "done":
            result = screen.split(",")[0]
            time.sleep(AFTER_S)
            break
        if todo is None:
            shot = out[:-4] + ".png"
            screenshot(shot)
            say(f"unknown screen, stopped: {screen} (screenshot {shot})")
            break
        if now - since > STUCK_S:
            shot = out[:-4] + ".png"
            screenshot(shot)
            say(f"no change for {STUCK_S} s, stopped (screenshot {shot})")
            break
        if todo != "wait":
            xy, every = todo
            if now - last_tap >= every:
                tap(xy)
                last_tap = time.monotonic()
                say(f"tap {xy}")
        time.sleep(0.05)
    lines = log.stop()
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(lines)
    say(f"{result}; log in {out}")
    subprocess.run([sys.executable, os.path.join(HERE, "timeline.py"), out])


main()
