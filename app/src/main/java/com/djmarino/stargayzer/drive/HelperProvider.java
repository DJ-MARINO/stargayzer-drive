package com.djmarino.stargayzer.drive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The helper calls call("helper") here with its binder. Only shell (adb) or root may. */
public final class HelperProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (!"helper".equals(method) || extras == null || (uid != 2000 && uid != 0)) return null;
        Bundle reply = new Bundle();
        reply.putBoolean("ok", HelperLink.accept(extras.getBinder("binder")));
        return reply;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
