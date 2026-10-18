package com.djmarino.stargayzer.drive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps Wireless debugging on, so the Pi can reach the head unit and start the helper (the app frames need it).
 * Android only allows it on a Wi-Fi network marked "Always allow" (StarGayzer) and switches it off whenever
 * Wi-Fi drops. After a cold start (Oct 18 2026) the X10 booted before Starlink had its Wi-Fi up, so the request
 * made at boot was switched straight back off and Spotify never appeared in its frame. Asking again each time
 * Wi-Fi connects, and once a minute while it is off, covers late Wi-Fi and the router's restarts.
 */
final class WifiDebug {
    private static final String KEY = "adb_wifi_enabled";
    private static Handler main;

    private WifiDebug() {}

    static void start(Context c) {
        final Context app = c.getApplicationContext();
        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        main = new Handler(Looper.getMainLooper());
        try {
            NetworkRequest wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            app.getSystemService(ConnectivityManager.class).registerNetworkCallback(wifi, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    // give Android a moment to settle on the network before asking
                    main.postDelayed(() -> ensure(app), 3000);
                }
            });
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "wifi watch", e);
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                ensure(app);
                main.postDelayed(this, 60000);
            }
        }, 60000);
    }

    static void ensure(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), KEY, 0) == 1) return;
            Settings.Global.putInt(c.getContentResolver(), KEY, 1);
            Log.i(MainActivity.TAG, "Wireless debugging requested");
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "Wireless debugging", e);
        }
    }
}
