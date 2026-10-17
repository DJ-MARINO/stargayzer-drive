package com.djmarino.stargayzer.drive;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** What the head unit knows that Home Assistant does not: GPS, what is playing, Maps' next turn, the network. */
final class Live {
    private static Location last;
    private static boolean started;
    private static String artKey = "", artUrl = "";

    /** GPS fixes once a second (the X10 has its own antenna). Needs ACCESS_FINE_LOCATION, granted over adb. */
    static void start(Context c) {
        if (started) return;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MainActivity.TAG, "no location permission: adb shell pm grant com.djmarino.stargayzer.drive android.permission.ACCESS_FINE_LOCATION");
            return;
        }
        LocationManager lm = c.getSystemService(LocationManager.class);
        LocationListener l = loc -> last = loc;
        try {
            for (String p : lm.getProviders(true)) {
                Location k = lm.getLastKnownLocation(p);
                if (k != null && (last == null || k.getTime() > last.getTime())) last = k;
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, l, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, l, Looper.getMainLooper());
            started = true;
        } catch (SecurityException e) {
            Log.w(MainActivity.TAG, "location", e);
        }
    }

    /** {lat, lon, spd (m/s), brg (deg), alt (m), acc (m), age (s), gps}; "{}" before the first fix. */
    static String location() {
        Location l = last;
        if (l == null) return "{}";
        try {
            JSONObject o = new JSONObject().put("lat", l.getLatitude()).put("lon", l.getLongitude())
                    .put("acc", l.hasAccuracy() ? l.getAccuracy() : -1)
                    .put("age", (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1e9)
                    .put("gps", LocationManager.GPS_PROVIDER.equals(l.getProvider()));
            if (l.hasSpeed()) o.put("spd", l.getSpeed());
            if (l.hasBearing()) o.put("brg", l.getBearing());
            if (l.hasAltitude()) o.put("alt", l.getAltitude());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The media session to show: the one playing, else the most recent one with a track. */
    static MediaController controller(Context c) {
        if (!NotifListener.connected) return null;
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, NotifListener.class));
            MediaController best = null;
            for (MediaController mc : list) {
                MediaMetadata md = mc.getMetadata();
                if (md == null || md.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (best == null) best = mc;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** {pkg, app, title, artist, album, playing, pos (s), dur (s), art (data URL)}; "{}" when nothing. */
    static String media(Context c) {
        MediaController mc = controller(c);
        if (mc == null) return NotifListener.connected ? "{}" : "{\"noAccess\":true}";
        try {
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            String pkg = mc.getPackageName();
            PackageManager pm = c.getPackageManager();
            String app = pkg;
            try { app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
            long pos = st == null ? 0 : st.getPosition();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0)
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String key = pkg + "|" + title + "|" + artist;
            if (!key.equals(artKey)) {
                artKey = key;
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                artUrl = b == null ? "" : png(b, 320);
            }
            return new JSONObject().put("pkg", pkg).put("app", app)
                    .put("title", title == null ? "" : title).put("artist", artist == null ? "" : artist)
                    .put("album", str(md.getString(MediaMetadata.METADATA_KEY_ALBUM)))
                    .put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING)
                    .put("pos", Math.max(0, pos) / 1000.0)
                    .put("dur", Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)) / 1000.0)
                    .put("art", artUrl).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** "play", "pause", "toggle", "next", "prev" on the session shown. */
    static void mediaCmd(Context c, String cmd) {
        MediaController mc = controller(c);
        if (mc == null) return;
        MediaController.TransportControls t = mc.getTransportControls();
        PlaybackState st = mc.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        switch (cmd) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "toggle": if (playing) t.pause(); else t.play(); break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            default: break;
        }
    }

    /** {online (validated internet), via ("wifi"|"cellular"|"other"|""), ssid}. */
    static String net(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            String via = caps == null ? "" : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "other";
            String ssid = "";
            if ("wifi".equals(via)) {
                WifiInfo wi = c.getSystemService(WifiManager.class).getConnectionInfo();
                if (wi != null && wi.getSSID() != null) ssid = wi.getSSID().replace("\"", "");
                if ("<unknown ssid>".equals(ssid)) ssid = "";
            }
            return new JSONObject().put("online", online).put("via", via).put("ssid", ssid).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(String s) { return s == null ? "" : s; }

    static String png(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight();
        float s = Math.min(1f, (float) max / Math.max(w, h));
        Bitmap o = s < 1f ? Bitmap.createScaledBitmap(b, Math.round(w * s), Math.round(h * s), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
