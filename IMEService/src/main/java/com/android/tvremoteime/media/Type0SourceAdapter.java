package com.android.tvremoteime.media;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class Type0SourceAdapter {
    private final MediaSource source;

    public Type0SourceAdapter(MediaSource source) {
        this.source = source;
    }

    public List<MediaItem> home() throws Exception {
        return requestList(urlWith("ac", "videolist", "pg", "1"));
    }

    public List<MediaItem> search(String keyword) throws Exception {
        return requestList(urlWith("ac", "videolist", "wd", keyword));
    }

    public MediaDetail detail(String id) throws Exception {
        List<MediaItem> list = requestList(urlWith("ac", "videolist", "ids", id));
        if (list.isEmpty()) return null;
        MediaItem item = list.get(0);
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
        if (item instanceof MediaDetail) detail.episodes.addAll(((MediaDetail) item).episodes);
        return detail;
    }

    public String resolve(String playId) throws Exception {
        if (TextUtils.isEmpty(playId)) return "";
        if (playId.startsWith("http://") || playId.startsWith("https://") || playId.startsWith("magnet:")) return playId;
        String body = MediaHttp.get(urlWith("ac", "play", "ids", playId));
        if (!TextUtils.isEmpty(body)) {
            try {
                JSONObject obj = new JSONObject(body);
                String url = obj.optString("url");
                if (!TextUtils.isEmpty(url)) return stripName(url);
            } catch (Exception ignored) {
            }
        }
        return stripName(playId);
    }

    private List<MediaItem> requestList(String url) throws Exception {
        String body = MediaHttp.get(url);
        if (TextUtils.isEmpty(body)) return new ArrayList<MediaItem>();
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return parseJson(trimmed);
        return parseXml(trimmed);
    }

    private List<MediaItem> parseJson(String body) throws Exception {
        ArrayList<MediaItem> result = new ArrayList<MediaItem>();
        JSONObject root = body.startsWith("[") ? new JSONObject().put("list", new JSONArray(body)) : new JSONObject(body);
        JSONArray list = root.optJSONArray("list");
        if (list == null) list = root.optJSONArray("data");
        if (list == null) return result;
        for (int i = 0; i < list.length(); i++) {
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            MediaDetail item = new MediaDetail();
            fillCommon(item, obj.optString("vod_id", obj.optString("id")),
                    obj.optString("vod_name", obj.optString("name")),
                    obj.optString("vod_pic", obj.optString("pic")),
                    obj.optString("vod_remarks", obj.optString("remarks")),
                    obj.optString("vod_year"), obj.optString("type_name"),
                    obj.optString("vod_content", obj.optString("content")));
            parseEpisodes(item, obj.optString("vod_play_url"));
            result.add(item);
        }
        return result;
    }

    private List<MediaItem> parseXml(String body) throws Exception {
        ArrayList<MediaItem> result = new ArrayList<MediaItem>();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(body)));
        NodeList videos = doc.getElementsByTagName("video");
        for (int i = 0; i < videos.getLength(); i++) {
            Node node = videos.item(i);
            if (!(node instanceof Element)) continue;
            Element video = (Element) node;
            MediaDetail item = new MediaDetail();
            fillCommon(item, text(video, "id"), text(video, "name"), text(video, "pic"),
                    text(video, "note"), text(video, "year"), text(video, "type"), text(video, "des"));
            parseEpisodes(item, playText(video));
            result.add(item);
        }
        return result;
    }

    private void fillCommon(MediaItem item, String id, String name, String pic, String remark, String year, String type, String desc) {
        item.sourceKey = source.key;
        item.sourceName = source.name;
        item.id = id;
        item.name = name;
        item.pic = pic;
        item.remark = remark;
        item.year = year;
        item.type = type;
        item.desc = desc == null ? "" : desc.replaceAll("<[^>]+>", "").trim();
    }

    private void parseEpisodes(MediaDetail item, String playList) {
        if (TextUtils.isEmpty(playList)) return;
        String firstLine = playList.split("#\\$#", 2)[0];
        String[] episodes = firstLine.split("#");
        for (int i = 0; i < episodes.length; i++) {
            String raw = episodes[i];
            if (TextUtils.isEmpty(raw)) continue;
            MediaEpisode episode = new MediaEpisode();
            int p = raw.indexOf('$');
            episode.name = p > 0 ? raw.substring(0, p) : "第" + (i + 1) + "集";
            episode.playId = p > 0 ? raw.substring(p + 1) : raw;
            item.episodes.add(episode);
        }
    }

    private String playText(Element video) {
        NodeList dds = video.getElementsByTagName("dd");
        if (dds.getLength() > 0) return dds.item(0).getTextContent();
        return text(video, "dl");
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent();
    }

    private String stripName(String value) {
        int p = value.indexOf('$');
        return p >= 0 ? value.substring(p + 1) : value;
    }

    private String urlWith(String k1, String v1, String k2, String v2) {
        Uri.Builder builder = Uri.parse(source.api).buildUpon();
        builder.appendQueryParameter(k1, v1);
        if (!TextUtils.isEmpty(v2)) builder.appendQueryParameter(k2, v2);
        return builder.build().toString();
    }
}
