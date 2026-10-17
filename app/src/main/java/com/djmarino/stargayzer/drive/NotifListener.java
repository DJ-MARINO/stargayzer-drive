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
    static volatile boolean connected;
    private static volatile NotifListener instance;

    @Override
    public void onListenerConnected() {
        connected = true;
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        instance = null;
    }

    /**
     * Maps' navigation notification as it is right now: {title, text, sub, big, t}, or "{}" when Maps is not
     * navigating. Read live from the active notifications each time (never a remembered copy), so the card
     * clears the moment a route ends.
     */
    static String nav() {
        NotifListener l = instance;
        if (l == null) return "{}";
        try {
            for (StatusBarNotification sbn : l.getActiveNotifications()) {
                if (!MAPS.equals(sbn.getPackageName())) continue;
                Notification n = sbn.getNotification();
                // the navigation notification is ongoing; other Maps notifications (traffic, tips) are not
                if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) continue;
                Bundle e = n.extras;
                String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
                String text = str(e.getCharSequence(Notification.EXTRA_TEXT));
                if (title.isEmpty() && text.isEmpty()) continue;
                return new JSONObject().put("title", title).put("text", text)
                        .put("sub", str(e.getCharSequence(Notification.EXTRA_SUB_TEXT)))
                        .put("big", str(e.getCharSequence(Notification.EXTRA_BIG_TEXT)))
                        .put("t", sbn.getPostTime()).toString();
            }
        } catch (Exception ignored) {
        }
        return "{}";
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
}
