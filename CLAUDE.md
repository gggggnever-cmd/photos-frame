# Photos Frame — everything a new session needs

The owner has already explained all of this once. **Do not ask him to repeat it.** Read this file,
README.md and INSTALL.md, then carry on. Ask only about things that are genuinely new.

## Who and what
- **The owner** owns the project and the TV. His assistant **the owner's assistant** sometimes drives sessions for him.
  the owner's wife is **the owner's partner**; they use Google Photos Partner Sharing so either phone can take trip photos.
- **TV:** TCL 85" A300W (NXTFRAME), Google TV, ~64 GB storage (~50 GB free for apps).
  Not OLED. TV IP address: not recorded yet — ask once, then write it here.
- **Goal:** a personal (sideloaded, not Play Store) Google TV app that shows the owner's own Google Photos
  like a framed picture on the wall, also usable as the TV's screensaver, with new photos easy to add.
- **How the owner likes instructions:** plain, numbered, step-by-step, "as if for a 10-year-old". Short status
  reports: what's done, what isn't, what he needs to do. Don't make him repeat himself.

## Look — keep exactly unless the owner asks
- Off-white bevelled matte, dark walnut frame, charcoal wall, soft shadow (FrameRenderer.java).
- One photo at a time; 10-minute change; slow 4-second fade; a few pixels of drift per change.
- **Art treatment on every photo** (ArtTreatment.java; level chosen with ◀ on the remote, Settings.java; default Moderate = 0.5): lower/softer light,
  gentle matte contrast, slightly quieter warm colour, faint edge falloff, fine paper grain. Like TCL's
  T-Exhibition / picture-frame TVs. The owner explicitly rejected Google's vivid full-screen Ambient Mode look.
- Remote: OK/▶ = next photo, ◀ = art filter level (Off/Light/Moderate/Strong, saved; default Moderate),
  ▼/Menu = Add photos, Back/Home = leave.

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
`gradle assembleDebug` (JDK 17+, Android SDK platform 34 + build-tools 34, Gradle 8.14, AGP 8.7.3,
`sdk.dir` in local.properties). Output: app/build/outputs/apk/debug/app-debug.apk.
tools/preview.py renders a framed, art-treated preview of a photo on a computer.

## Status / next steps (update this list as things change)
- [x] v0.4 built and pushed: frame look, art treatment, screensaver, phone sign-in, picker,
      4K download + cache, storage warnings, INSTALL.md, update scripts.
- [ ] First install on the real TV (nothing has run on the TV yet). Then test: sign-in, pick, download,
      screensaver, update script.
- [ ] Publish the consent screen so sign-ins don't expire weekly.
- [ ] Record the TV's IP above.
- Possible later: in-app "check for updates"; tuning art strength / matte size / timing after the owner
  lives with it for a few days.
