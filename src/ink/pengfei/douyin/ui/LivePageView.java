package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.model.LiveRoom;
import ink.pengfei.douyin.net.LiveDanmakuClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单个直播间画面：Media3 ExoPlayer (HLS/FLV) + 遥控器 UI/视频分离控制层。 */
public class LivePageView extends FrameLayout {
    public interface Actions {
        void onPlayPause();
        void onPlaybackSettings();
        void onToggleFullscreen();
        void onExitToNavigation();
        void onLiveUnavailable(LiveRoom room);
        default void onLivePlaying() {}
        void onChromeHidden();
        default void onChromeVisibilityChanged(boolean visible) {}
    }

    public interface FrameCallback {
        void onBitmap(android.graphics.Bitmap bitmap);
    }

    private static final long INFO_TIMEOUT_MS = 4500L;
    private static final long LONG_LOADING_DELAY_MS = 1500L;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000L;
    private static final long ROOM_TIMEOUT_MS = 8500L;
    private static final long BUFFER_STALL_TIMEOUT_MS = 8000L;

    private final android.widget.ImageView coverView;
    private final TextureView surface;
    private final ProgressBar spinner;
    private final FrameLayout centerToggle;
    private final IconView centerToggleIcon;
    private final TextView error;
    private boolean infoVisible = false;
    private final DanmakuView danmaku;
    private final LinearLayout info;
    private final TextView nickView;
    private final TextView titleView;
    private final TextView viewersView;
    private final Ui.IconButton settingsButton;
    private final Ui.IconButton fullscreenButton;
    private final List<Ui.IconButton> controlButtons = new ArrayList<>();
    private final View bottomShade;
    private Actions actions;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideInfo = () -> {
        if (LivePageView.this.wantPlay) hideInfoLayer();
    };
    private final Runnable prepareTimeout = new Runnable() {
        @Override public void run() {
            if (released || renderedFirstFrame || !wantPlay || failed) return;
            android.util.Log.w("dytv-live", "First-frame timeout urlIdx=" + urlIndex);
            releasePlayer();
            urlIndex++;
            prepare();
        }
    };
    private final Runnable roomTimeout = new Runnable() {
        @Override public void run() {
            if (released || renderedFirstFrame || !wantPlay || failed) return;
            android.util.Log.w("dytv-live", "Room startup timeout, marking unavailable");
            markLiveUnavailable("当前直播加载超时或已关闭");
        }
    };
    private final Runnable bufferStallTimeout = new Runnable() {
        @Override public void run() {
            if (released || !wantPlay || failed || !renderedFirstFrame) return;
            android.util.Log.w("dytv-live", "Live buffer stall timeout, advancing");
            releasePlayer();
            urlIndex++;
            if (urlIndex < urls.length) {
                startRoomTimeout();
                prepare();
            } else {
                markLiveUnavailable("当前直播已中断或关闭");
            }
        }
    };

    private void startRoomTimeout() {
        handler.removeCallbacks(roomTimeout);
        if (!released && wantPlay && !failed && !renderedFirstFrame) {
            handler.postDelayed(roomTimeout, ROOM_TIMEOUT_MS);
        }
    }

    private void cancelTimeouts() {
        handler.removeCallbacks(prepareTimeout);
        handler.removeCallbacks(roomTimeout);
        handler.removeCallbacks(bufferStallTimeout);
    }

    private void markLiveUnavailable(String message) {
        if (failed || released) return;
        failed = true;
        cancelTimeouts();
        releasePlayer();
        hideLoadingSpinner();
        if (wantPlay) showError(message);
        if (room != null && actions != null) {
            final LiveRoom bad = room;
            post(() -> { if (room == bad && actions != null) actions.onLiveUnavailable(bad); });
        }
    }

    private ExoPlayer player;
    private LiveDanmakuClient danmakuClient;
    private final ArrayDeque<String> danmakuQueue = new ArrayDeque<>();
    private final Runnable danmakuPump = new Runnable() {
        @Override public void run() {
            if (released || !wantPlay) return;
            String next = danmakuQueue.pollFirst();
            if (next != null && !danmaku.add(next, false)) {
                danmakuQueue.addFirst(next);
            }
            handler.postDelayed(this, 460);
        }
    };
    private long danmakuGeneration;
    private String danmakuRoomId = "";
    private LiveRoom room;
    private boolean prepared;
    private boolean renderedFirstFrame;
    private boolean wantPlay;
    private boolean fullscreenMode;
    private boolean released;
    private boolean failed;
    private boolean spinnerScheduled;
    private String[] urls = new String[0];
    private int urlIndex;
    private long prepareGeneration;

    private final Runnable delayedSpinner = this::showDelayedSpinner;

    private void showCoverMask() {
        if (room == null) {
            coverView.setVisibility(GONE);
            return;
        }
        coverView.animate().cancel();
        coverView.setAlpha(1f);
        coverView.setVisibility(VISIBLE);
    }

    private void hideCoverMask() {
        coverView.animate().cancel();
        coverView.setVisibility(GONE);
    }

    private void scheduleLoadingSpinner() {
        if (released || !wantPlay || failed || spinnerScheduled || spinner.getVisibility() == VISIBLE) return;
        spinnerScheduled = true;
        handler.postDelayed(delayedSpinner, LONG_LOADING_DELAY_MS);
    }

    private void showDelayedSpinner() {
        spinnerScheduled = false;
        if (released || !wantPlay || failed) return;
        if (!renderedFirstFrame || (player != null && player.getPlaybackState() == Player.STATE_BUFFERING)) {
            spinner.setVisibility(VISIBLE);
        }
    }

    private void hideLoadingSpinner() {
        spinnerScheduled = false;
        handler.removeCallbacks(delayedSpinner);
        spinner.setVisibility(GONE);
    }

    public LivePageView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);

        // 封面层放在 TextureView 下方，TextureView 通过 alpha 0f -> 1f 控制显隐，
        // 避免上层不透明 View 遮挡导致部分设备 HWUI 裁剪 TextureView 黑屏。
        coverView = new android.widget.ImageView(c);
        coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        coverView.setBackgroundColor(Color.BLACK);
        coverView.setVisibility(GONE);
        addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        surface = new TextureView(c);
        surface.setAlpha(0f);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        danmaku = new DanmakuView(c);
        danmaku.setTopInsetPx(Ui.dp(c, 58));
        addView(danmaku, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        spinner.setVisibility(GONE);
        LayoutParams sp = new LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42));
        sp.gravity = Gravity.CENTER;
        addView(spinner, sp);

        // 中间播放/暂停圆形按钮（参考绿果 TV 播放器）
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
            resetInfoTimeout();
        });
        centerToggle.setOnFocusChangeListener((v, has) -> {
            paintCenterToggle();
            if (has) resetInfoTimeout();
        });
        paintCenterToggle();
        addView(centerToggle, new LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72), Gravity.CENTER));

        error = Ui.text(c, "", 16, Color.WHITE);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(GONE);
        LayoutParams ep = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ep.gravity = Gravity.CENTER;
        addView(error, ep);

        bottomShade = new View(c);
        bottomShade.setBackground(Ui.scrimBottom(c));
        LayoutParams bp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 155));
        bp.gravity = Gravity.BOTTOM;
        addView(bottomShade, bp);

        info = Ui.row(c);
        info.setGravity(Gravity.BOTTOM);
        Ui.padding(info, 20, 14, 24, 16);
        LinearLayout meta = Ui.column(c);
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView live = Ui.bold(c, "● LIVE", 10, Color.WHITE);
        live.setGravity(Gravity.CENTER);
        live.setBackground(Ui.round(c, Ui.VIDEO_ACCENT, 6));
        live.setPadding(Ui.dp(c, 7), Ui.dp(c, 2), Ui.dp(c, 7), Ui.dp(c, 2));
        line.addView(live);
        nickView = Ui.text(c, "", 14.5f, 0xD9FFFFFF, false);
        nickView.setPadding(Ui.dp(c, 10), 0, 0, 0);
        nickView.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x80000000);
        line.addView(nickView);
        viewersView = Ui.text(c, "", 11f, 0x99FFFFFF, false);
        viewersView.setPadding(Ui.dp(c, 10), 0, 0, 0);
        viewersView.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x80000000);
        line.addView(viewersView);
        meta.addView(line);
        titleView = Ui.text(c, "", 12f, 0xB0FFFFFF, false);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(0, Ui.dp(c, 5), Ui.dp(c, 20), 0);
        titleView.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x80000000);
        meta.addView(titleView);
        info.addView(meta, Ui.lp(0, -2, 1f));

        LinearLayout controls = Ui.row(c);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        settingsButton = liveButton(controls, "设置", "播放与弹幕设置", IconView.SLIDERS, () -> actions.onPlaybackSettings());
        fullscreenButton = liveButton(controls, "重连", "重连当前直播", IconView.REFRESH,
                () -> {
                    reloadStream();
                    hideInfoImmediately();
                });
        info.addView(controls, Ui.lp(-2, -2));

        LayoutParams ip = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM;
        addView(info, ip);

        applyInfoVisibilityNow(false, false);
    }

    private void paintCenterToggle() {
        Context c = getContext();
        boolean focused = centerToggle.hasFocus();
        boolean showPlay = !wantPlay;
        centerToggleIcon.setType(showPlay ? IconView.PLAY : IconView.PAUSE);
        centerToggle.setBackground(focused
                ? Ui.pill(c, 0xFFC6ED92)
                : Ui.roundStroke(c, 0x59000000, 36, 0x40FFFFFF, 1));
        centerToggleIcon.setColor(focused ? 0xFF121F09 : 0xFFFFFFFF);
        centerToggle.setContentDescription(showPlay ? "播放" : "暂停");
    }

    private Ui.IconButton liveButton(LinearLayout row, String label, String description,
                                     int icon, Runnable action) {
        Context context = getContext();
        final Ui.IconButton button = Ui.iconAction(context, icon, label, description, true, false, () -> {
            resetInfoTimeout();
            if (actions != null) action.run();
        });
        button.setOnFocusChangeListener((v, hasFocus) -> {
            button.repaint();
            if (hasFocus) resetInfoTimeout();
        });
        controlButtons.add(button);
        row.addView(button, Ui.margin(-2, -2, 0, 0, Ui.dp(context, 14), 0));
        return button;
    }

    public void setActions(Actions a) {
        actions = a;
    }

    public void setFullscreen(boolean full) {
        fullscreenMode = true;
    }

    private void setControlsFocusable(boolean focusable) {
        if (centerToggle != null) {
            centerToggle.setFocusable(focusable);
            centerToggle.setFocusableInTouchMode(focusable);
        }
        if (info != null) {
            info.setDescendantFocusability(focusable
                    ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private void applyInfoVisibilityNow(boolean visible, boolean notifyHost) {
        infoVisible = visible;
        setControlsFocusable(visible);
        float a = visible ? 1f : 0f;
        int vis = visible ? VISIBLE : INVISIBLE;
        for (View v : new View[]{info, bottomShade, centerToggle}) {
            v.animate().cancel();
            v.setAlpha(a);
            v.setVisibility(vis);
        }
        paintCenterToggle();
        if (notifyHost && actions != null) actions.onChromeVisibilityChanged(visible);
    }

    public void hideInfoImmediately() {
        hideInfoImmediately(true);
    }

    public void hideInfoImmediately(boolean restoreFocus) {
        handler.removeCallbacks(hideInfo);
        boolean wasVisible = infoVisible;
        boolean hadFocus = hasControlFocus();
        applyInfoVisibilityNow(false, wasVisible);
        if (restoreFocus && hadFocus && actions != null) actions.onChromeHidden();
    }

    public boolean isInfoVisible() {
        return infoVisible;
    }

    public boolean hasControlFocus() {
        if (centerToggle != null && centerToggle.hasFocus()) return true;
        for (Ui.IconButton b : controlButtons) {
            if (b.hasFocus()) return true;
        }
        return false;
    }

    public void applyDanmakuSettings() {
        danmaku.applySettings();
    }

    public void reloadStream() {
        if (room == null) return;
        cancelTimeouts();
        hideLoadingSpinner();
        releasePlayer();
        urls = room.urlList(App.get().store().liveQuality(), App.get().store().liveProtocol() == 1);
        urlIndex = 0;
        failed = false;
        error.setVisibility(GONE);
        if (urls.length > 0 && wantPlay) {
            showCoverMask();
            scheduleLoadingSpinner();
            startRoomTimeout();
            prepare();
        } else if (wantPlay) markLiveUnavailable("当前清晰度暂无可用直播流");
    }

    /** 显示直播间信息层，autoHide=true 时播放中短暂等待后自动隐藏。 */
    public void showInfo() {
        showInfo(true);
    }

    public void showInfo(boolean autoHide) {
        boolean opening = !infoVisible;
        handler.removeCallbacks(hideInfo);
        infoVisible = true;
        setControlsFocusable(true);
        paintCenterToggle();
        for (View v : new View[]{info, bottomShade, centerToggle}) {
            v.animate().cancel();
            if (opening) v.setAlpha(0f);
            v.setVisibility(VISIBLE);
            v.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease()).start();
        }
        if (actions != null) actions.onChromeVisibilityChanged(true);
        if (autoHide) resetInfoTimeout();
    }

    /** 按确认键唤出控制层，默认聚焦中间的播放/暂停按钮。 */
    public void revealInfo() {
        showInfo();
        focusCenterToggle();
    }

    /** 按右键唤出控制层并聚焦底部按钮栏。 */
    public void revealControls() {
        showInfo();
        settingsButton.requestFocus();
        settingsButton.post(() -> {
            if (infoVisible && !hasControlFocus()) settingsButton.requestFocus();
        });
        resetInfoTimeout();
    }

    private void focusCenterToggle() {
        centerToggle.requestFocus();
        centerToggle.post(() -> {
            if (infoVisible && !hasControlFocus()) centerToggle.requestFocus();
        });
        resetInfoTimeout();
    }

    public void resetInfoTimeout() {
        handler.removeCallbacks(hideInfo);
        if (infoVisible && wantPlay) {
            handler.postDelayed(hideInfo, INFO_TIMEOUT_MS);
        }
    }

    private void hideInfoLayer() {
        handler.removeCallbacks(hideInfo);
        boolean wasVisible = infoVisible;
        boolean hadFocus = hasControlFocus();
        infoVisible = false;
        setControlsFocusable(false);
        for (final View v : new View[]{info, bottomShade, centerToggle}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(180).setInterpolator(Ui.ease())
                    .withEndAction(() -> {
                        if (!infoVisible) v.setVisibility(INVISIBLE);
                    }).start();
        }
        if (wasVisible && actions != null) actions.onChromeVisibilityChanged(false);
        if (hadFocus && actions != null) actions.onChromeHidden();
    }

    public boolean handleInfoKey(KeyEvent event) {
        if (!infoVisible) return false;
        int key = event.getKeyCode();
        int act = event.getAction();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                || key == KeyEvent.KEYCODE_BUTTON_B) {
            if (act == KeyEvent.ACTION_UP && !event.isCanceled()) {
                hideInfoLayer();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (!hasControlFocus()) return false;
            if (act == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                resetInfoTimeout();
                if (centerToggle.hasFocus()) {
                    if (actions != null) actions.onExitToNavigation();
                } else {
                    focusCenterToggle();
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!hasControlFocus()) return false;
            if (act == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                resetInfoTimeout();
                if (centerToggle.hasFocus()) {
                    settingsButton.requestFocus();
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (act == KeyEvent.ACTION_DOWN) {
                resetInfoTimeout();
                if (event.getRepeatCount() == 0) {
                    if (!hasControlFocus()) {
                        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) settingsButton.requestFocus();
                        else hideInfoLayer();
                    } else if (centerToggle.hasFocus()) {
                        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) settingsButton.requestFocus();
                        else hideInfoLayer();
                    } else {
                        moveControlFocus(key);
                    }
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (act == KeyEvent.ACTION_DOWN) {
                resetInfoTimeout();
                return true;
            }
            if (act == KeyEvent.ACTION_UP && !event.isCanceled() && event.getRepeatCount() == 0) {
                resetInfoTimeout();
                if (centerToggle.hasFocus()) {
                    centerToggle.performClick();
                } else {
                    View focused = findFocusedControl();
                    if (focused == null) focusCenterToggle();
                    else focused.performClick();
                }
            }
            return true;
        }
        return false;
    }

    private View findFocusedControl() {
        for (Ui.IconButton b : controlButtons) {
            if (b.hasFocus()) return b;
        }
        return null;
    }

    private void moveControlFocus(int key) {
        List<Ui.IconButton> visible = new ArrayList<>();
        for (Ui.IconButton b : controlButtons) {
            if (b.getVisibility() == VISIBLE) visible.add(b);
        }
        if (visible.isEmpty()) return;
        View focused = findFocusedControl();
        int idx = visible.indexOf(focused);
        if (idx < 0) {
            visible.get(0).requestFocus();
            return;
        }
        int next = key == KeyEvent.KEYCODE_DPAD_LEFT ? idx - 1 : idx + 1;
        if (next >= 0 && next < visible.size()) {
            visible.get(next).requestFocus();
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) {
            focusCenterToggle();
        }
    }

    public void setRoom(LiveRoom r, boolean autoplay) {
        cancelTimeouts();
        hideLoadingSpinner();
        releasePlayer();
        stopDanmaku();
        room = r;
        renderedFirstFrame = false;
        failed = false;
        error.setVisibility(GONE);
        coverView.setTag(null);
        coverView.setImageDrawable(null);
        if (r == null) {
            hideCoverMask();
            applyInfoVisibilityNow(false, false);
            return;
        }
        wantPlay = autoplay;
        showCoverMask();
        String coverUrl = (r.cover != null && !r.cover.isEmpty()) ? r.cover : r.avatar;
        if (coverUrl != null && !coverUrl.isEmpty()) {
            ink.pengfei.douyin.util.Images.load(coverUrl, coverView, 1280, 0);
        }
        nickView.setText(r.nick == null || r.nick.isEmpty() ? "抖音直播" : r.nick);
        titleView.setText(r.title == null || r.title.isEmpty() ? "正在直播" : r.title);
        viewersView.setText(r.viewers > 0 ? Ui.count(r.viewers) + " 人看" : "");
        resetSurface();
        urls = r.urlList(App.get().store().liveQuality(), App.get().store().liveProtocol() == 1);
        urlIndex = 0;
        applyDanmakuSettings();
        applyInfoVisibilityNow(false, false);
        if (!autoplay) return;
        scheduleLoadingSpinner();
        if (urls.length > 0) {
            startRoomTimeout();
            prepare();
        } else {
            markLiveUnavailable("当前直播已结束");
        }
    }

    private void resetSurface() {
        surface.setAlpha(0f);
        showCoverMask();
        LayoutParams lp = (LayoutParams) surface.getLayoutParams();
        lp.width = LayoutParams.MATCH_PARENT;
        lp.height = LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private void prepare() {
        if (released || urlIndex >= urls.length) {
            markLiveUnavailable("当前直播加载失败或已关闭");
            return;
        }
        releasePlayer();
        error.setVisibility(GONE);
        if (wantPlay) scheduleLoadingSpinner();
        final String url = urls[urlIndex];
        final long gen = ++prepareGeneration;

        try {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(getContext())
                    .setEnableDecoderFallback(true);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1500, 10000, 150, 600)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://live.douyin.com/");
            String cookies = App.get().api().cookies().header();
            if (!cookies.isEmpty()) headers.put("Cookie", cookies);

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(ink.pengfei.douyin.net.Http.UA)
                    .setDefaultRequestProperties(headers)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(4000)
                    .setReadTimeoutMs(5000);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build();

            final ExoPlayer exo = new ExoPlayer.Builder(getContext(), renderers)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory)
                            .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0)))
                    .setLoadControl(loadControl)
                    .setAudioAttributes(audioAttributes, true)
                    .build();
            player = exo;
            exo.setVideoTextureView(surface);
            exo.setVolume(1f);
            exo.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (player != exo || released) return;
                    if (playbackState == Player.STATE_BUFFERING && wantPlay) {
                        scheduleLoadingSpinner();
                        if (renderedFirstFrame) {
                            handler.removeCallbacks(bufferStallTimeout);
                            handler.postDelayed(bufferStallTimeout, BUFFER_STALL_TIMEOUT_MS);
                        }
                    } else if (playbackState == Player.STATE_READY) {
                        prepared = true;
                        handler.removeCallbacks(bufferStallTimeout);
                        if (renderedFirstFrame) hideLoadingSpinner();
                        fitVideo(exo.getVideoSize());
                    } else if (playbackState == Player.STATE_ENDED) {
                        if (!wantPlay) return;
                        advanceStreamAsync(gen);
                    }
                }

                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    if (player != exo || released) return;
                    if (isPlaying) {
                        handler.removeCallbacks(bufferStallTimeout);
                        if (renderedFirstFrame) hideLoadingSpinner();
                    }
                }

                @Override public void onVideoSizeChanged(VideoSize videoSize) {
                    if (player == exo && !released && videoSize != null) fitVideo(videoSize);
                }

                @Override public void onRenderedFirstFrame() {
                    if (player != exo || released) return;
                    renderedFirstFrame = true;
                    cancelTimeouts();
                    surface.setAlpha(1f);
                    hideCoverMask();
                    hideLoadingSpinner();
                    if (actions != null) actions.onLivePlaying();
                    if (wantPlay) startDanmaku();
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (player != exo || !wantPlay || released) return;
                    android.util.Log.w("dytv-live", "Exo live error=" + error.getErrorCodeName());
                    advanceStreamAsync(gen);
                }
            });

            exo.setMediaItem(MediaItem.fromUri(url));
            exo.setPlayWhenReady(wantPlay);
            exo.prepare();
            handler.removeCallbacks(prepareTimeout);
            if (wantPlay) handler.postDelayed(prepareTimeout, FIRST_FRAME_TIMEOUT_MS);
        } catch (Throwable e) {
            android.util.Log.w("dytv-live", "Live prepare failed: " + e);
            advanceStreamAsync(gen);
        }
    }

    private void advanceStreamAsync(final long gen) {
        handler.post(() -> {
            if (released || failed || !wantPlay || gen != prepareGeneration) return;
            releasePlayer();
            urlIndex++;
            prepare();
        });
    }

    private void fitVideo(VideoSize size) {
        if (size == null || size.width <= 0 || size.height <= 0) return;
        final int vw = size.pixelWidthHeightRatio != 1f
                ? Math.max(1, Math.round(size.width * size.pixelWidthHeightRatio))
                : size.width;
        final int vh = size.height;
        int pw = getWidth(), ph = getHeight();
        if (pw <= 0 || ph <= 0) {
            post(() -> fitVideo(size));
            return;
        }
        float scale = Math.min(pw / (float) vw, ph / (float) vh);
        int w = Math.round(vw * scale);
        int h = Math.round(vh * scale);
        LayoutParams lp = (LayoutParams) surface.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private void showError(String msg) {
        hideLoadingSpinner();
        error.setText(msg);
        error.setVisibility(VISIBLE);
    }

    public boolean isPlaying() {
        if (wantPlay) return true;
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void play() {
        if (released) return;
        wantPlay = true;
        paintCenterToggle();
        if (infoVisible) resetInfoTimeout();
        startDanmaku();
        if (failed) {
            if (room != null && actions != null) {
                final LiveRoom bad = room;
                post(() -> { if (room == bad && actions != null) actions.onLiveUnavailable(bad); });
            }
            return;
        }
        if (player == null && urls.length > 0) {
            showCoverMask();
            scheduleLoadingSpinner();
            startRoomTimeout();
            prepare();
            return;
        }
        if (player != null) {
            player.setVolume(1f);
            player.setPlayWhenReady(true);
            player.play();
            if (!renderedFirstFrame) {
                showCoverMask();
                scheduleLoadingSpinner();
                startRoomTimeout();
            }
        }
    }

    public void pause() {
        pause(false);
    }

    public void pauseSilently() {
        pause(true);
    }

    private void pause(boolean silent) {
        wantPlay = false;
        paintCenterToggle();
        stopDanmaku();
        handler.removeCallbacks(hideInfo);
        handler.removeCallbacks(prepareTimeout);
        hideLoadingSpinner();
        releasePlayer();
        if (silent) {
            applyInfoVisibilityNow(false, false);
            return;
        }
        showInfo();
        if (!hasControlFocus()) focusCenterToggle();
    }

    public void flash(String msg) {
        Ui.notice(this, msg);
    }

    private void startDanmaku() {
        if (room == null || room.id == null || room.id.isEmpty()) return;
        if (danmakuClient != null && room.id.equals(danmakuRoomId)) return;
        stopDanmaku();
        danmaku.clear();
        danmakuQueue.clear();
        danmaku.setPaused(false);
        danmakuRoomId = room.id;
        final String startedRoom = room.id;
        final long generation = danmakuGeneration;
        LiveDanmakuClient client = new LiveDanmakuClient(
                room.id, room.webRid, App.get().api().cookies(),
                (nick, content) -> handler.post(() -> {
                    if (released || generation != danmakuGeneration
                            || !startedRoom.equals(danmakuRoomId)) return;
                    String line = nick == null || nick.isEmpty() ? content : nick + "：" + content;
                    if (danmakuQueue.size() >= 60) danmakuQueue.pollFirst();
                    danmakuQueue.addLast(line);
                }));
        danmakuClient = client;
        client.start();
        handler.removeCallbacks(danmakuPump);
        handler.postDelayed(danmakuPump, 200);
    }

    private void stopDanmaku() {
        danmakuGeneration++;
        handler.removeCallbacks(danmakuPump);
        danmakuQueue.clear();
        if (danmakuClient != null) {
            danmakuClient.stop();
            danmakuClient = null;
        }
        danmakuRoomId = "";
    }

    private void releasePlayer() {
        prepareGeneration++;
        handler.removeCallbacks(prepareTimeout);
        prepared = false;
        renderedFirstFrame = false;
        if (player != null) {
            try { player.clearVideoTextureView(surface); } catch (Throwable ignored) {}
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        if (surface != null) surface.setAlpha(0f);
    }

    public boolean hasRenderedFrame() {
        return renderedFirstFrame && !released;
    }

    public int renderedWidth() { return surface.getWidth(); }
    public int renderedHeight() { return surface.getHeight(); }

    public void captureFrame(final FrameCallback cb) {
        if (cb != null) cb.onBitmap(null);
    }

    public void release() {
        released = true;
        stopDanmaku();
        hideLoadingSpinner();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }
}
