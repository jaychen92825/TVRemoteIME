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
        return readAndMigrate(HISTORY);
    }

    public synchronized JSONArray getFavorites() {
        return readAndMigrate(FAVORITES);
    }

    public synchronized JSONObject getHistoryItem(String sourceKey, String id) {
        return getHistoryItem(sourceKey, id, "", "", "");
    }

    public synchronized JSONObject getHistoryItem(String sourceKey, String id, String canonicalId, String name, String year) {
        JSONArray items = readAndMigrate(HISTORY);
        int index = find(items, sourceKey, id, canonicalId, name, year);
        return index >= 0 ? items.optJSONObject(index) : null;
    }

    public synchronized boolean isFavorite(String sourceKey, String id) {
        return isFavorite(sourceKey, id, "", "", "");
    }

    public synchronized boolean isFavorite(String sourceKey, String id, String canonicalId, String name, String year) {
        return find(readAndMigrate(FAVORITES), sourceKey, id, canonicalId, name, year) >= 0;
    }

    public synchronized boolean toggleFavorite(JSONObject item) {
        ensureCanonicalId(item);
        JSONArray items = readAndMigrate(FAVORITES);
        int index = find(items, item.optString("sourceKey"), item.optString("id"), item.optString("canonicalId"),
                item.optString("name"), item.optString("year"));
        if (index >= 0) {
            items = remove(items, index);
            write(FAVORITES, items);
            return false;
        }
        write(FAVORITES, prepend(items, item, MAX_FAVORITES));
        return true;
    }

    public synchronized void addHistory(JSONObject item) {
        ensureCanonicalId(item);
        JSONArray items = readAndMigrate(HISTORY);
        int existing = find(items, item.optString("sourceKey"), item.optString("id"), item.optString("canonicalId"),
                item.optString("name"), item.optString("year"));
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

    private JSONArray readAndMigrate(String key) {
        JSONArray items = read(key);
        boolean changed = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !TextUtils.isEmpty(item.optString("canonicalId"))) continue;
            String canonicalId = canonicalMediaId(item.optString("name"), item.optString("year"));
            if (TextUtils.isEmpty(canonicalId)) continue;
            try {
                item.put("canonicalId", canonicalId);
                changed = true;
            } catch (Exception ignored) {
            }
        }
        if (changed) write(key, items);
        return items;
    }

    private void write(String key, JSONArray items) {
        preferences.edit().putString(key, items.toString()).apply();
    }

    private static int find(JSONArray items, String sourceKey, String id, String canonicalId, String name, String year) {
        if (!TextUtils.isEmpty(sourceKey) && !TextUtils.isEmpty(id)) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && sourceKey.equals(item.optString("sourceKey")) && id.equals(item.optString("id"))) {
                    return i;
                }
            }
        }
        String identity = TextUtils.isEmpty(canonicalId) ? canonicalMediaId(name, year) : canonicalId;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String existingIdentity = item.optString("canonicalId");
            if (!TextUtils.isEmpty(identity) && TextUtils.equals(identity, existingIdentity)) return i;
            if (sameLegacyIdentity(item, name, year)) return i;
        }
        return -1;
    }

    private static boolean sameLegacyIdentity(JSONObject item, String name, String year) {
        String left = normalizeTitle(item.optString("name"));
        String right = normalizeTitle(name);
        if (TextUtils.isEmpty(left) || !TextUtils.equals(left, right)) return false;
        String leftYear = normalizeYear(item.optString("year"));
        String rightYear = normalizeYear(year);
        return TextUtils.isEmpty(leftYear) || TextUtils.isEmpty(rightYear) || TextUtils.equals(leftYear, rightYear);
    }

    private static void ensureCanonicalId(JSONObject item) {
        if (item == null || !TextUtils.isEmpty(item.optString("canonicalId"))) return;
        String canonicalId = canonicalMediaId(item.optString("name"), item.optString("year"));
        if (TextUtils.isEmpty(canonicalId)) return;
        try {
            item.put("canonicalId", canonicalId);
        } catch (Exception ignored) {
        }
    }

    public static String canonicalMediaId(String name, String year) {
        String title = normalizeTitle(name);
        if (TextUtils.isEmpty(title)) return "";
        String normalizedYear = normalizeYear(year);
        return TextUtils.isEmpty(normalizedYear) ? "title:" + title : "title:" + title + "|year:" + normalizedYear;
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        return value.toLowerCase().replaceAll("[\\s\\p{Punct}·•，。！？：；、【】（）《》]+", "");
    }

    private static String normalizeYear(String value) {
        if (value == null) return "";
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(0, 4) : "";
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
