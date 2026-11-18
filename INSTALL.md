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
1. Open **Photos Frame** from the TV's apps.
2. Press **▼ (down)** on the remote. The "Add photos" screen opens.
3. **First time only — sign in (about 2 minutes, done on your phone):**
   1. Make sure your phone is on the **same Wi-Fi** as the TV.
   2. Scan the QR code on the TV with your phone's camera. A small "Photos Frame" page opens.
   3. Tap **Sign in with Google**, choose your account, tap **Allow**.
      Google may say the app isn't verified: tap **Advanced → Go to Photos Frame (unsafe)**.
      (It's your own app; this warning is normal for personal projects.)
   4. You land on a page that says **"This site can't be reached"**. That's expected.
      Tap the address bar and **copy the whole address** (it starts with `http://127.0.0.1:8765`).
   5. Switch back to the "Photos Frame" tab, **paste** it in the box, tap **Send to TV**.
      The page says "Done!" and the TV moves on by itself.
4. **Pick photos:** the TV now shows a new QR code. Scan it; Google Photos opens.
   Select photos (up to 2,000 at a time), then tap **Done**.
5. The TV downloads them (a few seconds each) and returns to the frame.

To add more later, press **▼** again — new picks are added to the photos already there,
and you normally won't need to sign in again.
Photos your wife shares through **Partner Sharing** can be picked too, as long as Partner
Sharing is set to **save them into your library** (Google Photos → Settings → Sharing →
Partner sharing → Save to your account → All photos).

**Avoid signing in every week (one-time, on a computer):** while the Google Cloud project is
in "Testing" mode, Google ends the TV's sign-in after 7 days, so the next "Add photos" would
ask you to sign in again. To stop that: go to **console.cloud.google.com**, pick project
**<your Cloud project>**, search **"Google Auth Platform"** (or "OAuth consent screen"),
open **Audience**, and click **Publish app → Confirm**. Nothing becomes public — it only
changes how long sign-ins last. (The photos already on the TV keep showing either way.)

## Using the frame
- **▶** or **OK** — next picture now.
- **◀** — go back to the previous picture.
- **▲** — open **Settings**: use ▲▼ to pick a line and ◀▶ to change it, then **Back** to close.
  - **Change photo every:** 1 minute, 5 minutes, 10 minutes (the starting setting), 30 minutes or 1 hour.
  - **Art filter:** Off, Light, Moderate (the starting setting) or Strong. You see the change straight away.
  - **Look:** Full screen (the starting setting; upright photos appear 2 or 3 side by side) or Framed.
  - **Add photos…** — press OK.
  The TV remembers your choices, and the screensaver uses them too.
- **▼** — add photos.
- The TV and phone must be on the same Wi-Fi only while signing in.
- **Back / Home** — leave the app.

## Storage
Each photo is about 5–15 MB, so 2,000 photos take roughly 10–30 GB; the TV has about
50 GB free. When space runs low the frame shows a short warning, and if it gets full
it stops adding photos but keeps showing the ones it already has.

## Updating the app later
Repeat step 3 with the new `PhotosFrame.apk` (`adb connect …`, then `adb install -r …`).
Your photos and sign-in are kept.

## Updating later
Put `tools/update_tv.sh` (Mac/Linux) or `tools/update_tv.bat` (Windows) in the `platform-tools`
folder with the new `PhotosFrame.apk`, then run `./update_tv.sh 192.168.1.42` (or `update_tv.bat 192.168.1.42`).
After the first run it remembers the TV, so next time just `./update_tv.sh`. If your computer is
connected to Claude through Remote Control, Claude can run this for you.
