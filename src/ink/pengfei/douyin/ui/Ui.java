package ink.pengfei.douyin.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 抖音 TV 标志绿影院设计系统：
 * 深炭绿黑背景 + 分层通透质感 + 标志亮绿高亮（#C6ED92）；聚焦元素变成亮绿实底配深墨字。
 */
public final class Ui {
    public static final int THEME_DARK = 0, THEME_LIGHT = 1, THEME_SYSTEM = 2;
    public static final String[] THEME_LABELS = {"深色", "浅色", "跟随系统"};
    public static final int VIDEO_TEXT = 0xFFF5F7F6;
    public static final int VIDEO_MUTED = 0xFFD1D9D5;
    public static final int VIDEO_ACCENT = 0xFFFF4D6D;
    public static int BG, GROUPED_BG, SURFACE, SURFACE_2, SURFACE_3, ELEVATED;
    public static int FILL, FILL_2, SEPARATOR;
    public static int PRIMARY, PRIMARY_DIM, ACCENT_TEXT, TINT_PRIMARY, ON_ACCENT, ACCENT, GOLD;
    public static int TEXT, TEXT_2, TEXT_3, TEXT_4, SKELETON;
    public static int FOCUS_FILL, ON_FOCUS, FOCUS_RING;

    static { setPalette(false); }

    public static int themeMode(Context context) {
        int mode = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme_v2", THEME_DARK);
        return mode >= THEME_DARK && mode <= THEME_SYSTEM ? mode : THEME_DARK;
    }

    public static void applyTheme(Context context) {
        int mode = themeMode(context);
        int system = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        setPalette(mode == THEME_LIGHT || (mode == THEME_SYSTEM
                && system == android.content.res.Configuration.UI_MODE_NIGHT_NO));
    }

    public static void saveTheme(Context context, int mode) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("theme_v2", mode).apply();
    }

    private static void setPalette(boolean light) {
        BG = GROUPED_BG = light ? 0xFFF2F2F7 : 0xFF0D1110;
        SURFACE = ELEVATED = light ? 0xFFFFFFFF : 0xFF191F1D;
        SURFACE_2 = light ? 0xFFF2F3F5 : 0xFF232B28;
        SURFACE_3 = light ? 0xFFE7E8EC : 0xFF2E3833;
        FILL = light ? 0x14000000 : 0x1FFFFFFF;
        SEPARATOR = light ? 0x333C3C43 : 0x22FFFFFF;
        FILL_2 = light ? 0x0A000000 : 0x15FFFFFF;
        PRIMARY = light ? 0xFF2DB35A : 0xFFC6ED92;
        PRIMARY_DIM = light ? 0xFF6CCB86 : 0xFF9CBF6D;
        ACCENT_TEXT = light ? 0xFF1C9A47 : PRIMARY;
        TINT_PRIMARY = light ? 0x242DB35A : 0x26C6ED92;
        ON_ACCENT = light ? 0xFFFFFFFF : 0xFF121F09;
        ACCENT = light ? 0xFF3C7A6B : 0xFFC6ED92;
        GOLD = light ? 0xFFB07A14 : 0xFFE8B65A;
        TEXT = light ? 0xFF1C1C1E : VIDEO_TEXT;
        TEXT_2 = light ? 0xFF55555A : 0xFFBFC8C4;
        TEXT_3 = light ? 0xFF8A8A8E : 0xFF8A938F;
        TEXT_4 = light ? 0xFFB0B0B5 : 0xFF6E756F;
        SKELETON = light ? 0xFFE3E3E8 : 0xFF252929;
        FOCUS_FILL = PRIMARY;
        ON_FOCUS = ON_ACCENT;
        FOCUS_RING = PRIMARY;
    }

    // ---- 尺寸
    public static final int CARD_W = 104;
    public static final int CARD_H = 130;
    /** 顶部常驻 Tab 栏高度。 */
    public static final int BAR_H = 78;

    private Ui() {}

    public static int alpha(int rgb, int a) { return (rgb & 0x00FFFFFF) | (a << 24); }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dp(View v, float val) { return dp(v.getContext(), val); }

    private static float sSysDensity = -1f, sSysScaledDensity = -1f;
    private static int sSysDensityDpi = -1;

    /** 强制保持横屏（含反向横屏），防止手机或紧凑设备落入竖屏状态。 */
    public static void enforceLandscape(android.app.Activity activity) {
        if (activity == null) return;
        try {
            if (activity.getRequestedOrientation() != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        } catch (Throwable ignored) {}
    }

    /** 按 960×540dp 最小视口基准自适应缩放，避免 960×720 等小屏/高分屏下页面拥挤与裁切。 */
    public static void adaptScreen(android.app.Activity activity) {
        if (activity == null) return;
        enforceLandscape(activity);
        android.util.DisplayMetrics appDm = activity.getApplication().getResources().getDisplayMetrics();
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        if (sSysDensity <= 0f) {
            sSysDensity = appDm.density;
            sSysScaledDensity = appDm.scaledDensity;
            sSysDensityDpi = appDm.densityDpi;
        }
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        float target = Math.min(longSide / 960f, shortSide / 540f);
        float useDensity;
        float useScaled;
        int useDpi;
        if (target > 0.4f && target < sSysDensity - 0.01f) {
            float fontScale = sSysDensity > 0f ? (sSysScaledDensity / sSysDensity) : 1f;
            fontScale = Math.max(0.85f, Math.min(1.15f, fontScale));
            useDensity = target;
            useScaled = target * fontScale;
            useDpi = Math.max(120, Math.round(target * 160f));
        } else {
            useDensity = sSysDensity;
            useScaled = sSysScaledDensity;
            useDpi = sSysDensityDpi;
        }
        dm.density = useDensity;
        dm.scaledDensity = useScaled;
        dm.densityDpi = useDpi;
        appDm.density = useDensity;
        appDm.scaledDensity = useScaled;
        appDm.densityDpi = useDpi;
    }

    public static View pauseIndicator(Context context) {
        LinearLayout badge = column(context);
        badge.setGravity(Gravity.CENTER);
        padding(badge, 22, 15, 22, 14);
        badge.setBackground(round(context, 0xD91B221E, 16));
        IconView icon = new IconView(context, IconView.PAUSE, 24);
        icon.setColor(VIDEO_TEXT);
        badge.addView(icon, lp(dp(context, 30), dp(context, 30)));
        badge.addView(bold(context, "已暂停", 13, VIDEO_TEXT),
                margin(-2, -2, 0, dp(context, 4), 0, 0));
        badge.setVisibility(View.GONE);
        return badge;
    }

    /** 旧接口：按 sp 设置字号（使用系统密度换算）。 */
    public static void setSize(TextView t, float sp) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    public static String count(long n) {
        if (n >= 100000000L) return trim(n / 10000000L / 10.0) + "亿";
        if (n >= 10000L) return trim(n / 1000L / 10.0) + "万";
        return String.valueOf(n);
    }

    private static String trim(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    // ------------------------------------------------------------ 形状

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp,
                                               int strokeColor, float strokeDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable focusRing(Context c, boolean hot) {
        return roundStroke(c, hot ? SURFACE_2 : 0x00000000, 12,
                hot ? PRIMARY : 0x00000000, 2);
    }

    public static GradientDrawable pill(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 100));
        return d;
    }

    public static GradientDrawable scrimBottom(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF20B0E11, 0xCC0B0E11, 0x400B0E11, 0x000B0E11});
    }

    public static GradientDrawable scrimLeft(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20B0E11, 0xE00B0E11, 0x800B0E11, 0x000B0E11});
    }

    /** 顶部向下渐隐的黑色遮罩（视频页顶部 Tab 栏可读性）。 */
    public static GradientDrawable scrimTop(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE60B0E11, 0xB30B0E11, 0x000B0E11});
    }

    public static GradientDrawable verticalGradient(int top, int bottom, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setCornerRadius(radius);
        return d;
    }

    // ------------------------------------------------------------ 文字

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.defaultFromStyle(bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLineSpacing(dp(c, 5), 1f);
        return t;
    }

    /** 旧接口：非粗体。 */
    public static TextView text(Context c, String s, int sizeSp, int color) {
        return text(c, s, (float) sizeSp, color, false);
    }

    /** 旧接口：粗体。 */
    public static TextView bold(Context c, String s, int sizeSp, int color) {
        return text(c, s, (float) sizeSp, color, true);
    }

    public static TextView ellipsis(Context c, String s, float sizeSp, int color, int maxLines) {
        TextView t = text(c, s, sizeSp, color, false);
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static void icon(TextView view, int type, int color, int size) {
        IconDrawable drawable = new IconDrawable(type, color);
        int px = dp(view, size);
        drawable.setBounds(0, 0, px, px);
        view.setCompoundDrawables(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(view, 8));
    }

    /** 图标与文字使用同一前景色。 */
    private static final class ActionText extends TextView {
        boolean centerGroup;
        ActionText(Context context) { super(context); }
        @Override public void setTextColor(int color) { super.setTextColor(color); tintIcons(this, color); }
        @Override public void setCompoundDrawables(android.graphics.drawable.Drawable left,
                android.graphics.drawable.Drawable top, android.graphics.drawable.Drawable right,
                android.graphics.drawable.Drawable bottom) {
            super.setCompoundDrawables(left, top, right, bottom);
            tintIcons(this, getCurrentTextColor());
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            android.graphics.drawable.Drawable[] d = getCompoundDrawables();
            if (centerGroup && d[0] != null && getText() != null) {
                float textW = getPaint().measureText(getText().toString());
                float content = d[0].getIntrinsicWidth() + getCompoundDrawablePadding() + textW;
                float avail = getWidth() - getPaddingLeft() - getPaddingRight();
                float shift = Math.max(0, (avail - content) / 2f);
                if (shift > 0) {
                    canvas.save();
                    canvas.translate(shift, 0);
                    super.onDraw(canvas);
                    canvas.restore();
                    return;
                }
            }
            super.onDraw(canvas);
        }
    }

    public static void tintIcons(TextView view, int color) {
        for (android.graphics.drawable.Drawable drawable : view.getCompoundDrawables())
            if (drawable instanceof IconDrawable) ((IconDrawable) drawable).setColor(color);
    }

    /** 胶囊按钮的三种状态：聚焦=亮绿实底，选中=淡绿底，常态=淡白底。 */
    public static void buttonState(TextView view, boolean focused, boolean selected) {
        Context c = view.getContext();
        if (focused || view.getScaleX() != 1f) focus(view);
        view.setBackground(pill(c, focused ? FOCUS_FILL : selected ? TINT_PRIMARY : FILL_2));
        int foreground = focused ? ON_FOCUS : selected ? ACCENT_TEXT : TEXT;
        view.setTextColor(foreground);
        tintIcons(view, foreground);
        view.setTypeface(Typeface.defaultFromStyle(
                focused || selected ? Typeface.BOLD : Typeface.NORMAL));
    }

    /** 可选中块的统一画法：静止=淡底正文色；选中=品牌淡底+品牌粗体；聚焦=实心高亮+反色粗体。 */
    public static void tile(TextView v, boolean focused, boolean selected, float radiusDp) {
        Context c = v.getContext();
        v.setBackground(round(c, focused ? FOCUS_FILL : selected ? TINT_PRIMARY : FILL_2, radiusDp));
        v.setTextColor(focused ? ON_FOCUS : selected ? ACCENT_TEXT : TEXT);
        v.setTypeface(Typeface.defaultFromStyle(
                focused || selected ? Typeface.BOLD : Typeface.NORMAL));
        tintIcons(v, v.getCurrentTextColor());
    }

    public static TextView action(Context c, String label, int type, boolean primary) {
        TextView view = new ActionText(c);
        view.setText(label);
        view.setTextSize(14);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setMinHeight(dp(c, 44));
        padding(view, 20, 0, 20, 0);
        if (type >= 0) icon(view, type, TEXT, 18);
        else view.setCompoundDrawables(null, null, null, null);
        buttonState(view, false, false);
        view.setOnFocusChangeListener((v, has) -> buttonState(view, has, false));
        return view;
    }

    /** 旧接口：胶囊按钮（亮/暗两种基调），现在统一为设计系统的动作按钮。 */
    public static TextView button(Context c, String label, boolean light) {
        return action(c, label, -1, false);
    }

    /** 旧接口：无底色的可聚焦文字。 */
    public static TextView plainButton(Context c, String label, int color) {
        final TextView t = text(c, label, 15f, color, false);
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> {
            t.setTextColor(has ? ON_FOCUS : color);
            t.setBackground(has ? pill(c, FOCUS_FILL) : null);
        });
        return t;
    }

    /** 旧接口：焦点放大已废弃 —— 统一改为实底色块，不缩放。 */
    public static void focusScale(final View v, final float ignored) {
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, has) -> focus(view));
    }

    // ------------------------------------------------------------ 聚焦状态

    public static void focus(View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationZ(0f);
    }

    public static void enter(View v, float dxDp, float dyDp, long ms) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(dp(v.getContext(), (int) dxDp));
        v.setTranslationY(dp(v.getContext(), (int) dyDp));
        v.animate().alpha(1f).translationX(0).translationY(0).setDuration(ms)
                .setInterpolator(ease()).start();
    }

    /** 标准缓出曲线（Material emphasized decelerate），所有位移/淡入动画共用。 */
    public static android.animation.TimeInterpolator ease() {
        return new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    }

    /** 平滑淡出后隐藏，结束时恢复不透明度，方便下次直接显示。 */
    public static void fadeOut(final View v, long ms) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(ms).setInterpolator(ease()).withEndAction(() -> {
            v.setVisibility(View.GONE);
            v.setAlpha(1f);
        }).start();
    }

    /** 淡入显示。 */
    public static void fadeIn(final View v, long ms) {
        if (v == null) return;
        v.animate().cancel();
        if (v.getVisibility() != View.VISIBLE) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
        }
        v.animate().alpha(1f).setDuration(ms).setInterpolator(ease()).start();
    }

    /** 白底圆角品牌图标（充足留白，深浅主题下均保持白底质感）。 */
    public static android.widget.FrameLayout brandIcon(Context c, int sizeDp) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        float radius = Math.max(10f, Math.round(sizeDp * 0.28f));
        box.setBackground(round(c, 0xFFFFFFFF, radius));
        android.widget.ImageView mark = new android.widget.ImageView(c);
        mark.setImageResource(ink.pengfei.douyin.R.drawable.brand_mark);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, Math.max(3, Math.round(sizeDp * 0.08f)));
        box.setPadding(pad, pad, pad, pad);
        box.addView(mark, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return box;
    }

    /** 「抖音TV」字标：TV 两个字使用标志绿强调。 */
    public static TextView wordmark(Context c, float sizeSp) {
        android.text.SpannableString word = new android.text.SpannableString("抖音TV");
        word.setSpan(new android.text.style.ForegroundColorSpan(ACCENT_TEXT), 2, 4, 0);
        TextView t = text(c, "", sizeSp, TEXT, true);
        t.setText(word);
        t.setSingleLine(true);
        t.setLineSpacing(0, 1f);
        return t;
    }

    /** 骨架屏呼吸动画：挂到窗口时开始，移除时停止，不占用后台资源。 */
    public static <T extends View> T pulse(final T v) {
        final android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, .45f);
        anim.setDuration(820);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { if (!anim.isStarted()) anim.start(); }
            @Override public void onViewDetachedFromWindow(View view) { anim.cancel(); view.setAlpha(1f); }
        });
        return v;
    }

    public static void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(.94f);
        v.setScaleY(.94f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(ease()).start();
    }

    private static final class NoticeText extends TextView {
        Runnable hideTask;
        NoticeText(Context c) { super(c); }
    }

    /** 顶部轻提示：深色半透明胶囊，顶部居中滑入，2.2 秒后自动滑出，同容器内复用单实例不堆叠。 */
    public static void notice(final android.widget.FrameLayout host, String message) {
        if (host == null || message == null || message.isEmpty()) return;
        final Context c = host.getContext();
        NoticeText view = null;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child instanceof NoticeText) {
                view = (NoticeText) child;
                break;
            }
        }
        if (view == null) {
            view = new NoticeText(c);
            view.setTextSize(16);
            view.setTextColor(0xFFFFFFFF);
            view.setIncludeFontPadding(false);
            view.setGravity(Gravity.CENTER);
            view.setBackground(round(c, 0xEB1C1C1F, 24));
            padding(view, 24, 13, 24, 13);
            view.setMaxWidth(dp(c, 720));
            view.setVisibility(View.GONE);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(c, 40);
            host.addView(view, lp);
        }
        final NoticeText target = view;
        if (target.hideTask != null) target.removeCallbacks(target.hideTask);
        target.animate().cancel();
        target.setText(message);
        host.bringChildToFront(target);
        if (target.getVisibility() != View.VISIBLE) {
            target.setAlpha(0f);
            target.setTranslationY(-dp(c, 8));
            target.setVisibility(View.VISIBLE);
        }
        target.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(ease()).start();
        target.hideTask = () -> target.animate().alpha(0f).translationY(-dp(c, 8)).setDuration(220)
                .setInterpolator(ease()).withEndAction(() -> target.setVisibility(View.GONE)).start();
        target.postDelayed(target.hideTask, 2200);
    }

    /** 返回键完整消费（含长按重复），避免弹窗被同一次按键关两次。 */
    public static void closeOnBack(android.app.Dialog dialog) {
        final boolean[] pressed = {false};
        final long openedAt = android.os.SystemClock.uptimeMillis();
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK
                    && key != android.view.KeyEvent.KEYCODE_ESCAPE
                    && key != android.view.KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getDownTime() < openedAt) return true;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                pressed[0] = true;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                boolean close = pressed[0] && !event.isCanceled();
                pressed[0] = false;
                if (close) dialog.dismiss();
            }
            return true;
        });
    }

    // ------------------------------------------------------------ 常用容器

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBaselineAligned(false);
        return l;
    }

    public static View spacer(Context c) { return new View(c); }

    public static View spacer(Context c, int height) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, height));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    public static void padding(View v, float l, float t, float r, float b) {
        v.setPadding(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
    }

    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
            m.setMargins(dp(v, l), dp(v, t), dp(v, r), dp(v, b));
            v.setLayoutParams(m);
        }
    }

    public static View blank(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    public static void hide(View v) { if (v != null) v.setVisibility(View.GONE); }
    public static void show(View v) { if (v != null) v.setVisibility(View.VISIBLE); }

    /** 供旧代码调用：窗口底色（深色）。 */
    public static int bg(Context c) { return BG; }
    public static int text(Context c) { return TEXT; }
    public static int sub(Context c) { return TEXT_3; }

    /**
     * 轻量纯图标按钮（无外圈、无胶囊、无下划线）：图标居中 + 下方 2 字小标题；
     * 选中/聚焦时以主题色 + 缩放标出；喜欢按钮仅在「已喜欢」时呈现红色实心。
     */
    public static final class IconButton extends LinearLayout {
        private final android.widget.FrameLayout iconBadge;
        private final IconView icon;
        private final TextView label;
        private final boolean overlay;
        private final boolean likeStyle;
        private boolean active;
        private int focusColorOverride;

        public IconButton(Context c, int iconType, String text, String description,
                          boolean overlay, boolean likeStyle) {
            this(c, iconType, text, description, overlay, likeStyle, false);
        }

        public IconButton(Context c, int iconType, String text, String description,
                          boolean overlay, boolean likeStyle, boolean ignoredLabelPos) {
            super(c);
            this.overlay = overlay;
            this.likeStyle = likeStyle;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setFocusable(true);
            setClickable(true);
            setClipChildren(false);
            setClipToPadding(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
            setContentDescription(description);
            setMinimumWidth(dp(c, overlay ? 54 : 42));
            setBackground(null);

            int iconDp = overlay ? 21 : 20;
            icon = new IconView(c, iconType, iconDp);
            if (overlay) {
                iconBadge = new android.widget.FrameLayout(c);
                int badgePx = dp(c, 44);
                android.widget.FrameLayout.LayoutParams iconLp =
                        new android.widget.FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER);
                iconBadge.addView(icon, iconLp);
                addView(iconBadge, lp(badgePx, badgePx));
            } else {
                iconBadge = null;
                addView(icon, lp(dp(c, iconDp), dp(c, iconDp)));
            }

            label = Ui.text(c, text == null ? "" : text, overlay ? 12 : 11, 0xE6FFFFFF, false);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            if (overlay) {
                label.setShadowLayer(dp(c, 4), 0, dp(c, 1), 0xCC000000);
            }
            LayoutParams lpLabel = lp(-2, -2);
            lpLabel.topMargin = dp(c, overlay ? 5 : 3);
            addView(label, lpLabel);

            setOnFocusChangeListener((v, has) -> repaint());
            repaint();
        }

        public void setIconType(int type) {
            icon.setType(type);
        }

        public void setLabelText(String text) {
            label.setText(text == null ? "" : text);
            repaint();
        }

        public void setActive(boolean value) {
            active = value;
            if (likeStyle) icon.setFilled(value);
            repaint();
        }

        public void setFocusColor(int color) {
            focusColorOverride = color;
            repaint();
        }

        public void repaint() {
            Context c = getContext();
            boolean focused = hasFocus();
            setBackground(null);
            setTranslationZ(0f);

            boolean hasText = label.getText() != null && label.getText().length() > 0;
            label.setVisibility(hasText ? VISIBLE : GONE);

            if (overlay && iconBadge != null) {
                padding(this, 4, 2, 4, 2);
                float scale = focused ? 1.08f : 1f;
                animate().cancel();
                animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(ease()).start();

                if (likeStyle && active) {
                    if (focused) {
                        iconBadge.setBackground(pill(c, VIDEO_ACCENT));
                        icon.setColor(0xFFFFFFFF);
                        label.setTextColor(0xFFFF6B81);
                    } else {
                        iconBadge.setBackground(roundStroke(c, 0x662B0D14, 22, 0x99FF4D6D, 1));
                        icon.setColor(VIDEO_ACCENT);
                        label.setTextColor(0xFFFF5C77);
                    }
                    label.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                    return;
                }

                int accent = focusColorOverride != 0 ? focusColorOverride : 0xFFC6ED92;
                if (focused) {
                    iconBadge.setBackground(pill(c, accent));
                    icon.setColor(0xFF121F09);
                    label.setTextColor(accent);
                    label.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                } else if (active && !likeStyle) {
                    iconBadge.setBackground(roundStroke(c, 0x401B2913, 22, 0x80C6ED92, 1));
                    icon.setColor(accent);
                    label.setTextColor(accent);
                    label.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                } else {
                    iconBadge.setBackground(roundStroke(c, 0x730E1211, 22, 0x33FFFFFF, 1));
                    icon.setColor(0xF5FFFFFF);
                    label.setTextColor(0xEBFFFFFF);
                    label.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL));
                }
                return;
            }

            float scale = focused ? 1.12f : 1f;
            animate().cancel();
            setScaleX(scale);
            setScaleY(scale);
            padding(this, 6, 3, 6, 3);

            if (likeStyle && active) {
                int red = VIDEO_ACCENT;
                icon.setColor(red);
                label.setTextColor(red);
                label.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
                return;
            }

            int accent = focusColorOverride != 0 ? focusColorOverride : ACCENT_TEXT;
            int iconColor = focused ? accent
                    : (active && !likeStyle) ? accent : TEXT_2;
            int textColor = focused ? accent
                    : (active && !likeStyle) ? accent : TEXT_3;
            icon.setColor(iconColor);
            label.setTextColor(textColor);
            label.setTypeface(Typeface.defaultFromStyle(focused ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    public static IconButton iconAction(Context c, int icon, String label, String description,
                                        boolean overlay, boolean likeStyle, Runnable onClick) {
        return iconAction(c, icon, label, description, overlay, likeStyle, false, onClick);
    }

    public static IconButton iconAction(Context c, int icon, String label, String description,
                                        boolean overlay, boolean likeStyle, boolean labelOnLeft,
                                        Runnable onClick) {
        IconButton btn = new IconButton(c, icon, label, description, overlay, likeStyle, labelOnLeft);
        btn.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return btn;
    }

    public static void overlayButtonState(TextView button, boolean focused, boolean active) {
        Context c = button.getContext();
        focus(button);
        if (focused) {
            button.setBackground(pill(c, 0xFFC6ED92));
            button.setTextColor(0xFF121F09);
            tintIcons(button, 0xFF121F09);
            button.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        } else {
            button.setBackground(roundStroke(c, active ? 0x30C6ED92 : 0x7A0F1412, 100,
                    active ? 0x80C6ED92 : 0x2EFFFFFF, 1));
            int fg = active ? 0xFFC6ED92 : 0xFFFFFFFF;
            button.setTextColor(fg);
            tintIcons(button, fg);
            button.setTypeface(Typeface.defaultFromStyle(active ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    public static void overlayCircleState(android.widget.FrameLayout button, boolean focused) {
        Context c = button.getContext();
        focus(button);
        button.setBackground(focused
                ? pill(c, 0xFFC6ED92)
                : roundStroke(c, 0x7A0F1412, 100, 0x2EFFFFFF, 1));
        Object tag = button.getTag();
        if (tag instanceof IconView) {
            ((IconView) tag).setColor(focused ? 0xFF121F09 : 0xFFFFFFFF);
        }
    }

    public static android.widget.FrameLayout overlayCircleButton(Context c, String description,
                                                                 int iconType, Runnable onClick) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        box.setClickable(true);
        box.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) box.setDefaultFocusHighlightEnabled(false);
        box.setContentDescription(description);
        IconView iv = new IconView(c, iconType, 20);
        box.addView(iv, new android.widget.FrameLayout.LayoutParams(
                dp(c, 20), dp(c, 20), Gravity.CENTER));
        box.setTag(iv);
        overlayCircleState(box, false);
        box.setOnFocusChangeListener((v, has) -> overlayCircleState(box, has));
        box.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return box;
    }

    public static TextView overlayControlButton(Context c, String label, String description,
                                                  int icon, Runnable onClick) {
        TextView button = action(c, label, icon, false);
        button.setTextSize(13);
        button.setContentDescription(description);
        button.setFocusable(true);
        if (label.isEmpty()) {
            button.setPadding(0, 0, 0, 0);
            button.setCompoundDrawablePadding(0);
            if (icon >= 0) icon(button, icon, 0xFFFFFFFF, 20);
        } else {
            padding(button, 12, 0, 14, 0);
        }
        overlayButtonState(button, false, false);
        button.setOnFocusChangeListener((v, has) -> overlayButtonState(button, has, Boolean.TRUE.equals(button.getTag())));
        button.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return button;
    }

    // ------------------------------------------------------------ 骨架屏

    public static View skeletonBlock(Context c, int widthDp, int heightDp, float radiusDp) {
        View v = new View(c);
        v.setBackground(round(c, SKELETON, radiusDp));
        v.setLayoutParams(lp(widthDp < 0 ? widthDp : dp(c, widthDp), dp(c, heightDp)));
        return v;
    }

    public static LinearLayout skeletonGrid(Context c, int cols, int rows) {
        return pulse(new SkeletonGrid(c, cols, rows));
    }

    private static final class SkeletonGrid extends LinearLayout {
        private final int fallbackCols;
        private final int rows;
        private int builtWidth = -1;

        SkeletonGrid(Context c, int fallbackCols, int rows) {
            super(c);
            this.fallbackCols = Math.max(1, fallbackCols);
            this.rows = Math.max(1, rows);
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            rebuild(fallbackCols, CARD_W, CARD_H, dp(c, 16), dp(c, 22));
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && w != builtWidth) {
                builtWidth = w;
                post(() -> {
                    if (!isAttachedToWindow()) return;
                    ink.pengfei.douyin.ui.widget.CardGrid.Metrics m =
                            ink.pengfei.douyin.ui.widget.CardGrid.measureMetrics(getContext(), builtWidth);
                    rebuild(m.cols, m.cardW, m.cardH, m.colGap, m.rowGap);
                });
            }
        }

        private void rebuild(int cols, int cardWDp, int cardHDp, int colGapPx, int rowGapPx) {
            Context c = getContext();
            removeAllViews();
            int outerW = dp(c, cardWDp + 4);
            int w = dp(c, cardWDp);
            int h = dp(c, cardHDp);
            int titleW = Math.max(dp(c, 48), Math.round(w * 0.78f));
            int titleH = dp(c, 13);
            int topPad = dp(c, 2);
            int sidePad = dp(c, 2);
            for (int r = 0; r < rows; r++) {
                LinearLayout line = row(c);
                line.setBaselineAligned(false);
                for (int i = 0; i < cols; i++) {
                    LinearLayout card = column(c);
                    card.setPadding(sidePad, topPad, sidePad, 0);
                    View poster = new View(c);
                    poster.setBackground(round(c, SKELETON, 12));
                    card.addView(poster, lp(w, h));
                    View title = new View(c);
                    title.setBackground(round(c, SKELETON, 6));
                    card.addView(title, margin(titleW, titleH, 0, dp(c, 10), 0, dp(c, 3)));
                    line.addView(card, margin(outerW, -2, i == 0 ? 0 : colGapPx, 0, 0, 0));
                }
                addView(line, margin(-1, -2, 0, 0, 0, rowGapPx));
            }
        }
    }

    public static LinearLayout skeletonRows(Context c, int count) {
        LinearLayout box = column(c);
        for (int i = 0; i < count; i++) {
            LinearLayout item = row(c);
            item.setGravity(Gravity.CENTER_VERTICAL);
            padding(item, 10, 6, 10, 6);
            View avatar = new View(c);
            avatar.setBackground(pill(c, SKELETON));
            item.addView(avatar, lp(dp(c, 38), dp(c, 38)));
            LinearLayout lines = column(c);
            padding(lines, 10, 0, 0, 0);
            lines.addView(skeletonBlock(c, 108, 14, 6));
            item.addView(lines, lp(0, -2, 1f));
            box.addView(item, margin(-1, dp(c, 58), 0, 0, 0, dp(c, 4)));
        }
        return pulse(box);
    }

    public static LinearLayout skeletonProfileHeader(Context c) {
        LinearLayout card = row(c);
        card.setBackground(roundStroke(c, SURFACE, 20, SEPARATOR, 1));
        padding(card, 26, 20, 26, 20);
        View avatar = new View(c);
        avatar.setBackground(pill(c, SKELETON));
        card.addView(avatar, lp(dp(c, 74), dp(c, 74)));
        LinearLayout info = column(c);
        padding(info, 20, 0, 16, 0);
        info.addView(skeletonBlock(c, 130, 20, 8));
        info.addView(skeletonBlock(c, 190, 12, 6), margin(dp(c, 190), dp(c, 12), 0, dp(c, 10), 0, 0));
        card.addView(info, lp(0, -2, 1f));
        for (int i = 0; i < 3; i++) {
            LinearLayout stat = column(c);
            stat.setGravity(Gravity.CENTER_HORIZONTAL);
            stat.addView(skeletonBlock(c, 34, 18, 6));
            stat.addView(skeletonBlock(c, 28, 10, 5), margin(dp(c, 28), dp(c, 10), 0, dp(c, 6), 0, 0));
            card.addView(stat, margin(-2, -2, dp(c, 12), 0, dp(c, 12), 0));
        }
        return pulse(card);
    }
}
