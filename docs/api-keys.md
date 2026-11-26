# Eigene Schlüssel für Mayo TV

Mayo TV bringt keine Schlüssel für fremde Dienste mit. Drei Extras nutzen solche Dienste, und für jedes
brauchst du einen **eigenen Schlüssel** (oft „API-Key“ genannt) von deinem eigenen Konto dort. Alle drei
sind freiwillig: Ohne Schlüssel funktioniert Mayo TV ganz normal, nur das jeweilige Extra fehlt. Wo
es fehlt, sagt dir die App, welchen Schlüssel du dafür brauchst: auf der Infoseite eines Films oder
einer Serie (TMDB), auf der Suchseite (TypeSafe und TMDB) und wenn die Sprachsuche dich nicht
verstanden hat (ElevenLabs).

| Dienst | Schaltet frei | Ohne Schlüssel |
|------|------|------|
| [TMDB](#tmdb-poster-und-filminfos) | Poster, Hintergrundbilder, Handlung und Besetzung für Filme und Serien, die Film- und Serien-Kataloge, „Im Abo bei“, die Suche nach Schauspielern und Regie | Du siehst, was deine Playlist mitliefert (Titel und, falls vorhanden, ihre Bilder) |
| [ElevenLabs](#elevenlabs-sprachsuche) | Die Sprachsuche am Mikrofon-Knopf schreibt genau mit, was du sagst, auch Namen | Mayo TV nutzt die Spracherkennung der Box, falls sie eine hat |
| [TypeSafe](#typesafe-suche-nach-beschreibung) | Suche nach Beschreibung („ein gruseliger Film mit einer Hütte im Wald“); hilft außerdem der Sprachsuche, den gemeinten Sender oder Titel zu finden | Die Suche findet nach Titel, Sender und Sendung, nicht nach Beschreibung |

## Wo du Schlüssel eingibst

- **Bei der Ersteinrichtung**, im Schritt **Extras**. Am bequemsten mit dem Handy: Scanne den QR-Code
  auf dem Fernseher, füge deine Schlüssel auf der Seite ein, die sich öffnet, und tippe auf **An den
  Fernseher senden**. Dein Handy muss dafür im selben WLAN sein wie der Fernseher. Du kannst sie auch mit
  der Fernbedienung eintippen. Mayo TV prüft jeden Schlüssel gleich und zeigt, ob er funktioniert.
- **Später jederzeit** unter **Einstellungen › System › Eigene Schlüssel**. Dort kannst du Schlüssel
  hinzufügen, prüfen, ersetzen und entfernen.
- Den Schritt **Extras** kannst du mit **Später** einfach überspringen.

Tipp: Schlüssel sind lang. Kopiere sie am Handy oder Computer und schick sie per QR-Code, statt sie
mit der Fernbedienung abzutippen.

## TMDB: Poster und Filminfos

[The Movie Database (TMDB)](https://www.themoviedb.org/) ist eine von der Community gepflegte Datenbank
für Filme und Serien.

1. Lege auf [themoviedb.org](https://www.themoviedb.org/signup) ein kostenloses Konto an und bestätige
   deine E-Mail-Adresse.
2. Öffne dein Profil › **Einstellungen** › **API** und beantrage dort einen API-Schlüssel. TMDB fragt
   dabei nach ein paar Angaben zur Nutzung, zum Beispiel ob sie privat ist.
3. Kopiere den **API-Schlüssel** (32 Zeichen, nur Ziffern und Buchstaben a–f). Nicht gemeint ist das
   viel längere **Lese-Zugriffstoken** auf derselben Seite. Gibst du versehentlich das Token ein, sagt
   Mayo TV dir das.

**Kosten:** Für private, nicht-kommerzielle Nutzung stellt TMDB die API derzeit kostenlos zur Verfügung.
Es gelten die [Nutzungsbedingungen der TMDB-API](https://www.themoviedb.org/api-terms-of-use); maßgeblich
ist, was TMDB dort aktuell schreibt.

## ElevenLabs: Sprachsuche

[ElevenLabs](https://elevenlabs.io/) wandelt Sprache in Text um. Mayo TV nutzt dafür die
Echtzeit-Spracherkennung „Scribe“.

1. Lege auf [elevenlabs.io](https://elevenlabs.io/) ein Konto an.
2. Öffne in deinem Konto den Bereich **API Keys** (je nach Oberfläche unter den Einstellungen oder im
   Entwicklerbereich) und erstelle einen neuen Schlüssel.
3. Du kannst den Schlüssel auf das Nötige beschränken: Mayo TV braucht nur **Speech to Text**.
4. Kopiere den Schlüssel. Er wird meist nur einmal vollständig angezeigt.

**Kosten:** Jede Sprachsuche verbraucht etwas von deinem Guthaben bei ElevenLabs. Ob dein Tarif dafür
reicht und was darüber hinaus kostet, steht auf der [Preisseite von ElevenLabs](https://elevenlabs.io/pricing).
Mayo TV nimmt nur auf, während du über den Mikrofon-Knopf suchst.

## TypeSafe: Suche nach Beschreibung

Die Suche nach Beschreibung nutzt **Jev** von [TypeSafe](https://typesafe.ai/). Mayo TV spricht dafür
mit `api.typesafe.ai`.

1. Melde dich auf [typesafe.ai](https://typesafe.ai/) an.
2. Erstelle in deinem Konto einen API-Schlüssel und kopiere ihn.

**Kosten:** Jede Suche nach Beschreibung verbraucht etwas von deinem Guthaben bei TypeSafe. Die
aktuellen Konditionen findest du auf [typesafe.ai](https://typesafe.ai/).

Mayo TV prüft auch den TypeSafe-Schlüssel gleich, ohne Guthaben zu verbrauchen. Die Suche nach
Beschreibung braucht zusätzlich den TMDB-Schlüssel: TypeSafe versteht deinen Wunsch, TMDB findet die
passenden Filme und Serien.

## Datenschutz

- **Deine Schlüssel bleiben auf dem Fernseher.** Mayo TV schickt sie an niemanden außer an den
  jeweiligen Dienst selbst, und schreibt sie in kein Protokoll.
- **Vom Handy zum Fernseher** gehen die Schlüssel direkt über dein WLAN, nicht über das Internet oder
  einen Server von Mayo TV. Der Link im QR-Code gilt nur, solange der Schritt Extras offen ist, und
  schließt sich nach zu vielen falschen Aufrufen von selbst.
- **Sicherungen:** Eine mit Passwort geschützte Sicherung enthält den TMDB-Schlüssel, eine ohne Passwort
  nicht. Die Schlüssel für ElevenLabs und TypeSafe sind nie in einer Sicherung. Android selbst sichert
  die Daten von Mayo TV nicht in die Cloud.
- **Was die Dienste sehen:** TMDB erfährt, welche Titel Mayo TV nachschlägt. ElevenLabs bekommt die
  Aufnahme deiner Sprachsuche. TypeSafe bekommt deinen Suchwunsch und eine Auswahl passender Titel mit
  kurzer Beschreibung. Für alles Weitere gelten die Datenschutzbestimmungen des jeweiligen Dienstes.
- **Entfernen:** Unter Einstellungen › System › Eigene Schlüssel löschst du einen Schlüssel vom Fernseher.
  Brauchst du ihn gar nicht mehr, widerrufe ihn zusätzlich in deinem Konto beim Dienst.
