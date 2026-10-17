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

    /** Extra pixels below the visible frame: the strip a system bar reserves on the display. */
    private volatile int extra;
    private int checks;

    int extra() { return extra; }

    /** Ask the helper how much the bottom bar reserves; soon after attaching, then every 30 s. */
    private final Runnable checkInset = new Runnable() {
        @Override public void run() {
            IDriveHelper hp = HelperLink.get();
            final int id = displayId;
            if (hp == null || id < 0 || getVisibility() != VISIBLE) return;
            new Thread(() -> {
                int v;
                try { v = hp.bottomInset(id); } catch (RemoteException e) { return; }
                final int nv = v;
                post(() -> {
                    if (nv != extra) {
                        extra = nv;
                        activity.relayoutFrames();
                    }
                    int delay = checks++ < 4 ? 1500 : 30000;
                    removeCallbacks(checkInset);
                    postDelayed(checkInset, delay);
                });
            }, "frame-inset").start();
        }
    };

    /** Called each time Drive shows this frame: also makes sure the app is still on our display. */
    void setPackage(String p) {
        if (p == null) return;
        pkg = p;
        if (displayId >= 0) launch();
        removeCallbacks(recheck);
        postDelayed(recheck, 5000);
    }

    // while the frame is on screen, check every 5 s that its app is still there (apps can restart themselves
    // onto the main screen, e.g. Spotify after switching accounts); the helper only acts when it is not
    private final Runnable quickCheck = () -> { if (getVisibility() == VISIBLE && pkg != null && displayId >= 0) launch(); };

    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            if (getVisibility() != VISIBLE || pkg == null) return;
            if (displayId >= 0) launch();
            postDelayed(this, 5000);
        }
    };

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
        if (displayId >= 0) {
            checks = 0;
            removeCallbacks(checkInset);
            postDelayed(checkInset, 800);
        }
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
        // a tap can open a screen Android will not put on our display (it lands full screen on the main one):
        // check right away a few times so it comes back within a fraction of a second, not 5 s
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            for (int ms : new int[] {150, 450, 900, 1600, 3000}) postDelayed(quickCheck, ms);
        }
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
