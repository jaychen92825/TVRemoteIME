package com.android.tvremoteime.media;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MediaVodParser {
    public static List<MediaCategory> parseCategories(String body) throws Exception {
        ArrayList<MediaCategory> result = new ArrayList<MediaCategory>();
        if (TextUtils.isEmpty(body) || !body.trim().startsWith("{")) return result;
        JSONObject root = new JSONObject(body);
        JSONArray types = root.optJSONArray("class");
        if (types == null) return result;
        for (int i = 0; i < types.length(); i++) {
            JSONObject obj = types.optJSONObject(i);
            if (obj == null) continue;
            MediaCategory category = new MediaCategory();
            category.id = obj.optString("type_id", obj.optString("id"));
            category.name = obj.optString("type_name", obj.optString("name"));
            if (!TextUtils.isEmpty(category.id) && !TextUtils.isEmpty(category.name)) result.add(category);
        }
        return result;
    }

    public static List<MediaItem> parseJson(String body, MediaSource source) throws Exception {
        ArrayList<MediaItem> result = new ArrayList<MediaItem>();
        if (TextUtils.isEmpty(body)) return result;
        JSONObject root = body.trim().startsWith("[") ? new JSONObject().put("list", new JSONArray(body)) : new JSONObject(body);
        JSONArray list = root.optJSONArray("list");
        if (list == null) list = root.optJSONArray("data");
        if (list == null) return result;
        for (int i = 0; i < list.length(); i++) {
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            MediaDetail item = new MediaDetail();
            fillCommon(source, item, obj.optString("vod_id", obj.optString("id")),
                    obj.optString("vod_name", obj.optString("name")),
                    obj.optString("vod_pic", obj.optString("pic")),
                    obj.optString("vod_remarks", obj.optString("remarks")),
                    obj.optString("vod_year"), obj.optString("type_name"),
                    obj.optString("vod_content", obj.optString("content")));
            item.score = obj.optString("vod_score", obj.optString("score"));
            item.tag = obj.optString("vod_tag", obj.optString("tag"));
            item.action = obj.optString("action");
            item.folder = "folder".equalsIgnoreCase(item.tag)
                    || (!obj.isNull("cate") && obj.has("cate"))
                    || (item.id != null && item.id.endsWith("@folder"));
            parseEpisodes(item, obj.optString("vod_play_from"), obj.optString("vod_play_url"));
            result.add(item);
        }
        return result;
    }

    public static void fillCommon(MediaSource source, MediaItem item, String id, String name, String pic, String remark, String year, String type, String desc) {
        item.sourceKey = source.key;
        item.sourceName = source.name;
        item.id = id;
        item.name = name;
        item.pic = normalizePic(source, pic);
        item.remark = remark;
        item.year = year;
        item.type = type;
        item.desc = desc == null ? "" : desc.replaceAll("<[^>]+>", "").trim();
    }

    static String normalizePic(MediaSource source, String pic) {
        if (TextUtils.isEmpty(pic)) return "";
        String value = pic.trim().replace("&amp;", "&");
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) return value;
        if (value.startsWith("//")) {
            String scheme = source != null && source.api != null && source.api.startsWith("http://") ? "http:" : "https:";
            return scheme + value;
        }
        if (source != null && source.isType0()) {
            try {
                return new URL(new URL(source.api), value).toString();
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    public static void parseEpisodes(MediaDetail item, String playFrom, String playUrl) {
        if (TextUtils.isEmpty(playUrl)) return;
        String[] flags = TextUtils.isEmpty(playFrom) ? new String[0] : playFrom.split("\\$\\$\\$");
        String[] groups = playUrl.split("\\$\\$\\$");
        for (int i = 0; i < groups.length; i++) {
            String flag = i < flags.length ? flags[i].trim() : "";
            parseEpisodeGroup(item, flag, groups[i]);
        }
    }

    public static void parseEpisodeGroup(MediaDetail item, String flag, String playList) {
        if (TextUtils.isEmpty(playList)) return;
        String[] episodes = playList.split("#");
        for (int i = 0; i < episodes.length; i++) {
            String raw = episodes[i];
            if (TextUtils.isEmpty(raw)) continue;
            MediaEpisode episode = new MediaEpisode();
            int p = raw.indexOf('$');
            episode.name = p > 0 ? raw.substring(0, p) : "第" + (i + 1) + "集";
            episode.playId = p > 0 ? raw.substring(p + 1) : raw;
            episode.flag = flag;
            item.episodes.add(episode);
        }
    }
}
