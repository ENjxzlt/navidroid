package main

import "testing"

func TestStripWordTags(t *testing.T) {
	in := "[00:12.30] <00:12.30>Hello <00:12.80>world\n[00:15.00]Second line"
	want := "[00:12.30] Hello world\n[00:15.00]Second line"
	if got := stripWordTags(in); got != want {
		t.Fatalf("got %q", got)
	}
}

func TestBestLyricsPrefersMatchingSynced(t *testing.T) {
	res := []lrclibResult{
		{TrackName: "Ivy", ArtistName: "Someone Else", Duration: 249, SyncedLyrics: "[00:01.00]x"},
		{TrackName: "Ivy", ArtistName: "Frank Ocean", Duration: 249, PlainLyrics: "plain"},
		{TrackName: "Ivy", ArtistName: "Frank Ocean", Duration: 250, SyncedLyrics: "[00:01.00]synced"},
		{TrackName: "Ivy (Live)", ArtistName: "Frank Ocean", Duration: 400, SyncedLyrics: "[00:01.00]live"},
	}
	got := bestLyrics(res, "Ivy", "Frank Ocean", 249)
	if got == nil || got.SyncedLyrics != "[00:01.00]synced" {
		t.Fatalf("got %+v", got)
	}
	if bestLyrics([]lrclibResult{{TrackName: "Other", ArtistName: "Nobody", SyncedLyrics: "x"}}, "Ivy", "Frank Ocean", 0) != nil {
		t.Fatal("unpassender Treffer sollte verworfen werden")
	}
	if bestLyrics([]lrclibResult{{TrackName: "Ivy", ArtistName: "Frank Ocean", Instrumental: true}}, "Ivy", "Frank Ocean", 0) != nil {
		t.Fatal("instrumental ohne Text sollte verworfen werden")
	}
}

func TestPrimaryArtistAndSimplify(t *testing.T) {
	if got := primaryArtist("BIBIZA • Ikkimel"); got != "BIBIZA" {
		t.Fatal(got)
	}
	if got := primaryArtist("Tyler, The Creator feat. Kali Uchis"); got != "Tyler, The Creator" {
		t.Fatal(got)
	}
	if simplify("Beyoncé – Halo!") != "beyonce halo" {
		t.Fatal(simplify("Beyoncé – Halo!"))
	}
}

func TestMixTracks(t *testing.T) {
	tr := func(a, title string) deezerTrack {
		x := deezerTrack{Title: title}
		x.Artist.Name = a
		return x
	}
	in := []deezerTrack{tr("A", "1"), tr("A", "2"), tr("B", "3"), tr("A", "1"), tr("C", "Seed")}
	out := mixTracks(in, "seed", 10)
	if len(out) != 3 {
		t.Fatalf("len %d", len(out))
	}
	if out[0].Artist.Name == out[1].Artist.Name {
		t.Fatal("gleicher Künstler hintereinander")
	}
}

func TestBuildMixes(t *testing.T) {
	var songs []libSong
	for i := 0; i < 60; i++ {
		g := "Indie"
		if i >= 40 {
			g = "Jazz"
		}
		songs = append(songs, libSong{ID: string(rune('a'+i%26)) + string(rune('A'+i/26)), Title: "T", Artist: "A" + string(rune('0'+i%7)), Genre: g})
	}
	m := buildMixes(songs, 20000, nil)
	if len(m) < 2 || m[0].Name != "Indie Mix" {
		t.Fatalf("%+v", m)
	}
	if len(m[0].Songs) != 30 {
		t.Fatalf("len %d", len(m[0].Songs))
	}
	again := buildMixes(songs, 20000, nil)
	if again[0].Songs[0].ID != m[0].Songs[0].ID {
		t.Fatal("gleicher Tag sollte gleichen Mix ergeben")
	}
	last := m[len(m)-1]
	if last.ID != "discover" {
		t.Fatalf("Entdecken fehlt: %s", last.ID)
	}
}
