# NaviDroid Extras (Navidrome-Plugin)

- **Songtexte von LRCLIB** für Titel ohne eigene `.lrc`-Datei oder eingebetteten Text – in jeder Subsonic-App (zeilensynchron; Wort-Zeitmarken werden entfernt).
- **Ähnliche Titel** über das Deezer-Radio des Künstlers (`getSimilarSongs`/`getSimilarSongs2`, „Instant Mix“). Ähnliche Künstler, Bilder und Top-Titel liefert Navidromes eingebauter Deezer-Dienst.

Bauen: `./build.sh` (TinyGo im Docker-Container) → `navidroid-extras.ndp`.

Installation (Navidrome ≥ 0.64):
1. `navidroid-extras.ndp` nach `<DataFolder>/plugins/` kopieren.
2. In `navidrome.toml`:
   ```toml
   Agents = "deezer,lastfm,listenbrainz,navidroid-extras"
   LyricsPriority = ".ttml,.yaml,.yml,.elrc,.lrc,.srt,.txt,embedded,navidroid-extras"
   ```
3. `navidrome plugin enable navidroid-extras` (oder in der Weboberfläche) und Navidrome neu starten.
