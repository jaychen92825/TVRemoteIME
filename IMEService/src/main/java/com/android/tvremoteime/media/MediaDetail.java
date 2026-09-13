package com.android.tvremoteime.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MediaDetail extends MediaItem {
    public List<MediaEpisode> episodes = new ArrayList<MediaEpisode>();

    @Override
    public JSONObject toJson() throws JSONException {
        JSONObject obj = super.toJson();
        JSONArray arr = new JSONArray();
        for (MediaEpisode episode : episodes) arr.put(episode.toJson());
        obj.put("episodes", arr);
        return obj;
    }
}
