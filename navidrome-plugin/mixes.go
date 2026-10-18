package main

import (
	"hash/fnv"
	"math/rand"
	"sort"
	"strings"
)

// libSong ist ein Titel aus search3 (nur die Felder, die Mixe und Daylist brauchen).
type libSong struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Artist    string `json:"artist,omitempty"`
	Album     string `json:"album,omitempty"`
	AlbumID   string `json:"albumId,omitempty"`
	CoverArt  string `json:"coverArt,omitempty"`
	Duration  int    `json:"duration,omitempty"`
	Year      int    `json:"year,omitempty"`
	Genre     string `json:"genre,omitempty"`
	PlayCount int64  `json:"playCount,omitempty"`
}

type mix struct {
	ID          string
	Name        string
	Description string
	Songs       []libSong
}

// genresOf: Genre-Tags der Datei, sonst die Genres des Künstlers (aus Lidarr über den Helfer).
func genresOf(s libSong, artistGenres map[string][]string) []string {
	var out []string
	for _, g := range strings.FieldsFunc(s.Genre, func(r rune) bool { return r == ';' || r == '/' || r == ',' }) {
		if g = strings.TrimSpace(g); g != "" {
			out = append(out, g)
		}
	}
	if len(out) > 0 {
		return out
	}
	a := strings.ToLower(s.Artist)
	if g, ok := artistGenres[a]; ok {
		return g
	}
	for k, g := range artistGenres {
		if k != "" && strings.HasPrefix(a, k) {
			return g
		}
	}
	return nil
}

func capitalize(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	return strings.ToUpper(string(r[0])) + string(r[1:])
}

func seedFor(day int64, key string) int64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(key))
	return day*31 + int64(h.Sum64()>>1)
}

func spreadSongs(list []libSong) []libSong {
	out := make([]libSong, 0, len(list))
	pending := append([]libSong(nil), list...)
	for len(pending) > 0 {
		idx := 0
		last := ""
		if len(out) > 0 {
			last = out[len(out)-1].Artist
		}
		for i, s := range pending {
			if s.Artist != last {
				idx = i
				break
			}
		}
		out = append(out, pending[idx])
		pending = append(pending[:idx], pending[idx+1:]...)
	}
	return out
}

func shuffled(list []libSong, rnd *rand.Rand) []libSong {
	out := append([]libSong(nil), list...)
	rnd.Shuffle(len(out), func(i, j int) { out[i], out[j] = out[j], out[i] })
	return out
}

// pick wählt bis zu [size] Titel: Lieblinge und Unbekanntes gemischt, höchstens ein Drittel pro Künstler.
func pick(pool []libSong, day int64, key string, size int) []libSong {
	rnd := rand.New(rand.NewSource(seedFor(day, key)))
	artists := map[string]bool{}
	for _, s := range pool {
		artists[s.Artist] = true
	}
	n := len(artists)
	if n == 0 {
		n = 1
	}
	capN := size / 3
	if capN < 3 {
		capN = 3
	}
	if c := (size + n - 1) / n; c > capN {
		capN = c
	}
	byPlays := append([]libSong(nil), pool...)
	sort.SliceStable(byPlays, func(i, j int) bool { return byPlays[i].PlayCount > byPlays[j].PlayCount })
	if len(byPlays) > size {
		byPlays = byPlays[:size]
	}
	ordered := append(shuffled(byPlays, rnd), shuffled(pool, rnd)...)
	per := map[string]int{}
	seen := map[string]bool{}
	var chosen []libSong
	for _, s := range ordered {
		if len(chosen) >= size {
			break
		}
		if seen[s.ID] || per[s.Artist] >= capN {
			continue
		}
		seen[s.ID] = true
		per[s.Artist]++
		chosen = append(chosen, s)
	}
	return spreadSongs(shuffled(chosen, rnd))
}

func artistsLine(songs []libSong) string {
	counts := map[string]int{}
	var order []string
	for _, s := range songs {
		if s.Artist == "" {
			continue
		}
		if counts[s.Artist] == 0 {
			order = append(order, s.Artist)
		}
		counts[s.Artist]++
	}
	sort.SliceStable(order, func(i, j int) bool { return counts[order[i]] > counts[order[j]] })
	if len(order) <= 3 {
		return strings.Join(order, ", ")
	}
	return strings.Join(order[:3], ", ") + " und mehr"
}

// buildMixes: bis zu 5 Genre-Mixe mit möglichst unterschiedlichen Künstlern, dazu „Lieblinge“ und „Entdecken“.
func buildMixes(songs []libSong, day int64, artistGenres map[string][]string) []mix {
	if len(songs) == 0 {
		return nil
	}
	byGenre := map[string][]libSong{}
	for _, s := range songs {
		for _, g := range genresOf(s, artistGenres) {
			k := capitalize(g)
			byGenre[k] = append(byGenre[k], s)
		}
	}
	type entry struct {
		genre string
		pool  []libSong
	}
	var candidates []entry
	for g, pool := range byGenre {
		if len(pool) >= 8 {
			candidates = append(candidates, entry{g, pool})
		}
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		if len(candidates[i].pool) != len(candidates[j].pool) {
			return len(candidates[i].pool) > len(candidates[j].pool)
		}
		return candidates[i].genre < candidates[j].genre
	})
	covered := map[string]bool{}
	var chosen []entry
	for _, c := range candidates {
		artists := map[string]bool{}
		for _, s := range c.pool {
			if s.Artist != "" {
				artists[strings.ToLower(s.Artist)] = true
			}
		}
		fresh := 0
		for a := range artists {
			if !covered[a] {
				fresh++
			}
		}
		if len(chosen) == 0 || (len(artists) > 0 && float64(fresh)/float64(len(artists)) >= 0.4) {
			chosen = append(chosen, c)
			for a := range artists {
				covered[a] = true
			}
		}
		if len(chosen) == 5 {
			break
		}
	}
	var out []mix
	for _, c := range chosen {
		picked := pick(c.pool, day, c.genre, 30)
		out = append(out, mix{ID: "genre-" + strings.ToLower(c.genre), Name: c.genre + " Mix", Description: artistsLine(picked), Songs: picked})
	}
	var played, unplayed []libSong
	for _, s := range songs {
		if s.PlayCount > 0 {
			played = append(played, s)
		} else {
			unplayed = append(unplayed, s)
		}
	}
	if len(played) >= 10 {
		sort.SliceStable(played, func(i, j int) bool { return played[i].PlayCount > played[j].PlayCount })
		top := played
		if len(top) > 40 {
			top = top[:40]
		}
		top = shuffled(top, rand.New(rand.NewSource(day)))
		if len(top) > 30 {
			top = top[:30]
		}
		out = append(out, mix{ID: "favorites", Name: "Lieblinge", Description: "Was du am häufigsten hörst", Songs: spreadSongs(top)})
	}
	if len(unplayed) >= 10 {
		d := shuffled(unplayed, rand.New(rand.NewSource(day+7)))
		if len(d) > 30 {
			d = d[:30]
		}
		out = append(out, mix{ID: "discover", Name: "Entdecken", Description: "Titel aus deiner Sammlung, die du noch nie gehört hast", Songs: spreadSongs(d)})
	}
	return out
}
