package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MediaConfigManager {
    private static final String PREF = "media_browser";
    private static final String KEY_URL = "config_url";
    private static final String KEY_CONFIG = "config_json";

    private final Context context;

    public MediaConfigManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject loadFromUrl(String url) throws Exception {
        if (TextUtils.isEmpty(url)) throw new Exception("配置地址不能为空");
        String body = MediaHttp.get(url);
        if (TextUtils.isEmpty(body)) throw new Exception("配置下载失败");
        JSONObject config = new JSONObject(body);
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(KEY_URL, url);
        editor.putString(KEY_CONFIG, config.toString());
        editor.apply();
        return config;
    }

    public String getConfigUrl() {
        return prefs().getString(KEY_URL, "");
    }

    public JSONObject getConfig() {
        String text = prefs().getString(KEY_CONFIG, "");
        if (TextUtils.isEmpty(text)) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    public List<MediaSource> getSources() {
        return parseSources(getConfig());
    }

    public MediaSource getSource(String key) {
        for (MediaSource source : getSources()) {
            if (source.key != null && source.key.equals(key)) return source;
        }
        return null;
    }

    public static List<MediaSource> parseSources(JSONObject config) {
        ArrayList<MediaSource> result = new ArrayList<MediaSource>();
        if (config == null) return result;
        JSONArray sites = config.optJSONArray("sites");
        if (sites == null) return result;
        for (int i = 0; i < sites.length(); i++) {
            JSONObject item = sites.optJSONObject(i);
            if (item == null) continue;
            MediaSource source = new MediaSource();
            source.key = item.optString("key");
            source.name = item.optString("name", source.key);
            source.type = item.optInt("type", 0);
            source.api = item.optString("api");
            source.searchable = item.optInt("searchable", 1) != 0;
            result.add(source);
        }
        return result;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
