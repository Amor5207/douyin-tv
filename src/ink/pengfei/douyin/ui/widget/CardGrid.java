package ink.pengfei.douyin.ui.widget;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;

import ink.pengfei.douyin.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/** 自动列数、分批渲染、焦点驱动追加的卡片网格。 */
public class CardGrid extends LinearLayout {
    public static class Card {
        public String id = "";
        public String cover = "";
        public String title = "";
        public String badge = "";
        public String topBadge = "";
        public String subtitle = "";
        public Object payload;

        public Card() {}

        public Card(String id, String cover, String title) {
            this.id = id;
            this.cover = cover;
            this.title = title;
        }

        public Card(String id, String cover, String title, String badge) {
            this.id = id;
            this.cover = cover;
            this.title = title;
            this.badge = badge;
        }
    }

    public interface OnPick { void onPick(Card card); }

    private final List<Card> data = new ArrayList<>();
    private OnPick onPick;
    private Runnable nearEnd;
    private int batch = 24;
    private int shown;
    private int cols = 4;
    private int cardW = Ui.CARD_W;
    private int cardH = Ui.CARD_H;
    private int columnGap;
    private int rowGap;
    private float density = 1f;
    private int lastWidth;
    private boolean autoBatch = true;
    private android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher;
    private boolean pendingRebuild;

    public CardGrid(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        density = c.getResources().getDisplayMetrics().density;
        rowGap = Ui.dp(c, 22);
        columnGap = Ui.dp(c, 16);
        focusWatcher = (oldFocus, newFocus) -> {
            if (newFocus == null || !isDescendant(newFocus)) return;
            int index = indexOfTaggedAncestor(newFocus);
            if (index < 0) return;
            if (index >= shown - cols * 3) {
                onScrollNearBottom();
            }
        };
    }

    public void onScrollNearBottom() {
        if (shown < data.size()) addBatch();
        if (shown >= data.size() - cols * 2 && nearEnd != null && autoBatch) nearEnd.run();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (focusWatcher != null) {
            getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatcher);
            getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatcher);
        }
        FocusScrollView scroller = parentScroller();
        if (scroller != null) scroller.setOnNearBottom(this::onScrollNearBottom);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (focusWatcher != null) {
            getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatcher);
        }
        FocusScrollView scroller = parentScroller();
        if (scroller != null) scroller.setOnNearBottom(null);
        super.onDetachedFromWindow();
    }

    private FocusScrollView parentScroller() {
        android.view.ViewParent p = getParent();
        while (p != null) {
            if (p instanceof FocusScrollView) return (FocusScrollView) p;
            p = p.getParent();
        }
        return null;
    }

    private boolean isDescendant(View v) {
        View cur = v;
        while (cur != null) {
            if (cur == this) return true;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return false;
    }

    private int indexOfTaggedAncestor(View v) {
        View cur = v;
        while (cur != null && cur != this) {
            Object tag = cur.getTag();
            if (tag instanceof Integer) return (Integer) tag;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return -1;
    }

    public void setOnPick(OnPick pick) { onPick = pick; }

    public void setOnNearEnd(Runnable callback) { nearEnd = callback; }

    public void setBatchSize(int size) { batch = Math.max(4, size); }

    public void setAutoBatch(boolean value) { autoBatch = value; }

    public List<Card> items() { return data; }

    public int columnCount() { return cols; }

    public void setData(List<Card> cards) {
        data.clear();
        if (cards != null) data.addAll(cards);
        shown = 0;
        removeAllViews();
        if (lastWidth > 0) addBatch();
    }

    public void append(List<Card> cards) {
        if (cards == null || cards.isEmpty()) return;
        data.addAll(cards);
        if (lastWidth > 0) addBatch();
    }

    public void clear() {
        data.clear();
        shown = 0;
        removeAllViews();
    }

    public static final class Metrics {
        public final int cols, cardW, cardH, colGap, rowGap;
        Metrics(int cols, int cardW, int cardH, int colGap, int rowGap) {
            this.cols = cols;
            this.cardW = cardW;
            this.cardH = cardH;
            this.colGap = colGap;
            this.rowGap = rowGap;
        }
    }

    public static Metrics measureMetrics(Context c, int width) {
        float density = c.getResources().getDisplayMetrics().density;
        int gap = Ui.dp(c, 16);
        int columns = Math.max(1, (width + gap) / (Ui.dp(c, Ui.CARD_W + 4) + gap));
        int wdp = Math.max(72, Math.min(Ui.CARD_W + 20,
                (int) (((width - gap * (columns - 1)) / columns - Ui.dp(c, 4)) / density)));
        int outer = Ui.dp(c, wdp + 4);
        int colGap = columns > 1 ? Math.max(Ui.dp(c, 8),
                (width - columns * outer) / (columns - 1)) : gap;
        int hdp = Math.round(wdp * (float) Ui.CARD_H / Ui.CARD_W);
        return new Metrics(columns, wdp, hdp, colGap, Ui.dp(c, 22));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && (w != lastWidth || (shown == 0 && !data.isEmpty()))) {
            scheduleRebuild(w);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0 && (width != lastWidth || (shown == 0 && !data.isEmpty()))) {
            scheduleRebuild(width);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void scheduleRebuild(final int width) {
        if (pendingRebuild && width == lastWidth) return;
        pendingRebuild = true;
        post(() -> {
            pendingRebuild = false;
            if (!isAttachedToWindow()) return;
            int w = getWidth() > 0 ? getWidth() : width;
            if (w <= 0) return;
            Metrics m = measureMetrics(getContext(), w);
            boolean metricsChanged = m.cols != cols || m.cardW != cardW || m.colGap != columnGap;
            lastWidth = w;
            if (metricsChanged || (shown == 0 && !data.isEmpty())) {
                cols = m.cols;
                cardW = m.cardW;
                cardH = m.cardH;
                columnGap = m.colGap;
                int keep = shown;
                shown = 0;
                removeAllViews();
                addBatch();
                while (shown < keep && shown < data.size()) addBatch();
            }
        });
    }

    private void addBatch() {
        if (data.isEmpty()) return;
        int step = Math.max(batch, cols * 4);
        int rem = step % cols;
        if (rem != 0) step += cols - rem;
        int end = Math.min(data.size(), shown + step);
        LinearLayout row = null;
        int col = shown % cols;
        if (shown > 0 && col != 0 && getChildCount() > 0) {
            View last = getChildAt(getChildCount() - 1);
            if (last instanceof LinearLayout) row = (LinearLayout) last;
        }
        for (int i = shown; i < end; i++) {
            if (col == 0 || row == null) {
                row = Ui.row(getContext());
                row.setBaselineAligned(false);
                row.setClipChildren(false);
                LayoutParams rp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = rowGap;
                addView(row, rp);
            }
            row.addView(makeCard(data.get(i), i), cardParams(col));
            col = (col + 1) % cols;
        }
        shown = end;
    }

    private LinearLayout.LayoutParams cardParams(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), cardW + 4), LayoutParams.WRAP_CONTENT);
        lp.leftMargin = col == 0 ? 0 : columnGap;
        return lp;
    }

    private View makeCard(final Card item, final int index) {
        final PosterCard card = new PosterCard(getContext(), cardW, cardH);
        card.id = item.id;
        card.payload = item.payload;
        card.bind(item.cover, item.title, item.badge, item.topBadge, item.subtitle);
        card.setTag(index);
        card.setOnClickListener(v -> {
            if (!v.hasFocus()) v.requestFocus();
            if (onPick != null) onPick.onPick(item);
        });
        return card;
    }

    public void focusFirst() {
        if (getChildCount() == 0) return;
        View row = getChildAt(0);
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0) {
            ((LinearLayout) row).getChildAt(0).requestFocus();
        }
    }

    public void focusIndex(int index) {
        if (index < 0 || index >= shown || cols <= 0) return;
        int r = index / cols;
        int c = index % cols;
        if (r >= getChildCount()) return;
        View row = getChildAt(r);
        if (row instanceof LinearLayout && c < ((LinearLayout) row).getChildCount()) {
            ((LinearLayout) row).getChildAt(c).requestFocus();
        }
    }

    public boolean isTopRowFocus(View focus) {
        int idx = indexOfTaggedAncestor(focus);
        return idx >= 0 && idx < cols;
    }

    public boolean isLeftEdgeFocus(View focus) {
        int idx = indexOfTaggedAncestor(focus);
        return idx >= 0 && cols > 0 && (idx % cols) == 0;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            View focus = findFocus();
            int idx = focus == null ? -1 : indexOfTaggedAncestor(focus);
            if (idx >= 0 && cols > 0) {
                if (idx >= shown - cols * 3) onScrollNearBottom();
                int nextRow = idx / cols + 1;
                int lastRow = (shown - 1) / cols;
                if (idx + cols >= shown && nextRow <= lastRow) {
                    focusIndex(shown - 1);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
