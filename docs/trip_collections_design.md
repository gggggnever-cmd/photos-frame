# Photos Frame: "Trips" — play any trip on demand without filling the TV

Design document, 2026-11-18. Research and design only; no app code was changed.
Sources were checked on 2026-11-18. Items marked **(unverified)** could not be confirmed from an
official page and should be tested before anything is built on them.

## The short version

1. **The storage worry mostly disappears with one change.** The app draws at 1920x1080 but stores
   every photo at 3840x2160. Storing at "frame size" (about 1920 wide) cuts each photo from ~1.2 MB to
   roughly 0.4 MB. A 2,000-photo trip becomes ~0.8 GB instead of 2.3 GB, and a 10 GB budget holds
   about 25,000 photos: probably every trip the owner has, all on the TV at once, using under a quarter of
   the free space.
2. **So the real problem is getting trips in, not keeping them.** Google gives no way for an app to
   read the library by itself. Every photo must be handed over once, either by the owner picking it
   (Picker API) or by the owner exporting it with Google's own tools (album download / Takeout) on his PC.
3. **Recommendation:** make "Trips" a first-class thing on the TV (named, switchable with the remote,
   comparable, with a storage budget), keep the Picker for new trips, and add a PC "trip loader" for
   bringing in the back catalogue in bulk. After a trip is in once, switching to it from the sofa is
   two or three remote presses and needs no phone, no PC and no internet.

---

## 1. What is and isn't possible (late 2026)

### Google Photos APIs

| Claim | Status | Source |
|---|---|---|
| Only two Google Photos APIs exist for developers: Picker API and Library API. No "ambient"/frame API is offered publicly. | Confirmed | [Overview](https://developers.google.com/photos/overview/about) |
| Library API can only read albums and items **the app itself created**. Scopes `photoslibrary.readonly`, `photoslibrary.sharing`, `photoslibrary` were removed; listing/searching the user's library or albums returns 403. Shared-album methods are gone. | Confirmed, unchanged since 1 Apr 2025 | [Updates](https://developers.google.com/photos/support/updates), [Release notes](https://developers.google.com/photos/support/release-notes) |
| No newer release note exists after 1 April 2025; nothing has been loosened. | Confirmed (release notes page has no 2026 entries) | [Release notes](https://developers.google.com/photos/support/release-notes) |
| Picker: max 2,000 items per session (`maxItemCount` above 2000 is coerced to 2000). | Confirmed | [sessions reference](https://developers.google.com/photos/picker/reference/rest/v1/sessions) |
| Picker UI has **no Albums tab**, but the user **can search by keywords, dates, locations and album titles**, then select from the results. | Confirmed | [Picking experience](https://developers.google.com/photos/picker/guides/picking-experience) |
| Whether the picker offers "select a whole day" or drag-select is not documented. | **Unverified** (the owner already picked 1,990 somehow; ask him how it felt) | same page |
| baseUrls live 60 minutes, need the OAuth bearer token, and must carry a size (`=w…-h…`) or download (`=d`) parameter. | Confirmed | [Media items guide](https://developers.google.com/photos/picker/guides/media-items) |
| A picked item cannot be re-fetched later without a new picking session (sessions expire; Google advises deleting them after download). No "refresh" call exists. | Confirmed by absence | [Sessions guide](https://developers.google.com/photos/picker/guides/sessions) |
| Each picked item carries a persistent `id`, `createTime` (when the photo was taken), `filename`, original `width`/`height`. | Confirmed (the app already dedupes by `id`) | [mediaItems reference](https://developers.google.com/photos/picker/reference/rest/v1/mediaItems) |
| Policy: the Picker is the required route for "a limited set of content"; apps must not seek broad library access. Storing the user's own picked photos on the user's own device for display is within the approved use cases; no maximum cache time is stated. | Confirmed | [Photos API policy](https://developers.google.com/photos/support/api-policy) |
| Testing-mode sign-ins expire after 7 days. Publishing the consent screen removes that; an unverified app with a sensitive scope still works for up to 100 users behind a warning screen. | Confirmed | [OAuth app audience help](https://support.google.com/cloud/answer/15549945) |

### Google's own export features (no API needed)

| Claim | Status | Source |
|---|---|---|
| Google Takeout can export Google Photos with only chosen albums ("All photo albums included" → pick). Photos not in an album appear in per-year folders. Each file gets a JSON sidecar with taken-time and location. | Confirmed by several guides, not by the official page | [ente guide](https://ente.io/articles/exporting-google-photos-with-takeout), [IIT guide](https://www.iit.edu/ots/google-microsoft-365/migrating-google-photo-library/use-google-takeout-export-google-photos) |
| Takeout **cannot** be limited to a date range. Delivery: download link (valid ~7 days, 5 downloads) or Add to Drive / OneDrive / Dropbox / Box. Takes "a few minutes to a few days". Scheduled exports every 2 months for a year. | Confirmed | [Takeout help](https://support.google.com/accounts/answer/3024190) |
| On photos.google.com an album has ⋮ → **Download all** (one zip of originals); a manual selection can also be downloaded as a zip, reportedly up to 500 items. | Secondary sources only **(unverified cap)** | [online-tech-tips 2026](https://www.online-tech-tips.com/download-google-photos-pictures-videos/), [googlewatchblog](https://www.googlewatchblog.de/2025/05/google-fotos-so-koennt-ihr-sehr-einfach-viele-bilder-und-ganze-alben-herunterladen-ganz-ohne-takeout-chaos/amp/) |
| Drive API: `drive.file` (per-file, non-sensitive) vs `drive.readonly` (restricted, sees all of Drive). | Confirmed | [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) |

### Google-native TV features

| Claim | Status | Source |
|---|---|---|
| Google TV's Ambient mode can use "recent highlights, favorites, or any album" as the screensaver, and Google TV can play album slideshows and find photos by voice through Gemini (voice search: US + English + Gemini-enabled device only). | Confirmed | [Use Google Photos on your TV](https://support.google.com/photos/answer/16996534) |
| The Android phone photo picker can show Google Photos cloud albums and search. | Confirmed for the picker itself; behaviour and selection limit inside a phone **browser upload** are **unverified** | [Android Developers blog](https://android-developers.googleblog.com/2024/02/cloud-photos-now-available-in-android-photo-picker.html) |

### What is impossible (honestly)

- The TV app can never browse, search or list the owner's library or albums by itself, and can't "fetch
  Egypt 2025" on its own. A human hand-over (pick or export) is required once per photo.
- A picked photo can't be silently re-downloaded weeks later. If the TV deletes it, getting it back
  means picking it again or re-sending it from a copy on the PC.
- No automatic "new photos in album X appear on the TV".
- Takeout can't export "just March 2016"; only albums or whole years.
- Left out as grey or against the rules: scraping photos.google.com, unofficial endpoints, reusing
  browser cookies, any tool that pretends to be a Google app.

---

## 2. Storage: real numbers

Measured today: 1,990 photos = ~2.3 GB, so ~1.16 MB each at about 3837x2160 (8.3 megapixels).
The app composes every slide into a 1920x1080 bitmap, so three quarters of those pixels are never used.

Proposed "frame size": ask Google for `=w1920-h1440` instead of `=w3840-h2160`.
- 16:9 landscape → 1920x1080 (exactly the screen).
- 4:3 landscape → 1920x1440 (still fills the screen width when cover-cropped).
- 9:16 portrait → 810x1440 (the triptych shows portraits about 608x1080, so this is plenty).

**Measured afterwards (2026-11-18, 60 of the owner's photos re-saved at 1920x1080): 0.44 MB (JPEG q85) to 0.55 MB (q90) each, a 2.3-2.8x saving; plan on ~1 GB per 2,000-photo trip.** That is 25-33% of today's pixels. Small JPEGs compress slightly less well per pixel, so plan on
**~0.4 MB per photo (expected range 0.3-0.5 MB)**. This is an estimate: before building, download
20 photos at both sizes and compare.

| | 4K (today) | Frame size |
|---|---|---|
| One photo | ~1.2 MB | ~0.4 MB |
| A 2,000-photo trip | ~2.3 GB | ~0.8 GB |
| 10 trips of 2,000 | ~23 GB | ~8 GB |
| Photos in a 10 GB budget | ~8,500 | ~25,000 |
| Download time for 2,000 (today ~110/min) | ~18 min | faster, likely under 10 min |

Notes:
- Using 5-10 GB of the ~46 GB free will not slow the TV. Flash storage only hurts when it is nearly
  full; the app's existing 2 GB reserve already guards that. The budget is for peace of mind.
- Cost of the smaller size: if the app one day renders true 4K or zooms deeply into photos, the copies
  are too small and trips would need re-adding. A middle setting (2560x1440, ~0.6 MB) is possible.
  Suggest a Settings choice "Photo quality on TV: Frame (smallest) / High", default Frame.
- The 1,990 photos already on the TV can be shrunk in place in the background (decode, scale,
  re-save, copy the EXIF date across); about 30-40 minutes of quiet work, frees ~1.5 GB, no re-pick.
- Slides also get faster to prepare: decoding a 2 MP file instead of an 8 MP one cuts the ~200 ms decode.

---

## 3. Candidate architectures

### A. Trips on the TV (Picker + collections + frame-size storage)
Every pick becomes a named trip stored on the TV at frame size. No PC involved.

- **Day to day, on the sofa:** press ▲ (Settings) → row **Trip** → ◀▶ to "Egypt 2025" → Back. The
  frame now plays only Egypt, continuing where that trip left off. To compare: choose
  "Two trips…" and tick Tokyo 2016 and Tokyo 2019.
- **Adding a trip (once per trip):** ▼ Add photos → scan the QR with the phone → in Google's picker
  type "Egypt 2025" (or the album title, or a date) in the search box → select the photos → Done.
  The TV proposes a name from the photo dates ("Feb 2025, 14 days"); the owner can rename it from the phone page.
  Trips over 2,000 photos: "Add more to this trip" and pick the rest.
- **One-time setup:** publish the OAuth consent screen (otherwise sign-in is needed again every 7 days).
- **Storage:** ~0.8 GB per 2,000-photo trip; budget set by the owner (suggest 8-10 GB).
- **If the PC or phone is off:** playing and switching need neither. Adding needs the phone (or PC browser).
- **If a trip was evicted:** it stays in the list marked "not on the TV"; getting it back = pick it again
  (search, select, ~10 minutes of download). Favourites and hidden marks survive because they are
  keyed by Google's photo id.
- **ToS:** fully compliant; this is exactly what the Picker API is for.
- **Effort:** M. **Risks:** selecting ~1,500 photos in the picker may be tedious (bulk-select
  behaviour unverified); ten old trips means ten picking sessions.

### B. PC trip loader (Google album download / Takeout → PC → TV over home Wi-Fi)
The owner downloads a trip from Google with Google's own tools; a small tool on the PC shrinks the photos
and sends them to the TV. The PC keeps the shrunk copies as a private archive.

- **Day to day, on the sofa:** identical to A (trips already on the TV are switched with the remote).
- **Adding a trip:** on the PC, open photos.google.com → the trip's album → ⋮ → Download all. Save the
  zip into a folder called "Trips inbox". Run "Load trips" (a desktop shortcut, or ask Claude Code).
  The tool names the trip after the zip, shrinks the photos to frame size, and sends them to the TV.
  For the whole back catalogue in one go: one Takeout export with the trip albums ticked.
- **One-time setup:** in Google Photos, one album per trip (search "Japan 2016" → select all → add
  to album; Google often suggests trip albums already). Create the inbox folder and shortcut.
- **Storage:** TV as in A. PC archive ~0.8 GB per trip at frame size (originals can be deleted after loading).
- **If the PC is off:** nothing changes for playing or switching. Only loading new trips, or
  restoring evicted ones, waits until the PC is on.
- **If a trip was evicted:** run "Load trips → send Egypt 2025 again" on the PC; no Google step, no re-pick.
- **ToS:** compliant. Google's own export features, the owner's own files, the owner's own devices. Nothing is
  uploaded anywhere new.
- **Effort:** M (PC tool + a way for the TV to receive files). **Risks:** needs trip albums to exist;
  Takeout can take hours and delivers big zips; album zips and Picker picks of the same photo must be
  de-duplicated (match on file name + taken time).
- **Transport, two options:** (1) `adb push` into the app's inbox folder, reusing the adb link the
  update script already uses, no new network service (needs a check that adb can write to the app's
  external files folder on this TV); (2) an upload address on the TV's small web server protected by a
  pairing code shown on the TV. Start with (1).

### C. PC as a live photo server (TV streams, stores nothing)
- **Flow:** TV shows trips that live on the PC and fetches each photo when needed.
- **Storage:** near zero on the TV.
- **If the PC is off:** the frame has nothing to show except a small cache. This is the deciding
  flaw: the PC is not always on, and the frame is also the screensaver.
- **ToS:** compliant. **Effort:** M. **Risks:** PC sleep, Wi-Fi hiccups, Windows firewall prompts.
- **Verdict:** not worth it now that a trip costs ~0.8 GB. Its useful half (the PC holding an
  archive that can refill the TV) is already inside B.

### D. Takeout → Google Drive → TV pulls directly (no PC)
- **Flow:** the owner schedules a Takeout to Drive; the TV signs in to Drive, downloads and unpacks the zips.
- **Problems:** the TV would need `drive.readonly` (a restricted scope that can see all of the owner's
  Drive) or a Drive file-picker web flow for `drive.file`; archives are up to 50 GB each and must be
  unpacked on a weak TV with limited space; exports count against Google storage; no date filter.
- **ToS:** compliant in itself, but much broader access than needed. **Effort:** L. **Verdict:** rejected; B does the same job better on the PC.

### E. Google-native playback (Ambient mode album / Gemini on Google TV)
- **Flow:** Settings → System → Ambient mode → Google Photos → pick the "Egypt 2025" album; or ask
  Gemini on the remote to show an album. Zero storage, zero code.
- **Limits:** it is Google's vivid look that the owner already rejected (no print filter, no triptych, no
  paper tone), it lives outside Photos Frame, picking an album there may reset the screensaver
  setting, and voice search is US/English only for now.
- **Verdict:** a handy fallback for a quick peek at a trip that isn't loaded yet, not the solution.

### F. Phone upload page, no app (small top-ups) — later, unverified
The TV's web page could offer "Send photos from this phone"; the phone browser's file chooser opens
Android's photo picker, which can show Google Photos albums. This differs from the phone helper app
that was ruled out because nothing is installed on the phone. But the per-pick limit in a browser is
unverified, cloud photos must first download to the phone, and the owner's phone currently cannot open the
TV's page at all (the open ERR_TOO_MANY_RETRIES bug). Park it until that bug is solved.

---

## 4. Recommendation: A first, then B, then compare polish

**Phase 1 (build next): Trips on the TV.** Everything the owner asked for except bulk loading.
1. Frame-size downloads (`=w1920-h1440`) with a "Photo quality on TV" setting; shrink the existing
   1,990 in place.
2. Collections ("Trips") in the library; the existing photos become the first trip (first trip, Oct 2026).
3. Each pick asks/suggests a trip name; "Add more to this trip" for trips over 2,000.
4. Settings row **Trip**: All trips / one trip / Two trips… Each trip remembers its own place in its round.
5. Two-trip play, simple form: alternate slides between the two trips with a small caption
   ("Tokyo 2016" / "Tokyo 2019") in the corner.
6. Storage budget (Settings: 5 / 10 / 20 GB, default 10) with least-recently-played eviction of whole
   trips; never the trip that is playing; a trip can be pinned ("Keep on TV"). Evicted trips stay
   listed as "not on the TV".
7. Picker screen hint: "Tip: type the trip or album name in the search box, e.g. Egypt 2025".
8. Publish the OAuth consent screen (already on the to-do list) so re-adding never hits the 7-day sign-out.

**Phase 2: PC trip loader (B).** "Trips inbox" folder + loader on the PC (JDK 21 and PowerShell are
already installed; no new installs), adb push to an inbox folder on the TV, TV ingests a folder plus
a small manifest (file, taken time, width, height, trip name). The PC keeps the frame-size archive,
so eviction costs nothing to undo.

**Phase 3: polish.** True side-by-side comparison (left half one trip, right half the other, matched
by day-of-trip or by order, paper band between, reusing the triptych layout code); optional small PC
service so the TV can pull an evicted trip by itself when the PC happens to be on; the parked
"Highlights" idea becomes per-trip.

### Data model changes (PhotoLibrary and friends)

Keep one flat photo store and add membership lists, so a photo that is in two trips is stored once.

- `files/photos/<photoKey>.jpg` — unchanged location. Picker photos keep the Google item id as the
  key (so favourites.txt and hidden.txt keep working). PC-loaded photos use a key derived from
  file name + taken time; the same pair is recorded for Picker photos (`filename`, `createTime`) so the
  two sources can be de-duplicated.
- `files/library/collections.tsv` — one line per trip:
  `cid, name, source (picker|pc), createdAt, lastPlayedAt, pinned, firstTaken, lastTaken, total, onTv`.
- `files/library/c/<cid>.txt` — the trip's manifest, one line per photo: `photoKey, takenAt, width,
  height`. It is kept after eviction, so the TV can say "1,412 photos, not on the TV" and can tell
  when a re-add is complete.
- `files/library/rounds/<selection>.txt` — today's round.txt, one per play selection ("all", a cid,
  or "cidA+cidB"), so each trip resumes where it stopped.
- `meta.tsv`, `favourites.txt`, `hidden.txt` — unchanged and global.
- Settings prefs: `trip` ("all" | cid | "cidA+cidB"), `budget_gb`, `quality` (frame|high).
- `PhotoLibrary.refresh()` filters the photo list by the selected trip(s) before building a round;
  bursts, One day at a time, Day, Favourites only all keep working inside a trip unchanged.
- `Storage`: per-photo estimate drops from 15 MB to ~1 MB; add `usedByPhotos()`, `budget()`, and
  `evictUntilUnder(budget, keepCids)` (delete files whose every trip is evicted; skip pinned and playing).
- `Picker.download(…)` takes the size parameter and returns the items with `createTime`, `filename`,
  width and height so the manifest can be written without re-reading EXIF.
- `AddPhotosActivity`: after picking, a "Which trip?" step (new trip with suggested name / add to an
  existing trip); the name can be typed on the phone page served by SetupServer.
- Migration (once): create trip 1 from all current photos; the owner names it.
- Reused patterns from the registry: J9 (slides prepared off-thread, decode at target size) is
  unaffected; S8 (pairing token for LAN exposure) applies only if phase 2 uses the web-upload transport.

---

## 5. Questions only the owner can answer

1. Roughly how many trips do you want available, and how many photos in a typical one (500? 2,000? 5,000)?
2. Do your trips already exist as albums in Google Photos, or would you find them by searching a place and date?
3. When you picked the 1,990 first-trip photos, how did selecting them feel on the phone: quick, or a chore?
   (This decides how much weight the PC loader should carry.)
4. Is running one "Load trips" step on the PC now and then acceptable, or must everything work from the sofa with phone and remote only?
5. Is the PC usually on in the evenings, and would you let it keep a small private archive of shrunk trip photos (~0.8 GB per trip)?
6. How much TV storage are you comfortable giving the frame: 5, 10 or 20 GB?
7. Is frame-size quality (sharp at the size the app draws today) fine, or do you want sharper copies kept in case the app later draws in true 4K?
8. For "compare my two Tokyo trips": two photos side by side on one screen, or the trips taking turns?
9. Should the owner's partner's photos of the same trip be in the trip too (they must be saved into your library first)?
10. Are originals of old trips still on your phone or PC anywhere, or only in Google Photos? (Only matters if Google exports prove slow.)
