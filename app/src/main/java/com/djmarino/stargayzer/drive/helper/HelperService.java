package com.djmarino.stargayzer.drive.helper;

import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.djmarino.stargayzer.drive.IDriveHelper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The helper's binder. Only Drive's own uid (and adb: shell, root) may call it. Displays live as long as
 * the helper: hiding a frame only detaches its surface, so Maps keeps navigating while Drive shows
 * another screen. Displays are created without DESTROY_CONTENT_ON_REMOVAL: if the helper dies, the apps
 * move to the main screen instead of closing.
 */
final class HelperService extends IDriveHelper.Stub {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, VirtualDisplay> byName = new ConcurrentHashMap<>();
    private final Map<Integer, VirtualDisplay> byId = new ConcurrentHashMap<>();
    private volatile int appUid = -1;
    private volatile IBinder appToken;
    private boolean offering;

    /** Keeps offering our binder to Drive every 3 s until Drive says hello. */
    void offer() {
        if (offering) return;
        offering = true;
        main.post(new Runnable() {
            @Override public void run() {
                if (appToken != null) { offering = false; return; }
                appUid = Sys.packageUid(HelperMain.PKG);
                if (appUid >= 0) Sys.sendBinder(HelperService.this);
                main.postDelayed(this, 3000);
            }
        });
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != INTERFACE_TRANSACTION) {
            int uid = Binder.getCallingUid();
            if (uid != appUid && uid != Sys.SHELL_UID && uid != 0) {
                Sys.log("refused call from uid " + uid);
                return false;
            }
        }
        return super.onTransact(code, data, reply, flags);
    }

    private <T> T onMain(Callable<T> c) throws RemoteException {
        FutureTask<T> task = new FutureTask<>(c);
        main.post(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Sys.log("main thread task", e);
            throw new RemoteException(String.valueOf(e));
        }
    }

    @Override public int version() { return HelperMain.VERSION; }

    @Override
    public void hello(IBinder token) {
        // Not via onMain: Drive calls this from inside our own sendBinder(), which holds the main thread.
        appToken = token;
        Sys.log("Drive connected");
        try {
            token.linkToDeath(() -> main.post(() -> {
                Sys.log("Drive went away; keeping displays, offering again");
                if (appToken == token) appToken = null;
                for (VirtualDisplay vd : byId.values()) vd.setSurface(null);
                offer();
            }), 0);
        } catch (RemoteException e) {
            appToken = null;
            main.post(this::offer);
        }
    }

    @Override
    public int display(String name, Surface surface, int width, int height, int dpi) throws RemoteException {
        return onMain(() -> {
            VirtualDisplay vd = byName.get(name);
            if (vd != null) {
                vd.resize(width, height, dpi);
                vd.setSurface(surface);
                return vd.getDisplay().getDisplayId();
            }
            try {
                vd = Sys.createDisplay(name, width, height, dpi, surface);
            } catch (Exception e) {
                Sys.log("createDisplay " + name, e);
                return -1;
            }
            int id = vd.getDisplay().getDisplayId();
            byName.put(name, vd);
            byId.put(id, vd);
            lockRotation(id);
            return id;
        });
    }

    /**
     * Keep the display at its natural rotation. Otherwise an app asking for landscape (Google Maps) on a
     * frame that is slightly taller than wide turns the whole display, and Drive shows it sideways.
     */
    private static void lockRotation(int id) {
        String d = String.valueOf(id);
        new Thread(() -> {
            Sys.run("wm", "set-ignore-orientation-request", "-d", d, "true");
            Sys.run("wm", "fixed-to-user-rotation", "-d", d, "enabled");
            Sys.run("wm", "user-rotation", "-d", d, "lock", "0");
        }, "lock-rotation").start();
    }

    @Override
    public void detach(int displayId) throws RemoteException {
        onMain(() -> {
            VirtualDisplay vd = byId.get(displayId);
            if (vd != null) vd.setSurface(null);
            return null;
        });
    }

    @Override
    public boolean launch(int displayId, String component) {
        if (!byId.containsKey(displayId) || component == null || !component.matches("[\\w.]+/[\\w.$]+")) return false;
        // Safe to call again and again: when the app is already on this display nothing happens; when its
        // window ended up elsewhere (Drive restarted and the frames' displays were made anew, or the app
        // restarted itself, e.g. Spotify after an account switch) the window is moved back; otherwise the app
        // is started here.
        // Every window of the app counts: Android refuses some screens on a frame's display (Spotify's Now
        // Playing opens full screen on the main display instead), so each one found elsewhere is moved back.
        String pkg = component.substring(0, component.indexOf('/'));
        int root = -1, on = -1;
        boolean here = false;
        java.util.Set<Integer> moved = new java.util.HashSet<>();
        for (String line : Sys.output("am", "stack", "list").split("\n")) {
            java.util.regex.Matcher m = ROOT_TASK.matcher(line);
            if (m.find()) {
                root = Integer.parseInt(m.group(1));
                on = Integer.parseInt(m.group(2));
                continue;
            }
            if (!line.contains("taskId=") || !line.contains(": " + pkg + "/")) continue;
            if (on == displayId) { here = true; continue; }
            if (root >= 0 && moved.add(root)
                    && Sys.run("am", "display", "move-stack", String.valueOf(root), String.valueOf(displayId)) == 0) {
                Sys.log("moved " + pkg + " task " + root + " to display " + displayId);
                here = true;
            }
        }
        if (here) return true;
        // 0x10000000 NEW_TASK | 0x00200000 RESET_TASK_IF_NEEDED, like a launcher tap
        return Sys.run("am", "start", "--display", String.valueOf(displayId), "--windowingMode", "1",
                "-f", "0x10200000", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-n", component) == 0;
    }

    private static final java.util.regex.Pattern ROOT_TASK =
            java.util.regex.Pattern.compile("RootTask id=(\\d+) .*displayId=(\\d+)");

    @Override
    public void touch(int displayId, MotionEvent event) {
        if (byId.containsKey(displayId)) Sys.inject(event, displayId);
    }

    @Override
    public void key(int displayId, int keyCode) {
        if (!byId.containsKey(displayId)) return;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
            Sys.inject(e, displayId);
        }
    }

    private static final java.util.regex.Pattern BAR = java.util.regex.Pattern.compile(
            "InsetsSource type=ITYPE_(?:EXTRA_NAVIGATION_BAR|NAVIGATION_BAR|BOTTOM_TAPPABLE_ELEMENT) "
            + "frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\] visible=true");

    @Override
    public int bottomInset(int displayId) {
        if (!byId.containsKey(displayId)) return 0;
        int height = byId.get(displayId).getDisplay().getMode().getPhysicalHeight();
        boolean in = false;
        int inset = 0;
        for (String line : Sys.output("dumpsys", "window", "displays").split("\n")) {
            if (line.contains("Display: mDisplayId=")) {
                if (in) break;
                in = line.contains("Display: mDisplayId=" + displayId + " ");
                continue;
            }
            if (!in) continue;
            java.util.regex.Matcher m = BAR.matcher(line);
            if (m.find()) {
                int top = Integer.parseInt(m.group(2)), bottom = Integer.parseInt(m.group(4));
                if (bottom >= height - 2 && bottom > top) inset = Math.max(inset, bottom - top);
            }
        }
        return inset;
    }

    @Override
    public boolean setHome(String component) {
        if (component == null || !component.matches("[\\w.]+/[\\w.$]+")) return false;
        return Sys.run("cmd", "package", "set-home-activity", component) == 0;
    }

    @Override
    public void quit() {
        Sys.log("quit requested");
        main.post(() -> {
            // Removing a display closes the apps on it (Maps would lose its route and settings), so first
            // move them to the main screen; the next helper pulls them back into their frames.
            int root = -1, on = -1;
            for (String line : Sys.output("am", "stack", "list").split("\n")) {
                java.util.regex.Matcher m = ROOT_TASK.matcher(line);
                if (!m.find()) continue;
                root = Integer.parseInt(m.group(1));
                on = Integer.parseInt(m.group(2));
                if (byId.containsKey(on)) Sys.run("am", "display", "move-stack", String.valueOf(root), "0");
            }
            for (VirtualDisplay vd : byId.values()) vd.release();
            System.exit(0);
        });
    }
}
