package com.android.tvremoteime.media;

import org.json.JSONException;
import org.json.JSONObject;

public class MediaItem {
    public String sourceKey;
    public String sourceName;
    public String id;
    public String name;
    public String pic;
    public String remark;
    public String year;
    public String type;
    public String desc;

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("sourceKey", safe(sourceKey));
        obj.put("sourceName", safe(sourceName));
        obj.put("id", safe(id));
        obj.put("name", safe(name));
        obj.put("pic", safe(pic));
        obj.put("remark", safe(remark));
        obj.put("year", safe(year));
        obj.put("type", safe(type));
        obj.put("desc", safe(desc));
        return obj;
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }
}
