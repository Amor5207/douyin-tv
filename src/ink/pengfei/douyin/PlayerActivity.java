package ink.pengfei.douyin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.ui.FeedView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.Store;
import ink.pengfei.douyin.util.Theme;

import java.util.Collections;
import java.util.List;

/** Full-screen feed for a supplied list: posts, history or one shared video. */
public class PlayerActivity extends Activity implements FeedView.Host {
    public static final String MODE = "mode";
    public static final String SEC_UID = "secUid";
    public static final String INDEX = "index";
    public static final String NAME = "name";

    public interface Pager {
        boolean hasMore();
        List<Aweme> loadMore() throws Exception;
    }

    private static Aweme handoff;
    private static List<Aweme> handoffList;
    private static Pager handoffPager;
    private static boolean handoffShared;

    private FeedView feed;
    private TextView hint;
    private String mode;
    private String secUid;
    private int startIndex;
    private boolean loading;
    private long cursor;
    private Pager pager;
    private boolean sharedSource;

    public static void openSingle(Context c, Aweme a) {
        if (a == null) return;
        handoff = a;
        c.startActivity(new Intent(c, PlayerActivity.class)
                .putExtra(MODE, "single").putExtra("awemeId", a.id));
    }

    public static void openShared(Context c, Store.ShareEntry entry) {
        openShared(c, entry, null);
    }

    public static void openShared(Context c, Store.ShareEntry entry, Aweme preloaded) {
        if (entry == null || entry.awemeId.isEmpty()) return;
        handoff = preloaded;
        c.startActivity(new Intent(c, PlayerActivity.class)
                .putExtra(MODE, "shared").putExtra("awemeId", entry.awemeId));
    }

    public static void openRecent(Context c, int index) {
        c.startActivity(new Intent(c, PlayerActivity.class)
                .putExtra(MODE, "recent").putExtra(INDEX, index));
    }

    public static void openHistory(Context c) {
        c.startActivity(new Intent(c, PlayerActivity.class).putExtra(MODE, "history"));
    }

    public static void openPosts(Context c, String secUid, String name, int index) {
        c.startActivity(new Intent(c, PlayerActivity.class)
                .putExtra(MODE, "posts").putExtra(SEC_UID, secUid)
                .putExtra(NAME, name).putExtra(INDEX, index));
    }

    public static void openList(Context c, List<Aweme> items, int index) {
        openFeed(c, items, index, false, null);
    }

    public static void openFeed(Context c, List<Aweme> items, int index, boolean shared, Pager pager) {
        if (items == null || items.isEmpty()) return;
        handoffList = new java.util.ArrayList<>(items);
        handoffPager = pager;
        handoffShared = shared;
        c.startActivity(new Intent(c, PlayerActivity.class)
                .putExtra(MODE, "list").putExtra(INDEX, Math.max(0, Math.min(items.size() - 1, index))));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!App.get().api().loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Theme.applyWindow(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        mode = getIntent().getStringExtra(MODE);
        if (mode == null) mode = "recent";
        secUid = getIntent().getStringExtra(SEC_UID);
        startIndex = getIntent().getIntExtra(INDEX, 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        feed = new FeedView(this);
        feed.setHost(this);
        feed.setFocusable(true);
        root.addView(feed, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = Ui.text(this, "", 18, 0xFFDDDDDD);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x99000000);
        hint.setVisibility(View.GONE);
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        feed.requestFocus();
        load();
    }

    private void load() {
        if ("list".equals(mode)) {
            List<Aweme> items = handoffList;
            pager = handoffPager;
            sharedSource = handoffShared;
            handoffList = null;
            handoffPager = null;
            handoffShared = false;
            hideHint();
            if (items == null || items.isEmpty()) {
                finishWithError("没有可播放的视频");
                return;
            }
            int idx = Math.max(0, Math.min(startIndex, items.size() - 1));
            feed.showItems(items, idx);
            if (idx >= items.size() - 2 && pager != null && pager.hasMore()) {
                loadMoreFromPager();
            }
            feed.requestFocus();
            return;
        }
        if ("single".equals(mode) || "shared".equals(mode)) {
            String id = getIntent().getStringExtra("awemeId");
            boolean shared = "shared".equals(mode);
            Aweme selected = handoff;
            handoff = null;
            if (selected != null && selected.id.equals(id) && selected.playable()) {
                feed.addItems(Collections.singletonList(selected));
                hideHint();
                return;
            }
            Async.run(() -> {
                Aweme remote = App.get().api().detail(id);
                if (remote != null) return remote;
                for (Aweme recent : App.get().store().recent()) {
                    if (recent.id.equals(id) && recent.playable()) return recent;
                }
                return null;
            }, (a, error) -> {
                if (isFinishing() || isDestroyed()) return;
                if (a == null) {
                    finishWithError("视频暂时无法播放");
                    return;
                }
                if (shared && !a.desc.isEmpty()) App.get().store().renameShare(a.id, a.desc);
                feed.addItems(Collections.singletonList(a));
                hideHint();
            });
            return;
        }
        loading = true;
        Async.run(new Async.Fn<List<Aweme>>() {
            @Override public List<Aweme> run() throws Exception {
                if ("posts".equals(mode)) {
                    return App.get().api().posts(secUid, 0);
                } else if ("history".equals(mode)) {
                    return App.get().api().history();
                } else {
                    return App.get().store().recent();
                }
            }
        }, new Async.Cb<List<Aweme>>() {
            @Override public void done(List<Aweme> list, Exception error) {
                loading = false;
                hideHint();
                if (list == null || list.isEmpty()) {
                    if ("history".equals(mode)) {
                        List<Aweme> local = App.get().store().recent();
                        if (!local.isEmpty()) {
                            feed.showItems(local, Math.min(startIndex, local.size() - 1));
                            return;
                        }
                    }
                    finishWithError("没有可播放的视频");
                    return;
                }
                if (!list.isEmpty()) {
                    Aweme last = list.get(list.size() - 1);
                    cursor = last.createTime > 0 ? last.createTime * 1000 : 0;
                }
                int start = Math.min(startIndex, list.size() - 1);
                feed.showItems(list, start);
                feed.requestFocus();
                if ("recent".equals(mode) || "history".equals(mode)) {
                    refreshCurrentDetail(list.get(start));
                }
            }
        });
    }

    /** 刷新历史记录条目的最新服务端点赞状态，避免展示本地旧缓存。 */
    private void refreshCurrentDetail(final Aweme target) {
        if (target == null || target.id == null || target.id.isEmpty()) return;
        Async.run(() -> App.get().api().detail(target.id), (fresh, err) -> {
            if (isFinishing() || isDestroyed() || fresh == null) return;
            target.userDigged = fresh.userDigged;
            target.diggCount = fresh.diggCount;
            target.commentCount = fresh.commentCount;
            target.shareCount = fresh.shareCount;
            if (feed != null) feed.refreshCurrentMeta(target);
        });
    }

    private void loadMorePosts() {
        if (!"posts".equals(mode) || loading || cursor <= 0) return;
        loading = true;
        final long at = cursor;
        Async.run(new Async.Fn<List<Aweme>>() {
            @Override public List<Aweme> run() throws Exception {
                return App.get().api().posts(secUid, at);
            }
        }, new Async.Cb<List<Aweme>>() {
            @Override public void done(List<Aweme> list, Exception error) {
                loading = false;
                if (list != null && !list.isEmpty()) {
                    Aweme last = list.get(list.size() - 1);
                    long next = last.createTime > 0 ? last.createTime * 1000 : 0;
                    if (next >= cursor) cursor = next - 1000;
                    else cursor = next;
                    feed.addItems(list);
                } else {
                    cursor = 0;
                }
            }
        });
    }

    private void hideHint() {
        hint.setVisibility(View.GONE);
    }

    private void finishWithError(String message) {
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        hint.postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 1600);
    }

    @Override
    public void onOpenProfile(Aweme a) {
        if (a == null || a.authorSecUid == null || a.authorSecUid.isEmpty()) return;
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
        startActivity(new Intent(this, LoginActivity.class));
    }

    private void loadMoreFromPager() {
        if (pager == null || loading || !pager.hasMore()) return;
        loading = true;
        Async.run(pager::loadMore, (more, error) -> {
            loading = false;
            if (isFinishing() || isDestroyed()) return;
            if (more != null && !more.isEmpty()) feed.addItems(more);
        });
    }

    @Override
    public void onNearEnd() {
        if (pager != null) loadMoreFromPager();
        else loadMorePosts();
    }

    @Override
    public void onProgressSave(Aweme a, long positionMs) {
        if (a != null) App.get().store().addRecent(a);
    }

    @Override
    public void onTabStep(int dir) {
    }

    @Override
    public boolean supportsFullscreen() {
        return false;
    }

    @Override
    public boolean isFullscreen() {
        return false;
    }

    @Override
    public void onToggleFullscreen() {
    }

    @Override
    public void onPlaybackError(Aweme a) {
        if (a != null && (sharedSource || "shared".equals(mode))) {
            if ("shared".equals(mode) || feed.items().size() <= 1) {
                finishWithError("该视频无法播放，已移除");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (feed != null) feed.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (feed != null) feed.play();
    }

    @Override
    public void onBackPressed() {
        if (feed != null && feed.dismissChromeIfOpen()) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (feed != null) feed.destroy();
    }
}
