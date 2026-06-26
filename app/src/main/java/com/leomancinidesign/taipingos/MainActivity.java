package com.leomancinidesign.taipingos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // The API key is supplied at build time from secrets.properties via BuildConfig,
    // keeping it out of source control while still embedding it in the app.
    private static final String APP_URL =
            "https://tai-ping-os.leo.gd/?onDevice=true&key=" + BuildConfig.TAI_PING_API_KEY;

    // OS-level runtime permissions to request up front so the page's camera,
    // microphone and geolocation requests (granted via the WebChromeClient
    // callbacks below) actually receive data.
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    private WebView webView;

    // Pending callback for an in-page <input type="file"> (used by the OS's
    // backup "Restore" picker). Held while the system file chooser is open.
    private ValueCallback<Uri[]> fileChooserCallback;

    // Logcat tag for the lifecycle/config diagnostics below. Watch with:
    //   adb logcat -s TaiPingCfg
    private static final String CFG_TAG = "TaiPingCfg";

    // Last seen configuration, used to report exactly which fields change in
    // onConfigurationChanged (helps pin down what was recreating the Activity
    // and bouncing the web app back to its home screen).
    private Configuration lastConfig;

    private final ActivityResultLauncher<String[]> requestPermissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> loadApp());

    // Receives the document the user picked and hands it back to the WebView so
    // the <input type="file"> resolves (the page then reads it via FileReader).
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Uri[] uris = null;
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri data = result.getData().getData();
                            if (data != null) {
                                uris = new Uri[]{data};
                            }
                        }
                        if (fileChooserCallback != null) {
                            fileChooserCallback.onReceiveValue(uris);
                            fileChooserCallback = null;
                        }
                    });

    // Up-front runtime permissions. On pre-Q devices, writing a backup to the
    // public Downloads folder needs the legacy storage permission too; on Q+
    // MediaStore handles it without any permission.
    private String[] buildRuntimePermissions() {
        List<String> perms = new ArrayList<>(Arrays.asList(RUNTIME_PERMISSIONS));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A fresh onCreate while you were using the app means the Activity was
        // recreated (which reloads the WebView and resets the web app to home).
        // savedInstanceState != null indicates a recreation rather than a cold
        // start. Pair this with onConfigurationChanged below to see the cause.
        android.util.Log.i(CFG_TAG, "onCreate (recreated=" + (savedInstanceState != null) + ")");
        lastConfig = new Configuration(getResources().getConfiguration());

        enableImmersiveMode();

        webView = new ImmersiveWebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);

        // Lets the page save a backup file to the device's Downloads folder.
        // The page builds the JSON locally and passes it here; nothing is sent
        // over the network.
        webView.addJavascriptInterface(new FileBridge(), "AndroidFile");

        // When a hardware keyboard is attached the soft IME still binds to
        // focused web text fields and shows a slim "physical keyboard" bar.
        // Watch the IME inset and immediately hide it whenever it appears while
        // a hardware keyboard is present; hardware key events still reach the
        // WebView, so typing keeps working without the on-screen bar.
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            boolean hardKeyboard = getResources().getConfiguration().hardKeyboardHidden
                    == Configuration.HARDKEYBOARDHIDDEN_NO;
            if (imeVisible && hardKeyboard) {
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                        .hide(WindowInsetsCompat.Type.ime());
            }
            return insets;
        });

        // Keep navigation inside the WebView rather than spawning a browser.
        webView.setWebViewClient(new WebViewClient() {
            // Fires if the system kills the WebView's renderer process (e.g.
            // under memory pressure) rather than the Activity being recreated.
            // This is the OTHER way the app can reset to home; logging it tells
            // the two causes apart. Returning true keeps our app process alive.
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                android.util.Log.w(CFG_TAG, "WebView render process gone (didCrash="
                        + detail.didCrash() + ") — page was lost");
                return true;
            }
        });

        // Grant any in-page permission requests (camera/mic, geolocation)
        // automatically; the OS-level permissions are obtained up front below.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            // Drive the system document picker for in-page file inputs (the
            // backup "Restore" flow). The chosen file is returned to the
            // WebView, which feeds it to the <input> for the page to read.
            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // "*/*" so JSON backups are always selectable; the page's accept
                // attribute still hints the right type to the picker.
                intent.setType("*/*");
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Hardware back button navigates web history before exiting the app.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            // Request all runtime permissions first; loadApp() runs once the
            // user has responded (whatever the outcome).
            requestPermissions.launch(buildRuntimePermissions());
        }
    }

    // Fullscreen, immersive, draw into the display cutout. Re-applied on every
    // focus gain so the bars stay hidden after dialogs, the keyboard, or
    // returning from recents.
    private void enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveMode();
        }
    }

    // Fires for the configuration changes we now declare in the manifest
    // (keyboard, navigation, uiMode, etc.) instead of the Activity being
    // recreated. The diff() bitmask names exactly which field changed — e.g.
    // CONFIG_KEYBOARD=0x10 (hardware keyboard connect/disconnect),
    // CONFIG_NAVIGATION=0x20, CONFIG_UI_MODE=0x200. Watch with:
    //   adb logcat -s TaiPingCfg
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int changed = (lastConfig != null) ? lastConfig.diff(newConfig) : 0;
        android.util.Log.i(CFG_TAG, "onConfigurationChanged: changed=0x"
                + Integer.toHexString(changed)
                + " keyboard=" + newConfig.keyboard
                + " hardKeyboardHidden=" + newConfig.hardKeyboardHidden
                + " navigation=" + newConfig.navigation
                + " uiMode=0x" + Integer.toHexString(newConfig.uiMode)
                + " orientation=" + newConfig.orientation);
        lastConfig = new Configuration(newConfig);
        super.onConfigurationChanged(newConfig);
    }

    private void loadApp() {
        if (webView != null) {
            webView.loadUrl(APP_URL);
        }
    }

    // Stop the soft IME from appearing for focused web text fields. When a
    // hardware keyboard is attached, TYPE_NULL tells the IME no on-screen input
    // is needed, so it never binds — no bar and no per-keystroke flash — while
    // hardware key events still reach the WebView, so typing works. With no
    // hardware keyboard, NO_EXTRACT_UI / NO_FULLSCREEN keep the on-screen
    // keyboard inline instead of using the large landscape fullscreen editor.
    private static class ImmersiveWebView extends WebView {
        ImmersiveWebView(Context context) {
            super(context);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection ic = super.onCreateInputConnection(outAttrs);
            if (outAttrs != null) {
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        | EditorInfo.IME_FLAG_NO_FULLSCREEN;
                if (getResources().getConfiguration().hardKeyboardHidden
                        == Configuration.HARDKEYBOARDHIDDEN_NO) {
                    outAttrs.inputType = InputType.TYPE_NULL;
                }
            }
            return ic;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // JavaScript bridge exposed to the page as `window.AndroidFile`. Lets the
    // OS save a backup file to the device's Downloads folder. It only ever
    // writes text the page hands it — it cannot read anything back — so user
    // data still never leaves the device.
    private class FileBridge {
        @JavascriptInterface
        public void saveTextFile(String filename, String content) {
            runOnUiThread(() -> {
                boolean ok = writeToDownloads(filename, content);
                Toast.makeText(MainActivity.this,
                        ok ? "Saved " + filename + " to Downloads"
                           : "Couldn't save backup",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    // Write a UTF-8 text file into the public Downloads collection. Uses
    // MediaStore on Android Q+ (no permission needed) and the legacy public
    // directory on older versions (guarded by WRITE_EXTERNAL_STORAGE).
    private boolean writeToDownloads(String filename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(item)) {
                if (os == null) return false;
                os.write(bytes);
            } catch (Exception e) {
                return false;
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(item, values, null, null);
            return true;
        }
        try {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(dir, filename))) {
                fos.write(bytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
