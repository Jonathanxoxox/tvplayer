# Mitmachen bei Mayo TV

Mayo TV ist eine veränderte Fassung von [OwnTV](https://github.com/ahXN00/OwnTV). Die Kernbibliothek
(Datenbank, Parser, EPG, Player-Engines) wird aus dem eigenen Fork gebaut, den
[`core-fork.properties`](core-fork.properties) nennt; lokal zeigt `owntv.corePath` in
`~/.gradle/gradle.properties` auf einen Checkout davon. Dieses Repository enthält die App in `app/`.

## Grundregeln

- Mayo TV ist **nur ein Player**. Keine mitgelieferten Sender, Playlists oder Streams.
- Gebaut für **Android TV** mit Fernbedienung: jede Seite muss sich mit dem Steuerkreuz bedienen lassen.
- **Eine feste Bedienung**: keine neuen Layout-Schalter oder Varianten. Wenn etwas falsch aussieht,
  wird die eine Ansicht repariert.
- Keine Pop-ups, die von selbst erscheinen, und keine bewegten Hintergründe. Kurze Übergänge
  zwischen Ansichten sind erwünscht.
- Code-Stil wie im Rest: Kotlin, Jetpack Compose for TV, Koin, Room.

## Ablauf

1. Zweig von `main` anlegen, ändern, committen. Commit-Nachrichten beschreiben für einen Leser, was
   sich für den Zuschauer ändert.
2. Pull Request gegen `main` öffnen. CI baut die APK (`tvplayer.yml`), führt alle JVM-Tests und Android Lint
   aus und startet die App im Emulator (`smoke.yml`), und prüft auf feste Texte im Code (`i18n.yml`).
3. Gemergt wird, sobald alles grün ist.

Vor dem Push lohnt sich die Textprüfung lokal, sie braucht kein Android SDK:

```bash
python3 tools/i18n/check_hardcoded_strings.py verify-ci --base-sha "$(git merge-base HEAD origin/main)"
```

Neue Texte stehen nie fest im Code: Englisch (die Quelle) in einer Datei unter `app/src/main/res/values/`,
Deutsch in der gleichnamigen Datei unter `values-de/`. Welche Datei und wie, steht in
[docs/i18n.md](docs/i18n.md).

Schlüssel für fremde Dienste (TMDB, ElevenLabs, TypeSafe) gehören nie ins Repository, in die APK oder in
ein Protokoll. Jeder Nutzer bringt seine eigenen mit, siehe [docs/api-keys.md](docs/api-keys.md).

## Lizenz

Beiträge stehen wie das ganze Projekt unter der [GPLv3](LICENSE).
