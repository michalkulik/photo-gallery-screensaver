# Photo Gallery Screensaver

An Android TV / Google TV screensaver (daydream) that plays photos on the idle screen. Photos can
come from a folder on the device or from Google Photos, and the slideshow is fully configurable
from a D-pad friendly TV interface.

The app ships with an English and a Polish UI.

---

## ⚠️ Important: how Google Photos access works

As of **1 April 2025** Google removed the `photoslibrary.readonly` scope from the Photos Library
API. A third-party app can **no longer list a user's existing albums or library**.

The only supported way for an app like this to reach photos a user already has is the
**Google Photos Picker API**:

1. the app opens a picking session and shows a link / QR code,
2. the user selects albums or photos in Google Photos on a phone or computer,
3. the app downloads the selection to the TV so the screensaver works offline.

Consequences you should know about:

* **You need your own OAuth client.** The client id/secret are entered in the app and stored on the
  device. There is no bundled client, so the app never ships anyone's credentials.
* **"Recent highlights" is not available.** Google does not expose that collection to third-party
  apps. Albums and individual photos are what the Picker API offers.
* **Picked photos are copied to the TV.** Google's photo URLs expire after about an hour, so the
  bytes are cached locally. This also means the screensaver keeps working without a network.

The built-in Google TV ambient screensaver offers a simpler "just pick your account" flow, but that
experience belongs to Google's own privileged system apps (`com.google.android.apps.tv.dreamx`,
`com.google.android.backdrop`), which use signature-level permissions such as
`GET_ACCOUNTS_PRIVILEGED`. Those are not available to third-party applications.

---

## Features

**Photo sources**

* A folder from the TV's own storage (via MediaStore), or every image on the device.
* Albums or photos picked from Google Photos, downloaded for offline playback.

**Slideshow**

* Interval from 3 s to 10 minutes.
* Shuffle or sequential order.
* Fade or slide transition.
* Ken Burns (slow zoom) on or off.
* Fit to screen (`cover`) or letterbox (`contain`).
* Optional clock overlay.
* Adjustable screen dimming.

**Screensaver integration**

* Registers a real `DreamService`, so Android TV lists it under the system screensaver settings.
* In-app full-screen preview so settings can be checked without waiting for the screensaver.
* A shortcut on the main screen that opens the system screensaver picker.

---

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer |
| Target | Android TV / Google TV |
| Build | JDK 17+, Android SDK (compileSdk 37) |

Written in Kotlin. The only runtime dependencies are `androidx.core`, `kotlinx-coroutines` and
ZXing (used to render the sign-in and picker QR codes).

---

## Setting up Google Photos

You only need this for the Google Photos sources; local folders work without any of it.

### 1. Create a Google Cloud project

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project.
2. Enable the **Photos Picker API** for that project.

### 2. Create an OAuth client for a TV

1. Go to **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. Application type: **TVs and Limited Input devices**.
3. Copy the generated **Client ID** and **Client secret**.

This client type is what makes the sign-in work on a TV: instead of a browser redirect, Google
shows a short code that you type on your phone at `google.com/device`.

> While the OAuth consent screen is in **Testing** mode, only accounts added under **Test users**
> can sign in. Add your Google account there, or publish the app.

### 3. Enter the credentials on the TV

Open the app → **Google Photos account** → set **OAuth Client ID** and **OAuth Client secret** →
**Sign in with Google**. A code and a QR code appear; confirm on your phone. Then use
**Pick album or photos** to import a selection.

Changing the client id or secret automatically signs you out, because a refresh token is bound to
the client that issued it.

---

## Building

```bash
# Debug build (applicationId gets a .debug suffix so it can sit next to a release build)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Unit tests
./gradlew test
```

### Release signing

The release build is only signed when these properties are present (typically in the untracked
`gradle.properties` or `~/.gradle/gradle.properties`):

```properties
keystore.file=keystore/release.jks
keystore.password=...
signing.key.alias=...
signing.key.password=...
```

Then:

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The keystore and passwords are intentionally **not** committed. Keep your keystore safe: Android
only accepts an update if it is signed with the same key.

---

## Installing on a TV

```bash
adb connect <tv-ip>:5555      # for a network device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Selecting it as the screensaver

On the TV: **Settings → Display & Sound → Screen saver → choose “Photo Gallery Screensaver”**.

Some Android TV boxes do not expose that screen to apps. The main screen has an **Open screensaver
settings** entry that tries the standard intents and, when the firmware does not answer, shows the
path to use manually. Note that a few boxes keep their own ambient screensaver regardless of this
setting.

If you want to set it over adb:

```bash
adb shell settings put secure screensaver_components \
  com.michalkulik.photogallery/com.michalkulik.photogallery.dream.PhotoDreamService
adb shell settings put secure screensaver_enabled 1
```

> Use the `com.michalkulik.photogallery.debug` package name for a debug build.

After force-stopping the app over adb, launch it once from the TV launcher before the system will
use it as a screensaver again — Android will not start a force-stopped package as a dream.

---

## How it works

```
ui/       D-pad TV screens built in code (no layout XML)
dream/    PhotoDreamService + SlideshowView, shared with the in-app preview
data/     PhotoSource model, MediaStore access, Google photo cache
google/   OAuth device flow, Photos Picker API client, importer
core/     Settings (SharedPreferences) and a small service locator
```

* **`SlideshowView`** is used by both the screensaver and the preview screen, so what you see in the
  preview is exactly what the screensaver plays. It keeps at most two decoded bitmaps alive and
  pre-decodes the next photo while the current one is shown, so advancing never flashes black.
* **`BitmapLoader`** downsamples to roughly screen size, so memory stays flat regardless of the
  original resolution, and applies the EXIF orientation reported by MediaStore.
* **`PhotoCache`** stores imported Google Photos as files under the app's private storage.

---

## Troubleshooting

**The screensaver is not in the system list.**
Make sure the app has been launched at least once and is not force-stopped.

**Sign-in fails with `invalid_grant`.**
The credentials changed or access was revoked. Enter the credentials again and sign in.

**The picker session expires.**
Picking sessions time out. Start the selection again and confirm it in Google Photos.

**Photos do not appear after importing.**
Check that the imported source is the active one on the main screen, and use **Preview**.

**A source shows nothing / a black screen.**
The screensaver reports unreadable sources on screen instead of staying black. Re-import the source
or pick another folder.

---

## License

Personal project. No license is granted for redistribution.
