package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.VideoCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 全屏视频页：Media3 ExoPlayer (TextureView 无感切集预热 + SimpleCache) + 自动隐藏控制层。 */
public class VideoPage extends FrameLayout {
    public interface Listener {
        void onProgress(VideoPage page, long positionMs, long durationMs);
    }

    public interface Actions {
        void onLike();
        void onShare();
        void onProfile();
        void onControls();
        void onPlaybackSettings();
        void onDanmakuSettings();
        void onSeek(int seconds);
        void onPlayPause();
        void onRotate();
        void onToggleFullscreen();
        void onExitToNavigation();
        void onPlaybackError(Aweme a);
        void onVideoPlaying();
        void onChromeHidden();
        void onChromeVisibilityChanged(boolean visible);
    }

    private static final long CHROME_TIMEOUT_MS = 4500L;
    private static final long LONG_LOADING_DELAY_MS = 1500L;

    /** 双解码器预热自适应开关：若老电视仅支持单路硬解导致后台预热失败，自动降级为仅磁盘缓存预载。 */
    private static boolean standbyDecoderAllowed = true;
    private static int standbyDecoderErrors = 0;

    public static boolean canUseStandbyDecoder() {
        return standbyDecoderAllowed;
    }

    public Aweme item;
    private final android.widget.ImageView coverView;
    private final TextureView textureView;
    private final ProgressBar spinner;
    private final FrameLayout centerToggle;
    private final IconView centerToggleIcon;
    private final TextView author;
    private final TextView desc;
    private final TextView meta;
    private final Ui.IconButton diggLabel;
    private final LinearLayout rightRail;
    private final List<View> controlButtons = new ArrayList<>();

    private int rotationDegrees = 0;
    private int videoW = 0, videoH = 0;
    private final TextView time;
    private TextView errorTitle;
    private TextView errorHint;
    private final View bottomGrad;
    private final LinearLayout bottomText;

    private final SeekLine seek;
    private final View errorBox;
    private final DanmakuView danmaku;
    private final List<String> danmakuQueue = new ArrayList<>();
    private int danmakuPos;
    private long danmakuGeneration;

    private ExoPlayer player;
    private List<String> urls = new ArrayList<>();
    private int urlIndex = 0;
    private boolean prepared;
    private boolean renderedFirstFrame;
    private boolean failed;
    private boolean wantPlay;
    private boolean userPaused;
    private boolean standbyMode;
    private boolean detailRefreshed;
    private long itemGeneration;
    private long restorePosition;
    private boolean released;
    private long attemptStarted;
    private long lastTickSec = -1;
    private boolean tickerRunning;
    private boolean chromeVisible = false;
    private boolean spinnerScheduled;
    public boolean liked;

    private Listener listener;
    private Actions actions;
    private Runnable ended;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeTask = () -> {
        if (chromeVisible && !userPaused && wantPlay) hideChrome();
    };
    private final Runnable delayedSpinner = this::showDelayedSpinner;

    private void showCoverMask() {
        coverView.animate().cancel();
        coverView.setAlpha(1f);
        coverView.setVisibility(VISIBLE);
    }

    private void hideCoverMask() {
        coverView.animate().cancel();
        coverView.setVisibility(GONE);
    }

    private void scheduleLoadingSpinner() {
        if (released || standbyMode || !wantPlay || failed
                || spinnerScheduled || spinner.getVisibility() == VISIBLE) return;
        spinnerScheduled = true;
        handler.postDelayed(delayedSpinner, LONG_LOADING_DELAY_MS);
    }

    private void showDelayedSpinner() {
        spinnerScheduled = false;
        if (released || standbyMode || !wantPlay || failed) return;
        if (!renderedFirstFrame || (player != null && player.getPlaybackState() == Player.STATE_BUFFERING)) {
            spinner.setVisibility(VISIBLE);
        }
    }

    private void hideLoadingSpinner() {
        spinnerScheduled = false;
        handler.removeCallbacks(delayedSpinner);
        spinner.setVisibility(GONE);
    }

    private final Runnable startupTimeout = () -> {
        if (player != null && !prepared && !released && !standbyMode) tryNextUrl();
    };
    private final Runnable bufferTimeout = () -> {
        if (player == null || !prepared || !wantPlay || released || standbyMode) return;
        restorePosition = position();
        tryNextUrl();
    };

    public static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f};
    public static final String[] SPEED_LABELS = {"1.0x", "1.25x", "1.5x", "2.0x"};
    public static final int[] ROTATIONS = {0, 90, 180, 270};
    public static final String[] ROTATION_LABELS = {"默认 (0°)", "顺时针 90°", "翻转 180°", "逆时针 270°"};

    public static String rotationLabel(int deg) {
        int norm = ((deg % 360) + 360) % 360;
        for (int i = 0; i < ROTATIONS.length; i++) {
            if (ROTATIONS[i] == norm) return ROTATION_LABELS[i];
        }
        return ROTATION_LABELS[0];
    }

    public static int speedIndex() {
        int v = App.get().store().speedIndex();
        return Math.max(0, Math.min(SPEEDS.length - 1, v));
    }

    public static String speedLabel() {
        return SPEED_LABELS[speedIndex()];
    }

    public VideoPage(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setClipChildren(false);

        textureView = new TextureView(c);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 封面层放在 TextureView 之上：
        // 1) TextureView 始终 VISIBLE + alpha=1，保证后台预热也能正常分配 SurfaceTexture 并渲染首帧；
        // 2) 首帧就绪前由不透明黑底+当前视频封面遮住旧帧，既不黑屏也不闪旧画面；
        // 3) 预热完成触发 onRenderedFirstFrame() 后立刻隐藏封面，切入时 0ms 直出真画面、不闪封面。
        coverView = new android.widget.ImageView(c);
        coverView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        coverView.setBackgroundColor(Color.BLACK);
        coverView.setVisibility(VISIBLE);
        addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        bottomGrad = new View(c);
        bottomGrad.setBackground(Ui.scrimBottom(c));
        LayoutParams bpGrad = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 170));
        bpGrad.gravity = Gravity.BOTTOM;
        addView(bottomGrad, bpGrad);

        danmaku = new DanmakuView(c);
        danmaku.setTopInsetPx(Ui.dp(c, 58));
        addView(danmaku, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(c);
        LayoutParams sp = new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
        sp.gravity = Gravity.CENTER;
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        spinner.setVisibility(GONE);
        addView(spinner, sp);

        // 中间播放/暂停圆形按钮
        centerToggle = new FrameLayout(c);
        centerToggle.setId(View.generateViewId());
        centerToggle.setFocusable(false);
        centerToggle.setClickable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) centerToggle.setDefaultFocusHighlightEnabled(false);
        centerToggle.setContentDescription("播放或暂停");
        centerToggleIcon = new IconView(c, IconView.PAUSE, 28);
        centerToggle.addView(centerToggleIcon, new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER));
        centerToggle.setOnClickListener(v -> {
            if (actions != null) actions.onPlayPause();
            resetChromeTimeout();
        });
        centerToggle.setOnFocusChangeListener((v, has) -> {
            paintCenterToggle();
            if (has) resetChromeTimeout();
        });
        paintCenterToggle();
        addView(centerToggle, new LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72), Gravity.CENTER));

        errorBox = buildError();
        LayoutParams ep = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ep.gravity = Gravity.CENTER;
        errorBox.setVisibility(GONE);
        addView(errorBox, ep);

        bottomText = Ui.column(c);
        bottomText.setClipChildren(false);
        Ui.padding(bottomText, 20, 14, 140, 16);

        desc = Ui.text(c, "", 13.5f, 0xCCFFFFFF, false);
        desc.setMaxLines(1);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0x80000000);
        desc.setIncludeFontPadding(false);
        bottomText.addView(desc, Ui.lp(-1, -2));

        LinearLayout details = Ui.row(c);
        details.setGravity(Gravity.CENTER_VERTICAL);
        author = Ui.text(c, "", 11.5f, 0xB8FFFFFF, false);
        author.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x77000000);
        details.addView(author, Ui.lp(-2, -2));
        meta = Ui.text(c, "", 11f, 0x94FFFFFF, false);
        meta.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x77000000);
        details.addView(meta, Ui.margin(-2, -2, Ui.dp(c, 10), 0, 0, 0));
        time = Ui.text(c, "00:00 / 00:00", 11f, 0xA6FFFFFF, false);
        time.setFontFeatureSettings("tnum");
        time.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x77000000);
        details.addView(time, Ui.margin(-2, -2, Ui.dp(c, 12), 0, 0, 0));
        bottomText.addView(details, Ui.margin(-1, -2, 0, Ui.dp(c, 5), 0, 0));
        addView(bottomText, new LayoutParams(-1, -2, Gravity.BOTTOM));

        rightRail = Ui.column(c);
        rightRail.setGravity(Gravity.CENTER_HORIZONTAL);
        rightRail.setClipChildren(false);
        rightRail.setClipToPadding(false);
        actionButton(rightRail, "作者", "作者主页", IconView.USER, false, () -> actions.onProfile());
        diggLabel = actionButton(rightRail, "喜欢", "点赞", IconView.HEART, true, () -> actions.onLike());
        actionButton(rightRail, "分享", "扫码分享", IconView.SHARE, false, () -> actions.onShare());
        actionButton(rightRail, "旋转", "画面旋转", IconView.REFRESH, false, () -> actions.onRotate());
        actionButton(rightRail, "设置", "播放与弹幕设置", IconView.SLIDERS, false, () -> actions.onPlaybackSettings());
        LayoutParams railLp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        railLp.rightMargin = Ui.dp(c, 26);
        railLp.bottomMargin = Ui.dp(c, 22);
        addView(rightRail, railLp);

        seek = new SeekLine(c);
        seek.setEdgeToEdge(true);
        addView(seek, new LayoutParams(-1, Ui.dp(c, 3), Gravity.BOTTOM));

        applyChromeVisibilityNow(false, false);
    }

    private void paintCenterToggle() {
        Context c = getContext();
        boolean focused = centerToggle.hasFocus();
        boolean showPlay = userPaused || !wantPlay;
        centerToggleIcon.setType(showPlay ? IconView.PLAY : IconView.PAUSE);
        centerToggle.setBackground(focused
                ? Ui.pill(c, 0xFFC6ED92)
                : Ui.roundStroke(c, 0x59000000, 36, 0x40FFFFFF, 1));
        centerToggleIcon.setColor(focused ? 0xFF121F09 : 0xFFFFFFFF);
        centerToggle.setContentDescription(showPlay ? "播放" : "暂停");
    }

    private Ui.IconButton actionButton(LinearLayout col, String label, String description,
                                       int icon, boolean likeStyle, Runnable action) {
        Context context = getContext();
        final Ui.IconButton button = Ui.iconAction(context, icon, label, description, true, likeStyle, false, () -> {
            resetChromeTimeout();
            if (actions != null) action.run();
        });
        button.setOnFocusChangeListener((v, hasFocus) -> {
            button.repaint();
            if (hasFocus) resetChromeTimeout();
        });
        controlButtons.add(button);
        col.addView(button, Ui.margin(-2, -2, 0, Ui.dp(context, 10), 0, 0));
        return button;
    }

    private View buildError() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(getContext(), 32), Ui.dp(getContext(), 24),
                Ui.dp(getContext(), 32), Ui.dp(getContext(), 24));
        col.setBackground(Ui.roundStroke(getContext(), Ui.SURFACE, 18, Ui.SEPARATOR, 1));
        errorTitle = Ui.bold(getContext(), "这段视频暂时播不了", 18, Ui.TEXT);
        errorHint = Ui.text(getContext(), "按 ↑ ↓ 切换视频", 13, Ui.TEXT_3, false);
        errorHint.setPadding(0, Ui.dp(getContext(), 8), 0, Ui.dp(getContext(), 16));
        TextView retry = Ui.text(getContext(), "重新加载", 14, Ui.ACCENT_TEXT, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(Ui.dp(getContext(), 22), Ui.dp(getContext(), 9),
                Ui.dp(getContext(), 22), Ui.dp(getContext(), 9));
        retry.setBackground(Ui.pill(getContext(), Ui.TINT_PRIMARY));
        retry.setClickable(true);
        retry.setFocusable(false);
        retry.setOnClickListener(v -> retryCurrent());
        col.addView(errorTitle);
        col.addView(errorHint);
        col.addView(retry);
        return col;
    }

    private void retryCurrent() {
        if (item == null) return;
        errorBox.setVisibility(GONE);
        hideLoadingSpinner();
        detailRefreshed = false;
        standbyMode = false;
        urlIndex = 0;
        if (wantPlay) scheduleLoadingSpinner();
        openUrl();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setActions(Actions a) {
        actions = a;
    }

    public void setOnEnded(Runnable r) {
        ended = r;
    }

    /** 倍速在播放中即时生效，暂停时等下一次起播应用。 */
    public void applySpeedSetting() {
        applySpeed();
    }

    private void applySpeed() {
        if (player == null) return;
        try {
            player.setPlaybackSpeed(SPEEDS[speedIndex()]);
        } catch (Exception ignored) {}
    }

    public synchronized void setAweme(Aweme a, boolean autoPlay) {
        if (a != null) App.get().api().applyKnownLike(a);
        boolean sameItem = item != null && a != null && item.id.equals(a.id);
        item = a;
        itemGeneration++;
        detailRefreshed = false;
        if (!sameItem) rotationDegrees = 0;
        videoW = a == null ? 0 : a.videoWidth;
        videoH = a == null ? 0 : a.videoHeight;
        wantPlay = autoPlay;
        userPaused = false;
        standbyMode = false;
        lastTickSec = -1;
        restorePosition = 0;
        liked = a != null && a.userDigged == 1;
        prepared = false;
        renderedFirstFrame = false;
        failed = false;
        urlIndex = 0;
        errorBox.setVisibility(GONE);
        handler.removeCallbacks(hideChromeTask);
        applyChromeVisibilityNow(false, false);
        hideLoadingSpinner();
        fitMedia(videoW, videoH);
        desc.setText(a == null ? "" : a.desc);
        author.setText(a == null ? "" : "@" + (a.authorName == null ? "抖音作者" : a.authorName));
        meta.setText(a == null ? "" : "赞 " + Ui.count(a.diggCount) + " · 评论 " + Ui.count(a.commentCount)
                + " · 分享 " + Ui.count(a.shareCount));
        setDigg(a == null ? 0 : a.diggCount, liked);
        seek.setProgress(0);
        time.setText("00:00 / " + fmt(a == null ? 0 : a.durationMs));
        danmakuGeneration++;
        danmakuQueue.clear();
        danmakuPos = 0;
        danmaku.clear();
        danmaku.applySettings();
        danmaku.setPaused(!autoPlay);
        handler.removeCallbacks(danmakuPump);
        releasePlayer();
        coverView.setTag(null);
        coverView.setImageDrawable(null);
        showCoverMask();
        if (a != null && a.cover != null && !a.cover.isEmpty()) {
            ink.pengfei.douyin.util.Images.load(a.cover, coverView, 1280, 0);
        }
        String q = a == null ? "720p" : App.get().store().quality();
        urls = a != null ? a.playCandidates(q) : new ArrayList<>();
        paintCenterToggle();
        if (!autoPlay) return;
        scheduleLoadingSpinner();
        if (!urls.isEmpty()) {
            openUrl();
        } else if (a != null) {
            refreshDetailOrFail();
        }
    }

    /** 后台预热下一条视频的首帧与缓冲，切入时直接起播实现无感切换。 */
    public synchronized void prepareStandby() {
        if (released || !standbyDecoderAllowed || item == null || player != null || failed) return;
        if (urls.isEmpty()) urls = item.playCandidates(App.get().store().quality());
        if (urls.isEmpty()) return;
        standbyMode = true;
        wantPlay = false;
        urlIndex = 0;
        openUrl();
    }

    public void setStandby(boolean value) {
        if (value) {
            standbyMode = false;
            hideLoadingSpinner();
            releasePlayer();
        }
    }

    private synchronized void openUrl() {
        if (item == null || urlIndex >= urls.size() || released) return;
        final String url = urls.get(urlIndex);
        final long gen = itemGeneration;
        VideoCache.cancelPreloadFor(url);
        releasePlayer();
        attemptStarted = SystemClock.elapsedRealtime();

        try {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(getContext())
                    .setEnableDecoderFallback(true);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(12000, 40000, 200, 1000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build();

            final ExoPlayer exo = new ExoPlayer.Builder(getContext(), renderers)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(VideoCache.dataSourceFactory(getContext()))
                            .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0)))
                    .setLoadControl(loadControl)
                    .setAudioAttributes(audioAttributes, !standbyMode)
                    .build();
            player = exo;
            textureView.setVisibility(VISIBLE);
            exo.setVideoTextureView(textureView);
            exo.setVolume(standbyMode ? 0f : 1f);
            exo.setRepeatMode(App.get().store().autoNext() ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
            exo.setPlaybackSpeed(SPEEDS[speedIndex()]);
            exo.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (player != exo || released) return;
                    if (playbackState == Player.STATE_BUFFERING && wantPlay && !standbyMode) {
                        scheduleLoadingSpinner();
                        handler.removeCallbacks(bufferTimeout);
                        handler.postDelayed(bufferTimeout, 15000);
                    } else if (playbackState == Player.STATE_READY) {
                        prepared = true;
                        handler.removeCallbacks(startupTimeout);
                        handler.removeCallbacks(bufferTimeout);
                        if (renderedFirstFrame && !standbyMode) hideLoadingSpinner();
                        applyVideoSize(exo.getVideoSize());
                        if (!standbyMode && restorePosition > 0) {
                            long target = restorePosition;
                            restorePosition = 0;
                            exo.seekTo(target);
                        }
                        if (!standbyMode && exo.isPlaying()) startTicker();
                    } else if (playbackState == Player.STATE_ENDED) {
                        stopTicker();
                        if (!standbyMode && wantPlay && App.get().store().autoNext() && ended != null) {
                            ended.run();
                        }
                    }
                }

                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (player != exo || released) return;
                    if (isPlaying && !standbyMode) {
                        if (renderedFirstFrame) hideLoadingSpinner();
                        startTicker();
                    } else {
                        stopTicker();
                    }
                }

                @Override public void onVideoSizeChanged(VideoSize videoSize) {
                    if (player == exo && !released && videoSize != null) {
                        applyVideoSize(videoSize);
                    }
                }

                @Override public void onRenderedFirstFrame() {
                    if (player != exo || released) return;
                    renderedFirstFrame = true;
                    hideCoverMask();
                    if (standbyMode) {
                        standbyDecoderErrors = 0;
                        return;
                    }
                    hideLoadingSpinner();
                    if (wantPlay && item != null && danmakuQueue.isEmpty() && App.get().store().danmakuOn()) {
                        loadDanmaku(item);
                    }
                    if (actions != null) actions.onVideoPlaying();
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (player != exo || released) return;
                    if (standbyMode) {
                        android.util.Log.w("dytv-player", "Standby Exo error=" + error.getErrorCodeName());
                        standbyMode = false;
                        standbyDecoderErrors++;
                        if (standbyDecoderErrors >= 2) standbyDecoderAllowed = false;
                        handler.post(() -> { if (player == exo) releasePlayer(); });
                        return;
                    }
                    android.util.Log.w("dytv-player", "Exo error=" + error.getErrorCodeName()
                            + " urlIdx=" + urlIndex + " msg=" + error.getMessage());
                    if (prepared) restorePosition = Math.max(0, exo.getCurrentPosition());
                    handler.post(() -> {
                        if (!released && itemGeneration == gen && player == exo) tryNextUrl();
                    });
                }
            });

            exo.setMediaItem(MediaItem.fromUri(url));
            exo.setPlayWhenReady(!standbyMode && wantPlay);
            exo.prepare();
            handler.removeCallbacks(startupTimeout);
            if (!standbyMode) handler.postDelayed(startupTimeout, 5500);
        } catch (Throwable e) {
            android.util.Log.w("dytv-player", "openUrl failed: " + e);
            if (standbyMode) {
                standbyMode = false;
                standbyDecoderErrors++;
                if (standbyDecoderErrors >= 2) standbyDecoderAllowed = false;
                releasePlayer();
            } else {
                handler.post(() -> {
                    if (!released && itemGeneration == gen) tryNextUrl();
                });
            }
        }
    }

    private void tryNextUrl() {
        urlIndex++;
        releasePlayer();
        if (item != null && urlIndex < urls.size() && !released) {
            if (wantPlay) scheduleLoadingSpinner();
            openUrl();
            return;
        }
        refreshDetailOrFail();
    }

    private void refreshDetailOrFail() {
        final Aweme target = item;
        if (target == null || released) {
            markPlaybackFailed(target);
            return;
        }
        if (detailRefreshed || target.id == null || target.id.isEmpty()) {
            markPlaybackFailed(target);
            return;
        }
        detailRefreshed = true;
        final long gen = itemGeneration;
        final String q = App.get().store().quality();
        if (wantPlay) scheduleLoadingSpinner();
        Async.run(() -> App.get().api().detail(target.id), (fresh, err) -> {
            if (released || item != target || itemGeneration != gen) return;
            if (fresh != null) {
                List<String> refreshed = fresh.playCandidates(q);
                if (!refreshed.isEmpty()) {
                    target.copyStreamsFrom(fresh);
                    urls = refreshed;
                    urlIndex = 0;
                    failed = false;
                    errorBox.setVisibility(GONE);
                    if (wantPlay) openUrl();
                    else hideLoadingSpinner();
                    return;
                }
            }
            markPlaybackFailed(target);
        });
    }

    private void markPlaybackFailed(final Aweme target) {
        failed = true;
        standbyMode = false;
        hideLoadingSpinner();
        errorTitle.setText("当前视频暂时无法播放");
        errorHint.setText("按 ↑ ↓ 切换视频");
        errorBox.setVisibility(VISIBLE);
        if (actions != null && item == target && target != null) {
            post(() -> {
                if (item == target && actions != null) actions.onPlaybackError(target);
            });
        }
    }

    private void applyVideoSize(VideoSize size) {
        if (size == null || size.width <= 0 || size.height <= 0) return;
        int vw = size.pixelWidthHeightRatio != 1f
                ? Math.max(1, Math.round(size.width * size.pixelWidthHeightRatio))
                : size.width;
        int vh = size.height;
        post(() -> fitMedia(vw, vh));
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public void setRotationDegrees(int deg) {
        rotationDegrees = ((deg % 360) + 360) % 360;
        fitMedia(videoW, videoH);
    }

    public int rotateNext() {
        setRotationDegrees(rotationDegrees + 90);
        return rotationDegrees;
    }

    private void fitMedia(int vw, int vh) {
        if (vw > 0 && vh > 0) {
            videoW = vw;
            videoH = vh;
        }
        textureView.setRotation(rotationDegrees);
        int width = -1, height = -1;
        int pw = getWidth(), ph = getHeight();
        if (videoW > 0 && videoH > 0 && pw > 0 && ph > 0) {
            boolean swapped = (rotationDegrees % 180) != 0;
            float scale = swapped
                    ? Math.min(pw / (float) videoH, ph / (float) videoW)
                    : Math.min(pw / (float) videoW, ph / (float) videoH);
            width = Math.max(1, Math.round(videoW * scale));
            height = Math.max(1, Math.round(videoH * scale));
        }
        textureView.setLayoutParams(new LayoutParams(width, height, Gravity.CENTER));
    }

    public boolean hasRenderedFrame() {
        return renderedFirstFrame && !released;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (player != null) applyVideoSize(player.getVideoSize());
        else if (item != null) fitMedia(item.videoWidth, item.videoHeight);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTicker();
    }

    public void setLiked(boolean value) {
        liked = value;
        diggLabel.setLabelText(value ? "已赞" : "喜欢");
        diggLabel.setActive(value);
    }

    public void setDigg(long count, boolean value) {
        setLiked(value);
        diggLabel.setContentDescription((value ? "已点赞，" : "未点赞，") + Ui.count(count));
        if (item != null) {
            meta.setText("赞 " + Ui.count(count) + " · 评论 " + Ui.count(item.commentCount)
                    + " · 分享 " + Ui.count(item.shareCount));
        }
    }

    private void loadDanmaku(final Aweme a) {
        final long gen = ++danmakuGeneration;
        Async.run(() -> App.get().api().comments(a.id, 40), (value, error) -> {
            if (gen != danmakuGeneration) return;
            List<String> list = value;
            if ((list == null || list.isEmpty()) && a.desc != null && !a.desc.isEmpty()) {
                list = new ArrayList<>();
                list.add(a.desc);
            }
            danmakuQueue.clear();
            if (list != null) {
                for (String s : list) {
                    if (s == null) continue;
                    String t = s.trim();
                    if (!t.isEmpty() && !danmakuQueue.contains(t)) danmakuQueue.add(t);
                }
            }
            danmakuPos = 0;
            handler.removeCallbacks(danmakuPump);
            if (!danmakuQueue.isEmpty() && App.get().store().danmakuOn() && wantPlay) {
                handler.postDelayed(danmakuPump, 650);
            }
        });
    }

    private final Runnable danmakuPump = new Runnable() {
        @Override public void run() {
            if (!wantPlay || released) return;
            if (!App.get().store().danmakuOn()) {
                handler.postDelayed(this, 1200);
                return;
            }
            if (!danmakuQueue.isEmpty() && isPlaying()) {
                if (danmakuPos >= danmakuQueue.size()) danmakuPos = 0;
                danmaku.add(danmakuQueue.get(danmakuPos++), false);
            }
            handler.postDelayed(this, 1050);
        }
    };

    public void refreshDanmakuSettings() {
        danmaku.applySettings();
        if (App.get().store().danmakuOn() && !danmakuQueue.isEmpty() && wantPlay) {
            handler.removeCallbacks(danmakuPump);
            handler.postDelayed(danmakuPump, 500);
        } else if (App.get().store().danmakuOn() && danmakuQueue.isEmpty() && item != null && wantPlay) {
            loadDanmaku(item);
        }
    }

    public void play() {
        boolean wasStandby = standbyMode;
        standbyMode = false;
        wantPlay = true;
        userPaused = false;
        paintCenterToggle();
        if (item != null) {
            App.get().api().applyKnownLike(item);
            setDigg(item.diggCount, item.userDigged == 1);
        }
        danmaku.setPaused(false);
        if (chromeVisible) resetChromeTimeout();
        if (player == null) {
            if (urls.isEmpty() && item != null) {
                urls = item.playCandidates(App.get().store().quality());
            }
            showCoverMask();
            scheduleLoadingSpinner();
            if (!urls.isEmpty()) {
                urlIndex = 0;
                errorBox.setVisibility(GONE);
                openUrl();
            } else if (item != null) {
                refreshDetailOrFail();
            }
            return;
        }
        try {
            player.setVolume(1f);
            player.setPlayWhenReady(true);
            player.play();
            applySpeed();
            if (renderedFirstFrame) {
                hideCoverMask();
                hideLoadingSpinner();
                if (item != null && danmakuQueue.isEmpty() && App.get().store().danmakuOn()) {
                    loadDanmaku(item);
                } else if (!danmakuQueue.isEmpty() && App.get().store().danmakuOn()) {
                    handler.removeCallbacks(danmakuPump);
                    handler.postDelayed(danmakuPump, 450);
                }
                if (wasStandby && actions != null) actions.onVideoPlaying();
                startTicker();
            } else {
                showCoverMask();
                scheduleLoadingSpinner();
                handler.removeCallbacks(startupTimeout);
                handler.postDelayed(startupTimeout, 5500);
            }
        } catch (Exception ignored) {}
    }

    public void pause() {
        pause(false);
    }

    public void pauseSilently() {
        pause(true);
    }

    private void pause(boolean silent) {
        wantPlay = false;
        userPaused = !silent;
        stopTicker();
        paintCenterToggle();
        danmaku.setPaused(true);
        handler.removeCallbacks(danmakuPump);
        hideLoadingSpinner();
        if (player != null) {
            try {
                player.setPlayWhenReady(false);
                player.pause();
            } catch (Exception ignored) {}
            handler.removeCallbacks(bufferTimeout);
        }
        handler.removeCallbacks(hideChromeTask);
        if (!silent) {
            showChrome();
            if (!hasControlFocus()) focusCenterToggle();
        }
    }

    /** 退到后台或切换页面时保存当前进度并释放硬件解码器，防止多页面占用解码资源。 */
    public void saveAndReleasePlayer() {
        long pos = position();
        if (pos > 0) restorePosition = pos;
        wantPlay = false;
        userPaused = false;
        standbyMode = false;
        stopTicker();
        handler.removeCallbacks(danmakuPump);
        handler.removeCallbacks(hideChromeTask);
        danmaku.setPaused(true);
        applyChromeVisibilityNow(false, false);
        hideLoadingSpinner();
        releasePlayer();
    }

    public boolean wantsPlayback() {
        return wantPlay;
    }

    public void reloadQuality() {
        long pos = position();
        setAweme(item, wantPlay);
        restorePosition = pos;
    }

    public void applyRepeatSetting() {
        if (player == null) return;
        try {
            player.setRepeatMode(App.get().store().autoNext() ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
        } catch (Exception ignored) {}
    }

    public void setFullscreenSupported(boolean supported) {
    }

    public void setFullscreen(boolean full) {
    }

    // ------------------------------------------------------------ 控制层显示/隐藏

    private void setControlsFocusable(boolean focusable) {
        if (centerToggle != null) {
            centerToggle.setFocusable(focusable);
            centerToggle.setFocusableInTouchMode(focusable);
        }
        if (rightRail != null) {
            rightRail.setDescendantFocusability(focusable
                    ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private void applyChromeVisibilityNow(boolean visible, boolean notifyHost) {
        chromeVisible = visible;
        setControlsFocusable(visible);
        float a = visible ? 1f : 0f;
        int vis = visible ? VISIBLE : INVISIBLE;
        for (View v : new View[]{bottomGrad, bottomText, rightRail, centerToggle}) {
            v.animate().cancel();
            v.setAlpha(a);
            v.setVisibility(vis);
        }
        paintCenterToggle();
        if (notifyHost && actions != null) actions.onChromeVisibilityChanged(visible);
    }

    /** 显示播放控制层，autoHide=true 时无操作 4.5 秒后自动隐藏。 */
    public void showChrome() {
        showChrome(true);
    }

    public void showChrome(boolean autoHide) {
        boolean opening = !chromeVisible;
        handler.removeCallbacks(hideChromeTask);
        chromeVisible = true;
        setControlsFocusable(true);
        paintCenterToggle();
        for (View v : new View[]{bottomGrad, bottomText, rightRail, centerToggle}) {
            v.animate().cancel();
            if (opening) v.setAlpha(0f);
            v.setVisibility(VISIBLE);
            v.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease()).start();
        }
        if (actions != null) actions.onChromeVisibilityChanged(true);
        if (autoHide) resetChromeTimeout();
    }

    /** 渐隐控制层并将焦点交回视频本体。 */
    public void hideChrome() {
        handler.removeCallbacks(hideChromeTask);
        boolean wasVisible = chromeVisible;
        boolean hadFocus = hasControlFocus();
        chromeVisible = false;
        setControlsFocusable(false);
        for (final View v : new View[]{bottomGrad, bottomText, rightRail, centerToggle}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(180).setInterpolator(Ui.ease())
                    .withEndAction(() -> {
                        if (!chromeVisible) v.setVisibility(INVISIBLE);
                    }).start();
        }
        if (wasVisible && actions != null) actions.onChromeVisibilityChanged(false);
        if (hadFocus && actions != null) actions.onChromeHidden();
    }

    public void hideChromeImmediately() {
        hideChromeImmediately(true);
    }

    public void hideChromeImmediately(boolean restoreFocus) {
        handler.removeCallbacks(hideChromeTask);
        boolean wasVisible = chromeVisible;
        boolean hadFocus = hasControlFocus();
        applyChromeVisibilityNow(false, wasVisible);
        if (restoreFocus && hadFocus && actions != null) actions.onChromeHidden();
    }

    /** 按确认键唤出控制层，默认聚焦中间的播放/暂停按钮。 */
    public void revealControls() {
        showChrome();
        focusCenterToggle();
    }

    /** 短按右键直接唤出控制层并聚焦右侧操作栏「喜欢」。 */
    public void revealRightRail() {
        showChrome();
        focusRightRail();
    }

    public void focusCenterToggle() {
        if (!chromeVisible) showChrome();
        centerToggle.requestFocus();
        centerToggle.post(() -> {
            if (chromeVisible && !hasControlFocus()) centerToggle.requestFocus();
        });
        resetChromeTimeout();
    }

    public void focusRightRail() {
        if (!chromeVisible) showChrome();
        diggLabel.requestFocus();
        diggLabel.post(() -> {
            if (chromeVisible && !isRightRailFocused()) diggLabel.requestFocus();
        });
        resetChromeTimeout();
    }

    public void resetChromeTimeout() {
        handler.removeCallbacks(hideChromeTask);
        if (chromeVisible && !userPaused && wantPlay) {
            handler.postDelayed(hideChromeTask, CHROME_TIMEOUT_MS);
        }
    }

    public boolean isCenterToggleFocused() {
        return centerToggle != null && centerToggle.hasFocus();
    }

    public boolean isRightRailFocused() {
        for (View b : controlButtons) {
            if (b.hasFocus()) return true;
        }
        return false;
    }

    public boolean hasControlFocus() {
        return isCenterToggleFocused() || isRightRailFocused();
    }

    public boolean isChromeVisible() {
        return chromeVisible;
    }

    /**
     * 控制层显示期间的遥控器处理：
     * - 焦点在中间播放/暂停键：确认切换播放/暂停，↑ 进顶部导航，↓ 隐藏控制层，→ 去右侧图标栏，← 隐藏控制层。
     * - 焦点在右侧图标栏：↑/↓ 切换 5 个图标（顶部再按 ↑ 进顶部导航），← 回到中间播放/暂停键，返回隐藏控制层。
     */
    public boolean handleChromeKey(KeyEvent event) {
        if (!chromeVisible) return false;
        int key = event.getKeyCode();
        int act = event.getAction();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                || key == KeyEvent.KEYCODE_BUTTON_B) {
            if (act == KeyEvent.ACTION_UP && !event.isCanceled()) {
                hideChrome();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!hasControlFocus()) return false;
            if (act == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                resetChromeTimeout();
                if (isCenterToggleFocused()) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) {
                        if (actions != null) actions.onExitToNavigation();
                    } else {
                        hideChrome();
                    }
                } else if (isRightRailFocused()) {
                    moveRightRailFocus(key);
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (act == KeyEvent.ACTION_DOWN) {
                resetChromeTimeout();
                return true;
            }
            if (act == KeyEvent.ACTION_UP && !event.isCanceled() && event.getRepeatCount() == 0) {
                resetChromeTimeout();
                if (isCenterToggleFocused()) {
                    centerToggle.performClick();
                } else {
                    View focused = findFocusedRightControl();
                    if (focused != null) focused.performClick();
                    else focusCenterToggle();
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (act == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && actions != null) {
                actions.onControls();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (act == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && actions != null) {
                actions.onPlayPause();
            }
            return true;
        }
        return false;
    }

    private View findFocusedRightControl() {
        for (View b : controlButtons) {
            if (b.hasFocus()) return b;
        }
        return null;
    }

    private void moveRightRailFocus(int key) {
        List<View> visible = new ArrayList<>();
        for (View b : controlButtons) {
            if (b.getVisibility() == VISIBLE) visible.add(b);
        }
        if (visible.isEmpty()) return;
        View focused = findFocusedRightControl();
        int idx = visible.indexOf(focused);
        if (idx < 0) {
            diggLabel.requestFocus();
            return;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (idx == 0) {
                if (actions != null) actions.onExitToNavigation();
            } else {
                visible.get(idx - 1).requestFocus();
            }
        } else {
            if (idx + 1 < visible.size()) {
                visible.get(idx + 1).requestFocus();
            }
        }
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public long seekBy(long deltaMs) {
        if (player == null || !prepared) return -1;
        try {
            long dur = player.getDuration();
            if (dur <= 0 || dur == C.TIME_UNSET) return -1;
            long pos = Math.max(0, Math.min(dur - 1L, player.getCurrentPosition() + deltaMs));
            player.seekTo(pos);
            lastTickSec = -1;
            updateProgress(pos, dur);
            return pos;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public long position() {
        try {
            return player != null && prepared ? Math.max(0, player.getCurrentPosition()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public long duration() {
        try {
            long dur = player != null && prepared ? player.getDuration() : 0;
            return dur == C.TIME_UNSET ? 0 : Math.max(0, dur);
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized void releasePlayer() {
        stopTicker();
        handler.removeCallbacks(startupTimeout);
        handler.removeCallbacks(bufferTimeout);
        renderedFirstFrame = false;
        showCoverMask();
        if (player != null) {
            try { player.clearVideoTextureView(textureView); } catch (Throwable ignored) {}
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        prepared = false;
    }

    public void releaseAll() {
        released = true;
        standbyMode = false;
        hideLoadingSpinner();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        danmaku.clear();
    }

    private void updateProgress(long pos, long dur) {
        if (dur > 0 && dur != C.TIME_UNSET) {
            seek.setProgress((int) Math.min(1000L, pos * 1000L / dur));
        }
        long sec = Math.max(0L, pos / 1000L);
        if (sec != lastTickSec) {
            lastTickSec = sec;
            time.setText(fmt(pos) + " / " + fmt(dur == C.TIME_UNSET ? 0 : dur));
        }
        if (listener != null) listener.onProgress(this, pos, dur == C.TIME_UNSET ? 0 : dur);
    }

    private void startTicker() {
        if (tickerRunning || released || !isAttachedToWindow()) return;
        tickerRunning = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void stopTicker() {
        tickerRunning = false;
        handler.removeCallbacks(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!tickerRunning || released || player == null || !isAttachedToWindow()) {
                tickerRunning = false;
                return;
            }
            if (player.isPlaying()) {
                try {
                    updateProgress(Math.max(0, player.getCurrentPosition()), player.getDuration());
                } catch (Exception ignored) {}
                handler.postDelayed(this, 500);
            } else {
                tickerRunning = false;
            }
        }
    };

    public static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
