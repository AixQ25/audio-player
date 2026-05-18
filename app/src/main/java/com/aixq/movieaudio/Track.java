package com.aixq.movieaudio;

import org.json.JSONException;
import org.json.JSONObject;

public final class Track {
    public String id = "";
    public String name = "";
    public String audioUri = "";
    public String subtitleUri = "";
    public String subtitleName = "";
    public long addedAt = 0L;
    public long updatedAt = 0L;
    public long durationMs = 0L;
    public long positionMs = 0L;
    public long listenedMs = 0L;
    public long subtitleOffsetMs = 900L;
    public float playbackSpeed = 1.0f;

    public static Track fromJson(JSONObject json) {
        Track track = new Track();
        track.id = json.optString("id", "");
        track.name = json.optString("name", "");
        track.audioUri = json.optString("audioUri", "");
        track.subtitleUri = json.optString("subtitleUri", "");
        track.subtitleName = json.optString("subtitleName", "");
        track.addedAt = json.optLong("addedAt", 0L);
        track.updatedAt = json.optLong("updatedAt", 0L);
        track.durationMs = json.optLong("durationMs", 0L);
        track.positionMs = json.optLong("positionMs", 0L);
        track.listenedMs = json.optLong("listenedMs", 0L);
        track.subtitleOffsetMs = json.optLong("subtitleOffsetMs", 900L);
        track.playbackSpeed = (float) json.optDouble("playbackSpeed", 1.0);
        if (track.playbackSpeed < 0.5f || track.playbackSpeed > 2.0f) {
            track.playbackSpeed = 1.0f;
        }
        return track;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("audioUri", audioUri);
        json.put("subtitleUri", subtitleUri);
        json.put("subtitleName", subtitleName);
        json.put("addedAt", addedAt);
        json.put("updatedAt", updatedAt);
        json.put("durationMs", durationMs);
        json.put("positionMs", positionMs);
        json.put("listenedMs", listenedMs);
        json.put("subtitleOffsetMs", subtitleOffsetMs);
        json.put("playbackSpeed", playbackSpeed);
        return json;
    }
}
