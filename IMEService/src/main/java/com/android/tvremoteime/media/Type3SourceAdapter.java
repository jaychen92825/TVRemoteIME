package com.android.tvremoteime.media;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.IMEService;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import fi.iki.elonen.NanoHTTPD;

public class Type3SourceAdapter {
    private static final ExecutorService SPIDER_EXECUTOR = Executors.newCachedThreadPool();
    private static final int MAX_PROXY_IMAGE_BYTES = 10 * 1024 * 1024;

    private final Context context;
    private final MediaSource source;

    public Type3SourceAdapter(Context context, MediaSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
    }

    public MediaBrowseResult home() throws Exception {
        return callWithTimeout(new Callable<MediaBrowseResult>() {
            @Override
            public MediaBrowseResult call() throws Exception {
                return doHome();
            }
        }, operationTimeout());
    }

    public List<MediaItem> search(final String keyword) throws Exception {
        return search(keyword, false);
    }

    public List<MediaItem> search(final String keyword, final boolean quick) throws Exception {
        return callWithTimeout(new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                return doSearch(keyword, quick);
            }
        }, operationTimeout());
    }

    public List<MediaItem> category(final String id, final String page) throws Exception {
        return callWithTimeout(new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                return doCategory(id, page);
            }
        }, operationTimeout());
    }

    public MediaDetail detail(final String id) throws Exception {
        return callWithTimeout(new Callable<MediaDetail>() {
            @Override
            public MediaDetail call() throws Exception {
                return doDetail(id);
            }
        }, operationTimeout());
    }

    public String resolve(final String flag, final String playId) throws Exception {
        return callWithTimeout(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return doResolve(flag, playId);
            }
        }, operationTimeout());
    }

    public MediaBinary proxyImage(final String proxyUrl) throws Exception {
        return callWithTimeout(new Callable<MediaBinary>() {
            @Override
            public MediaBinary call() throws Exception {
                return doProxyImage(proxyUrl);
            }
        }, operationTimeout());
    }

    private MediaBrowseResult doHome() throws Exception {
        final Spider spider = spider();
        return withSpiderLoader(spider, new Callable<MediaBrowseResult>() {
            @Override
            public MediaBrowseResult call() throws Exception {
                MediaBrowseResult result = new MediaBrowseResult();
                // CatVod sources may initialize state while building the category list.
                // Keep the same order as FongMi/OKTV: home first, recommendations second.
                String home = spider.homeContent(true);
                result.categories = MediaVodParser.parseCategories(home);
                result.items = parseList(home);
                String video = "";
                if (result.items.isEmpty()) {
                    video = spider.homeVideoContent();
                    result.items = parseList(video);
                }
                Log.i(IMEService.TAG, "media spider home: " + source.key + ", categories="
                        + result.categories.size() + ", items=" + result.items.size()
                        + ", homeLength=" + length(home) + ", videoLength=" + length(video));
                return result;
            }
        });
    }

    private List<MediaItem> doSearch(String keyword, final boolean quick) throws Exception {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<MediaItem>();
        final Spider spider = spider();
        final String key = keyword;
        return withSpiderLoader(spider, new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                String body = spider.searchContent(key, quick);
                List<MediaItem> items = parseList(body);
                Log.i(IMEService.TAG, "media spider search: " + source.key + ", quick=" + quick
                        + ", items=" + items.size() + ", bodyLength=" + length(body));
                return items;
            }
        });
    }

    private List<MediaItem> doCategory(String id, String page) throws Exception {
        final Spider spider = spider();
        final String typeId = id;
        final String pageNumber = TextUtils.isEmpty(page) ? "1" : page;
        return withSpiderLoader(spider, new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                String body = spider.categoryContent(typeId, pageNumber, true, new HashMap<String, String>());
                List<MediaItem> items = parseList(body);
                Log.i(IMEService.TAG, "media spider category: " + source.key + ", id=" + typeId
                        + ", items=" + items.size() + ", bodyLength=" + length(body));
                return items;
            }
        });
    }

    private MediaDetail doDetail(String id) throws Exception {
        final Spider spider = spider();
        final String itemId = id;
        String body = withSpiderLoader(spider, new Callable<String>() {
            @Override
            public String call() throws Exception {
                // Match FongMi exactly. Some guarded spiders replace the item in
                // this fixed-size list while normalizing an encoded detail id.
                return spider.detailContent(Arrays.asList(itemId));
            }
        });
        List<MediaItem> list = parseList(body);
        Log.i(IMEService.TAG, "media spider detail: " + source.key + ", idLength="
                + length(itemId) + ", items=" + list.size() + ", bodyLength=" + length(body));
        if (list.isEmpty()) return null;
        MediaItem item = list.get(0);
        if (item instanceof MediaDetail) return (MediaDetail) item;
        MediaDetail detail = new MediaDetail();
        detail.sourceKey = item.sourceKey;
        detail.sourceName = item.sourceName;
        detail.id = item.id;
        detail.name = item.name;
        detail.pic = item.pic;
        detail.score = item.score;
        detail.remark = item.remark;
        detail.year = item.year;
        detail.type = item.type;
        detail.desc = item.desc;
        detail.tag = item.tag;
        detail.action = item.action;
        detail.folder = item.folder;
        return detail;
    }

    private String doResolve(String flag, String playId) throws Exception {
        if (TextUtils.isEmpty(playId)) return "";
        if (isDirectUrl(playId)) return playId;
        final Spider spider = spider();
        final String playFlag = MediaItem.safe(flag);
        final String id = playId;
        String body = withSpiderLoader(spider, new Callable<String>() {
            @Override
            public String call() throws Exception {
                return spider.playerContent(playFlag, id, Collections.<String>emptyList());
            }
        });
        if (TextUtils.isEmpty(body)) return "";
        JSONObject obj = new JSONObject(body);
        String url = extractUrl(obj.opt("url"));
        if (TextUtils.isEmpty(url)) url = obj.optString("playUrl");
        return TextUtils.isEmpty(url) ? "" : stripName(url);
    }

    private MediaBinary doProxyImage(String proxyUrl) throws Exception {
        final Spider spider = spider();
        final Map<String, String> params = parseProxyParams(proxyUrl);
        params.put("siteKey", MediaItem.safe(source.key));
        Object[] response = withSpiderLoader(spider, new Callable<Object[]>() {
            @Override
            public Object[] call() throws Exception {
                return spider.proxy(params);
            }
        });
        if (response == null || response.length == 0) throw new IOException("spider 图片代理返回无效");
        if (response[0] instanceof NanoHTTPD.Response) {
            NanoHTTPD.Response direct = (NanoHTTPD.Response) response[0];
            try {
                int status = direct.getStatus() == null ? 500 : direct.getStatus().getRequestStatus();
                if (status < 200 || status >= 300) throw new IOException("spider 图片代理 HTTP " + status);
                return readProxyImage(direct.getData(), direct.getMimeType());
            } finally {
                try {
                    direct.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (response.length < 3) throw new IOException("spider 图片代理返回无效");
        if (!(response[0] instanceof Number)) throw new IOException("spider 图片代理状态无效");
        int status = ((Number) response[0]).intValue();
        if (status < 200 || status >= 300) throw new IOException("spider 图片代理 HTTP " + status);
        if (!(response[2] instanceof InputStream)) throw new IOException("spider 图片代理内容无效");
        return readProxyImage((InputStream) response[2], response[1] instanceof String ? (String) response[1] : null);
    }

    private MediaBinary readProxyImage(InputStream input, String mimeType) throws Exception {
        if (input == null) throw new IOException("spider 图片代理内容为空");
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                if (output.size() > MAX_PROXY_IMAGE_BYTES) throw new IOException("图片文件过大");
            }
        } finally {
            try {
                input.close();
            } catch (Exception ignored) {
            }
        }

        MediaBinary result = new MediaBinary();
        result.data = output.toByteArray();
        result.mimeType = MediaHttp.resolveImageMime(mimeType, result.data);
        return result;
    }

    private Map<String, String> parseProxyParams(String proxyUrl) throws Exception {
        if (TextUtils.isEmpty(proxyUrl) || !proxyUrl.regionMatches(true, 0, "proxy://", 0, 8)) {
            throw new IOException("无效的 proxy 图片地址");
        }
        String query = proxyUrl.substring(8);
        if (query.startsWith("?")) query = query.substring(1);
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        if (query.length() == 0) return params;
        for (String entry : query.split("&")) {
            if (entry.length() == 0) continue;
            int split = entry.indexOf('=');
            String key = split < 0 ? entry : entry.substring(0, split);
            String value = split < 0 ? "" : entry.substring(split + 1);
            key = URLDecoder.decode(key, "UTF-8");
            value = URLDecoder.decode(value, "UTF-8");
            if (key.length() > 0) params.put(key, value);
        }
        return params;
    }

    private Spider spider() throws Exception {
        return MediaSpiderManager.get(context).getSpider(source);
    }

    private List<MediaItem> parseList(String body) throws Exception {
        if (TextUtils.isEmpty(body)) return new ArrayList<MediaItem>();
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return new ArrayList<MediaItem>();
        return MediaVodParser.parseJson(trimmed, source);
    }

    private <T> T callWithTimeout(Callable<T> callable, int seconds) throws Exception {
        Future<T> future = SPIDER_EXECUTOR.submit(callable);
        try {
            return future.get(Math.max(1, seconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("spider 调用超时");
        }
    }

    private int operationTimeout() {
        return Math.min(60, Math.max(30, source.timeout));
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private <T> T withSpiderLoader(Spider spider, Callable<T> callable) throws Exception {
        synchronized (spider) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(spider.getClass().getClassLoader());
                return callable.call();
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private String extractUrl(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return "";
        if (value instanceof JSONArray) return extractUrl((JSONArray) value);
        return value.toString();
    }

    private String extractUrl(JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (isDirectUrl(value)) return value;
        }
        return array.length() > 0 ? array.optString(0) : "";
    }

    private boolean isDirectUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("magnet:");
    }

    private String stripName(String value) {
        int p = value.indexOf('$');
        return p >= 0 ? value.substring(p + 1) : value;
    }
}
