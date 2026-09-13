package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        ConfigLoadResult result = parseConfigOrHtml(url, body);
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(KEY_URL, result.url);
        editor.putString(KEY_CONFIG, result.config.toString());
        editor.apply();
        return result.config;
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

    private ConfigLoadResult parseConfigOrHtml(String url, String body) throws Exception {
        JSONObject direct = parseConfig(body);
        if (direct != null) return new ConfigLoadResult(url, direct);

        String trimmed = body.trim().toLowerCase();
        if (!trimmed.startsWith("<!doctype") && !trimmed.startsWith("<html")) {
            throw new Exception("配置内容不是有效的 TVBox JSON");
        }

        for (String candidate : extractConfigUrls(url, body)) {
            String candidateBody = MediaHttp.get(candidate);
            if (TextUtils.isEmpty(candidateBody)) continue;
            JSONObject config = parseConfig(candidateBody);
            if (config != null) return new ConfigLoadResult(candidate, config);
        }

        throw new Exception("这个地址返回的是网页，不是配置 JSON；请复制页面里“空壳接口”的真实配置地址后再连接。");
    }

    private JSONObject parseConfig(String body) {
        if (TextUtils.isEmpty(body)) return null;
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return null;
        try {
            JSONObject config = new JSONObject(trimmed);
            return config.optJSONArray("sites") == null ? null : config;
        } catch (JSONException e) {
            return null;
        }
    }

    private List<String> extractConfigUrls(String baseUrl, String html) {
        ArrayList<String> urls = new ArrayList<String>();
        Pattern pattern = Pattern.compile("(?:data-clipboard-text|href)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String value = htmlDecode(matcher.group(1).trim());
            if (!looksLikeConfigUrl(value)) continue;
            String resolved = resolveUrl(baseUrl, value);
            if (!urls.contains(resolved)) urls.add(resolved);
        }
        return urls;
    }

    private boolean looksLikeConfigUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("./") && !lower.startsWith("/")) return false;
        if (lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) return false;
        return lower.contains("tv") || lower.contains("box") || lower.contains("json") || lower.contains("bmp") || !lower.contains(".");
    }

    private String resolveUrl(String baseUrl, String value) {
        try {
            return new URL(new URL(baseUrl), value).toString();
        } catch (Exception e) {
            return value;
        }
    }

    private String htmlDecode(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static class ConfigLoadResult {
        String url;
        JSONObject config;

        ConfigLoadResult(String url, JSONObject config) {
            this.url = url;
            this.config = config;
        }
    }
}
