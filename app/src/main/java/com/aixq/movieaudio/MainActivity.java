package com.aixq.movieaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1001;
    private static final int REQUEST_SUBTITLE = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final int REQUEST_BACKUP = 1004;
    private static final int REQUEST_RESTORE = 1005;
    private static final long UI_TICK_MS = 200L;
    private static final long DEFAULT_SUBTITLE_OFFSET_MS = 0L;
    private static final int SUBTITLE_VISIBLE_RANGE = 3;
    private static final int COLOR_BG = 0xFF050504;
    private static final int COLOR_SCREEN = 0xFF0B0B09;
    private static final int COLOR_SURFACE = 0xFF151410;
    private static final int COLOR_SURFACE_STRONG = 0xFF1D1A15;
    private static final int COLOR_SURFACE_SOFT = 0xFF11100D;
    private static final int COLOR_LINE = 0xFF2B2821;
    private static final int COLOR_LINE_SOFT = 0xFF1F1D18;
    private static final int COLOR_TEXT = 0xFFF1ECE2;
    private static final int COLOR_TEXT_SOFT = 0xFFCFC6B8;
    private static final int COLOR_MUTED = 0xFF8F887B;
    private static final int COLOR_FAINT = 0xFF665F53;
    private static final int COLOR_GHOST = 0xFF474138;
    private static final int COLOR_ACCENT = 0xFFD2AD67;
    private static final int COLOR_ACCENT_2 = 0xFF9EB39A;
    private static final int COLOR_ACCENT_SOFT = 0x21D2AD67;
    private static final int COLOR_DANGER = 0xFFD78380;

    private TrackStore store;
    private ArrayList<Track> tracks = new ArrayList<>();
    private ArrayList<SubtitleCue> subtitleCues = new ArrayList<>();
    private final ArrayList<Button> speedButtons = new ArrayList<>();
    private int lastSubtitleIndex = -1;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying) {
                return;
            }
            updatePositionViews(estimatedPositionMs());
            uiHandler.postDelayed(this, UI_TICK_MS);
        }
    };

    private String currentTrackId = "";
    private boolean isPlaying;
    private boolean userSeeking;
    private long lastPositionMs;
    private long lastDurationMs;
    private long lastStateRealtimeMs;

    private TextView currentTitle;
    private TextView fileListenTime;
    private TextView totalListenTime;
    private TextView currentTime;
    private TextView durationTime;
    private TextView subtitleStatus;
    private TextView subtitleOffsetValue;
    private TextView speedValue;
    private ScrollView subtitleScrollView;
    private LinearLayout subtitleList;
    private TextView libraryCount;
    private LinearLayout libraryList;
    private SeekBar progress;
    private PlayPauseButton playPause;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlaybackService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            String nextTrackId = intent.getStringExtra(PlaybackService.EXTRA_TRACK_ID);
            if (nextTrackId != null && !nextTrackId.equals(currentTrackId)) {
                currentTrackId = nextTrackId;
                parseCurrentSubtitle();
                renderLibrary();
            }

            isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false);
            lastPositionMs = intent.getLongExtra(PlaybackService.EXTRA_POSITION_MS, lastPositionMs);
            lastDurationMs = intent.getLongExtra(PlaybackService.EXTRA_DURATION_MS, lastDurationMs);
            lastStateRealtimeMs = SystemClock.elapsedRealtime();

            String trackName = intent.getStringExtra(PlaybackService.EXTRA_TRACK_NAME);
            long fileListenedMs = intent.getLongExtra(PlaybackService.EXTRA_FILE_LISTENED_MS, currentTrackListenedMs());
            long totalListenedMs = intent.getLongExtra(PlaybackService.EXTRA_TOTAL_LISTENED_MS, store.getTotalListenedMs());
            updatePlaybackViews(trackName, fileListenedMs, totalListenedMs);
            if (isPlaying) {
                startUiTicker();
            } else {
                stopUiTicker();
            }

            String error = intent.getStringExtra(PlaybackService.EXTRA_ERROR);
            if (error != null && !error.isEmpty()) {
                toast(error);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TrackStore(this);
        buildUi();
        requestNotificationPermission();
        loadLocalState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PlaybackService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        sendService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_QUERY), false);
    }

    @Override
    protected void onStop() {
        stopUiTicker();
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_AUDIO) {
            importAudioFiles(data);
        } else if (requestCode == REQUEST_SUBTITLE) {
            importSubtitle(data);
        } else if (requestCode == REQUEST_BACKUP) {
            exportBackup(data);
        } else if (requestCode == REQUEST_RESTORE) {
            restoreBackup(data);
        }
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(COLOR_SCREEN);
            getWindow().setNavigationBarColor(COLOR_SCREEN);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_SCREEN);
        scrollView.setFitsSystemWindows(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(15), dp(18), dp(15), dp(28));
        scrollView.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout stats = row();
        stats.setPadding(0, 0, 0, dp(6));
        fileListenTime = text("00:00", 17, COLOR_TEXT, Typeface.BOLD);
        totalListenTime = text("00:00", 17, COLOR_TEXT, Typeface.BOLD);
        stats.addView(metricPanel("当前文件", fileListenTime), weightParams());
        stats.addView(metricPanel("总收听", totalListenTime), weightParams());
        page.addView(stats);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(13), 0, dp(8));
        View signal = new View(this);
        signal.setBackground(circleBackground(COLOR_ACCENT_2));
        LinearLayout.LayoutParams signalParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        signalParams.setMargins(0, 0, dp(9), 0);
        titleRow.addView(signal, signalParams);
        currentTitle = text("还没有选择 MP3", 17, COLOR_TEXT, Typeface.BOLD);
        currentTitle.setSingleLine(true);
        currentTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(currentTitle, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        page.addView(titleRow);

        progress = new SeekBar(this);
        progress.setMax(100);
        progress.setProgress(0);
        styleSeekBar(progress);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    currentTime.setText(TimeFormat.clock(value * 1000L));
                    updateSubtitle(value * 1000L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                Intent intent = new Intent(MainActivity.this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_SEEK_TO)
                    .putExtra(PlaybackService.EXTRA_POSITION_MS, seekBar.getProgress() * 1000L);
                sendService(intent, false);
            }
        });
        page.addView(progress);

        LinearLayout timeRow = row();
        timeRow.setPadding(0, 0, 0, dp(4));
        currentTime = text("00:00", 12, COLOR_MUTED, Typeface.NORMAL);
        durationTime = text("00:00", 12, COLOR_MUTED, Typeface.NORMAL);
        durationTime.setGravity(Gravity.END);
        timeRow.addView(currentTime, weightParams());
        timeRow.addView(durationTime, weightParams());
        page.addView(timeRow);

        LinearLayout controls = row();
        controls.setPadding(0, dp(6), 0, dp(12));
        Button rewindLarge = button("-30s");
        Button rewindSmall = button("-10s");
        playPause = new PlayPauseButton(this);
        playPause.setContentDescription("播放");
        Button forwardSmall = button("+10s");
        Button forwardLarge = button("+30s");
        rewindLarge.setTextSize(12);
        rewindSmall.setTextSize(12);
        forwardSmall.setTextSize(12);
        forwardLarge.setTextSize(12);
        rewindLarge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                seekRelative(-30_000L);
            }
        });
        rewindSmall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                seekRelative(-10_000L);
            }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlayback();
            }
        });
        forwardSmall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                seekRelative(10_000L);
            }
        });
        forwardLarge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                seekRelative(30_000L);
            }
        });
        controls.addView(rewindLarge, controlButtonParams());
        controls.addView(rewindSmall, controlButtonParams());
        controls.addView(playPause, playButtonParams());
        controls.addView(forwardSmall, controlButtonParams());
        controls.addView(forwardLarge, controlButtonParams());
        page.addView(controls);

        LinearLayout reader = new LinearLayout(this);
        reader.setOrientation(LinearLayout.VERTICAL);
        reader.setPadding(0, dp(14), 0, dp(14));
        reader.setBackground(sectionBackground());
        subtitleStatus = text("当前文件未导入字幕", 12, COLOR_MUTED, Typeface.NORMAL);
        subtitleStatus.setGravity(Gravity.CENTER);
        subtitleStatus.setSingleLine(true);
        subtitleStatus.setEllipsize(TextUtils.TruncateAt.END);
        subtitleStatus.setPadding(dp(10), 0, dp(10), dp(10));
        reader.addView(subtitleStatus);

        subtitleScrollView = new ScrollView(this);
        subtitleScrollView.setFillViewport(true);
        subtitleScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        subtitleScrollView.setPadding(0, 0, 0, 0);
        subtitleList = new LinearLayout(this);
        subtitleList.setOrientation(LinearLayout.VERTICAL);
        subtitleList.setGravity(Gravity.CENTER_VERTICAL);
        subtitleList.setMinimumHeight(dp(286));
        subtitleScrollView.addView(subtitleList, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        reader.addView(subtitleScrollView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(300)
        ));
        page.addView(reader, fullWidthMargins(0, dp(8), 0, dp(16)));

        LinearLayout speedPanel = panel();
        LinearLayout speedHead = headRow();
        TextView speedTitle = text("倍速", 12, COLOR_TEXT_SOFT, Typeface.BOLD);
        speedValue = text("1.00x", 12, COLOR_ACCENT, Typeface.NORMAL);
        speedValue.setGravity(Gravity.END);
        speedHead.addView(speedTitle, weightParams());
        speedHead.addView(speedValue, weightParams());
        speedPanel.addView(speedHead);
        LinearLayout speedRow = row();
        speedRow.setPadding(0, 0, 0, 0);
        speedButtons.clear();
        float[] speeds = new float[]{0.75f, 0.90f, 1.0f, 1.10f, 1.25f};
        for (final float speed : speeds) {
            Button speedButton = chip(String.format(Locale.US, "%.2fx", speed));
            speedButton.setTag(Float.valueOf(speed));
            speedButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    setPlaybackSpeed(speed);
                }
            });
            speedButtons.add(speedButton);
            speedRow.addView(speedButton, weightParams());
        }
        speedPanel.addView(speedRow);
        page.addView(speedPanel, fullWidthMargins(0, 0, 0, dp(10)));

        LinearLayout offsetPanel = panel();
        LinearLayout offsetHead = headRow();
        TextView offsetTitle = text("字幕偏移", 12, COLOR_TEXT_SOFT, Typeface.BOLD);
        subtitleOffsetValue = text("提前 0.9s", 12, COLOR_ACCENT, Typeface.NORMAL);
        subtitleOffsetValue.setGravity(Gravity.END);
        offsetHead.addView(offsetTitle, weightParams());
        offsetHead.addView(subtitleOffsetValue, weightParams());
        offsetPanel.addView(offsetHead);
        LinearLayout offsetRow = row();
        offsetRow.setPadding(0, 0, 0, 0);
        Button offsetBackLarge = chip("-0.5s");
        Button offsetBackSmall = chip("-0.1s");
        Button offsetReset = chip("0");
        Button offsetAheadSmall = chip("+0.1s");
        Button offsetAheadLarge = chip("+0.5s");
        offsetBackLarge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adjustSubtitleOffset(-500L);
            }
        });
        offsetBackSmall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adjustSubtitleOffset(-100L);
            }
        });
        offsetReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSubtitleOffset(DEFAULT_SUBTITLE_OFFSET_MS);
            }
        });
        offsetAheadSmall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adjustSubtitleOffset(100L);
            }
        });
        offsetAheadLarge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adjustSubtitleOffset(500L);
            }
        });
        offsetRow.addView(offsetBackLarge, weightParams());
        offsetRow.addView(offsetBackSmall, weightParams());
        offsetRow.addView(offsetReset, weightParams());
        offsetRow.addView(offsetAheadSmall, weightParams());
        offsetRow.addView(offsetAheadLarge, weightParams());
        offsetPanel.addView(offsetRow);
        page.addView(offsetPanel, fullWidthMargins(0, 0, 0, dp(16)));

        LinearLayout management = panel();
        LinearLayout importRow = row();
        importRow.setPadding(0, 0, 0, dp(6));
        Button importAudio = button("导入 MP3");
        Button importSubtitle = button("导入字幕");
        importAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAudioPicker();
            }
        });
        importSubtitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSubtitlePicker();
            }
        });
        importRow.addView(importAudio, weightParams());
        importRow.addView(importSubtitle, weightParams());
        management.addView(importRow);

        LinearLayout dataRow = row();
        dataRow.setPadding(0, 0, 0, 0);
        Button backupData = button("备份数据");
        Button restoreData = button("恢复数据");
        backupData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openBackupPicker();
            }
        });
        restoreData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmRestore();
            }
        });
        dataRow.addView(backupData, weightParams());
        dataRow.addView(restoreData, weightParams());
        management.addView(dataRow);
        page.addView(management, fullWidthMargins(0, 0, 0, dp(16)));

        libraryCount = text("文件 0", 13, COLOR_MUTED, Typeface.BOLD);
        libraryCount.setPadding(0, dp(2), 0, dp(10));
        page.addView(libraryCount);

        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        page.addView(libraryList);

        setContentView(scrollView);
    }

    private void loadLocalState() {
        tracks = store.loadTracks();
        currentTrackId = store.getCurrentTrackId();
        if ((currentTrackId == null || currentTrackId.isEmpty()) && !tracks.isEmpty()) {
            currentTrackId = tracks.get(0).id;
            store.setCurrentTrackId(currentTrackId);
        }
        Track track = currentTrack();
        lastPositionMs = track == null ? 0L : track.positionMs;
        lastDurationMs = track == null ? 0L : track.durationMs;
        lastStateRealtimeMs = SystemClock.elapsedRealtime();
        parseCurrentSubtitle();
        renderLibrary();
        updatePlaybackViews(track == null ? null : track.name, currentTrackListenedMs(), store.getTotalListenedMs());
    }

    private void renderLibrary() {
        tracks = store.loadTracks();
        libraryCount.setText("文件 " + tracks.size());
        libraryList.removeAllViews();

        if (tracks.isEmpty()) {
            TextView empty = text("还没有导入 MP3。", 15, COLOR_MUTED, Typeface.NORMAL);
            empty.setPadding(0, dp(8), 0, dp(8));
            libraryList.addView(empty);
            return;
        }

        for (final Track track : tracks) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(13), dp(13), dp(13), dp(13));
            item.setBackground(cardBackground(track.id.equals(currentTrackId)));

            TextView name = text(track.name, 14, COLOR_TEXT, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView meta = text(
                "进度 " + TimeFormat.clock(track.positionMs) + " / " + TimeFormat.clock(track.durationMs)
                    + "    收听 " + TimeFormat.clock(track.listenedMs),
                13,
                COLOR_MUTED,
                Typeface.NORMAL
            );
            TextView subtitle = text(
                track.subtitleName == null || track.subtitleName.isEmpty() ? "未导入字幕" : "字幕 " + track.subtitleName,
                13,
                COLOR_MUTED,
                Typeface.NORMAL
            );
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(name);
            item.addView(meta);
            item.addView(subtitle);

            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    currentTrackId = track.id;
                    store.setCurrentTrackId(track.id);
                    parseCurrentSubtitle();
                    renderLibrary();
                    loadTrack(track.id, true);
                }
            });

            LinearLayout actions = row();
            if (track.subtitleName != null && !track.subtitleName.isEmpty()) {
                Button removeSub = button("移除字幕");
                removeSub.setTextSize(12);
                removeSub.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        track.subtitleUri = "";
                        track.subtitleName = "";
                        track.subtitleOffsetMs = DEFAULT_SUBTITLE_OFFSET_MS;
                        track.updatedAt = System.currentTimeMillis();
                        store.upsert(track);
                        if (currentTrackId.equals(track.id)) {
                            subtitleCues.clear();
                            lastSubtitleIndex = -1;
                            updateSubtitle(lastPositionMs);
                        }
                        renderLibrary();
                        toast("字幕已移除");
                    }
                });
                actions.addView(removeSub, weightParams());
            }
            Button delete = button("删除");
            delete.setTextColor(COLOR_DANGER);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmDelete(track);
                }
            });
            actions.addView(delete, weightParams());
            item.addView(actions);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(10));
            libraryList.addView(item, params);
        }
    }

    private void updatePlaybackViews(String trackName, long fileListenedMs, long totalListenedMs) {
        Track track = currentTrack();
        String title = trackName == null || trackName.isEmpty()
            ? (track == null ? "还没有选择 MP3" : track.name)
            : trackName;
        currentTitle.setText(title);
        fileListenTime.setText(TimeFormat.clock(fileListenedMs));
        totalListenTime.setText(TimeFormat.clock(totalListenedMs));
        playPause.setPlaying(isPlaying);
        currentTime.setText(TimeFormat.clock(lastPositionMs));
        durationTime.setText(TimeFormat.clock(lastDurationMs));
        updateSpeedView();
        updateSubtitleOffsetView();

        updatePositionViews(estimatedPositionMs());
    }

    private void updateSubtitle(long positionMs) {
        Track track = currentTrack();

        if (track == null || track.subtitleName == null || track.subtitleName.isEmpty()) {
            subtitleStatus.setText("当前文件未导入字幕");
            subtitleList.removeAllViews();
            lastSubtitleIndex = -1;
            return;
        }

        subtitleStatus.setText("字幕 " + track.subtitleName);

        if (subtitleCues.isEmpty()) {
            subtitleList.removeAllViews();
            lastSubtitleIndex = -1;
            return;
        }

        long subtitlePositionMs = Math.max(0L, positionMs + currentSubtitleOffsetMs());
        int currentIndex = SubtitleParser.findCurrentIndex(subtitleCues, subtitlePositionMs);
        if (currentIndex < 0) {
            for (int i = 0; i < subtitleCues.size(); i++) {
                if (subtitleCues.get(i).startMs > subtitlePositionMs) {
                    currentIndex = Math.max(0, i - 1);
                    break;
                }
            }
            if (currentIndex < 0) {
                currentIndex = subtitleCues.size() - 1;
            }
        }

        if (currentIndex == lastSubtitleIndex && subtitleList.getChildCount() > 0) {
            return;
        }
        lastSubtitleIndex = currentIndex;

        int start = Math.max(0, currentIndex - SUBTITLE_VISIBLE_RANGE);
        int end = Math.min(subtitleCues.size() - 1, currentIndex + SUBTITLE_VISIBLE_RANGE);

        subtitleList.removeAllViews();

        for (int i = start; i <= end; i++) {
            final SubtitleCue cue = subtitleCues.get(i);
            int distance = Math.abs(i - currentIndex);

            int textSize;
            int textColor;
            int typeface;
            switch (distance) {
                case 0:
                    textSize = 22;
                    textColor = COLOR_TEXT;
                    typeface = Typeface.BOLD;
                    break;
                case 1:
                    textSize = 15;
                    textColor = COLOR_MUTED;
                    typeface = Typeface.NORMAL;
                    break;
                case 2:
                    textSize = 13;
                    textColor = COLOR_GHOST;
                    typeface = Typeface.NORMAL;
                    break;
                default:
                    textSize = 12;
                    textColor = COLOR_FAINT;
                    typeface = Typeface.NORMAL;
                    break;
            }

            TextView item = text(cue.text, textSize, textColor, typeface);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(12), distance == 0 ? dp(13) : dp(7), dp(12), distance == 0 ? dp(13) : dp(7));
            item.setLineSpacing(dp(2), 1.0f);
            if (distance == 0) {
                item.setPadding(dp(12), dp(17), dp(12), dp(17));
                item.setBackground(currentCueBackground());
                item.setShadowLayer(dp(10), 0, 0, 0x33F1ECE2);
                item.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
            item.setClickable(true);
            final SubtitleCue clickCue = cue;
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seekToSubtitle(clickCue);
                }
            });
            subtitleList.addView(item);
        }

        final int scrollIndex = currentIndex;
        subtitleScrollView.post(new Runnable() {
            @Override
            public void run() {
                View target = subtitleList.getChildAt(scrollIndex - start);
                if (target != null) {
                    int scrollY = target.getTop()
                        - (subtitleScrollView.getHeight() / 2)
                        + (target.getHeight() / 2);
                    subtitleScrollView.smoothScrollTo(0, Math.max(0, scrollY));
                }
            }
        });
    }

    private void seekToSubtitle(SubtitleCue cue) {
        long targetMs = Math.max(0L, cue.startMs - currentSubtitleOffsetMs());
        lastPositionMs = targetMs;
        lastStateRealtimeMs = SystemClock.elapsedRealtime();
        updatePositionViews(targetMs);
        Intent intent = new Intent(this, PlaybackService.class)
            .setAction(PlaybackService.ACTION_SEEK_TO)
            .putExtra(PlaybackService.EXTRA_POSITION_MS, targetMs);
        sendService(intent, false);
    }

    private void updatePositionViews(long positionMs) {
        currentTime.setText(TimeFormat.clock(positionMs));
        durationTime.setText(TimeFormat.clock(lastDurationMs));

        if (!userSeeking) {
            int max = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, lastDurationMs / 1000L));
            int value = (int) Math.max(0L, Math.min(max, positionMs / 1000L));
            progress.setMax(max);
            progress.setProgress(value);
        }
        updateSubtitle(positionMs);
    }

    private void updateSpeedView() {
        if (speedValue == null) {
            return;
        }
        float currentSpeed = currentPlaybackSpeed();
        speedValue.setText(String.format(Locale.US, "%.2fx", currentSpeed));
        for (Button button : speedButtons) {
            Object tag = button.getTag();
            boolean active = tag instanceof Float && Math.abs(((Float) tag).floatValue() - currentSpeed) < 0.01f;
            styleChip(button, active);
        }
    }

    private void updateSubtitleOffsetView() {
        if (subtitleOffsetValue == null) {
            return;
        }
        long offsetMs = currentSubtitleOffsetMs();
        if (offsetMs == 0L) {
            subtitleOffsetValue.setText("同步 0.0s");
            return;
        }
        String direction = offsetMs > 0L ? "提前" : "延后";
        subtitleOffsetValue.setText(String.format(Locale.US, "%s %.1fs", direction, Math.abs(offsetMs) / 1000.0));
    }

    private long currentSubtitleOffsetMs() {
        Track track = currentTrack();
        return track == null ? DEFAULT_SUBTITLE_OFFSET_MS : track.subtitleOffsetMs;
    }

    private float currentPlaybackSpeed() {
        Track track = currentTrack();
        return track == null ? 1.0f : track.playbackSpeed;
    }

    private void adjustSubtitleOffset(long deltaMs) {
        setSubtitleOffset(currentSubtitleOffsetMs() + deltaMs);
    }

    private void setSubtitleOffset(long offsetMs) {
        Track track = currentTrack();
        if (track == null) {
            toast("请先选择一个 MP3");
            return;
        }
        track.subtitleOffsetMs = Math.max(-5000L, Math.min(5000L, offsetMs));
        track.updatedAt = System.currentTimeMillis();
        store.upsert(track);
        tracks = store.loadTracks();
        updateSubtitleOffsetView();
        updateSubtitle(estimatedPositionMs());
    }

    private void setPlaybackSpeed(float speed) {
        Track track = currentTrack();
        if (track == null) {
            toast("请先选择一个 MP3");
            return;
        }
        float safeSpeed = Math.max(0.5f, Math.min(2.0f, speed));
        track.playbackSpeed = safeSpeed;
        track.updatedAt = System.currentTimeMillis();
        store.upsert(track);
        tracks = store.loadTracks();
        updateSpeedView();

        Intent intent = new Intent(this, PlaybackService.class)
            .setAction(PlaybackService.ACTION_SET_SPEED)
            .putExtra(PlaybackService.EXTRA_PLAYBACK_SPEED, safeSpeed);
        sendService(intent, false);
    }

    private long estimatedPositionMs() {
        if (!isPlaying || lastStateRealtimeMs <= 0L) {
            return lastPositionMs;
        }

        long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - lastStateRealtimeMs);
        long estimatedMs = lastPositionMs + Math.round(elapsedMs * currentPlaybackSpeed());
        if (lastDurationMs > 0L) {
            estimatedMs = Math.min(estimatedMs, lastDurationMs);
        }
        return Math.max(0L, estimatedMs);
    }

    private void startUiTicker() {
        uiHandler.removeCallbacks(uiTicker);
        uiHandler.post(uiTicker);
    }

    private void stopUiTicker() {
        uiHandler.removeCallbacks(uiTicker);
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_AUDIO);
    }

    private void openSubtitlePicker() {
        if (currentTrack() == null) {
            toast("请先选择一个 MP3");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "text/plain",
            "text/vtt",
            "application/x-subrip",
            "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_SUBTITLE);
    }

    private void openBackupPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "movie-audio-backup-" + System.currentTimeMillis() + ".json");
        startActivityForResult(intent, REQUEST_BACKUP);
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
            .setTitle("恢复数据")
            .setMessage("恢复会覆盖当前文件记录、进度和收听统计。MP3 和字幕文件本体不会从备份中恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择备份", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int which) {
                    openRestorePicker();
                }
            })
            .show();
    }

    private void openRestorePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_RESTORE);
    }

    private void exportBackup(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            toast("没有选择备份位置");
            return;
        }

        try {
            OutputStream outputStream = getContentResolver().openOutputStream(uri);
            if (outputStream == null) {
                toast("无法写入备份文件");
                return;
            }
            try {
                outputStream.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } finally {
                outputStream.close();
            }
            toast("备份完成");
        } catch (Exception error) {
            toast("备份失败");
        }
    }

    private void restoreBackup(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            toast("没有选择备份文件");
            return;
        }

        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                toast("无法打开备份文件");
                return;
            }

            String raw;
            try {
                raw = readAllText(inputStream);
            } finally {
                inputStream.close();
            }

            sendService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_STOP), false);
            int count = store.importJson(raw);
            loadLocalState();
            toast("已恢复 " + count + " 个文件记录");
        } catch (Exception error) {
            toast("恢复失败，备份文件格式不对");
        }
    }

    private void importAudioFiles(Intent data) {
        ArrayList<Uri> uris = extractUris(data);
        int imported = 0;
        String firstImportedId = "";
        for (Uri uri : uris) {
            takeReadPermission(uri);
            String name = displayName(uri);
            String mimeType = getContentResolver().getType(uri);
            String lowerName = name.toLowerCase(Locale.ROOT);
            if ((mimeType == null || !mimeType.startsWith("audio/")) && !lowerName.endsWith(".mp3")) {
                continue;
            }

            Track existing = findTrackByAudioUri(uri.toString());
            Track track = existing == null ? new Track() : existing;
            long now = System.currentTimeMillis();
            if (track.id.isEmpty()) {
                track.id = "track-" + Long.toHexString(now) + "-" + Integer.toHexString(uri.toString().hashCode());
                track.addedAt = now;
            }
            track.name = name;
            track.audioUri = uri.toString();
            track.updatedAt = now;
            store.upsert(track);
            if (firstImportedId.isEmpty()) {
                firstImportedId = track.id;
            }
            imported++;
        }

        if (imported == 0) {
            toast("没有识别到 MP3 文件");
            return;
        }

        currentTrackId = firstImportedId;
        store.setCurrentTrackId(currentTrackId);
        parseCurrentSubtitle();
        renderLibrary();
        loadTrack(currentTrackId, false);
        toast("已导入 " + imported + " 个文件");
    }

    private void importSubtitle(Intent data) {
        Track track = currentTrack();
        if (track == null) {
            toast("请先选择一个 MP3");
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            toast("没有选择字幕文件");
            return;
        }
        takeReadPermission(uri);

        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                toast("无法打开字幕文件");
                return;
            }
            try {
                ArrayList<SubtitleCue> cues = SubtitleParser.parse(inputStream);
                if (cues.isEmpty()) {
                    toast("没有识别到有效字幕");
                    return;
                }
                track.subtitleUri = uri.toString();
                track.subtitleName = displayName(uri);
                track.subtitleOffsetMs = DEFAULT_SUBTITLE_OFFSET_MS;
                track.updatedAt = System.currentTimeMillis();
                store.upsert(track);
                subtitleCues = cues;
                lastSubtitleIndex = -1;
                renderLibrary();
                updateSubtitle(lastPositionMs);
                toast("已导入 " + cues.size() + " 条字幕");
            } finally {
                inputStream.close();
            }
        } catch (Exception error) {
            toast("字幕解析失败");
        }
    }

    private void parseCurrentSubtitle() {
        subtitleCues.clear();
        lastSubtitleIndex = -1;
        Track track = currentTrack();
        if (track == null || track.subtitleUri == null || track.subtitleUri.isEmpty()) {
            updateSubtitle(lastPositionMs);
            return;
        }

        try {
            InputStream inputStream = getContentResolver().openInputStream(Uri.parse(track.subtitleUri));
            if (inputStream == null) {
                return;
            }
            try {
                subtitleCues = SubtitleParser.parse(inputStream);
            } finally {
                inputStream.close();
            }
        } catch (Exception ignored) {
            subtitleCues.clear();
        }
        updateSubtitle(lastPositionMs);
    }

    private void togglePlayback() {
        Track track = currentTrack();
        if (track == null) {
            toast("请先导入并选择 MP3");
            return;
        }
        if (isPlaying) {
            sendService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PAUSE), false);
        } else {
            sendService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY), false);
        }
    }

    private void loadTrack(String trackId, boolean autoplay) {
        Intent intent = new Intent(this, PlaybackService.class)
            .setAction(PlaybackService.ACTION_LOAD)
            .putExtra(PlaybackService.EXTRA_TRACK_ID, trackId)
            .putExtra(PlaybackService.EXTRA_AUTOPLAY, autoplay);
        sendService(intent, autoplay);
    }

    private void seekRelative(long deltaMs) {
        Intent intent = new Intent(this, PlaybackService.class)
            .setAction(PlaybackService.ACTION_SEEK_RELATIVE)
            .putExtra(PlaybackService.EXTRA_SEEK_DELTA_MS, deltaMs);
        sendService(intent, false);
    }

    private void sendService(Intent intent, boolean foregroundExpected) {
        if (foregroundExpected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void confirmDelete(final Track track) {
        new AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("删除记录不会删除原始 MP3 文件。\n\n" + track.name)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int which) {
                    if (track.id.equals(currentTrackId)) {
                        sendService(new Intent(MainActivity.this, PlaybackService.class).setAction(PlaybackService.ACTION_STOP), false);
                    }
                    store.delete(track.id);
                    loadLocalState();
                }
            })
            .show();
    }

    private ArrayList<Uri> extractUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void takeReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant temporary access only; playback can still work for the current session.
        }
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to URI fallback.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isEmpty() ? "未命名文件" : fallback;
    }

    private String readAllText(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private Track currentTrack() {
        if (currentTrackId == null || currentTrackId.isEmpty()) {
            return null;
        }
        for (Track track : tracks) {
            if (track.id.equals(currentTrackId)) {
                return track;
            }
        }
        return store.find(currentTrackId);
    }

    private Track findTrackByAudioUri(String audioUri) {
        ArrayList<Track> localTracks = store.loadTracks();
        for (Track track : localTracks) {
            if (track.audioUri.equals(audioUri)) {
                return track;
            }
        }
        return null;
    }

    private long currentTrackListenedMs() {
        Track track = currentTrack();
        return track == null ? 0L : track.listenedMs;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private LinearLayout headRow() {
        LinearLayout row = row();
        row.setPadding(0, 0, 0, dp(9));
        return row;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(11), dp(11), dp(11), dp(11));
        panel.setBackground(roundedBackground(0x8F151410, COLOR_LINE_SOFT, 16));
        return panel;
    }

    private LinearLayout metricPanel(String label, TextView value) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(13), dp(12), dp(13), dp(12));
        panel.setBackground(roundedBackground(0xC7151410, COLOR_LINE_SOFT, 15));

        TextView labelView = text(label, 11, COLOR_FAINT, Typeface.NORMAL);
        value.setPadding(0, dp(4), 0, 0);
        panel.addView(labelView);
        panel.addView(value);
        return panel;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams controlButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams playButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(64));
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams fullWidthMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(appTypeface(style));
        textView.setLineSpacing(dp(2), 1.0f);
        return textView;
    }

    private Typeface appTypeface(int style) {
        String[] candidates = new String[]{
            "/system/fonts/MiSansVF.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/Roboto-Regular.ttf"
        };
        for (String path : candidates) {
            try {
                if (new File(path).exists()) {
                    return Typeface.create(Typeface.createFromFile(path), style);
                }
            } catch (RuntimeException ignored) {
                // Try the next system font.
            }
        }
        return Typeface.create("sans-serif", style);
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT_SOFT);
        button.setTextSize(12);
        button.setTypeface(appTypeface(Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(roundedBackground(COLOR_SURFACE, COLOR_LINE, 12));
        return button;
    }

    private Button chip(String value) {
        Button button = button(value);
        button.setMinHeight(dp(36));
        styleChip(button, false);
        return button;
    }

    private void styleChip(Button button, boolean active) {
        button.setTextColor(active ? COLOR_TEXT : COLOR_MUTED);
        button.setBackground(roundedBackground(
            active ? COLOR_ACCENT_SOFT : COLOR_SURFACE_SOFT,
            active ? 0x85D2AD67 : COLOR_LINE,
            12
        ));
    }

    private void styleSeekBar(SeekBar seekBar) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF242119));
            seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT_2));
        }
    }

    private GradientDrawable cardBackground(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(active ? 0x17D2AD67 : 0xBD1D1A15);
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), active ? 0x61D2AD67 : COLOR_LINE);
        return drawable;
    }

    private GradientDrawable currentCueBackground() {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                0x00000000,
                0x16D2AD67,
                0x149EB39A,
                0x16D2AD67,
                0x00000000
            }
        );
        drawable.setCornerRadius(dp(28));
        return drawable;
    }

    private GradientDrawable sectionBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(COLOR_SCREEN);
        drawable.setCornerRadius(dp(2));
        drawable.setStroke(dp(1), COLOR_LINE_SOFT);
        return drawable;
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable roundedBackground(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private final class PlayPauseButton extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path playPath = new Path();
        private boolean playing;

        PlayPauseButton(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setMinimumWidth(dp(64));
            setMinimumHeight(dp(64));
            iconPaint.setColor(0xFF11100C);
            iconPaint.setStyle(Paint.Style.FILL);
        }

        void setPlaying(boolean nextPlaying) {
            playing = nextPlaying;
            setContentDescription(playing ? "暂停" : "播放");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = Math.min(getWidth(), getHeight());
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = size / 2f;

            circlePaint.setShader(new LinearGradient(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius,
                COLOR_ACCENT,
                COLOR_ACCENT_2,
                Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(centerX, centerY, radius, circlePaint);
            circlePaint.setShader(null);

            if (playing) {
                float barWidth = dp(6);
                float barHeight = dp(22);
                float gap = dp(6);
                float top = centerY - barHeight / 2f;
                float left = centerX - gap / 2f - barWidth;
                float right = centerX + gap / 2f;
                canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, dp(3), dp(3), iconPaint);
                canvas.drawRoundRect(right, top, right + barWidth, top + barHeight, dp(3), dp(3), iconPaint);
                return;
            }

            playPath.reset();
            playPath.moveTo(centerX - dp(6), centerY - dp(11));
            playPath.lineTo(centerX - dp(6), centerY + dp(11));
            playPath.lineTo(centerX + dp(12), centerY);
            playPath.close();
            canvas.drawPath(playPath, iconPaint);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
