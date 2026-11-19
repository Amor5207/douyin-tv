package ink.pengfei.douyin.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import ink.pengfei.douyin.ui.Ui;

/**
 * 圆角/圆形裁切容器：子视图（封面图、头像、渐变、角标）全部裁成圆角或正圆。
 * 硬件加速时用 Outline 裁切；软件渲染时用 clipPath 兜底，并绘制严格同心的聚焦环。
 */
public class RoundFrame extends FrameLayout {
    private final float fixedRadius;
    private final boolean circle;
    private final Path clip = new Path();
    private final Path ring = new Path();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ringOn;
    private float ringWidth;

    public RoundFrame(Context c, float radiusDp) {
        super(c);
        this.circle = radiusDp < 0;
        this.fixedRadius = circle ? 0f : Ui.dp(c, radiusDp);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                float r = radiusFor(v.getWidth(), v.getHeight());
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
    }

    public static RoundFrame circle(Context c) {
        return new RoundFrame(c, -1f);
    }

    /** 聚焦环：与圆角裁切严格同心（外轮廓 − 内轮廓），不会出现四角对不齐。 */
    public void setRing(boolean on, int color, float widthDp) {
        ringOn = on;
        ringPaint.setColor(color);
        ringWidth = Ui.dp(getContext(), widthDp);
        invalidate();
    }

    private float radiusFor(int w, int h) {
        return circle ? Math.min(w, h) / 2f : fixedRadius;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (canvas.isHardwareAccelerated()) {
            super.dispatchDraw(canvas);
            drawRing(canvas);
            return;
        }
        int save = canvas.save();
        float r = radiusFor(getWidth(), getHeight());
        clip.reset();
        clip.addRoundRect(0, 0, getWidth(), getHeight(), r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        drawRing(canvas);
        canvas.restoreToCount(save);
    }

    private void drawRing(Canvas canvas) {
        if (!ringOn || ringWidth <= 0) return;
        float w = getWidth(), h = getHeight(), s = ringWidth;
        float r = radiusFor(getWidth(), getHeight());
        ring.reset();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        ring.addRoundRect(s, s, w - s, h - s, Math.max(0, r - s), Math.max(0, r - s), Path.Direction.CW);
        canvas.drawPath(ring, ringPaint);
    }
}
