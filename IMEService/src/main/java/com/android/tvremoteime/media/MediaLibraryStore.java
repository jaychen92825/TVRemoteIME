package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

public class MediaLibraryStore {
    private static final String PREFS = "media_library";
    private static final String HISTORY = "history";
    private static final String FAVORITES = "favorites";
    private static final int MAX_HISTORY = 100;
    private static final int MAX_FAVORITES = 200;

    private final SharedPreferences preferences;

    public MediaLibraryStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized JSONArray getHistory() {
        return read(HISTORY);
    }

    public synchronized JSONArray getFavorites() {
        return read(FAVORITES);
    }

    public synchronized boolean isFavorite(String sourceKey, String id) {
        return find(read(FAVORITES), sourceKey, id) >= 0;
    }

    public synchronized boolean toggleFavorite(JSONObject item) {
        JSONArray items = read(FAVORITES);
        int index = find(items, item.optString("sourceKey"), item.optString("id"));
        if (index >= 0) {
            items = remove(items, index);
            write(FAVORITES, items);
            return false;
        }
        write(FAVORITES, prepend(items, item, MAX_FAVORITES));
        return true;
    }

    public synchronized void addHistory(JSONObject item) {
        JSONArray items = read(HISTORY);
        int existing = find(items, item.optString("sourceKey"), item.optString("id"));
        if (existing >= 0) items = remove(items, existing);
        write(HISTORY, prepend(items, item, MAX_HISTORY));
    }

    public synchronized void clearHistory() {
        preferences.edit().remove(HISTORY).apply();
    }

    private JSONArray read(String key) {
        try {
            return new JSONArray(preferences.getString(key, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void write(String key, JSONArray items) {
        preferences.edit().putString(key, items.toString()).apply();
    }

    private static int find(JSONArray items, String sourceKey, String id) {
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(id)) return -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && sourceKey.equals(item.optString("sourceKey")) && id.equals(item.optString("id"))) {
                return i;
            }
        }
        return -1;
    }

    private static JSONArray remove(JSONArray items, int index) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            if (i != index) result.put(items.opt(i));
        }
        return result;
    }

    private static JSONArray prepend(JSONArray items, JSONObject item, int limit) {
        JSONArray result = new JSONArray();
        result.put(item);
        for (int i = 0; i < items.length() && result.length() < limit; i++) result.put(items.opt(i));
        return result;
    }
}
