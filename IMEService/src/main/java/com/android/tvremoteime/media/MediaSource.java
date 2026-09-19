package com.android.tvremoteime.media;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class MediaSource {
    public String key;
    public String name;
    public int type;
    public String api;
    public String spider;
    public String ext;
    public int indexs;
    public int timeout;
    public boolean searchable;
    public boolean quickSearch;
    public final Map<String, String> headers = new LinkedHashMap<String, String>();

    public boolean isSupported() {
        return isType0() || isType3Csp();
    }

    public boolean isType0() {
        return type == 0 && api != null && (api.startsWith("http://") || api.startsWith("https://"));
    }

    public boolean isType3Csp() {
        return type == 3 && api != null && api.startsWith("csp_") && spider != null
                && (spider.startsWith("http://") || spider.startsWith("https://") || spider.startsWith("file:"));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("key", MediaItem.safe(key));
        obj.put("name", MediaItem.safe(name));
        obj.put("type", type);
        obj.put("api", MediaItem.safe(api));
        obj.put("spider", MediaItem.safe(spider));
        obj.put("indexs", indexs);
        obj.put("timeout", timeout);
        obj.put("searchable", searchable);
        obj.put("quickSearch", quickSearch);
        obj.put("supported", isSupported());
        return obj;
    }
}
