package com.android.tvremoteime.media;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Type3SourceAdapter {
    private final Context context;
    private final MediaSource source;

    public Type3SourceAdapter(Context context, MediaSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
    }

    public List<MediaItem> home() throws Exception {
        Spider spider = spider();
        List<MediaItem> video = parseList(spider.homeVideoContent());
        if (!video.isEmpty()) return video;
        return parseList(spider.homeContent(true));
    }

    public List<MediaItem> search(String keyword) throws Exception {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<MediaItem>();
        String body = spider().searchContent(keyword, false);
        if (TextUtils.isEmpty(body)) body = spider().searchContent(keyword, false, "1");
        return parseList(body);
    }

    public MediaDetail detail(String id) throws Exception {
        String body = spider().detailContent(Collections.singletonList(id));
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

    public String resolve(String flag, String playId) throws Exception {
        if (TextUtils.isEmpty(playId)) return "";
        if (isDirectUrl(playId)) return playId;
        String body = spider().playerContent(MediaItem.safe(flag), playId, Collections.<String>emptyList());
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
