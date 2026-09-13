package com.android.tvremoteime.media;

import android.net.Uri;
import android.text.TextUtils;

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
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return MediaVodParser.parseJson(trimmed, source);
        return parseXml(trimmed);
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
            MediaVodParser.fillCommon(source, item, text(video, "id"), text(video, "name"), text(video, "pic"),
                    text(video, "note"), text(video, "year"), text(video, "type"), text(video, "des"));
            parseXmlEpisodes(item, video);
            result.add(item);
        }
        return result;
    }

    private void parseXmlEpisodes(MediaDetail item, Element video) {
        NodeList dds = video.getElementsByTagName("dd");
        if (dds.getLength() == 0) {
            MediaVodParser.parseEpisodeGroup(item, "", text(video, "dl"));
            return;
        }
        for (int i = 0; i < dds.getLength(); i++) {
            Node node = dds.item(i);
            if (!(node instanceof Element)) continue;
            Element dd = (Element) node;
            MediaVodParser.parseEpisodeGroup(item, dd.getAttribute("flag"), dd.getTextContent());
        }
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
