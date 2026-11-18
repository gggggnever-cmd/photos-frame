# Photos Frame — notes for Claude

Personal Google TV app for the owner's TCL 85" A300W (NXTFRAME): shows his own Google Photos as a
framed picture. Personal use only, sideloaded. Read README.md and INSTALL.md first.

## Decisions already made (don't re-open without the owner)
- Photo source: Google Photos **Picker API** only (scope photospicker.mediaitems.readonly).
  Shared albums are out (Google closed API access March 2025). Partner-sharing photos work once
  saved into the owner's library.
- Sign-in: Google rejects the Picker scope in the TV device-code flow (invalid_scope, tested).
  So: **Desktop** OAuth client + PKCE, done from the phone via the TV's local page
  (SetupServer.java, port 8080); user pastes the http://127.0.0.1:8765/?code=… address back.
- Look (keep exactly): off-white bevelled matte, dark walnut frame, charcoal wall, one photo,
  10-minute change, 4-second fade, few-pixel drift. Art treatment on every photo
  (ArtTreatment.STRENGTH, default 0.5 = moderate). Not a vivid slideshow / Ambient Mode look.
- Photos download at =w3840-h2160 (~5-15 MB each); target ≥2,000; warn when storage is low
  (<5 GB), stop adding below 2 GB reserve, keep rotating what's cached.

## Private files (gitignored — never commit)
- app/src/main/assets/oauth_client.json — the Desktop client JSON (the owner's client_secret.json).
- app/src/main/assets/photos/ — optional placeholder photos shown before any are picked.

## Build / install
gradle assembleDebug (JDK 17+, Android SDK platform 34, Gradle 8.14, AGP 8.7.3).
Install/update on TV: tools/update_tv.sh or tools/update_tv.bat (adb over Wi-Fi).

## Status (end of first session)
- v0.4 built; not yet run on the real TV. First install = first real test.
- To do: install + test on TV; optionally publish the Google Cloud consent screen
  ("Publish app") so sign-ins don't expire after 7 days.
