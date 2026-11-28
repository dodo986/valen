# Valen — TODO

## P1 — Go All In
- [x] Fix Android's Spotify — Revera's sign-in relay ported and verified (2026-11-28; see HANDOFF.md).
- [ ] Protect Spotify playback from DMCA (still waiting for Spotify to open source).
- [ ] Device sync: One profile across Android and desktop.
- [ ] [NoteBurner](https://www.noteburner.com/en1/) as a playback source (said to have a HUGE database).
- [ ] Supabase backend + online accounts (? maybe), optional for sure.
- [ ] Fix the bottom island.
- [ ] Floating lyrics like Mochy.
- [ ] An anime mascot.
- [x] Search bar for the playlist import page.
- [x] Fix some songs skipping.
- [ ] A better lyrics API, or fix Lyperia drifting.
- [ ] Better UI/UX and much cooler animations.
- [ ] Ad blocker for Spotify (maybe with a Go daemon?).
- [ ] Look for bugs in the backend.
- [x] Discord RPC settings.
- [x] Youtube playlist imports limited to 100 tracks.
    
- [x] Fix the algorithm throwing random slop into the queue.
  - Near-rewrite
  - Known limits: a noisy related list can still name a genuinely unrelated real artist (now only
    one whose page returns songs and which is corroborated or top-ranked); the already-saved log
    keeps its old listening as "real" (a generated mix played before this change is not marked);
    "Less like this" and "Don't recommend this song" in the song menu are the manual
    fixes
