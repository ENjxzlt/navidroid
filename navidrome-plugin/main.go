// NaviDroid Extras – Navidrome-Plugin.
//
//   - Lyrics: synchronisierte Songtexte von LRCLIB (für Titel ohne eigene .lrc-Datei oder eingebetteten Text).
//   - MetadataAgent: ähnliche Titel über das Deezer-Radio des Künstlers – füllt „Instant Mix“
//     und Radio-Funktionen in Subsonic-Apps. Ähnliche Künstler, Bilder und Top-Titel liefert schon
//     Navidromes eingebauter Deezer-Dienst.
//
// Bauen: ./build.sh  →  navidroid-extras.ndp
package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"

	"github.com/navidrome/navidrome/plugins/pdk/go/host"
	"github.com/navidrome/navidrome/plugins/pdk/go/lyrics"
	"github.com/navidrome/navidrome/plugins/pdk/go/metadata"
	"github.com/navidrome/navidrome/plugins/pdk/go/pdk"
	"github.com/navidrome/navidrome/plugins/pdk/go/types"
)

const userAgent = "NaviDroid-Extras/1.0 (https://github.com/ENjxzlt/navidroid)"

type plugin struct{}

func init() {
	lyrics.Register(&plugin{})
	metadata.Register(&plugin{})
}

var (
	_ lyrics.Lyrics                         = (*plugin)(nil)
	_ metadata.SimilarSongsByTrackProvider  = (*plugin)(nil)
	_ metadata.SimilarSongsByArtistProvider = (*plugin)(nil)
	_ metadata.SimilarSongsByAlbumProvider  = (*plugin)(nil)
)

// getJSON holt eine URL (mit Zwischenspeicher) und dekodiert JSON. 404 = keine Daten (nil, nil).
func getJSON(rawURL string, ttl int64, out any) (found bool, err error) {
	key := "http:" + rawURL
	if b, ok, _ := host.CacheGetBytes(key); ok {
		if len(b) == 0 {
			return false, nil
		}
		return true, json.Unmarshal(b, out)
	}
	resp, err := host.HTTPSend(host.HTTPRequest{
		Method:    "GET",
		URL:       rawURL,
		Headers:   map[string]string{"Accept": "application/json", "User-Agent": userAgent},
		TimeoutMs: 10000,
	})
	if err != nil {
		return false, err
	}
	if resp.StatusCode == 404 {
		_ = host.CacheSetBytes(key, []byte{}, ttl)
		return false, nil
	}
	if resp.StatusCode != 200 {
		return false, fmt.Errorf("HTTP %d von %s", resp.StatusCode, rawURL)
	}
	if err := json.Unmarshal(resp.Body, out); err != nil {
		return false, err
	}
	_ = host.CacheSetBytes(key, resp.Body, ttl)
	return true, nil
}

// ---- Songtexte ----

func (p *plugin) GetLyrics(req lyrics.GetLyricsRequest) (lyrics.GetLyricsResponse, error) {
	t := req.Track
	artist := primaryArtist(t.Artist)
	if t.Title == "" || artist == "" {
		return lyrics.GetLyricsResponse{}, nil
	}
	dur := float64(t.Duration)

	// 1. Genaue Abfrage (Titel, Künstler, Album, Länge)
	q := url.Values{}
	q.Set("track_name", t.Title)
	q.Set("artist_name", artist)
	if t.Album != "" {
		q.Set("album_name", t.Album)
	}
	if dur > 0 {
		q.Set("duration", strconv.Itoa(int(dur+0.5)))
	}
	var exact lrclibResult
	found, err := getJSON("https://lrclib.net/api/get?"+q.Encode(), 7*24*3600, &exact)
	if err != nil {
		pdk.Log(pdk.LogWarn, "LRCLIB: "+err.Error())
	}
	var hit *lrclibResult
	if found {
		hit = bestLyrics([]lrclibResult{exact}, t.Title, artist, dur)
	}

	// 2. Suche, falls nichts oder nur ungesyncter Text gefunden wurde
	if hit == nil || hit.SyncedLyrics == "" {
		s := url.Values{}
		s.Set("track_name", t.Title)
		s.Set("artist_name", artist)
		var results []lrclibResult
		if ok, err := getJSON("https://lrclib.net/api/search?"+s.Encode(), 7*24*3600, &results); err == nil && ok {
			if better := bestLyrics(results, t.Title, artist, dur); better != nil && (hit == nil || better.SyncedLyrics != "") {
				hit = better
			}
		}
	}
	if hit == nil {
		return lyrics.GetLyricsResponse{}, nil
	}
	return lyrics.GetLyricsResponse{Lyrics: []lyrics.LyricsText{{Text: lyricsText(hit)}}}, nil
}

// ---- Ähnliche Titel ----

func deezerArtistID(name string) (int64, bool) {
	name = primaryArtist(name)
	if name == "" {
		return 0, false
	}
	var res struct {
		Data []deezerArtist `json:"data"`
	}
	ok, err := getJSON("https://api.deezer.com/search/artist?limit=5&q="+url.QueryEscape(name), 30*24*3600, &res)
	if err != nil || !ok {
		return 0, false
	}
	a := pickArtist(res.Data, name)
	if a == nil {
		return 0, false
	}
	return a.ID, true
}

func similarSongs(artist, skipTitle string, count int32) (*metadata.SimilarSongsResponse, error) {
	id, ok := deezerArtistID(artist)
	if !ok {
		return nil, nil // kein Treffer = keine Daten
	}
	var radio struct {
		Data []deezerTrack `json:"data"`
	}
	if _, err := getJSON(fmt.Sprintf("https://api.deezer.com/artist/%d/radio?limit=100", id), 24*3600, &radio); err != nil {
		return nil, err
	}
	limit := int(count)
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	tracks := mixTracks(radio.Data, skipTitle, limit)
	if len(tracks) == 0 {
		return nil, nil
	}
	songs := make([]types.SongRef, 0, len(tracks))
	for _, t := range tracks {
		songs = append(songs, types.SongRef{
			Name:       t.Title,
			Artists:    []types.ArtistRef{{Name: t.Artist.Name}},
			Artist:     t.Artist.Name,
			Album:      t.Album.Title,
			DurationMs: uint32(t.Duration) * 1000,
		})
	}
	return &metadata.SimilarSongsResponse{Songs: songs}, nil
}

func (p *plugin) GetSimilarSongsByTrack(req metadata.SimilarSongsByTrackRequest) (*metadata.SimilarSongsResponse, error) {
	return similarSongs(req.Artist, req.Name, req.Count)
}

func (p *plugin) GetSimilarSongsByArtist(req metadata.SimilarSongsByArtistRequest) (*metadata.SimilarSongsResponse, error) {
	return similarSongs(req.Name, "", req.Count)
}

func (p *plugin) GetSimilarSongsByAlbum(req metadata.SimilarSongsByAlbumRequest) (*metadata.SimilarSongsResponse, error) {
	return similarSongs(req.Artist, "", req.Count)
}

func main() {}
