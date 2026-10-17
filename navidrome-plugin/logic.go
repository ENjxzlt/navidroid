package main

import (
	"regexp"
	"strings"
	"unicode"
)

// Akzente ohne zusätzliche Bibliothek entfernen (genug für Namen und Titel).
var foldAccents = strings.NewReplacer(
	"ä", "a", "ö", "o", "ü", "u", "ß", "ss", "é", "e", "è", "e", "ê", "e", "ë", "e",
	"á", "a", "à", "a", "â", "a", "ã", "a", "å", "a", "í", "i", "ì", "i", "î", "i", "ï", "i",
	"ó", "o", "ò", "o", "ô", "o", "õ", "o", "ø", "o", "ú", "u", "ù", "u", "û", "u",
	"ñ", "n", "ç", "c", "ý", "y", "ÿ", "y", "’", "'",
)

// ---- LRCLIB ----

type lrclibResult struct {
	TrackName    string  `json:"trackName"`
	ArtistName   string  `json:"artistName"`
	AlbumName    string  `json:"albumName"`
	Duration     float64 `json:"duration"`
	Instrumental bool    `json:"instrumental"`
	PlainLyrics  string  `json:"plainLyrics"`
	SyncedLyrics string  `json:"syncedLyrics"`
}

// Wort-Zeitmarken aus erweitertem LRC (<mm:ss.xx>) – über die Subsonic-Schnittstelle gibt es nur
// Zeilen-Timing, und andere Apps würden die Marken sonst als Text anzeigen.
var wordTag = regexp.MustCompile(`<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>`)

func stripWordTags(lrc string) string {
	lines := strings.Split(lrc, "\n")
	for i, l := range lines {
		l = wordTag.ReplaceAllString(l, "")
		lines[i] = strings.Join(strings.Fields(l), " ")
		// "[00:12.34]" + Leerzeichen-Normalisierung lässt den Zeitstempel unberührt
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

// bestLyrics wählt aus Treffern den passendsten: gleiche Länge (±3 s) und synchronisiert bevorzugt.
func bestLyrics(results []lrclibResult, title, artist string, duration float64) *lrclibResult {
	var best *lrclibResult
	bestScore := -1
	for i := range results {
		r := &results[i]
		if r.Instrumental || (r.SyncedLyrics == "" && r.PlainLyrics == "") {
			continue
		}
		score := 0
		if simplify(r.TrackName) == simplify(title) {
			score += 4
		}
		if strings.Contains(simplify(r.ArtistName), simplify(artist)) || strings.Contains(simplify(artist), simplify(r.ArtistName)) {
			score += 3
		}
		if duration > 0 && r.Duration > 0 {
			d := r.Duration - duration
			if d < 0 {
				d = -d
			}
			switch {
			case d <= 3:
				score += 3
			case d > 20:
				score -= 4
			}
		}
		if r.SyncedLyrics != "" {
			score += 2
		}
		if score > bestScore {
			best, bestScore = r, score
		}
	}
	if bestScore < 5 { // Titel oder Künstler müssen schon passen
		return nil
	}
	return best
}

func lyricsText(r *lrclibResult) string {
	if r.SyncedLyrics != "" {
		return stripWordTags(r.SyncedLyrics)
	}
	return strings.TrimSpace(r.PlainLyrics)
}

// primaryArtist macht aus "A • B" bzw. "A feat. B" den Hauptkünstler für die Suche.
func primaryArtist(artist string) string {
	for _, sep := range []string{" • ", " feat. ", " ft. ", " & ", " x "} {
		if i := strings.Index(artist, sep); i > 0 {
			return strings.TrimSpace(artist[:i])
		}
	}
	return strings.TrimSpace(artist)
}

func simplify(s string) string {
	s = foldAccents.Replace(strings.ToLower(s))
	var b strings.Builder
	space := false
	for _, r := range s {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
			space = false
		default:
			if !space && b.Len() > 0 {
				b.WriteByte(' ')
				space = true
			}
		}
	}
	return strings.TrimSpace(b.String())
}

// ---- Deezer ----

type deezerArtist struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type deezerTrack struct {
	ID       int64        `json:"id"`
	Title    string       `json:"title"`
	Duration int          `json:"duration"`
	Artist   deezerArtist `json:"artist"`
	Album    struct {
		Title string `json:"title"`
	} `json:"album"`
}

func pickArtist(hits []deezerArtist, name string) *deezerArtist {
	want := simplify(name)
	for i := range hits {
		if simplify(hits[i].Name) == want {
			return &hits[i]
		}
	}
	return nil
}

// mixTracks verteilt die Radio-Titel so, dass nicht mehrere vom selben Künstler hintereinander kommen,
// und lässt die Ausgangstitel selbst weg.
func mixTracks(tracks []deezerTrack, skipTitle string, limit int) []deezerTrack {
	seen := map[string]bool{}
	pending := make([]deezerTrack, 0, len(tracks))
	for _, t := range tracks {
		k := simplify(t.Artist.Name) + "|" + simplify(t.Title)
		if seen[k] || (skipTitle != "" && simplify(t.Title) == simplify(skipTitle)) {
			continue
		}
		seen[k] = true
		pending = append(pending, t)
	}
	out := make([]deezerTrack, 0, limit)
	last := ""
	for len(pending) > 0 && len(out) < limit {
		idx := 0
		for i, t := range pending {
			if t.Artist.Name != last {
				idx = i
				break
			}
		}
		out = append(out, pending[idx])
		last = pending[idx].Artist.Name
		pending = append(pending[:idx], pending[idx+1:]...)
	}
	return out
}
