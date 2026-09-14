package com.android.tvremoteime.server;

import android.content.Context;
import android.text.TextUtils;

import com.android.tvremoteime.VideoPlayHelper;
import com.android.tvremoteime.media.MediaConfigManager;
import com.android.tvremoteime.media.MediaDetail;
import com.android.tvremoteime.media.MediaItem;
import com.android.tvremoteime.media.MediaSource;
import com.android.tvremoteime.media.Type0SourceAdapter;
import com.android.tvremoteime.media.Type3SourceAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MediaRequestProcesser implements RequestProcesser {
    private final Context context;
    private final MediaConfigManager configManager;

    public MediaRequestProcesser(Context context) {
        this.context = context;
        this.configManager = new MediaConfigManager(context);
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
                if ("/media/home".equals(fileName)) return homeResponse();
                if ("/media/search".equals(fileName)) return searchResponse(params.get("q"));
                if ("/media/detail".equals(fileName)) return detailResponse(params.get("sourceKey"), params.get("id"));
            } else if (session.getMethod() == NanoHTTPD.Method.POST) {
                if ("/media/config".equals(fileName)) return saveConfigResponse(params.get("url"));
                if ("/media/play".equals(fileName)) return playResponse(params);
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        } catch (Exception e) {
            return errorResponse(e.getMessage());
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

    private NanoHTTPD.Response searchResponse(String keyword) throws Exception {
        JSONArray items = new JSONArray();
        String lastError = "";
        int tried = 0;
        long deadline = System.currentTimeMillis() + 24000;
        if (!TextUtils.isEmpty(keyword)) {
            for (MediaSource source : configManager.getSources()) {
                if (!source.isSupported() || !source.searchable) continue;
                if (System.currentTimeMillis() >= deadline) break;
                try {
                    int seconds = (int) Math.max(1, Math.min(4, (deadline - System.currentTimeMillis()) / 1000));
                    addItems(items, search(source, keyword, seconds));
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
                tried++;
                if (items.length() >= 60) break;
            }
        }
        JSONObject obj = new JSONObject();
        obj.put("items", items);
        if (items.length() == 0 && !TextUtils.isEmpty(keyword)) {
            obj.put("message", !TextUtils.isEmpty(lastError) && tried == 0 ? lastError : "没有搜到结果，请输入更具体的片名再试。");
        } else if (items.length() > 0 && System.currentTimeMillis() >= deadline) {
            obj.put("message", "已返回部分结果，部分源响应较慢已跳过。");
        }
        return ok(obj);
    }

    private NanoHTTPD.Response homeResponse() throws Exception {
        JSONObject obj = new JSONObject();
        JSONArray items = new JSONArray();
        String lastError = "";
        int tried = 0;
        long deadline = System.currentTimeMillis() + 17000;
        for (MediaSource source : homeCandidates()) {
            if (System.currentTimeMillis() >= deadline) break;
            try {
                int seconds = (int) Math.max(1, Math.min(4, (deadline - System.currentTimeMillis()) / 1000));
                addItems(items, home(source, seconds));
                tried++;
                if (items.length() > 0) break;
            } catch (Exception e) {
                tried++;
                lastError = e.getMessage();
            }
        }
        obj.put("items", items);
        if (items.length() == 0) {
            obj.put("message", tried == 0 ? "这个配置暂时没有可用首页源。" : "首页源响应较慢，可以直接搜索片名。");
        } else if (tried > 1 || !TextUtils.isEmpty(lastError)) {
            obj.put("message", "已跳过响应较慢的首页源。");
        }
        return ok(obj);
    }

    private NanoHTTPD.Response listResponse(MediaSource source, boolean requireSearchable, String keyword) throws Exception {
        JSONObject obj = new JSONObject();
        JSONArray items = new JSONArray();
        if (source != null && source.isSupported() && (!requireSearchable || source.searchable)) {
            addItems(items, TextUtils.isEmpty(keyword) ? home(source) : search(source, keyword));
        }
        obj.put("items", items);
        return ok(obj);
    }

    private NanoHTTPD.Response detailResponse(String sourceKey, String id) throws Exception {
        MediaSource source = requireSource(sourceKey);
        MediaDetail detail = detail(source, id);
        if (detail == null) throw new Exception("未找到详情");
        JSONObject obj = new JSONObject();
        obj.put("item", detail.toJson());
        return ok(obj);
    }

    private NanoHTTPD.Response playResponse(Map<String, String> params) throws Exception {
        MediaSource source = requireSource(params.get("sourceKey"));
        String url = resolve(source, params.get("flag"), params.get("playId"));
        if (TextUtils.isEmpty(url)) throw new Exception("无法解析播放地址");
        VideoPlayHelper.playUrl(context, url, 0, "true".equalsIgnoreCase(params.get("useSystem")));
        JSONObject obj = new JSONObject();
        obj.put("success", true);
        obj.put("playUrl", url);
        return ok(obj);
    }

    private List<MediaItem> home(MediaSource source) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).home();
        return new Type0SourceAdapter(source).home();
    }

    private List<MediaItem> home(MediaSource source, int maxSeconds) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).home(maxSeconds);
        return new Type0SourceAdapter(source).home();
    }

    private List<MediaItem> search(MediaSource source, String keyword) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).search(keyword, 6);
        return new Type0SourceAdapter(source).search(keyword);
    }

    private List<MediaItem> search(MediaSource source, String keyword, int maxSeconds) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).search(keyword, maxSeconds);
        return new Type0SourceAdapter(source).search(keyword);
    }

    private MediaDetail detail(MediaSource source, String id) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).detail(id);
        return new Type0SourceAdapter(source).detail(id);
    }

    private String resolve(MediaSource source, String flag, String playId) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).resolve(flag, playId);
        return new Type0SourceAdapter(source).resolve(playId);
    }

    private MediaSource firstHomeSource() {
        MediaSource fallback = null;
        for (MediaSource source : configManager.getSources()) {
            if (!source.isSupported()) continue;
            if (source.indexs == 1) return source;
            if (fallback == null) fallback = source;
        }
        return fallback;
    }

    private List<MediaSource> homeCandidates() {
        ArrayList<MediaSource> result = new ArrayList<MediaSource>();
        ArrayList<MediaSource> fallback = new ArrayList<MediaSource>();
        for (MediaSource source : configManager.getSources()) {
            if (!source.isSupported()) continue;
            if (source.indexs == 1) result.add(source);
            else if (fallback.size() < 8) fallback.add(source);
        }
        for (MediaSource source : fallback) {
            if (!result.contains(source)) result.add(source);
            if (result.size() >= 8) break;
        }
        return result;
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
    }

    private void addItems(JSONArray arr, List<MediaItem> items) throws Exception {
        for (MediaItem item : items) arr.put(item.toJson());
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
}
