package ink.pengfei.douyin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.model.UserProfile;
import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.ui.widget.CardGrid;
import ink.pengfei.douyin.ui.widget.FocusScrollView;
import ink.pengfei.douyin.ui.widget.RoundFrame;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.Images;
import ink.pengfei.douyin.util.Theme;

import java.util.ArrayList;
import java.util.List;

/** 作者主页：绿果风格头部 + 作品网格。 */
public class ProfileActivity extends Activity {
    private FrameLayout content;
    private String secUid;
    private String fallbackName;
    private final List<Aweme> posts = new ArrayList<>();
    private CardGrid gridRef;
    private View backRef;
    private long nextCursor;
    private boolean hasMorePosts;
    private boolean loadingMore;
    private boolean backDown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!App.get().api().loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Theme.applyWindow(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        secUid = getIntent().getStringExtra("secUid");
        fallbackName = getIntent().getStringExtra("name");
        if (secUid == null || secUid.isEmpty()) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        loading();
        load();
    }

    private void loading() {
        content.removeAllViews();
        LinearLayout page = Ui.column(this);
        Ui.padding(page, 54, 26, 54, 30);
        page.addView(Ui.pulse(Ui.skeletonBlock(this, 96, 38, 19)), Ui.margin(Ui.dp(this, 96), Ui.dp(this, 38), 0, 0, 0, Ui.dp(this, 18)));
        page.addView(Ui.skeletonProfileHeader(this), Ui.margin(-1, -2, 0, 0, 0, Ui.dp(this, 26)));
        page.addView(Ui.pulse(Ui.skeletonBlock(this, 96, 20, 6)), Ui.margin(Ui.dp(this, 96), Ui.dp(this, 20), 0, 0, 0, Ui.dp(this, 14)));
        page.addView(Ui.skeletonGrid(this, 7, 2));
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void load() {
        Async.run(new Async.Fn<Object[]>() {
            @Override public Object[] run() throws Exception {
                UserProfile p = App.get().api().profile(secUid);
                ink.pengfei.douyin.net.DouyinApi.AwemePage page = App.get().api().postPage(secUid, 0);
                return new Object[]{p, page};
            }
        }, new Async.Cb<Object[]>() {
            @Override public void done(Object[] value, Exception error) {
                if (isFinishing()) return;
                UserProfile p = value == null ? null : (UserProfile) value[0];
                ink.pengfei.douyin.net.DouyinApi.AwemePage page = value == null ? null
                        : (ink.pengfei.douyin.net.DouyinApi.AwemePage) value[1];
                posts.clear();
                if (page != null) {
                    posts.addAll(page.items);
                    nextCursor = page.nextCursor;
                    hasMorePosts = page.hasMore && page.nextCursor > 0;
                } else {
                    hasMorePosts = false;
                }
                if (p == null && posts.isEmpty()) {
                    errorPage();
                    return;
                }
                render(p);
            }
        });
    }

    private void errorPage() {
        content.removeAllViews();
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        TextView t = Ui.bold(this, "主页加载失败", 24, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        TextView s = Ui.text(this, "网络波动或作者设置了隐私限制", 14, Ui.TEXT_3, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20));
        TextView retry = Ui.action(this, "重试", IconView.REFRESH, false);
        retry.setOnClickListener(v -> { loading(); load(); });
        box.addView(t);
        box.addView(s);
        box.addView(retry, Ui.lpm(Ui.dp(this, 160), Ui.dp(this, 46)));
        content.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        retry.requestFocus();
    }

    private void render(UserProfile p) {
        content.removeAllViews();
        FocusScrollView sv = new FocusScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 54), Ui.dp(this, 26), Ui.dp(this, 54), Ui.dp(this, 30));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.enter(col, 0, 12, 200);

        LinearLayout top = Ui.row(this);
        final TextView back = Ui.action(this, "返回", IconView.BACK, false);
        back.setId(View.generateViewId());
        back.setOnClickListener(v -> finish());
        top.addView(back);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView label = Ui.text(this, "作者主页", 12, Ui.TEXT_3, false);
        top.addView(label);
        col.addView(top, Ui.margin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 18));

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(Ui.roundStroke(this, Ui.SURFACE, 20, Ui.SEPARATOR, 1));
        Ui.padding(header, 26, 20, 26, 20);
        String nick = p != null && p.nickname != null && !p.nickname.isEmpty()
                ? p.nickname : (fallbackName == null || fallbackName.isEmpty() ? "抖音作者" : fallbackName);
        RoundFrame avatarFrame = RoundFrame.circle(this);
        avatarFrame.setBackgroundColor(Ui.PRIMARY);
        String initialText = nick.isEmpty() ? "抖" : nick.substring(0, nick.offsetByCodePoints(0, 1));
        TextView initial = Ui.bold(this, initialText, 28, Ui.ON_ACCENT);
        initial.setGravity(Gravity.CENTER);
        avatarFrame.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarFrame.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        String avatarUrl = p != null ? p.avatar : (posts.isEmpty() ? "" : posts.get(0).authorAvatar);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Images.load(avatarUrl, avatar, Ui.dp(this, 78), 0);
        }
        header.addView(avatarFrame, Ui.lp(Ui.dp(this, 78), Ui.dp(this, 78)));
        LinearLayout text = Ui.column(this);
        Ui.padding(text, 20, 0, 16, 0);
        TextView name = Ui.ellipsis(this, nick, 23, Ui.TEXT, 1);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        String sig = p != null && p.signature != null && !p.signature.isEmpty()
                ? p.signature : "这个人很神秘，什么都没有留下";
        TextView signature = Ui.ellipsis(this, sig, 13, Ui.TEXT_3, 1);
        signature.setSingleLine(true);
        text.addView(name);
        text.addView(signature, Ui.margin(-1, -2, 0, Ui.dp(this, 6), 0, 0));
        header.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout stats = Ui.row(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        long followers = p != null ? p.followers : 0;
        long likes = p != null ? p.likes : 0;
        long count = p != null && p.awemes > 0 ? p.awemes : posts.size();
        addStat(stats, Ui.count(followers), "粉丝");
        addStat(stats, Ui.count(likes), "获赞");
        addStat(stats, Ui.count(count), "作品");
        header.addView(stats, Ui.lp(-2, -2));
        col.addView(header, Ui.margin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 26));

        final TextView title = Ui.bold(this, "作品 · " + posts.size(), 19, Ui.TEXT);
        col.addView(title, Ui.margin(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));

        final CardGrid grid = posts.isEmpty() ? null : new CardGrid(this);
        if (grid != null) {
            grid.setId(View.generateViewId());
            sv.setOnKeyListener((v, code, ev) -> false);
            back.setNextFocusDownId(grid.getId());
        }
        sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gridRef = grid;
        backRef = back;

        if (posts.isEmpty()) {
            LinearLayout empty = Ui.column(this);
            empty.setBackground(Ui.round(this, Ui.SURFACE, 16));
            empty.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
            empty.addView(Ui.bold(this, "暂无可播放的作品", 16, Ui.TEXT));
            TextView s = Ui.text(this, "作者可能设置了隐私限制，或还在审核中。", 13, Ui.TEXT_3, false);
            s.setPadding(0, Ui.dp(this, 6), 0, 0);
            empty.addView(s);
            col.addView(empty);
        } else {
            List<CardGrid.Card> cards = new ArrayList<>();
            for (int i = 0; i < posts.size(); i++) {
                Aweme a = posts.get(i);
                CardGrid.Card card = new CardGrid.Card(a.id, a.cover, a.desc);
                card.subtitle = "";
                card.payload = i;
                cards.add(card);
            }
            grid.setData(cards);
            grid.setOnPick(card -> PlayerActivity.openFeed(ProfileActivity.this,
                    posts, (Integer) card.payload, false, makePager(grid, title)));
            grid.setOnNearEnd(() -> loadMorePosts(grid, title));
            col.addView(grid, Ui.lp(-1, -2));
            if (hasMorePosts && posts.size() <= 18) loadMorePosts(grid, title);
        }
        content.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (grid != null) grid.post(grid::focusFirst);
        else back.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        backDown = false;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (key == android.view.KeyEvent.KEYCODE_BACK || key == android.view.KeyEvent.KEYCODE_ESCAPE
                || key == android.view.KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) backDown = true;
                return true;
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                boolean fire = backDown && !event.isCanceled();
                backDown = false;
                if (fire) finish();
                return true;
            }
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                    && focus != null && gridRef != null && gridRef.isTopRowFocus(focus) && backRef != null) {
                backRef.requestFocus();
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && focus == backRef && gridRef != null && gridRef.getChildCount() > 0) {
                gridRef.focusFirst();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void loadMorePosts(final CardGrid grid, final TextView title) {
        if (loadingMore || !hasMorePosts || nextCursor <= 0) return;
        loadingMore = true;
        final long at = nextCursor;
        Async.run(() -> App.get().api().postPage(secUid, at), (page, error) -> {
            loadingMore = false;
            if (isFinishing() || page == null) return;
            appendProfilePage(page, at, grid, title);
        });
    }

    private java.util.List<Aweme> appendProfilePage(ink.pengfei.douyin.net.DouyinApi.AwemePage page,
                                                    long at, CardGrid grid, TextView title) {
        hasMorePosts = page.hasMore && page.nextCursor != at;
        nextCursor = page.nextCursor;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Aweme a : posts) seen.add(a.id);
        java.util.List<Aweme> added = new ArrayList<>();
        List<CardGrid.Card> cards = new ArrayList<>();
        for (Aweme a : page.items) {
            if (!seen.add(a.id)) continue;
            int idx = posts.size();
            posts.add(a);
            added.add(a);
            CardGrid.Card card = new CardGrid.Card(a.id, a.cover, a.desc);
            card.subtitle = "";
            card.payload = idx;
            cards.add(card);
        }
        if (!cards.isEmpty() && grid != null) {
            grid.append(cards);
            if (title != null) title.setText("作品 · " + posts.size() + (hasMorePosts ? "+" : ""));
        }
        return added;
    }

    private PlayerActivity.Pager makePager(final CardGrid grid, final TextView title) {
        final long[] cursor = {nextCursor};
        final boolean[] more = {hasMorePosts};
        final java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Aweme a : posts) seen.add(a.id);
        return new PlayerActivity.Pager() {
            @Override public boolean hasMore() {
                return more[0] && cursor[0] > 0;
            }

            @Override public List<Aweme> loadMore() throws Exception {
                if (!hasMore()) return java.util.Collections.emptyList();
                final long at = cursor[0];
                final ink.pengfei.douyin.net.DouyinApi.AwemePage page = App.get().api().postPage(secUid, at);
                if (page == null) {
                    more[0] = false;
                    return java.util.Collections.emptyList();
                }
                more[0] = page.hasMore && page.nextCursor != at;
                cursor[0] = page.nextCursor;
                final List<Aweme> added = new ArrayList<>();
                for (Aweme a : page.items) {
                    if (seen.add(a.id)) added.add(a);
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) appendProfilePage(page, at, grid, title);
                });
                return added;
            }
        };
    }

    private void addStat(LinearLayout row, String value, String label) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setMinimumWidth(Ui.dp(this, 54));
        TextView v = Ui.bold(this, value, 19, Ui.ACCENT_TEXT);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = Ui.lp(-2, -2);
        vp.gravity = Gravity.CENTER_HORIZONTAL;
        TextView l = Ui.text(this, label, 12, Ui.TEXT_3, false);
        l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = Ui.margin(-2, -2, 0, Ui.dp(this, 2), 0, 0);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(v, vp);
        box.addView(l, lp);
        row.addView(box, Ui.margin(-2, -2, Ui.dp(this, 14), 0, Ui.dp(this, 10), 0));
    }
}
