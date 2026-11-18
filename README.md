# Photos Frame

A personal Google TV app (built for a TCL NXTFRAME) that shows your own Google Photos
as a framed picture: off-white bevelled matte, dark walnut frame, charcoal wall,
one photo at a time, changing every 10 minutes with a slow 4-second fade.
Each photo gets an "art print" treatment when shown (◀ on the remote cycles Off/Light/Moderate/Strong,
saved in `Settings.java`; default Moderate = 0.5):
lower, softer light, gentler matte contrast, slightly quieter warm colour, a faint
gallery-light falloff and fine paper grain — no blur or painterly effects.
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

On the frame: **OK / ▶** skips to the next photo; **◀** changes the art filter level.

## Private files (not in git)
- `app/src/main/assets/oauth_client.json` — the **Desktop app** OAuth client JSON from
  Google Cloud (not the "TVs and Limited Input devices" one; Google won't allow Photos on that).
  Without it the app shows "Not set up yet".
- `app/src/main/assets/photos/` — optional placeholder photos shown before any are picked.

## Build
Needs JDK 17+, Android SDK (platform 34) and Gradle 8.14:
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## Install on the TV
Full step-by-step guide: [INSTALL.md](INSTALL.md). Short version:
On the TV: Settings → System → About → click "Android TV OS build" 7× →
Developer options → USB debugging (and network debugging if present). Then:
```
adb connect <TV-IP>
adb install -r app-debug.apk
# make it the screensaver (Google TV hides third-party screensavers in Settings):
adb shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
```

## tools/
- `picker_test.py` — the original Picker spike (desktop OAuth client, Python stdlib).
- `preview.py` — renders a framed, art-treated preview of a photo on a computer
  (`python3 tools/preview.py photo.jpg out.jpg 0.5`); mirrors FrameRenderer/ArtTreatment.
