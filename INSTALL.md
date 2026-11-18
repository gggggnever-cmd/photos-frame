# Installing Photos Frame on the TCL TV

You need: the TV, a computer (Windows, Mac or Linux) on the **same Wi-Fi** as the TV,
the file `PhotosFrame.apk`, and your phone. Setup takes about 15 minutes, once.

## 1. Turn on developer mode on the TV (one time)
1. On the TV: **Settings → System → About**.
2. Scroll to **Android TV OS build** and press **OK 7 times**, until it says
   "You are now a developer".
3. Go back to **Settings → System → Developer options** and turn on **USB debugging**.
   If you also see **Wireless debugging** or **Network debugging**, turn that on too.
4. Write down the TV's IP address: **Settings → Network & Internet → (your Wi-Fi)**,
   e.g. `192.168.1.42`.

## 2. Install the connection tool on the computer (one time)
1. Download Google's **SDK Platform-Tools** for your computer:
   https://developer.android.com/tools/releases/platform-tools
2. Unzip it. Open a terminal (Mac/Linux) or Command Prompt (Windows) **in that
   `platform-tools` folder**. Copy `PhotosFrame.apk` into the same folder.
   (On Mac/Linux, type `./adb` instead of `adb` in the commands below.)

## 3. Install the app
1. Type: `adb connect 192.168.1.42` (use your TV's IP address).
2. The TV asks **"Allow USB debugging?"** — tick **Always allow from this computer**, choose **Allow**.
   Then run the same `adb connect` command again; it should say `connected`.
3. Type: `adb install -r PhotosFrame.apk` — wait for `Success`.

**Photos Frame** now appears in the TV's apps. To put it on the home screen row:
**Apps → See all apps →** highlight Photos Frame → hold **OK** → **Add to favourites**.

## 4. Make it the screensaver (recommended)
Google TV doesn't list other screensavers in Settings, so this is set from the computer:
```
adb shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
adb shell settings put secure screensaver_enabled 1
```
Now when the TV sits idle it shows your framed photos instead of Google's Ambient Mode.
To choose **how soon** it starts, use the TV's own setting:
**Settings → System → Ambient mode / Screensaver → When to start** (names vary by software version).

To keep the pictures up for hours, check that the TV isn't set to switch the screen off:
**Settings → System → Power & Energy** — set the "turn off screen / auto power off" timer
to the longest option or **Never**.

To go back to Google's screensaver later:
```
adb shell settings delete secure screensaver_components
```

## 5. Add your photos (any time)
1. Open **Photos Frame** (or let the screensaver start, then open the app).
2. Press **▼ (down)** on the remote. The "Add photos" screen opens.
3. **First time only — sign in:** scan the QR code with your phone (or go to
   **google.com/device**), enter the code shown on the TV, choose your Google account and
   **Allow**. Google may warn that the app is unverified: tap **Advanced → Go to Photos Frame**.
4. **Pick photos:** scan the next QR code with your phone. Google Photos opens.
   Select photos (up to 2,000 at a time), then tap **Done**.
5. The TV downloads them (a few seconds each) and returns to the frame.

To add more later, press **▼** again — new picks are added to the photos already there.
Photos your wife shares through **Partner Sharing** can be picked too, as long as Partner
Sharing is set to **save them into your library** (Google Photos → Settings → Sharing →
Partner sharing → Save to your account → All photos).

## Using the frame
- **OK** or **▶** — next picture now. Otherwise each picture stays 10 minutes.
- **▼** — add photos.
- **Back / Home** — leave the app.

## Storage
Each photo is about 5–15 MB, so 2,000 photos take roughly 10–30 GB; the TV has about
50 GB free. When space runs low the frame shows a short warning, and if it gets full
it stops adding photos but keeps showing the ones it already has.

## Updating the app later
Repeat step 3 with the new `PhotosFrame.apk` (`adb connect …`, then `adb install -r …`).
Your photos and sign-in are kept.
