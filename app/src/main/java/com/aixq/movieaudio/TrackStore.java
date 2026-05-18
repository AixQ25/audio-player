package com.aixq.movieaudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class TrackStore {
    private static final String PREFS = "movie_audio_store";
    private static final String KEY_TRACKS = "tracks_json";
    private static final String KEY_CURRENT_TRACK = "current_track_id";
    private static final String KEY_TOTAL_LISTENED = "total_listened_ms";

    private final SharedPreferences prefs;

    public TrackStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized ArrayList<Track> loadTracks() {
        ArrayList<Track> tracks = new ArrayList<>();
        String raw = prefs.getString(KEY_TRACKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    Track track = Track.fromJson(json);
                    if (!track.id.isEmpty() && !track.audioUri.isEmpty()) {
                        tracks.add(track);
                    }
                }
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }
        return tracks;
    }

    public synchronized Track find(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ArrayList<Track> tracks = loadTracks();
        for (Track track : tracks) {
            if (id.equals(track.id)) {
                return track;
            }
        }
        return null;
    }

    public synchronized Track currentTrack() {
        return find(getCurrentTrackId());
    }

    public synchronized void upsert(Track nextTrack) {
        ArrayList<Track> tracks = loadTracks();
        boolean found = false;
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id.equals(nextTrack.id)) {
                tracks.set(i, nextTrack);
                found = true;
                break;
            }
        }
        if (!found) {
            tracks.add(0, nextTrack);
        }
        saveTracks(tracks);
    }

    public synchronized void delete(String id) {
        ArrayList<Track> tracks = loadTracks();
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (tracks.get(i).id.equals(id)) {
                tracks.remove(i);
            }
        }
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_TRACKS, serialize(tracks));
        if (id != null && id.equals(getCurrentTrackId())) {
            String nextId = tracks.isEmpty() ? "" : tracks.get(0).id;
            editor.putString(KEY_CURRENT_TRACK, nextId);
        }
        editor.commit();
    }

    public synchronized void saveTracks(List<Track> tracks) {
        prefs.edit().putString(KEY_TRACKS, serialize(tracks)).commit();
    }

    public synchronized String getCurrentTrackId() {
        return prefs.getString(KEY_CURRENT_TRACK, "");
    }

    public synchronized void setCurrentTrackId(String id) {
        prefs.edit().putString(KEY_CURRENT_TRACK, id == null ? "" : id).commit();
    }

    public synchronized long getTotalListenedMs() {
        return prefs.getLong(KEY_TOTAL_LISTENED, 0L);
    }

    public synchronized String exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("app", "movie-audio-listener");
        root.put("exportedAt", System.currentTimeMillis());
        root.put("currentTrackId", getCurrentTrackId());
        root.put("totalListenedMs", getTotalListenedMs());
        root.put("tracks", new JSONArray(serialize(loadTracks())));
        return root.toString(2);
    }

    public synchronized int importJson(String raw) throws JSONException {
        JSONObject root;
        JSONArray array;
        try {
            root = new JSONObject(raw);
            array = root.optJSONArray("tracks");
            if (array == null) {
                throw new JSONException("Missing tracks");
            }
        } catch (JSONException objectError) {
            root = new JSONObject();
            array = new JSONArray(raw);
        }

        ArrayList<Track> importedTracks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            Track track = Track.fromJson(json);
            if (!track.id.isEmpty() && !track.audioUri.isEmpty()) {
                importedTracks.add(track);
            }
        }

        String currentTrackId = root.optString("currentTrackId", importedTracks.isEmpty() ? "" : importedTracks.get(0).id);
        long totalListenedMs = root.optLong("totalListenedMs", 0L);
        prefs.edit()
            .putString(KEY_TRACKS, serialize(importedTracks))
            .putString(KEY_CURRENT_TRACK, currentTrackId)
            .putLong(KEY_TOTAL_LISTENED, Math.max(0L, totalListenedMs))
            .commit();
        return importedTracks.size();
    }

    public synchronized Track updatePlayback(String id, long positionMs, long durationMs, long listenedDeltaMs) {
        ArrayList<Track> tracks = loadTracks();
        Track updated = null;
        for (Track track : tracks) {
            if (track.id.equals(id)) {
                track.positionMs = Math.max(0L, positionMs);
                if (durationMs > 0L) {
                    track.durationMs = durationMs;
                    if (track.positionMs > durationMs) {
                        track.positionMs = durationMs;
                    }
                }
                if (listenedDeltaMs > 0L) {
                    track.listenedMs += listenedDeltaMs;
                }
                track.updatedAt = System.currentTimeMillis();
                updated = track;
                break;
            }
        }

        if (updated != null) {
            long total = getTotalListenedMs() + Math.max(0L, listenedDeltaMs);
            prefs.edit()
                .putString(KEY_TRACKS, serialize(tracks))
                .putLong(KEY_TOTAL_LISTENED, total)
                .commit();
        }
        return updated;
    }

    private String serialize(List<Track> tracks) {
        JSONArray array = new JSONArray();
        for (Track track : tracks) {
            try {
                array.put(track.toJson());
            } catch (JSONException ignored) {
                // Skip malformed items rather than risking the whole store.
            }
        }
        return array.toString();
    }
}
