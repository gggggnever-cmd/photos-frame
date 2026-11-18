# Photos Frame: trips that organise and name themselves

Design note, 2026-11-18. Research and design only; no app code changed.
Builds on docs/trip_collections_design.md (Phase 1 "Trips on the TV"). It replaces that
document's "Which trip?" step and "type the name on the phone page" with something the owner will
actually do: nothing, or one spoken word.

Items marked **(unverified)** need a short test before anything is built on them.

## The short version

- **A trip is worked out from the photo dates, never from the pick.** Two picks of the same trip
  join by themselves; one pick holding two trips splits by itself. The owner is never asked "which trip?".
- **Every trip has a usable name with zero input**: "Nov 2025 · 12 days", shown beside a
  four-photo cover, so he recognises it by sight.
- **One optional spoken word** ("Egypt") while the photos download turns that into
  "Egypt · Nov 2025". Doing nothing is always fine.
- Everything else (time-zone clue, calendar lookup, landmark recognition, pasted album list) is
  an optional way to fill in that one word automatically.

## Facts checked for this note

| Fact | Status | Source |
|---|---|---|
| The picker link cannot be given a search word or an album in advance. The only documented addition is `/autoclose`; the only setting is `maxItemCount`. | Confirmed | [sessions reference](https://developers.google.com/photos/picker/reference/rest/v1/sessions) |
| Each picked item also carries `filename`, original `width`/`height`, `cameraMake`, `cameraModel` and `createTime`. The app reads only `id` and `baseUrl` today (Picker.java, `photos()`). | Confirmed | [mediaItems reference](https://developers.google.com/photos/picker/reference/rest/v1/mediaItems) |
| `createTime` is the capture time, written in UTC ("Z"). The page does not say what Google does when it doesn't know the photo's time zone. | Confirmed, with that gap **(unverified)** | same page |
| ML Kit's built-in image labelling knows 400+ general things ("Beach", "Mountain"), not places. Custom TFLite models can be plugged in. | Confirmed | [ML Kit image labeling](https://developers.google.com/ml-kit/vision/image-labeling) |
| Google's landmark recognition in ML Kit / Firebase ML is cloud-only (photos are sent to Google Cloud Vision, needs billing). No on-device version. | Confirmed (the docs are old but unchanged) | [Firebase landmark docs](https://firebase.google.com/docs/ml-kit/android/recognize-landmarks) |
| Google published on-device landmark classifiers per continent (`landmarks_classifier_asia_V1` etc., about 99,000 classes for Asia, trained on Google Landmarks v2). Size, licence and current download location not confirmed. | Partly confirmed **(details unverified)** | [ailia-models copy](https://github.com/vitaliyhan/ailia-models/blob/master/image_classification/landmarks_classifier_asia) |

Not checked here (the parallel agent's job): reading album names/dates from Google.

---

## Option 1. Automatic trips from dates, date names, cover pictures (zero input)

The foundation; every other option only improves the name.

**How trips are found.** After each download, the TV looks at all photos' capture days
(PhotoLibrary already stores `taken` and `day` per photo in meta.tsv):
1. Sort the shooting days. A run of days with no gap longer than 2 empty days is one group.
2. A group is a **trip** if it has at least 2 days and 80 photos (the owner's trips are 1,000-3,000
   over one to three weeks, so they pass easily). Smaller groups go to "Other photos <year>".
3. Because trips are recomputed from dates each time, a second pick of the same trip (needed
   above 2,000 photos) lands in the existing trip without a question, and a pick that contains
   Tokyo 2016 and Tokyo 2019 becomes two trips.
4. A name, favourite marks and "keep on TV" are stored against a date range, and follow whichever
   trip overlaps that range most, so a trip that grows keeps its name.

**How it's named with no input.** "Nov 2025 · 12 days"; across a month end "Oct-Nov 2025 ·
16 days"; two trips in one month "Early Nov 2025" / "Late Nov 2025".

**How the owner recognises it.** Settings → Trip opens a wall of cards (6 per screen), each a
four-photo cover, the name and "1,412 photos". Cover photos: favourites first; otherwise four
landscapes from four different days, taken from the largest bursts (scenes he shot many times),
skipping dark or blurred ones. Small cover files are made once in the background.

- **The owner's steps:** Add photos exactly as today. Later: ▲ → Trip → move to a card → OK.
- **Typed / said per trip:** nothing.
- **Accuracy:** grouping should be right for nearly every real trip (not yet run against real
  dates). Wrong cases: two trips less than 3 days apart merge; a trip with 3+ days without a
  single photo splits. Both fixable with one remote action on the card ("Join with next" /
  "Split here"), rarely needed.
- **Effort:** M (it is most of Phase 1 of the trips design, minus the naming screens).
- **Risks:** date names of old trips blur together ("Mar 2016" vs "May 2016"); the cover carries
  the recognition. the owner's partner's and the owner's phones need roughly correct clocks (phones set them
  automatically).
- **If album names/dates arrive:** an album's date range overrides the gap rule (the album is the
  truth about where a trip starts and ends) and its title becomes the name.

## Option 2. One spoken word, asked while the download runs

The download takes 5-15 minutes and the owner is on the sofa looking at a progress screen. That is
the moment to ask, and the question never blocks anything.

Screen during download (cover on the left, built from the first photos to arrive):

> **New trip: Nov 2025 · 12 days**
> Hold the mic button and say a name, for example "Egypt".
> Or do nothing: it stays "Nov 2025 · 12 days".

He says "Egypt". The TV shows **Egypt · Nov 2025** (the date part is always added by the TV, so
he never says the year and two Tokyo trips stay distinct). If it misheard: mic again. If the
pick only added to a trip that already has a name, no question is shown.
Later, from the frame: hold OK → photo menu → "Name this trip" does the same thing.

- **The owner's steps:** 1) ▼ Add photos, 2) pick on the phone as today, 3) say one word at the TV.
- **Typed / said per trip:** types nothing new; says one word, once per trip (optional).
- **Accuracy:** Google's speech recognition is generally good on country and city names; the
  result is shown before it is kept.
- **Effort:** S on top of Option 1.
- **How:** start Google's speech screen with `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
  **(unverified on this TCL; test: `adb shell am start -a android.speech.action.RECOGNIZE_SPEECH`)**.
  Fallback that the owner has confirmed exists: a text box that opens the TV keyboard with its mic key.
- **Risks:** the spoken word goes to Google's speech service (sound only, no photos). If the
  remote's mic button always opens Google Assistant instead, use the keyboard fallback.
- **If album names arrive:** the question is only shown for trips no album matched.

## Option 3. Time-zone clue: "7 hours ahead of home" and a short list to press

A measurement nobody has tried yet. The picker gives `createTime` in UTC; the downloaded file's
EXIF date is the camera's local wall time. Their difference is the UTC offset where the photo was
taken (photo taken 14:00 local, createTime 12:00Z → UTC+2). The TV knows its own home offset.

Uses:
- **Home or away** without guessing: a trip abroad shows a different offset from home; a block of
  days at the home offset between two away blocks is a real boundary (sharpens Option 1).
- **A hint on the card:** "Nov 2025 · 12 days · 7 h ahead of home".
- **A press-to-pick list instead of speaking:** offset + month gives a short list of likely
  countries (UTC+2 in November: Egypt, Greece, South Africa, Israel, Finland...; UTC+9: Japan,
  Korea). The naming screen shows them as buttons; ◀▶ + OK, no voice, no typing.

- **The owner's steps:** same as Option 2, but he can press OK on "Japan" instead of speaking.
- **Typed / said per trip:** nothing; one or two remote presses.
- **Accuracy:** the offset itself should be exact when Google knows where the photo was taken
  (phone photos with location on). **(unverified)**: for photos whose zone Google doesn't know,
  createTime may just repeat the local time, giving offset 0; treat "all zero" as "unknown".
  The country list is only a shortlist: many countries share an offset, and trips inside the owner's
  home time zone get no clue at all.
- **Effort:** S (store `createTime` from the picker response; one subtraction; a small table).
- **Risks:** only works for trips picked after the change (createTime isn't kept today). Needs a
  10-minute test first: one new pick with logging, compare 20 photos.

## Option 4. Ask the owner's Google Calendar what the trip was (fully automatic, new permission)

Gmail can put flights and hotel stays into Google Calendar by itself ("Flight to Cairo", "Stay
at ..., Luxor, Egypt"). With one extra read-only permission, the TV could look up the calendar for
the trip's dates and take the place from there: most frequent country in the event locations →
"Egypt · Nov 2025", no question asked.

- **The owner's steps:** once: sign in again and tick the extra "see your calendar events" box. Per
  trip: nothing.
- **Typed / said per trip:** nothing.
- **Accuracy:** high when the bookings went to the owner's Gmail and "Events from Gmail" is on; nothing
  when the owner's partner booked, or for road trips. **(unverified for the owner's account)**: he can check in one
  minute by opening Google Calendar on a past trip's dates.
- **Effort:** M (second scope in Auth.java, one Calendar API call per trip, location parsing).
- **Risks:** it widens what the app may read beyond the "Picker only" decision in CLAUDE.md, so it
  needs the owner's explicit yes. Calendar data stays on the TV. Official API, ToS-compliant.

## Option 5. On-device picture recognition to guess the place

What exists:
- ML Kit image labelling (on-device, small): says "Beach", "Temple", "Mountain". Enough for a
  flavour line ("beaches, boats") but not a place name. Not worth it for naming.
- Google's own landmark recognition is cloud-only: photos would leave the TV. **Ruled out by the
  privacy rule.**
- The per-continent TFLite landmark classifiers could run on the TV with the plain TensorFlow Lite
  runtime (it supports 32-bit ARM and needs no Google Play services or network). The TV would run
  it on about 150 photos per trip (one per burst, spread over the days), keep only confident
  answers that repeat across several scenes, and suggest the top one or two: "Giza pyramids?
  Karnak?".

- **The owner's steps:** none; the guess appears as the first button on the Option 2/3 naming screen.
- **Typed / said per trip:** nothing if the guess is right (one OK press).
- **Accuracy (estimate, not measured):** good on world icons (pyramids, Mount Fuji, Colosseum,
  Eiffel Tower); poor on streets, food, people, hotels, which are most of a trip. These models have
  no "not a landmark" answer and can be confidently wrong. It names a sight ("Fushimi Inari"),
  not the country. Expect a useful suggestion for perhaps half of the trips.
- **CPU:** roughly 0.2-0.5 s per photo on this TV (estimate, not measured), so 1-2 minutes per
  trip in the background, once. Must not run while a slide is being prepared.
- **Effort:** L (model files of tens of MB per continent in the APK or copied once; licence and
  download source to confirm; label handling; voting; tests). Option 3's offset could choose which
  continent's model to load.
- **Risks:** biggest build for the least certain result. Measure first on the PC with the 1,990
  first-trip photos (photos stay on the owner's PC) before deciding.

## Option 6. Hand over the words once (two variants)

**6a. "What will you search for?" before the picker.** The picker link cannot be pre-filled
(confirmed), so the word cannot be passed to Google; it can only be asked twice or copied.
The QR would open the TV's page first: one box "Trip name", then "Open Google Photos"; the page
copies the word so he can paste it into the picker's search box.
- **Typed per trip:** the name once on the phone (the same word he types in the search box today),
  plus a paste.
- **Accuracy:** exact when the pick is one trip; wrong when the pick holds two.
- **Effort:** S-M. **Risks:** blocked by the open bug (the owner's phone cannot open the TV's page); it
  adds a step to every pick; copying from a plain-http page is unreliable on phones. Voice at the
  TV (Option 2) gets the same word with less work. **Not recommended.**

**6b. Paste the album list once.** On the PC, open photos.google.com/albums, press Ctrl+A,
Ctrl+C, and paste into a box on the TV's page. The list shows each album's title and item count
(dates are probably not shown there **(unverified)**). The TV matches titles to trips by the year
in the title ("Egypt 2025" → the only 2025 trip) and by photo count; unmatched titles become a
press-to-pick list on the naming screen.
- **Typed / said per trip:** nothing; one paste for all trips, ever (repeat when new albums exist).
- **Accuracy:** good when the title has a year and one trip matches; otherwise it falls back to
  the pick list (still no typing). Counts differ from the TV's (videos are skipped), so count is
  only a tie-breaker.
- **Effort:** S-M. **Risks:** needs the PC (the owner would rather not); the pasted page text is
  messy and changes when Google redesigns the page; the TV's page must be open (Add photos
  screen). Reasonable as a one-evening catch-up for ten old trips, not as the main route.
- **If the parallel agent finds an official way to read album titles and dates**, this option is
  replaced by it: same matching, no paste.

---

## Recommendation

**Build Option 1 + Option 2 together. Log Option 3's measurement in the same build.**

1. **Option 1** makes trips appear, join and split by themselves and gives every trip a date name
   and a cover. From this point the owner never has to do anything.
2. **Option 2** adds the single spoken word during the download. Skipping it costs nothing.
3. **Option 3**: store `createTime` and log the offset in that same build (a few lines). If the
   first real pick shows sensible offsets, add the hint line and the press-to-pick country list in
   the next build.
4. **Option 4 (calendar)**: ask the owner one question first: "Open Google Calendar at the dates of your
   Egypt trip: do you see the flights?" If yes and he accepts the extra permission, it makes naming
   fully automatic and Option 2 becomes the fallback.
5. **Options 5 and 6**: not now. 6b is worth offering only if he wants ten old trips named in one
   evening and no official album route exists. 5 only after a PC measurement.

**Where a name comes from, strongest first:** the owner's spoken or picked name → album title (if the
parallel research finds an official route; matched by date range) → calendar place → landmark
guess (shown as a suggestion only, never applied silently) → date name. A lower source never
overwrites a higher one. The date part ("· Nov 2025") is always added by the TV.

### The owner's steps in the recommended flow

1. On the TV press ▼ (Add photos). Scan the code with the phone.
2. In Google's picker, search the album name and tick the days, then Done (same as today).
3. The TV starts downloading and shows the new trip's cover: "New trip: Nov 2025 · 12 days.
   Hold the mic and say a name, or do nothing."
4. Say "Egypt". The TV shows "Egypt · Nov 2025".
5. Trip over 2,000 photos: repeat steps 1-2 for the rest. The TV says "Added 950 more to
   Egypt · Nov 2025". No question.
6. Any time: ▲ → Trip → choose a cover → OK.

Typed per trip: nothing beyond the picker search he already does. Said per trip: one word, optional.

### Changes this implies (for whoever builds it)

- `Picker.Item`: also keep `createTime`, `filename`, `width`, `height`, `cameraMake/Model`
  (already in the response).
- `PhotoLibrary`: `trips()` computed from `meta` days (gap rule above); `names.tsv` holding
  `firstDay, lastDay, name, source, pinned`; trip filter applied before a round is built (as in the
  trips design); cover files in `files/library/covers/`.
- `AddPhotosActivity`: after the item list arrives, show the "New trip" panel beside the progress
  line; mic → speech intent → name saved. No "Which trip?" step.
- `SettingsPanel`: Trip row opens the cover wall; card menu on hold-OK: Name (mic), Join with
  next, Split here, Keep on TV.
- First run after the update: the 1,990 existing photos are grouped the same way and the owner is
  offered the one-word naming once.
- Tests to run before building: the speech intent on the TCL; one logged pick for the offset;
  the gap rule against the dates of the 1,990 photos already on the TV.
