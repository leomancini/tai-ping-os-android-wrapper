# tai-ping-os-android-wrapper

Fullscreen, landscape-locked Android WebView wrapper around the `tai-ping-os.leo.gd`
web app (kiosk-style). All the app logic lives in the web app; this project is just
the native host (`MainActivity.java`).

## Building & running (this machine)

Neither `java` nor `adb` is on `PATH`. Use these explicit paths:

- JDK 17 (homebrew): `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- adb (cmdline-tools SDK at `/opt/homebrew/share/android-commandlinetools`):
  `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`

Build, install, launch:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
./gradlew assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell monkey -p com.leomancinidesign.taipingos -c android.intent.category.LAUNCHER 1
```

The API key is injected at build time from a gitignored `secrets.properties`
(`TAI_PING_API_KEY`) via `BuildConfig`.

### Flashing to the phone

1. Connect the phone over USB (USB debugging must be enabled in Developer
   Options). Verify it shows up — should list the device, e.g. `ZL83243X98 device`:

   ```sh
   $ADB devices
   ```

   First connection: accept the "Allow USB debugging?" RSA prompt on the phone.

2. Build, then flash + relaunch in one go (`-r` reinstalls over the existing
   app, keeping its data; `force-stop` ensures a clean restart):

   ```sh
   ./gradlew assembleDebug
   APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
   $ADB install -r "$APK"
   $ADB shell am force-stop com.leomancinidesign.taipingos
   $ADB shell monkey -p com.leomancinidesign.taipingos -c android.intent.category.LAUNCHER 1
   ```

If `install` fails with a signature/downgrade error, uninstall first:
`$ADB uninstall com.leomancinidesign.taipingos`. Package id: `com.leomancinidesign.taipingos`.
**But do NOT reflexively uninstall — that deletes all user data.** See the next
section first.

## Updating WITHOUT wiping the phone's data (signing fingerprint check)

All user data — every user-created app, its saved data, and Notes — lives in the
app's private on-device storage (IndexedDB in the WebView). `adb install -r`
keeps that data **only if the new APK is signed with the same certificate as the
installed app.** If the certs differ, `install -r` fails with a signature
mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only way to install is
`adb uninstall` first — **which permanently deletes all the user's data.**

This bites when building on a **different computer**: with no stable keystore
configured, each machine signs with its own per-machine debug key
(`~/.android/debug.keystore`), so a build from machine B will NOT match an app
installed from machine A. (A stable shared key in `secrets.properties` —
`TAI_PING_KEYSTORE_*`, see `secrets.properties.example` — avoids this entirely.)

**Before flashing from a new machine, with the phone connected, check the
fingerprints and only then decide how to install:**

1. Fingerprint the app currently on the phone:

   ```sh
   APK_PATH=$($ADB shell pm path com.leomancinidesign.taipingos | sed 's/package://' | tr -d '\r' | head -1)
   $ADB pull "$APK_PATH" /tmp/installed.apk
   keytool -printcert -jarfile /tmp/installed.apk | grep -i SHA256
   # or, with build-tools on PATH: apksigner verify --print-certs /tmp/installed.apk | grep -i SHA-256
   ```

2. Fingerprint the new build you're about to install:

   ```sh
   ./gradlew assembleDebug
   APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
   apksigner verify --print-certs "$APK" | grep -i SHA-256
   # apksigner lives in $ANDROID_HOME/build-tools/<version>/apksigner
   ```

3. Compare the two SHA-256 values:
   - **They MATCH** → safe to update in place, data preserved:
     `$ADB install -r "$APK"`
   - **They DIFFER** → installing means uninstall = data loss. **Do not uninstall
     yet.** Pick one:
     - **(Preferred) Sign the new build with the matching key.** Copy the
       keystore that signed the installed app onto this machine (for a debug-key
       app that's machine A's `~/.android/debug.keystore`), set
       `TAI_PING_KEYSTORE_*` in `secrets.properties`, rebuild, re-check the
       fingerprints, then `install -r`. Going forward, use one shared keystore on
       every machine so this never recurs.
     - **Or back up, then uninstall + reinstall + restore** (step 4).

4. Back up the data before any uninstall:
   - In the running app: **Settings → Back up** writes `tai-ping-backup-*.json`
     to the phone's Downloads. Pull it off as a safety copy:

     ```sh
     $ADB shell ls /sdcard/Download/tai-ping-backup-*.json
     $ADB pull "/sdcard/Download/<that-file>.json" .
     ```

   - After reinstalling the new build, open **Settings → Restore** and pick that
     file to bring everything back.
   - Caveat: the in-app Back up / Restore UI only appears on the **new** wrapper
     build (it's hidden on the old one). If the phone still runs the old wrapper,
     you can't back up from the UI — get onto the matching signing key instead so
     you can update in place, or pull the WebView IndexedDB via `adb` if the build
     is debuggable.

## Rendering scale / device-pixel-ratio (subpixel rendering)

The WebView's `window.devicePixelRatio` is read **straight from the physical
display** by Chromium. It **cannot** be changed from app code — overriding the
Activity/context density (`createConfigurationContext`, `applyOverrideConfiguration`)
has NO effect on WebView DPR. Verified on-device: still reported `dpr=1.75`.

The only lever is the display's actual density (`wm density`, device-wide, persists
across reboots, no special app permission via adb shell):

```sh
adb shell wm density 160     # 1x  -> CSS viewport 1600x720, dpr=1 (pixel-perfect, native res)
adb shell wm density 320     # 2x  -> CSS viewport 800x360,  dpr=2
adb shell wm density reset   # native 280dpi = 1.75x (fractional -> subpixel rendering)
```

Test device panel (moto g play 2024): 1600x720 physical (landscape), native
280dpi = 1.75x. The fractional 1.75 is what causes subpixel rendering; an integer
DPR (1x/2x) fixes it.

**Standard setting: 2x (`wm density 320`).** Set every phone to 2x after flashing:

```sh
$ADB -s <SERIAL> shell wm density 320
```

Caveat: `wm density` is device-global (affects launcher + every app). Fine for a
dedicated kiosk. If the web app needs to stay crisp regardless of host DPR, that
fix belongs in `tai-ping-os` (snap canvas backing store to integer device pixels),
not this wrapper.

To measure what the page actually sees, temporarily add a `WebViewClient.onPageFinished`
that runs `console.log('dpr=' + devicePixelRatio + ' ' + innerWidth + 'x' + innerHeight)`
plus a `WebChromeClient.onConsoleMessage` that forwards to logcat, then
`adb logcat -s <TAG>`.
