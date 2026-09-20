package com.android.tvremoteime.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.media.MediaBinary;
import com.android.tvremoteime.media.MediaBrowseResult;
import com.android.tvremoteime.media.MediaCategory;
import com.android.tvremoteime.media.MediaConfigManager;
import com.android.tvremoteime.media.MediaDetail;
import com.android.tvremoteime.media.MediaItem;
import com.android.tvremoteime.media.MediaLibraryStore;
import com.android.tvremoteime.media.MediaPlaybackManager;
import com.android.tvremoteime.media.MediaSource;
import com.android.tvremoteime.media.MediaSourceQualityStore;
import com.android.tvremoteime.media.Type0SourceAdapter;
import com.android.tvremoteime.media.Type3SourceAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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

    public MediaRequestProcesser(Context context) {
        this.context = context;
        this.configManager = new MediaConfigManager(context);
        this.libraryStore = new MediaLibraryStore(context);
        this.playbackManager = MediaPlaybackManager.get(context);
        this.qualityStore = new MediaSourceQualityStore(context);
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
                if ("/media/session".equals(fileName)) return ok(playbackManager.snapshot());
            } else if (session.getMethod() == NanoHTTPD.Method.POST) {
                if ("/media/config".equals(fileName)) return saveConfigResponse(params.get("url"));
                if ("/media/play".equals(fileName)) return playResponse(params);
                if ("/media/resume".equals(fileName)) return ok(playbackManager.resumeCurrent());
                if ("/media/episode".equals(fileName)) {
                    String index = params.get("index");
                    return ok(TextUtils.isEmpty(index)
                            ? playbackManager.playAdjacent(parseDirection(params.get("direction")))
                            : playbackManager.playEpisodeAt(Integer.parseInt(index)));
                }
                if ("/media/marker".equals(fileName)) return ok(playbackManager.updateMarker(params.get("action"), params.get("delta")));
                if ("/media/favorite".equals(fileName)) return favoriteResponse(params);
                if ("/media/history/clear".equals(fileName)) return clearHistoryResponse();
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
        item.put("favorite", libraryStore.isFavorite(source.key, detail.id));
        JSONObject history = libraryStore.getHistoryItem(source.key, detail.id);
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
}
