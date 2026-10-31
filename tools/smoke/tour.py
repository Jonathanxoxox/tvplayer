#!/usr/bin/env python3
"""Bildschirm-Tour für den Starttest: richtet Mayo TV mit der Test-Playlist ein, läuft per
Fernbedienungstasten durch Menüs, Einstellungen, TV-Guide und Player und legt von jedem Schritt ein
Bildschirmfoto plus die sichtbaren Texte ab. So lassen sich abgeschnittene, überlappende oder
verdeckte Elemente ohne Fernseher prüfen.

Gesteuert wird nur mit Tasten (wie mit der Fernbedienung): `goto` sucht ein Element über den
UI-Baum und bewegt den Fokus mit dem Steuerkreuz dorthin.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.github.jonathanxoxox.tvplayer"
OUT = sys.argv[1] if len(sys.argv) > 1 else "tour"
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "index.txt"), "a", encoding="utf-8")
steps = []
counter = [0]
# Zeilen, die die Tour nie auslöst: sie löschen, setzen zurück oder verlassen die App.
DANGER = re.compile(
    r"Lösch|Zurücksetzen|Abmelden|Entfernen|Neustart|Neu starten|Beenden|Werkseinst|Alle Daten|Delete|Reset|Remove|"
    r"Log ?out|Restart|Exit|Wiederherstellen|Restore|Sicherung erstellen|Leeren|Clear|Update|Aktualisierung",
    re.I,
)


def adb(*args, check=False, timeout=60):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def key(name, n=1, wait=0.6, long=False):
    for _ in range(n):
        sh(f"input keyevent {'--longpress ' if long else ''}KEYCODE_{name}")
        time.sleep(wait)


_key_dev = []


def hold(code=28, seconds=1.5):
    """Eine Taste wirklich gedrückt halten (Standard: Enter, Linux-Code 28), über das Eingabegerät des
    Emulators. `input keyevent --longpress` schickt nur eine Wiederholung; gehalten erzeugt Android die
    Wiederholungen selbst, alle 50 ms, wie bei einer Fernbedienung (braucht adb root, siehe screens.sh)."""
    if not _key_dev:
        dev, cur = None, None
        for line in sh("getevent -pl").splitlines():
            m = re.match(r"add device \d+: (\S+)", line)
            if m:
                cur = m.group(1)
            elif cur and "KEY_ENTER" in line and dev is None:
                dev = cur
        _key_dev.append(dev)
    dev = _key_dev[0]
    if not dev:
        print("[hold] kein Eingabegerät mit KEY_ENTER, nehme --longpress", flush=True)
        key("ENTER", long=True, wait=0)
        return
    sh(f"sendevent {dev} 1 {code} 1; sendevent {dev} 0 0 0")
    time.sleep(seconds)
    sh(f"sendevent {dev} 1 {code} 0; sendevent {dev} 0 0 0")


def bounds(node):
    m = re.findall(r"\d+", node.get("bounds", "[0,0][0,0]"))
    return tuple(int(v) for v in m)


def dump():
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", timeout=40)
        xml = sh("cat /sdcard/ui.xml")
        if xml.strip().startswith("<?xml"):
            try:
                return ET.fromstring(xml[xml.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def label(node):
    return (node.get("text") or node.get("content-desc") or "").strip()


def texts(root):
    return [(label(n), bounds(n)) for n in root.iter("node") if label(n)]


def focused(root):
    best = None
    for n in root.iter("node"):
        if n.get("focused") == "true":
            best = n  # innerstes fokussiertes Element
    return best


def focused_text(root):
    f = focused(root)
    if f is None:
        return ""
    own = label(f)
    if own:
        return own
    inner = [label(n) for n in f.iter("node") if label(n)]
    return " · ".join(inner[:3])


def shot(name):
    counter[0] += 1
    fn = f"{counter[0]:03d}-{re.sub(r'[^A-Za-z0-9äöüÄÖÜß_-]+', '_', name)[:60]}"
    time.sleep(0.8)
    # Erst das Bild, dann der UI-Baum: der Dump dauert Sekunden, kurze Einblendungen wären sonst weg.
    png = os.path.join(OUT, fn + ".png")
    with open(png, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    root = dump()
    t = texts(root)
    w, h = 1920, 1080
    off = [s for s, b in t if b[0] < 0 or b[1] < 0 or b[2] > w or b[3] > h]
    entry = {"step": fn, "focus": focused_text(root), "texts": [s for s, _ in t][:80], "offscreen": off}
    steps.append(entry)
    log.write(json.dumps(entry, ensure_ascii=False) + "\n")
    log.flush()
    print(f"[shot] {fn}  focus={entry['focus']!r}", flush=True)
    return root


def find(root, pattern, xmin=0, xmax=99999):
    rx = re.compile(pattern, re.I)
    hits = [n for n in root.iter("node") if label(n) and rx.search(label(n)) and xmin <= bounds(n)[0] <= xmax]
    return hits[0] if hits else None


def goto(pattern, press=True, tries=24, xmin=0, xmax=99999):
    """Fokus per Steuerkreuz auf das Element mit passendem Text bewegen und (optional) OK drücken."""
    last = None
    for _ in range(tries):
        root = dump()
        target = find(root, pattern, xmin, xmax)
        if target is None:
            print(f"[goto] {pattern!r} nicht gefunden; sichtbar: {[s for s, _ in texts(root)][:25]}", flush=True)
            return False
        tb = bounds(target)
        cx, cy = (tb[0] + tb[2]) // 2, (tb[1] + tb[3]) // 2
        f = focused(root)
        if f is not None:
            fb = bounds(f)
            if fb[0] <= cx <= fb[2] and fb[1] <= cy <= fb[3]:
                if press:
                    key("DPAD_CENTER", wait=1.2)
                return True
            fx, fy = (fb[0] + fb[2]) // 2, (fb[1] + fb[3]) // 2
            dx, dy = cx - fx, cy - fy
            # Erst die Zeile wechseln, dann seitlich: ◀ ▶ auf einer Stepper-Zeile verstellt sonst den Wert.
            th = max(20, (fb[3] - fb[1]) // 2)
            d = ("DPAD_DOWN" if dy > 0 else "DPAD_UP") if abs(dy) > th else ("DPAD_RIGHT" if dx > 0 else "DPAD_LEFT")
            if last == (fb, d):  # Fokus bewegt sich nicht mehr: andere Achse versuchen
                d = ("DPAD_DOWN" if dy > 0 else "DPAD_UP") if d in ("DPAD_LEFT", "DPAD_RIGHT") else ("DPAD_RIGHT" if dx > 0 else "DPAD_LEFT")
            last = (fb, d)
        else:
            d = "DPAD_DOWN"
        key(d, wait=0.4)
    print(f"[goto] {pattern!r}: Fokus nicht erreicht", flush=True)
    return False


def goto_row(pattern, tries=30):
    """Im Guide die Zeile des Senders ansteuern: der Fokus steht dort auf der Sendung, nicht auf dem
    Namen, deshalb nur ▲▼ nach der Höhe des Namens (◀ würde den Guide in die Gruppen verlassen)."""
    for _ in range(tries):
        root = dump()
        target = find(root, pattern)
        f = focused(root)
        if target is None or f is None:
            print(f"[goto_row] {pattern!r} nicht gefunden", flush=True)
            return False
        ty = (bounds(target)[1] + bounds(target)[3]) // 2
        fb = bounds(f)
        if fb[1] <= ty <= fb[3]:
            return True
        key("DPAD_DOWN" if ty > fb[3] else "DPAD_UP", wait=0.5)
    return False


def type_text(value):
    sh("input text " + "'" + value.replace("'", "") + "'")
    time.sleep(0.8)


def step(name, fn):
    try:
        fn()
    except Exception as e:  # die Tour läuft immer weiter, nur der Schritt fehlt dann
        print(f"[step] {name} fehlgeschlagen: {e}", flush=True)
    shot(name)


# ---------------------------------------------------------------- Einrichtung
def setup():
    shot("start")
    for pattern, name in [
        (r"^Los geht", "willkommen"),
        (r"^Weiter$", "anzeigegroesse"),
        (r"^Ich verstehe", "hinweis"),
        (r"^Neues Profil", "einrichtung"),
    ]:
        goto(pattern)
        shot(name)
    # Ohne Namen bleibt "Profil erstellen" gesperrt: Name-Zeile (erster Fokus) öffnen und tippen.
    # Der Name ist der erste Fokus; OK öffnet die Tastatur direkt in der Zeile.
    key("DPAD_CENTER", wait=1.5)
    shot("profil-tastatur")
    type_text("Jonathan")
    key("ENTER", wait=1.2)
    shot("profil-name")
    goto(r"^Profil erstellen")
    shot("profil")
    goto(r"^Hier eingeben")
    shot("quelle-formular")
    # Typ auf M3U stellen: Zeile "Typ" mit ◀ ▶
    if goto(r"^Typ$", press=False):
        key("DPAD_RIGHT")
    shot("quelle-m3u")
    # Die Eingabezeilen tauchen im UI-Baum nicht mit Text auf: von "Typ" aus zwei nach unten (Name, URL).
    key("DPAD_DOWN", n=2, wait=0.7)
    if True:
        key("DPAD_CENTER", wait=1.5)
        shot("tastatur")
        type_text("http://127.0.0.1:8080/playlist.m3u")
        key("ENTER", wait=1.0)
        shot("url-eingegeben")
        root = dump()
        if find(root, r"127\.0\.0\.1") is None:
            key("BACK", wait=1.0)
    root = shot("formular-fertig")
    # "Importieren starten" sitzt im Feld rechts und ist von jeder Zeile mit ▶ erreichbar.
    if not goto(r"^Importieren starten"):
        key("DPAD_RIGHT", wait=1.0)
        shot("import-knopf")
        if find(dump(), r"^Importieren starten") is not None:
            key("DPAD_CENTER", wait=1.0)
    for i in range(30):
        time.sleep(3)
        root = dump()
        if find(root, r"Jetzt synchronisieren|^Weiter$|Alles bereit|Fertig|fehlgeschlagen|failed"):
            break
    shot("import")
    # Die EPG-Abfrage hat den Fokus schon auf "Jetzt synchronisieren".
    if steps and steps[-1]["focus"].startswith("Jetzt synchronisieren"):
        key("DPAD_CENTER", wait=8)
        shot("epg-sync")
    # EPG-Abfrage, "Alles bereit" und Co. wegdrücken, bis die Startseite mit dem Menü steht.
    for _ in range(12):
        root = dump()
        print(f"[setup] Dump: {len(list(root.iter('node')))} Knoten, Fokus {focused_text(root)!r}", flush=True)
        if find(root, r"Jetzt synchronisieren"):
            goto(r"Jetzt synchronisieren")
            time.sleep(8)
            shot("epg-sync")
        elif find(root, r"^Fertig$"):
            goto(r"^Fertig$")
        elif find(root, r"^Weiter$"):
            shot("fertig")
            goto(r"^Weiter$")
        elif find(root, r"^(Live-TV|Startseite)$") is not None:
            break
        time.sleep(2)
    time.sleep(4)
    shot("app-start")


# ---------------------------------------------------------------- Menü und Bereiche
RAIL = re.compile(r"^(Suche|Live-TV|Programmübersicht|Sport|Filme|Serien|Downloads|Startseite|Mehr)$")
# Menüpunkte liegen ganz links; der Guide-Titel heißt ebenfalls „Live-TV“.
RAIL_XMAX = 160
SETTINGS_CARDS = [
    "Schnellzugriff", "Profil", "Quellen und Programm", "Darstellung", "Layout", "Inhalte und Metadaten",
    "Player", "Bild", "Ton und Untertitel", "Live-TV", "Wiedergabe und Aufnahme", "App",
]


def slug(text):
    return re.sub(r"[^\wäöüÄÖÜß-]+", "_", text)[:40]


def dismiss_exit(root):
    """Zurück auf dem Menü fragt "Mayo TV beenden?": immer Abbrechen."""
    if find(root, r"^Mayo TV beenden") is not None:
        goto(r"^Abbrechen$")
        return True
    return False


def to_rail():
    """Fokus ins linke Menü: ◀, und wo ◀ nichts mehr bewegt, Zurück."""
    for _ in range(10):
        root = dump()
        if dismiss_exit(root):
            continue
        before = focused_text(root)
        if RAIL.match(before or ""):
            return True
        key("DPAD_LEFT", wait=0.6)
        root = dump()
        if dismiss_exit(root):
            continue
        now = focused_text(root)
        if RAIL.match(now or ""):
            return True
        if now == before:
            key("BACK", wait=1.2)
    print("[rail] Menü nicht erreicht", flush=True)
    return False


def open_section(name):
    if not to_rail():
        return False
    ok = goto("^" + re.escape(name) + "$", xmax=RAIL_XMAX)
    time.sleep(2.5)
    return ok


def rail_tour():
    to_rail()
    shot("menue-offen")
    for name in ["Startseite", "Suche", "Live-TV", "Sport", "Filme", "Serien", "Downloads"]:
        if not open_section(name):
            continue
        shot(f"bereich-{slug(name)}")
        key("DPAD_RIGHT", wait=1.0)
        shot(f"bereich-{slug(name)}-inhalt")
        key("DPAD_DOWN", n=3, wait=0.5)
        shot(f"bereich-{slug(name)}-unten")


def more_tour():
    for item in ["Favoriten", "Wiedergabeverlauf", "Sicherung", "Lokale Synchronisierung", "Fehlerprotokoll", "Über"]:
        if not open_section("Mehr"):
            return
        if item == "Favoriten":
            shot("mehr")
        key("DPAD_RIGHT", wait=0.8)
        if goto("^" + re.escape(item)):
            time.sleep(1.5)
            shot(f"mehr-{slug(item)}")
            key("BACK", wait=1.2)


def guide_tour():
    # Live-TV ist der Guide (wie „TV“ bei TiviMate), eine eigene Programmübersicht gibt es nicht mehr.
    if not open_section("Live-TV"):
        return
    time.sleep(2)
    shot("guide")
    key("DPAD_RIGHT", n=2, wait=0.6)
    shot("guide-fokus")
    key("DPAD_DOWN", n=4, wait=0.5)
    key("DPAD_RIGHT", n=3, wait=0.5)
    shot("guide-weiter")
    key("PROG_GREEN", wait=1.5)
    shot("guide-gruen")
    key("BACK", wait=1.0)
    key("INFO", wait=1.5)
    shot("guide-info")
    key("BACK", wait=1.0)
    key("DPAD_CENTER", long=True, wait=1.5)
    shot("guide-ok-lang")
    key("BACK", wait=1.0)
    # Das Menü über dem Guide: die Seite rückt zur Seite und wird dunkler
    if to_rail() and goto(r"^Live-TV$", press=False, xmax=RAIL_XMAX):
        shot("guide-menue-offen")
        key("DPAD_RIGHT", wait=1.2)
        shot("guide-gruppen")
        key("DPAD_DOWN", n=2, wait=0.6)
        shot("guide-gruppen-runter")
        key("DPAD_RIGHT", wait=1.2)
        shot("guide-nach-gruppen")
        # Drei Ebenen: ◀ aus dem Guide zu den Kategorien, ◀ noch einmal zum Menü, Zurück eine Ebene zurück.
        key("DPAD_LEFT", wait=1.2)
        shot("guide-links-kategorien")
        key("DPAD_LEFT", wait=1.2)
        shot("guide-links-menue")
        key("DPAD_RIGHT", wait=1.2)
        shot("guide-menue-rechts-kategorien")
        key("DPAD_RIGHT", wait=1.2)
        key("BACK", wait=1.2)
        shot("guide-zurueck-kategorien")
        key("DPAD_RIGHT", wait=1.2)


def in_fullscreen():
    """Vollbild ohne Einblendung: der Fokus liegt auf der bildschirmfüllenden Fangfläche des Players.
    (Der Guide bleibt hinter dem Vollbild aufgebaut, seine Texte stehen also auch im UI-Baum.)"""
    f = focused(dump())
    if f is None:
        return False
    b = bounds(f)
    return b[2] - b[0] >= 1600 and b[3] - b[1] >= 900


def start_fullscreen(row, group=None):
    """Von überall frisch in den Vollbild-Player: Menü › Live-TV › (Gruppe) › Senderzeile › OK, OK."""
    if not open_section("Live-TV"):
        return False
    key("DPAD_RIGHT", wait=1.0)
    if group:
        goto(group, press=False, xmax=RAIL_XMAX + 400)
        time.sleep(1.5)
    key("DPAD_RIGHT", wait=1.0)
    if not goto_row(row):
        return False
    # Erstes OK spielt in der Vorschau, das zweite geht ins Vollbild.
    for _ in range(2):
        key("DPAD_CENTER", wait=3)
        if in_fullscreen():
            return True
    return in_fullscreen()


def player_tour():
    if not open_section("Live-TV"):
        return
    shot("live")
    # Live-TV ist immer der Guide: ▶ führt vom Menü in die Gruppen, ein zweites ▶ in die Senderzeilen.
    # Erst dort wird der Sender per Namen gesucht; in der Gruppenspalte wechselte ▼ sonst die Gruppe.
    key("DPAD_RIGHT", wait=1.0)
    key("DPAD_RIGHT", wait=1.0)
    shot("live-guide")
    if not goto_row(r"^Sky Sport Bundesliga 1"):
        return
    key("DPAD_CENTER", wait=1.0)
    time.sleep(4)
    shot("live-vorschau")
    if not in_fullscreen():
        key("DPAD_CENTER", wait=4)
    shot("player")
    # Zurück: das Bild gleitet in die Vorschau-Ecke des Guides und läuft dort weiter; OK holt es zurück.
    key("BACK", wait=0.15)
    shot("player-zurueck-gleitet")
    time.sleep(2)
    shot("player-zurueck-im-guide")
    key("DPAD_CENTER", wait=4)
    shot("player-wieder-vollbild")

    # Zappen: ▲▼ und die CH-Tasten, einzeln und als schnelle Folge (wie eine gehaltene Taste).
    # Das Foto direkt nach dem Druck zeigt, ob die Infoleiste schon den neuen Sender nennt.
    if True:
        key("DPAD_UP", wait=0.3)
        shot("player-hoch-zappen-sofort")
        time.sleep(2)
        shot("player-hoch-zappen")
        key("DPAD_DOWN", wait=2)
        shot("player-runter-zappen")
        key("CHANNEL_UP", wait=0.3)
        shot("player-ch-plus-sofort")
        time.sleep(2)
        shot("player-ch-plus")
        key("CHANNEL_DOWN", wait=2)
        shot("player-ch-minus")
        key("CHANNEL_UP", n=4, wait=0.2)
        shot("player-ch-schnell-zappen")
        time.sleep(3)
        shot("player-ch-schnell-angekommen")
        time.sleep(3)
        key("DPAD_RIGHT", wait=1.5)
        shot("player-rechts-letzter-sender")
        time.sleep(3)
        key("DPAD_LEFT", wait=1.5)
        shot("player-links-senderliste")
        key("DPAD_LEFT", wait=1.5)
        shot("player-links-gruppen")
        key("BACK", wait=1.0)
        key("BACK", wait=1.0)
        shot("player-nach-liste")

    # OK: Infoleiste mit Kacheln; ▼ öffnet die Einstellungszeile; daraus Ton, Untertitel und Format.
    for name in ("Ton", "Untertitel", "Format"):
        if not in_fullscreen() and not start_fullscreen(r"^Sky Sport Bundesliga 1"):
            break
        key("DPAD_CENTER", wait=1.5)
        if name == "Ton":
            shot("player-ok-infoleiste")
        key("DPAD_DOWN", wait=1.2)
        if name == "Ton":
            shot("player-ok-einstellungszeile")
        if goto(rf"^{name}"):
            time.sleep(1.2)
            shot(f"player-dialog-{name.lower()}")
            key("BACK", wait=1.5)
        if not in_fullscreen():
            key("BACK", wait=1.5)

    # Die übrigen Tasten der Fernbedienung, jede aus dem freien Vollbild.
    for code, name in [
        ("DPAD_CENTER", "ok-lang-optionen"), ("MENU", "menue"), ("MENU", "menue-lang-epg"),
        ("PROG_RED", "rot"), ("PROG_BLUE", "blau"), ("INFO", "info"),
    ]:
        if not in_fullscreen() and not start_fullscreen(r"^Sky Sport Bundesliga 1"):
            break
        key(code, long=name.endswith(("lang-optionen", "lang-epg")), wait=1.8)
        shot(f"player-{name}")
        if name == "ok-lang-optionen":
            key("DPAD_DOWN", n=3, wait=0.5)
            shot("player-optionen-unten")
        key("BACK", wait=1.5)
        if dismiss_exit(dump()):
            break

    # Ein Sender ohne Signal: Fehlerbild mit „Erneut versuchen“, ▲▼ zappen weiter weg.
    # Erst zurück in den Guide (◀ öffnete im Vollbild die Senderliste statt des Menüs).
    for _ in range(3):
        if in_fullscreen():
            key("BACK", wait=2)
            break
        key("BACK", wait=1.5)
    to_rail()
    # Auch ohne Vollbild-Bestätigung weiter: das Fehlerbild hält den Fokus auf „Erneut versuchen“.
    start_fullscreen(r"^Kein Signal", group=r"^Dokumentationen")
    if True:
        time.sleep(1)
        shot("player-fehler-laedt")
        time.sleep(12)
        shot("player-fehler")
        key("DPAD_DOWN", wait=0.4)
        shot("player-fehler-weggezappt-sofort")
        time.sleep(3)
        shot("player-fehler-weggezappt")
        key("BACK", wait=1.5)
    shot("player-zurueck")

def vod_section(name, layout):
    """Filme oder Serien: Raster, Hero, Kategorien und die Detailseite eines Titels."""
    if not open_section(name):
        return
    time.sleep(4)  # Metadaten (TMDB) für den ersten Titel nachladen lassen
    tag = f"{slug(name)}-{layout}"
    shot(f"vod-{tag}")
    key("DPAD_RIGHT", wait=1.0)
    time.sleep(3)
    shot(f"vod-{tag}-titel")
    key("DPAD_RIGHT", n=2, wait=0.8)
    time.sleep(3)
    shot(f"vod-{tag}-titel-3")
    key("DPAD_DOWN", wait=1.0)
    time.sleep(2)
    shot(f"vod-{tag}-zeile-2")
    key("DPAD_UP", wait=0.8)
    key("DPAD_LEFT", n=6, wait=0.6)
    shot(f"vod-{tag}-links")
    key("DPAD_DOWN", n=3, wait=0.8)
    time.sleep(2)
    shot(f"vod-{tag}-kategorie")
    # OK auf einem Film spielt ihn ab (auch ein langes OK über adb); eine Serie öffnet ihre Seite.
    if name == "Serien":
        key("DPAD_RIGHT", wait=1.0)
        key("DPAD_CENTER", wait=3.0)
        time.sleep(3)
        shot(f"vod-{tag}-serie")
        key("DPAD_DOWN", n=2, wait=0.8)
        shot(f"vod-{tag}-serie-unten")
        key("BACK", wait=1.5)


def vod_details():
    """Filme: OK halten auf einem Poster öffnet die Optionen, darin die TMDB-Detailseite."""
    if not open_section("Filme"):
        return
    time.sleep(3)
    # ▶ erreicht die Kategorien-Spalte, zweimal ▶ das zweite Poster (wie in vod_section).
    key("DPAD_RIGHT", wait=1.0)
    key("DPAD_RIGHT", n=2, wait=0.8)
    # Ein langes OK über adb spielt den Film ab; die Menü-Taste öffnet die Optionen wie OK halten.
    key("MENU", wait=1.5)
    shot("vod-filme-optionen")
    if goto(r"details", tries=12):
        time.sleep(4)
        shot("vod-filme-details")
        key("DPAD_DOWN", n=2, wait=0.8)
        shot("vod-filme-details-unten")
        key("BACK", wait=1.5)
    if in_fullscreen():
        key("BACK", n=2, wait=1.5)


def search_tour():
    """Suche: ein Titel, ein Schauspieler (TMDB) und eine Beschreibung (Jev), jeweils Liste und erste Zeile."""
    if not open_section("Suche"):
        return
    # Die Suche setzt den Fokus selbst ins Feld; ▶ wäre schon das Mikrofon.
    time.sleep(1.0)
    if RAIL.match(focused_text(dump()) or ""):
        key("DPAD_RIGHT", wait=1.0)
    shot("suche-leer")
    for q, tag in [("top gun", "titel"), ("Tom Cruise", "schauspieler"), ("gruseliger Vampirfilm", "beschreibung")]:
        key("DPAD_CENTER", wait=1.5)
        type_text(q.replace(" ", "%s"))
        key("ENTER", wait=1.0)
        time.sleep(12)  # TMDB und Jev antworten nicht sofort
        shot(f"suche-{tag}")
        key("DPAD_DOWN", n=2, wait=0.8)
        time.sleep(3)
        shot(f"suche-{tag}-zeile")
        # Zurück leert die Suche und setzt den Fokus wieder ins Feld.
        key("BACK", wait=1.5)


def vod_tour():
    # Filme und Serien haben nur noch das filmische Layout.
    vod_section("Filme", "filmisch")
    vod_section("Serien", "filmisch")
    vod_details()
    search_tour()


def settings_tour():
    if not open_section("Mehr"):
        return
    key("DPAD_RIGHT", wait=0.8)
    if not goto(r"^Einstellungen$"):
        return
    time.sleep(2)
    shot("einstellungen")
    # Von der Leiste aus: ▶ landet in der Suchzeile, ▼ auf der ersten Kachel
    key("DPAD_RIGHT", wait=0.8)
    key("DPAD_DOWN", wait=0.8)
    shot("einstellungen-raster")
    for card in SETTINGS_CARDS:
        if not goto("^" + re.escape(card) + "$", xmin=600):
            continue
        time.sleep(1.5)
        shot(f"einst-{slug(card)}")
        key("DPAD_DOWN", n=6, wait=0.4)
        shot(f"einst-{slug(card)}-mitte")
        key("DPAD_DOWN", n=10, wait=0.4)
        shot(f"einst-{slug(card)}-ende")
        # Die Tastenbelegung ist die letzte Zeile von Layout
        if card == "Layout" and goto(r"^Fernbedienungs-Kurzbefehle"):
            time.sleep(1.5)
            shot("einst-tasten")
            key("DPAD_DOWN", wait=0.6)
            shot("einst-tasten-zeile")
            key("DPAD_CENTER", wait=1.5)
            shot("einst-tasten-auswahl")
            key("BACK", wait=1.0)
            key("DPAD_DOWN", n=12, wait=0.4)
            shot("einst-tasten-unten")
            key("BACK", wait=1.5)
        key("BACK", wait=1.5)
        root = dump()
        if dismiss_exit(root) or find(root, r"^Alle Einstellungen durchsuchen") is None:
            key("BACK", wait=1.5)


SCENES = ["Basalt", "Taupe", "Salbei", "Pflaume", "Neon", "Abendrot", "Flutlicht", "Gletscher"]


def open_scene_row():
    """Mehr › Einstellungen › Darstellung › Glas & Hintergrund, Fokus auf die Zeile „Farbthema“."""
    if not open_section("Mehr"):
        return False
    key("DPAD_RIGHT", wait=0.8)
    if not goto(r"^Einstellungen$"):
        return False
    time.sleep(2)
    key("DPAD_RIGHT", wait=0.8)
    key("DPAD_DOWN", wait=0.8)
    if not goto(r"^Darstellung$", xmin=600):
        return False
    time.sleep(1.5)
    if not goto(r"^Glas & Hintergrund$"):
        return False
    time.sleep(1.5)
    return goto(r"^Farbthema$", press=False)


def scenes_tour():
    """Jedes Farbthema einstellen (Vorschau im Feld rechts), dann Startseite und Live-TV davor."""
    for name in SCENES:
        if not open_scene_row():
            shot(f"szene-{slug(name)}-zeile-fehlt")
            return
        for _ in range(20):
            if find(dump(), "^" + re.escape(name) + "$") is not None:
                break
            key("DPAD_RIGHT", wait=1.0)
        time.sleep(2)
        shot(f"szene-{slug(name)}-einstellung")
        # Erst mit Zurück raus: ◀ auf der Szenen-Zeile würde die Szene wieder umstellen.
        key("BACK", wait=1.5)
        key("BACK", wait=1.5)
        key("BACK", wait=1.5)
        for section in ["Startseite", "Live-TV"]:
            if open_section(section):
                time.sleep(2)
                shot(f"szene-{slug(name)}-{slug(section)}")
        key("DPAD_RIGHT", wait=1.5)
        shot(f"szene-{slug(name)}-live-liste")


def probe(name, *keys_, wait=1.2):
    """Tasten drücken und festhalten, wo der Fokus danach steht (leer = Fokus verloren)."""
    for k in keys_:
        key(k.removesuffix("_LONG"), wait=wait, long=k.endswith("_LONG"))
    root = shot(f"nav-{name}")
    f = focused_text(root)
    with open(os.path.join(OUT, "nav.txt"), "a", encoding="utf-8") as nf:
        nf.write(f"{name:40s} fokus={f!r}{'   <-- FOKUS WEG' if not f and focused(root) is None else ''}\n")
    return root


def nav_tour():
    """Fernbedienung: jeder Bereich rein, runter, Zurück, Zurück, und wohin der Fokus dabei geht."""
    for name in ["Startseite", "Suche", "Live-TV", "Sport", "Filme", "Serien", "Downloads", "Mehr"]:
        if not open_section(name):
            continue
        s = slug(name)
        probe(f"{s}-ok-auf-menue")
        probe(f"{s}-rechts", "DPAD_RIGHT")
        probe(f"{s}-runter", "DPAD_DOWN", "DPAD_DOWN")
        probe(f"{s}-rechts2", "DPAD_RIGHT")
        probe(f"{s}-zurueck1", "BACK", wait=1.5)
        probe(f"{s}-zurueck2", "BACK", wait=1.5)
        root = probe(f"{s}-zurueck3", "BACK", wait=1.5)
        dismiss_exit(root)
    # Ein Titel öffnen und zurück: der Fokus soll auf dem Titel stehen. ▶ ▶: über die Kategorien zu den Titeln.
    for name in ["Filme", "Serien", "Sport"]:
        if not open_section(name):
            continue
        s = slug(name)
        key("DPAD_RIGHT", n=1 if name == "Sport" else 2, wait=1.5)
        probe(f"{s}-titel-vorher")
        probe(f"{s}-titel-ok", "DPAD_CENTER", wait=6)
        probe(f"{s}-titel-zurueck", "BACK", wait=3)
        probe(f"{s}-titel-zurueck2", "BACK", wait=2)
        dismiss_exit(dump())
    # Mehr: ▶ auf einer Zeile öffnet deren Seite.
    if open_section("Mehr"):
        key("DPAD_RIGHT", wait=0.8)
        if goto(r"^Favoriten", press=False):
            probe("mehr-favoriten-rechts", "DPAD_RIGHT")
            probe("mehr-favoriten-zurueck", "BACK")
        dismiss_exit(dump())
    # Einstellungen: Kachel auf, Zurück auf die Kachel, Zurück nach Mehr.
    if open_section("Mehr"):
        key("DPAD_RIGHT", wait=0.8)
        if goto(r"^Einstellungen$"):
            time.sleep(2)
            probe("einst-geoeffnet")
            key("DPAD_RIGHT", wait=0.8)
            key("DPAD_DOWN", wait=0.8)
            probe("einst-kachel")
            probe("einst-kachel-ok", "DPAD_CENTER", wait=2)
            probe("einst-kachel-runter", "DPAD_DOWN", "DPAD_DOWN")
            probe("einst-kachel-zurueck", "BACK", wait=2)
            probe("einst-zurueck-mehr", "BACK", wait=2)
            probe("einst-zurueck-menue", "BACK", wait=2)
            dismiss_exit(dump())
    # Suche: Text, Ergebnis öffnen, Zurück in die Suche.
    if open_section("Suche"):
        probe("suche-feld")
        key("DPAD_CENTER", wait=1.5)  # OK öffnet die Tastatur
        type_text("Sky")
        probe("suche-getippt", "BACK", wait=3)  # Tastatur zu, Fokus auf dem Feld
        probe("suche-runter", "DPAD_DOWN", "DPAD_DOWN")
        probe("suche-ergebnis-ok", "DPAD_CENTER", wait=4)
        probe("suche-ergebnis-zurueck", "BACK", wait=3)
        probe("suche-ergebnis-zurueck2", "BACK", wait=2)
        probe("suche-ergebnis-zurueck3", "BACK", wait=2)
        dismiss_exit(dump())
    # Player: Vollbild aus dem Guide, Zurück, ◀ ◀.
    if open_section("Live-TV"):
        key("DPAD_RIGHT", n=2, wait=1.2)
        probe("player-guide-zeile")
        key("DPAD_CENTER", wait=4)
        if not in_fullscreen():
            key("DPAD_CENTER", wait=5)
        probe("player-vollbild")
        probe("player-zurueck-guide", "BACK", wait=3)
        probe("player-guide-links", "DPAD_LEFT")
        probe("player-guide-links2", "DPAD_LEFT")
        probe("player-guide-rechts", "DPAD_RIGHT")
        probe("player-guide-rechts2", "DPAD_RIGHT")
    if open_section("Startseite"):
        probe("lang-zurueck", "BACK_LONG", wait=5)
        probe("lang-zurueck-dann-zurueck", "BACK", wait=3)
        probe("lang-zurueck-zurueck2", "BACK", wait=2)
        dismiss_exit(dump())
    # Sport-Spiel: Sender abspielen und zurück auf die Spielseite.
    if open_section("Sport"):
        key("DPAD_RIGHT", wait=1.5)
        probe("sport-spiel-ok", "DPAD_CENTER", wait=3)
        probe("sport-spiel-sender-ok", "DPAD_CENTER", wait=5)
        probe("sport-spiel-sender-ok2", "DPAD_CENTER", wait=5)
        probe("sport-spiel-zurueck", "BACK", wait=3)
        probe("sport-spiel-zurueck2", "BACK", wait=2)
        probe("sport-spiel-zurueck3", "BACK", wait=2)
        dismiss_exit(dump())


def record(name, *keys_, seconds=5, wait=0.4):
    """Bildschirmvideo um Tastendrücke herum. Ein Flackern von ein, zwei Frames zeigt kein Foto; der
    Workflow zerlegt die Videos in Kontaktbögen (jedes geänderte Bild einmal, zeilenweise in Reihenfolge)."""
    remote = f"/sdcard/rec-{slug(name)}.mp4"
    sh(f"rm -f {remote}")
    rec = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "960x540", "--bit-rate", "6000000",
                            "--time-limit", str(seconds), remote])
    time.sleep(1.2)  # screenrecord braucht einen Moment, bis das erste Bild läuft
    for k in keys_:
        if k == "HOLD_OK":
            hold()
            time.sleep(wait)
        else:
            key(k.removesuffix("_LONG"), wait=wait, long=k.endswith("_LONG"))
    try:
        rec.wait(timeout=seconds + 20)
    except subprocess.TimeoutExpired:
        rec.kill()
    time.sleep(1.5)
    counter[0] += 1
    adb("pull", remote, os.path.join(OUT, f"{counter[0]:03d}-rec-{slug(name)}.mp4"), timeout=120)
    return probe(f"rec-{name}")


def open_settings_card(card):
    """Mehr › Einstellungen › [card]: die Seite der Gruppe, Fokus auf ihrer ersten Zeile."""
    if not open_section("Mehr"):
        return False
    key("DPAD_RIGHT", wait=0.8)
    if not goto(r"^Einstellungen$"):
        return False
    time.sleep(2)
    key("DPAD_RIGHT", wait=0.8)
    key("DPAD_DOWN", wait=0.8)
    if not goto("^" + re.escape(card) + "$", xmin=600):
        return False
    time.sleep(2)
    return True


def longpress_tour():
    """OK gedrückt halten auf einem Titel: das Menü muss offen bleiben, wenn OK losgelassen wird (es schloss
    sich wieder und schaltete dabei seine erste Zeile, Favoriten, um). Danach Zurück: das Menü geht zu."""
    for section in ["Filme", "Serien"]:
        for i in range(3):
            if not open_section(section):
                break
            key("DPAD_RIGHT", n=2, wait=1.5)
            s = slug(section)
            probe(f"{s}-titel-{i}")
            record(f"{s}-lang-ok-{i}", "HOLD_OK", seconds=6, wait=1.5)
            probe(f"{s}-lang-ok-zurueck-{i}", "BACK")
    # Ein frischer Druck im Menü muss sofort wirken (kein „zweimal drücken“): OK auf „Zu Favoriten hinzufügen“
    # schließt das Menü; beim nächsten Halten heißt die Zeile „Aus Favoriten entfernen“. Dann zurückstellen.
    if open_section("Filme"):
        key("DPAD_RIGHT", n=2, wait=1.5)
        record("Filme-halten-dann-ok", "HOLD_OK", seconds=4, wait=1.5)
        probe("Filme-frisches-ok", "DPAD_CENTER", wait=1.5)
        record("Filme-halten-erneut", "HOLD_OK", seconds=4, wait=1.5)
        probe("Filme-frisches-ok-zurueck", "DPAD_CENTER", wait=1.5)
    # Im Guide: lang OK auf einem Sender öffnet sein Menü.
    if open_section("Live-TV"):
        key("DPAD_RIGHT", n=2, wait=1.5)
        record("guide-lang-ok", "HOLD_OK", seconds=6, wait=1.5)
        probe("guide-lang-ok-zurueck", "BACK")


def flicker_tour():
    """Die Wege zurück aus den Einstellungen, gefilmt, mit Animationen wie auf dem Fernseher (Skala 1)."""
    for k in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        sh(f"settings put global {k} 1")
    time.sleep(1)
    try:
        for i in range(3):
            # Einstellungsseite › Zurück › Mehr › Zurück › Menü
            if open_settings_card("Profil"):
                record(f"einst-zurueck-mehr-{i}", "BACK")
                record(f"mehr-zurueck-menue-{i}", "BACK")
                dismiss_exit(dump())
            # Unterseite (Fernbedienungs-Kurzbefehle) › Zurück › Einstellungsseite › Zurück › Mehr
            if open_settings_card("Layout") and goto(r"^Fernbedienungs-Kurzbefehle"):
                time.sleep(2)
                record(f"unterseite-zurueck-{i}", "BACK")
                record(f"layout-zurueck-mehr-{i}", "BACK")
            # Einstellungsseite › ◀ ins Menü › Live-TV (der Guide mit seinen Gruppen neben dem Menü)
            if open_settings_card("Profil"):
                record(f"einst-links-menue-{i}", "DPAD_LEFT", "DPAD_LEFT")
                if goto(r"^Live-TV$", press=False, xmax=RAIL_XMAX):
                    record(f"menue-ok-livetv-{i}", "DPAD_CENTER", seconds=6)
                    record(f"livetv-rechts-gruppen-{i}", "DPAD_RIGHT")
            # Mehr › Zurück › Menü › Filme
            if open_section("Mehr"):
                record(f"mehr-zurueck-{i}", "BACK")
                dismiss_exit(dump())
                if goto(r"^Filme$", press=False, xmax=RAIL_XMAX):
                    record(f"menue-ok-filme-{i}", "DPAD_CENTER", seconds=6)
    finally:
        for k in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
            sh(f"settings put global {k} 0")


def to_match_card(downs=8):
    """Fokus auf die erste Spielkarte (hohe Karte, keine Chip- oder Senderzeile) im Sport-Bereich."""
    for _ in range(downs):
        root = dump()
        f = focused(root)
        if f is not None:
            b = bounds(f)
            if b[3] - b[1] >= 150 and b[2] - b[0] >= 250 and b[0] >= 100:
                return True
        key("DPAD_DOWN", wait=0.8)
    return False


def sports_tour():
    if not open_section("Sport"):
        return
    time.sleep(4)
    shot("sport-hub")
    key("DPAD_RIGHT", wait=1.5)
    shot("sport-hub-fokus")
    if not to_match_card():
        shot("sport-keine-karte")
        return
    shot("sport-karte")
    key("DPAD_CENTER", wait=4)
    shot("sport-spielseite")
    for tab in ["Tabelle", "Form", "Aufstellungen", "Statistik"]:
        if goto("^" + tab + "$", tries=10):
            time.sleep(1.5)
            shot(f"sport-spielseite-{slug(tab)}")
    if goto(r"^Sender suchen$", tries=12):
        time.sleep(20)
        shot("sport-sender")
        key("DPAD_CENTER", wait=6)
        shot("sport-vorschau")
        key("DPAD_CENTER", wait=6)
        shot("sport-vollbild")
        key("BACK", wait=4)
        shot("sport-zurueck-spielseite")
    else:
        shot("sport-sender-nicht-suchbar")
    key("BACK", wait=2.5)
    shot("sport-zurueck-hub")
    key("DPAD_DOWN", n=3, wait=0.8)
    shot("sport-hub-unten")
    # Launcher-Kachel „Für dich“: kalter Start direkt auf eine Spielseite.
    out = sh("content query --uri content://android.media.tv/preview_program --projection intent_uri 2>&1", timeout=30)
    m = re.search(r"tvplayer_match=([^\s,;#]+(?:%23|#)\d+)", out)
    print(f"[sport] Launcher-Programme: {out[:300]!r}", flush=True)
    if m:
        uri = "owntv://open?tvplayer_match=" + m.group(1).replace("#", "%23")
        sh(f"am force-stop {PKG}")
        time.sleep(2)
        sh(f"am start -a android.intent.action.VIEW -d '{uri}' {PKG}")
        time.sleep(12)
        shot("sport-launcher-kachel-kaltstart")


def main():
    step("einrichtung", setup)
    # Ohne fertige Einrichtung würden die weiteren Schritte nur im Assistenten herumdrücken.
    if find(dump(), r"Los geht|Wer schaut gerade|Angenehm lesbar|Mayo TV einrichten|Playlist hinzufügen|Hier eingeben|Importieren starten") is not None:
        print("[tour] Einrichtung nicht abgeschlossen, Tour endet hier", flush=True)
        return
    # TOUR_PARTS=vod,scenes,player,… läuft nur durch diese Teile (schneller beim Arbeiten an einem Bereich).
    parts = [p for p in os.environ.get("TOUR_PARTS", "all").replace(" ", "").split(",") if p]
    tour = [
        ("menu", "menue", rail_tour), ("vod", "filme-serien", vod_tour), ("more", "mehr-tour", more_tour),
        ("guide", "guide", guide_tour), ("player", "player", player_tour),
        ("settings", "einstellungen-tour", settings_tour), ("scenes", "szenen", scenes_tour),
        ("nav", "navigation", nav_tour), ("sports", "sport", sports_tour),
    ]
    # Nur auf Anfrage (TOUR_PARTS=flicker, longpress): Videos statt Fotos, für kurze Sprünge.
    tour += [(p, n, f) for p, n, f in [("flicker", "flackern", flicker_tour), ("longpress", "lang-ok", longpress_tour)] if p in parts]
    for part, name, fn in tour:
        if "all" in parts or part in parts:
            step(name, fn)
    with open(os.path.join(OUT, "steps.json"), "w", encoding="utf-8") as f:
        json.dump(steps, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
