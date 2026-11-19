package ink.pengfei.douyin.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class Theme {
    public static final int RED = 0xFFFE2C55;
    public static final int CYAN = 0xFF25F4EE;
    public static final int DARK_BG = 0xFF0B0B0F;
    public static final int DARK_CARD = 0xFF17171E;
    public static final int DARK_TEXT = 0xFFF4F4F7;
    public static final int DARK_SUB = 0xFF9B9BA5;
    private static final int BG = 0xFF0E0E12;
    private static final int LINE = 0xFF2A2A33;
    private static final int SOFT = 0xFF23232C;

    private Theme() {}

    public static int bg(Context c) { return BG; }
    public static int card(Context c) { return DARK_CARD; }
    public static int text(Context c) { return DARK_TEXT; }
    public static int sub(Context c) { return DARK_SUB; }
    public static int line(Context c) { return LINE; }
    public static int soft(Context c) { return SOFT; }

    public static void applyWindow(Activity a) {
        ink.pengfei.douyin.ui.Ui.applyTheme(a);
        Window w = a.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ink.pengfei.douyin.ui.Ui.BG));
        if (Build.VERSION.SDK_INT >= 19) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, float radius, int width) {
        GradientDrawable d = round(fill, radius);
        d.setStroke(width, strokeColor);
        return d;
    }
}
