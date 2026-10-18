package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/navidrome/navidrome/plugins/pdk/go/host"
	"github.com/navidrome/navidrome/plugins/pdk/go/lifecycle"
	"github.com/navidrome/navidrome/plugins/pdk/go/pdk"
	"github.com/navidrome/navidrome/plugins/pdk/go/scheduler"
)

// Daylist und Mixe als echte Navidrome-Playlists – sichtbar in jeder App.
//
//   - Daylist: zu Beginn jedes Zeitfensters (5, 9, 12, 17, 21 Uhr) vom Helfer/Claude, gleicher Inhalt wie in NaviDroid.
//   - Mixe: jeden Morgen neu (Genre-Mixe, „Lieblinge“, „Entdecken“).
//
// Erkennungszeichen im Kommentar: "NaviDroid-Daylist" bzw. "NaviDroid-Mix". NaviDroid zeigt diese Mixe dann direkt an.

const (
	markDaylist = "NaviDroid-Daylist"
	markMix     = "NaviDroid-Mix"
)

func init() {
	lifecycle.Register(&plugin{})
	scheduler.Register(&plugin{})
}

var (
	_ lifecycle.InitProvider     = (*plugin)(nil)
	_ scheduler.CallbackProvider = (*plugin)(nil)
)

func (p *plugin) OnInit() error {
	if _, err := host.SchedulerScheduleRecurring("2 5,9,12,17,21 * * *", "daylist", "navidroid-daylist"); err != nil {
		pdk.Log(pdk.LogWarn, "Daylist-Zeitplan: "+err.Error())
	}
	if _, err := host.SchedulerScheduleRecurring("7 5 * * *", "mixes", "navidroid-mixes"); err != nil {
		pdk.Log(pdk.LogWarn, "Mix-Zeitplan: "+err.Error())
	}
	// Einmal kurz nach dem Start, damit die Playlists nicht erst am nächsten Morgen da sind
	_, _ = host.SchedulerScheduleOneTime(45, "all", "navidroid-start")
	return nil
}

func (p *plugin) OnCallback(req scheduler.SchedulerCallbackRequest) error {
	// Navidrome gibt jedem Aufruf nur ~30 Sekunden: Arbeit in getrennte Aufrufe aufteilen.
	if req.Payload == "all" {
		_, _ = host.SchedulerScheduleOneTime(1, "mixes", "navidroid-mixes-now")
		_, _ = host.SchedulerScheduleOneTime(5, "daylist", "navidroid-daylist-now")
		return nil
	}
	users, err := host.UsersGetUsers()
	if err != nil {
		return err
	}
	attempt := 0
	if strings.HasPrefix(req.Payload, "daylist-retry-") {
		fmt.Sscanf(strings.TrimPrefix(req.Payload, "daylist-retry-"), "%d", &attempt)
	}
	pending := false
	for _, u := range users {
		switch {
		case req.Payload == "mixes":
			if n, err := updateMixes(u.UserName); err != nil {
				pdk.Log(pdk.LogWarn, fmt.Sprintf("Mixe für %s: %v", u.UserName, err))
			} else {
				pdk.Log(pdk.LogInfo, fmt.Sprintf("Mixe für %s aktualisiert: %d Playlists", u.UserName, n))
			}
		case strings.HasPrefix(req.Payload, "daylist"):
			waiting, err := updateDaylist(u.UserName)
			if err != nil {
				pdk.Log(pdk.LogWarn, fmt.Sprintf("Daylist für %s: %v", u.UserName, err))
			}
			pending = pending || waiting
		}
	}
	// Claude stellt die Daylist im Helfer noch zusammen: später erneut abholen (höchstens 5-mal)
	if pending && attempt < 5 {
		_, _ = host.SchedulerScheduleOneTime(90, fmt.Sprintf("daylist-retry-%d", attempt+1), fmt.Sprintf("navidroid-daylist-retry-%d", attempt+1))
	}
	return nil
}

// ---- Subsonic ----

func subsonic(endpoint string, user string, params url.Values) (map[string]json.RawMessage, error) {
	if params == nil {
		params = url.Values{}
	}
	params.Set("u", user)
	params.Set("f", "json")
	raw, err := host.SubsonicAPICall(endpoint + "?" + params.Encode())
	if err != nil {
		return nil, err
	}
	var env struct {
		Resp map[string]json.RawMessage `json:"subsonic-response"`
	}
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		return nil, err
	}
	var status string
	_ = json.Unmarshal(env.Resp["status"], &status)
	if status != "ok" {
		return nil, fmt.Errorf("%s: %s", endpoint, string(env.Resp["error"]))
	}
	return env.Resp, nil
}

func librarySongs(user string) ([]libSong, error) {
	var all []libSong
	for offset := 0; offset < 20000; offset += 500 {
		resp, err := subsonic("search3", user, url.Values{
			"query": {""}, "artistCount": {"0"}, "albumCount": {"0"},
			"songCount": {"500"}, "songOffset": {fmt.Sprint(offset)},
		})
		if err != nil {
			return nil, err
		}
		var res struct {
			Song []libSong `json:"song"`
		}
		_ = json.Unmarshal(resp["searchResult3"], &res)
		all = append(all, res.Song...)
		if len(res.Song) < 500 {
			break
		}
	}
	return all, nil
}

type playlistInfo struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Comment string `json:"comment"`
	Owner   string `json:"owner"`
}

func ownPlaylists(user string) ([]playlistInfo, error) {
	resp, err := subsonic("getPlaylists", user, nil)
	if err != nil {
		return nil, err
	}
	var res struct {
		Playlist []playlistInfo `json:"playlist"`
	}
	_ = json.Unmarshal(resp["playlists"], &res)
	var mine []playlistInfo
	for _, p := range res.Playlist {
		if strings.EqualFold(p.Owner, user) {
			mine = append(mine, p)
		}
	}
	return mine, nil
}

// writePlaylist legt die Playlist an oder ersetzt Titel, Name und Kommentar einer bestehenden.
func writePlaylist(user, existingID, name, comment string, songIDs []string) (string, error) {
	params := url.Values{}
	if existingID != "" {
		params.Set("playlistId", existingID)
	} else {
		params.Set("name", name)
	}
	for _, id := range songIDs {
		params.Add("songId", id)
	}
	resp, err := subsonic("createPlaylist", user, params)
	if err != nil {
		return "", err
	}
	id := existingID
	if id == "" {
		var pl playlistInfo
		_ = json.Unmarshal(resp["playlist"], &pl)
		id = pl.ID
	}
	if id == "" {
		return "", errors.New("keine Playlist-ID")
	}
	_, err = subsonic("updatePlaylist", user, url.Values{"playlistId": {id}, "name": {name}, "comment": {comment}, "public": {"false"}})
	return id, err
}

func deletePlaylist(user, id string) {
	_, _ = subsonic("deletePlaylist", user, url.Values{"id": {id}})
}

// ---- Helfer ----

func helperConfig() (string, string, bool) {
	base, ok1 := host.ConfigGet("helperUrl")
	token, ok2 := host.ConfigGet("helperToken")
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	return base, strings.TrimSpace(token), ok1 && ok2 && base != "" && token != ""
}

func helperCall(method, path string, body any, timeoutMs int32, out any) error {
	base, token, ok := helperConfig()
	if !ok {
		return errors.New("Helfer nicht eingerichtet (helperUrl/helperToken)")
	}
	var data []byte
	if body != nil {
		var err error
		if data, err = json.Marshal(body); err != nil {
			return err
		}
	}
	resp, err := host.HTTPSend(host.HTTPRequest{
		Method:    method,
		URL:       base + path,
		Headers:   map[string]string{"X-Helper-Token": token, "Content-Type": "application/json", "User-Agent": userAgent},
		Body:      data,
		TimeoutMs: timeoutMs,
	})
	if err != nil {
		return err
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("Helfer HTTP %d: %s", resp.StatusCode, string(bytes.TrimSpace(resp.Body[:min(len(resp.Body), 200)])))
	}
	return json.Unmarshal(resp.Body, out)
}

// ---- Mixe ----

func updateMixes(user string) (int, error) {
	songs, err := librarySongs(user)
	if err != nil {
		return 0, err
	}
	genres := map[string][]string{}
	hasTags := false
	for _, s := range songs {
		if s.Genre != "" {
			hasTags = true
			break
		}
	}
	if !hasTags {
		if err := helperCall("GET", "/api/plugin/artist-genres", nil, 30000, &genres); err != nil {
			pdk.Log(pdk.LogInfo, "Künstler-Genres nicht verfügbar: "+err.Error())
		}
	}
	day := time.Now().Unix() / 86400
	mixes := buildMixes(songs, day, genres)

	existing, err := ownPlaylists(user)
	if err != nil {
		return 0, err
	}
	byName := map[string]playlistInfo{}
	for _, p := range existing {
		if strings.HasPrefix(p.Comment, markMix) {
			byName[p.Name] = p
		}
	}
	keep := map[string]bool{}
	for _, m := range mixes {
		ids := make([]string, 0, len(m.Songs))
		for _, s := range m.Songs {
			ids = append(ids, s.ID)
		}
		prev := byName[m.Name]
		comment := markMix + " · täglich neu · " + m.Description
		id, err := writePlaylist(user, prev.ID, m.Name, comment, ids)
		if err != nil {
			pdk.Log(pdk.LogWarn, fmt.Sprintf("Mix %s: %v", m.Name, err))
			continue
		}
		keep[id] = true
	}
	// Mixe von gestern, die es heute nicht mehr gibt, wieder entfernen
	for _, p := range byName {
		if !keep[p.ID] {
			deletePlaylist(user, p.ID)
		}
	}
	return len(keep), nil
}

// ---- Daylist ----

type daylistResponse struct {
	Name        string    `json:"name"`
	Description string    `json:"description"`
	Tags        []string  `json:"tags"`
	Tracks      []libSong `json:"tracks"`
	SlotKey     string    `json:"slotKey"`
	End         string    `json:"end"`
	Pending     bool      `json:"pending"`
}

// updateDaylist liefert true, solange der Helfer die Daylist noch erstellt.
func updateDaylist(user string) (bool, error) {
	if _, _, ok := helperConfig(); !ok {
		return false, nil // ohne Helfer keine Daylist
	}
	existing, err := ownPlaylists(user)
	if err != nil {
		return false, err
	}
	var current []playlistInfo
	for _, p := range existing {
		if strings.HasPrefix(p.Comment, markDaylist) {
			current = append(current, p)
		}
	}
	songs, err := librarySongs(user)
	if err != nil {
		return false, err
	}
	var d daylistResponse
	body := map[string]any{"user": user, "songs": songs, "count": 40}
	if err := helperCall("POST", "/api/plugin/daylist", body, 20000, &d); err != nil {
		return false, err
	}
	if d.Pending {
		return true, nil
	}
	if len(d.Tracks) == 0 {
		return false, errors.New("leere Daylist")
	}
	marker := markDaylist + " " + d.SlotKey
	if len(current) == 1 && strings.HasPrefix(current[0].Comment, marker) {
		return false, nil // schon aktuell
	}
	ids := make([]string, 0, len(d.Tracks))
	for _, t := range d.Tracks {
		ids = append(ids, t.ID)
	}
	prevID := ""
	for i, p := range current {
		if i == 0 {
			prevID = p.ID
		} else {
			deletePlaylist(user, p.ID) // es soll nur eine Daylist geben
		}
	}
	tags := ""
	for _, t := range d.Tags {
		tags += " #" + t
	}
	comment := fmt.Sprintf("%s · bis %s · %s%s", marker, d.End, d.Description, tags)
	if _, err := writePlaylist(user, prevID, "Daylist · "+d.Name, comment, ids); err != nil {
		return false, err
	}
	pdk.Log(pdk.LogInfo, fmt.Sprintf("Daylist für %s: %s", user, d.Name))
	return false, nil
}
