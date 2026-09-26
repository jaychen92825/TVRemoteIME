package com.android.tvremoteime.media;

import org.json.JSONException;
import org.json.JSONObject;

public class MediaCategory {
    public String id;
    public String name;

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", MediaItem.safe(id));
        obj.put("name", MediaItem.safe(name));
        return obj;
    }
}
