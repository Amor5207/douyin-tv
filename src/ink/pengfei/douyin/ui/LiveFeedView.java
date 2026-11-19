package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import ink.pengfei.douyin.model.LiveRoom;

import java.util.ArrayList;
import java.util.List;

/** 全屏直播流：上下切换直播间，右侧预加载，接近末尾自动续页。 */
public class LiveFeedView extends android.widget.FrameLayout {
    public interface Host {
        void onNearEnd();
        void onTabStep(int dir);
        boolean isFullscreen();
        void onToggleFullscreen();
        default void onChromeVisibilityChanged(boolean visible) {}
    }

    private final List<LiveRoom> rooms = new ArrayList<>();
    private final LivePageView[] pages = new LivePageView[2];
    private final int[] slotIndex = {-1, -1};
    private final FrameLayout outgoingFrame;
    private final android.widget.ImageView outgoingImage;
    private android.graphics.Bitmap outgoingBitmap;
    private android.animation.ValueAnimator slideAnimator;
    private int switchToken;
    private int curSlot;
    private int index = -1;
    private boolean transitioning;
    private Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downX, downY;
    private boolean tapCandidate;
    private long lastTapAt;
    private boolean swallowOkUp;
    private int consecutiveErrors;
    private Runnable pendingErrorSkip;

    private final Runnable tapToggle = new Runnable() {
        @Override public void run() {
            togglePlay();
        }
    };

    public LiveFeedView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);
        for (int i = 0; i < 2; i++) {
            pages[i] = new LivePageView(c);
            pages[i].setVisibility(GONE);
            pages[i].setActions(new LivePageView.Actions() {
                @Override public void onPlayPause() { togglePlay(); }
                @Override public void onPlaybackSettings() {
                    if (current() != null) openPlaybackSettings(0);
                }
                @Override public void onToggleFullscreen() {
                    if (host != null) host.onToggleFullscreen();
                }
                @Override public void onExitToNavigation() {
                    if (host != null) host.onTabStep(-1);
                }
                @Override public void onLiveUnavailable(LiveRoom room) {
                    handleLiveUnavailable(room);
                }
                @Override public void onLivePlaying() {
                    consecutiveErrors = 0;
                }
                @Override public void onChromeHidden() {
                    requestFocus();
                }
                @Override public void onChromeVisibilityChanged(boolean visible) {
                    if (host != null) host.onChromeVisibilityChanged(visible);
                }
            });
            addView(pages[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        pages[0].setVisibility(VISIBLE);
        outgoingFrame = new FrameLayout(c);
        outgoingFrame.setBackgroundColor(Color.BLACK);
        outgoingFrame.setVisibility(GONE);
        outgoingImage = new android.widget.ImageView(c);
        outgoingImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        outgoingFrame.addView(outgoingImage, new LayoutParams(-1, -1, android.view.Gravity.CENTER));
        addView(outgoingFrame, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOnTouchListener((v, e) -> handleTouch(e));
    }

    public void setHost(Host h) {
        host = h;
        boolean full = h != null && h.isFullscreen();
        for (LivePageView p : pages) p.setFullscreen(full);
    }

    public void setFullscreen(boolean full) {
        for (LivePageView p : pages) p.setFullscreen(full);
        if (full && pages[curSlot] != null) pages[curSlot].hideInfoImmediately();
    }

    public void refreshDanmakuSettings() {
        for (LivePageView p : pages) p.applyDanmakuSettings();
    }

    public List<LiveRoom> rooms() {
        return rooms;
    }

    public boolean dismissInfoIfOpen() {
        LivePageView cur = pages[curSlot];
        if (cur != null && cur.isInfoVisible()) {
            cur.hideInfoImmediately();
            requestFocus();
            return true;
        }
        return false;
    }

    public LiveRoom current() {
        return index >= 0 && index < rooms.size() ? rooms.get(index) : null;
    }

    public void setRooms(List<LiveRoom> list) {
        setRooms(list, true);
    }

    public void setRooms(List<LiveRoom> list, boolean autoplay) {
        consecutiveErrors = 0;
        if (pendingErrorSkip != null) {
            handler.removeCallbacks(pendingErrorSkip);
            pendingErrorSkip = null;
        }
        rooms.clear();
        if (list != null) rooms.addAll(list);
        if (rooms.isEmpty()) {
            index = -1;
            pages[0].setRoom(null, false);
            pages[1].setRoom(null, false);
            return;
        }
        index = 0;
        transitioning = false;
        curSlot = 0;
        slotIndex[0] = 0;
        slotIndex[1] = -1;
        pages[1].setRoom(null, false);
        pages[1].setVisibility(GONE);
        pages[1].setTranslationY(0);
        pages[0].setVisibility(VISIBLE);
        pages[0].setTranslationY(0);
        pages[0].setRoom(rooms.get(0), autoplay);
        for (int i = 0; i < Math.min(3, rooms.size()); i++) {
            LiveRoom r = rooms.get(i);
            String u = (r.cover != null && !r.cover.isEmpty()) ? r.cover : r.avatar;
            if (u != null && !u.isEmpty()) ink.pengfei.douyin.util.Images.preload(u, 1280);
        }
    }

    public void addRooms(List<LiveRoom> more) {
        if (more == null || more.isEmpty()) return;
        for (LiveRoom r : more) {
            if (r == null || r.id == null) continue;
            boolean dup = false;
            for (LiveRoom old : rooms) {
                if (old.id.equals(r.id)) { dup = true; break; }
            }
            if (!dup) rooms.add(r);
        }
    }

    private void clearOutgoingFrame() {
        if (outgoingFrame != null) {
            outgoingFrame.animate().cancel();
            outgoingFrame.setVisibility(GONE);
            outgoingFrame.setTranslationY(0f);
        }
        if (outgoingImage != null) outgoingImage.setImageDrawable(null);
        if (outgoingBitmap != null) {
            if (!outgoingBitmap.isRecycled()) outgoingBitmap.recycle();
            outgoingBitmap = null;
        }
    }

    private void cancelSlideTransition() {
        if (slideAnimator != null) {
            slideAnimator.cancel();
            slideAnimator = null;
        }
        for (LivePageView p : pages) p.setTranslationY(0f);
        if (outgoingFrame != null) outgoingFrame.setTranslationY(0f);
    }

    private void navigate(int target, int dir) {
        if (transitioning || rooms.isEmpty()) return;
        if (target < 0) {
            flash("已经是第一个直播");
            return;
        }
        if (target >= rooms.size()) {
            flash("正在加载更多直播…");
            if (host != null) host.onNearEnd();
            return;
        }
        consecutiveErrors = 0;
        if (pendingErrorSkip != null) {
            handler.removeCallbacks(pendingErrorSkip);
            pendingErrorSkip = null;
        }
        transitioning = true;
        cancelSlideTransition();
        final int token = ++switchToken;
        final int oldSlot = curSlot;
        final int newSlot = 1 - curSlot;
        final LivePageView oldPage = pages[oldSlot];
        oldPage.animate().withEndAction(null).cancel();
        oldPage.hideInfoImmediately(false);
        oldPage.pauseSilently();
        clearOutgoingFrame();
        beginNavigateSlide(token, oldSlot, newSlot, target, dir);
    }

    private void beginNavigateSlide(final int token, final int oldSlot, final int newSlot,
                                    final int target, final int dir) {
        if (token != switchToken || !isAttachedToWindow()) {
            transitioning = false;
            return;
        }
        final LivePageView oldPage = pages[oldSlot];
        oldPage.setRoom(null, false);
        oldPage.setVisibility(GONE);

        final LivePageView targetPage = pages[newSlot];
        targetPage.animate().withEndAction(null).cancel();
        targetPage.setRoom(rooms.get(target), true);
        slotIndex[newSlot] = target;
        index = target;
        curSlot = newSlot;
        for (int k : new int[]{target - 1, target + 1, target + 2}) {
            if (k >= 0 && k < rooms.size()) {
                LiveRoom r = rooms.get(k);
                String u = (r.cover != null && !r.cover.isEmpty()) ? r.cover : r.avatar;
                if (u != null && !u.isEmpty()) ink.pengfei.douyin.util.Images.preload(u, 1280);
            }
        }

        final float h = getHeight() > 0 ? getHeight() : Ui.dp(this, 720);
        final float offset = dir > 0 ? h : -h;
        targetPage.setVisibility(VISIBLE);
        targetPage.setAlpha(1f);
        targetPage.setTranslationY(offset);
        if (outgoingFrame.getVisibility() == VISIBLE) bringChildToFront(outgoingFrame);
        bringChildToFront(targetPage);
        targetPage.play();

        final boolean hasOutgoing = outgoingFrame.getVisibility() == VISIBLE;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            float inY = offset * (1f - f);
            targetPage.setTranslationY(inY);
            if (hasOutgoing) outgoingFrame.setTranslationY(inY - offset);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (slideAnimator == animation) slideAnimator = null;
                targetPage.setTranslationY(0f);
                clearOutgoingFrame();
                slotIndex[oldSlot] = -1;
                transitioning = false;
                if (index >= rooms.size() - 2 && host != null) host.onNearEnd();
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                if (slideAnimator == animation) slideAnimator = null;
                transitioning = false;
            }
        });
        slideAnimator = anim;
        anim.start();
    }

    private void togglePlay() {
        LivePageView p = pages[curSlot];
        if (p == null || current() == null) return;
        if (p.isPlaying()) {
            p.pause();
            p.showInfo();
        } else {
            p.play();
            p.showInfo();
        }
    }

    public void play() {
        if (index >= 0 && index < rooms.size()) pages[curSlot].play();
    }

    public void showInfo(boolean autoHide) {
        if (index >= 0 && index < rooms.size()) pages[curSlot].showInfo(autoHide);
    }

    public void dismissInfoIfOpen(boolean restoreFocus) {
        if (index >= 0 && index < rooms.size()) pages[curSlot].hideInfoImmediately(restoreFocus);
    }

    public void stop() {
        cancelSlideTransition();
        clearOutgoingFrame();
        for (LivePageView p : pages) p.pauseSilently();
    }

    public void release() {
        cancelSlideTransition();
        clearOutgoingFrame();
        handler.removeCallbacksAndMessages(null);
        for (LivePageView p : pages) p.release();
    }

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                tapCandidate = true;
                pages[curSlot].showInfo();
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
                if (tapCandidate) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapAt < 300 && lastTapAt > 0) {
                        lastTapAt = 0;
                        removeCallbacks(tapToggle);
                    } else {
                        lastTapAt = now;
                        postDelayed(tapToggle, 240);
                    }
                }
                tapCandidate = false;
                return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (swallowOkUp && (key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                swallowOkUp = false;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) return true;
        }
        LivePageView cur = pages[curSlot];
        if (cur != null && cur.isInfoVisible()) {
            if (cur.handleInfoKey(event)) return true;
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
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getRepeatCount() == 0 && cur != null) {
                        cur.revealControls();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() == 0 && cur != null) {
                        swallowOkUp = true;
                        cur.revealInfo();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (event.getRepeatCount() == 0) togglePlay();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (current() != null && event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            int h = getHeight();
            if (h > 0 && event.getY() >= h * 0.72f) {
                pages[curSlot].showInfo();
            }
        }
        return super.dispatchHoverEvent(event);
    }

    private void openPlaybackSettings(int focusIndex) {
        final android.app.Activity act = (android.app.Activity) getContext();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final String[] qKeys = {"auto", "FULL_HD1", "HD1", "SD1", "SD2"};
        final String[] qLabels = {"自动", "原画", "超清", "高清", "标清"};
        final int curQ = Math.max(0, java.util.Arrays.asList(qKeys).indexOf(ink.pengfei.douyin.App.get().store().liveQuality()));
        final String[] protos = {"HLS 优先", "FLV 优先"};
        final int curP = Math.max(0, Math.min(1, ink.pengfei.douyin.App.get().store().liveProtocol()));
        String[] rows = {
                "直播清晰度\t" + qLabels[curQ],
                "拉流协议\t" + protos[curP],
                "弹幕设置\t" + (DanmakuView.enabled() ? "已开启" : "已关闭"),
                "重连当前直播"
        };
        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "播放与弹幕设置", null, rows, -1, focusIndex,
                null, idx -> {
                    if (idx == 0) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "直播清晰度", null,
                                qLabels, curQ, curQ, null, k -> {
                                    if (k != curQ) {
                                        ink.pengfei.douyin.App.get().store().setLiveQuality(qKeys[k]);
                                        pages[curSlot].reloadStream();
                                    }
                                    openPlaybackSettings(0);
                                }, () -> openPlaybackSettings(0));
                    } else if (idx == 1) {
                        ink.pengfei.douyin.ui.widget.TvDialog.options(act, "拉流协议", null,
                                protos, curP, curP, null, k -> {
                                    if (k != curP) {
                                        ink.pengfei.douyin.App.get().store().setLiveProtocol(k);
                                        pages[curSlot].reloadStream();
                                    }
                                    openPlaybackSettings(1);
                                }, () -> openPlaybackSettings(1));
                    } else if (idx == 2) {
                        PlayerOverlays.openDanmakuSettings(act, null,
                                LiveFeedView.this::refreshDanmakuSettings,
                                () -> openPlaybackSettings(2));
                    } else {
                        pages[curSlot].reloadStream();
                        flash("正在重连直播…");
                        requestFocus();
                    }
                }, this::requestFocus);
    }

    private void handleLiveUnavailable(final LiveRoom r) {
        if (r == null) return;
        int errIdx = rooms.indexOf(r);
        if (errIdx < 0) return;
        if (errIdx != index) {
            rooms.remove(errIdx);
            if (errIdx < index) index--;
            slotIndex[curSlot] = index;
            if (rooms.size() - 1 - Math.max(0, index) <= 2 && host != null) host.onNearEnd();
            return;
        }
        if (consecutiveErrors >= 5 || rooms.size() <= 1) {
            flash("当前直播加载失败或已关闭，请按 ↑ ↓ 切换");
            if (rooms.size() <= 1 && host != null) host.onNearEnd();
            return;
        }
        consecutiveErrors++;
        flash("直播加载失败或已关闭，即将跳过");
        if (pendingErrorSkip != null) handler.removeCallbacks(pendingErrorSkip);
        pendingErrorSkip = () -> {
            pendingErrorSkip = null;
            if (!isAttachedToWindow()) return;
            int idx = rooms.indexOf(r);
            if (idx < 0 || idx != index) return;
            rooms.remove(idx);
            slotIndex[0] = slotIndex[1] = -1;
            transitioning = false;
            if (!rooms.isEmpty()) {
                int next = Math.min(idx, rooms.size() - 1);
                index = next;
                curSlot = 0;
                slotIndex[0] = next;
                pages[0].animate().withEndAction(null).cancel();
                pages[1].animate().withEndAction(null).cancel();
                pages[1].setRoom(null, false);
                pages[1].setVisibility(GONE);
                pages[1].setTranslationY(0f);
                pages[0].setVisibility(VISIBLE);
                pages[0].setTranslationY(0f);
                pages[0].setAlpha(1f);
                bringChildToFront(pages[0]);
                pages[0].setRoom(rooms.get(next), true);
            } else {
                index = -1;
                pages[0].setRoom(null, false);
                pages[1].setRoom(null, false);
                if (host != null) host.onNearEnd();
            }
            if (rooms.size() - 1 - Math.max(0, index) <= 2 && host != null) host.onNearEnd();
        };
        handler.postDelayed(pendingErrorSkip, 900);
    }

    public void flash(String msg) {
        Ui.notice(this, msg);
    }
}
