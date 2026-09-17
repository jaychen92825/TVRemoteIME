package com.android.tvremoteime.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

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
        Spider.configureNetwork(getConfig());
    }

    public JSONObject loadFromUrl(String url) throws Exception {
        if (TextUtils.isEmpty(url)) throw new Exception("配置地址不能为空");
        ConfigLoadResult result = loadConfigUrl(url, 0);
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(KEY_URL, result.url);
        editor.putString(KEY_CONFIG, result.config.toString());
        editor.apply();
        Spider.configureNetwork(result.config);
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

    public MediaSource getDefaultSource() {
        List<MediaSource> sources = getSources();
        String home = getConfig() == null ? "" : getConfig().optString("home");
        if (!TextUtils.isEmpty(home)) {
            for (MediaSource source : sources) {
                if (home.equals(source.key) && source.isSupported()) return source;
            }
        }
        for (MediaSource source : sources) {
            if (source.isSupported()) return source;
        }
        return null;
    }

    public static List<MediaSource> parseSources(JSONObject config) {
        ArrayList<MediaSource> result = new ArrayList<MediaSource>();
        if (config == null) return result;
        JSONArray sites = config.optJSONArray("sites");
        if (sites == null) return result;
        String defaultSpider = config.optString("spider");
        for (int i = 0; i < sites.length(); i++) {
            JSONObject item = sites.optJSONObject(i);
            if (item == null) continue;
            MediaSource source = new MediaSource();
            source.key = item.optString("key");
            source.name = item.optString("name", source.key);
            source.type = item.optInt("type", 0);
            source.api = item.optString("api");
            source.spider = item.optString("jar", defaultSpider);
            source.ext = readExt(item);
            source.indexs = item.optInt("indexs", 0);
            source.timeout = item.optInt("timeout", 30);
            source.searchable = item.optInt("searchable", 1) != 0;
            source.quickSearch = item.optInt("quickSearch", 1) != 0;
            result.add(source);
        }
        return result;
    }

    private static String readExt(JSONObject item) {
        Object ext = item.opt("ext");
        if (ext == null || JSONObject.NULL.equals(ext)) return "";
        return ext.toString();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private ConfigLoadResult loadConfigUrl(String url, int depth) throws Exception {
        if (depth > 3) throw new Exception("配置跳转层级过多");
        String body;
        try {
            body = MediaHttp.getRequired(url);
        } catch (Exception e) {
            throw new Exception("配置下载失败：" + e.getMessage());
        }
        if (TextUtils.isEmpty(body)) throw new Exception("配置下载失败：返回内容为空");
        return parseConfigOrHtml(url, body, depth);
    }

    private ConfigLoadResult parseConfigOrHtml(String url, String body, int depth) throws Exception {
        JSONObject direct = parseJsonConfig(url, body);
        ConfigLoadResult resolved = resolveConfigObject(url, direct, depth);
        if (resolved != null) return resolved;

        String trimmed = body.trim().toLowerCase();
        if (!trimmed.startsWith("<!doctype") && !trimmed.startsWith("<html")) {
            throw new Exception("配置内容不是有效的 TVBox JSON");
        }

        List<String> candidates = extractConfigUrls(url, body);
        String lastError = "";
        for (String candidate : candidates) {
            try {
                return loadConfigUrl(candidate, depth + 1);
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }

        if (!candidates.isEmpty()) {
            throw new Exception("已识别这是导航页，但自动加载候选接口失败；请直接复制页面里的空壳接口地址，例如：" + candidates.get(0) + (TextUtils.isEmpty(lastError) ? "" : "。最后错误：" + lastError));
        }
        throw new Exception("这个地址返回的是网页，不是配置 JSON；请复制页面里“空壳接口”的真实配置地址后再连接。");
    }

    private ConfigLoadResult resolveConfigObject(String url, JSONObject object, int depth) throws Exception {
        if (object == null) return null;
        if (object.optJSONArray("sites") != null) return new ConfigLoadResult(url, object);
        JSONArray depots = object.optJSONArray("urls");
        if (depots == null) return null;
        for (int i = 0; i < depots.length(); i++) {
            Object item = depots.opt(i);
            String nextUrl = "";
            if (item instanceof JSONObject) nextUrl = ((JSONObject) item).optString("url");
            else if (item instanceof String) nextUrl = (String) item;
            if (TextUtils.isEmpty(nextUrl)) continue;
            try {
                return loadConfigUrl(resolveUrl(url, nextUrl), depth + 1);
            } catch (Exception ignored) {
            }
        }
        throw new Exception("多仓配置里没有可加载的点播配置");
    }

    private JSONObject parseJsonConfig(String url, String body) {
        if (TextUtils.isEmpty(body)) return null;
        String trimmed;
        try {
            trimmed = MediaConfigDecoder.decode(url, body).trim();
        } catch (Exception e) {
            return null;
        }
        if (!trimmed.startsWith("{")) return null;
        try {
            return new JSONObject(trimmed);
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
