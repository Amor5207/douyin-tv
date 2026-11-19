package ink.pengfei.douyin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.model.Friend;
import ink.pengfei.douyin.ui.FeedView;
import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.ui.page.LivePage;
import ink.pengfei.douyin.ui.page.MePage;
import ink.pengfei.douyin.ui.page.ShellHost;
import ink.pengfei.douyin.ui.page.ShellPage;
import ink.pengfei.douyin.ui.widget.TvDialog;
import ink.pengfei.douyin.ui.widget.FocusScrollView;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.Store;
import ink.pengfei.douyin.util.Theme;

import java.util.List;

public class MainActivity extends Activity implements FeedView.Host {
    private static final int REQ_LOGIN = 100;
    private static final int TOP_BAR_H = 56;
    private static final String[] TAB_NAMES = {"直播", "推荐", "我的"};
    private static final int TAB_LIVE = 0, TAB_FEED = 1, TAB_ME = 2;

    private FrameLayout root;
    private FrameLayout pageHost;
    private FrameLayout loadingLayer;
    private TextView loadingText;
    private TextView retryBtn;
    private FeedView feed;
    private FrameLayout dockHost;
    private View dockDivider;
    private LinearLayout dockPillGroup;
    private boolean dockOpen;
    private boolean streamChromeVisible;
    private final FrameLayout[] tabViews = new FrameLayout[TAB_NAMES.length];
    private final TextView[] tabLabels = new TextView[TAB_NAMES.length];
    private final View[] tabLines = new View[TAB_NAMES.length];
    private final ShellPage[] pages = new ShellPage[TAB_NAMES.length];
    private final View[] contentFocus = new View[TAB_NAMES.length];
    private int selected = TAB_FEED;
    private boolean switchingTab;
    private boolean tabConfirmDown;
    private boolean otherTabsPreloaded;
    private boolean feedLoading;
    private boolean feedAccountState;
    private ProgressBar loadingSpinner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startupDockAutoHide = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (!dockOpen || (selected != TAB_FEED && selected != TAB_LIVE)) return;
            dockOpen = false;
            if (selected == TAB_FEED && feed != null) {
                feed.dismissChromeIfOpen(false);
            } else if (selected == TAB_LIVE && pages[TAB_LIVE] instanceof LivePage) {
                ((LivePage) pages[TAB_LIVE]).dismissInfoIfOpen(false);
            }
            streamChromeVisible = false;
            updateDockAppearance(true);
            paintTabs();
            if (selected == TAB_FEED) {
                if (retryBtn != null && retryBtn.isShown()) retryBtn.requestFocus();
                else if (feed != null) feed.requestFocus();
            } else if (pages[TAB_LIVE] != null) {
                pages[TAB_LIVE].requestFocus();
            }
        }
    };

    private void scheduleDockAutoHide() {
        handler.removeCallbacks(startupDockAutoHide);
        if (dockOpen && (selected == TAB_FEED || selected == TAB_LIVE)) {
            handler.postDelayed(startupDockAutoHide, 4500L);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (toLoginIfNeeded()) return;
        Theme.applyWindow(this);
        buildRoot();
        feedAccountState = App.get().api().loggedIn();
        if (savedInstanceState != null) {
            selectTab(savedInstanceState.getInt("tab", TAB_FEED), true);
        } else {
            dockOpen = true;
            updateDockAppearance(false);
            focusNavTab(TAB_FEED, false);
        }
        ink.pengfei.douyin.update.UpdateManager.cleanup(this);
        preloadOtherTabs();
        loadFeed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", selected);
        super.onSaveInstanceState(state);
    }

    // ---------------------------------------------------------------- layout

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        feed = new FeedView(this);
        feed.setHost(this);
        feed.setFullscreen(true);
        root.addView(feed, match());

        pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(Ui.BG);
        pageHost.setVisibility(View.GONE);
        root.addView(pageHost, match());

        buildLoadingLayer();
        buildTopDock();
        updateDockAppearance();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((previous, next) -> {
            if (isUnder(pageHost, next)) contentFocus[selected] = next;
        });

        setContentView(root);
    }

    private void buildTopDock() {
        dockHost = new FrameLayout(this);
        dockHost.setClipChildren(false);
        root.addView(dockHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, TOP_BAR_H), Gravity.TOP));

        dockPillGroup = Ui.row(this);
        dockPillGroup.setGravity(Gravity.CENTER_VERTICAL);
        dockPillGroup.setClipChildren(false);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            FrameLayout tab = new FrameLayout(this);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setId(View.generateViewId());
            tab.setContentDescription(TAB_NAMES[i]);
            tab.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (android.os.Build.VERSION.SDK_INT >= 26) tab.setDefaultFocusHighlightEnabled(false);
            Ui.padding(tab, 18, 0, 18, 0);

            TextView label = Ui.text(this, TAB_NAMES[i], 15, Ui.TEXT_2, false);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setShadowLayer(Ui.dp(this, 3), 0, Ui.dp(this, 1), 0x77000000);
            FrameLayout.LayoutParams lpLabel = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            tab.addView(label, lpLabel);

            View underline = new View(this);
            FrameLayout.LayoutParams lpLine = new FrameLayout.LayoutParams(
                    Ui.dp(this, 22), Ui.dp(this, 3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lpLine.bottomMargin = Ui.dp(this, 2);
            tab.addView(underline, lpLine);

            tabViews[i] = tab;
            tabLabels[i] = label;
            tabLines[i] = underline;
            tab.setOnFocusChangeListener((v, has) -> paintTabs());
            tab.setOnClickListener(v -> selectTab(index, true));
            dockPillGroup.addView(tab, Ui.margin(-2, Ui.dp(this, 36),
                    i == 0 ? 0 : Ui.dp(this, 10), 0, 0, 0));
        }
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        dockHost.addView(dockPillGroup, pillLp);

        dockDivider = new View(this);
        dockDivider.setBackgroundColor(Ui.SEPARATOR);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)), Gravity.BOTTOM);
        dockHost.addView(dockDivider, dlp);

        for (int i = 0; i < tabViews.length; i++) {
            final int index = i;
            View tab = tabViews[i];
            tab.setNextFocusLeftId(tabViews[Math.max(0, i - 1)].getId());
            tab.setNextFocusRightId(tabViews[Math.min(tabViews.length - 1, i + 1)].getId());
            tab.setNextFocusUpId(tab.getId());
            tab.setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (event.getRepeatCount() == 0) tabConfirmDown = true;
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean fire = tabConfirmDown && !event.isCanceled();
                        tabConfirmDown = false;
                        if (fire) selectTab(index, true);
                        return true;
                    }
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    handler.removeCallbacks(startupDockAutoHide);
                    enterCurrentTab();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    handler.removeCallbacks(startupDockAutoHide);
                    if (index > 0) focusNavTab(index - 1, true);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    handler.removeCallbacks(startupDockAutoHide);
                    if (index < tabViews.length - 1) focusNavTab(index + 1, true);
                    return true;
                }
                return false;
            });
        }
        dockHost.setVisibility(View.VISIBLE);
    }

    private void focusNavTab(int index, boolean switchContent) {
        if (index < 0 || index >= tabViews.length) return;
        dockOpen = true;
        dockPillGroup.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        FrameLayout tab = tabViews[index];
        if (!tab.requestFocus()) {
            tab.setFocusableInTouchMode(true);
            tab.requestFocus();
            tab.setFocusableInTouchMode(false);
        }
        if (switchContent) {
            selectTab(index, false);
        } else {
            paintTabs();
        }
        scheduleDockAutoHide();
    }

    private void updateDockAppearance() {
        updateDockAppearance(false);
    }

    private void updateDockAppearance(boolean animateVisibility) {
        if (dockHost == null) return;
        boolean streamTab = selected == TAB_FEED || selected == TAB_LIVE;
        if (root != null && root.getChildCount() > 0 && root.getChildAt(root.getChildCount() - 1) != dockHost) {
            dockHost.bringToFront();
        }
        if (streamTab) {
            dockHost.setBackground(Ui.scrimTop(this));
            dockPillGroup.setBackground(null);
            dockDivider.setVisibility(View.GONE);
        } else {
            dockHost.setBackgroundColor(Ui.BG);
            dockPillGroup.setBackground(null);
            dockDivider.setVisibility(View.VISIBLE);
        }
        dockPillGroup.setDescendantFocusability(dockOpen
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) pageHost.getLayoutParams();
        int wantTop = streamTab ? 0 : Ui.dp(this, TOP_BAR_H);
        if (pp.topMargin != wantTop) {
            pp.topMargin = wantTop;
            pageHost.setLayoutParams(pp);
        }
        setStreamFocusBlocked(streamTab && dockOpen);
        updateDockVisibility(animateVisibility);
    }

    private void updateDockVisibility(boolean animate) {
        if (dockHost == null) return;
        boolean streamTab = selected == TAB_FEED || selected == TAB_LIVE;
        final boolean show = !streamTab || dockOpen || streamChromeVisible;
        dockHost.animate().cancel();
        if (!animate) {
            dockHost.setAlpha(show ? 1f : 0f);
            dockHost.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (show) {
            if (dockHost.getVisibility() != View.VISIBLE) {
                dockHost.setAlpha(0f);
                dockHost.setVisibility(View.VISIBLE);
            }
            dockHost.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease()).start();
        } else {
            if (dockHost.getVisibility() == View.VISIBLE) {
                dockHost.animate().alpha(0f).setDuration(180).setInterpolator(Ui.ease())
                        .withEndAction(() -> {
                            boolean stillHidden = (selected == TAB_FEED || selected == TAB_LIVE)
                                    && !dockOpen && !streamChromeVisible;
                            if (stillHidden) dockHost.setVisibility(View.INVISIBLE);
                        }).start();
            } else {
                dockHost.setAlpha(0f);
                dockHost.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void setStreamFocusBlocked(boolean blocked) {
        if (feed != null) {
            feed.setFocusable(!blocked);
            feed.setFocusableInTouchMode(!blocked);
            feed.setDescendantFocusability(blocked
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        if (pageHost != null) {
            pageHost.setDescendantFocusability(blocked && selected == TAB_LIVE
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    private void buildLoadingLayer() {
        loadingLayer = new FrameLayout(this);
        loadingLayer.setBackgroundColor(Ui.BG);
        LinearLayout center = Ui.column(this);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.addView(Ui.brandIcon(this, 56), Ui.lp(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView name = Ui.wordmark(this, 22);
        center.addView(name, Ui.margin(-2, -2, 0, Ui.dp(this, 14), 0, Ui.dp(this, 24)));
        loadingSpinner = new ProgressBar(this);
        loadingSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        center.addView(loadingSpinner, Ui.lp(Ui.dp(this, 32), Ui.dp(this, 32)));
        loadingText = Ui.text(this, "", 13, Ui.TEXT_3, false);
        loadingText.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 12));
        loadingText.setVisibility(View.GONE);
        center.addView(loadingText, Ui.lp(-2, -2));
        retryBtn = Ui.action(this, "重新加载", IconView.REFRESH, false);
        retryBtn.setVisibility(View.GONE);
        retryBtn.setOnClickListener(v -> loadFeed());
        center.addView(retryBtn, Ui.lp(-2, Ui.dp(this, 44)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        loadingLayer.addView(center, cp);
        root.addView(loadingLayer, match());
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------------------------------------------------------- tab state

    private void enterCurrentTab() {
        selectTab(selected, true);
    }

    private void selectTab(int index, boolean enterContent) {
        if (index < 0 || index >= TAB_NAMES.length) return;
        if (switchingTab) return;
        handler.removeCallbacks(startupDockAutoHide);
        switchingTab = true;
        try {
            if (index != selected || (index != TAB_FEED && pages[index] == null)) {
                selected = index;
                streamChromeVisible = false;
                if (index == TAB_FEED) showFeed(); else showPage(index);
            }
            dockOpen = !enterContent;
            if (index == TAB_FEED && !feed.items().isEmpty()) {
                feed.showChrome(enterContent);
            } else if (index == TAB_LIVE && pages[TAB_LIVE] instanceof LivePage) {
                ((LivePage) pages[TAB_LIVE]).showInfo(enterContent);
            }
            updateDockAppearance(enterContent);
            paintTabs();
            if (enterContent) {
                if (index == TAB_FEED) {
                    if (retryBtn.isShown()) retryBtn.requestFocus();
                    else feed.requestFocus();
                } else {
                    final ShellPage page = pages[index];
                    if (page != null) page.post(() -> {
                        if (selected != index) return;
                        if (index == TAB_LIVE) {
                            page.focusFirst();
                            return;
                        }
                        View previous = contentFocus[index];
                        if (previous == null || !previous.isShown() || !isUnder(page, previous)
                                || !previous.requestFocus(View.FOCUS_DOWN))
                            page.focusFirst();
                    });
                }
            }
        } finally {
            switchingTab = false;
        }
    }

    private void showFeed() {
        streamChromeVisible = false;
        refreshFeedAccount();
        pageHost.setVisibility(View.GONE);
        for (int i = 0; i < pages.length; i++) {
            if (i != TAB_FEED && pages[i] != null) {
                pages[i].onHide();
                pages[i].setVisibility(View.GONE);
            }
        }
        feed.setVisibility(View.VISIBLE);
        loadingLayer.setVisibility(feed.items().isEmpty() ? View.VISIBLE : View.GONE);
        if (!feed.items().isEmpty()) feed.play();
    }

    private void showPage(int index) {
        streamChromeVisible = false;
        feed.stop();
        feed.setVisibility(View.GONE);
        loadingLayer.setVisibility(View.GONE);
        pageHost.setVisibility(View.VISIBLE);
        ShellPage page = pageFor(index);
        for (int i = 0; i < pages.length; i++) {
            if (i != TAB_FEED && pages[i] != null) {
                if (i != index) pages[i].onHide();
                pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        if (index != TAB_LIVE) Ui.enter(page, 0, 12, 200);
        page.onShow();
    }

    /** 原地平滑切换主题色板并保持设置页焦点，避免 Activity recreate 黑屏闪烁。 */
    public void applyThemeInPlace(final String focusKey) {
        if (root == null) {
            recreate();
            return;
        }
        final View oldRoot = root;
        final List<Aweme> savedFeed = new java.util.ArrayList<>(feed.items());
        oldRoot.animate().alpha(0f).setDuration(140).setInterpolator(Ui.ease()).withEndAction(() -> {
            Theme.applyWindow(this);
            for (int i = 0; i < pages.length; i++) {
                if (pages[i] != null) pages[i].onHide();
                pages[i] = null;
                contentFocus[i] = null;
            }
            if (feed != null) feed.destroy();
            selected = TAB_FEED;
            buildRoot();
            if (!savedFeed.isEmpty()) {
                feed.addItems(savedFeed);
                feed.stop();
                loadingLayer.setVisibility(View.GONE);
            }
            root.setAlpha(0f);
            selectTab(TAB_ME, true);
            if (pages[TAB_ME] instanceof MePage && focusKey != null) {
                ((MePage) pages[TAB_ME]).openSettingsTab(focusKey);
            }
            root.animate().alpha(1f).setDuration(200).setInterpolator(Ui.ease()).start();
        }).start();
    }

    private ShellPage pageFor(int index) {
        if (pages[index] != null) return pages[index];
        ShellHost host = shellHost();
        switch (index) {
            case TAB_LIVE: pages[index] = new LivePage(this, host); break;
            case TAB_ME: pages[index] = new MePage(this, host); break;
            default: return null;
        }
        pages[index].setVisibility(View.GONE);
        pageHost.addView(pages[index], match());
        return pages[index];
    }

    private void preloadOtherTabs() {
        if (otherTabsPreloaded) return;
        otherTabsPreloaded = true;
        int[] targets = {TAB_LIVE, TAB_ME};
        long[] delays = {150L, 2000L};
        for (int i = 0; i < targets.length; i++) {
            final int tab = targets[i];
            handler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                ShellPage p = pageFor(tab);
                if (p != null) p.preload();
            }, delays[i]);
        }
    }

    private void paintTabs() {
        boolean overVideo = selected == TAB_FEED || selected == TAB_LIVE;
        int accent = overVideo ? 0xFFC6ED92 : Ui.ACCENT_TEXT;
        int pillFill = overVideo ? 0xFFC6ED92 : Ui.FOCUS_FILL;
        int onPill = overVideo ? 0xFF121F09 : Ui.ON_FOCUS;
        for (int i = 0; i < tabViews.length; i++) {
            FrameLayout tab = tabViews[i];
            boolean focused = dockOpen && tab.hasFocus();
            boolean active = selected == i;
            tab.setScaleX(1f);
            tab.setScaleY(1f);
            if (focused) {
                tab.setBackground(Ui.pill(this, pillFill));
                tabLabels[i].setTextColor(onPill);
                tabLabels[i].setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                tabLabels[i].setShadowLayer(0f, 0f, 0f, 0);
                tabLines[i].setVisibility(View.INVISIBLE);
            } else {
                tab.setBackground(null);
                int fg = active ? accent : (overVideo ? 0xCCF5F7F6 : Ui.TEXT_3);
                tabLabels[i].setTextColor(fg);
                tabLabels[i].setTypeface(Typeface.defaultFromStyle(
                        active ? Typeface.BOLD : Typeface.NORMAL));
                if (overVideo) tabLabels[i].setShadowLayer(Ui.dp(this, 3), 0, Ui.dp(this, 1), 0x77000000);
                else tabLabels[i].setShadowLayer(0f, 0f, 0f, 0);
                tabLines[i].setBackground(Ui.round(this, accent, 2));
                tabLines[i].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    // ---------------------------------------------------------------- feed loading

    private final Runnable feedWatchdog = new Runnable() {
        @Override public void run() {
            if (!feedLoading) return;
            feedLoading = false;
            android.util.Log.d("dytv-feed", "feed watchdog fired");
            if (feed.items().isEmpty() && selected == TAB_FEED) {
                loadingText.setText("推荐暂时无法加载");
                loadingText.setVisibility(View.VISIBLE);
                retryBtn.setVisibility(View.VISIBLE);
                loadingSpinner.setVisibility(View.GONE);
            }
        }
    };

    private void loadFeed() {
        if (feedLoading) return;
        feedLoading = true;
        final boolean signedIn = feedAccountState;
        final boolean firstPage = feed.items().isEmpty();
        if (firstPage && selected == TAB_FEED) {
            loadingLayer.setVisibility(View.VISIBLE);
            loadingText.setText("");
            loadingText.setVisibility(View.GONE);
            retryBtn.setVisibility(View.GONE);
            loadingSpinner.setVisibility(View.VISIBLE);
        }
        // 兜底：请求挂起超过 35 秒时解除加载态，避免一直转圈。
        handler.removeCallbacks(feedWatchdog);
        handler.postDelayed(feedWatchdog, 35000);
        Async.run(new Async.Fn<List<Aweme>>() {
            @Override public List<Aweme> run() throws Exception {
                return App.get().api().feed(10);
            }
        }, new Async.Cb<List<Aweme>>() {
            @Override public void done(List<Aweme> list, Exception error) {
                feedLoading = false;
                handler.removeCallbacks(feedWatchdog);
                if (error != null) android.util.Log.d("dytv-feed", "feed error " + error);
                if (isFinishing()) return;
                if (signedIn != feedAccountState) {
                    loadFeed();
                    return;
                }
                if (list != null && !list.isEmpty()) {
                    feed.addItems(list);
                    if (firstPage) {
                        Ui.fadeOut(loadingLayer, 220);
                        preloadOtherTabs();
                    }
                    if (firstPage && selected == TAB_FEED) {
                        if (dockOpen) {
                            feed.showChrome(false);
                            focusNavTab(TAB_FEED, false);
                            handler.removeCallbacks(startupDockAutoHide);
                            handler.postDelayed(startupDockAutoHide, 4500L);
                        } else {
                            if (!isUnder(dockHost, getCurrentFocus())) feed.requestFocus();
                            feed.showChrome(true);
                        }
                    }
                    if (feed.items().size() <= 5) {
                        handler.postDelayed(MainActivity.this::loadFeed, 300);
                    }
                } else if (feed.items().isEmpty()) {
                    loadingText.setText("推荐暂时无法加载");
                    retryBtn.setVisibility(View.VISIBLE);
                    loadingSpinner.setVisibility(View.GONE);
                    if (selected == TAB_FEED && !tabViews[TAB_FEED].hasFocus()) retryBtn.requestFocus();
                } else if (firstPage) {
                    loadingLayer.setVisibility(View.GONE);
                }
            }
        });
    }

    // ---------------------------------------------------------------- host

    private ShellHost shellHost() {
        return new ShellHost() {
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }

            @Override public void openLogin() {
                MainActivity.this.openLogin();
            }

            @Override public void openRelay() {
                startActivity(new Intent(MainActivity.this, ShareActivity.class));
            }

            @Override public void openShareEntry(Store.ShareEntry e) {
                PlayerActivity.openShared(MainActivity.this, e);
            }

            @Override public void playRecent(int index) {
                PlayerActivity.openRecent(MainActivity.this, index);
            }

            @Override public void refreshDanmakuSettings() {
                feed.refreshDanmakuSettings();
                if (pages[TAB_LIVE] instanceof LivePage) {
                    ((LivePage) pages[TAB_LIVE]).refreshDanmakuSettings();
                }
            }

            @Override public void refreshPlaybackSettings() {
                feed.refreshPlaybackSettings();
            }

            @Override public void onTabStep(int dir) {
                MainActivity.this.onTabStep(dir);
            }

            @Override public boolean isFullscreen() {
                return true;
            }

            @Override public void onToggleFullscreen() {
            }

            @Override public void onChromeVisibilityChanged(boolean visible) {
                MainActivity.this.onChromeVisibilityChanged(visible);
            }
        };
    }

    @Override
    public void onOpenProfile(Aweme a) {
        if (a == null || a.authorSecUid == null || a.authorSecUid.isEmpty()) {
            toast("作者信息缺失");
            return;
        }
        Intent i = new Intent(this, ProfileActivity.class);
        i.putExtra("secUid", a.authorSecUid);
        i.putExtra("name", a.authorName);
        startActivity(i);
    }

    @Override
    public void onShare(Aweme a) {
        if (a == null) return;
        ink.pengfei.douyin.ui.widget.ShareDialog.show(this, a);
    }

    @Override
    public void onLoginRequired() {
        toast("点赞需要先扫码登录");
        openLogin();
    }

    @Override
    public void onNearEnd() {
        loadFeed();
    }

    @Override
    public void onProgressSave(Aweme a, long positionMs) {
        if (a != null) App.get().store().addRecent(a);
    }

    @Override
    public void onTabStep(int dir) {
        if (dir < 0) openNavigation();
    }

    @Override
    public boolean supportsFullscreen() {
        return false;
    }

    @Override
    public boolean isFullscreen() {
        return true;
    }

    @Override
    public void onToggleFullscreen() {
    }

    @Override
    public void onPlaybackError(Aweme a) {
    }

    @Override
    public void onChromeVisibilityChanged(boolean visible) {
        if (!visible && dockOpen && (selected == TAB_FEED || selected == TAB_LIVE)) {
            View cur = getCurrentFocus();
            if (cur == null || !isUnder(dockHost, cur)) {
                handler.removeCallbacks(startupDockAutoHide);
                dockOpen = false;
                streamChromeVisible = false;
                updateDockAppearance(true);
                paintTabs();
                return;
            }
        }
        if (streamChromeVisible == visible) return;
        streamChromeVisible = visible;
        updateDockVisibility(true);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        return super.dispatchGenericMotionEvent(ev);
    }

    private void openNavigation() {
        dockOpen = true;
        if (selected == TAB_FEED && feed != null && !feed.items().isEmpty()) {
            feed.showChrome(false);
        } else if (selected == TAB_LIVE && pages[TAB_LIVE] instanceof LivePage) {
            ((LivePage) pages[TAB_LIVE]).showInfo(false);
        }
        updateDockAppearance(true);
        focusNavTab(selected, false);
    }

    // ---------------------------------------------------------------- back

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if ((key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                || key == KeyEvent.KEYCODE_BUTTON_B) && feed != null && !feed.menuOpen()) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
            return true;
        }
        View focus = getCurrentFocus();
        if (dockOpen && (focus == null || !isUnder(dockHost, focus))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.getRepeatCount() == 0) selectTab(selected, true);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                focusNavTab(selected, false);
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP && focus != null && isUnder(pageHost, focus)
                && selected != TAB_LIVE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (pages[selected] != null && pages[selected].dispatchKeyEvent(event)) return true;
                View next = FocusFinder.getInstance().findNextFocus(pageHost, focus, View.FOCUS_UP);
                if (next != null && isUnder(pageHost, next)) {
                    next.requestFocus(View.FOCUS_UP);
                } else {
                    openNavigation();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        View focus = getCurrentFocus();
        boolean inBar = dockOpen || (focus != null && isUnder(dockHost, focus));
        if (!inBar) {
            if (selected != TAB_FEED && selected != TAB_LIVE && pages[selected] != null) {
                pages[selected].onBack();
            }
            openNavigation();
            return;
        }
        TvDialog.confirm(this, "退出抖音TV？", "再次确认后将退出应用。", "退出", this::finish);
    }

    private static boolean isUnder(ViewGroup parent, View child) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK && App.get().api().loggedIn()) {
            toast("登录成功");
            refreshFeedAccount();
            if (feed.items().isEmpty()) {
                loadFeed();
            }
            if (selected == TAB_FEED) feed.requestFocus();
            else if (pages[selected] != null) pages[selected].onShow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        if (toLoginIfNeeded()) return;
        ink.pengfei.douyin.update.UpdateManager.onHomeResume(this);
        refreshFeedAccount();
        if (selected == TAB_FEED) {
            feed.refreshDanmakuSettings();
            if (!feed.items().isEmpty() && loadingLayer.getVisibility() != View.VISIBLE) feed.play();
        } else if (pages[selected] != null) {
            pages[selected].onShow();
        }
    }

    private boolean toLoginIfNeeded() {
        if (App.get().api().loggedIn()) return false;
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        return true;
    }

    private void refreshFeedAccount() {
        boolean signedIn = App.get().api().loggedIn();
        if (signedIn == feedAccountState) return;
        feedAccountState = signedIn;
        feed.clearItems();
        loadFeed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (selected == TAB_FEED && feed != null) feed.stop();
        if (selected != TAB_FEED && pages[selected] != null) pages[selected].onHide();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (feed != null) feed.destroy();
    }

    private void openLogin() {
        startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
    }

    private void toast(String msg) {
        if (root != null) Ui.notice(root, msg);
        else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
