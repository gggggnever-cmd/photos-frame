# Photos Frame — everything a new session needs

The owner has already explained all of this once. **Do not ask him to repeat it.** Read this file,
README.md and INSTALL.md, then carry on. Ask only about things that are genuinely new.

## Who and what
- **The owner** owns the project and the TV; **the owner's assistant** sometimes drives sessions.
  The owner and their partner use Google Photos Partner Sharing so either phone can take trip photos.
  Real names, addresses and accounts are in CLAUDE.local.md (gitignored, local to the PC).
- **TV:** TCL 85" A300W (NXTFRAME), Google TV, ~64 GB storage (~50 GB free for apps).
  Not OLED. TV IP address, Wi-Fi, Cast name, adb product: see CLAUDE.local.md
  (model "Smart TV"; ~46 GB free). The owner's PC is on the same Wi-Fi.
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
- Project and Google account: see CLAUDE.local.md. OAuth consent screen name "Photos Frame",
  External, the owner is a test user. Photos Picker API enabled (free, no billing).
- Clients: **Desktop** client (id in CLAUDE.local.md) ← the one the app uses.
  "Photos Frame TV" (limited-input) client exists but is unusable for Photos; can be ignored/deleted.
- Consent screen **In production** since 2026-11-18 (sign-ins no longer expire weekly).

## Public web pages and Google publishing (2026-11-18) — needed for any wider release
The owner's assistant did this to publish the OAuth consent screen ("In production" verified
2026-11-18). **If the owner ever folds Photos Frame into Zero Studio or distributes it widely, start here.**
- The GitHub repo **gggggnever-cmd/photos-frame is now PUBLIC** (was private) and serves **GitHub Pages**
  from `main` / root: https://gggggnever-cmd.github.io/photos-frame/
- `index.html` — home page (what the app is, owner's contact email, link to privacy policy).
- `privacy.html` — privacy policy: single-user personal app; only Picker-selected photos; stored on the TV;
  nothing sent to the developer or third parties; no analytics/ads/tracking. **Rewrite it for a real
  multi-user release** (it says "single-user"; must match the store's Data safety form).
- `google721cc4a7188302b3.html` — Google Search Console domain-ownership file for the Pages site. Don't
  delete or rename it (Google re-checks ownership).
- Consent screen: External, In production, **not verified** (users see "unverified app"; 100-user lifetime
  cap, 2 used). Mass distribution needs Google **OAuth verification** of the sensitive Picker scope (home page,
  privacy page, demo video, possibly a security review), a brand/logo, and its own Cloud project/clients
  rather than the owner's personal one. Play Store would also need a real signing key (today: debug key).
- Because the repo is public: never commit oauth_client.json, photos, APKs or tools/tv_ip.txt (all gitignored).
  CLAUDE.md itself (family names, TV IP, account name) is public now — trim it before any wider release.

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
Current version: **v0.15** (versionCode 15). Everything is on GitHub `main`.
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
      (address in CLAUDE.local.md). No phone request has been seen in the `SetupServer` log yet, so it's unknown
      whether the request reaches the TV. Research 2026-11-18: SetupServer logs every accepted socket, so
      nothing reaches the TV; ERR_TOO_MANY_RETRIES (-375) comes from auth/cert restarts or retries on a
      reused socket, which points at a proxy or VPN on the phone (Wi-Fi Proxy setting, Samsung Secure
      Wi-Fi / Auto protect, a VPN/ad-blocker app) or the Lens in-app browser. Checklist for the owner: type the
      address into Chrome by hand; Wi-Fi network → Proxy = None; turn off VPN / Secure Wi-Fi; try Samsung
      Internet; each while logging. Next: have the owner scan once while `adb logcat SetupServer:I *:S`
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
- [x] v0.14 (2026-11-18, built on the PC, NOT yet installed: the TV was off): **Places only** and
      **Highlights** (the owner asked to build both). PhotoChecker (background, MIN priority, files/library/
      scores.tsv: faces, largest face height / long side, Laplacian sharpness, brightness) using ML Kit face
      detection 16.1.7 with its bundled model (ACCURATE, min face 0.015), falling back to
      android.media.FaceDetector. Places rule (Highlights.PERSON_FACE): a person is "in" the photo when a
      face is >= 2.5% of the long side; tune after the owner sees results. Photo menu "Places only" flips a
      photo (places.txt overrides). Highlights.select (pure, 8 JUnit tests): best member per burst, quality =
      sharpness rank in trip + exposure + people bonus + burst size, favourites always in, per-day quota
      proportional to scenes (min 1), avoid photos <90 s apart; trips in random order, each in time order;
      Settings → Highlights per trip 20/30/50/100 (Order and Day rows hide). Play pref now int "play"
      (migrates "favourites_only"); round.txt h[3] = play number (old true/false read), h[7] use, h[8] count.
      APK limited to armeabi-v7a (ML Kit libs; 17.6 MB). Also: sign-in log now redacts URL-encoded codes;
      sign-in screen says to use a computer if the phone can't reach the page.
      **Print filter additions** (the owner showed Gemini's two "Frame TV paper" styles): relief-lit paper fibres /
      canvas weave and a slight ink soak for Paper and Canvas; new Print surface **Paper cut** (flat colour
      sheets with soft shadows; docs/print_filter_spec.md "Additions"). PC preview: tools/PrintPreview.java.
      11 JUnit tests pass (8 Highlights, 3 PrintEffects).
      Installed on the TV 2026-11-18 10:30: settings unchanged (All trips, 30 s, Maximum, Paper, Full screen,
      Shuffle, All photos, 20 GB). Paper cut bake 7.6 s on the TV while the checker runs (fine: prepared ahead).
      First Paper cut was too strong for the owner (faces vanished); now 16 colours / finer, faces keep features
      when close, small far-away figures still simplify. Photo checker ~36 photos/min on the TV (~1 h for 1,990).
      Shrinker finished (no log, marker present); its re-saves had new mtimes, so the round at the time
      absorbed all burst siblings (1,990 instead of ~1,092) — fixed: Shrinker now keeps mtime. Self-heals
      next round. Checked on the TV 11:20-11:32: checker done, 1,865 photos in 2,981 s, 1,580 with people,
      285 places; Places only round = 205 (scenery, food, streets; misses people seen from behind, as expected).
      First Highlights (30) were almost all the partner posing + near-repeats, so Highlights now mixes people/places
      (people share <= 60%, firm alternation) and spaces picks >= 5 min apart; re-checked: good mix.
      Settings restored (Highlights per trip back to 30). STILL TO CHECK:
      checker speed and "done" log, places count looks right, Highlights look good,
      settings/menu rows fit, the owner's settings unchanged after install.
- [x] v0.15 (2026-11-18): all on-screen wording renamed per the Fable designer (the owner approved all, kept
      "Shuffle"): Print filter → **Print look** (Moderate→Medium, Maximum→Strongest), Print surface → **Style**
      (Fine art paper / Canvas / Cut-out collage; shows "Off (Print look is off)" when off, plus a one-line note
      when collage is chosen), Trip → Trips to play, Look → Picture size (Framed → With a border), Order → Photo
      order, Day → Which day, Play → Photos to show (Favourites only → Favourites, "sorting %"), Highlights per
      trip → Best photos per trip, Add photos… → Add more photos; photo menu, toasts, sign-in and storage texts.
      Prefs/indices unchanged. The owner kept the v0.14 Paper texture upgrade. Checked on the TV: Settings and
      one-photo menu fit; settings unchanged. Not seen: the 3-photo menu hint (shortened so it fits one line).
      Older notes above use the old names.
- [x] v0.16 (2026-11-18): Add-photos screen: text column was a fixed 640dp, overflowing the 960dp screen and
      cutting off the QR's left edge; now weight 1. Checked on the TV; the owner's phone scan then worked
      (phone sign-in bug looks resolved).
- [x] v0.17 (2026-11-18): **Places only misses fixed with a person finder.** The owner showed two misses (people
      small in an archway / on a chairlift). Contact sheets of all 304 "places" showed ~25% had people: from
      behind, side-on, sunglasses, small in a garden. Faces can't fix that, so PhotoChecker also runs
      EfficientDet-Lite0 (COCO, assets/person_detector.tflite, TFLite Task Vision 0.4.4, ~0.4 s/photo on the
      TV): Score.body = tallest "person" box / photo height (score >= 0.4); a person is "in" when body >=
      Highlights.PERSON_BODY 0.15 or the old face rule. scores.tsv gets a 6th column; older rows are re-checked
      (faces/sharpness kept). APK 17.6 → 27 MB. NOT YET REVIEWED: the new Places set (the TV was moved
      mid-run; the run resumes by itself). To review: install a debug build briefly (same key, `run-as` works),
      pull files/library/scores.tsv, contact-sheet the places, reinstall release.
- [x] v0.18 (2026-11-20): **art-button reminder.** After the TV was wall-mounted the art button went back to
      T-Exhibition: enabled_accessibility_services was empty (rewritten by `android`). AOSP turns an
      accessibility service off when its app is **force-stopped** (onHandleForceStop); `install -r` does NOT
      (checked). So never `am force-stop` / `am start -S` this app. Apps can't prevent it, so ArtKeyReminder:
      when the frame or screensaver opens with the helper off, a bottom line shows 10 s, at most once a day;
      a short OK in that window opens Accessibility settings (this TV has no per-service details page; the
      only TV OEM link is the shortcut picker). The screensaver turns interactive only for those 10 s (other
      buttons still end it). Test: `am start --activity-single-top -n com.gary.photosframe/.MainActivity --ez
      test_art_reminder true`. Checked on the TV: line shows, OK opens Accessibility. The owner re-enabled the
      helper himself. TV got a new IP after the move (see CLAUDE.local.md).
- [ ] v0.19 (2026-11-20): **pick in lots, download in the background.** The owner wants to pick 2,000, then
      1,500, then more, without waiting. DownloadQueue (process-wide, one worker thread) takes each pick; the
      Add photos screen names new trips, then shows a fresh QR at once ("N photos added to the queue. More?")
      with a live queue line; Back leaves and downloads carry on. baseUrls die after 60 min, so the session
      stays open until its lot is done and is re-listed (fresh links) per 100-photo chunk once 45 min old;
      sessions waiting are saved in files/download_queue.txt and resumed when MainActivity starts. The frame
      shows "Adding photos: …" bottom-right and rescans every 3 min while downloading (reloadQuietly, no skip).
      Trips skipped at naming aren't asked again in the same visit. Installed; NOT yet tried with a real pick
      (the TV was in use). Unknown: how long Google keeps a picked session (expireTime undocumented).
- [x] v0.20 (2026-11-21): **5, 10 and 20 second intervals** (owner asked). Saved as seconds, so old choices keep
      (DEFAULT_INTERVAL index moved to 6 = 10 min). Auto-advance fade is min(4 s, interval/4). Installed; Settings checked on the TV.
- [x] v0.21 (2026-11-21): **YouTube corner.** The TV has no picture-in-picture and YouTube Music stops in the
      background, so: YouTube's official embedded player (IFrame API) in a focus-less WebView over a corner
      (CornerPlayer, assets/youtube_corner.html, loaded with base https://com.gary.photosframe/ so YouTube gets
      a referrer). Not signed in (Google blocks sign-in in web views): public/unlisted only, with ads. Phone
      remote: CornerServer on port 8081 while the frame is open, private key in the QR (Settings -> YouTube
      corner); paste a link (YouTubeLink, unit-tested), play/pause, next/prev, volume, move corner, size, close.
      Pauses when the frame leaves, resumes on return. Tested on the TV from the PC: plays, buttons, move,
      size, close, wrong key 403. NOT yet tried: a playlist link, from the owner's phone.
- [x] 2026-11-21 **power experiment** (PowerTest, adb-only switches test_power=sleep / test_wake_in=N; also
      MainActivity setTurnScreenOn/setShowWhenLocked; installed in the 0.21 build). Findings: (1) the app CAN
      sleep the TV: ArtKeyService performGlobalAction(LOCK_SCREEN) -> standby. (2) With TV Settings -> System ->
      Power & Energy -> Quick Start + Screenless Service ON and the keep-Wi-Fi-in-standby switch in Network
      settings ON (owner set all three), the sleeping TV stays on Wi-Fi (ping, adb, mDNS "Kitchen") and a Cast
      launch or Google Home wakes it; before the Wi-Fi switch it left the network entirely. (3) The app CANNOT
      wake itself: in standby its exact ELAPSED_REALTIME_WAKEUP alarm shows as type 3 (non-wakeup) and fires only
      when something else wakes the TV (TIME_TICK stalls too). So "on when we're home" needs an outside waker
      (Google Home automation, phone cast, IR blaster); the app can do "off" and open the frame after a wake.
- [x] v0.22 (2026-11-21): **frame after an automatic wake.** ArtKeyService keeps the time of the last key (not
      which) and listens for SCREEN_ON; 6 s after a wake with no key in the 8 s before and no music playing
      (AudioManager.isMusicActive: a phone casting), it opens MainActivity. Tested with a Cast wake from the PC.
      NOT yet known: whether the remote's power key reaches the accessibility filter while asleep (if not,
      a remote power-on would also open the frame); the owner will test.
- [x] v0.23: CornerServer became one app-wide server (attach/detach per frame screen) that retries the port;
      it used to die when the frame screen was re-created (likely why the owner's phone scan failed). Logs
      each request (path, no key).
- [x] v0.24 (2026-11-21): **bedtime when both phones charge.** Phones' automations call
      /phone?k=KEY&who=NAME&charging=1|0 (or =forget) on port 8081; Bedtime keeps {name: charging, time};
      phones heard from this week all charging + 9 pm-5 am + 60 s grace → ArtKeyService.sleepTv(), but only
      while MainActivity or the screensaver shows (a film is never cut off). The helper starts the server too.
      Tested from the PC with two pretend phones (then forgotten). Phones' automations not set up yet.
      Later findings: Samsung Modes and Routines can detect charging but has no documented open-URL/HTTP
      action, so the phones would need our own companion app (not built; owner avoids outside apps). The
      owner reports Google Home can turn this TV ON but not OFF; Alexa can turn it off. TCL logs "Change
      Wakeup Alarm type to non Wakeup type" for every app, Google's too.
- [ ] v0.25 (2026-11-22): **"TV off if remote unused"** (Settings, last row): Never / 10 / 30 min / 1-4 h.
      AutoOff (30 s check, in the helper's process): photos or screensaver showing, no remote press, no sound
      (Spotify, YouTube corner) for that long since the later of last key / photos came on → sleepTv().
      Default Never. Installed quietly while the TV slept; NOT yet tried on screen.
- [x] v0.26 (2026-11-22): YouTube corner fixes, tried on the TV. (1) Playlist-only links threw "Invalid video id" in the
      IFrame API because videoId was passed as undefined; now the key is left out. (2) A single video plays as YouTube's
      mix (list RD+id, ~50 songs) so music keeps going; falls back to the lone video if the mix fails. (3) The player is
      sized to the video's shape (oEmbed, fetched in Java: CornerPlayer.lookUpShape) so 4:3 videos fill the window.
      (4) Phone button "Trim black edges" (zoom 1 / 1.2 / 1.4) for videos with bars burned into the picture.
      Chrome on the owner's phone can't reach the TV's port 8081 (ERR_TOO_MANY_RETRIES, request never arrives);
      Samsung Internet works, so the phone page now says to use it.
      Cause found the same day: Chrome lacked Android's **Nearby devices** permission (local network access);
      allowing it fixed Chrome. The phone page's help now says so (v0.29, installed with v0.30).
- [x] v0.30 (2026-11-22): YouTube corner has **soft edges**: rounded corners and a ~9% edge fade (CSS mask on #box,
      WebView background transparent) so the video melts into the photo. Seen on the TV (screencap), works.
- [x] v0.31 (2026-11-22): phone button **Blend: Off / Light / Medium / Strong** makes the whole corner video see-through
      (opacity 1 / 0.8 / 0.6 / 0.45 on #box, kept in "corner" prefs). Seen on the TV at Medium; left at Medium.
- [x] v0.32 (2026-11-22): corner **Size** button cycles Small / Medium / Large (720 dp) / Full screen (MATCH_PARENT,
      no margin); prefs "size" 0-3 (old 0/1 carry over). With Blend it reads as a double exposure. Seen on the TV.
- [x] v0.33 (2026-11-22): **hold ▼** on the remote steps the YouTube corner: Small → Medium → Large → Full screen →
      paused and hidden → Small and playing. After Close it replays the last link. A short ▼ still opens Add photos,
      now on release. Tested with `input keyevent --longpress KEYCODE_DPAD_DOWN`.
- [x] v0.27 (2026-11-22): the remote's Back closes the YouTube corner (when no card or menu is open). Tried with adb.
- [~] v0.28 (2026-11-22): **Google Home "turn off" works via the helper** (GoogleOff). Cause: Google's command reaches
      cast_shell (com.google.android.apps.mediashell, which only has HDMI_CEC, no DEVICE_POWER), which logs
      Display.TurnOff.In → "TurnStandby result: 0" → reports CAST::EDISPLAY_ATV: a TV can't CEC-standby itself.
      GoogleOff runs `logcat -s cast_shell:I` in the helper's process and calls sleepTv() on TurnOff.In.
      Needs READ_LOGS granted once by adb (`pm grant com.gary.photosframe android.permission.READ_LOGS`, done
      2026-11-22; survives `install -r`, lost on uninstall). Tested with a fake log line (TV slept, Cast woke it);
      a real Google Home off still to be tried by the owner. Google may still say something went wrong.
- Installing an update restarts the app on the TV (the screen briefly shows something else): tell the owner first.
- [x] Consent screen published by the owner's assistant (2026-11-18); verified in Cloud console Audience: "In production", External,
      2 users / 100 cap. The TV's current sign-in dates from Testing, so it may still lapse once: if Add photos asks
      to sign in again, do it once (expect Google's "unverified app" screen: Advanced → Go to Photos Frame).
- [x] Phone sign-in: worked on 2026-11-18 after the v0.16 QR fix (reminder no longer needed).
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

## Idea from the owner (2026-11-18): "Places only" — photos of where we went, without us in them
BUILT in v0.14 (see status).  Original thinking: Thinking: a Play choice "Places only" that skips photos where a person is the subject.
- On-device face detection once per photo, in the background, cached in files/library/meta.tsv
  (android.media.FaceDetector needs no extra library; ML Kit face detection with its bundled model is more
  accurate, e.g. side-on faces, at a few MB).
- Rule: skip a photo if any face is bigger than a small share of the frame (the owner or partner posing); tiny
  faces of passers-by in a street scene don't count. Can't tell "us" from strangers (no face recognition).
- Misses: people seen from behind or far away. A hidden-style "not a places photo" mark in the photo menu
  could fix mistakes. Shares the face scores with the parked "Highlights" idea.

## Idea from the owner (2026-11-18): "Highlights" / trip overview — BUILT in v0.14 (the owner asked)
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
1. Clone https://github.com/gggggnever-cmd/photos-frame (public since 2026-11-18).
2. Find his Desktop key: search Downloads etc. for `client_secret*.json` whose client_id matches the Desktop
   client in CLAUDE.local.md; copy to app/src/main/assets/oauth_client.json (gitignored). Not the TV client.
3. Install JDK 17+, Android SDK (platform 34, build-tools 34, platform-tools/adb), Gradle 8.14; build.
4. Find the TV on the LAN yourself (e.g. scan for port 5555, or `adb mdns services`), install with
   tools/update_tv, set as screensaver. The owner must turn on Developer options + USB/network debugging on
   the TV and press **Allow** on the TV prompt — tell him when, then wait.
5. Walk him through first sign-in and pick one short step at a time (INSTALL.md step 5).
6. Update this file (TV IP, checklist), commit, push.
Placeholder photos are not in git; the frame shows an empty wall until photos are picked — fine.

## The owner's PC toolchain (set up 2026-11-17)
JDK 21, Android SDK (platform 34 added) and Gradle 8.14 under the user's folder (paths in CLAUDE.local.md). In Git Bash, set
`MSYS_NO_PATHCONV=1` before adb shell commands with paths. Install a build with
`adb -s <TV-IP>:5555 install -r app/build/outputs/apk/release/app-release.apk`.
`python3` on this PC is the Windows Store stub; a real Python's path is in CLAUDE.local.md.

## History (short)
- First session (cloud, Nov 2026): research → Picker spike → v0.1–v0.5. The owner's assistant
  drove part of it. Ruled out: shared albums, phone helper app, TV device-code sign-in, light sensor.
- First local session (the owner's PC, 2026-11-17): toolchain, first TV install, two sign-in fixes,
  sign-in done from the PC browser, first ~2,000 photos picked.
