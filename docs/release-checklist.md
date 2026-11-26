# Checkliste vor der Veröffentlichung

Was vor dem öffentlichen Start nur du selbst erledigen kannst: Konten, Secrets, Releases, Rechtliches
und der Test auf echter Hardware. Die Punkte sind nach Dringlichkeit geordnet. Abhaken, wenn erledigt.

## 1. Die drei eingebauten Schlüssel austauschen (dringend)

Bis zu diesem Umbau hat die CI die Secrets `TMDB_API_KEY`, `TYPESAFE_API_KEY` und `ELEVENLABS_API_KEY`
in jede APK gebaut (als `BuildConfig`-Felder). Jeder, der eine alte APK herunterlädt, kann sie mit
frei verfügbaren Werkzeugen in wenigen Minuten auslesen. Stichprobe vom 26.11.2026: Die aktuelle
`latest/MayoTV.apk` enthält im Code eine Zeichenkette im Format eines ElevenLabs-Schlüssels (`sk_…`) und
mehrere 32-stellige Hex-Zeichenketten (Format eines TMDB-Schlüssels). Die Werte stehen absichtlich
nicht hier.

Das Löschen alter Releases reicht nicht: Wer eine APK schon hat, behält die Schlüssel. Nur Widerrufen
schützt wirklich.

- [ ] **ElevenLabs**: im Konto unter API Keys den alten Schlüssel löschen. Vorher in der Nutzungsübersicht
      nachsehen, ob fremde Nutzung aufgelaufen ist, und ein Ausgabenlimit prüfen.
- [ ] **TypeSafe**: im Konto auf typesafe.ai den alten Schlüssel widerrufen und die Nutzung prüfen.
- [ ] **TMDB**: auf themoviedb.org unter Einstellungen › API den Schlüssel neu erzeugen (der alte wird
      damit ungültig).
- [ ] Neue Schlüssel nur noch für dich selbst verwenden (in der App unter Einstellungen › System ›
      Eigene Schlüssel), nie wieder als Secret in die CI.
- [ ] **Alte Releases aufräumen**: die APK-Anhänge aller Releases, die vor dem Umbau gebaut wurden
      (`v1.0.*` bis einschließlich des letzten Laufs mit den Secrets, dazu `latest` mit `MayoTV.apk` und
      `Flutlicht.apk`), löschen oder die Releases ganz entfernen. Der Updater in der App sucht das
      neueste `v1.0.<Lauf>`-Release; es muss danach ein Release ohne Schlüssel als neuestes dastehen,
      sonst bietet der Updater nichts oder eine alte APK an.
- [ ] **Bestehende Installationen**: Ältere Versionen haben den eingebauten TMDB-Schlüssel beim ersten
      Start in ihre Einstellungen kopiert. Nach dem Widerruf fehlen dort Poster und Filminfos, bis die
      Nutzer einen eigenen Schlüssel eintragen. Im Release-Text darauf hinweisen (Link auf
      [docs/api-keys.md](api-keys.md)). Sprachsuche und Suche nach Beschreibung lasen ihre Schlüssel
      direkt aus der APK; mit dem Update sind sie dort einfach aus.

## 2. GitHub-Secrets löschen

Erst, wenn kein Workflow sie mehr liest (der Umbau der Workflows entfernt die Build-Zeilen):

- [x] `grep -rn "TMDB_API_KEY\|TYPESAFE_API_KEY\|ELEVENLABS_API_KEY" .github/` liefert nichts mehr
      (auch `screens.yml` liest das Secret nicht mehr).
- [ ] Unter Settings › Secrets and variables › Actions die Secrets `TMDB_API_KEY`, `TYPESAFE_API_KEY`
      und `ELEVENLABS_API_KEY` löschen. `KEYSTORE_BASE64` bleibt.
- [ ] Gibt es dieselben Secrets auch als Environment- oder Organisations-Secrets oder in Forks, die du
      verwaltest? Dort ebenfalls löschen.

## 3. Git-Verlauf auf Geheimnisse geprüft

Durchsucht am 26.11.2026: alle 1288 Commits aller Zweige und Tags (`git log --all -p`, nach dem
Nachladen des vollständigen Verlaufs), dazu `extras/worker/*` mit beiden `wrangler.toml` und
`tools/smoke/*`. Gesucht wurde nach Schlüssel-Mustern (32-stellig hex, `sk_…`, JWT/`eyJ…`, GitHub-,
AWS- und Google-Tokens, privaten Schlüsseln, Zuweisungen an `apiKey`/`token`/`secret`/`password`)
und nach Schlüsseldateien.

Ergebnis:

- **Keine Dienst-Schlüssel im Verlauf.** Die 32-stelligen Hex-Treffer sind ausschließlich Room-Schema-
  Hashes (`app/schemas/…`). Der einzige `sk_…`-Treffer ist ein Platzhalter in
  `ApiKeysSanitizeTest.kt`. Die Schlüssel kamen nur über die GitHub-Secrets in die APKs, nicht über
  den Quelltext.
- **`wrangler.toml` und Worker** (`extras/worker/tmdb`, `extras/worker/opensub`): keine Geheimnisse; die
  Schlüssel werden dort per `wrangler secret put` gesetzt.
- **Smoke-Testdaten** (`tools/smoke/fixtures.py`): keine Zugangsdaten, nur die lokale Test-Playlist.
- **Gefunden: eine Signaturdatei im Verlauf.** `app/sideload.keystore` wurde am 25.10.2026 mit
  „Begleit-App für Android-TV-Kanäle mit IPTV-Vorschlägen“ (`b4e825fa`) eingecheckt und später wieder
  entfernt; im Verlauf ist sie weiter abrufbar. Sie öffnet sich mit dem öffentlich bekannten Passwort
  `android`. Ihr Zertifikat (`CN=TV Kanaele Sideload`, SHA-256 beginnt mit `56:9C:C8:40`) ist **nicht**
  das Zertifikat der aktuellen Releases (`CN=TV Kanaele`, SHA-256 beginnt mit `80:54:3B:74`).
  - [ ] Prüfen, ob jemals eine veröffentlichte APK mit dieser Datei signiert wurde (vermutlich die
        frühen Builds vor v1.0.36). Falls ja, kann jeder „Updates“ für solche alten Installationen
        bauen; diese Releases dann löschen.
  - [ ] Optional: die Datei mit `git filter-repo` aus dem Verlauf entfernen. Das schreibt den Verlauf
        um (alle Zweige und Tags, Force-Push); nur sinnvoll, wenn die Datei je produktiv war.
- **Signaturpasswort im Klartext**: Die Workflows setzen `KEYSTORE_PASSWORD`/`KEY_PASSWORD` auf `android`.
  Der Release-Schlüssel ist damit allein durch das Secret `KEYSTORE_BASE64` geschützt. Das ist
  vertretbar, solange das Secret sicher ist; ein echtes Passwort als zweites Secret wäre robuster.
- **Kein Geheimnis, aber öffentlich**: `app/build.gradle.kts` nennt einen lokalen Pfad auf deinem
  Rechner (`E:/MEGA/…`), und die Commits tragen die E-Mail-Adresse des Autors. Prüfe, ob beides
  öffentlich sichtbar sein soll (GitHub bietet eine `noreply`-Adresse für künftige Commits).
- [ ] Vor dem Umschalten auf „öffentlich“ noch einmal mit einem eigenen Scanner gegenprüfen, z. B.
      `gitleaks detect --log-opts="--all"` oder `trufflehog git file://.` auf einem vollständigen Klon.
      GitHubs „Secret scanning“ nach dem Veröffentlichen einschalten.

## 4. Metadaten ohne eigenen TMDB-Schlüssel: was öffentliche Nutzer bekommen

- Die Workflows setzen kein `OWNTV_EDGE_KEY`, also ist `TMDB_EDGE_KEY` in den Releases leer
  (`app/build.gradle.kts`). Ohne eigenen TMDB-Schlüssel und ohne eigenen Server spricht die App dann
  mit `owntv-tmdb-meta.xiannero.workers.dev`, dem alten, ungeschützten Worker des **OwnTV-Projekts**
  (nicht deiner). Laut den früheren Kommentaren in `app/build.gradle.kts` und `OwnTVApp.kt` antwortete
  der gemeinsame OwnTV-Dienst Mayo-TV-Builds mit 403; deshalb wurde damals der eigene TMDB-Schlüssel
  eingebaut. Ob der alte Worker heute antwortet, ist nicht sicher.
- Praktisch heißt das: Ohne eigenen Schlüssel gibt es keine oder nur unzuverlässige TMDB-Daten, und
  falls doch, laufen sie über einen fremden Dienst mit Tageskontingent. Die Texte in der App und in
  [docs/api-keys.md](api-keys.md) sagen deshalb „Poster brauchen einen eigenen Schlüssel“.
- [ ] Entscheiden: (a) so lassen, (b) eigenen Worker betreiben (`extras/worker/tmdb`, mit eigenem
      Schlüssel als Worker-Secret) und als Standard eintragen, oder (c) ohne Schlüssel gar keine
      Anfragen an den OwnTV-Worker schicken. (b) und (c) brauchen eine Änderung im Core-Fork.
      Mit dem OwnTV-Projekt klären, ob die Nutzung seiner Worker durch Mayo TV in Ordnung ist.
- [ ] Dasselbe gilt für die Untertitelsuche: Sie läuft über `my-owntv-opensub.xiannero.workers.dev`,
      ebenfalls ein Worker des OwnTV-Projekts.
- Den Stand zeigt `tour/net.txt` aus dem Workflow `screens.yml` (Schritt „Metadaten-Dienst
  erreichbar?“).

## 5. Lizenzen und Hinweise

- [ ] **GPLv3, vollständiger Quelltext**: Zu jeder veröffentlichten APK muss der passende Quelltext
      erreichbar sein. Das App-Repository wird öffentlich; der Core kommt aus dem Fork in
      `core-fork.properties` (`Jonathanxoxox/OwnTV_Core`, Zweig `mayo`). Dieser Fork muss ebenfalls
      öffentlich sein, und es muss nachvollziehbar sein, welcher Core-Stand in welcher APK steckt
      (z. B. Core-Commit im Release-Text nennen oder taggen).
- [ ] Copyright-Hinweise von OwnTV (© ahXN00, GPLv3) bleiben erhalten; README nennt die Änderungen.
      `LICENSE` liegt im Repository.
- [ ] **TMDB-Hinweis**: Wer die TMDB-API nutzt, muss TMDB nennen, mit dem Satz „This product uses the
      TMDB API but is not endorsed or certified by TMDB.“ und dem TMDB-Logo. README hat den Satz; in
      der App steht er in den Metadaten-Einstellungen (Core). Prüfen, dass er auch nach dem Umbau gut
      auffindbar ist (z. B. unter Über), und die aktuellen Vorgaben in den TMDB-Nutzungsbedingungen
      gegenlesen.
- [ ] **Schriften**: Alle mitgelieferten Schriften stehen unter der SIL Open Font License; die Texte
      liegen in `extras/font-licenses/`. Die OFL verlangt, dass Lizenz und Copyright der Schrift
      beiliegen. Prüfen, ob die App eine Lizenzseite hat oder die Texte in der APK mitbringen sollte.
- [ ] **Bibliotheken**: mpv und FFmpeg (LGPL/GPL), Media3/ExoPlayer (Apache 2.0) und die übrigen
      Abhängigkeiten in einer Lizenzübersicht nennen (App oder README).
- [ ] **Logos und Marken**: `extras/partner-logos/` enthält Logos von TMDB, OpenSubtitles und Weblate.
      Nur gemäß deren Markenrichtlinien verwenden. Name „Mayo TV“ und das eigene Logo: einmal auf
      Kollisionen prüfen.
- [ ] **Sound-Logo**: Der Ton des Intros wird von `tools/sound/mayo_intro.py` erzeugt. Bestätigen, dass
      keine fremden Samples darin stecken.
- [ ] **Spieldaten**: Spielpläne und Ergebnisse kommen von nicht offiziell dokumentierten ESPN-Endpunkten.
      Deren Nutzungsbedingungen einmal lesen und das Risiko bewusst eingehen oder eine Alternative
      planen.

## 6. Datenschutz

- [ ] Einen kurzen Datenschutzhinweis veröffentlichen (README oder eigene Seite), der ehrlich aufzählt,
      wohin Mayo TV Daten schickt:
      - dein IPTV-Anbieter (Playlist, EPG, Streams),
      - ESPN (Spieldaten), GitHub (Update-Prüfung),
      - TMDB mit dem eigenen Schlüssel des Nutzers bzw. ohne Schlüssel der OwnTV-Worker (Titel, nach
        denen gesucht wird),
      - ElevenLabs (Aufnahme der Sprachsuche, nur mit eigenem Schlüssel),
      - TypeSafe (Suchwunsch und eine Auswahl Titel mit Kurzbeschreibung, nur mit eigenem Schlüssel),
      - OpenSubtitles über den OwnTV-Worker (Untertitelsuche, nur mit eigenem Konto).
- Keine Analyse- oder Absturzdienste von Dritten eingebaut (Stand jetzt: `app/build.gradle.kts` hat
  keine).
- Schlüssel liegen nur auf dem Gerät, werden nicht protokolliert und kommen nicht in die
  Android-Cloud-Sicherung (`allowBackup="false"`). Eine mit Passwort geschützte Sicherung aus der App
  enthält den TMDB-Schlüssel.
- **Home-Wächter (Bedienungshilfe)**: Er wertet nur den Paketnamen der App aus, die nach vorn kommt
  (`typeWindowStateChanged`, `canRetrieveWindowContent="false"`), und sendet nichts. Genau so steht es
  in der Einrichtung und in der Beschreibung des Dienstes. Bei jeder Änderung am Dienst diese Aussage
  neu prüfen.
- Die Handy-Seiten (Playlist, Schlüssel) laufen nur im lokalen WLAN, mit Einmal-Link bzw. PIN.

## 7. Test auf einer frisch zurückgesetzten Box

Am besten auf zwei Geräten: einer schwachen Box (Android 8, 1–2 GB RAM) und einem Google-TV- oder
Fire-TV-Gerät. Vorher auf Werkseinstellungen zurücksetzen, dann die öffentliche APK über Downloader
installieren, genau wie ein neuer Nutzer.

- [ ] **Intro**: läuft flüssig, Ton in normaler Lautstärke, jede Taste überspringt, die Stummtaste
      schaltet den Ton auch für später ab.
- [ ] **Ganze Einrichtung nur mit der Fernbedienung**, ohne nachzudenken: auf jeder Seite ist sofort
      etwas fokussiert, Zurück tut überall etwas Sinnvolles, nichts ist abgeschnitten, alles aus 3 m
      lesbar.
- [ ] **Playlist per Handy** und per Fernbedienung; Import im Hintergrund, während die Kapitel laufen.
- [ ] **Startbildschirm**: Home-Wächter einschalten, mit Zurück in die App, Status springt auf „Aktiv“;
      Home-Taste bringt Mayo TV; Box aus- und einschalten (Beim Einschalten: Startseite / Live-TV /
      nichts ändern). Auf einer Box, die keine Bedienungshilfen-Einstellungen öffnen lässt: keine
      Sackgasse, „Später“ geht weiter.
- [ ] **App mitten in der Einrichtung verlassen** (Home-Taste oder Einstellungen der Box) und
      zurückkommen: dieselbe Seite erscheint wieder.
- [ ] **Fußball**: ohne Internet (Vereinsliste nicht erreichbar) und mit; „Kein Fußball für mich“.
- [ ] **Extras**: Schlüssel per QR-Code vom Handy, einmal falsch, einmal richtig; ohne Netzwerk sagt
      die Seite das. Danach in den Einstellungen ändern und entfernen.
- [ ] **Ohne jeden Schlüssel**: Filme/Serien, Suche und Mikrofon sagen klar, was fehlt, und nichts
      hängt oder stürzt ab.
- [ ] **Update statt Neuinstallation**: die letzte alte Release-APK installieren und einrichten, dann
      die neue darüber installieren. Erwartet: keine Einrichtung, alles wie vorher (Poster gehen erst
      wieder, wenn ein eigener Schlüssel eingetragen ist, siehe Abschnitt 1).
- [ ] **Systemsprache Englisch** einmal komplett durchspielen.
- [ ] Danach `screens.yml` laufen lassen und die Fotos der Kapitel (`kapitel-*` im Zweig `screens/…`)
      durchsehen.

## 8. Zum Schluss

- [ ] In `CHANGELOG.md` die Überschrift „Unreleased — Mayo TV“ durch Version und Datum ersetzen.
- [ ] Release-Text: Hinweis auf eigene Schlüssel und die neue Einrichtung, Link auf
      [docs/api-keys.md](api-keys.md).
- [ ] Repository auf „Public“ stellen, danach Secret scanning und Dependabot-Warnungen einschalten.
