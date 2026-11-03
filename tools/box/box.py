#!/usr/bin/env python3
"""Mayo TV auf einer echten Box im WLAN: installieren, einrichten, messen, Bildschirmfotos.

Anders als tools/smoke/ (Emulator, Test-Playlist) arbeitet dieses Skript auf der Box, die jemand
wirklich benutzt. Es ändert deshalb keine Playlist und keine App-Einstellungen, und `bench` läuft
nur, wenn niemand fernsieht (siehe `state`).

  python tools/box/box.py connect                 # adb connect, Box-Daten
  python tools/box/box.py state                   # schläft / frei / jemand sieht fern
  python tools/box/box.py setup                   # Rechte und Box-Einstellungen (beliebig oft)
  python tools/box/box.py ci <branch>             # wartet auf tvplayer.yml und nennt das Release-Tag
  python tools/box/box.py install <tag|apk>       # z. B. v1.0.331 oder pfad/MayoTV.apk
  python tools/box/box.py bench --label vorher    # Messlauf, Ergebnis als JSON + eine Zeile in history.md
  python tools/box/box.py shot <name>             # Bildschirmfoto
  python tools/box/box.py dump                    # UI-Baum (Fokus, Texte)
  python tools/box/box.py logs [sekunden]         # Logcat der App mitlesen

Box und Ablage lassen sich über BOX (ip:port) und BOX_OUT (Ergebnisordner) ändern.
"""
import argparse
import datetime as dt
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

PKG = "io.github.jonathanxoxox.tvplayer"
REPO = "Jonathanxoxox/tvplayer"
BOX = os.environ.get("BOX", "192.168.0.145:5555")
OUT = os.environ.get("BOX_OUT", os.path.join(os.getcwd(), "box-results"))
LOG_TAGS = ["OwnTVPerf:V", "FrameRate:V", "LiveZapList:V", "MediaCodec:E", "CCodec:E", "AndroidRuntime:E", "*:S"]


def adb_path():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = shutil.which("adb")
    if found:
        return found
    pattern = os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe")
    hits = glob.glob(pattern)
    if hits:
        return hits[0]
    sys.exit("adb nicht gefunden (ADB=... setzen)")


ADB = adb_path()


def adb(*args, timeout=60, check=False):
    r = subprocess.run([ADB, "-s", BOX, *args], capture_output=True, text=True, timeout=timeout,
                       encoding="utf-8", errors="replace")
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


STAMP = os.path.join(tempfile.gettempdir(), "mayo-box-lastkey")


def key(name, n=1, wait=0.4):
    for _ in range(n):
        sh(f"input keyevent KEYCODE_{name}")
        # Merken, wann wir selbst gedrückt haben: `box_state` soll das nicht für Fernsehen halten.
        with open(STAMP, "w") as f:
            f.write(str(time.time()))
        time.sleep(wait)


def connect(quiet=False):
    subprocess.run([ADB, "connect", BOX], capture_output=True, text=True, timeout=20)
    state = subprocess.run([ADB, "-s", BOX, "get-state"], capture_output=True, text=True, timeout=10).stdout.strip()
    if state != "device":
        if not quiet:
            print(f"Box {BOX} nicht erreichbar ({state or 'offline'})")
        return False
    return True


def version():
    m = re.search(r"versionName=(\S+)", sh(f"dumpsys package {PKG} | grep -m1 versionName"))
    return m.group(1) if m else "?"


def box_state():
    """'offline', 'asleep', 'busy' (jemand sieht fern) oder 'idle'."""
    if not connect(quiet=True):
        return "offline"
    power = sh("dumpsys power")
    wake = re.search(r"mWakefulness=(\w+)", power)
    if not wake or wake.group(1) == "Asleep":
        return "asleep"
    # Bildschirmschoner: startet nur ohne Wiedergabe und ohne Eingabe, es sieht also niemand fern.
    if wake.group(1) == "Dreaming" or "DreamActivity" in sh("dumpsys activity activities | grep -m1 topResumedActivity"):
        return "idle"
    # Letzte Eingabe (uptimeMillis). "Jetzt" in derselben Uhr liefert mLastWakeTime=X (Y ms ago).
    last = re.search(r"mLastUserActivityTime\S*=(\d+)", power)
    ref = re.search(r"mLastWakeTime=(\d+) \((\d+) ms ago\)", power)
    if not last or not ref:
        return "busy"
    idle_s = (int(ref.group(1)) + int(ref.group(2)) - int(last.group(1))) / 1000
    ours = None
    try:
        ours = time.time() - float(open(STAMP).read())
    except (OSError, ValueError):
        pass
    # Die letzte Eingabe war unsere eigene (Messlauf), wenn sie zeitlich zu unserem letzten Tastendruck passt.
    if ours is not None and abs(ours - idle_s) < 15:
        return "idle"
    return "idle" if idle_s > 20 * 60 else "busy"


# ---------------------------------------------------------------------------------------------
# setup

GRANTS = [
    f"pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS",
    f"appops set {PKG} SYSTEM_ALERT_WINDOW allow",
    f"appops set {PKG} ACCESS_RESTRICTED_SETTINGS allow",
    f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow",
    f"cmd deviceidle whitelist +{PKG}",
    f"cmd package set-home-activity --user 0 {PKG}/tv.own.owntv.MainActivity",
]
SETTINGS = [
    "settings put global window_animation_scale 0.5",
    "settings put global transition_animation_scale 0.5",
    "settings put global animator_duration_scale 0.5",
    # Bildwiederholrate anpassen: Immer. Ab Werk "nur nahtlos" – 60→50 Hz ist nie nahtlos, Mayos AFR
    # hält sich an die Systemeinstellung und schaltet dann nie auf 50 Hz.
    "settings put secure match_content_frame_rate 2",
]


# Loop-Modus: Fernseher aus lässt die Box wach (Bildschirmschoner, Mayo stoppt die Wiedergabe in
# onStop), damit sie im WLAN bleibt. Im Tiefschlaf schaltet die Box WLAN ab und ist nicht weckbar.
LOOPMODE = {
    "on": ["settings put global hdmi_control_auto_device_off_enabled 0", "settings put secure sleep_timeout -1"],
    "off": ["settings put global hdmi_control_auto_device_off_enabled 1", "settings put secure sleep_timeout 900000"],
}


def cmd_loopmode(a):
    if not connect():
        return 1
    for c in LOOPMODE[a.mode]:
        sh(c)
        print(c)
    return 0


def cmd_setup(_):
    if not connect():
        return 1
    for c in GRANTS + SETTINGS:
        out = sh(f"{c} 2>&1").strip()
        print(("ok  " if not re.search(r"Exception|Error|Unknown|not ", out) else "!!  ") + c + (f"  -> {out}" if out else ""))
    return 0


# ---------------------------------------------------------------------------------------------
# CI und Installation

def gh(*args, timeout=120):
    r = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    if r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def cmd_ci(a):
    """Wartet auf den letzten tvplayer.yml-Lauf des Branches; gibt das Release-Tag v1.0.<Lauf> aus."""
    deadline = time.time() + a.timeout
    while True:
        runs = json.loads(gh("run", "list", "-R", REPO, "--workflow", "tvplayer.yml", "--branch", a.branch, "-L", "1",
                             "--json", "databaseId,number,status,conclusion,headSha"))
        if runs and runs[0]["status"] == "completed":
            r = runs[0]
            if r["conclusion"] != "success":
                print(f"FEHLER Lauf {r['number']} {r['conclusion']}: https://github.com/{REPO}/actions/runs/{r['databaseId']}")
                return 1
            print(f"v1.0.{r['number']}")
            return 0
        if time.time() > deadline:
            print("Zeitüberschreitung")
            return 2
        time.sleep(30)


def cmd_install(a):
    if not connect():
        return 1
    apk = a.what
    if not os.path.isfile(apk):
        tmp = tempfile.mkdtemp(prefix="mayo-")
        gh("release", "download", apk, "-R", REPO, "-p", "MayoTV-v*.apk", "-D", tmp, timeout=600)
        apk = glob.glob(os.path.join(tmp, "*.apk"))[0]
    before = version()
    out = adb("install", "-r", apk, timeout=600)
    print(out.strip().splitlines()[-1] if out.strip() else "?")
    print(f"{before} -> {version()}")
    # Nach dem Update wieder Startbildschirm, sonst steht die Box im Einstellungs-Fallback.
    sh(f"cmd package set-home-activity --user 0 {PKG}/tv.own.owntv.MainActivity")
    # adb install kompiliert nur "verify"; der In-App-Updater liefert das Startprofil (.dm) mit, und der
    # Hintergrund-Dexopt kommt später nach. Einmal starten (profileinstaller legt das Profil ab) und
    # gleich mit speed-profile kompilieren, sonst misst der Vergleich die Kompilierung statt der Änderung.
    key("HOME", wait=12)
    print(sh(f"cmd package compile -m speed-profile -f {PKG}", timeout=300).strip())
    return 0 if "Success" in out else 1


# ---------------------------------------------------------------------------------------------
# Messen

def gfx_reset():
    sh(f"dumpsys gfxinfo {PKG} reset")


def gfx_read():
    txt = sh(f"dumpsys gfxinfo {PKG}")
    def num(pattern, cast=int):
        m = re.search(pattern, txt)
        return cast(m.group(1)) if m else None
    return {
        "frames": num(r"Total frames rendered: (\d+)"),
        "janky": num(r"Janky frames: (\d+)"),
        "janky_pct": num(r"Janky frames: \d+ \(([\d.]+)%\)", float),
        "p50": num(r"50th percentile: (\d+)ms"),
        "p90": num(r"90th percentile: (\d+)ms"),
        "p99": num(r"99th percentile: (\d+)ms"),
        "slow_ui": num(r"Number Slow UI thread: (\d+)"),
        "slow_draw": num(r"Number Slow issue draw commands: (\d+)"),
        "frozen": num(r"Number Frame deadline missed: (\d+)"),
    }


def meminfo():
    txt = sh(f"dumpsys meminfo {PKG}")
    def kb(label):
        m = re.search(label + r":\s+(\d+)", txt)
        return int(m.group(1)) // 1024 if m else None
    return {"pss_mb": kb("TOTAL PSS"), "java_mb": kb("Java Heap"), "native_mb": kb("Native Heap"), "graphics_mb": kb("Graphics")}


def display_mode():
    m = re.search(r"mActiveSfDisplayMode=DisplayMode\{id=\d+, width=(\d+), height=(\d+).*?refreshRate=([\d.]+)", sh("dumpsys display"))
    return f"{m.group(1)}x{m.group(2)}@{float(m.group(3)):.3f}" if m else "?"


def active_codecs():
    txt = sh("dumpsys media.resource_manager")
    return sorted(set(re.findall(r"(c2\.[\w.]+|OMX\.[\w.]+)", txt)))


def cold_start(runs):
    """Prozess beenden; als Startbildschirm startet das System Mayo sofort neu (kalt). `am start -W`
    liefert für die Home-Activity keine Zeit, gemessen wird über Mayos eigene Stempel (start_times)."""
    for _ in range(runs):
        sh(f"am force-stop {PKG}")
        time.sleep(1)
        key("HOME", wait=14)


def start_times(lines):
    """Pro Prozess: ms bis zum ersten Bild und bis 'settled', aus den OwnTVPerf-Stempeln."""
    runs = {}
    for l in lines:
        m = re.search(r"OwnTVPerf\(\s*(\d+)\): (startup-first-frame|startup-settled) @(\d+)ms", l)
        if m:
            runs.setdefault(m.group(1), {})[m.group(2)] = int(m.group(3))
    return list(runs.values())


def open_live():
    """Startseite → Live-TV (Seitenleiste) → erste Gruppe → erster Sender."""
    key("HOME", wait=3)
    key("DPAD_DOWN", wait=1)
    key("DPAD_CENTER", wait=3)
    key("DPAD_RIGHT", wait=1)
    key("DPAD_RIGHT", wait=2)
    key("DPAD_CENTER", wait=10)


def logcat_start():
    sh("logcat -c")
    return subprocess.Popen([ADB, "-s", BOX, "logcat", "-v", "time", *LOG_TAGS], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")


def logcat_stop(p):
    p.terminate()
    try:
        out, _ = p.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return out.replace("\r", "").splitlines()


def cmd_bench(a):
    st = box_state()
    if st in ("offline",):
        print("Box offline")
        return 1
    if st == "busy" and not a.force:
        print("Box wird benutzt (letzte Eingabe < 20 min) – Messung übersprungen")
        return 3
    # Ohne CEC messen, damit der Fernseher aus bleibt, falls er aus ist.
    cec = sh("settings get global hdmi_control_enabled").strip()
    quiet = st != "busy"
    if quiet:
        sh("settings put global hdmi_control_enabled 0")
    woke = st == "asleep"
    key("WAKEUP", wait=3)
    res = {"time": dt.datetime.now().isoformat(timespec="seconds"), "label": a.label, "version": version(),
           "box": BOX, "display_idle": display_mode()}
    log = logcat_start()
    try:
        cold_start(a.runs)
        gfx_reset()
        for k in ["DOWN"] * 6 + ["UP"] * 6 + ["RIGHT"] * 8 + ["LEFT"] * 8:
            key(k, wait=0.35)
        time.sleep(1)
        res["home_nav"] = gfx_read()
        res["mem_home"] = meminfo()

        open_live()
        res["display_live"] = display_mode()
        res["codecs_live"] = active_codecs()
        gfx_reset()
        for k in ["CHANNEL_UP"] * 5 + ["CHANNEL_DOWN"] * 5:
            key(k, wait=4)
        res["zap"] = gfx_read()
        res["display_after_zap"] = display_mode()
        res["mem_live"] = meminfo()
        if a.shots:
            shot(f"bench-live-{res['version']}")
        key("HOME", wait=3)
    finally:
        lines = logcat_stop(log)
        if woke:
            key("SLEEP", wait=2)
        elif quiet:
            # Zurück zum Bildschirmschoner, wie vor dem Messlauf.
            sh("am start -n com.android.systemui/.Somnambulator 2>/dev/null || input keyevent KEYCODE_SLEEP")
        if quiet:
            sh(f"settings put global hdmi_control_enabled {cec if cec not in ('', 'null') else 1}")
    res["cold_start"] = start_times(lines)
    ff = [r["startup-first-frame"] for r in res["cold_start"] if "startup-first-frame" in r]
    res["cold_start_median_ms"] = int(statistics.median(ff)) if ff else None
    res["slow_frames_start"] = [int(m.group(1)) for l in lines for m in [re.search(r"slow-frame (\d+) ms", l)] if m]
    res["perf_stamps"] = [l for l in lines if "OwnTVPerf" in l][-60:]
    res["framerate_log"] = [l for l in lines if "FrameRate" in l][-30:]
    res["errors"] = [l for l in lines if re.search(r" [EF]/", l)][-40:]
    res["crashed"] = bool(sh(f"logcat -d -b crash | grep -c {PKG}").strip() not in ("", "0"))

    os.makedirs(OUT, exist_ok=True)
    name = f"{res['time'][:16].replace(':', '')}-{res['version']}-{a.label}.json"
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        json.dump(res, f, indent=1, ensure_ascii=False)
    line = (f"| {res['time'][:16]} | {res['version']} | {a.label} | {res['cold_start_median_ms']} | "
            f"{res['home_nav']['janky_pct']} / p90 {res['home_nav']['p90']} | {res['zap']['janky_pct']} / p90 {res['zap']['p90']} | "
            f"{res['mem_live']['pss_mb']} | {res['display_live']} | {len(res['errors'])} | {'JA' if res['crashed'] else '-'} |")
    hist = os.path.join(OUT, "history.md")
    if not os.path.exists(hist):
        with open(hist, "w", encoding="utf-8") as f:
            f.write("| Zeit | Version | Label | Kaltstart ms (Median) | Home: Ruckler % / p90 ms | Zappen: Ruckler % / p90 ms | PSS Live MB | Bildmodus Live | Fehler | Absturz |\n")
            f.write("|---|---|---|---|---|---|---|---|---|---|\n")
    with open(hist, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line)
    print(os.path.abspath(os.path.join(OUT, name)))
    return 0


# ---------------------------------------------------------------------------------------------
# Kleinkram

def shot(name):
    os.makedirs(os.path.join(OUT, "shots"), exist_ok=True)
    path = os.path.abspath(os.path.join(OUT, "shots", f"{name}.png"))
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "-s", BOX, "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout)
    print(path)
    return path


def cmd_dump(_):
    sh("uiautomator dump /sdcard/mayo-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/mayo-ui.xml")
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?focused="(\w+)"[^>]*?bounds="([^"]+)"', xml):
        text, desc, focused, bounds = m.groups()
        if text or desc or focused == "true":
            print(("» " if focused == "true" else "  ") + f"{text or desc}  {bounds}")
    return 0


def cmd_logs(a):
    p = logcat_start()
    time.sleep(a.seconds)
    print("\n".join(logcat_stop(p)))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("connect")
    sub.add_parser("state")
    sub.add_parser("setup")
    p = sub.add_parser("loopmode"); p.add_argument("mode", choices=["on", "off"])
    p = sub.add_parser("ci"); p.add_argument("branch"); p.add_argument("--timeout", type=int, default=1800)
    p = sub.add_parser("install"); p.add_argument("what")
    p = sub.add_parser("bench"); p.add_argument("--label", default="lauf"); p.add_argument("--runs", type=int, default=5)
    p.add_argument("--force", action="store_true"); p.add_argument("--shots", action="store_true")
    p = sub.add_parser("shot"); p.add_argument("name")
    sub.add_parser("dump")
    p = sub.add_parser("logs"); p.add_argument("seconds", type=int, nargs="?", default=30)
    a = ap.parse_args()

    if a.cmd == "connect":
        if not connect():
            return 1
        print(f"{BOX}: {sh('getprop ro.product.model').strip()}, Android {sh('getprop ro.build.version.release').strip()}, Mayo TV {version()}")
        return 0
    if a.cmd == "state":
        print(box_state())
        return 0
    if a.cmd == "shot":
        return 0 if connect() and shot(a.name) else 1
    return {"setup": cmd_setup, "loopmode": cmd_loopmode, "ci": cmd_ci, "install": cmd_install, "bench": cmd_bench,
            "dump": cmd_dump, "logs": cmd_logs}[a.cmd](a) or 0


if __name__ == "__main__":
    sys.exit(main())
