package com.djmarino.stargayzer.drive;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * window.SGA, what Drive's page may ask of Android. Only answers pages from Home Assistant (or the
 * built-in offline page): anything else the WebView ends up on gets nothing.
 */
final class Bridge {
    private final MainActivity a;
    private final Map<String, String> icons = new HashMap<>();

    Bridge(MainActivity activity) { a = activity; }

    private boolean ok() {
        String u = a.pageUrl;
        boolean allowed = a.isHaPage(u) || u.startsWith("file:///android_asset/");
        if (!allowed) Log.w(MainActivity.TAG, "bridge call refused from " + u);
        return allowed;
    }

    @JavascriptInterface
    public String info() {
        if (!ok()) return "{}";
        try {
            JSONObject o = new JSONObject(a.helperState());
            o.put("app", BuildConfig.VERSION_NAME);
            o.put("ha", a.ha());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Launchable apps: [{pkg, label}], sorted by label. */
    @JavascriptInterface
    public String apps() {
        if (!ok()) return "[]";
        PackageManager pm = a.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        list.sort((x, y) -> String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm))));
        JSONArray arr = new JSONArray();
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(a.getPackageName())) continue;
            try {
                arr.put(new JSONObject().put("pkg", pkg).put("label", String.valueOf(r.loadLabel(pm))));
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    @JavascriptInterface
    public boolean installed(String pkg) {
        return ok() && pkg != null && a.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** The app's icon as a 96 px PNG data URL, or "". */
    @JavascriptInterface
    public String icon(String pkg) {
        if (!ok() || pkg == null) return "";
        synchronized (icons) {
            String c = icons.get(pkg);
            if (c != null) return c;
        }
        String url = "";
        try {
            Drawable d = a.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            url = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
        synchronized (icons) { icons.put(pkg, url); }
        return url;
    }

    /** Opens an app full screen on the head unit (not in a frame). */
    @JavascriptInterface
    public boolean open(String pkg) {
        if (!ok() || pkg == null) return false;
        Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.runOnUiThread(() -> {
            try { a.startActivity(i); } catch (ActivityNotFoundException e) { Log.w(MainActivity.TAG, "open " + pkg, e); }
        });
        return true;
    }

    /** Without the helper: Android's split screen, the app beside Drive. */
    @JavascriptInterface
    public boolean openBeside(String pkg) {
        if (!ok() || pkg == null) return false;
        Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        a.runOnUiThread(() -> {
            try { a.startActivity(i); } catch (ActivityNotFoundException e) { Log.w(MainActivity.TAG, "openBeside " + pkg, e); }
        });
        return true;
    }

    /** Where Drive's app frames are: [{name:"a"|"b", pkg, x, y, w, h}] in CSS px. "[]" hides them. */
    @JavascriptInterface
    public void frames(String json) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.setFrames(json));
    }

    /** Back key for the app in the frame last touched. */
    @JavascriptInterface
    public void frameBack() {
        if (!ok()) return;
        a.runOnUiThread(a::backToFrame);
    }

    /** Media volume 0..1. */
    @JavascriptInterface
    public double volume() {
        if (!ok()) return 0;
        AudioManager am = a.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? (double) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0;
    }

    @JavascriptInterface
    public void setVolume(double v) {
        if (!ok()) return;
        AudioManager am = a.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(Math.max(0, Math.min(1, v)) * max), 0);
    }

    /**
     * Settings: "wifi", "bluetooth" (ATOTO's Bluetooth app with the paired phones: the phones use ATOTO's own
     * Bluetooth chip, which Android's Bluetooth settings never see), "android" (stock Android Settings, not
     * ATOTO's skin), or ATOTO's settings for anything else.
     */
    @JavascriptInterface
    public void settings(String which) {
        if (!ok()) return;
        Intent i;
        if ("wifi".equals(which)) i = new Intent(Settings.ACTION_WIFI_SETTINGS);
        else if ("bluetooth".equals(which)) {
            i = a.getPackageManager().getLaunchIntentForPackage("com.zhonghong.bluetooth");
            if (i == null) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        } else if ("android".equals(which))
            i = new Intent().setClassName("com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity");
        else i = new Intent(Settings.ACTION_SETTINGS);
        final Intent go = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.runOnUiThread(() -> {
            try { a.startActivity(go); }
            catch (Exception e) {
                Log.w(MainActivity.TAG, "settings " + which, e);
                try { a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        });
    }

    /** GPS: {lat, lon, spd m/s, brg, alt m, acc m, age s, gps}. */
    @JavascriptInterface
    public String location() { return ok() ? Live.location() : "{}"; }

    /** What is playing: {pkg, app, title, artist, album, playing, pos, dur, art}; {noAccess:true} without notification access. */
    @JavascriptInterface
    public String media() { return ok() ? Live.media(a) : "{}"; }

    /** "play", "pause", "toggle", "next", "prev". */
    @JavascriptInterface
    public void mediaCmd(String cmd) { if (ok() && cmd != null) Live.mediaCmd(a, cmd); }

    /** Google Maps' navigation notification: {title, text, sub, big, t}; "{}" when not navigating. */
    @JavascriptInterface
    public String nav() { return ok() ? NotifListener.nav : "{}"; }

    /** {online, via, ssid}. */
    @JavascriptInterface
    public String net() { return ok() ? Live.net(a) : "{}"; }

    /** Exit StarGayzer: ATOTO's launcher becomes Home again and opens. */
    @JavascriptInterface
    public void exit() {
        if (!ok()) return;
        a.exitToAtoto();
    }

    @JavascriptInterface
    public void reload() {
        if (!ok()) return;
        a.reload();
    }

    @JavascriptInterface
    public void retry() {
        if (!ok()) return;
        a.retry();
    }
}
