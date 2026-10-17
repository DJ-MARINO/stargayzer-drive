package com.djmarino.stargayzer.drive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * After a boot, turns Wireless debugging back on (Android switches it off at boot) so the Pi can reach
 * the head unit and start the helper. Needs WRITE_SECURE_SETTINGS, granted once over adb. Android only
 * enables it on a Wi-Fi network already marked "Always allow" (the StarGayzer network); only paired
 * computers can connect.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            Log.i("SGDrive", "no WRITE_SECURE_SETTINGS; leaving Wireless debugging alone");
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i("SGDrive", "Wireless debugging requested");
        } catch (SecurityException e) {
            Log.w("SGDrive", "could not enable Wireless debugging", e);
        }
    }
}
