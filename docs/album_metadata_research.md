# Can anything legitimately read the owner's Google Photos album names and date ranges? (without downloading the photos)

Research only, no code changed. Sources checked 2026-11-18. Pages were read through a fetch tool that
summarises; where wording is quoted it is as the tool returned it. Items marked **(unverified)** could
not be confirmed from an official page.

## Short answer

**No app or PC tool can read album names or album date ranges from Google by itself, by any
ToS-compliant API, in late 2026.** There is no "metadata only" export either. The two legitimate ways
to get the information into the app are:

1. **The owner types it once** (trip name, first day, last day), reading the dates off each album in Google
   Photos. About a minute per trip. No Google dependency at all.
2. **A Takeout of the trip albums** (or album "Download all"). Folder name = album title; every photo has
   a JSON sidecar with its taken time (and GPS, which Picker downloads lack). But the media comes with
   it, so this only makes sense as a by-product of the Phase 2 "PC trip loader", which needs those
   downloads anyway.

Also still true: when the owner picks a whole trip through the Picker, the app already gets every item's
`createTime`, so the date range is free; only the name needs typing once (already in the Trips design).

## Yes/no table

| Route | Album names + dates readable by an app/PC tool? | Without media? | Usable by the owner (Canada, personal Testing-mode app)? | Verdict |
|---|---|---|---|---|
| Photos Library API (`albums.list`) | Only albums the app itself created | n/a | Works, but returns nothing useful | **No** |
| Removed Library scopes via Testing mode | Scopes are removed for everyone, not merely "unverified-restricted" | n/a | No | **No** |
| Photos Picker API | No album, location, description or search-text field | n/a | Yes (in use) | **No** for albums; **Yes** for dates of picked items |
| Data Portability API | No Google Photos resource group exists | n/a | Not offered in Canada anyway | **No** |
| Google Takeout, chosen albums | **Yes**: folder per album + JSON sidecars | **No**, media always included | Yes (manual, the owner's own export) | **Yes, but heavy** |
| Takeout "metadata only" | Option does not exist | - | - | **No** |
| Album "Download all" zip on photos.google.com | Zip/folder named after album; dates from EXIF | No, it is the media | Yes (manual) | **Yes, but heavy** |
| Google Photos web/Android album view | Human can see it; no app can read it | Yes | Yes | **The owner can see it, then type it** |
| Ask Photos (in Google Photos) | Human only | Yes | Canada availability **unverified** | Maybe, for the owner's eyes only |
| Gemini app + Google Photos | Human only; no developer access | Yes | Conflicting: help-page country list has no Canada; Personal Intelligence (incl. Photos) launched in Canada Apr 2026 for paid plans | Maybe, for the owner's eyes only |
| Google TV Photos search / Gemini voice | Human only | Yes | US + English only | **No** (in Canada) |
| Google Maps Timeline export (on phone) | Trip dates and places, not album names | Yes | Yes (manual export on the phone) **(unverified menu path)** | Possible alternative source of trip dates |
| Scraping photos.google.com, shared-album link pages, private endpoints, browser cookies | - | - | - | **Excluded (against the rules / grey)** |

## Details and sources

### 1. Photos Library API (2026)
- Removed after 31 Mar 2025: `photoslibrary.readonly`, `photoslibrary.sharing`, `photoslibrary`. Remaining:
  `photoslibrary.appendonly`, `photoslibrary.readonly.appcreateddata`, `photoslibrary.edit.appcreateddata`.
  `albums.list` is in the group described as: "These methods can now only be used with albums and media
  items created by your app." Sharing methods return `403 PERMISSION_DENIED`.
  Source: https://developers.google.com/photos/support/updates
- Authorization page: "After March 31, 2025, you will only be able to access content created by your
  application." `photoslibrary.readonly.appcreateddata` = "Read access to media items and albums created
  by the developer." No scope lists albums the app did not create.
  Source: https://developers.google.com/photos/overview/authorization
- Nothing loosened since: the newest release note is still 1 April 2025; no late-2025 or 2026 entries.
  Source: https://developers.google.com/photos/support/release-notes
- Testing mode does not help. The old scopes were deleted from the API, not placed behind verification,
  so a Testing-mode app with the owner as test user cannot request them. (Inference from the two pages above;
  no page describes a testing exception.)
- An app-created album is no workaround: the app can only put app-uploaded items into albums it created.

### 2. Picker API
- Fields of a picked item: `id`, `createTime`, `type`, `mediaFile` (`baseUrl`, `mimeType`, `filename`,
  `mediaFileMetadata`: `width`, `height`, `cameraMake`, `cameraModel`, photo/video metadata). No album,
  location, description or search-query field.
  Source: https://developers.google.com/photos/picker/reference/rest/v1/mediaItems
- Note: the reference lists `cameraMake`/`cameraModel` in the Picker's JSON response. The project notes
  say the camera model is stripped from the downloaded file; it may still be available in the API
  response. Not tested here.

### 3. Google Data Portability API
- The scope list has these product prefixes only: alerts, businessmessaging, chrome, discover, fitbit,
  maps, myactivity, mymaps, order_reserve, play, saved, search_ugc, searchnotifications, shopping,
  streetview, youtube. The only "photos" scope is `dataportability.maps.photos_videos` (photos posted on
  Maps). **There is no Google Photos resource group.**
  Source: https://developers.google.com/data-portability/user-guide/scopes
- Regions: 29 European countries (EEA members + Switzerland) and the UK. **Canada is not included.**
  Source: https://support.google.com/accounts/answer/14452558
- Apps need verification before publishing (identity, privacy policy, demo video; security assessment
  for restricted scopes). Whether a Testing-mode app can call it for its own test user is not stated.
  Moot for the owner on both counts above.
  Source: https://developers.google.com/data-portability/user-guide/overview

### 4. Google Takeout
- Official help: within a product you can open "All data included" and untick items; delivery by link
  or Drive/Dropbox/OneDrive/Box; "from a few minutes to a few days"; archive "expires in about 7 days",
  5 downloads each; up to 50 GB per archive file; scheduled export every 2 months for a year. For photos,
  extra metadata goes "into a separate JSON file". **No metadata-only option is mentioned.**
  Source: https://support.google.com/accounts/answer/3024190
- Choosing only certain albums ("All photo albums included"): confirmed by guides, not the official page.
  Sources: https://ente.io/articles/exporting-google-photos-with-takeout ,
  https://www.iit.edu/ots/google-microsoft-365/migrating-google-photo-library/use-google-takeout-export-google-photos
- What an album folder holds (third-party tool docs, **not a Google spec**): the folder is named after the
  album; a folder-level `metadata.json` with the album title, description and date under `albumData`; one
  JSON sidecar per photo (names vary, e.g. `IMG_1.jpg.supplemental-metadata.json`) with `photoTakenTime`
  and `geoData` latitude/longitude. Whether the folder-level `metadata.json` is still present in 2026
  exports is **unverified**; the folder name and per-photo sidecars are enough either way.
  Sources: https://photostructure.com/guide/albums/ ,
  https://github.com/laurentlbm/google-photos-takeout-date-fixer ,
  https://exiftool.org/forum/index.php?msg=90811
- Size and time: the export is the albums' original files. Ten trips of ~2,000 phone photos at 3-5 MB each
  is very roughly 60-100 GB (estimate, not measured); expect hours to a day or more, in 50 GB pieces.
- Useful side effect: the sidecars carry GPS, which Picker downloads do not.

### 5. Ways for the owner to see the information himself
- Google Photos album view: items in an album can be sorted Newest / Oldest / Recently added, so the first
  and last photo dates are two taps away. Albums can be sorted by title, last modified or most recent
  photo. Source: https://support.google.com/photos/answer/6128849
  Whether the album header itself prints a date range is **unverified** (no official page says so);
  the owner can simply look.
- Ask Photos: "rolling out to a select number of countries and regions"; supports questions "about your
  photos and albums" (example: "What did I eat on my trip to Barcelona?"). Country list not given on the
  page; a Nov 2025 expansion to "over 100 countries" is reported without naming Canada. **Canada unverified.**
  Sources: https://support.google.com/photos/answer/15318661 ,
  https://www.googlewatchblog.de/2025/11/google-fotos-neue-ki-suchfunktion-ask-photos-startet-in-vielen-neuen-laendern-und-sprachen-bald-in-eu/
- Gemini app connected to Google Photos: can find "photos, videos, and albums" by date, place, people,
  description ("Show my photos from my most recent trip"). The help page's country list does **not**
  include Canada. Separately, Gemini "Personal Intelligence" (Gmail, Photos, Search, YouTube) launched in
  Canada on 14 Apr 2026 for paid AI plans, free users "in the coming weeks". The two sources conflict, so
  treat as **unverified for the owner**; trying it costs him one minute. No developer API to this exists.
  Sources: https://support.google.com/photos/answer/17597604 ,
  https://www.iphoneincanada.ca/2026/04/14/google-gemini-can-now-see-your-gmail-and-photos-in-canada-heres-how/
- Google TV: albums can be used for screensaver/slideshows; Photos search and Gemini voice need "US
  location and English language" (expansion promised for "summer 2026", page not updated). Not for Canada.
  Source: https://support.google.com/photos/answer/16996534
- Google Maps Timeline: since 2024 it lives on the phone and can be exported as JSON from the phone's
  settings (third-party guides; menu path varies; **unverified** against an official page). It would give
  trip dates and places, not album names.
  Sources: https://www.androidauthority.com/android-google-maps-location-history-3491255/ ,
  https://dawarich.app/blog/whats-inside-your-google-timeline-export/

### 6. Excluded
Scraping photos.google.com or a shared-album link page, unofficial/private endpoints, reusing browser
cookies or tokens, tools that pose as a Google app. A public shared-album link does show a title, but
having the app fetch and parse it is scraping: grey, excluded.

## Recommendation

**Best route: a small "trip calendar" the owner fills in once.** A list of rows: name, first day, last day
(e.g. `Egypt 2025, 2025-11-01, 2025-11-15`), entered on the TV's phone page or kept as a text file on
the PC and pushed with the update script. The TV then auto-names any later pick whose EXIF date falls
in a range. Effort for the owner: open each album, sort Oldest then Newest, note two dates; about a minute per
trip, once. Effort to build: small.

**Free extras that fill the calendar automatically:**
- Any trip picked whole through the Picker: range = min/max `createTime`; the owner only confirms the name.
- If Phase 2 (PC trip loader) is built: the loader reads the album folder name and the photos' dates from
  the Takeout/album zip and writes the calendar row itself. Zero extra effort then, but it is not worth
  a 60-100 GB export just for names and dates.
