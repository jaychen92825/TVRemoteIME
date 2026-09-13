package com.android.tvremoteime.media;

import org.json.JSONException;
import org.json.JSONObject;

public class MediaEpisode {
    public String name;
    public String playId;

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("name", MediaItem.safe(name));
        obj.put("playId", MediaItem.safe(playId));
        return obj;
    }
}
