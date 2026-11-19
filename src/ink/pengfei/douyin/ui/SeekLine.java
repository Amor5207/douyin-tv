package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 细线进度条：轨道两端内缩，圆点任何时候都不会被裁剪。 */
public class SeekLine extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float trackH;
    private final float dotR;
    private final float inset;
    private int max = 1000;
    private int progress;
    private boolean edgeToEdge;

    public SeekLine(Context c) {
        super(c);
        trackH = Ui.dp(c, 3);
        dotR = Ui.dp(c, 7);
        inset = dotR + Ui.dp(c, 2);
        track.setColor(0x3DFFFFFF);
        fill.setColor(Ui.PRIMARY);
        dot.setColor(Ui.PRIMARY);
    }

    public void setMax(int value) {
        max = Math.max(1, value);
        invalidate();
    }

    public void setEdgeToEdge(boolean value) {
        edgeToEdge = value;
        requestLayout();
        invalidate();
    }

    public void setProgress(int value) {
        int v = Math.max(0, Math.min(max, value));
        if (v == progress) return;
        progress = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                resolveSize(Ui.dp(getContext(), edgeToEdge ? 3 : 30), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cy = getHeight() / 2f;
        float left = edgeToEdge ? 0 : inset;
        float right = edgeToEdge ? getWidth() : getWidth() - inset;
        if (right <= left) return;
        float x = left + (right - left) * (progress / (float) max);
        float half = trackH / 2f;
        canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, track);
        canvas.drawRoundRect(left, cy - half, x, cy + half, half, half, fill);
        if (!edgeToEdge && x > left) canvas.drawCircle(x, cy, dotR, dot);
    }
}
