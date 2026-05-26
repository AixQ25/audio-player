package com.aixq.movieaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public final class PlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_LOAD = "com.aixq.movieaudio.LOAD";
    public static final String ACTION_PLAY = "com.aixq.movieaudio.PLAY";
    public static final String ACTION_PAUSE = "com.aixq.movieaudio.PAUSE";
    public static final String ACTION_SEEK_TO = "com.aixq.movieaudio.SEEK_TO";
    public static final String ACTION_SEEK_RELATIVE = "com.aixq.movieaudio.SEEK_RELATIVE";
    public static final String ACTION_SET_SPEED = "com.aixq.movieaudio.SET_SPEED";
    public static final String ACTION_QUERY = "com.aixq.movieaudio.QUERY";
    public static final String ACTION_STOP = "com.aixq.movieaudio.STOP";
    public static final String ACTION_STATE = "com.aixq.movieaudio.STATE";

    public static final String EXTRA_TRACK_ID = "track_id";
    public static final String EXTRA_AUTOPLAY = "autoplay";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String EXTRA_SEEK_DELTA_MS = "seek_delta_ms";
    public static final String EXTRA_TRACK_NAME = "track_name";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_FILE_LISTENED_MS = "file_listened_ms";
    public static final String EXTRA_TOTAL_LISTENED_MS = "total_listened_ms";
    public static final String EXTRA_PLAYBACK_SPEED = "playback_speed";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL_ID = "movie_audio_playback";
    private static final int NOTIFICATION_ID = 4201;
    private static final long SEEK_SMALL_STEP_MS = 10_000L;
    private static final long SEEK_LARGE_STEP_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isActuallyPlaying()) {
                saveProgress(true);
                updateMediaSession();
                publishState();
                refreshNotification(true);
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private TrackStore store;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Track currentTrack;
    private boolean prepared;
    private boolean pendingPlay;
    private boolean isForeground;
    private long lastListenClockMs;
    private String lastError = "";

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pausePlayback();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new TrackStore(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
        createMediaSession();
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_LOAD.equals(action)) {
            loadTrack(intent.getStringExtra(EXTRA_TRACK_ID), intent.getBooleanExtra(EXTRA_AUTOPLAY, false));
        } else if (ACTION_PLAY.equals(action)) {
            playCurrent();
        } else if (ACTION_PAUSE.equals(action)) {
            pausePlayback();
        } else if (ACTION_SEEK_TO.equals(action)) {
            seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0L));
        } else if (ACTION_SEEK_RELATIVE.equals(action)) {
            seekBy(intent.getLongExtra(EXTRA_SEEK_DELTA_MS, 0L));
        } else if (ACTION_SET_SPEED.equals(action)) {
            setPlaybackSpeed(intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, 1.0f));
        } else if (ACTION_STOP.equals(action)) {
            pausePlayback();
            stopSelf();
        } else {
            publishState();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        saveProgress(false);
        handler.removeCallbacks(ticker);
        releasePlayer();
        abandonAudioFocus();
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        saveProgress(false);
        if (!isActuallyPlaying()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) {
            player.setVolume(0.25f);
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && player != null) {
            player.setVolume(1f);
        }
    }

    private void loadTrack(String trackId, boolean autoplay) {
        Track track = trackId == null || trackId.isEmpty() ? store.currentTrack() : store.find(trackId);
        if (track == null) {
            lastError = "没有可播放的 MP3 文件";
            publishState();
            return;
        }

        saveProgress(false);
        releasePlayer();
        currentTrack = track;
        store.setCurrentTrackId(track.id);
        prepared = false;
        pendingPlay = autoplay;
        lastError = "";

        player = new ExoPlayer.Builder(this)
            .setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.CONTENT_TYPE_SPEECH)
                    .build(),
                false
            )
            .setHandleAudioBecomingNoisy(true)
            .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    boolean firstReady = !prepared;
                    if (firstReady) {
                        prepared = true;
                        long durationMs = Math.max(0L, player.getDuration());
                        long resumeMs = currentTrack == null ? 0L : Math.max(0L, currentTrack.positionMs);
                        if (durationMs > 0L && resumeMs > durationMs - 1000L) {
                            resumeMs = 0L;
                        }
                        if (currentTrack != null) {
                            currentTrack = store.updatePlayback(currentTrack.id, resumeMs, durationMs, 0L);
                        }
                        applyPlaybackSpeed(currentTrack == null ? 1.0f : currentTrack.playbackSpeed);
                        if (resumeMs > 0L) {
                            player.seekTo(resumeMs);
                        }
                    }
                    updateMediaSession();
                    publishState();
                    if (player.isPlaying()) {
                        if (lastListenClockMs <= 0L) {
                            lastListenClockMs = android.os.SystemClock.elapsedRealtime();
                        }
                        handler.removeCallbacks(ticker);
                        handler.post(ticker);
                    }
                    final boolean shouldPlay = pendingPlay;
                    if (shouldPlay) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                startPlayback();
                            }
                        });
                    } else {
                        refreshNotification(isActuallyPlaying());
                    }
                    return;
                }
                if (state == Player.STATE_ENDED) {
                    saveProgress(true);
                    handler.removeCallbacks(ticker);
                    lastListenClockMs = 0L;
                    if (currentTrack != null) {
                        currentTrack = store.updatePlayback(currentTrack.id, 0L, getDurationMs(), 0L);
                    }
                    updateMediaSession();
                    publishState();
                    refreshNotification(false);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                lastError = "播放失败，文件可能已被移动或权限失效";
                handler.removeCallbacks(ticker);
                lastListenClockMs = 0L;
                updateMediaSession();
                publishState();
                refreshNotification(false);
            }
        });

        player.setMediaItem(MediaItem.fromUri(Uri.parse(track.audioUri)));
        player.prepare();
        publishState();
        if (autoplay) {
            refreshNotification(true);
        }
    }

    private void playCurrent() {
        if (player == null || currentTrack == null) {
            loadTrack(store.getCurrentTrackId(), true);
            return;
        }
        if (!prepared) {
            pendingPlay = true;
            refreshNotification(true);
            return;
        }
        startPlayback();
    }

    private void startPlayback() {
        if (player == null || !prepared) {
            pendingPlay = true;
            return;
        }
        if (!requestAudioFocus()) {
            lastError = "无法获得音频焦点";
            publishState();
            return;
        }
        player.play();
        applyPlaybackSpeed(currentTrack == null ? 1.0f : currentTrack.playbackSpeed);
        pendingPlay = false;
        lastListenClockMs = android.os.SystemClock.elapsedRealtime();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updateMediaSession();
        publishState();
        refreshNotification(true);
    }

    private void pausePlayback() {
        if (player == null) {
            publishState();
            return;
        }
        saveProgress(true);
        if (player.getPlayWhenReady()) {
            player.pause();
        }
        lastListenClockMs = 0L;
        handler.removeCallbacks(ticker);
        abandonAudioFocus();
        updateMediaSession();
        publishState();
        refreshNotification(false);
    }

    private void seekBy(long deltaMs) {
        seekTo(getPositionMs() + deltaMs);
    }

    private void seekTo(long positionMs) {
        if (player == null || !prepared) {
            return;
        }
        long durationMs = getDurationMs();
        long nextMs = Math.max(0L, positionMs);
        if (durationMs > 0L) {
            nextMs = Math.min(nextMs, durationMs);
        }
        player.seekTo(nextMs);
        if (currentTrack != null) {
            currentTrack = store.updatePlayback(currentTrack.id, nextMs, durationMs, 0L);
        }
        updateMediaSession();
        publishState();
        refreshNotification(isActuallyPlaying());
    }

    private void setPlaybackSpeed(float speed) {
        float safeSpeed = Math.max(0.5f, Math.min(2.0f, speed));
        if (currentTrack == null) {
            currentTrack = store.currentTrack();
        }
        if (currentTrack != null) {
            currentTrack.playbackSpeed = safeSpeed;
            currentTrack.updatedAt = System.currentTimeMillis();
            store.upsert(currentTrack);
        }
        if (isActuallyPlaying()) {
            applyPlaybackSpeed(safeSpeed);
        }
        publishState();
    }

    private void applyPlaybackSpeed(float speed) {
        if (player == null || !prepared) {
            return;
        }
        player.setPlaybackParameters(new PlaybackParameters(Math.max(0.5f, Math.min(2.0f, speed))));
    }

    private void saveProgress(boolean countListeningDelta) {
        if (currentTrack == null || player == null || !prepared) {
            return;
        }
        long deltaMs = 0L;
        if (countListeningDelta && lastListenClockMs > 0L) {
            long now = android.os.SystemClock.elapsedRealtime();
            deltaMs = Math.max(0L, now - lastListenClockMs);
            lastListenClockMs = now;
        }
        currentTrack = store.updatePlayback(currentTrack.id, getPositionMs(), getDurationMs(), deltaMs);
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.release();
        }
        player = null;
        prepared = false;
        pendingPlay = false;
        lastListenClockMs = 0L;
    }

    private boolean isActuallyPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    private long getPositionMs() {
        if (player != null && prepared) {
            return Math.max(0L, player.getCurrentPosition());
        }
        return currentTrack == null ? 0L : currentTrack.positionMs;
    }

    private long getDurationMs() {
        if (player != null && prepared) {
            return Math.max(0L, player.getDuration());
        }
        return currentTrack == null ? 0L : currentTrack.durationMs;
    }

    private void publishState() {
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        Track track = currentTrack == null ? store.currentTrack() : currentTrack;
        if (track != null) {
            state.putExtra(EXTRA_TRACK_ID, track.id);
            state.putExtra(EXTRA_TRACK_NAME, track.name);
            state.putExtra(EXTRA_FILE_LISTENED_MS, track.listenedMs);
            state.putExtra(EXTRA_PLAYBACK_SPEED, track.playbackSpeed);
        }

        long positionMs = getPositionMs();
        long durationMs = getDurationMs();
        if ((player == null || !prepared) && track != null) {
            positionMs = track.positionMs;
            durationMs = track.durationMs;
        }

        state.putExtra(EXTRA_POSITION_MS, positionMs);
        state.putExtra(EXTRA_DURATION_MS, durationMs);
        state.putExtra(EXTRA_IS_PLAYING, isActuallyPlaying());
        state.putExtra(EXTRA_TOTAL_LISTENED_MS, store.getTotalListenedMs());
        if (!lastError.isEmpty()) {
            state.putExtra(EXTRA_ERROR, lastError);
            lastError = "";
        }
        sendBroadcast(state);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "MovieAudioListener");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                playCurrent();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo(pos);
            }

            @Override
            public void onFastForward() {
                seekBy(SEEK_SMALL_STEP_MS);
            }

            @Override
            public void onRewind() {
                seekBy(-SEEK_SMALL_STEP_MS);
            }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) {
            return;
        }
        Track track = currentTrack == null ? store.currentTrack() : currentTrack;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (track != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, track.name);
        }
        metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, getDurationMs());
        mediaSession.setMetadata(metadata.build());

        int state = isActuallyPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_REWIND;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(actions)
            .setState(state, getPositionMs(), isActuallyPlaying() && track != null ? track.playbackSpeed : 0f)
            .build());
    }

    private void refreshNotification(boolean foreground) {
        Notification notification = buildNotification();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (foreground) {
                if (!isForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                    isForeground = true;
                } else if (manager != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                }
            } else {
                if (isForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(Service.STOP_FOREGROUND_DETACH);
                    } else {
                        stopForeground(false);
                    }
                    isForeground = false;
                }
                if (manager != null && currentTrack != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                }
            }
        } catch (SecurityException ignored) {
        }
    }

    private Notification buildNotification() {
        boolean playing = isActuallyPlaying();
        Track track = currentTrack == null ? store.currentTrack() : currentTrack;
        String title = track == null ? "英语电影听力" : track.name;
        String text = TimeFormat.clock(getPositionMs()) + " / " + TimeFormat.clock(getDurationMs());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activityPendingIntent())
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "-30", servicePendingIntent(ACTION_SEEK_RELATIVE, -SEEK_LARGE_STEP_MS, 1))
            .addAction(android.R.drawable.ic_media_rew, "-10", servicePendingIntent(ACTION_SEEK_RELATIVE, -SEEK_SMALL_STEP_MS, 2))
            .addAction(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放",
                servicePendingIntent(playing ? ACTION_PAUSE : ACTION_PLAY, 0L, 3)
            )
            .addAction(android.R.drawable.ic_media_ff, "+10", servicePendingIntent(ACTION_SEEK_RELATIVE, SEEK_SMALL_STEP_MS, 4))
            .addAction(android.R.drawable.ic_media_ff, "+30", servicePendingIntent(ACTION_SEEK_RELATIVE, SEEK_LARGE_STEP_MS, 5));

        return builder.build();
    }

    private PendingIntent activityPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags());
    }

    private PendingIntent servicePendingIntent(String action, long seekDeltaMs, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        if (ACTION_SEEK_RELATIVE.equals(action)) {
            intent.putExtra(EXTRA_SEEK_DELTA_MS, seekDeltaMs);
        }
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return true;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(this)
                    .build();
            }
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "播放控制",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("英语电影听力的后台播放控制");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
