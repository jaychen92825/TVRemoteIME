package com.android.tvremoteime.media;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Type3SourceAdapter {
    private final Context context;
    private final MediaSource source;

    public Type3SourceAdapter(Context context, MediaSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
    }

    public List<MediaItem> home() throws Exception {
        return callWithTimeout(new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                return doHome();
            }
        }, source.timeout);
    }

    public List<MediaItem> search(final String keyword) throws Exception {
        return search(keyword, source.timeout);
    }

    public List<MediaItem> search(final String keyword, int maxSeconds) throws Exception {
        return callWithTimeout(new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                return doSearch(keyword);
            }
        }, maxSeconds);
    }

    public MediaDetail detail(final String id) throws Exception {
        return callWithTimeout(new Callable<MediaDetail>() {
            @Override
            public MediaDetail call() throws Exception {
                return doDetail(id);
            }
        }, source.timeout);
    }

    public String resolve(final String flag, final String playId) throws Exception {
        return callWithTimeout(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return doResolve(flag, playId);
            }
        }, source.timeout);
    }

    private List<MediaItem> doHome() throws Exception {
        final Spider spider = spider();
        return withSpiderLoader(spider, new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                List<MediaItem> video = parseList(spider.homeVideoContent());
                if (!video.isEmpty()) return video;
                return parseList(spider.homeContent(true));
            }
        });
    }

    private List<MediaItem> doSearch(String keyword) throws Exception {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<MediaItem>();
        final Spider spider = spider();
        final String key = keyword;
        return withSpiderLoader(spider, new Callable<List<MediaItem>>() {
            @Override
            public List<MediaItem> call() throws Exception {
                String body = spider.searchContent(key, false);
                if (TextUtils.isEmpty(body)) body = spider.searchContent(key, false, "1");
                return parseList(body);
            }
        });
    }

    private MediaDetail doDetail(String id) throws Exception {
        final Spider spider = spider();
        final String itemId = id;
        String body = withSpiderLoader(spider, new Callable<String>() {
            @Override
            public String call() throws Exception {
                return spider.detailContent(Collections.singletonList(itemId));
            }
        });
        List<MediaItem> list = parseList(body);
        if (list.isEmpty()) return null;
        MediaItem item = list.get(0);
        if (item instanceof MediaDetail) return (MediaDetail) item;
        MediaDetail detail = new MediaDetail();
        detail.sourceKey = item.sourceKey;
        detail.sourceName = item.sourceName;
        detail.id = item.id;
        detail.name = item.name;
        detail.pic = item.pic;
        detail.remark = item.remark;
        detail.year = item.year;
        detail.type = item.type;
        detail.desc = item.desc;
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(callable);
        try {
            return future.get(Math.max(5, seconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("spider 调用超时");
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T withSpiderLoader(Spider spider, Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(spider.getClass().getClassLoader());
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
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
