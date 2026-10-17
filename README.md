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
- Scrobbling an Navidrome („Spielt gerade“ + nach halber Laufzeit)
- Musik anfordern über [Lidarr](https://lidarr.audio/): Alben oder ganze Künstler suchen und hinzufügen,
  Downloads verfolgen (Tab „Anfragen“; benötigt Lidarr-URL und API-Key)
- In-App-Updates über GitHub Releases

Schrift: [Manrope](https://github.com/googlefonts/manrope), SIL Open Font License (`licenses/Manrope-OFL.txt`).

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
