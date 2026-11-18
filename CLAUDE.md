# Photos Frame — everything a new session needs

The owner has already explained all of this once. **Do not ask him to repeat it.** Read this file,
README.md and INSTALL.md, then carry on. Ask only about things that are genuinely new.

## Who and what
- **The owner** owns the project and the TV. His assistant **the owner's assistant** sometimes drives sessions for him.
  the owner's wife is **the owner's partner**; they use Google Photos Partner Sharing so either phone can take trip photos.
- **TV:** TCL 85" A300W (NXTFRAME), Google TV, ~64 GB storage (~50 GB free for apps).
  Not OLED. TV IP address: **<TV-IP>** (Wi-Fi "<home Wi-Fi>"; Cast name "<Cast name>", adb product
  `<adb product>`, model "Smart TV"; ~46 GB free). The owner's PC is <PC-IP> (also on Wi-Fi).
- **Goal:** a personal (sideloaded, not Play Store) Google TV app that shows the owner's own Google Photos
  like a framed picture on the wall, also usable as the TV's screensaver, with new photos easy to add.
- **How the owner likes instructions:** plain, numbered, step-by-step, "as if for a 10-year-old". Short status
  reports: what's done, what isn't, what he needs to do. Don't make him repeat himself.

## Look — keep exactly unless the owner asks
- **Default look: Full screen** (the owner's choice 2026-11-17, v0.7): photos fill the whole TV edge to edge
  (cover-cropped). Portraits: **Flush Triptych** (the owner's pick from the Fable designer's options,
  2026-11-18, v0.8): three tall phone portraits shown whole, full height, outer ones touching the screen
  edges, separated by **paper-tone bands** (#D4CEC3 tinted 18% toward the photos' average colour; dark
  tones look muddy on the matte screen). PhotoSource.build pulls 3 tall portraits from the whole queue
  (all of the owner's portraits are 9:16, ~1 in 12 photos). Fallbacks: 2 portraits centred on paper; a lone
  portrait or a panorama shown whole on paper. FrameRenderer.drawFull.
- **Look: Framed** (Settings option): off-white bevelled matte, dark walnut frame, charcoal wall,
  soft shadow, one photo at a time (FrameRenderer.draw).
- One slide at a time; change every 10 minutes by default (Settings page: 30 s, 1/5/10/30/60 min;
  saved as seconds in pref "interval_s", migrated once from the old index pref "interval");
  slow 4-second fade (1.2 s when the remote asks for next/previous); a few pixels of drift per change.
- **The TV is a matte "frame" art TV** (TCL A300W NXTFRAME: ultra-matte anti-glare QLED, modest
  brightness, off-white bezel). Design for print-like presentation; near-black areas read as muddy grey.
- **Print filter** (v0.8, designed by the Fable art-direction agent; spec in docs/print_filter_spec.md;
  ArtTreatment.java): tone baked once per slide (saturation, then a per-channel curve ending at a warm
  paper white and an ink black, mid-tone contrast kept); surface texture drawn live over the whole frame
  with OVERLAY (Settings: **Print surface** Paper or Canvas); faint top-to-bottom gallery light (no radial
  vignette). Levels Off/Light/Moderate/Strong/Extra strong/Maximum (0/0.25/0.5/1/1.5/2). Past Strong
  (the owner asked for stronger, v0.10) saturation, ink black and texture keep rising but paper white falls
  only 0.06 per unit and the gallery light is capped at 10%, because the matte panel isn't bright.
  Default Moderate. The owner's own settings change often (last seen 2026-11-18: 1 minute, Strong, Paper,
  Full screen): **read them on screen (▲) before testing and put back exactly what was there**.
  the owner explicitly rejected Google's vivid full-screen Ambient Mode look.
- **Photo order** (v0.11, PhotoLibrary.java, state in files/library/): the shuffle and its position are
  saved, so restarts continue the round (every photo once before repeats). **Bursts**: photos taken within
  5 s of a burst's first photo (EXIF DateTimeOriginal, anchored, not chained) count as one; each round shows
  one member, rotating. Measured on the owner's 1,990: 20 s chained left only 434, 5 s anchored leaves 1,092.
  **Order**: Shuffle or "One day at a time" (the owner's name; days in random order, each day in time order,
  triptychs from the same day). **Play**: All photos / Favourites only (falls back to all if none).
  **Hold OK** = photo menu (Favourite toggle, Hide; ◀▶ picks left/middle/right with an outline when 2-3
  photos show). Settings → Hidden photos: OK shows them again. Dropped by the owner: "On this day".
  New photos join the current round if downloaded after it began (file mtime vs round start).
  Known: the slide prepared ahead counts as shown, so one photo can be skipped per restart.
- Remote (the owner's choice, 2026-11-17): ▶/OK = next photo, ◀ = previous photo (history of 100),
  ▲ = on-TV Settings page (SettingsPanel.java: photo time, print filter, print surface, look, Add photos;
  ▲▼ pick, ◀▶ change, Back closes), ▼/Menu = Add photos, Back/Home = leave. OK acts on release;
  holding it opens the photo menu (`adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` tests it).

## Decisions already made (don't re-open without the owner)
- **Photo source: Google Photos Picker API only** (scope `photospicker.mediaitems.readonly`).
  - Since March 2025 Google blocks apps from reading the library or shared albums. Shared-album / "Wall"
    album and phone-helper ideas were considered and dropped.
  - The picker has no Albums tab / whole-album select; max 2,000 items per pick. Re-picking adds to the
    photos already on the TV (deduped by item id).
  - Partner-sharing photos are pickable once saved into the owner's library (Partner sharing → Save to your
    account → All photos). Live-album auto-add of partner photos is undocumented — build nothing on it.
- **Sign-in:** Google rejects the Picker scope in the TV device-code flow (`invalid_scope`, tested with a
  "TVs and Limited Input devices" client). So the app uses the **Desktop** OAuth client + PKCE, done from
  the phone: TV shows a QR → its local page (SetupServer.java, port 8080) → "Sign in with Google" →
  phone lands on http://127.0.0.1:8765/?code=… (won't load) → user pastes that address back → TV signs in.
- **Downloads:** `baseUrl=w3840-h2160` (≈3837×2160, ~5–15 MB each). Target ≥ 2,000 photos.
  Warn on screen below 5 GB free; stop adding below a 2 GB reserve; always keep rotating cached photos.
- **No ambient-light-sensor dimming.** Considered and declined by the owner; brightness is set by the art
  filter level (▲ Settings) and the TV's own picture settings. Don't propose it again.
- **Updates to the TV:** adb over Wi-Fi via tools/update_tv.sh / update_tv.bat (remembers TV IP in
  tools/tv_ip.txt). A session running on the owner's PC can run these directly.

## Google Cloud
- Project **<your Cloud project>**, account <owner account>. OAuth consent screen name "Photos Frame",
  External, the owner is a test user. Photos Picker API enabled (free, no billing).
- Clients: **Desktop** client (<desktop-client-id>) ← the one the app uses.
  "Photos Frame TV" (limited-input) client exists but is unusable for Photos; can be ignored/deleted.
- Consent screen is (as far as we know) still in **Testing** → sign-ins expire after 7 days.
  Recommended: Google Auth Platform → Audience → **Publish app** (see INSTALL.md step 5).

## Private files (gitignored — never commit, never put in a public place)
- `app/src/main/assets/oauth_client.json` = the owner's Desktop `client_secret.json`. Needed to build.
- `app/src/main/assets/photos/` = optional placeholder photos (shown until real ones are picked).
- Built APKs contain the key (and any placeholder photos): keep them private.

## Build
`gradle assembleRelease` (JDK 17+, Android SDK platform 34 + build-tools 34, Gradle 8.14, AGP 8.7.3,
`sdk.dir` in local.properties). Output: app/build/outputs/apk/release/app-release.apk, signed with the
local debug key so it updates the installed app in place. **Use the release build on the TV**: the
debuggable build runs the per-pixel tone loop several times slower on the TV's weak 32-bit CPU.
Side effect: `adb shell run-as` no longer works, so check settings on screen (▲) instead of reading prefs.
tools/preview.py still mirrors the OLD filter (pre-v0.8); the new filter's reference is docs/print_filter_spec.md.

## Performance (TV CPU: 32-bit, 4 cores, ~2.4 GB RAM), learned 2026-11-18
- Never do decode/filter/layout on the UI thread. FrameView does all of it on the "slides" HandlerThread,
  composes each slide into one 1920x1080 bitmap, and prepares the next slide ahead (PhotoSource.peekNext).
  onDraw only blends two bitmaps + one OVERLAY texture rect + a gradient (~7 ms frames with the filter
  Off, ~32 ms with it on).
- Photos are stored at 3837x2160; always decode scaled to the target (PhotoSource.load uses inSampleSize +
  inDensity/inTargetDensity). A power-of-2-only check left them at full 4K and made everything 4x slower.
- Measured per slide (release build): decode ~200 ms, tone bake ~340 ms (4 threads), layout ~70 ms.

## Status / next steps (update this list as things change)
Current version: **v0.13** (versionCode 13). Everything is on GitHub `main`.
- [x] v0.4: frame look, art treatment, screensaver, phone sign-in, picker, 4K download + cache,
      storage warnings, INSTALL.md, update scripts.
- [x] v0.5: art filter level on the remote (◀: Off/Light/Moderate/Strong, saved, used by screensaver too).
- [x] v0.6: ◀/▶ previous/next photo; ▲ Settings page with photo time (1/5/10/30/60 min) and art filter.
- [x] v0.7: full-screen look with side-by-side portraits (default); Framed kept as a Settings "Look" option.
- [x] v0.8 (2026-11-18): Flush Triptych portraits with paper bands; new print filter (Paper/Canvas
      surface); app no longer sluggish (background slide preparation, decode at screen size, release
      build). Checked on the TV: triptych slides, filter Moderate/Strong/Canvas renders, frame timings,
      the owner's settings restored afterwards (5 min, filter Off, Paper, Full screen).
      Checked on the TV: 12 slides (landscapes full-bleed, a 3-portrait slide), Look toggle both ways, ◀ history.
- [x] TV prep done by the owner (photos checked): Developer options ON, USB debugging ON. This TV's
      Debugging section has **no separate Wireless/Network debugging toggle** — try `adb connect IP:5555`
      (Android TV normally listens there when USB debugging is on). Leave everything else at defaults;
      keep "Animator duration scale" at 1x (the 4-s fade depends on it) and "Stay awake" OFF (it would
      stop the screensaver starting). If Play Protect ("Verify apps over USB") blocks the install,
      choose "Install anyway" or turn that one toggle off.
- [x] First install on the real TV (2026-11-17, from the owner's PC over adb Wi-Fi at <TV-IP>:5555).
      Verified on the TV: app opens (empty charcoal wall), ▼ opens sign-in, sign-in completed
      **from the PC browser**, picker QR worked on the phone, ~1,990 photos downloading at ~110/min
      (~1.2 MB each, not 5-15 MB). Screensaver set via `screensaver_components` (previous value was
      `com.google.android.apps.tv.dreamx/.service.Backdrop`).
      Fixed on the way: AddPhotosActivity spacers swallowed the screen (instructions + address were
      invisible); SetupServer was single-threaded, so a browser's idle preconnect socket blocked the page
      (reproduced from the PC; now one thread per connection, and each request is logged under tag
      `SetupServer` with the auth code redacted).
- [ ] **Open bug: the owner's phone (Chrome) can't open http://<TV-IP>:8080/**
      (`ERR_TOO_MANY_RETRIES`), though the PC on the same Wi-Fi loads it and the TV can ping the phone
      (likely <PC-IP>). No phone request has been seen in the `SetupServer` log yet, so it's unknown
      whether the request reaches the TV. Next: have the owner scan once while `adb logcat SetupServer:I *:S`
      runs; try Samsung Internet; check phone VPN / Private DNS / "secure Wi-Fi". Workaround: sign in from
      the PC browser.
- [x] v0.6 on the TV (2026-11-17), all 1,990 photos downloaded (~2.3 GB). Checked with adb key presses +
      screenshots: ▶ next, ◀ back to the same photo, ▲ Settings card (▲▼ focus, ◀▶ change, live filter,
      Back closes without leaving the app), setting saved in shared_prefs/settings.xml; screensaver
      started via `am start -n com.android.systemui/.Somnambulator` shows the frame
      (dumpsys dreams: FrameDreamService).
- [ ] Still to test: timed rotation over a real interval, screensaver starting by itself after idle,
      update_tv script; the owner to try the remote himself.
- [x] v0.9 (2026-11-18): **art button on the remote opens Photos Frame** (ArtKeyService, an
      accessibility service that filters only TCL key 4181 / scan 507; TCL's system otherwise sends it to
      T-Exhibition via TclShortCutKeyManager.startExhibit, with no setting to change that; searched
      system/secure/global settings, getprop and TCL's config provider). **The owner switched it on himself** in
      Settings → System → Accessibility (don't enable accessibility services from adb). Verified with a real
      press: Photos Frame opened. NB: `adb shell input keyevent` presses skip accessibility filters, so
      only real remote presses can test it.
- Screensaver: already Photos Frame. The home-screen "Screensaver" button and Ambient mode start it too
  (logged: DreamController starting FrameDreamService). With the filter Off and full-screen look it looks
  like Google's photo screensaver, which caused confusion. Google's Ambient mode menu doesn't list it;
  picking an option there may reset `screensaver_components` (re-run the update script if so).
- [x] v0.10 (2026-11-18): print filter Extra strong + Maximum; 30-second photo interval. Checked on the TV:
      the owner's old interval carried over, all three strong levels rendered, settings restored.
- [x] v0.11 (2026-11-18): saved shuffle across restarts, burst thinning, One day at a time, favourites +
      Favourites only, hide/unhide. Checked on the TV with logs and screenshots; test favourites/hide undone.
      Not yet tested on the TV: choosing left/middle/right in the photo menu on a three-photo screen.
      Testing tip: with a 30 s interval the photo changes mid-test; use Play → Favourites only to undo test favourites.
- [x] v0.12 (2026-11-18): One day at a time can play a chosen day (Settings → Day, shown only in that
      order; pref "day" = yyyy:MM:dd or ""). Checked on the TV: Sat 10 Oct, 304 photos, 175 screens after bursts.
- [x] v0.13 (2026-11-18): **Trips** (Trips.java + PhotoLibrary): found from capture dates (>2 empty days =
      new trip), names/last-played/cleared remembered per date range in files/library/trips.tsv (best
      overlap, 1 day slack because picker dates are UTC). Naming while downloading (AddPhotosActivity:
      trips computed from picker createTime before download, one screen per unnamed trip with 4 previews,
      TV keyboard/mic, Back = keep date name; already-named trips get new photos silently). Rename later:
      photo menu "Name this trip" or Settings → Trip (hold OK). Settings → Trip chooser (TripPanel: All, or
      tick several = mixed; pref "trips" = "start~end;..."). Settings → Space for photos 5-30 GB (default 20);
      download clears the least-recently-played trip (never the trip being added or chosen ones) and lists
      it as "not on the TV". Downloads now "=w1920-h1440" (frame size); Shrinker re-saves old 4K photos once
      (date self-check, marker files/library/shrunk.done). Checked on the TV: first-trip = "Oct 2026 · 7 days",
      tick/rename/restore, voice keyboard appears. NOT yet tested: naming screen during a real pick, multi-
      trip picks, eviction (needs >20 GB), final Shrinker totals.
- Installing an update restarts the app on the TV (the screen briefly shows something else): tell the owner first.
- [ ] Publish the consent screen so sign-ins don't expire weekly.
- [x] TV IP recorded above and in tools/tv_ip.txt (gitignored; local to the PC).
- Possible later: in-app "check for updates"; tuning art strength / matte size / timing after the owner
  lives with it for a few days.

## Trips design (2026-11-18), awaiting the owner's answers — docs/trip_collections_design.md
Goal: play any trip (Egypt 2025, Japan 2016, compare two Tokyo trips) without much TV storage. Fable
engineering-design agent's recommendation: Phase 1 trips on the TV (store at 1080p "frame size", named
trips, Settings → Trip, two-trip alternate, storage budget with eviction); Phase 2 PC trip loader (album
download/Takeout → PC shrinks → adb push); live PC streaming and Takeout→Drive rejected. Verified: Picker
search by keywords/dates/places/album titles (no album tab, no select-whole-album); 1080p size measured.
Follow-ups (2026-11-18): docs/album_metadata_research.md: NO legitimate way to read album names/dates
(Library API read scopes deleted; Data Portability has no Photos and isn't offered in Canada; Takeout always
includes media). docs/trip_naming_design.md: trips found automatically from capture-date gaps (>2 empty
days = new trip), named "Nov 2025 · 12 days" with a cover; optional one spoken word right after a pick
(TV keyboard mic; Google RecognitionService present on the TV). The owner: no typing, no manual assigning;
picker per-day select circles + search is how he picks; budget must be user-set (default 20 GB).

## Idea parked by the owner (2026-11-18): "Highlights" / trip overview — DON'T BUILD until he asks
The owner's words: an "executive overview" of a trip: pick the most important photos (say ~30, maybe user-set)
and play just those. Thinking so far (nothing built):
- Name: "Highlights" (Settings → Play: All photos / Favourites only / Highlights, with a count 20/30/50/100).
- Picker API gives no Google "best of" data, so importance must come from what's on the TV:
  favourites (strongest signal); burst size (a scene the owner shot many times mattered to him; also pick the
  sharpest member); faces (android.media.FaceDetector, on-device); sharpness/exposure (blur and dark
  shots score low); spread across days so every day of the trip is represented (proportional quota per day,
  minimum one); and no two near-identical picks.
- Play the chosen set in date order, like a short story of the trip; loop it.
- Score once per photo in the background and cache it in files/library/meta.tsv (CPU is slow).
- Let the owner nudge it: favourite = always in, hidden = never in.


## Setting up on the owner's PC (first local session)
The owner will open Claude Code on his PC and ask to set everything up. Do it end to end without manual
steps for him beyond the unavoidable ones:
1. Clone https://github.com/gggggnever-cmd/photos-frame (private; help with GitHub login if needed).
2. Find his Desktop key: search Downloads etc. for `client_secret*.json` whose client_id starts with
   `<desktop-client-id>`; copy to app/src/main/assets/oauth_client.json (gitignored). Not the TV client.
3. Install JDK 17+, Android SDK (platform 34, build-tools 34, platform-tools/adb), Gradle 8.14; build.
4. Find the TV on the LAN yourself (e.g. scan for port 5555, or `adb mdns services`), install with
   tools/update_tv, set as screensaver. The owner must turn on Developer options + USB/network debugging on
   the TV and press **Allow** on the TV prompt — tell him when, then wait.
5. Walk him through first sign-in and pick one short step at a time (INSTALL.md step 5).
6. Update this file (TV IP, checklist), commit, push.
Placeholder photos are not in git; the frame shows an empty wall until photos are picked — fine.

## The owner's PC toolchain (set up 2026-11-17)
JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`, SDK at `%USERPROFILE%\Android\Sdk`
(platform 34 added), Gradle at `%USERPROFILE%\tools\gradle-8.14\bin\gradle`. In Git Bash, set
`MSYS_NO_PATHCONV=1` before adb shell commands with paths. Install a build with
`adb -s <TV-IP>:5555 install -r app/build/outputs/apk/release/app-release.apk`.
`python3` on this PC is the Windows Store stub; a real Python is in the <python-venv> venv.

## History (short)
- First session (cloud, Nov 2026): research → Picker spike → v0.1–v0.5. the owner's assistant (the owner's assistant)
  drove part of it. Ruled out: shared albums, phone helper app, TV device-code sign-in, light sensor.
- First local session (the owner's PC, 2026-11-17): toolchain, first TV install, two sign-in fixes,
  sign-in done from the PC browser, first ~2,000 photos picked.
