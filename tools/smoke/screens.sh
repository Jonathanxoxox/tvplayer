#!/usr/bin/env bash
# Läuft im Emulator-Schritt: Deutsch und 1080p einstellen, App installieren, Tour starten.
set -x
adb root; sleep 3; adb wait-for-device
adb shell setprop persist.sys.locale de-DE
adb shell setprop ctl.restart zygote
sleep 25; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put system user_rotation 1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
pgrep -f "http.server 8080" >/dev/null || (nohup python3 -m http.server 8080 --directory fx > http.log 2>&1 &)
adb reverse tcp:8080 tcp:8080
adb install -r dist/app.apk
adb logcat -c
adb logcat -G 8M || true
# Frame report (MayoJank, one line per screen every 10 s) and startup stamps (OwnTVPerf) for tour/perf.txt.
mkdir -p tour
adb logcat -v time -s MayoJank:I OwnTVPerf:V > tour/perf-raw.txt 2>/dev/null &
PERF_PID=$!
adb shell monkey -p io.github.jonathanxoxox.tvplayer -c android.intent.category.LAUNCHER 1
# Short wait only: the tour waits for the first screen itself and photographs the intro on the way.
sleep 2
python3 tools/smoke/tour.py tour
# Cold start, five times after the tour (playlist set up): ActivityManager's TotalTime per launch.
APP=io.github.jonathanxoxox.tvplayer
COMP=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $APP | tr -d '\r' | tail -n1)
for i in 1 2 3 4 5; do
  adb shell am force-stop $APP; sleep 2
  adb shell am start -W -n "$COMP" | tr -d '\r' | grep -E "TotalTime" >> tour/startup.txt || true
  sleep 8
done
kill $PERF_PID 2>/dev/null || true
python3 - <<'PY' || true
import re
times = sorted(int(m) for m in re.findall(r"TotalTime: (\d+)", open("tour/startup.txt").read()))
lines = open("tour/perf-raw.txt", errors="ignore").read().splitlines()
agg = {}
for l in lines:
    m = re.search(r"screen=(\S+) frames=(\d+) jank=(\d+) worstMs=(\d+)", l)
    if not m: continue
    a = agg.setdefault(m.group(1), [0, 0, 0])
    a[0] += int(m.group(2)); a[1] += int(m.group(3)); a[2] = max(a[2], int(m.group(4)))
with open("tour/perf.txt", "w") as f:
    if times: f.write(f"Kaltstart TotalTime ms: Median {times[len(times)//2]} (alle: {times})\n")
    f.write("Bildschirm: Frames, ruckelnd (>1.5 Frames), Anteil, längster Frame ms\n")
    for k, (fr, j, w) in sorted(agg.items(), key=lambda kv: -kv[1][0]):
        f.write(f"{k}: {fr}, {j}, {100.0*j/max(fr,1):.1f} %, {w}\n")
    f.write("\nStart-Stempel (OwnTVPerf):\n")
    f.writelines(l + "\n" for l in lines if "OwnTVPerf" in l)
PY
adb logcat -d -b crash > tour/crash.txt || true
adb logcat -d '*:E' | tail -n 400 > tour/errors.txt || true
exit 0
