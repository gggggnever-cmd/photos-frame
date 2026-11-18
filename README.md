# Photos Frame

A personal Google TV app (built for a TCL NXTFRAME) that shows your own Google Photos
as a framed picture: off-white bevelled matte, dark walnut frame, charcoal wall,
one photo at a time, changing every 10 minutes with a slow 4-second fade.
It also works as the TV's screensaver.

## How photos get onto the frame
Press **▼** (or Menu) on the remote → "Add photos":
1. First time only: sign in to Google with the code shown on screen (google.com/device).
2. Scan the QR code with your phone; Google Photos opens. Pick photos, tap **Done**.
3. The TV downloads them at 4K (3840×2160, aspect kept) into its own storage.
   Re-picking later adds to what is already there.

Photos come only through the official Google Photos **Picker API**
(scope `photospicker.mediaitems.readonly`). Photos saved to your library from
Partner Sharing are pickable too.

On the frame: **OK / ▶** skips to the next photo.

## Private files (not in git)
- `app/src/main/assets/oauth_client.json` — "TVs and Limited Input devices" OAuth
  client from Google Cloud. Without it the app shows "Not set up yet".
- `app/src/main/assets/photos/` — optional placeholder photos shown before any are picked.

## Build
Needs JDK 17+, Android SDK (platform 34) and Gradle 8.14:
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## Install on the TV
On the TV: Settings → System → About → click "Android TV OS build" 7× →
Developer options → USB debugging (and network debugging if present). Then:
```
adb connect <TV-IP>
adb install -r app-debug.apk
# make it the screensaver (Google TV hides third-party screensavers in Settings):
adb shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
```

## tools/picker_test.py
The original Picker spike (desktop OAuth client, Python stdlib).
