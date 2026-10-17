package com.djmarino.stargayzer.drive;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

/**
 * Notification access, granted once over adb (cmd notification allow_listener). It gives Drive two things:
 * the right to read media sessions (what is playing, and the controls), and Google Maps' turn-by-turn
 * notification, which carries the next turn, its distance and the arrival time.
 */
public final class NotifListener extends NotificationListenerService {
    static final String MAPS = "com.google.android.apps.maps";
    static volatile String nav = "{}";
    static volatile boolean connected;

    @Override
    public void onListenerConnected() {
        connected = true;
        try {
            for (StatusBarNotification n : getActiveNotifications()) onNotificationPosted(n);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        // Maps' navigation notification is ongoing; other Maps notifications (traffic, tips) are not
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        Bundle e = n.extras;
        try {
            nav = new JSONObject()
                    .put("title", str(e.getCharSequence(Notification.EXTRA_TITLE)))
                    .put("text", str(e.getCharSequence(Notification.EXTRA_TEXT)))
                    .put("sub", str(e.getCharSequence(Notification.EXTRA_SUB_TEXT)))
                    .put("big", str(e.getCharSequence(Notification.EXTRA_BIG_TEXT)))
                    .put("t", System.currentTimeMillis())
                    .toString();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS.equals(sbn.getPackageName()) && (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0) nav = "{}";
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
}
