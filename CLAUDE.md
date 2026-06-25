# tai-ping-os-android

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
