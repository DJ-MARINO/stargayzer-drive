package com.djmarino.stargayzer.drive;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * StarGayzer Drive: the head unit's home screen. A full-screen WebView showing Drive (served by Home
 * Assistant at /local/drive/), with native app frames (AppFrame) laid over the places Drive's page marks
 * for Maps and the media app. ATOTO's own launcher stays installed and selectable.
 */
public final class MainActivity extends Activity {
    static final String TAG = "SGDrive";
    static final String DEFAULT_HA = "http://stargayzer.local:8123";
    static final String DRIVE_PATH = "/local/drive/index.html";
    static final String OFFLINE = "file:///android_asset/offline.html";
    static final String SELF_HOME = "com.djmarino.stargayzer.drive/.MainActivity";
    static final String ATOTO_HOME = "com.android.launcher3/.uioverrides.QuickstepLauncher";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AppFrame> frames = new LinkedHashMap<>();
    private FrameLayout root;
    private WebView web;
    private SharedPreferences prefs;
    volatile String pageUrl = "";
    private AppFrame lastFrame;
    private long lastFrameAt;
    private boolean wantHome;
    private boolean loginPolling;
    private final Runnable helperListener = this::helperChanged;

    String ha() { return prefs.getString("ha", DEFAULT_HA); }

    String driveUrl() { return ha() + DRIVE_PATH; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("drive", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0a0a0b);
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect over adb
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // HA serves /local/ with a 31-day cache: always ask the hub, so a deployed Drive or Orbital shows at once
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " StarGayzerDrive/" + BuildConfig.VERSION_NAME);
        web.setBackgroundColor(0xff0a0a0b);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(this), "SGA");
        web.setWebViewClient(new Client());
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        for (String name : new String[] {"a", "b"}) {
            AppFrame f = new AppFrame(this, name);
            f.setVisibility(View.GONE);
            root.addView(f, new FrameLayout.LayoutParams(1, 1));
            frames.put(name, f);
        }
        setContentView(root);
        immersive();

        HelperLink.listen(helperListener);
        Live.start(this);
        handleIntent(getIntent());
        web.loadUrl(driveUrl());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        // adb shell am start -n com.djmarino.stargayzer.drive/.MainActivity --es ha http://host:8123
        String ha = intent.getStringExtra("ha");
        if (ha != null && ha.matches("https?://[\\w.:-]+")) {
            prefs.edit().putString("ha", ha).apply();
            if (web != null) web.loadUrl(driveUrl());
        }
        // Opened from the StarGayzer icon on ATOTO's home screen: take the Home role back.
        if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            wantHome = true;
            claimHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Live.start(this);   // no-op once running; picks up a location grant made after the first start
    }

    @Override
    protected void onDestroy() {
        HelperLink.unlisten(helperListener);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    private void immersive() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onBackPressed() {
        // Back right after touching an app frame goes to that app (e.g. closes Maps' search).
        if (lastFrame != null && lastFrame.getVisibility() == View.VISIBLE
                && SystemClock.uptimeMillis() - lastFrameAt < 30000) {
            lastFrame.back();
            return;
        }
        js("window.sgaBack&&sgaBack()");
        // Never finish: this is the home screen.
    }

    void touchedFrame(AppFrame f) {
        lastFrame = f;
        lastFrameAt = SystemClock.uptimeMillis();
    }

    void backToFrame() {
        if (lastFrame != null) lastFrame.back();
    }

    // ---- helper ----

    private void helperChanged() {
        for (AppFrame f : frames.values()) f.helperChanged();
        claimHome();
        js("window.sgaHelper&&sgaHelper(" + helperState() + ")");
    }

    String helperState() {
        try {
            JSONObject o = new JSONObject();
            o.put("connected", HelperLink.get() != null);
            o.put("version", HelperLink.version());
            o.put("outdated", HelperLink.outdated());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void claimHome() {
        IDriveHelper hp = HelperLink.get();
        if (!wantHome || hp == null) return;
        wantHome = false;
        new Thread(() -> {
            try { hp.setHome(SELF_HOME); } catch (RemoteException e) { Log.w(TAG, "setHome", e); }
        }, "claim-home").start();
    }

    /** Exit StarGayzer: give the Home role back to ATOTO's launcher and open it. */
    void exitToAtoto() {
        IDriveHelper hp = HelperLink.get();
        new Thread(() -> {
            if (hp != null) {
                try { hp.setHome(ATOTO_HOME); } catch (RemoteException e) { Log.w(TAG, "setHome", e); }
            }
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClassName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) { Log.w(TAG, "ATOTO launcher", e); }
            });
        }, "exit").start();
    }

    // ---- frames (called by Bridge on the UI thread) ----

    private String lastFrames = "[]";

    /** A frame learned how much its display's bottom bar reserves: lay the frames out again. */
    void relayoutFrames() { setFrames(lastFrames); }

    /**
     * json: [{name:"a", pkg:"com.google.android.apps.maps", x, y, w, h}] in CSS px of the page.
     * A frame that reaches the bottom of the screen is made taller by the strip its display's bottom bar
     * reserves (ATOTO's taskbar), so that strip runs off the screen and the app fills the visible frame.
     */
    void setFrames(String json) {
        lastFrames = json;
        Set<String> shown = new HashSet<>();
        float density = getResources().getDisplayMetrics().density;
        int screenH = root.getHeight();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AppFrame f = frames.get(o.optString("name"));
                if (f == null) continue;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) f.getLayoutParams();
                lp.leftMargin = Math.round((float) o.getDouble("x") * density);
                lp.topMargin = Math.round((float) o.getDouble("y") * density);
                lp.width = Math.max(1, Math.round((float) o.getDouble("w") * density));
                lp.height = Math.max(1, Math.round((float) o.getDouble("h") * density));
                if (screenH > 0 && lp.topMargin + lp.height >= screenH - 2) lp.height += f.extra();
                f.setLayoutParams(lp);
                f.setPackage(o.optString("pkg", null));
                if (HelperLink.get() != null) f.setVisibility(View.VISIBLE);
                shown.add(f.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "frames " + json, e);
        }
        for (AppFrame f : frames.values()) if (!shown.contains(f.name)) f.setVisibility(View.GONE);
        holdFocusForFrames(!shown.isEmpty() && HelperLink.get() != null);
    }

    /**
     * Missed taps in a frame: every press lands on Drive's window first, and Android moved input focus to
     * Drive and then back to the app with each tap; some controls (Maps) spend the first tap after regaining
     * focus just taking it. While a frame shows, Drive's window does not take focus, so the app keeps it.
     */
    private void holdFocusForFrames(boolean framesShown) {
        int nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        boolean now = (getWindow().getAttributes().flags & nf) != 0;
        if (framesShown && !now) getWindow().addFlags(nf);
        else if (!framesShown && now) getWindow().clearFlags(nf);
    }

    // ---- page ----

    void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    void reload() {
        main.post(() -> web.loadUrl(driveUrl()));
    }

    /** From the offline page: try HA again in the background, load Drive when it answers. */
    void retry() {
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(driveUrl()).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                ok = c.getResponseCode() == 200;
                c.disconnect();
            } catch (Exception ignored) {
            }
            if (ok) reload();
            else js("window.sgaOffline&&sgaOffline()");
        }, "retry").start();
    }

    boolean isHaPage(String url) {
        return url != null && url.startsWith(ha() + "/");
    }

    /** First run: Drive needs HA's login (hassTokens) in this WebView. Send the page to HA's login. */
    private void checkLogin(String url) {
        if (url.startsWith(driveUrl())) {
            web.evaluateJavascript("!!localStorage.getItem('hassTokens')", v -> {
                if ("false".equals(v)) {
                    Log.i(TAG, "no HA login yet, opening HA's login page");
                    web.loadUrl(ha() + "/");
                }
            });
        } else if (isHaPage(url) && !loginPolling) {
            loginPolling = true;
            pollLogin();
        }
    }

    /** While on an HA page other than Drive, check every second for the login; no time limit (it can
     *  take a while, e.g. a forgotten password). Stops once Drive is loaded again. */
    private void pollLogin() {
        main.postDelayed(() -> {
            if (!isHaPage(pageUrl) || pageUrl.startsWith(driveUrl())) { loginPolling = false; return; }
            web.evaluateJavascript("!!localStorage.getItem('hassTokens')", v -> {
                if ("true".equals(v)) { loginPolling = false; web.loadUrl(driveUrl()); }
                else pollLogin();
            });
        }, 1000);
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            if (isHaPage(url) || url.equals(ha()) || url.startsWith("file:///android_asset/")) return false;
            // Anything else (a link out of HA): hand it to Android.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "no app for " + url);
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageUrl = url;
            setFrames("[]");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageUrl = url;
            checkLogin(url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame() && !req.getUrl().toString().startsWith("file:")) {
                Log.w(TAG, "load failed: " + err.getDescription());
                view.loadUrl(OFFLINE);
            }
        }
    }
}
