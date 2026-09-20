package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a small adaptive reliability score for media sources.  This is intentionally
 * local-only and lightweight: it is used to order searches and playback fallback,
 * without changing the user's TVBox configuration.
 */
public class MediaSourceQualityStore {
    private static final String PREFS = "media_source_quality";
    private static final String DATA = "scores";
    private static final int MAX_COUNTER = 30;

    private final SharedPreferences preferences;

    public MediaSourceQualityStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void recordSuccess(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) return;
        JSONObject data = read();
        JSONObject item = data.optJSONObject(sourceKey);
        if (item == null) item = new JSONObject();
        try {
            item.put("success", Math.min(MAX_COUNTER, item.optInt("success", 0) + 1));
            item.put("failure", Math.max(0, item.optInt("failure", 0) - 1));
            item.put("lastSuccess", System.currentTimeMillis());
            data.put(sourceKey, item);
            write(data);
        } catch (Exception ignored) {
        }
    }

    public synchronized void recordFailure(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) return;
        JSONObject data = read();
        JSONObject item = data.optJSONObject(sourceKey);
        if (item == null) item = new JSONObject();
        try {
            item.put("success", Math.max(0, item.optInt("success", 0) - 1));
            item.put("failure", Math.min(MAX_COUNTER, item.optInt("failure", 0) + 1));
            item.put("lastFailure", System.currentTimeMillis());
            data.put(sourceKey, item);
            write(data);
        } catch (Exception ignored) {
        }
    }

    public synchronized int score(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) return 0;
        JSONObject item = read().optJSONObject(sourceKey);
        if (item == null) return 0;
        return item.optInt("success", 0) * 4 - item.optInt("failure", 0) * 6;
    }

    public List<MediaSource> rank(List<MediaSource> input) {
        final JSONObject snapshot;
        synchronized (this) {
            snapshot = read();
        }
        List<MediaSource> result = new ArrayList<MediaSource>(input);
        Collections.sort(result, new Comparator<MediaSource>() {
            @Override
            public int compare(MediaSource left, MediaSource right) {
                int leftScore = score(snapshot, left == null ? "" : left.key);
                int rightScore = score(snapshot, right == null ? "" : right.key);
                if (leftScore != rightScore) return rightScore - leftScore;
                if (left != null && right != null && left.quickSearch != right.quickSearch) return left.quickSearch ? -1 : 1;
                return 0;
            }
        });
        return result;
    }

    private static int score(JSONObject data, String sourceKey) {
        JSONObject item = data.optJSONObject(sourceKey);
        if (item == null) return 0;
        return item.optInt("success", 0) * 4 - item.optInt("failure", 0) * 6;
    }

    private JSONObject read() {
        try {
            return new JSONObject(preferences.getString(DATA, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void write(JSONObject data) {
        preferences.edit().putString(DATA, data.toString()).apply();
    }
}
