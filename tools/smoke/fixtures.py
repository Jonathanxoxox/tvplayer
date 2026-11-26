#!/usr/bin/env python3
"""Testdaten für die Bildschirm-Tour: Playlist und TV-Guide rund um die aktuelle Uhrzeit.

Die Sender zeigen alle auf den lokalen Teststream (stream/index.m3u8), den die Tour per ffmpeg
erzeugt. Lange deutsche Namen sind Absicht: sie zeigen, wo Text abgeschnitten wird.
"""
import datetime as dt
import pathlib
import sys

out = pathlib.Path(sys.argv[1])
base = "http://127.0.0.1:8080"
groups = {
    "Fußball": [
        "Sky Sport Bundesliga 1 HD", "Sky Sport Bundesliga 2 HD", "DAZN 1 HD", "DAZN 2 HD",
        "Sky Sport Top Event UHD", "Sportschau Live", "TV 2 Sport (DK)",
    ],
    "Deutschland": [
        "Das Erste HD", "ZDF HD", "RTL HD", "SAT.1 HD", "ProSieben HD", "VOX HD", "kabel eins HD",
        "NDR Fernsehen Hamburg HD", "WDR Fernsehen Köln HD", "Bayerisches Fernsehen Süd HD",
    ],
    "Nachrichten": ["tagesschau24 HD", "n-tv HD", "WELT HD", "phoenix HD"],
    "Dokumentationen und Wissen extra langer Gruppenname": ["arte HD", "3sat HD", "ZDFinfo HD", "Discovery Channel HD", "Kein Signal (Test)"],
}
shows = {
    "Fußball": [
        ("Bundesliga: Hamburger SV – Borussia Dortmund", "Live aus dem Volksparkstadion. Vorberichte, Spiel und Analyse."),
        ("UEFA Champions League: FC Liverpool – FC Barcelona", "Das Spitzenspiel der Gruppenphase in voller Länge."),
        ("Bundesliga – Die Konferenz am Samstagnachmittag", "Alle Spiele, alle Tore."),
    ],
    "default": [
        ("Tagesschau", "Nachrichten des Tages."),
        ("Ein ausgesprochen langer Sendungstitel, der in keine Zelle passt – Teil 2", "Beschreibung mit vielen Wörtern, damit der Text umbricht."),
        ("Tatort: Der Fall Flutlicht", "Krimi, Deutschland 2026."),
    ],
}
lines = [f'#EXTM3U url-tvg="{base}/epg.xml"']
programmes = []
now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0)
n = 0
for group, names in groups.items():
    for name in names:
        n += 1
        cid = f"ch{n}"
        lines.append(f'#EXTINF:-1 tvg-id="{cid}" tvg-name="{name}" group-title="{group}",{name}')
        # Ein toter Sender für das Fehlerbild des Players.
        lines.append(f"{base}/stream/{'fehlt' if name.startswith('Kein Signal') else 'index'}.m3u8")
        pool = shows["Fußball" if group == "Fußball" else "default"]
        t = now - dt.timedelta(hours=3)
        for i in range(16):
            title, desc = pool[(i + n) % len(pool)]
            end = t + dt.timedelta(minutes=45 if i % 2 else 90)
            programmes.append(
                f'<programme start="{t:%Y%m%d%H%M%S} +0000" stop="{end:%Y%m%d%H%M%S} +0000" channel="{cid}">'
                f"<title>{title}</title><desc>{desc}</desc></programme>"
            )
            t = end
# Filme und Serien (type="movie"/"series"), damit Filme/Serien in der Tour nicht leer sind. Echte Titel,
# damit die Metadaten (TMDB) Poster, Hintergrund und Besetzung finden; die eigenen Poster sind nur Ersatz.
movies = {
    "Filme | Neu": [
        "Gladiator II (2024)", "Wicked (2024)", "Dune: Part Two (2024)", "Alles steht Kopf 2 (2024)",
        "Deadpool & Wolverine (2024)", "Oppenheimer (2023)", "Barbie (2023)", "Top Gun: Maverick (2022)",
    ],
    "Filme | Deutsch": [
        "Lola rennt (1998)", "Das Boot (1981)", "Der Schuh des Manitu (2001)", "Good Bye, Lenin! (2003)",
        "Im Westen nichts Neues (2022)", "Das Leben der Anderen (2006)",
    ],
    "Filme | Horror": ["Wolfman (2010)", "Nosferatu (2024)", "Smile 2 (2024)"],
}
series = {
    "Serien | Neu": [("Nobody Wants This (2024)", 1, 6), ("The Bear (2022)", 2, 5), ("Shōgun (2024)", 1, 5)],
    "Serien | Deutsch": [("Dark (2017)", 3, 6), ("Babylon Berlin (2017)", 2, 4), ("4 Blocks (2017)", 1, 6)],
    "Serien | Kinder": [("Bluey (2018)", 2, 6), ("Peppa Wutz (2004)", 1, 6)],
}
posters = []
for group, titles in movies.items():
    for title in titles:
        logo = f"{base}/poster/m{len(posters)}.png"
        posters.append(title)
        lines.append(f'#EXTINF:-1 type="movie" tvg-logo="{logo}" group-title="{group}",{title}')
        lines.append(f"{base}/stream/index.m3u8")
for group, shows_ in series.items():
    for show, seasons, episodes in shows_:
        logo = f"{base}/poster/m{len(posters)}.png"
        posters.append(show)
        for s_ in range(1, seasons + 1):
            for e_ in range(1, episodes + 1):
                lines.append(f'#EXTINF:-1 type="series" tvg-logo="{logo}" group-title="{group}",{show} S{s_:02d}E{e_:02d}')
                lines.append(f"{base}/stream/index.m3u8")
try:  # Ersatzposter; ohne Pillow bleiben die Kacheln eben ohne Bild
    from PIL import Image, ImageDraw

    (out / "poster").mkdir(parents=True, exist_ok=True)
    for i, title in enumerate(posters):
        im = Image.new("RGB", (400, 600))
        d = ImageDraw.Draw(im)
        for y in range(600):
            d.line([(0, y), (400, y)], fill=(20 + (i * 37) % 120, 40 + y // 8, 80 + (i * 53) % 150))
        d.multiline_text((24, 420), title.replace(" (", "\n("), fill="white", spacing=8)
        im.save(out / "poster" / f"m{i}.png")
except ImportError:
    pass
out.mkdir(parents=True, exist_ok=True)
(out / "playlist.m3u").write_text("\n".join(lines) + "\n", encoding="utf-8")
channels = "".join(f'<channel id="ch{i}"><display-name>ch{i}</display-name></channel>' for i in range(1, n + 1))
(out / "epg.xml").write_text(
    '<?xml version="1.0" encoding="UTF-8"?><tv>' + channels + "".join(programmes) + "</tv>\n", encoding="utf-8"
)
print(f"{n} Sender, {len(programmes)} Sendungen, {len(posters)} Filme und Serien")
