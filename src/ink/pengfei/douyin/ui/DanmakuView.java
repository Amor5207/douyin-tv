package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import ink.pengfei.douyin.App;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Comment danmaku layer. Comments come from the video comment API and roll right-to-left
 * in the upper area, with configurable speed, size, and opacity controls.
 */
public class DanmakuView extends View {
    public static final float[] SPEEDS = {0.7f, 1f, 1.4f};
    public static final String[] SPEED_LABELS = {"慢", "标准", "快"};
    public static final int[] SIZES = {17, 21, 26};
    public static final String[] SIZE_LABELS = {"小", "中", "大"};
    public static final int[] ALPHAS = {25, 50, 75, 100};
    public static final String[] ALPHA_LABELS = {"25%", "50%", "75%", "100%"};

    private static final class Item {
        String text;
        float x;
        float width;
        int lane;
        boolean mine;
    }

    private final List<Item> items = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrame;
    private boolean paused;
    private int topInsetPx;
    private float cachedLineHeight;
    private float cachedPxPerMs;
    private int cachedWidth;

    public DanmakuView(Context c) {
        this(c, null);
    }

    public DanmakuView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setWillNotDraw(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xCC000000);
        fill.setColor(0xFFFFFFFF);
        setClickable(false);
        setFocusable(false);
        applySettings();
    }

    private static ink.pengfei.douyin.util.Store store() {
        return App.get().store();
    }

    public void applySettings() {
        float px = Ui.dp(getContext(), SIZES[sizeIndex()]);
        fill.setTextSize(px);
        stroke.setTextSize(px);
        stroke.setStrokeWidth(Math.max(2f, px / 9f));
        fill.setFakeBoldText(true);
        cachedLineHeight = px * 1.5f;
        cachedPxPerMs = cachedWidth > 0 ? (cachedWidth + Ui.dp(getContext(), 180)) / 8800f * SPEEDS[speedIndex()] : 0f;
        setAlpha(ALPHAS[alphaIndex()] / 100f);
        setVisibility(enabled() ? VISIBLE : GONE);
        if (!enabled()) clear();
        invalidate();
    }

    public static boolean enabled() { return store().danmakuOn(); }
    public static int speedIndex() { return clamp(store().danmakuSpeed(), SPEEDS.length); }
    public static int sizeIndex() { return clamp(store().danmakuSize(), SIZES.length); }
    public static int alphaIndex() { return clamp(store().danmakuAlpha(), ALPHAS.length); }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    public void clear() {
        items.clear();
        lastFrame = 0;
        invalidate();
    }

    public void setPaused(boolean value) {
        paused = value;
        lastFrame = 0;
        if (!paused) postInvalidateOnAnimation();
    }

    public int active() {
        return items.size();
    }

    private float lineHeight() {
        return cachedLineHeight > 0 ? cachedLineHeight : fill.getTextSize() * 1.5f;
    }

    /** Pushes the lanes below the top bar so comments never cover the tabs. */
    public void setTopInsetPx(int px) {
        topInsetPx = px;
        invalidate();
    }

    private int lanes() {
        int usable = Math.max(0, getHeight() - topInsetPx);
        return Math.min(5, Math.max(1, (int) (usable * 0.40f / lineHeight())));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedWidth = w;
        cachedPxPerMs = w > 0 ? (w + Ui.dp(getContext(), 180)) / 8800f * SPEEDS[speedIndex()] : 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        items.clear();
        lastFrame = 0;
    }

    /** Adds one line; dropped when every lane is still busy (better sparse than crowded). */
    public boolean add(String text, boolean mine) {
        if (!enabled() || getWidth() <= 0 || text == null) return false;
        String t = text.replaceAll("\\[[^\\]]{1,6}\\]", "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.isEmpty()) return false;
        if (t.length() > 32) t = t.substring(0, 31) + "…";
        float w = fill.measureText(t);
        int lanes = lanes();
        int w0 = getWidth();
        float[] tail = new float[lanes];
        for (Item it : items) if (it.lane < lanes) tail[it.lane] = Math.max(tail[it.lane], it.x + it.width);
        float gap = Ui.dp(getContext(), 40);
        int chosen = -1;
        float bestTail = Float.MAX_VALUE;
        for (int lane = 0; lane < lanes; lane++) {
            if (tail[lane] + gap < w0 && tail[lane] < bestTail) {
                bestTail = tail[lane];
                chosen = lane;
            }
        }
        if (chosen < 0) return false;
        Item it = new Item();
        it.text = t;
        it.width = w;
        it.x = w0;
        it.lane = chosen;
        it.mine = mine;
        items.add(it);
        lastFrame = 0;
        postInvalidateOnAnimation();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (items.isEmpty() || getVisibility() != VISIBLE) { lastFrame = 0; return; }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastFrame == 0 || paused ? 0 : Math.min(64, now - lastFrame);
        lastFrame = now;
        float pxPerMs = cachedPxPerMs > 0 ? cachedPxPerMs
                : (getWidth() + Ui.dp(getContext(), 180)) / 8800f * SPEEDS[speedIndex()];
        float line = lineHeight();
        float top = topInsetPx + Ui.dp(getContext(), 4);
        Paint.FontMetrics fm = fill.getFontMetrics();
        for (Iterator<Item> i = items.iterator(); i.hasNext(); ) {
            Item it = i.next();
            it.x -= dt * pxPerMs;
            if (it.x + it.width < 0) { i.remove(); continue; }
            float y = top + it.lane * line + (line - (fm.descent - fm.ascent)) / 2 - fm.ascent;
            canvas.drawText(it.text, it.x, y, stroke);
            fill.setColor(it.mine ? 0xFF7CF29C : 0xFFFFFFFF);
            canvas.drawText(it.text, it.x, y, fill);
        }
        if (!items.isEmpty() && !paused && isShown()) postInvalidateOnAnimation();
    }
}
