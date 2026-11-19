package ink.pengfei.douyin.ui.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import ink.pengfei.douyin.ui.Ui;

/** LottieFiles 风格点赞心形爆裂动效（弹性贝塞尔心形 + 扩散光环 + 放射粒子，纯 Java 无第三方依赖）。 */
public final class LikeBurstView extends View {
    private static final PathInterpolator SPRING = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final int[] SPARK_COLORS = {0xFFFF2D55, 0xFFFF85A1, 0xFFFFD166, 0xFF25F4EE};

    private final Paint heartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heartPath = new Path();
    private final float baseRadius;
    private float progress;

    public LikeBurstView(Context c) {
        super(c);
        baseRadius = Ui.dp(c, 42);
        ringPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStyle(Paint.Style.FILL);
        heartPaint.setShader(new LinearGradient(0, -baseRadius, 0, baseRadius,
                0xFFFF5E7E, 0xFFFE2C55, Shader.TileMode.CLAMP));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public static void playIn(final ViewGroup parent) {
        if (parent == null) return;
        final LikeBurstView view = new LikeBurstView(parent.getContext());
        int size = Ui.dp(parent.getContext(), 220);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        parent.addView(view, lp);

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(720);
        anim.addUpdateListener(a -> {
            view.progress = (Float) a.getAnimatedValue();
            view.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                parent.removeView(view);
            }
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float p = progress;

        // 1. 扩散光环（0.05 -> 0.58）
        if (p >= 0.05f && p <= 0.62f) {
            float rt = EASE_OUT.getInterpolation((p - 0.05f) / 0.57f);
            float radius = baseRadius * (0.45f + 1.55f * rt);
            ringPaint.setColor(0xFFFE2C55);
            ringPaint.setAlpha(Math.round(210 * (1f - rt)));
            ringPaint.setStrokeWidth(Math.max(1.5f, baseRadius * 0.14f * (1f - rt * 0.85f)));
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }

        // 2. 12 颗放射星点粒子（0.12 -> 0.85）
        if (p >= 0.10f && p <= 0.88f) {
            float st = EASE_OUT.getInterpolation((p - 0.10f) / 0.78f);
            int alpha = Math.round(255 * (1f - st));
            for (int i = 0; i < 12; i++) {
                double ang = Math.toRadians(i * 30.0 - 15.0 + (i % 2 == 0 ? 6 : -6));
                float dist = baseRadius * (0.65f + ((i % 2 == 0) ? 1.55f : 1.15f) * st);
                float sx = cx + (float) Math.cos(ang) * dist;
                float sy = cy + (float) Math.sin(ang) * dist;
                float sr = baseRadius * ((i % 2 == 0) ? 0.11f : 0.075f) * (1f - st * 0.65f);
                sparkPaint.setColor(SPARK_COLORS[i % SPARK_COLORS.length]);
                sparkPaint.setAlpha(alpha);
                canvas.drawCircle(sx, sy, sr, sparkPaint);
            }
        }

        // 3. 弹性主心形（弹入 -> 微晃回正 -> 上浮消散）
        float scale;
        int heartAlpha = 255;
        float offsetY = 0f;
        float rot = -10f * (1f - Math.min(1f, p / 0.4f));
        if (p < 0.34f) {
            scale = SPRING.getInterpolation(p / 0.34f) * 1.12f;
        } else if (p < 0.62f) {
            float mid = (p - 0.34f) / 0.28f;
            scale = 1.12f - 0.14f * (float) Math.sin(mid * Math.PI * 0.5);
        } else {
            float fade = (p - 0.62f) / 0.38f;
            scale = 0.98f + 0.18f * fade;
            offsetY = -baseRadius * 0.85f * fade;
            heartAlpha = Math.round(255 * (1f - fade));
        }

        if (heartAlpha <= 0 || scale <= 0.01f) return;
        heartPaint.setAlpha(heartAlpha);
        canvas.save();
        canvas.translate(cx, cy + offsetY);
        canvas.rotate(rot);
        canvas.scale(scale, scale);
        buildHeart(baseRadius);
        canvas.drawPath(heartPath, heartPaint);
        canvas.restore();
    }

    private void buildHeart(float r) {
        heartPath.reset();
        heartPath.moveTo(0, r * 0.78f);
        heartPath.cubicTo(-r * 1.15f, r * 0.08f, -r * 1.05f, -r * 0.88f, -r * 0.42f, -r * 0.88f);
        heartPath.cubicTo(-r * 0.15f, -r * 0.88f, 0, -r * 0.62f, 0, -r * 0.48f);
        heartPath.cubicTo(0, -r * 0.62f, r * 0.15f, -r * 0.88f, r * 0.42f, -r * 0.88f);
        heartPath.cubicTo(r * 1.05f, -r * 0.88f, r * 1.15f, r * 0.08f, 0, r * 0.78f);
        heartPath.close();
    }
}
