package ink.pengfei.douyin.ui.page;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.LoginActivity;
import ink.pengfei.douyin.MainActivity;
import ink.pengfei.douyin.PlayerActivity;
import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.model.UserProfile;
import ink.pengfei.douyin.net.DouyinApi;
import ink.pengfei.douyin.ui.DanmakuView;
import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.ui.VideoPage;
import ink.pengfei.douyin.ui.widget.CardGrid;
import ink.pengfei.douyin.ui.widget.FocusScrollView;
import ink.pengfei.douyin.ui.widget.RoundFrame;
import ink.pengfei.douyin.ui.widget.TvDialog;
import ink.pengfei.douyin.update.UpdateManager;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.Images;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 我的：无框轻量化资料头 + 分区标签（作品 / 喜欢 / 收藏 / 最近观看 / 设置）。 */
public class MePage extends ShellPage {
    private static final String[] TAB_NAMES = {"作品", "喜欢", "收藏", "最近观看", "设置"};
    private static final int TAB_WORKS = 0, TAB_LIKES = 1, TAB_COLLECTS = 2, TAB_RECENT = 3, TAB_ABOUT = 4;

    private static final String[] QUALITY_LABELS = {"自动", "1080P 超清", "720P 高清", "540P 标清"};
    private static final String[] QUALITY_VALUES = {"auto", "1080p", "720p", "540p"};
    private static final String[] LIVE_Q_KEYS = {"auto", "FULL_HD1", "HD1", "SD1", "SD2"};
    private static final String[] LIVE_Q_LABELS = {"自动", "原画", "超清", "高清", "标清"};
    private static final String[] LIVE_PROTOS = {"HLS 优先", "FLV 优先"};

    private final ShellHost host;
    private final FocusScrollView scroll;
    private final LinearLayout col;
    private final FrameLayout[] tabViews = new FrameLayout[TAB_NAMES.length];
    private final TextView[] tabLabels = new TextView[TAB_NAMES.length];
    private final View[] tabLines = new View[TAB_NAMES.length];
    private final List<Aweme> works = new ArrayList<>();
    private final List<Aweme> likes = new ArrayList<>();
    private final List<Aweme> collects = new ArrayList<>();
    private final List<Aweme> recents = new ArrayList<>();
    private final boolean[] loaded = new boolean[TAB_NAMES.length];
    private final boolean[] loading = new boolean[TAB_NAMES.length];
    private final boolean[] failed = new boolean[TAB_NAMES.length];
    private final long[] nextCursors = new long[3];
    private final boolean[] moreAvailable = new boolean[3];

    private int activeTab = TAB_WORKS;
    private String secUid;
    private long signature = Long.MIN_VALUE;
    private ImageView accountAvatar;
    private TextView nameInitial, accountName, accountBio, tabSummary;
    private LinearLayout accountStats;
    private FrameLayout contentHost;
    private CardGrid activeGrid;
    private Ui.IconButton sessionButton;
    private TextView updateValueView;
    private final java.util.Map<String, View> settingRowMap = new java.util.HashMap<>();
    private UserProfile profile;
    private boolean profileLoading;
    private int accountGeneration;
    private long likesLoadedRevision = -1;

    public MePage(Context c, ShellHost host) {
        super(c);
        this.host = host;
        setBackgroundColor(Ui.BG);
        scroll = new FocusScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        col = Ui.column(c);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        Ui.padding(col, 56, 26, 56, 48);
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(-1, -1));
        buildPage();
    }

    @Override
    public void preload() {
        onShow();
    }

    @Override
    public void onShow() {
        if (signature() != signature) {
            resetData();
            buildPage();
        }
        refreshRecents();
        if (profile != null && accountAvatar != null
                && (accountAvatar.getDrawable() == null || accountAvatar.getAlpha() < 1f)) {
            paintProfile();
        }
        loadProfile();
        refreshLikesIfChanged();
        ensureTabLoaded(activeTab);
        syncUpdateState();
    }

    @Override
    public void focusFirst() {
        if (tabViews[activeTab] != null && tabViews[activeTab].isShown()) {
            tabViews[activeTab].requestFocus();
            return;
        }
        View first = firstFocusable(col);
        if (first != null) first.requestFocus();
    }

    @Override
    public boolean onBack() {
        if (scroll.getScrollY() > Ui.dp(getContext(), 20)) {
            scroll.smoothScrollTo(0, 0);
        }
        return false;
    }

    public void openSettingsTab(final String focusKey) {
        selectTab(TAB_ABOUT);
        post(() -> {
            View target = focusKey == null ? null : settingRowMap.get(focusKey);
            if (target != null && target.isShown()) target.requestFocus();
            else if (tabViews[TAB_ABOUT] != null) tabViews[TAB_ABOUT].requestFocus();
        });
    }

    private boolean logged() {
        return App.get().api().loggedIn();
    }

    private long signature() {
        return logged() ? 1L : 0L;
    }

    private List<Aweme> listOf(int index) {
        switch (index) {
            case TAB_WORKS: return works;
            case TAB_LIKES: return likes;
            case TAB_COLLECTS: return collects;
            default: return recents;
        }
    }

    private void resetData() {
        accountGeneration++;
        profileLoading = false;
        profile = null;
        secUid = null;
        likesLoadedRevision = -1;
        works.clear();
        likes.clear();
        collects.clear();
        recents.clear();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            loaded[i] = false;
            loading[i] = false;
            failed[i] = false;
            if (i < 3) {
                nextCursors[i] = 0;
                moreAvailable[i] = false;
            }
        }
    }

    private void buildPage() {
        col.removeAllViews();
        settingRowMap.clear();
        updateValueView = null;
        signature = signature();
        Context c = getContext();
        final boolean logged = logged();

        // 1. 顶部无框轻量化个人资料区（直接融入深色背景，不用卡片壳）
        LinearLayout header = Ui.row(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        Ui.padding(header, 4, 8, 4, 8);

        RoundFrame portrait = RoundFrame.circle(c);
        portrait.setBackgroundColor(Ui.PRIMARY);
        nameInitial = Ui.bold(c, "", 28, Ui.ON_ACCENT);
        nameInitial.setGravity(Gravity.CENTER);
        portrait.addView(nameInitial, new FrameLayout.LayoutParams(-1, -1));
        accountAvatar = new ImageView(c);
        accountAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait.addView(accountAvatar, new FrameLayout.LayoutParams(-1, -1));
        header.addView(portrait, Ui.lp(Ui.dp(c, 72), Ui.dp(c, 72)));

        LinearLayout info = Ui.column(c);
        Ui.padding(info, 20, 0, 16, 0);
        accountName = Ui.ellipsis(c, "", 24, Ui.TEXT, 1);
        accountName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(accountName);
        accountBio = Ui.ellipsis(c, "", 13, Ui.TEXT_3, 1);
        accountBio.setSingleLine(true);
        info.addView(accountBio, Ui.margin(-1, -2, 0, Ui.dp(c, 6), 0, 0));
        header.addView(info, Ui.lp(0, -2, 1f));

        accountStats = Ui.row(c);
        accountStats.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(accountStats, Ui.margin(-2, -2, 0, 0, Ui.dp(c, 16), 0));

        sessionButton = Ui.iconAction(c, logged ? IconView.LOGOUT : IconView.USER,
                logged ? "退出" : "登录",
                logged ? "退出登录" : "扫码登录", false, false,
                () -> { if (logged()) confirmLogout(); else host.openLogin(); });
        sessionButton.setFocusColor(logged ? 0xFFFF4D4F : 0);
        sessionButton.setId(View.generateViewId());
        sessionButton.setNextFocusLeftId(sessionButton.getId());
        sessionButton.setNextFocusRightId(sessionButton.getId());
        header.addView(sessionButton, Ui.lp(-2, -2));
        col.addView(header, Ui.margin(-1, -2, 0, 0, 0, Ui.dp(c, 22)));
        paintProfile();

        // 2. 分区标签栏（无下划线，保持轻量化风格）
        LinearLayout tabRow = Ui.row(c);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setClipChildren(false);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            if (!logged && i < 3) {
                tabViews[i] = null;
                tabLabels[i] = null;
                tabLines[i] = null;
                continue;
            }
            FrameLayout tab = buildTab(c, i);
            tabViews[i] = tab;
            tabRow.addView(tab, Ui.margin(-2, Ui.dp(c, 36), 0, 0, Ui.dp(c, 10), 0));
        }
        tabRow.addView(new View(c), Ui.lp(0, 1, 1f));
        tabSummary = Ui.text(c, "", 13, Ui.TEXT_3, false);
        tabRow.addView(tabSummary);
        col.addView(tabRow, Ui.margin(-1, -2, 0, 0, 0, Ui.dp(c, 18)));

        if (!logged && activeTab < 3) activeTab = TAB_RECENT;
        paintTabs();

        // 3. 主内容容器
        contentHost = new FrameLayout(c);
        contentHost.setClipChildren(false);
        contentHost.setClipToPadding(false);
        col.addView(contentHost, Ui.lp(-1, -2));

        UpdateManager.setListener(this::syncUpdateState);
        syncUpdateState();
        refreshRecents();
        renderContent();
    }

    private FrameLayout buildTab(Context c, int index) {
        FrameLayout tab = new FrameLayout(c);
        tab.setId(View.generateViewId());
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setContentDescription(TAB_NAMES[index]);
        tab.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 26) tab.setDefaultFocusHighlightEnabled(false);
        Ui.padding(tab, 18, 0, 18, 0);

        TextView label = Ui.text(c, TAB_NAMES[index], 15, Ui.TEXT_2, false);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lpLabel = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tab.addView(label, lpLabel);

        View underline = new View(c);
        FrameLayout.LayoutParams lpLine = new FrameLayout.LayoutParams(
                Ui.dp(c, 22), Ui.dp(c, 3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpLine.bottomMargin = Ui.dp(c, 2);
        tab.addView(underline, lpLine);

        tabLabels[index] = label;
        tabLines[index] = underline;

        tab.setOnFocusChangeListener((v, has) -> {
            if (has && activeTab != index) selectTab(index);
            else paintTabs();
        });
        tab.setOnClickListener(v -> {
            v.requestFocus();
            selectTab(index);
        });
        final int firstVisible = logged() ? 0 : TAB_RECENT;
        tab.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && index == firstVisible) {
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && index == TAB_NAMES.length - 1) {
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP && sessionButton != null) {
                sessionButton.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (activeGrid != null && activeGrid.getChildCount() > 0) {
                    activeGrid.focusFirst();
                    return true;
                }
                View first = firstFocusable(contentHost);
                if (first != null) {
                    first.requestFocus();
                    return true;
                }
            }
            return false;
        });
        return tab;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focus = findFocus();
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_UP && focus != null
                    && activeGrid != null && activeGrid.isTopRowFocus(focus)) {
                FrameLayout curTab = tabViews[activeTab];
                if (curTab != null) {
                    curTab.requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && focus == sessionButton) {
                FrameLayout curTab = tabViews[activeTab];
                if (curTab != null) {
                    curTab.requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && focus == tabViews[activeTab]) {
                if (activeGrid != null && activeGrid.getChildCount() > 0) {
                    activeGrid.focusFirst();
                    return true;
                }
                View first = firstFocusable(contentHost);
                if (first != null) {
                    first.requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && focus != null && activeGrid == null
                    && isDescendantOf(contentHost, focus)) {
                View prev = android.view.FocusFinder.getInstance().findNextFocus(contentHost, focus, View.FOCUS_UP);
                if (prev != null && isDescendantOf(contentHost, prev)) {
                    prev.requestFocus(View.FOCUS_UP);
                    return true;
                }
                FrameLayout curTab = tabViews[activeTab];
                if (curTab != null) {
                    curTab.requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && focus == sessionButton) {
                host.onTabStep(-1);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && focus != null) {
                if (focus == sessionButton
                        || (activeGrid != null && activeGrid.isLeftEdgeFocus(focus))
                        || isDescendantOf(contentHost, focus) && activeGrid == null) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isDescendantOf(ViewGroup parent, View child) {
        View cur = child;
        while (cur != null) {
            if (cur == parent) return true;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return false;
    }

    private void selectTab(int index) {
        if (activeTab == index) return;
        activeTab = index;
        paintTabs();
        if (index == TAB_RECENT) refreshRecents();
        if (index < 3 && failed[index]) failed[index] = false;
        ensureTabLoaded(index);
        renderContent();
    }

    private void paintTabs() {
        Context c = getContext();
        for (int i = 0; i < tabViews.length; i++) {
            FrameLayout tab = tabViews[i];
            if (tab == null) continue;
            boolean focused = tab.hasFocus();
            boolean active = i == activeTab;
            tab.setScaleX(1f);
            tab.setScaleY(1f);
            if (focused) {
                tab.setBackground(Ui.pill(c, Ui.FOCUS_FILL));
                tabLabels[i].setTextColor(Ui.ON_FOCUS);
                tabLabels[i].setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                if (tabLines[i] != null) tabLines[i].setVisibility(View.INVISIBLE);
            } else {
                tab.setBackground(null);
                tabLabels[i].setTextColor(active ? Ui.ACCENT_TEXT : Ui.TEXT_3);
                tabLabels[i].setTypeface(Typeface.defaultFromStyle(
                        active ? Typeface.BOLD : Typeface.NORMAL));
                if (tabLines[i] != null) {
                    tabLines[i].setBackground(Ui.round(c, Ui.ACCENT_TEXT, 2));
                    tabLines[i].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
                }
            }
            tabLabels[i].setText(TAB_NAMES[i]);
        }
    }

    private void syncUpdateState() {
        if (updateValueView != null) {
            updateValueView.setText(UpdateManager.statusLabel());
        }
    }

    private void renderContent() {
        if (contentHost == null) return;
        contentHost.removeAllViews();
        settingRowMap.clear();
        updateValueView = null;
        activeGrid = null;
        paintTabs();

        Context c = getContext();
        int tab = activeTab;
        if (tab == TAB_ABOUT) {
            tabSummary.setText(UpdateManager.versionLabel(c));
            renderSettingsAndAbout();
            return;
        }
        if (tab == TAB_RECENT) {
            tabSummary.setText(recents.isEmpty() ? "" : "共 " + recents.size() + " 条观看记录");
            if (recents.isEmpty()) {
                contentHost.addView(emptyCard("暂无最近观看", "观看推荐或朋友分享的视频后，会记录在这里。"));
                return;
            }
            showGrid(tab, recents);
            return;
        }

        if (!logged()) {
            tabSummary.setText("");
            LinearLayout empty = emptyCard("登录后查看「" + TAB_NAMES[tab] + "」",
                    "扫码登录抖音账号即可同步作品、喜欢与收藏。");
            TextView login = Ui.action(c, "扫码登录", IconView.USER, false);
            login.setOnClickListener(v -> host.openLogin());
            empty.addView(login, Ui.margin(Ui.dp(c, 150), Ui.dp(c, 44), 0, 14, 0, 0));
            contentHost.addView(empty);
            return;
        }

        if (!loaded[tab] && !failed[tab]) {
            tabSummary.setText("");
            contentHost.addView(Ui.skeletonGrid(c, 7, 2));
            return;
        }

        if (failed[tab] && !loaded[tab]) {
            tabSummary.setText("加载失败");
            LinearLayout empty = emptyCard("加载失败", "网络波动或账号验证过期，请重试。");
            TextView retry = Ui.action(c, "重试", IconView.REFRESH, false);
            retry.setOnClickListener(v -> {
                failed[tab] = false;
                ensureTabLoaded(tab);
                renderContent();
            });
            empty.addView(retry, Ui.margin(Ui.dp(c, 150), Ui.dp(c, 44), 0, 14, 0, 0));
            contentHost.addView(empty);
            return;
        }

        List<Aweme> data = listOf(tab);
        if (data.isEmpty() && !moreAvailable[tab]) {
            tabSummary.setText("共 0 个视频");
            String sub = tab == TAB_WORKS ? "在手机抖音发布作品后，会显示在这里。"
                    : tab == TAB_LIKES ? "双击确认键点赞的视频，会同步显示在这里。"
                    : "在抖音里收藏的视频会同步显示在这里。";
            contentHost.addView(emptyCard(TAB_NAMES[tab] + "是空的", sub));
            return;
        }

        tabSummary.setText(summaryText(tab, data.size()));
        showGrid(tab, data);
    }

    private String summaryText(int tab, int loadedCount) {
        if (tab < 3 && moreAvailable[tab]) return "";
        return "共 " + loadedCount + " 个视频";
    }

    private void showGrid(final int tab, final List<Aweme> data) {
        Context c = getContext();
        LinearLayout box = Ui.column(c);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        CardGrid grid = new CardGrid(c);
        grid.setData(gridEntries(tab, data, 0));
        grid.setOnPick(card -> {
            int pos = (Integer) card.payload;
            openItem(tab, data.get(pos), pos);
        });
        if (tab < 3) {
            grid.setOnNearEnd(() -> loadNextPage(tab));
        }
        activeGrid = grid;
        box.addView(grid, Ui.lp(-1, -2));

        if (tab < 3 && moreAvailable[tab] && !loading[tab] && data.size() <= 18) {
            loadNextPage(tab);
        }
        contentHost.addView(box, Ui.lp(-1, -2));
        Ui.enter(box, 0, 10, 180);
    }

    private List<CardGrid.Card> gridEntries(int tab, List<Aweme> data, int from) {
        List<CardGrid.Card> entries = new ArrayList<>();
        for (int i = from; i < data.size(); i++) {
            Aweme item = data.get(i);
            String badge = item.durationMs > 0 ? VideoPage.fmt(item.durationMs) : "";
            CardGrid.Card entry = new CardGrid.Card(item.id, item.cover, item.desc, badge);
            entry.payload = i;
            entry.subtitle = "";
            entries.add(entry);
        }
        return entries;
    }

    private void openItem(final int tab, Aweme item, int position) {
        Activity activity = (Activity) getContext();
        if (tab == TAB_RECENT) {
            PlayerActivity.openRecent(activity, position);
            return;
        }
        final List<Aweme> list = new ArrayList<>(listOf(tab));
        if (list.isEmpty()) {
            PlayerActivity.openSingle(activity, item);
            return;
        }
        final int generation = accountGeneration;
        final long[] cursor = {nextCursors[tab]};
        final boolean[] hasMore = {moreAvailable[tab]};
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (Aweme a : list) seen.add(a.id);
        PlayerActivity.Pager pager = new PlayerActivity.Pager() {
            @Override public boolean hasMore() {
                return hasMore[0];
            }

            @Override public List<Aweme> loadMore() throws Exception {
                if (!hasMore[0]) return java.util.Collections.emptyList();
                final long at = cursor[0];
                final DouyinApi.AwemePage page = fetchPage(tab, at);
                if (page == null) {
                    hasMore[0] = false;
                    return java.util.Collections.emptyList();
                }
                hasMore[0] = page.hasMore && page.nextCursor != at;
                cursor[0] = page.nextCursor;
                final List<Aweme> added = new ArrayList<>();
                for (Aweme a : page.items) {
                    if (seen.add(a.id)) added.add(a);
                }
                post(() -> {
                    if (generation != accountGeneration) return;
                    updateCursor(tab, at, page);
                    List<Aweme> target = listOf(tab);
                    int start = target.size();
                    java.util.HashSet<String> known = new java.util.HashSet<>();
                    for (Aweme old : target) known.add(old.id);
                    for (Aweme a : added) if (known.add(a.id)) target.add(a);
                    if (activeTab == tab && activeGrid != null) {
                        if (target.size() > start) activeGrid.append(gridEntries(tab, target, start));
                        tabSummary.setText(summaryText(tab, target.size()));
                    }
                    paintTabs();
                });
                return added;
            }
        };
        PlayerActivity.openFeed(activity, list, Math.max(0, position), false, pager);
    }

    private LinearLayout emptyCard(String title, String sub) {
        Context c = getContext();
        LinearLayout empty = Ui.column(c);
        empty.setBackground(Ui.roundStroke(c, Ui.SURFACE, 18, Ui.SEPARATOR, 1));
        empty.setPadding(Ui.dp(c, 28), Ui.dp(c, 26), Ui.dp(c, 28), Ui.dp(c, 26));
        empty.addView(Ui.bold(c, title, 18, Ui.TEXT));
        TextView s = Ui.text(c, sub, 13, Ui.TEXT_3, false);
        s.setPadding(0, Ui.dp(c, 8), 0, 0);
        empty.addView(s);
        return empty;
    }

    private void ensureTabLoaded(int tab) {
        if (tab >= 3 || !logged() || profileLoading) return;
        if (!loaded[tab] && !loading[tab] && !failed[tab]) {
            load(tab);
        }
    }

    private void renderSettingsAndAbout() {
        final Context c = getContext();
        final Activity act = (Activity) c;
        LinearLayout list = Ui.column(c);

        addSectionTitle(list, "关于与更新");
        addSettingRow(list, "update", "检查更新", UpdateManager::statusLabel, true, row -> {
            UpdateManager.onItemClick(act);
            syncUpdateState();
        });
        addSettingRow(list, "version", "应用版本", () -> UpdateManager.versionLabel(c), false, null);

        addSectionTitle(list, "播放与弹幕");
        addSettingRow(list, "quality", "默认清晰度", this::qualityLabel, false, row ->
                TvDialog.choose(act, "默认清晰度", "视频源支持的清晰度会自动回退",
                        QUALITY_LABELS, qualityIndex(), row, idx -> {
                            App.get().store().setQuality(QUALITY_VALUES[idx]);
                            refreshSettingsValue(row, this::qualityLabel);
                        }));
        addSettingRow(list, "speed", "播放倍速", VideoPage::speedLabel, false, row ->
                TvDialog.choose(act, "播放倍速", "立即作用于正在播放的视频",
                        VideoPage.SPEED_LABELS, VideoPage.speedIndex(), row, idx -> {
                            App.get().store().setSpeedIndex(idx);
                            host.refreshPlaybackSettings();
                            refreshSettingsValue(row, VideoPage::speedLabel);
                        }));
        addSettingRow(list, "autonext", "自动连播", () -> App.get().store().autoNext() ? "开启" : "关闭", false, row -> {
            boolean on = App.get().store().autoNext();
            TvDialog.choose(act, "自动连播", "开启后视频播完自动切到下一个",
                    new String[]{"开启", "关闭"}, on ? 0 : 1, row, idx -> {
                        App.get().store().setAutoNext(idx == 0);
                        host.refreshPlaybackSettings();
                        refreshSettingsValue(row, () -> App.get().store().autoNext() ? "开启" : "关闭");
                    });
        });
        addSettingRow(list, "live_q", "直播清晰度", () -> LIVE_Q_LABELS[liveQualityIndex()], false, row ->
                TvDialog.choose(act, "直播清晰度", null, LIVE_Q_LABELS, liveQualityIndex(), row, idx -> {
                    App.get().store().setLiveQuality(LIVE_Q_KEYS[idx]);
                    refreshSettingsValue(row, () -> LIVE_Q_LABELS[liveQualityIndex()]);
                }));
        addSettingRow(list, "live_proto", "拉流协议", () -> LIVE_PROTOS[liveProtoIndex()], false, row ->
                TvDialog.choose(act, "拉流协议", null, LIVE_PROTOS, liveProtoIndex(), row, idx -> {
                    App.get().store().setLiveProtocol(idx);
                    refreshSettingsValue(row, () -> LIVE_PROTOS[liveProtoIndex()]);
                }));
        addSettingRow(list, "dm_on", "弹幕显示", () -> App.get().store().danmakuOn() ? "开启" : "关闭", false, row -> {
            boolean on = App.get().store().danmakuOn();
            TvDialog.choose(act, "弹幕显示", null, new String[]{"开启", "关闭"}, on ? 0 : 1, row, idx -> {
                App.get().store().setDanmakuOn(idx == 0);
                host.refreshDanmakuSettings();
                refreshSettingsValue(row, () -> App.get().store().danmakuOn() ? "开启" : "关闭");
            });
        });

        addSectionTitle(list, "外观与缓存");
        addSettingRow(list, "theme", "界面主题", () -> Ui.THEME_LABELS[Ui.themeMode(c)], false, row ->
                TvDialog.choose(act, "界面主题", null, Ui.THEME_LABELS, Ui.themeMode(c), row, mode -> {
                    if (mode == Ui.themeMode(c)) return;
                    Ui.saveTheme(c, mode);
                    if (act instanceof MainActivity) {
                        ((MainActivity) act).applyThemeInPlace("theme");
                    } else {
                        act.recreate();
                    }
                }));
        addSettingRow(list, "cache", "图片与视频缓存",
                () -> formatMb(Images.usedBytes() + ink.pengfei.douyin.util.VideoCache.usedBytes(c))
                        + "（图片 " + Images.cacheCount() + " 张）", false,
                row -> TvDialog.confirm(act, "清理缓存？", "将释放已缓存的封面图片与预加载视频片段。", "清理", () -> {
                    Images.clear();
                    ink.pengfei.douyin.util.VideoCache.clear(c);
                    host.toast("缓存已清理");
                    refreshSettingsValue(row, () -> formatMb(Images.usedBytes() + ink.pengfei.douyin.util.VideoCache.usedBytes(c)));
                }));
        addSettingRow(list, "history", "最近观看记录", () -> App.get().store().recent().size() + " 条", false,
                row -> TvDialog.confirm(act, "清空最近观看？", "将清空本机保存的最近观看视频记录。", "清空", () -> {
                    App.get().store().clearRecent();
                    refreshRecents();
                    host.toast("最近观看记录已清空");
                    refreshSettingsValue(row, () -> App.get().store().recent().size() + " 条");
                }));

        contentHost.addView(list, Ui.lp(-1, -2));
        Ui.enter(list, 0, 10, 180);
    }

    private interface SettingVal { String get(); }
    private interface SettingClick { void run(View row); }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView t = Ui.bold(getContext(), title, 14, Ui.TEXT_3);
        parent.addView(t, Ui.margin(-1, -2, 0, Ui.dp(getContext(), 14), 0, Ui.dp(getContext(), 8)));
    }

    private void addSettingRow(LinearLayout parent, String key, String title,
                               SettingVal valFn, boolean isUpdateRow, SettingClick click) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setId(View.generateViewId());
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(row, 18, 16, 18, 16);
        TextView name = Ui.bold(c, title, 16, Ui.TEXT);
        row.addView(name, Ui.lp(0, -2, 1f));
        TextView val = Ui.text(c, valFn.get(), 14, Ui.TEXT_2, false);
        row.addView(val, Ui.lp(-2, -2));
        if (isUpdateRow) updateValueView = val;
        row.setTag(val);
        settingRowMap.put(key, row);

        if (click != null) {
            row.setFocusable(true);
            row.setClickable(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) row.setDefaultFocusHighlightEnabled(false);
            row.setOnFocusChangeListener((v, has) -> {
                row.setBackground(has
                        ? Ui.roundStroke(c, Ui.TINT_PRIMARY, 10, Ui.ACCENT_TEXT, 1)
                        : Ui.round(c, Ui.FILL_2, 10));
                name.setTextColor(has ? Ui.ACCENT_TEXT : Ui.TEXT);
                val.setTextColor(has ? Ui.ACCENT_TEXT : Ui.TEXT_2);
            });
            row.setBackground(Ui.round(c, Ui.FILL_2, 10));
            row.setOnClickListener(v -> click.run(row));
        } else {
            row.setBackground(Ui.round(c, Ui.FILL_2, 14));
        }
        parent.addView(row, Ui.margin(-1, -2, 0, 0, 0, Ui.dp(c, 8)));
    }

    private void refreshSettingsValue(View row, SettingVal fn) {
        Object tag = row.getTag();
        if (tag instanceof TextView) ((TextView) tag).setText(fn.get());
    }

    private int qualityIndex() {
        String q = App.get().store().quality();
        for (int i = 0; i < QUALITY_VALUES.length; i++) if (QUALITY_VALUES[i].equals(q)) return i;
        return 0;
    }

    private String qualityLabel() {
        return QUALITY_LABELS[qualityIndex()];
    }

    private int liveQualityIndex() {
        String q = App.get().store().liveQuality();
        for (int i = 0; i < LIVE_Q_KEYS.length; i++) if (LIVE_Q_KEYS[i].equals(q)) return i;
        return 0;
    }

    private int liveProtoIndex() {
        int v = App.get().store().liveProtocol();
        return v >= 0 && v < LIVE_PROTOS.length ? v : 0;
    }

    private static String formatMb(long bytes) {
        if (bytes < 1024 * 1024L) return (bytes / 1024L) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private void load(final int index) {
        if (!logged() || index >= 3 || loading[index]) return;
        loading[index] = true;
        failed[index] = false;
        final int generation = accountGeneration;
        final long revision = App.get().api().likeRevision();
        Async.run(() -> fetchPage(index, 0), new Async.Cb<DouyinApi.AwemePage>() {
            @Override public void done(DouyinApi.AwemePage value, Exception error) {
                if (generation != accountGeneration || !logged()) return;
                loading[index] = false;
                if (value != null) {
                    List<Aweme> target = listOf(index);
                    target.clear();
                    target.addAll(value.items);
                    updateCursor(index, 0, value);
                    loaded[index] = true;
                    if (index == TAB_LIKES) likesLoadedRevision = revision;
                    if (index == TAB_WORKS && !works.isEmpty() && !works.get(0).authorAvatar.isEmpty()
                            && App.get().store().lastAccountAvatar().isEmpty()) {
                        App.get().store().setAccountAvatar(works.get(0).authorAvatar);
                        paintProfile();
                    }
                } else {
                    failed[index] = true;
                }
                if (activeTab == index) renderContent();
                else paintTabs();
                // 预取其余未加载分区，错开 350ms 防止同毫秒并发触发风控空响应。
                for (int next = 0; next < 3; next++) {
                    if (!loaded[next] && !loading[next] && !failed[next]) {
                        final int targetTab = next;
                        postDelayed(() -> {
                            if (generation == accountGeneration && logged()) load(targetTab);
                        }, 350);
                        break;
                    }
                }
            }
        });
    }

    private void loadNextPage(final int index) {
        if (index >= 3 || loading[index] || !moreAvailable[index]) return;
        final long cursor = nextCursors[index];
        final int generation = accountGeneration;
        loading[index] = true;
        Async.run(() -> fetchPage(index, cursor), (page, error) -> {
            if (generation != accountGeneration) return;
            loading[index] = false;
            if (page == null) return;
            updateCursor(index, cursor, page);
            List<Aweme> list = listOf(index);
            int start = list.size();
            java.util.HashSet<String> known = new java.util.HashSet<>();
            for (Aweme item : list) known.add(item.id);
            for (Aweme item : page.items) if (known.add(item.id)) list.add(item);
            if (activeTab == index && activeGrid != null) {
                activeGrid.append(gridEntries(index, list, start));
                tabSummary.setText(summaryText(index, list.size()));
                paintTabs();
            } else {
                paintTabs();
            }
        });
    }

    private void updateCursor(int index, long previous, DouyinApi.AwemePage page) {
        nextCursors[index] = page.nextCursor;
        moreAvailable[index] = page.hasMore && page.nextCursor != previous;
    }

    private DouyinApi.AwemePage fetchPage(int index, long cursor) throws Exception {
        DouyinApi api = App.get().api();
        if (index == TAB_COLLECTS) return api.collectionPage(18, cursor);
        String sec = secUid;
        if (sec == null || sec.isEmpty()) sec = api.selfSecUid();
        if (sec == null || sec.isEmpty()) throw new java.io.IOException("missing sec_uid");
        return index == TAB_LIKES ? api.likedPage(sec, cursor) : api.postPage(sec, cursor);
    }

    private void refreshRecents() {
        recents.clear();
        recents.addAll(App.get().store().recent());
        loaded[TAB_RECENT] = true;
        if (activeTab == TAB_RECENT && contentHost != null) renderContent();
        else paintTabs();
    }

    private void refreshLikesIfChanged() {
        if (!loaded[TAB_LIKES] || likesLoadedRevision == App.get().api().likeRevision()) return;
        loaded[TAB_LIKES] = false;
    }

    private void loadProfile() {
        if (!logged() || profile != null || profileLoading) return;
        profileLoading = true;
        int generation = accountGeneration;
        Async.run(() -> UserProfile.from(App.get().api().selfUser()), (value, error) -> {
            if (generation != accountGeneration || !logged()) return;
            profileLoading = false;
            if (value != null) {
                profile = value;
                secUid = value.secUid;
                if (!value.nickname.isEmpty()) App.get().store().setAccountName(value.nickname);
                if (!value.avatar.isEmpty()) App.get().store().setAccountAvatar(value.avatar);
                paintProfile();
            }
            ensureTabLoaded(activeTab);
        });
    }

    private void paintProfile() {
        Context c = getContext();
        String name = logged() ? App.get().store().lastAccountName() : "";
        accountName.setText(name.isEmpty() ? (logged() ? "抖音账号" : "未登录") : name);
        nameInitial.setText(name.isEmpty() ? "抖" : name.substring(0, name.offsetByCodePoints(0, 1)));
        String bio = !logged() ? "扫码登录查看作品、喜欢与收藏"
                : profile == null || profile.signature == null || profile.signature.isEmpty()
                ? "记录美好生活" : profile.signature;
        accountBio.setText(bio);
        String avatarUrl = (profile != null && !profile.avatar.isEmpty())
                ? profile.avatar
                : (logged() ? App.get().store().lastAccountAvatar() : "");
        if (avatarUrl.isEmpty() && !works.isEmpty() && !works.get(0).authorAvatar.isEmpty()) {
            avatarUrl = works.get(0).authorAvatar;
        }
        if (!avatarUrl.isEmpty()) {
            Images.load(avatarUrl, accountAvatar, Ui.dp(c, 74), 0);
        }
        accountStats.removeAllViews();
        if (profile == null) {
            if (logged()) {
                for (int i = 0; i < 3; i++) {
                    LinearLayout sk = Ui.column(c);
                    sk.setGravity(Gravity.CENTER_HORIZONTAL);
                    sk.addView(Ui.skeletonBlock(c, 34, 18, 6));
                    sk.addView(Ui.skeletonBlock(c, 28, 10, 5), Ui.margin(Ui.dp(c, 28), Ui.dp(c, 10), 0, Ui.dp(c, 6), 0, 0));
                    accountStats.addView(Ui.pulse(sk), Ui.margin(-2, -2, Ui.dp(c, 14), 0, Ui.dp(c, 10), 0));
                }
            }
            return;
        }
        addStat("关注", profile.following);
        addStat("粉丝", profile.followers);
        addStat("获赞", profile.likes);
    }

    private void addStat(String label, long count) {
        Context c = getContext();
        LinearLayout stat = Ui.column(c);
        stat.setGravity(Gravity.CENTER_HORIZONTAL);
        stat.setMinimumWidth(Ui.dp(c, 54));
        TextView num = Ui.bold(c, Ui.count(count), 19, Ui.ACCENT_TEXT);
        num.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = Ui.lp(-2, -2);
        np.gravity = Gravity.CENTER_HORIZONTAL;
        TextView lab = Ui.text(c, label, 12, Ui.TEXT_3, false);
        lab.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = Ui.margin(-2, -2, 0, Ui.dp(c, 2), 0, 0);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        stat.addView(num, np);
        stat.addView(lab, lp);
        accountStats.addView(stat, Ui.margin(-2, -2, Ui.dp(c, 14), 0, Ui.dp(c, 10), 0));
    }

    /** 右上角图标入口：图标在上、小字在下，聚焦时整块变亮绿。 */
    private View iconTile(Context c, int iconType, String label) {
        LinearLayout tile = Ui.column(c);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) tile.setDefaultFocusHighlightEnabled(false);
        Ui.padding(tile, 14, 5, 14, 5);

        FrameLayout iconBox = new FrameLayout(c);
        final IconView icon = new IconView(c, iconType, 22);
        icon.setColor(Ui.TEXT_2);
        iconBox.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        final TextView cap = Ui.text(c, label, 12, Ui.TEXT_2, false);
        cap.setGravity(Gravity.CENTER);
        tile.addView(iconBox, Ui.lp(Ui.dp(c, 42), Ui.dp(c, 26)));
        LinearLayout.LayoutParams cl = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(c, 3);
        tile.addView(cap, cl);

        final Runnable paint = () -> {
            boolean f = tile.hasFocus();
            tile.setBackground(f ? Ui.round(c, Ui.FOCUS_FILL, 16) : Ui.round(c, Ui.FILL_2, 16));
            int fg = f ? Ui.ON_FOCUS : Ui.TEXT_2;
            icon.setColor(fg);
            cap.setTextColor(fg);
            cap.setTypeface(Typeface.defaultFromStyle(f ? Typeface.BOLD : Typeface.NORMAL));
        };
        paint.run();
        tile.setOnFocusChangeListener((v, has) -> paint.run());
        return tile;
    }

    private void confirmLogout() {
        TvDialog.confirm((Activity) getContext(), "退出登录？",
                "退出后将清除本机观看记录，并返回扫码登录页。", "退出",
                () -> {
                    App.get().api().logout();
                    App.get().store().clearRecent();
                    App.get().store().setAccountName("");
                    App.get().store().setAccountAvatar("");
                    resetData();
                    Activity act = (Activity) getContext();
                    act.startActivity(new Intent(act, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    act.finish();
                });
    }

    private View firstFocusable(View root) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = firstFocusable(g.getChildAt(i));
                if (v != null) return v;
            }
            return null;
        }
        return root.isFocusable() ? root : null;
    }
}
