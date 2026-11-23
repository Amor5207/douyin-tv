package ink.pengfei.douyin.ui.page;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.model.LiveRoom;
import ink.pengfei.douyin.ui.LiveFeedView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.util.Async;

import java.util.ArrayList;
import java.util.List;

/** 直播页：整屏竖向直播流，关注优先、推荐在后，上下切换、自动续页。 */
public class LivePage extends ShellPage implements LiveFeedView.Host {
    private static final long LONG_LOADING_DELAY_MS = 1500L;

    private final ShellHost host;
    private final LiveFeedView feed;
    private final FrameLayout loadingLayer;
    private final ProgressBar loadingBar;
    private final TextView loadingText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable delayedLoadingBar = new Runnable() {
        @Override public void run() {
            if (loading && loadingBar != null) loadingBar.setVisibility(View.VISIBLE);
        }
    };
    private final Runnable deferredPlay = new Runnable() {
        @Override public void run() {
            if (active && !feed.rooms().isEmpty()) feed.play();
        }
    };
    private boolean loading;
    private boolean loadingMore;
    private boolean loaded;
    private boolean noMore;
    private boolean active;
    private String errorMessage = "";

    public LivePage(Context c, ShellHost host) {
        super(c);
        this.host = host;
        setBackgroundColor(Color.BLACK);

        feed = new LiveFeedView(c);
        feed.setHost(this);
        addView(feed, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        loadingLayer = new FrameLayout(c);
        loadingLayer.setClickable(true);
        LinearLayoutBox box = new LinearLayoutBox(c);
        ProgressBar bar = new ProgressBar(c);
        loadingBar = bar;
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        box.addView(bar, Ui.lp(Ui.dp(c, 34), Ui.dp(c, 34)));
        loadingText = Ui.text(c, "", 13, Ui.TEXT_3, false);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, Ui.dp(c, 14), 0, 0);
        loadingText.setVisibility(View.GONE);
        box.addView(loadingText, Ui.lp(Ui.dp(c, 360), LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        loadingLayer.addView(box, bp);
        addView(loadingLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static final class LinearLayoutBox extends android.widget.LinearLayout {
        LinearLayoutBox(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
        }
    }

    @Override
    public void preload() {
        if (!loaded && !loading) loadFirst();
    }

    @Override
    public void onShow() {
        active = true;
        handler.removeCallbacks(deferredPlay);
        if ((!loaded || feed.rooms().isEmpty()) && !loading) loadFirst();
        else if (!feed.rooms().isEmpty()) handler.postDelayed(deferredPlay, 80L);
    }

    @Override
    public void onHide() {
        active = false;
        handler.removeCallbacks(deferredPlay);
        feed.stop();
    }

    @Override
    public void onDestroy() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        feed.release();
    }

    @Override
    public void focusFirst() {
        if (!feed.rooms().isEmpty()) {
            feed.requestFocus();
        } else {
            requestFocus();
        }
    }

    public void showInfo(boolean autoHide) {
        if (feed != null) feed.showInfo(autoHide);
    }

    public void dismissInfoIfOpen(boolean restoreFocus) {
        if (feed != null) feed.dismissInfoIfOpen(restoreFocus);
    }

    @Override
    public boolean onBack() {
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(delayedLoadingBar);
        handler.removeCallbacks(deferredPlay);
    }

    private void loadFirst() {
        loading = true;
        feed.setVisibility(View.INVISIBLE);
        errorMessage = "";
        loadingLayer.setVisibility(View.VISIBLE);
        loadingBar.setVisibility(View.GONE);
        handler.removeCallbacks(delayedLoadingBar);
        handler.postDelayed(delayedLoadingBar, LONG_LOADING_DELAY_MS);
        loadingText.setText("");
        loadingText.setVisibility(View.GONE);
        feed.setRooms(new ArrayList<>());
        App.get().api().resetLiveOffsets();
        Async.run(new Async.Fn<List<LiveRoom>>() {
            @Override public List<LiveRoom> run() throws Exception {
                List<LiveRoom> out = new ArrayList<>();
                try {
                    out.addAll(App.get().api().liveFeed(12));
                } catch (Exception e) {
                    if (!App.get().api().loggedIn()) throw e;
                }
                if (out.isEmpty() && App.get().api().loggedIn()) {
                    try {
                        out.addAll(App.get().api().liveFollowing(10));
                    } catch (Exception ignored) {}
                }
                return dedupe(out);
            }
        }, new Async.Cb<List<LiveRoom>>() {
            @Override public void done(List<LiveRoom> list, Exception error) {
                loading = false;
                loaded = true;
                handler.removeCallbacks(delayedLoadingBar);
                if (list == null || list.isEmpty()) {
                    loadingLayer.setVisibility(View.GONE);
                    errorMessage = error != null ? "直播加载失败，按 OK 重试" : "暂时没有正在直播的房间，按 OK 重试";
                    showError(errorMessage);
                    return;
                }
                loadingBar.setVisibility(View.GONE);
                Ui.fadeOut(loadingLayer, 200);
                feed.setVisibility(View.VISIBLE);
                feed.setRooms(list, active);
                if (!active) {
                    return;
                } else if (hasFocus()) {
                    feed.requestFocus();
                    feed.showInfo(true);
                } else {
                    feed.showInfo(false);
                }
            }
        });
    }

    private void loadMore() {
        if (loading || loadingMore || noMore) return;
        loadingMore = true;
        Async.run(new Async.Fn<List<LiveRoom>>() {
            @Override public List<LiveRoom> run() throws Exception {
                return App.get().api().liveFeed(12);
            }
        }, new Async.Cb<List<LiveRoom>>() {
            @Override public void done(List<LiveRoom> list, Exception error) {
                loadingMore = false;
                if (list == null || list.isEmpty()) {
                    noMore = true;
                    feed.flash("没有更多直播了");
                    return;
                }
                feed.addRooms(list);
            }
        });
    }

    private static List<LiveRoom> dedupe(List<LiveRoom> list) {
        List<LiveRoom> out = new ArrayList<>();
        for (LiveRoom r : list) {
            if (r == null || r.id == null) continue;
            boolean dup = false;
            for (LiveRoom old : out) {
                if (old.id.equals(r.id)) { dup = true; break; }
            }
            if (!dup) out.add(r);
        }
        return out;
    }

    private void showError(String msg) {
        feed.setVisibility(View.GONE);
        if (loadingBar != null) loadingBar.setVisibility(View.GONE);
        if (loadingText != null) {
            loadingLayer.setVisibility(View.VISIBLE);
            loadingText.setText(msg);
            loadingText.setVisibility(View.VISIBLE);
        }
        if (active && hasFocus()) requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean control = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER
                || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (control && !feed.rooms().isEmpty()) {
            return feed.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            if (feed.rooms().isEmpty() && !loading) {
                loadFirst();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onNearEnd() {
        loadMore();
    }

    @Override
    public void onTabStep(int dir) {
        host.onTabStep(dir);
    }

    @Override
    public boolean isFullscreen() {
        return host != null && host.isFullscreen();
    }

    @Override
    public void onToggleFullscreen() {
        if (host != null) host.onToggleFullscreen();
    }

    @Override
    public void onChromeVisibilityChanged(boolean visible) {
        if (host != null) host.onChromeVisibilityChanged(visible);
    }

    public void setFullscreen(boolean full) {
        feed.setFullscreen(full);
    }

    public void refreshDanmakuSettings() {
        feed.refreshDanmakuSettings();
    }
}
