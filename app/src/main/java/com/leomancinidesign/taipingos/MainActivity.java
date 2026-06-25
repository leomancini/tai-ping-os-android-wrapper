package com.leomancinidesign.taipingos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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

    private final ActivityResultLauncher<String[]> requestPermissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> loadApp());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        enableImmersiveMode();

        webView = new ImmersiveWebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);

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
        webView.setWebViewClient(new WebViewClient());

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
            requestPermissions.launch(RUNTIME_PERMISSIONS);
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
}
