package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

public class SpiderDebug {
    private static final String TAG = SpiderDebug.class.getSimpleName();

    public static void log(Throwable throwable) {
        if (throwable != null) Log.e(TAG, throwable.getMessage(), throwable);
    }

    public static void log(String message) {
        if (!TextUtils.isEmpty(message)) Log.d(TAG, message);
    }

    public static void log(String tag, String message, Object... args) {
        if (TextUtils.isEmpty(message)) return;
        String output = message;
        if (args != null && args.length > 0) {
            try {
                output = String.format(message, args);
            } catch (Exception ignored) {
            }
        }
        Log.d(safeTag(tag), output);
    }

    private static String safeTag(String tag) {
        if (TextUtils.isEmpty(tag)) return TAG;
        return tag.length() > 23 ? tag.substring(0, 23) : tag;
    }
}
