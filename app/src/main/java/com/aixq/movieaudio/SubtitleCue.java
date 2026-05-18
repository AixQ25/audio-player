package com.aixq.movieaudio;

public final class SubtitleCue {
    public final long startMs;
    public final long endMs;
    public final String text;

    public SubtitleCue(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
