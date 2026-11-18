# Photos Frame

A personal Google TV app (built for a TCL NXTFRAME) that shows your own Google Photos
full screen, edge to edge. Upright photos are shown three side by side, whole, with paper-coloured gaps. A "Framed" look
(off-white bevelled matte, dark walnut frame, charcoal wall) can be chosen in Settings.
Pictures change every 10 minutes (adjustable) with a slow 4-second fade.
Photos can get a "print" filter (▲ Settings: Off/Light/Moderate/Strong/Extra strong/Maximum, surface Paper or Canvas):
a warm paper white and ink-black ends, slightly quieter colour, a fine paper or canvas texture and a
soft gallery light, so they read like prints on the TV's matte screen. No blur or painterly effects.
It also works as the TV's screensaver.

## How photos get onto the frame
Press **▼** (or Menu) on the remote → "Add photos":
1. First time only: sign in from the phone. The TV shows a QR code for a tiny setup page it
   serves on the home Wi-Fi (`SetupServer.java`); the phone signs in to Google there and pastes
   the resulting `http://127.0.0.1:8765/?code=…` address back to the TV.
   (Google rejects the Picker scope in the TV "device code" flow — `invalid_scope` — so a
   **Desktop app** OAuth client with PKCE is used instead.)
2. Scan the QR code with your phone; Google Photos opens. Pick photos, tap **Done**.
3. The TV downloads them at 4K (3840×2160, aspect kept) into its own storage.
   Re-picking later adds to what is already there. Each photo is roughly 5-15 MB, so
   2,000 photos need about 10-30 GB; the TV warns on screen when storage is low and stops
   adding (but keeps rotating) when it is full (`Storage.java`).

Photos come only through the official Google Photos **Picker API**
(scope `photospicker.mediaitems.readonly`). Photos saved to your library from
Partner Sharing are pickable too.

Photo order: a saved shuffle (continues after restarts), near-identical burst shots shown one per round,
"One day at a time", favourites (Play: Favourites only) and hiding, via a long press on OK
(`PhotoLibrary.java`, `PhotoMenu.java`).

The remote's art button can open Photos Frame instead of TCL's T-Exhibition (optional accessibility
helper `ArtKeyService.java`; switched on in TV Settings → Accessibility).

On the frame: **▶ / OK** next photo; **◀** previous photo; **▲** Settings page (time between
photos 30 s to 1 hour, art filter level, Add photos; `SettingsPanel.java`); **▼** Add photos.

## Private files (not in git)
- `app/src/main/assets/oauth_client.json` — the **Desktop app** OAuth client JSON from
  Google Cloud (not the "TVs and Limited Input devices" one; Google won't allow Photos on that).
  Without it the app shows "Not set up yet".
- `app/src/main/assets/photos/` — optional placeholder photos shown before any are picked.

## Build
Needs JDK 17+, Android SDK (platform 34) and Gradle 8.14:
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease   # → app/build/outputs/apk/release/app-release.apk (signed with the debug key)
```

## Install on the TV
Full step-by-step guide: [INSTALL.md](INSTALL.md). Short version:
On the TV: Settings → System → About → click "Android TV OS build" 7× →
Developer options → USB debugging (and network debugging if present). Then:
```
adb connect <TV-IP>
adb install -r app-release.apk
# make it the screensaver (Google TV hides third-party screensavers in Settings):
adb shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
```

## tools/
- `picker_test.py` — the original Picker spike (desktop OAuth client, Python stdlib).
- `preview.py` — renders a framed, art-treated preview of a photo on a computer
  (`python3 tools/preview.py photo.jpg out.jpg 0.5`). Mirrors the pre-v0.8 filter; the current print
  filter is specified in `docs/print_filter_spec.md`.
