package com.djmarino.stargayzer.drive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * One app frame of the Drive page (Map, Media). Shows a virtual display owned by the helper; the app on
 * it (Google Maps, Spotify) runs there for real. Touches inside the frame go to that display 1:1 (the
 * display has the frame's size in pixels).
 */
final class AppFrame extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "SGDrive";
    final String name;
    private final MainActivity activity;
    private String pkg;
    private String launched;
    private int displayId = -1;
    private int width, height;

    AppFrame(MainActivity activity, String name) {
        super(activity);
        this.activity = activity;
        this.name = name;
        getHolder().addCallback(this);
        setFocusable(false);
    }

    int displayId() { return displayId; }

    void setPackage(String p) {
        if (p == null || p.equals(pkg)) return;
        pkg = p;
        if (displayId >= 0 && !p.equals(launched)) launch();
    }

    /** The helper (re)connected: draw again and make sure our app is on the display. */
    void helperChanged() {
        launched = null;
        displayId = -1;
        attach();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        width = w;
        height = h;
        attach();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        IDriveHelper hp = HelperLink.get();
        if (hp != null && displayId >= 0) {
            try { hp.detach(displayId); } catch (RemoteException ignored) {}
        }
    }

    private void attach() {
        IDriveHelper hp = HelperLink.get();
        if (hp == null || width <= 0 || height <= 0 || !getHolder().getSurface().isValid()) return;
        try {
            int dpi = getResources().getDisplayMetrics().densityDpi;
            displayId = hp.display("stargayzer-" + name, getHolder().getSurface(), width, height, dpi);
        } catch (RemoteException e) {
            Log.w(TAG, "display " + name, e);
            displayId = -1;
        }
        if (displayId >= 0 && pkg != null && !pkg.equals(launched)) launch();
    }

    private void launch() {
        final String p = pkg;
        final int id = displayId;
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(p);
        ComponentName c = i != null ? i.getComponent() : null;
        IDriveHelper hp = HelperLink.get();
        if (c == null || hp == null) {
            Log.w(TAG, "cannot launch " + p + " in frame " + name);
            return;
        }
        launched = p;
        final String comp = c.flattenToShortString();
        new Thread(() -> {
            try {
                if (!hp.launch(id, comp)) Log.w(TAG, "launch " + comp + " failed");
            } catch (RemoteException e) {
                Log.w(TAG, "launch " + comp, e);
            }
        }, "frame-launch").start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        IDriveHelper hp = HelperLink.get();
        if (hp == null || displayId < 0) return true;
        activity.touchedFrame(this);
        try {
            hp.touch(displayId, ev);
        } catch (RemoteException ignored) {
        }
        return true;
    }

    void back() {
        IDriveHelper hp = HelperLink.get();
        if (hp == null || displayId < 0) return;
        try { hp.key(displayId, android.view.KeyEvent.KEYCODE_BACK); } catch (RemoteException ignored) {}
    }
}
