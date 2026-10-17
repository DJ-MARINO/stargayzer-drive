package com.djmarino.stargayzer.drive;

import android.view.MotionEvent;
import android.view.Surface;

// The helper runs as the shell user (started by the Pi over adb). It owns the virtual displays that
// Drive's app frames show, starts apps on them and passes touches through.
interface IDriveHelper {
    int version();

    // Drive registers itself; the helper drops all surfaces if this binder dies.
    void hello(IBinder appToken);

    // Creates the display called name, or reuses it (apps on it keep running), and draws it on surface.
    // Returns the display id, or -1.
    int display(String name, in Surface surface, int width, int height, int dpi);

    // The frame is hidden: keep the display and its apps, stop drawing.
    void detach(int displayId);

    // Starts the activity (flattened component name) on the display.
    boolean launch(int displayId, String component);

    oneway void touch(int displayId, in MotionEvent event);

    oneway void key(int displayId, int keyCode);

    // Pixels at the bottom of the display that a system bar reserves (ATOTO's Launcher3 adds its
    // taskbar to every display). Drive makes the display that much taller and lets it run off the screen.
    int bottomInset(int displayId);

    // cmd package set-home-activity
    boolean setHome(String component);

    void quit();
}
