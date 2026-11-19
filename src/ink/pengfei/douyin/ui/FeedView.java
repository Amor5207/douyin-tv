package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.ui.widget.FocusScrollView;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.VideoCache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全屏推荐流。长按左右方向键快退或快进，短按左右移动焦点，上下切换视频。
 * 单击 OK 播放/暂停，双击 OK 点赞，长按 OK 打开操作面板。鼠标点击全部可用。
 */
public class FeedView extends FrameLayout {
    public interface Host {
        void onOpenProfile(Aweme a);
        void onShare(Aweme a);
        void onLoginRequired();
        void onNearEnd();
        void onProgressSave(Aweme a, long positionMs);
        void onTabStep(int dir);
        boolean supportsFullscreen();
        boolean isFullscreen();
        void onToggleFullscreen();
        void onPlaybackError(Aweme a);
        default void onChromeVisibilityChanged(boolean visible) {}
    }

    private final List<Aweme> items = new ArrayList<>();
    private final VideoPage[] pages = new VideoPage[2];
    private final int[] slotIndex = {-1, -1};
    private final Set<String> pendingLikes = new HashSet<>();
    private android.animation.ValueAnimator slideAnimator;
    private int switchToken;
    private int curSlot = 0;
    private int index = -1;
    private boolean transitioning;
    private boolean playbackEnabled = true;
    private int waitingForNext = -1;
    private Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingPreload;

    private boolean longMenuOpened;
    private long lastTapAt;
    private float downX, downY;
    private boolean tapCandidate;

    private FrameLayout menu;
    private Aweme menuAweme;
    private boolean resumeAfterPanel;

    private static final long LONG_PRESS_SEEK_MS = 400L;

    private int arrowDir;
    private boolean arrowTracking;
    private boolean arrowLongPressed;
    private boolean arrowHeld;
    private TextView seekHud;
    private int consecutiveErrors;
    private Runnable pendingErrorSkip;
    private final Runnable longPressSeek = this::beginLongSeek;
    private final Runnable dismissSeek = this::hideSeekHud;
    private final Runnable seekTick = new Runnable() {
        @Override public void run() {
            if (arrowHeld) {
                stepSeek();
                handler.postDelayed(this, 380);
            }
        }
    };

    private final Runnable togglePlayPause = new Runnable() {
        @Override public void run() {
            if (overlayOpen()) return;
            VideoPage p = pages[curSlot];
            if (p == null) return;
            if (p.wantsPlayback() && waitingForNext < 0) {
                p.pause();
            } else {
                p.play();
            }
        }
    };

    private final Runnable tapToggle = new Runnable() {
        @Override public void run() {
            if (overlayOpen()) return;
            VideoPage p = pages[curSlot];
            if (p == null) return;
            if (p.isChromeVisible()) p.hideChrome();
            else p.revealControls();
        }
    };

    public FeedView(Context c) {
        super(c);
        setClipChildren(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            pages[i] = new VideoPage(c);
            pages[i].setVisibility(GONE);
            final VideoPage page = pages[i];
            page.setActions(new VideoPage.Actions() {
                @Override public void onLike() { toggleLike(); }
                @Override public void onShare() { shareCurrent(); }
                @Override public void onControls() { openMenu(); }
                @Override public void onPlaybackSettings() {
                    if (current() == null || overlayOpen()) return;
                    openPlaybackSettings(0);
                }
                @Override public void onDanmakuSettings() {
                    if (current() == null || overlayOpen()) return;
                    PlayerOverlays.openDanmakuSettings((android.app.Activity) getContext(),
                            null, FeedView.this::refreshDanmakuSettings, FeedView.this::requestFocus);
                }
                @Override public void onSeek(int seconds) {
                    page.seekBy(seconds * 1000L);
                    page.showChrome();
                }
                @Override public void onPlayPause() { togglePlayPause.run(); }
                @Override public void onRotate() {
                    VideoPage cur = pages[curSlot];
                    if (cur == null) return;
                    int deg = cur.rotateNext();
                    cur.showChrome();
                    flash(deg == 0 ? "画面已恢复默认方向" : "画面已旋转 " + deg + "°");
                }
                @Override public void onProfile() {
                    Aweme a = current();
                    if (a != null && host != null) host.onOpenProfile(a);
                }
                @Override public void onToggleFullscreen() {
                    if (host != null) host.onToggleFullscreen();
                }
                @Override public void onExitToNavigation() {
                    if (host != null) host.onTabStep(-1);
                }
                @Override public void onPlaybackError(Aweme a) {
                    handlePlaybackError(a);
                }
                @Override public void onVideoPlaying() {
                    consecutiveErrors = 0;
                    cancelErrorSkip();
                    schedulePreloadAround(index);
                }
                @Override public void onChromeHidden() {
                    if (!overlayOpen()) requestFocus();
                }
                @Override public void onChromeVisibilityChanged(boolean visible) {
                    if (host != null) host.onChromeVisibilityChanged(visible);
                }
            });
            page.setOnEnded(() -> {
                if (pages[curSlot] != page || !playbackEnabled) return;
                waitingForNext = index;
                if (!advanceWhenReady() && host != null) host.onNearEnd();
            });
            addView(pages[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        pages[0].setVisibility(VISIBLE);

        setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e);
            }
        });
    }

    private void cancelPendingPreload() {
        if (pendingPreload != null) {
            handler.removeCallbacks(pendingPreload);
            pendingPreload = null;
        }
    }

    private void schedulePreloadAround(final int at) {
        cancelPendingPreload();
        if (at < 0 || items.isEmpty() || !playbackEnabled) return;
        pendingPreload = () -> {
            pendingPreload = null;
            if (!isAttachedToWindow() || !playbackEnabled || transitioning || index != at) return;
            preloadAround(at);
        };
        handler.postDelayed(pendingPreload, 220);
    }

    private void preloadAround(int at) {
        if (at < 0 || items.isEmpty() || !playbackEnabled) return;
        for (int k : new int[]{at - 1, at + 1, at + 2}) {
            if (k >= 0 && k < items.size()) {
                Aweme a = items.get(k);
                if (a != null && a.cover != null && !a.cover.isEmpty()) {
                    ink.pengfei.douyin.util.Images.preload(a.cover, 1280);
                }
            }
        }
        if (App.get().store().preload() && at + 1 < items.size()) {
            preload(1 - curSlot, at + 1);
            if (VideoPage.canUseStandbyDecoder()) {
                Aweme next2 = at + 2 < items.size() ? items.get(at + 2) : null;
                Aweme next3 = at + 3 < items.size() ? items.get(at + 3) : null;
                VideoCache.preloadUpcoming(getContext(), next2, next3);
            } else {
                Aweme next1 = items.get(at + 1);
                Aweme next2 = at + 2 < items.size() ? items.get(at + 2) : null;
                VideoCache.preloadUpcoming(getContext(), next1, next2);
            }
        }
    }

    public void setHost(Host h) {
        host = h;
        boolean canFull = h != null && h.supportsFullscreen();
        boolean full = canFull && h.isFullscreen();
        for (VideoPage p : pages) {
            p.setFullscreenSupported(canFull);
            p.setFullscreen(full);
        }
    }

    public void setFullscreen(boolean full) {
        for (VideoPage p : pages) p.setFullscreen(full);
        if (full && pages[curSlot] != null) pages[curSlot].hideChromeImmediately(false);
    }

    public List<Aweme> items() {
        return items;
    }

    public Aweme current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public boolean menuOpen() {
        return menu != null;
    }

    public boolean dismissChromeIfOpen() {
        return dismissChromeIfOpen(true);
    }

    public boolean dismissChromeIfOpen(boolean restoreFocus) {
        if (menu != null) {
            closeMenu(true);
            return true;
        }
        VideoPage curPage = pages[curSlot];
        if (curPage != null && curPage.isChromeVisible()) {
            curPage.hideChromeImmediately(restoreFocus);
            if (restoreFocus) requestFocus();
            return true;
        }
        return false;
    }

    public void showChrome(boolean autoHide) {
        VideoPage curPage = pages[curSlot];
        if (curPage != null && current() != null) {
            curPage.showChrome(autoHide);
        }
    }

    private boolean overlayOpen() {
        return menu != null;
    }

    // ------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlayOpen()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    closeMenu(true);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        VideoPage curPage = pages[curSlot];
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return handleHorizontalKey(event, curPage, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
        }
        if (curPage != null && curPage.isChromeVisible()) {
            cancelPendingControls();
            if (curPage.handleChromeKey(event)) return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getRepeatCount() == 0) {
                        if (index <= 0 && host != null) host.onTabStep(-1);
                        else navigate(index - 1, -1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.getRepeatCount() == 0) navigate(index + 1, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() >= 1 && !longMenuOpened) {
                        longMenuOpened = true;
                        openMenu();
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (event.getRepeatCount() == 0 && curPage != null) {
                        curPage.revealControls();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePlayPause.run();
                    return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (!event.isCanceled() && !longMenuOpened && curPage != null) {
                    curPage.revealControls();
                }
                longMenuOpened = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleHorizontalKey(KeyEvent event, VideoPage curPage, int dir) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                stopSeeking();
                arrowDir = dir;
                arrowTracking = true;
                arrowLongPressed = false;
                if (current() != null && !transitioning) {
                    handler.postDelayed(longPressSeek, LONG_PRESS_SEEK_MS);
                }
            } else if (arrowTracking && arrowDir == dir && !arrowLongPressed) {
                beginLongSeek();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (arrowTracking && arrowDir == dir) {
                boolean wasLong = arrowLongPressed;
                stopSeeking();
                if (wasLong) {
                    handler.postDelayed(dismissSeek, 700);
                    if (curPage != null && curPage.isChromeVisible()) curPage.resetChromeTimeout();
                } else if (!event.isCanceled() && curPage != null) {
                    if (!curPage.isChromeVisible()) {
                        if (dir > 0) curPage.revealRightRail();
                    } else if (curPage.isCenterToggleFocused()) {
                        if (dir > 0) curPage.focusRightRail();
                        else curPage.hideChrome();
                    } else if (curPage.isRightRailFocused()) {
                        if (dir < 0) curPage.focusCenterToggle();
                        else curPage.resetChromeTimeout();
                    } else {
                        if (dir > 0) curPage.focusRightRail();
                        else curPage.hideChrome();
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void beginLongSeek() {
        if (!arrowTracking || arrowLongPressed || current() == null || transitioning) return;
        handler.removeCallbacks(longPressSeek);
        arrowLongPressed = true;
        arrowHeld = true;
        stepSeek();
        handler.postDelayed(seekTick, 380);
    }

    private void stopSeeking() {
        handler.removeCallbacks(longPressSeek);
        handler.removeCallbacks(seekTick);
        handler.removeCallbacks(dismissSeek);
        arrowTracking = false;
        arrowLongPressed = false;
        arrowHeld = false;
    }

    private void stepSeek() {
        VideoPage p = pages[curSlot];
        if (p == null) return;
        long target = p.seekBy(arrowDir * 5000L);
        if (target < 0) return;
        showSeekHud(arrowDir, target);
    }

    // ------------------------------------------------------------ touch

    private boolean handleTouch(MotionEvent e) {
        if (overlayOpen()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                tapCandidate = true;
                if (!hasFocus()) requestFocus();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getY() - downY) > Ui.dp(this, 60)
                        || Math.abs(e.getX() - downX) > Ui.dp(this, 100)) {
                    tapCandidate = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dy) > Ui.dp(this, 90) && Math.abs(dy) > Math.abs(dx)) {
                    navigate(index + (dy < 0 ? 1 : -1), dy < 0 ? 1 : -1);
                    tapCandidate = false;
                    return true;
                }
                if (Math.abs(dx) > Ui.dp(this, 110) && Math.abs(dx) > Math.abs(dy)) {
                    if (host != null) host.onTabStep(dx < 0 ? 1 : -1);
                    tapCandidate = false;
                    return true;
                }
                if (tapCandidate) handleTap();
                tapCandidate = false;
                return true;
        }
        return false;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        return super.dispatchHoverEvent(event);
    }

    private void handleTap() {
        long now = System.currentTimeMillis();
        if (now - lastTapAt < 300 && lastTapAt > 0) {
            lastTapAt = 0;
            removeCallbacks(tapToggle);
            toggleLike();
        } else {
            lastTapAt = now;
            postDelayed(tapToggle, 260);
        }
    }

    // ------------------------------------------------------------ navigation

    private void navigate(int target, int dir) {
        if (transitioning || items.isEmpty()) return;
        if (target < 0) {
            flash("已经是第一个视频");
            return;
        }
        if (target >= items.size()) {
            if (host != null) host.onNearEnd();
            flash("正在加载更多推荐…");
            return;
        }
        consecutiveErrors = 0;
        cancelErrorSkip();
        transitionTo(target, dir);
    }

    private void cancelSlideTransition() {
        if (slideAnimator != null) {
            slideAnimator.cancel();
            slideAnimator = null;
        }
        for (VideoPage p : pages) p.setTranslationY(0f);
    }

    private void transitionTo(int target, int dir) {
        waitingForNext = -1;
        transitioning = true;
        cancelPendingPreload();
        cancelPendingControls();
        cancelSlideTransition();
        final int token = ++switchToken;
        final int oldSlot = curSlot;
        final int newSlot = 1 - curSlot;
        final Aweme old = current();
        if (old != null) {
            long pos = pages[oldSlot].position();
            if (host != null) host.onProgressSave(old, pos);
            App.get().store().addRecent(old);
            final Aweme save = old;
            Async.run(new Async.Fn<Object>() {
                @Override public Object run() {
                    App.get().api().writeHistory(save, pos);
                    return null;
                }
            }, null);
        }
        final VideoPage oldPage = pages[oldSlot];
        oldPage.animate().withEndAction(null).cancel();
        oldPage.hideChromeImmediately(false);
        oldPage.pauseSilently();

        final VideoPage targetPage = pages[newSlot];
        targetPage.animate().withEndAction(null).cancel();
        boolean prewarmed = slotIndex[newSlot] == target;
        if (!prewarmed) {
            if (!VideoPage.canUseStandbyDecoder()) {
                oldPage.releasePlayer();
                oldPage.setVisibility(GONE);
            }
            targetPage.releasePlayer();
            targetPage.setAweme(items.get(target), false);
            slotIndex[newSlot] = target;
        }
        index = target;
        curSlot = newSlot;

        // 立刻开播目标视频（若已预热首帧则 0ms 无感起播）
        targetPage.play();

        final float h = getHeight() > 0 ? getHeight() : Ui.dp(this, 720);
        final float offset = dir == 0 ? 0f : (dir > 0 ? h : -h);
        targetPage.setTranslationY(offset);
        targetPage.setAlpha(1f);
        targetPage.setVisibility(VISIBLE);
        bringChildToFront(targetPage);
        if (menu != null) bringChildToFront(menu);

        if (dir == 0) {
            oldPage.releasePlayer();
            oldPage.setVisibility(GONE);
            oldPage.setTranslationY(0f);
            slotIndex[oldSlot] = -1;
            targetPage.setTranslationY(0f);
            transitioning = false;
            schedulePreloadAround(index);
            if (items.size() - 1 - index <= 5 && host != null) host.onNearEnd();
            return;
        }

        final boolean animateOld = oldPage.getVisibility() == VISIBLE;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            float inY = offset * (1f - f);
            targetPage.setTranslationY(inY);
            if (animateOld) oldPage.setTranslationY(inY - offset);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (slideAnimator == animation) slideAnimator = null;
                targetPage.setTranslationY(0f);
                oldPage.releasePlayer();
                oldPage.setVisibility(GONE);
                oldPage.setTranslationY(0f);
                slotIndex[oldSlot] = -1;
                transitioning = false;
                if (token == switchToken && App.get().store().preload() && index + 1 < items.size()) {
                    schedulePreloadAround(index);
                }
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                if (slideAnimator == animation) slideAnimator = null;
                transitioning = false;
            }
        });
        slideAnimator = anim;
        anim.start();
        if (items.size() - 1 - index <= 5 && host != null) host.onNearEnd();
    }

    public void addItems(List<Aweme> more) {
        if (more == null || more.isEmpty()) return;
        for (Aweme a : more) {
            if (a == null || a.id == null) continue;
            boolean dup = false;
            for (int i = Math.max(0, items.size() - 30); i < items.size(); i++) {
                if (items.get(i).id.equals(a.id)) { dup = true; break; }
            }
            if (!dup) items.add(a);
        }
        if (index < 0 && !items.isEmpty()) showAt(0);
        else if (!advanceWhenReady() && index >= 0 && App.get().store().preload()
                && index + 1 < items.size()) {
            schedulePreloadAround(index);
        }
        if (index >= 0 && items.size() - 1 - index <= 5 && host != null) host.onNearEnd();
    }

    public void showItems(List<Aweme> initial, int start) {
        clearItems();
        playbackEnabled = true;
        consecutiveErrors = 0;
        cancelErrorSkip();
        if (initial == null || initial.isEmpty()) return;
        for (Aweme a : initial) {
            if (a != null && a.id != null) items.add(a);
        }
        if (!items.isEmpty()) showAt(Math.max(0, Math.min(start, items.size() - 1)));
    }

    public void clearItems() {
        stop();
        cancelSlideTransition();
        cancelErrorSkip();
        waitingForNext = -1;
        for (VideoPage page : pages) {
            page.animate().withEndAction(null).cancel();
            page.releasePlayer();
            page.setVisibility(GONE);
        }
        items.clear();
        slotIndex[0] = slotIndex[1] = -1;
        index = -1;
        curSlot = 0;
        transitioning = false;
    }

    private boolean advanceWhenReady() {
        if (waitingForNext < 0 || waitingForNext != index || transitioning || overlayOpen()
                || !playbackEnabled || !App.get().store().autoNext()
                || index + 1 >= items.size()) return false;
        transitionTo(index + 1, 1);
        return true;
    }

    public void showAt(int i) {
        if (items.isEmpty()) return;
        i = Math.max(0, Math.min(items.size() - 1, i));
        index = i;
        waitingForNext = -1;
        transitioning = false;
        curSlot = 0;
        slotIndex[0] = i;
        slotIndex[1] = -1;
        pages[1].releasePlayer();
        pages[1].setVisibility(GONE);
        pages[1].setTranslationY(0);
        pages[1].setAlpha(1f);

        pages[0].releasePlayer();
        pages[0].setVisibility(VISIBLE);
        bringChildToFront(pages[0]);
        pages[0].setTranslationY(0);
        pages[0].setAlpha(1f);
        pages[0].setAweme(items.get(i), playbackEnabled);
        schedulePreloadAround(i);
    }

    private void preload(int slot, int at) {
        VideoPage p = pages[slot];
        if (at >= 0 && at < items.size()) {
            boolean fresh = slotIndex[slot] != at;
            if (fresh) {
                p.setAweme(items.get(at), false);
                slotIndex[slot] = at;
            }
            if (VideoPage.canUseStandbyDecoder()) {
                // 保持在视口内 (translationY=0) 且位于当前播放页下方被完全遮挡，
                // 让 TextureView 立刻分配 SurfaceTexture 并完成首帧解码，切入时 0ms 直出真画面。
                p.setTranslationY(0f);
                p.setAlpha(1f);
                p.setVisibility(VISIBLE);
                p.prepareStandby();
            } else {
                p.setVisibility(INVISIBLE);
            }
        } else {
            slotIndex[slot] = -1;
            p.setVisibility(GONE);
            p.releasePlayer();
        }
    }

    // ------------------------------------------------------------ like

    private void toggleLike() {
        final Aweme a = current();
        if (a == null) return;
        android.util.Log.d("dytv-actions", "toggleLike " + a.id + " digged=" + a.userDigged);
        if (!App.get().api().loggedIn()) {
            if (host != null) host.onLoginRequired();
            return;
        }
        if (!pendingLikes.add(a.id)) return;
        final boolean target = a.userDigged != 1;
        if (target) heartBurst();
        final long oldCount = a.diggCount;
        a.userDigged = target ? 1 : 0;
        a.diggCount = Math.max(0, a.diggCount + (target ? 1 : -1));
        pages[curSlot].setDigg(a.diggCount, target);
        Async.run(new Async.Fn<Boolean>() {
            @Override public Boolean run() {
                return App.get().api().like(a.id, target);
            }
        }, new Async.Cb<Boolean>() {
            @Override public void done(Boolean value, Exception error) {
                pendingLikes.remove(a.id);
                if (!Boolean.TRUE.equals(value)) {
                    a.userDigged = target ? 0 : 1;
                    a.diggCount = oldCount;
                    if (isAttachedToWindow() && current() == a) {
                        pages[curSlot].setDigg(a.diggCount, a.userDigged == 1);
                        String reason = App.get().api().lastLikeError();
                        if (App.get().api().lastLikeAuthError()) {
                            flash(reason.isEmpty() ? "登录状态已失效，请重新扫码登录" : reason);
                        } else if (!reason.isEmpty()) {
                            flash((target ? "点赞失败：" : "取消点赞失败：") + reason);
                        } else {
                            flash(target ? "点赞失败，请稍后重试" : "取消点赞失败，请稍后重试");
                        }
                    }
                } else if (isAttachedToWindow() && current() == a) {
                    if (pages[curSlot].isChromeVisible()) pages[curSlot].resetChromeTimeout();
                    flash(target ? "已点赞" : "已取消点赞");
                }
            }
        });
    }

    private void heartBurst() {
        ink.pengfei.douyin.ui.widget.LikeBurstView.playIn(this);
    }

    private void cancelErrorSkip() {
        if (pendingErrorSkip != null) {
            handler.removeCallbacks(pendingErrorSkip);
            pendingErrorSkip = null;
        }
    }

    private void handlePlaybackError(final Aweme a) {
        if (a == null) return;
        if (host != null) host.onPlaybackError(a);
        final int errIdx = items.indexOf(a);
        if (errIdx < 0) return;
        if (errIdx != index) {
            items.remove(errIdx);
            if (errIdx < index) index--;
            slotIndex[curSlot] = index;
            if (items.size() - 1 - Math.max(0, index) <= 4 && host != null) host.onNearEnd();
            return;
        }
        if (consecutiveErrors >= 2 || items.size() <= 1) {
            flash("当前视频暂时无法播放，请按 ↑ ↓ 切换视频");
            if (items.size() <= 1 && host != null) host.onNearEnd();
            return;
        }
        consecutiveErrors++;
        flash("该视频无法播放，即将跳过");
        cancelErrorSkip();
        pendingErrorSkip = () -> {
            pendingErrorSkip = null;
            if (!isAttachedToWindow()) return;
            int idx = items.indexOf(a);
            if (idx < 0 || idx != index) return;
            items.remove(idx);
            slotIndex[0] = slotIndex[1] = -1;
            if (!items.isEmpty()) {
                showAt(Math.min(idx, items.size() - 1));
            } else {
                index = -1;
                if (host != null) host.onNearEnd();
            }
            if (items.size() - 1 - Math.max(0, index) <= 4 && host != null) host.onNearEnd();
        };
        handler.postDelayed(pendingErrorSkip, 450);
    }

    public void flash(String msg) {
        Ui.notice(this, msg);
    }

    private void showSeekHud(int dir, long pos) {
        if (seekHud == null) {
            seekHud = Ui.bold(getContext(), "", 15, 0xFFFFFFFF);
            seekHud.setGravity(Gravity.CENTER);
            seekHud.setBackground(Ui.round(getContext(), 0xEB1C1C1F, 24));
            seekHud.setPadding(Ui.dp(getContext(), 22), Ui.dp(getContext(), 11),
                    Ui.dp(getContext(), 22), Ui.dp(getContext(), 11));
            LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.bottomMargin = Ui.dp(getContext(), 48);
            addView(seekHud, p);
        }
        seekHud.setText((dir < 0 ? "« 快退" : "快进 »") + "   " + VideoPage.fmt(pos));
        seekHud.setVisibility(VISIBLE);
        if (menu != null) bringChildToFront(menu);
    }

    private void hideSeekHud() {
        if (seekHud != null) seekHud.setVisibility(GONE);
    }

    // ------------------------------------------------------------ menu

    private void openMenu() {
        final Aweme a = current();
        if (a == null || overlayOpen()) return;
        menuAweme = a;
        resumeAfterPanel = pages[curSlot].wantsPlayback();
        pages[curSlot].pause();

        menu = new FrameLayout(getContext());
        menu.setBackgroundColor(0x99000000);
        menu.setClickable(true);
        menu.setOnClickListener(v -> closeMenu(true));

        LinearLayout panel = Ui.column(getContext());
        panel.setBackground(Ui.roundStroke(getContext(), Ui.SURFACE, 20, Ui.SEPARATOR, 1));
        Ui.padding(panel, 26, 22, 26, 20);
        panel.setClickable(true);

        LinearLayout titleRow = Ui.row(getContext());
        TextView title = Ui.bold(getContext(), "视频操作", 21, Ui.TEXT);
        titleRow.addView(title);
        panel.addView(titleRow);

        String text = a.desc == null || a.desc.isEmpty() ? "抖音视频" : a.desc;
        TextView sub = Ui.ellipsis(getContext(), text, 12, Ui.TEXT_3, 1);
        sub.setPadding(0, Ui.dp(getContext(), 6), 0, Ui.dp(getContext(), 18));
        panel.addView(sub);

        boolean liked = a.userDigged == 1;
        String[] entries = new String[]{"作者主页", "分享给好友", liked ? "取消点赞" : "点赞",
                "播放设置", "弹幕设置", "从头播放"};
        int[] icons = new int[]{IconView.USER, IconView.SHARE, IconView.HEART,
                IconView.SETTING, IconView.DANMAKU, IconView.REFRESH};

        LinearLayout row = null;
        List<TextView> options = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            final int which = i;
            if (i % 3 == 0) {
                row = Ui.row(getContext());
                row.setClipChildren(false);
                panel.addView(row, Ui.margin(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, i == 0 ? 0 : 10, 0, 0));
            }
            final TextView b = menuOption(entries[i], icons[i], i == 2 && liked);
            b.setOnClickListener(v -> activateMenu(which));
            options.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(getContext(), 52), 1f);
            lp.leftMargin = i % 3 == 0 ? 0 : Ui.dp(getContext(), 10);
            row.addView(b, lp);
        }

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(getContext(), 760), getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 120)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pp.bottomMargin = Ui.dp(getContext(), 48);
        menu.addView(panel, pp);
        addView(menu, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        Ui.enter(panel, 0, 24, 200);
        options.get(0).requestFocus();
        seekHudVisibility(false);
    }

    private TextView menuOption(String label, int iconType, boolean marked) {
        final TextView b = Ui.action(getContext(), label, iconType, false);
        b.setOnFocusChangeListener(null);
        final Runnable paint = new Runnable() {
            @Override public void run() {
                Ui.tile(b, b.hasFocus(), marked, 14);
            }
        };
        b.setOnFocusChangeListener((v, has) -> paint.run());
        paint.run();
        return b;
    }

    private void activateMenu(int which) {
        Aweme a = menuAweme;
        switch (which) {
            case 0:
                closeMenu(false);
                if (a != null && host != null) host.onOpenProfile(a);
                break;
            case 1:
                closeMenu(true);
                shareCurrent();
                break;
            case 2:
                closeMenu(true);
                toggleLike();
                break;
            case 3:
                closeMenu(true);
                openPlaybackSettings(0);
                break;
            case 4:
                closeMenu(true);
                PlayerOverlays.openDanmakuSettings((android.app.Activity) getContext(),
                        null, this::refreshDanmakuSettings, this::requestFocus);
                break;
            case 5:
                closeMenu(false);
                waitingForNext = -1;
                VideoPage page = pages[curSlot];
                if (page.seekBy(-page.position()) < 0) page.setAweme(a, true);
                else page.play();
                break;
            default:
                closeMenu(true);
        }
    }

    private void closeMenu(boolean resume) {
        if (menu == null) return;
        removeView(menu);
        menu = null;
        longMenuOpened = false;
        seekHudVisibility(true);
        requestFocus();
        if (resume && resumeAfterPanel && !advanceWhenReady()) pages[curSlot].play();
    }

    /** 合并后的播放与弹幕设置弹窗：一级菜单展示当前值，进入某项后在二级菜单里单选。 */
    private void openPlaybackSettings(int focusIndex) {
        final android.app.Activity act = (android.app.Activity) getContext();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final boolean auto = App.get().store().autoNext();
        final String[] qualities = {"auto", "1080p", "720p", "540p"};
        final String[] qLabels = {"自动", "1080p", "720p", "540p"};
        final int curQ = Math.max(0, java.util.Arrays.asList(qualities).indexOf(App.get().store().quality()));
        final int curRot = pages[curSlot] == null ? 0 : pages[curSlot].getRotationDegrees();
        int rotIdx = 0;
        for (int i = 0; i < VideoPage.ROTATIONS.length; i++) {
            if (VideoPage.ROTATIONS[i] == curRot) { rotIdx = i; break; }
        }
        final int curRotIdx = rotIdx;
        final boolean dmOn = DanmakuView.enabled();
        final String[] dmAlphas = new String[DanmakuView.ALPHAS.length];
        for (int i = 0; i < DanmakuView.ALPHAS.length; i++) dmAlphas[i] = DanmakuView.ALPHAS[i] + "%";
        String[] rows = {
                "自动连播\t" + (auto ? "开启" : "关闭"),
                "播放倍速\t" + VideoPage.speedLabel(),
                "清晰度\t" + qLabels[curQ],
                "画面旋转\t" + VideoPage.rotationLabel(curRot),
                "弹幕显示\t" + (dmOn ? "开启" : "关闭"),
                "弹幕速度\t" + DanmakuView.SPEED_LABELS[DanmakuView.speedIndex()],
                "弹幕字号\t" + DanmakuView.SIZE_LABELS[DanmakuView.sizeIndex()],
                "弹幕不透明度\t" + dmAlphas[DanmakuView.alphaIndex()]
        };
        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "播放与弹幕设置", null, rows, -1, focusIndex,
                null, idx -> {
                    if (idx == 0) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "自动连播", null,
                                new String[]{"开启", "关闭"}, auto ? 0 : 1, auto ? 0 : 1, null, k -> {
                                    boolean nextAuto = k == 0;
                                    App.get().store().setAutoNext(nextAuto);
                                    if (!nextAuto) waitingForNext = -1;
                                    for (VideoPage p : pages) p.applyRepeatSetting();
                                    openPlaybackSettings(0);
                                }, () -> openPlaybackSettings(0));
                    } else if (idx == 1) {
                        int cur = VideoPage.speedIndex();
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "播放倍速", null,
                                VideoPage.SPEED_LABELS, cur, cur, null, k -> {
                                    App.get().store().setSpeedIndex(k);
                                    refreshPlaybackSettings();
                                    openPlaybackSettings(1);
                                }, () -> openPlaybackSettings(1));
                    } else if (idx == 2) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "清晰度", null,
                                qLabels, curQ, curQ, null, k -> {
                                    if (k != curQ) {
                                        App.get().store().setQuality(qualities[k]);
                                        pages[curSlot].reloadQuality();
                                        pages[1 - curSlot].releasePlayer();
                                        slotIndex[1 - curSlot] = -1;
                                    }
                                    openPlaybackSettings(2);
                                }, () -> openPlaybackSettings(2));
                    } else if (idx == 3) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "画面旋转", null,
                                VideoPage.ROTATION_LABELS, curRotIdx, curRotIdx, null, k -> {
                                    if (pages[curSlot] != null) {
                                        pages[curSlot].setRotationDegrees(VideoPage.ROTATIONS[k]);
                                    }
                                    openPlaybackSettings(3);
                                }, () -> openPlaybackSettings(3));
                    } else if (idx == 4) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "弹幕开关", null,
                                new String[]{"开启", "关闭"}, dmOn ? 0 : 1, dmOn ? 0 : 1, null, k -> {
                                    App.get().store().setDanmakuOn(k == 0);
                                    refreshDanmakuSettings();
                                    openPlaybackSettings(4);
                                }, () -> openPlaybackSettings(4));
                    } else if (idx == 5) {
                        int cur = DanmakuView.speedIndex();
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "弹幕速度", null,
                                DanmakuView.SPEED_LABELS, cur, cur, null, k -> {
                                    App.get().store().setDanmakuSpeed(k);
                                    refreshDanmakuSettings();
                                    openPlaybackSettings(5);
                                }, () -> openPlaybackSettings(5));
                    } else if (idx == 6) {
                        int cur = DanmakuView.sizeIndex();
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "弹幕字号", null,
                                DanmakuView.SIZE_LABELS, cur, cur, null, k -> {
                                    App.get().store().setDanmakuSize(k);
                                    refreshDanmakuSettings();
                                    openPlaybackSettings(6);
                                }, () -> openPlaybackSettings(6));
                    } else {
                        int cur = DanmakuView.alphaIndex();
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "弹幕不透明度", null,
                                dmAlphas, cur, cur, null, k -> {
                                    App.get().store().setDanmakuAlpha(k);
                                    refreshDanmakuSettings();
                                    openPlaybackSettings(7);
                                }, () -> openPlaybackSettings(7));
                    }
                }, this::requestFocus);
    }

    private void seekHudVisibility(boolean visible) {
        if (seekHud != null && !visible) seekHud.setVisibility(GONE);
    }

    // ------------------------------------------------------------ share

    private void shareCurrent() {
        Aweme a = current();
        if (a == null || overlayOpen()) return;
        if (menu != null) closeMenu(false);
        if (host != null) host.onShare(a);
    }

    // ------------------------------------------------------------ lifecycle

    public void stop() {
        boolean wasEnabled = playbackEnabled;
        playbackEnabled = false;
        cancelPendingPreload();
        cancelPendingControls();
        cancelSlideTransition();
        VideoCache.cancelPreload();
        Aweme a = current();
        if (wasEnabled && a != null && pages[curSlot] != null) {
            long pos = pages[curSlot].position();
            App.get().store().addRecent(a);
            if (host != null) host.onProgressSave(a, pos);
        }
        if (pages[curSlot] != null) pages[curSlot].saveAndReleasePlayer();
        int standbySlot = 1 - curSlot;
        pages[standbySlot].setStandby(true);
        pages[standbySlot].setVisibility(GONE);
        pages[standbySlot].setTranslationY(0f);
        slotIndex[standbySlot] = -1;
    }

    public void play() {
        playbackEnabled = true;
        if (advanceWhenReady()) return;
        if (waitingForNext == index && index >= 0 && App.get().store().autoNext()) {
            if (host != null) host.onNearEnd();
        } else if (index >= 0 && index < items.size()) {
            pages[curSlot].play();
        }
        if (App.get().store().preload() && index >= 0 && index + 1 < items.size()) {
            schedulePreloadAround(index);
        } else {
            preload(1 - curSlot, -1);
        }
    }

    public void pause() {
        if (index >= 0 && index < items.size()) pages[curSlot].pause();
    }

    public void refreshDanmakuSettings() {
        for (VideoPage p : pages) p.refreshDanmakuSettings();
    }

    public void refreshCurrentMeta(Aweme updated) {
        if (updated == null) return;
        Aweme cur = current();
        if (cur != null && cur.id.equals(updated.id) && pages[curSlot] != null) {
            cur.userDigged = updated.userDigged;
            cur.diggCount = updated.diggCount;
            cur.commentCount = updated.commentCount;
            cur.shareCount = updated.shareCount;
            pages[curSlot].setDigg(cur.diggCount, cur.userDigged == 1);
        }
    }

    public void refreshPlaybackSettings() {
        for (VideoPage p : pages) {
            p.applySpeedSetting();
            p.applyRepeatSetting();
        }
    }

    public void destroy() {
        cancelPendingPreload();
        cancelPendingControls();
        cancelSlideTransition();
        VideoCache.cancelPreload();
        handler.removeCallbacksAndMessages(null);
        for (VideoPage p : pages) p.animate().withEndAction(null).cancel();
        for (VideoPage p : pages) p.releaseAll();
    }

    private void cancelPendingControls() {
        stopSeeking();
        hideSeekHud();
        removeCallbacks(tapToggle);
        lastTapAt = 0;
    }
}
