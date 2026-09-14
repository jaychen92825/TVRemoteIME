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
import com.android.tvremoteime.media.MediaSource;
import com.android.tvremoteime.media.Type0SourceAdapter;
import com.android.tvremoteime.media.Type3SourceAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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
                if ("/media/home".equals(fileName)) return homeResponse(params.get("sourceKey"));
                if ("/media/category".equals(fileName)) return categoryResponse(params.get("sourceKey"), params.get("id"), params.get("page"));
                if ("/media/search".equals(fileName)) return searchResponse(params.get("q"), params.get("sourceKey"));
                if ("/media/detail".equals(fileName)) return detailResponse(params.get("sourceKey"), params.get("id"));
                if ("/media/image".equals(fileName)) return imageResponse(params.get("url"));
            } else if (session.getMethod() == NanoHTTPD.Method.POST) {
                if ("/media/config".equals(fileName)) return saveConfigResponse(params.get("url"));
                if ("/media/play".equals(fileName)) return playResponse(params);
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        } catch (Exception e) {
            Log.e(IMEService.TAG, "media request failed: " + fileName, e);
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

    private NanoHTTPD.Response searchResponse(String keyword, String sourceKey) throws Exception {
        JSONObject obj = new JSONObject();
        JSONArray items = new JSONArray();
        if (TextUtils.isEmpty(keyword)) {
            obj.put("items", items);
            return ok(obj);
        }

        MediaSource selected = configManager.getSource(sourceKey);
        if (selected != null && selected.isSupported() && selected.searchable) {
            addItems(items, search(selected, keyword, false));
            obj.put("sourceKey", selected.key);
            obj.put("sourceName", selected.name);
            obj.put("searchedSources", 1);
        } else {
            SearchBatch batch = quickSearch(keyword);
            addItems(items, batch.items);
            obj.put("searchedSources", batch.searchedSources);
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
        MediaBrowseResult result = home(source);
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
        addItems(items, category(source, id, page));
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
        MediaDetail detail = detail(source, id);
        if (detail == null) {
            String displayId = id.length() > 64 ? id.substring(0, 64) + "..." : id;
            throw new Exception("源「" + source.name + "」未返回详情，ID=" + displayId);
        }
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

    private NanoHTTPD.Response imageResponse(String url) throws Exception {
        if (TextUtils.isEmpty(url)) throw new Exception("未指定图片地址");
        MediaBinary image = com.android.tvremoteime.media.MediaHttp.getBinary(url);
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, image.mimeType,
                new ByteArrayInputStream(image.data), image.data.length);
    }

    private MediaBrowseResult home(MediaSource source) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).home();
        MediaBrowseResult result = new MediaBrowseResult();
        result.items = new Type0SourceAdapter(source).home();
        return result;
    }

    private List<MediaItem> search(MediaSource source, String keyword, boolean quick) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).search(keyword, quick);
        return new Type0SourceAdapter(source).search(keyword);
    }

    private List<MediaItem> category(MediaSource source, String id, String page) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).category(id, page);
        return new Type0SourceAdapter(source).category(id, page);
    }

    private MediaDetail detail(MediaSource source, String id) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).detail(id);
        return new Type0SourceAdapter(source).detail(id);
    }

    private String resolve(MediaSource source, String flag, String playId) throws Exception {
        if (source.isType3Csp()) return new Type3SourceAdapter(context, source).resolve(flag, playId);
        return new Type0SourceAdapter(source).resolve(playId);
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
        final List<MediaSource> candidates = new ArrayList<MediaSource>();
        for (MediaSource source : configManager.getSources()) {
            if (source.isSupported() && source.searchable && source.quickSearch) candidates.add(source);
        }
        SearchBatch batch = new SearchBatch();
        if (candidates.isEmpty()) return batch;

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(6, candidates.size()));
        CompletionService<List<MediaItem>> completion = new ExecutorCompletionService<List<MediaItem>>(executor);
        List<Future<List<MediaItem>>> futures = new ArrayList<Future<List<MediaItem>>>();
        for (final MediaSource source : candidates) {
            futures.add(completion.submit(new Callable<List<MediaItem>>() {
                @Override
                public List<MediaItem> call() throws Exception {
                    return search(source, keyword, true);
                }
            }));
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        try {
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || batch.items.size() >= 60) break;
                Future<List<MediaItem>> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) break;
                batch.searchedSources++;
                try {
                    List<MediaItem> result = future.get();
                    for (MediaItem item : result) {
                        batch.items.add(item);
                        if (batch.items.size() >= 60) break;
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            for (Future<List<MediaItem>> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        return batch;
    }

    private static class SearchBatch {
        final List<MediaItem> items = new ArrayList<MediaItem>();
        int searchedSources;
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
