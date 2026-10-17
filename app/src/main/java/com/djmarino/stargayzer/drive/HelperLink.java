package com.djmarino.stargayzer.drive;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.djmarino.stargayzer.drive.helper.HelperMain;

import java.util.concurrent.CopyOnWriteArrayList;

/** Drive's side of the helper connection: the binder the helper handed us, and who wants to know. */
final class HelperLink {
    private static final String TAG = "SGDrive";
    private static final Binder TOKEN = new Binder();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile IDriveHelper helper;
    private static volatile int remoteVersion;

    private HelperLink() {}

    static IDriveHelper get() { return helper; }

    static int version() { return remoteVersion; }

    static boolean outdated() { return helper != null && remoteVersion != HelperMain.VERSION; }

    static void listen(Runnable r) { LISTENERS.add(r); }

    static void unlisten(Runnable r) { LISTENERS.remove(r); }

    /** Called from HelperProvider (a binder thread) when the helper offers itself. */
    static boolean accept(IBinder binder) {
        if (binder == null) return false;
        IDriveHelper h = IDriveHelper.Stub.asInterface(binder);
        try {
            remoteVersion = h.version();
            h.hello(TOKEN);
            binder.linkToDeath(() -> {
                if (helper == h) {
                    helper = null;
                    Log.i(TAG, "helper died");
                    changed();
                }
            }, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "helper offer failed", e);
            return false;
        }
        helper = h;
        Log.i(TAG, "helper connected, version " + remoteVersion);
        changed();
        return true;
    }

    private static void changed() {
        MAIN.post(() -> { for (Runnable r : LISTENERS) r.run(); });
    }
}
