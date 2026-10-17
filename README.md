# NaviDroid

Android-Client für [Navidrome](https://www.navidrome.org/) (Subsonic-API), geschrieben in Kotlin mit Jetpack Compose und Media3.

## Funktionen

- Anmeldung per Subsonic-Token (das Passwort wird nicht gespeichert)
- Alben (neu, zuletzt gespielt, häufig, zufällig, A–Z, Favoriten) mit endlosem Nachladen
- Künstler-Index, Künstler- und Album-Ansicht, Playlists, Suche
- Hintergrundwiedergabe mit Benachrichtigung, Sperrbildschirm- und Bluetooth-Steuerung
- Warteschlange (als Nächstes / anhängen / entfernen), Zufall, Wiederholen
- Zufallsmix aus der ganzen Bibliothek
- Synchronisierte Songtexte, die Wort für Wort mitlaufen (von Navidrome; optional ergänzt über
  [LRCLIB](https://lrclib.net), abschaltbar im Menü auf der Startseite)
- Favoriten für Titel und Alben
- Fehlt in Navidrome ein Cover, lädt die App es über MusicBrainz (ID oder Suche nach Album und Künstler) aus dem
  [Cover Art Archive](https://coverartarchive.org)
- Scrobbling an Navidrome („Spielt gerade“ + nach halber Laufzeit)
- Musik anfordern über [Lidarr](https://lidarr.audio/): Alben oder ganze Künstler suchen und hinzufügen,
  Downloads verfolgen (Tab „Anfragen“; benötigt Lidarr-URL und API-Key)
- Playlists anlegen, umbenennen, sortieren (Ziehen), Titel hinzufügen/entfernen, löschen – gespeichert in Navidrome
- Beim Anfordern Quelle wählen: Lidarr oder direkt YouTube; YouTube-Suche mit Abgleich der Album-Titel
- Fehlende Alben aus YouTube-Playlists oder „Full Album“-Videos mit Kapiteln holen (optionaler YouTube-Helfer, siehe unten)
- Optionale Musikvideos im Player (stumm und synchron zum Song)
- In-App-Updates über GitHub Releases

Schrift: [Manrope](https://github.com/googlefonts/manrope), SIL Open Font License (`licenses/Manrope-OFL.txt`).

## YouTube-Helfer (optional)

Findet Lidarr für ein Album keine Quelle, kann die App es aus einer YouTube-Playlist holen. Das erledigt
ein kleiner Docker-Dienst auf deinem Server (`helper/`, Python mit yt-dlp und ffmpeg):

1. Die App schickt den Playlist-Link an den Helfer. Er ordnet die Videos den Titeln des Lidarr-Albums zu.
2. Der Helfer lädt die Audiospuren, wandelt sie in MP3 (320 kbit/s) und setzt Tags, Cover und
   MusicBrainz-IDs.
3. Lidarr importiert die Dateien per „Manual Import“, benennt sie nach deinem Schema und verschiebt sie
   in den Stammordner. Das Album gilt danach als vorhanden, Navidrome findet es beim nächsten Scan.
4. Optional lädt der Helfer die Musikvideos (MP4) in einen eigenen Ordner; die App spielt sie im Player.

Einrichtung:

1. `helper/docker-compose.example.yml` übernehmen und anpassen. Das Image wird automatisch als
   `ghcr.io/enjxzlt/navidroid-helper:latest` gebaut und wöchentlich aktualisiert (neues yt-dlp).
2. Den Staging-Ordner (z. B. `/pfad/zu/downloads/navidroid`) **auch in Lidarr** einbinden. Liegt er dort
   unter einem anderen Pfad, diesen als `LIDARR_STAGING_DIR` setzen.
3. Helfer und Lidarr mit derselben UID/GID laufen lassen, damit Lidarr die Dateien verschieben darf.
4. In der App: Anfragen → Einstellungen → „YouTube-Helfer“ mit URL (Port 8787) und `HELPER_TOKEN`.

Hinweis: YouTube-Downloads können gegen die Nutzungsbedingungen von YouTube verstoßen. Importiere nur
Inhalte, die du nutzen darfst.

Tests des Helfers (im gebauten Image):

```bash
docker build -t navidroid-helper helper && docker run --rm -v "$PWD/helper/tests:/app/tests:ro" --tmpfs /tmp:uid=1000 navidroid-helper python -m unittest discover -s tests
```

## Bauen

Voraussetzungen: JDK 17 und Android SDK (am einfachsten: Projekt in Android Studio öffnen).

```bash
gradle assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/`.

## Updates über GitHub

Die App fragt beim Start `https://api.github.com/repos/<owner>/<repo>/releases/latest` ab.
Ist dort eine höhere Version mit angehängter `.apk`, bietet sie Download und Installation an
(manuell: Menü → „Nach Updates suchen“).

Einrichtung (einmalig):

1. Projekt in ein GitHub-Repository pushen. Das Repo muss öffentlich sein, da die App ohne Token abfragt.
2. Signierschlüssel erzeugen – alle Updates müssen mit demselben Schlüssel signiert sein:
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias navidroid -keyalg RSA -keysize 4096 -validity 10000
   ```
3. Unter *Settings → Secrets and variables → Actions* anlegen:
   - `KEYSTORE_BASE64` – Ausgabe von `base64 -w0 release.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (z. B. `navidroid`), `KEY_PASSWORD`
4. Release veröffentlichen:
   ```bash
   git tag v1.0.1 && git push origin v1.0.1
   ```
   Der Workflow baut die APK mit Version `1.0.1`, setzt das Repo für die Update-Prüfung automatisch
   und hängt die APK an das Release.

`release.jks` gut aufbewahren und **nicht** committen. Lokal gebaute APKs (Debug-Schlüssel) lassen sich
nicht per In-App-Update auf eine Release-Version aktualisieren – einmal die APK aus dem ersten
GitHub-Release installieren, danach laufen Updates automatisch.
