package com.djmarino.stargayzer.drive.helper;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.InputEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The hidden-API plumbing the helper needs inside a bare app_process (no Application, no Context).
 * The ActivityThread / fake shell context approach follows scrcpy's server (Apache 2.0,
 * github.com/Genymobile/scrcpy: Workarounds.java, FakeContext.java, NewDisplayCapture.java).
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
final class Sys {
    static final String SHELL = "com.android.shell";
    static final int SHELL_UID = 2000;

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*; the hidden ones by value
    static final int FLAG_PUBLIC = 1;
    static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    static final int FLAG_TRUSTED = 1 << 10;
    static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;

    private static Object activityThread;
    private static Context systemContext;
    private static DisplayManager displayManager;
    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method setDisplayId;

    private Sys() {}

    static void log(String msg, Throwable t) {
        Log.e(HelperMain.TAG, msg, t);
        System.err.println("stargayzer-drive-helper: " + msg + (t != null ? ": " + t : ""));
    }

    static void log(String msg) {
        Log.i(HelperMain.TAG, msg);
        System.out.println("stargayzer-drive-helper: " + msg);
    }

    static void setProcessName(String name) {
        try {
            Method m = android.os.Process.class.getMethod("setArgV0", String.class);
            m.invoke(null, name);
        } catch (Throwable ignored) {
        }
    }

    static void init() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);
        Field system = at.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(activityThread, true);
        fillAppInfo(at);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        systemContext = (Context) getSystemContext.invoke(activityThread);
    }

    private static void fillAppInfo(Class<?> at) {
        try {
            Class<?> bindData = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> c = bindData.getDeclaredConstructor();
            c.setAccessible(true);
            Object data = c.newInstance();
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = SHELL;
            Field appInfo = bindData.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(data, ai);
            Field bound = at.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, data);
        } catch (Throwable t) {
            log("fillAppInfo", t);
        }
    }

    /** A context that says it is the shell package, so system services accept our calls as shell. */
    static final class ShellContext extends ContextWrapper {
        ShellContext() { super(systemContext); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID).setPackageName(SHELL).build();
        }
    }

    private static DisplayManager displayManager() throws Exception {
        if (displayManager == null) {
            Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            displayManager = c.newInstance(new ShellContext());
        }
        return displayManager;
    }

    /** A trusted, touchable display only its own apps go to. Falls back to fewer flags if refused. */
    static VirtualDisplay createDisplay(String name, int w, int h, int dpi, Surface surface) throws Exception {
        int base = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH | FLAG_ROTATES_WITH_CONTENT;
        int[] tries = {
            base | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED,
            base | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP,
            base | FLAG_TRUSTED,
            base,
        };
        Exception last = null;
        for (int flags : tries) {
            try {
                VirtualDisplay vd = displayManager().createVirtualDisplay(name, w, h, dpi, surface, flags);
                if (vd != null) {
                    log("display " + name + " id " + vd.getDisplay().getDisplayId() + " flags 0x" + Integer.toHexString(flags));
                    return vd;
                }
            } catch (SecurityException e) {
                last = e;
                log("display flags 0x" + Integer.toHexString(flags) + " refused", e);
            }
        }
        throw last != null ? last : new IllegalStateException("createVirtualDisplay returned null");
    }

    static void inject(InputEvent event, int displayId) {
        try {
            if (inputManager == null) {
                Object im;
                try {
                    im = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
                } catch (NoSuchMethodException e) { // Android 14+
                    Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
                    im = g.getDeclaredMethod("getInstance").invoke(null);
                }
                injectInputEvent = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
                inputManager = im;
            }
            setDisplayId.invoke(event, displayId);
            injectInputEvent.invoke(inputManager, event, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable t) {
            log("inject", t);
        }
    }

    /** Runs a shell command (we are shell) and returns its exit code; output goes to the log. */
    static int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int code = p.waitFor();
            if (code != 0) log(String.join(" ", cmd) + " -> " + code + ": " + out.toString().trim());
            return code;
        } catch (Exception e) {
            log("run " + cmd[0], e);
            return -1;
        }
    }

    static String output(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** uid of an installed package, from "package:NAME uid:10123", or -1. */
    static int packageUid(String pkg) {
        for (String line : output("cmd", "package", "list", "packages", "-U", pkg).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:" + pkg + " ")) {
                int i = line.indexOf("uid:");
                if (i >= 0) {
                    try { return Integer.parseInt(line.substring(i + 4).split("[ ,]")[0]); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    /**
     * Hands our binder to Drive: call() on Drive's content provider, as Shizuku does. Starts Drive's
     * process if it is not running. Returns true if Drive answered.
     */
    static boolean sendBinder(IBinder binder) {
        Object am = null;
        try {
            am = ActivityManager.class.getMethod("getService").invoke(null);
            Method getProvider = am.getClass().getMethod("getContentProviderExternal",
                    String.class, int.class, IBinder.class, String.class);
            Object holder = getProvider.invoke(am, HelperMain.AUTHORITY, 0, null, HelperMain.AUTHORITY);
            if (holder == null) {
                log("Drive's provider not found (is the app installed?)");
                return false;
            }
            Field providerField = holder.getClass().getField("provider");
            Object provider = providerField.get(holder);
            Bundle extras = new Bundle();
            extras.putBinder("binder", binder);
            extras.putInt("version", HelperMain.VERSION);
            Bundle reply;
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource src = new AttributionSource.Builder(SHELL_UID).setPackageName(SHELL).build();
                Method call = provider.getClass().getMethod("call", AttributionSource.class,
                        String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, src, HelperMain.AUTHORITY, "helper", null, extras);
            } else {
                Method call = provider.getClass().getMethod("call", String.class, String.class,
                        String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, SHELL, null, HelperMain.AUTHORITY, "helper", null, extras);
            }
            return reply != null && reply.getBoolean("ok");
        } catch (Throwable t) {
            log("sendBinder", t);
            return false;
        } finally {
            if (am != null) {
                try {
                    am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(am, HelperMain.AUTHORITY, null, 0);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
