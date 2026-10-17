package com.djmarino.stargayzer.drive.helper;

import android.net.LocalServerSocket;
import android.os.Looper;

import java.io.IOException;

/**
 * Entry point of the helper. Not part of the app's normal life: the Pi (or the laptop) starts it over adb
 * as the shell user, from the installed APK, the way scrcpy starts its server:
 *
 *   CLASSPATH=$(pm path com.djmarino.stargayzer.drive | cut -d: -f2) \
 *     app_process /system/bin com.djmarino.stargayzer.drive.helper.HelperMain
 *
 * (pi/x10-helper.sh wraps this with setsid/nohup so it keeps running after adb disconnects.)
 * As shell it may create trusted virtual displays, start activities on them and inject input, which
 * an ordinary app may not. It hands its binder to Drive through Drive's content provider.
 */
public final class HelperMain {
    public static final String PKG = "com.djmarino.stargayzer.drive";
    public static final String AUTHORITY = PKG + ".helper";
    public static final int VERSION = 1;
    static final String TAG = "SGDriveHelper";

    private HelperMain() {}

    public static void main(String[] args) {
        // One helper at a time: the abstract socket name is the lock (released when the process dies).
        LocalServerSocket lock;
        try {
            lock = new LocalServerSocket("stargayzer-drive-helper");
        } catch (IOException e) {
            System.out.println("stargayzer-drive-helper: already running");
            return;
        }
        Sys.setProcessName("stargayzer-helper");
        Looper.prepareMainLooper();
        try {
            Sys.init();
        } catch (Throwable t) {
            Sys.log("init failed", t);
            System.exit(1);
        }
        HelperService service = new HelperService();
        service.offer();
        System.out.println("stargayzer-drive-helper: running, version " + VERSION);
        Looper.loop();
        try { lock.close(); } catch (IOException ignored) {}
    }
}
