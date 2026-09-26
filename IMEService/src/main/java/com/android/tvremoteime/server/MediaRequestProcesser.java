package com.android.tvremoteime.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.VideoPlayHelper;
import com.android.tvremoteime.media.MediaBinary;
import com.android.tvremoteime.media.MediaBrowseResult;
import com.android.tvremoteime.media.MediaCategory;
import com.android.tvremoteime.media.MediaConfigManager;
import com.android.tvremoteime.media.MediaDetail;
import com.android.tvremoteime.media.MediaItem;
import com.android.tvremoteime.media.MediaHttp;
import com.android.tvremoteime.media.MediaLibraryStore;
import com.android.tvremoteime.media.MediaPlaybackManager;
import com.android.tvremoteime.media.MediaSource;
import com.android.tvremoteime.media.MediaSourceQualityStore;
import com.android.tvremoteime.media.Type0SourceAdapter;
import com.android.tvremoteime.media.Type3SourceAdapter;
import com.android.tvremoteime.media.WebVideoSniffer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class MediaRequestProcesser implements RequestProcesser {
    private final Context context;
    private final MediaConfigManager configManager;
    private final MediaLibraryStore libraryStore;
    private final MediaPlaybackManager playbackManager;
    private final MediaSourceQualityStore qualityStore;
    private final WebVideoSniffer webVideoSniffer;

    public MediaRequestProcesser(Context context) {
        this.context = context;
        this.configManager = new MediaConfigManager(context);
        this.libraryStore = new MediaLibraryStore(context);
        this.playbackManager = MediaPlaybackManager.get(context);
        this.qualityStore = new MediaSourceQualityStore(context);
        this.webVideoSniffer = WebVideoSniffer.get(context);
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && fileName.startsWith("/media/");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        try {
            if (session.getMethod() == NanoHTTPD.Method.GET) {
                if ("/media/config".equals(fileName)) return configResponse();
                if ("/media/home".equals(fileName)) return homeResponse(params.get("sourceKey"));
                if ("/media/category".equals(fileName)) return categoryResponse(params.get("sourceKey"), params.get("id"), params.get("page"));
                if ("/media/search".equals(fileName)) return searchResponse(params.get("q"), params.get("sourceKey"));
                if ("/media/detail".equals(fileName)) return detailResponse(params.get("sourceKey"), params.get("id"));
                if ("/media/image".equals(fileName)) return imageResponse(params.get("url"), params.get("sourceKey"));
                if ("/media/history".equals(fileName)) return libraryResponse(libraryStore.getHistory());
                if ("/media/favorites".equals(fileName)) return libraryResponse(libraryStore.getFavorites());
                if ("/media/live/sources".equals(fileName)) return liveSourcesResponse();
                if ("/media/live/list".equals(fileName)) return liveListResponse(params.get("index"));
                if ("/media/live/epg".equals(fileName)) return liveEpgResponse(params.get("index"), params.get("name"), params.get("date"));
                if ("/media/live/logo".equals(fileName)) return liveLogoResponse(params.get("index"), params.get("name"), params.get("url"));
                if ("/media/session".equals(fileName)) return ok(playbackManager.snapshot());
                if ("/media/web/session".equals(fileName)) return ok(webVideoSniffer.snapshot(params.get("sessionId")));
            } else if (session.getMethod() == NanoHTTPD.Method.POST) {
                if ("/media/config".equals(fileName)) return saveConfigResponse(params.get("url"));
                if ("/media/live/play".equals(fileName)) return livePlayResponse(params);
                if ("/media/play".equals(fileName)) return playResponse(params);
                if ("/media/resume".equals(fileName)) return ok(playbackManager.resumeCurrent());
                if ("/media/episode".equals(fileName)) {
                    String index = params.get("index");
                    return ok(TextUtils.isEmpty(index)
                            ? playbackManager.playAdjacent(parseDirection(params.get("direction")))
                            : playbackManager.playEpisodeAt(Integer.parseInt(index)));
                }
                if ("/media/switch".equals(fileName)) {
                    String type = params.get("type");
                    if ("route".equals(type)) return ok(playbackManager.switchRoute(params.get("flag")));
                    if ("source".equals(type)) return ok(playbackManager.switchSource(params.get("sourceKey")));
                    throw new Exception("未知的播放切换类型");
                }
                if ("/media/marker".equals(fileName)) return ok(playbackManager.updateMarker(params.get("action"), params.get("delta")));
                if ("/media/favorite".equals(fileName)) return favoriteResponse(params);
                if ("/media/history/clear".equals(fileName)) return clearHistoryResponse();
                if ("/media/web/sniff".equals(fileName)) return ok(webVideoSniffer.start(params.get("url")));
                if ("/media/web/play".equals(fileName)) return ok(webVideoSniffer.play(params.get("sessionId"), params.get("candidateId")));
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        } catch (Exception e) {
            Log.e(IMEService.TAG, "media request failed: " + fileName, e);
            return errorResponse(userFacingError(e));
        } catch (LinkageError e) {
            Log.e(IMEService.TAG, "media linkage failed: " + fileName, e);
            return errorResponse(userFacingError(e));
        }
    }

    private NanoHTTPD.Response configResponse() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("url", configManager.getConfigUrl());
        addSources(obj);
        return ok(obj);
    }

    private NanoHTTPD.Response saveConfigResponse(String url) throws Exception {
        configManager.loadFromUrl(url);
        JSONObject obj = new JSONObject();
        obj.put("url", configManager.getConfigUrl());
        addSources(obj);
        return ok(obj);
    }

    private NanoHTTPD.Response liveSourcesResponse() throws Exception {
        JSONArray raw = configManager.getLiveSources();
        JSONArray sources = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject live = raw.optJSONObject(i);
            if (live == null || TextUtils.isEmpty(firstLiveUrl(live))) continue;
            JSONObject item = new JSONObject();
            item.put("index", i);
            item.put("name", live.optString("name", "直播源 " + (i + 1)));
            item.put("type", live.optInt("type", 0));
            item.put("epg", live.optString("epg"));
            item.put("logo", live.optString("logo"));
            item.put("hasHeaders", !liveHeaders(live).isEmpty());
            item.put("catchupSupported", hasLiveCatchup(live));
            item.put("timeZone", live.optString("timeZone"));
            item.put("playerType", live.optInt("playerType", -1));
            item.put("timeout", live.optInt("timeout", 0));
            sources.put(item);
        }
        JSONObject obj = new JSONObject();
        obj.put("sources", sources);
        obj.put("count", sources.length());
        return ok(obj);
    }

    private NanoHTTPD.Response liveListResponse(String indexText) throws Exception {
        int index;
        try {
            index = Integer.parseInt(indexText == null ? "-1" : indexText);
        } catch (NumberFormatException e) {
            throw new Exception("直播源索引无效");
        }
        JSONArray lives = configManager.getLiveSources();
        JSONObject live = index >= 0 && index < lives.length() ? lives.optJSONObject(index) : null;
        if (live == null) throw new Exception("未找到这个直播源");
        String sourceUrl = firstLiveUrl(live);
        if (TextUtils.isEmpty(sourceUrl)) throw new Exception("这个直播源没有可用地址");
        String resolvedUrl = resolveLiveUrl(sourceUrl);
        if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
            throw new Exception("暂不支持这个直播源地址格式");
        }
        Map<String, String> headers = liveHeaders(live);
        String text = MediaHttp.getRequired(resolvedUrl, headers, configManager.getConfig());
        if (TextUtils.isEmpty(text)) throw new Exception("直播源返回内容为空");
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, text);
    }

    private NanoHTTPD.Response liveEpgResponse(String indexText, String channelName, String date) throws Exception {
        JSONObject live = requireLiveSource(indexText);
        String template = live.optString("epg").trim();
        if (TextUtils.isEmpty(template)) throw new Exception("这个直播源没有配置节目单");
        if (TextUtils.isEmpty(channelName)) throw new Exception("未指定频道名称");
        String epgUrl = applyLiveTemplate(template, channelName, date);
        epgUrl = resolveConfigUrl(epgUrl);
        String text = MediaHttp.getRequired(epgUrl, liveHeaders(live), configManager.getConfig());
        if (TextUtils.isEmpty(text)) throw new Exception("节目单返回内容为空");
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, text);
    }

    private NanoHTTPD.Response liveLogoResponse(String indexText, String channelName, String explicitUrl) throws Exception {
        JSONObject live = parseLiveSource(indexText);
        String logoUrl = explicitUrl == null ? "" : explicitUrl.trim();
        if (TextUtils.isEmpty(logoUrl)) {
            if (live == null) throw new Exception("这个频道没有台标地址");
            String template = live.optString("logo").trim();
            if (TextUtils.isEmpty(template)) throw new Exception("这个直播源没有配置台标");
            logoUrl = applyLiveTemplate(template, channelName, null);
            logoUrl = resolveConfigUrl(logoUrl);
        } else {
            logoUrl = resolveLiveAssetUrl(logoUrl, live);
        }
        MediaBinary image = MediaHttp.getBinary(logoUrl, live == null ? null : liveHeaders(live), configManager.getConfig());
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, image.mimeType,
                new ByteArrayInputStream(image.data), image.data.length);
        response.addHeader("Cache-Control", "public, max-age=86400");
        return response;
    }

    private NanoHTTPD.Response livePlayResponse(Map<String, String> params) throws Exception {
        String playUrl = params.get("playUrl");
        if (TextUtils.isEmpty(playUrl)) throw new Exception("未指定直播地址");
        JSONObject live = parseLiveSource(params.get("index"));
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        if (live != null) headers.putAll(liveHeaders(live));
        playUrl = stripInlineLiveHeaders(playUrl, headers);
        applyConfigLiveHeaders(playUrl, headers);
        boolean useSystem = "true".equalsIgnoreCase(params.get("useSystem"));
        String title = params.get("title");
        boolean forcedInternal = !headers.isEmpty();
        if (forcedInternal) {
            VideoPlayHelper.playDirectStream(context, playUrl, TextUtils.isEmpty(title) ? playUrl : title, headers);
        } else {
            VideoPlayHelper.playUrl(context, playUrl, 0, useSystem, title);
        }
        JSONObject obj = new JSONObject();
        obj.put("success", true);
        obj.put("forcedInternal", forcedInternal && useSystem);
        obj.put("headerCount", headers.size());
        return ok(obj);
    }

    private JSONObject parseLiveSource(String indexText) {
        if (TextUtils.isEmpty(indexText) || "local".equalsIgnoreCase(indexText)) return null;
        try {
            int index = Integer.parseInt(indexText);
            JSONArray lives = configManager.getLiveSources();
            return index >= 0 && index < lives.length() ? lives.optJSONObject(index) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject requireLiveSource(String indexText) throws Exception {
        JSONObject live = parseLiveSource(indexText);
        if (live == null) throw new Exception("未找到这个直播源");
        return live;
    }

    private boolean hasLiveCatchup(JSONObject live) {
        if (live == null) return false;
        Object value = live.opt("catchup");
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof JSONObject) return ((JSONObject) value).length() > 0;
        return !TextUtils.isEmpty(String.valueOf(value));
    }

    private String applyLiveTemplate(String template, String channelName, String date) throws Exception {
        String result = template == null ? "" : template;
        String encodedName = URLEncoder.encode(channelName == null ? "" : channelName, "UTF-8").replace("+", "%20");
        result = result.replace("{name}", encodedName);
        if (date != null) {
            String encodedDate = URLEncoder.encode(date, "UTF-8");
            result = result.replace("{date}", encodedDate);
        }
        return result;
    }

    private String resolveConfigUrl(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            String base = configManager.getConfigUrl();
            if (!TextUtils.isEmpty(base)) return new URL(new URL(base), value).toString();
        } catch (Exception ignored) {
        }
        return value;
    }

    private String resolveLiveAssetUrl(String value, JSONObject live) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            if (value.startsWith("http://") || value.startsWith("https://")) return value;
            if (live != null) {
                String listUrl = resolveLiveUrl(firstLiveUrl(live));
                if (!TextUtils.isEmpty(listUrl)) return new URL(new URL(listUrl), value).toString();
            }
        } catch (Exception ignored) {
        }
        return resolveConfigUrl(value);
    }

    private String stripInlineLiveHeaders(String value, Map<String, String> headers) {
        String result = value == null ? "" : value.trim();
        int pipe = result.indexOf('|');
        if (pipe <= 0) return result;
        String suffix = result.substring(pipe + 1);
        result = result.substring(0, pipe).trim();
        String[] parts = suffix.split("&");
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            try {
                String key = URLDecoder.decode(part.substring(0, equals), "UTF-8").trim();
                String val = URLDecoder.decode(part.substring(equals + 1), "UTF-8").trim();
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(val)) headers.put(key, val);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private void applyConfigLiveHeaders(String playUrl, Map<String, String> headers) {
        JSONObject config = configManager.getConfig();
        JSONArray rules = config == null ? null : config.optJSONArray("headers");
        if (rules == null || TextUtils.isEmpty(playUrl)) return;
        String host = "";
        try { host = new URL(playUrl).getHost(); } catch (Exception ignored) {}
        if (TextUtils.isEmpty(host)) return;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String ruleHost = rule.optString("host");
            if (TextUtils.isEmpty(ruleHost) || !(host.equalsIgnoreCase(ruleHost) || host.toLowerCase().endsWith("." + ruleHost.toLowerCase()))) continue;
            JSONObject object = rule.optJSONObject("header");
            if (object == null) continue;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key);
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)) headers.put(key, value);
            }
        }
    }

    private String firstLiveUrl(JSONObject live) {
        if (live == null) return "";
        Object raw = live.opt("url");
        if (raw instanceof JSONArray) {
            JSONArray urls = (JSONArray) raw;
            for (int i = 0; i < urls.length(); i++) {
                Object item = urls.opt(i);
                if (item instanceof JSONObject) {
                    String value = ((JSONObject) item).optString("url");
                    if (!TextUtils.isEmpty(value)) return value.trim();
                } else if (item != null && item != JSONObject.NULL) {
                    String value = item.toString().trim();
                    if (!TextUtils.isEmpty(value)) return value;
                }
            }
            return "";
        }
        if (raw instanceof JSONObject) return ((JSONObject) raw).optString("url").trim();
        return raw == null || raw == JSONObject.NULL ? "" : raw.toString().trim();
    }

    private Map<String, String> liveHeaders(JSONObject live) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        JSONObject object = live.optJSONObject("header");
        if (object == null) object = live.optJSONObject("headers");
        if (object == null) {
            String raw = live.optString("header").trim();
            if (!TextUtils.isEmpty(raw) && raw.startsWith("{")) {
                try { object = new JSONObject(raw); } catch (Exception ignored) {}
            }
        }
        if (object != null) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key);
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)) headers.put(key, value);
            }
        }
        String ua = live.optString("ua");
        if (TextUtils.isEmpty(ua)) ua = live.optString("userAgent");
        if (!TextUtils.isEmpty(ua)) headers.put("User-Agent", ua);
        String referer = live.optString("referer");
        if (TextUtils.isEmpty(referer)) referer = live.optString("referrer");
        if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
        String origin = live.optString("origin");
        if (!TextUtils.isEmpty(origin)) headers.put("Origin", origin);
        return headers;
    }

    private String resolveLiveUrl(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            String base = configManager.getConfigUrl();
            if (!TextUtils.isEmpty(base)) return new URL(new URL(base), value).toString();
        } catch (Exception ignored) {
        }
        return value;
    }

    private NanoHTTPD.Response searchResponse(String keyword, String sourceKey) throws Exception {
        JSONObject obj = new JSONObject();
        JSONArray items = new JSONArray();
        if (TextUtils.isEmpty(keyword)) {
            obj.put("items", items);
            return ok(obj);
        }

        MediaSource selected = configManager.getSource(sourceKey);
        if (selected != null && selected.isSupported() && selected.searchable) {
            List<MediaItem> result;
            try {
                result = search(selected, keyword, false);
                qualityStore.recordSuccess(selected.key);
            } catch (Exception e) {
                qualityStore.recordFailure(selected.key);
                throw e;
            }
            addItems(items, rankAndDedupe(result, keyword, 60));
            obj.put("sourceKey", selected.key);
            obj.put("sourceName", selected.name);
            obj.put("searchedSources", 1);
        } else {
            SearchBatch batch = quickSearch(keyword);
            addItems(items, batch.items);
            obj.put("searchedSources", batch.searchedSources);
            obj.put("rawMatches", batch.rawMatches);
            obj.put("dedupedItems", batch.items.size());
            obj.put("global", true);
        }
        obj.put("items", items);
        if (items.length() == 0) {
            obj.put("message", selected != null && selected.searchable ? "当前源没有搜索到结果。" : "快速搜索源没有返回结果。");
        }
        return ok(obj);
    }

    private NanoHTTPD.Response homeResponse(String sourceKey) throws Exception {
        MediaSource source = requestedSource(sourceKey);
        if (source == null) throw new Exception("这个配置没有可用的点播源");
        MediaBrowseResult result;
        try {
            result = home(source);
            qualityStore.recordSuccess(source.key);
        } catch (Exception e) {
            qualityStore.recordFailure(source.key);
            throw e;
        }
        JSONObject obj = new JSONObject();
        obj.put("sourceKey", source.key);
        obj.put("sourceName", source.name);
        JSONArray categories = new JSONArray();
        JSONArray items = new JSONArray();
        addCategories(categories, result.categories);
        addItems(items, result.items);
        obj.put("categories", categories);
        obj.put("items", items);
        return ok(obj);
    }

    private NanoHTTPD.Response categoryResponse(String sourceKey, String id, String page) throws Exception {
        if (TextUtils.isEmpty(id)) throw new Exception("未指定分类");
        MediaSource source = requireSource(sourceKey);
        JSONObject obj = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            addItems(items, category(source, id, page));
            qualityStore.recordSuccess(source.key);
        } catch (Exception e) {
            qualityStore.recordFailure(source.key);
            throw e;
        }
        obj.put("sourceKey", source.key);
        obj.put("sourceName", source.name);
        obj.put("categoryId", id);
        obj.put("items", items);
        return ok(obj);
    }

    private NanoHTTPD.Response detailResponse(String sourceKey, String id) throws Exception {
        MediaSource source = requireSource(sourceKey);
        if (source.indexs == 1) throw new Exception("索引源卡片需要按片名搜索，不能直接加载详情");
        if (TextUtils.isEmpty(id)) throw new Exception("媒体源没有返回详情 ID");
        MediaDetail detail;
        try {
            detail = detail(source, id);
        } catch (Exception e) {
            qualityStore.recordFailure(source.key);
            throw e;
        }
        if (detail == null) {
            qualityStore.recordFailure(source.key);
            String displayId = id.length() > 64 ? id.substring(0, 64) + "..." : id;
            throw new Exception("源「" + source.name + "」未返回详情，ID=" + displayId);
        }
        qualityStore.recordSuccess(source.key);
        if (TextUtils.isEmpty(detail.id)) detail.id = id;
        JSONObject obj = new JSONObject();
        JSONObject item = detail.toJson();
        String canonicalId = MediaLibraryStore.canonicalMediaId(detail.name, detail.year);
        item.put("canonicalId", canonicalId);
        item.put("favorite", libraryStore.isFavorite(source.key, detail.id, canonicalId, detail.name, detail.year));
        JSONObject history = libraryStore.getHistoryItem(source.key, detail.id, canonicalId, detail.name, detail.year);
        if (history != null) item.put("history", history);
        obj.put("item", item);
        return ok(obj);
    }

    private NanoHTTPD.Response playResponse(Map<String, String> params) throws Exception {
        return ok(playbackManager.play(params));
    }

    private int parseDirection(String direction) {
        if ("prev".equalsIgnoreCase(direction) || "previous".equalsIgnoreCase(direction) || "-1".equals(direction)) return -1;
        return 1;
    }

    private NanoHTTPD.Response libraryResponse(JSONArray items) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("items", items);
        return ok(obj);
    }

    private NanoHTTPD.Response favoriteResponse(Map<String, String> params) throws Exception {
        if (TextUtils.isEmpty(params.get("sourceKey")) || TextUtils.isEmpty(params.get("mediaId"))) {
            throw new Exception("缺少收藏所需的媒体信息");
        }
        JSONObject obj = new JSONObject();
        obj.put("favorite", libraryStore.toggleFavorite(libraryItem(params)));
        return ok(obj);
    }

    private NanoHTTPD.Response clearHistoryResponse() throws Exception {
        libraryStore.clearHistory();
        JSONObject obj = new JSONObject();
        obj.put("success", true);
        return ok(obj);
    }

    private JSONObject libraryItem(Map<String, String> params) throws Exception {
        JSONObject item = new JSONObject();
        item.put("sourceKey", safe(params.get("sourceKey")));
        item.put("sourceName", safe(params.get("sourceName")));
        item.put("id", safe(params.get("mediaId")));
        item.put("name", safe(params.get("mediaName")));
        item.put("pic", safe(params.get("pic")));
        item.put("score", safe(params.get("score")));
        item.put("remark", safe(params.get("remark")));
        item.put("year", safe(params.get("year")));
        item.put("type", safe(params.get("type")));
        item.put("canonicalId", safe(params.get("canonicalId")));
        return item;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private NanoHTTPD.Response imageResponse(String url, String sourceKey) throws Exception {
        if (TextUtils.isEmpty(url)) throw new Exception("未指定图片地址");
        MediaBinary image;
        if (url.regionMatches(true, 0, "proxy://", 0, 8)) {
            MediaSource source = requireSource(sourceKey);
            if (!source.isType3Csp()) throw new Exception("这个媒体源不支持 spider 图片代理");
            image = new Type3SourceAdapter(context, source).proxyImage(url);
        } else {
            MediaSource source = TextUtils.isEmpty(sourceKey) ? null : configManager.getSource(sourceKey);
            image = com.android.tvremoteime.media.MediaHttp.getBinary(
                    url, source == null ? null : source.headers, configManager.getConfig());
        }
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, image.mimeType,
                new ByteArrayInputStream(image.data), image.data.length);
        response.addHeader("Cache-Control", "public, max-age=86400");
        return response;
    }

    private MediaBrowseResult home(MediaSource source) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).home();
        MediaBrowseResult result = new MediaBrowseResult();
        result.items = new Type0SourceAdapter(source, configManager.getConfig()).home();
        return result;
    }

    private List<MediaItem> search(MediaSource source, String keyword, boolean quick) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).search(keyword, quick);
        return new Type0SourceAdapter(source, configManager.getConfig()).search(keyword);
    }

    private List<MediaItem> category(MediaSource source, String id, String page) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).category(id, page);
        return new Type0SourceAdapter(source, configManager.getConfig()).category(id, page);
    }

    private MediaDetail detail(MediaSource source, String id) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).detail(id);
        return new Type0SourceAdapter(source, configManager.getConfig()).detail(id);
    }

    private String resolve(MediaSource source, String flag, String playId) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).resolve(flag, playId);
        return new Type0SourceAdapter(source, configManager.getConfig()).resolve(playId);
    }

    private MediaSource requestedSource(String sourceKey) {
        MediaSource source = TextUtils.isEmpty(sourceKey) ? null : configManager.getSource(sourceKey);
        return source != null && source.isSupported() ? source : configManager.getDefaultSource();
    }

    private MediaSource requireSource(String sourceKey) throws Exception {
        MediaSource source = configManager.getSource(sourceKey);
        if (source == null || !source.isSupported()) throw new Exception("不支持或未找到该媒体源");
        return source;
    }

    private void addSources(JSONObject obj) throws Exception {
        JSONArray arr = new JSONArray();
        int supported = 0;
        List<MediaSource> sources = configManager.getSources();
        for (MediaSource source : sources) {
            if (source.isSupported()) supported++;
            arr.put(source.toJson());
        }
        obj.put("sources", arr);
        obj.put("totalSources", sources.size());
        obj.put("supportedSources", supported);
        obj.put("unsupportedSources", sources.size() - supported);
        obj.put("liveSources", configManager.getLiveSources().length());
        MediaSource defaultSource = configManager.getDefaultSource();
        obj.put("defaultSourceKey", defaultSource == null ? "" : defaultSource.key);
    }

    private void addItems(JSONArray arr, List<MediaItem> items) throws Exception {
        for (MediaItem item : items) arr.put(item.toJson());
    }

    private void addCategories(JSONArray arr, List<MediaCategory> categories) throws Exception {
        for (MediaCategory category : categories) arr.put(category.toJson());
    }

    private SearchBatch quickSearch(final String keyword) throws Exception {
        List<MediaSource> rawCandidates = new ArrayList<MediaSource>();
        for (MediaSource source : configManager.getSources()) {
            if (source.isSupported() && source.searchable) rawCandidates.add(source);
        }
        List<MediaSource> rankedCandidates = qualityStore.rank(rawCandidates);
        final List<MediaSource> candidates = new ArrayList<MediaSource>();
        for (MediaSource source : rankedCandidates) {
            if (source.quickSearch) candidates.add(source);
            if (candidates.size() >= 18) break;
        }
        if (candidates.size() < 18) {
            for (MediaSource source : rankedCandidates) {
                if (source.quickSearch || candidates.contains(source)) continue;
                candidates.add(source);
                if (candidates.size() >= 18) break;
            }
        }
        SearchBatch batch = new SearchBatch();
        if (candidates.isEmpty()) return batch;

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(6, candidates.size()));
        CompletionService<SourceSearchResult> completion = new ExecutorCompletionService<SourceSearchResult>(executor);
        List<Future<SourceSearchResult>> futures = new ArrayList<Future<SourceSearchResult>>();
        for (final MediaSource source : candidates) {
            futures.add(completion.submit(new Callable<SourceSearchResult>() {
                @Override
                public SourceSearchResult call() {
                    SourceSearchResult result = new SourceSearchResult(source);
                    try {
                        result.items = search(source, keyword, true);
                        qualityStore.recordSuccess(source.key);
                    } catch (Exception e) {
                        qualityStore.recordFailure(source.key);
                    }
                    return result;
                }
            }));
        }

        List<MediaItem> rawItems = new ArrayList<MediaItem>();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        try {
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || rawItems.size() >= 180) break;
                Future<SourceSearchResult> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) break;
                batch.searchedSources++;
                try {
                    SourceSearchResult result = future.get();
                    if (result != null && result.items != null) {
                        for (MediaItem item : result.items) {
                            rawItems.add(item);
                            if (rawItems.size() >= 180) break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            for (Future<SourceSearchResult> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        batch.rawMatches = rawItems.size();
        batch.items.addAll(rankAndDedupe(rawItems, keyword, 60));
        return batch;
    }

    private List<MediaItem> rankAndDedupe(List<MediaItem> input, final String keyword, int limit) {
        List<RankedItem> ranked = new ArrayList<RankedItem>();
        for (MediaItem item : input) {
            if (item == null || TextUtils.isEmpty(item.name)) continue;
            ranked.add(new RankedItem(item, searchScore(item, keyword)));
        }
        Collections.sort(ranked, new Comparator<RankedItem>() {
            @Override
            public int compare(RankedItem left, RankedItem right) {
                if (left.score != right.score) return right.score - left.score;
                return safe(left.item.name).compareToIgnoreCase(safe(right.item.name));
            }
        });

        LinkedHashMap<String, MediaItem> deduped = new LinkedHashMap<String, MediaItem>();
        for (RankedItem rankedItem : ranked) {
            MediaItem item = rankedItem.item;
            String titleKey = normalize(item.name);
            if (TextUtils.isEmpty(titleKey)) titleKey = safe(item.sourceKey) + ":" + safe(item.id);
            String key = titleKey;
            MediaItem existing = deduped.get(key);
            if (existing != null && !TextUtils.isEmpty(existing.year) && !TextUtils.isEmpty(item.year)
                    && !TextUtils.equals(existing.year, item.year)) {
                key = titleKey + "|" + item.year;
            }
            if (!deduped.containsKey(key)) deduped.put(key, item);
            if (deduped.size() >= limit) break;
        }
        return new ArrayList<MediaItem>(deduped.values());
    }

    private int searchScore(MediaItem item, String keyword) {
        String query = normalize(keyword);
        String name = normalize(item.name);
        int score = 0;
        if (!TextUtils.isEmpty(query) && query.equals(name)) score += 10000;
        else if (!TextUtils.isEmpty(query) && name.startsWith(query)) score += 8000;
        else if (!TextUtils.isEmpty(query) && name.contains(query)) score += 6500;
        else if (!TextUtils.isEmpty(name) && query.contains(name)) score += 6000;
        else score += 1000;
        score += Math.max(-500, Math.min(500, qualityStore.score(item.sourceKey) * 10));
        if (!TextUtils.isEmpty(item.pic)) score += 40;
        if (!TextUtils.isEmpty(item.year) && safe(keyword).contains(item.year)) score += 120;
        if (!TextUtils.isEmpty(item.score)) score += 10;
        return score;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase().replaceAll("[\\s\\p{Punct}·•，。！？：；、【】（）《》]+", "");
    }

    private static class SearchBatch {
        final List<MediaItem> items = new ArrayList<MediaItem>();
        int searchedSources;
        int rawMatches;
    }

    private static class SourceSearchResult {
        final MediaSource source;
        List<MediaItem> items = new ArrayList<MediaItem>();

        SourceSearchResult(MediaSource source) {
            this.source = source;
        }
    }

    private static class RankedItem {
        final MediaItem item;
        final int score;

        RankedItem(MediaItem item, int score) {
            this.item = item;
            this.score = score;
        }
    }

    private NanoHTTPD.Response ok(JSONObject obj) {
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, obj.toString());
    }

    private NanoHTTPD.Response errorResponse(String message) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("message", TextUtils.isEmpty(message) ? "请求失败" : message);
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, obj.toString());
        } catch (Exception ignored) {
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "请求失败");
        }
    }

    private String userFacingError(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        StringBuilder trace = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 6) {
            trace.append(current.getClass().getName()).append(' ');
            if (!TextUtils.isEmpty(current.getMessage())) trace.append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        String detail = trace.toString().toLowerCase();

        if (detail.contains("spider 调用超时")
                || detail.contains("sockettimeoutexception")
                || detail.contains("timeoutexception")
                || detail.contains("timed out")) {
            return "媒体源响应超时，请重试或切换其他源。";
        }
        if (detail.contains("classnotfoundexception")
                || detail.contains("noclassdeffounderror")
                || detail.contains("nosuchmethoderror")
                || detail.contains("incompatibleclasschangeerror")
                || detail.contains("verifyerror")) {
            return "这个源需要的 Spider 组件与当前版本不兼容，请切换其他源。";
        }
        if (detail.contains("unknownhostexception")
                || detail.contains("connectexception")
                || detail.contains("ssl")
                || detail.contains("connection reset")
                || detail.contains("connection refused")) {
            return "媒体源暂时无法连接，请检查网络或切换其他源。";
        }
        if (!TextUtils.isEmpty(message) && message.contains("未返回详情")) {
            return "这个源没有返回可用详情，请返回浏览或切换其他源。";
        }
        if (TextUtils.isEmpty(message)) return "媒体源处理失败，请重试或切换其他源。";
        if (message.startsWith("java.")
                || message.contains("Exception:")
                || message.contains("Error:")
                || message.contains("com.github.catvod.")) {
            return "媒体源处理失败，请重试或切换其他源。";
        }
        return message;
    }
}
