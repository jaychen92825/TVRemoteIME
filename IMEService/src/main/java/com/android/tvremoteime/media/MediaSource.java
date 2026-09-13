package com.android.tvremoteime.media;

import org.json.JSONException;
import org.json.JSONObject;

public class MediaSource {
    public String key;
    public String name;
    public int type;
    public String api;
    public String spider;
    public String ext;
    public boolean searchable;

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
        obj.put("searchable", searchable);
        obj.put("supported", isSupported());
        return obj;
    }
}
