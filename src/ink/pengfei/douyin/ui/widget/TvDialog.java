package ink.pengfei.douyin.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;

/** 绿果风格的应用内确认框与列表选择框。 */
public final class TvDialog {
    private TvDialog() {}

    public static Dialog confirm(Activity host, String title, String message,
                                 String action, Runnable accept) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 28, 24, 28, 24);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 16, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 22, Ui.TEXT, true), Ui.lp(-1, -2));
        if (message != null && !message.isEmpty()) {
            TextView body = Ui.text(host, message, 15, Ui.TEXT_2, false);
            LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
            lp.topMargin = Ui.dp(host, 14);
            panel.addView(body, lp);
        }
        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ap = Ui.lp(-1, -2);
        ap.topMargin = Ui.dp(host, 24);
        panel.addView(actions, ap);
        TextView cancel = Ui.action(host, "取消", -1, false);
        cancel.setOnClickListener(v -> d.dismiss());
        actions.addView(cancel, Ui.lp(-2, Ui.dp(host, 46)));
        TextView ok = Ui.action(host, action, -1, false);
        ok.setOnClickListener(v -> { d.dismiss(); accept.run(); });
        LinearLayout.LayoutParams op = Ui.lp(-2, Ui.dp(host, 46));
        op.leftMargin = Ui.dp(host, 18);
        actions.addView(ok, op);
        d.setContentView(panel);
        Ui.popIn(panel);
        d.setCanceledOnTouchOutside(false);
        Ui.closeOnBack(d);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 520),
                    host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 40)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        cancel.requestFocus();
        return d;
    }

    public interface Pick { void pick(int index); }

    public static Dialog choose(Activity host, String title, String hint, String[] options,
                                int selected, View returnFocus, Pick pick) {
        return options(host, title, hint, options, selected, Math.max(0, selected),
                returnFocus, pick, null);
    }

    /**
     * 绿果风格一级/二级菜单弹窗：
     * 传 "名称\t当前值" 且 selected=-1 表示一级菜单，点击行后再开二级选择窗；
     * 传普通选项且 selected>=0 表示二级单选列表，当前值打勾高亮。
     */
    public static Dialog options(Activity host, String title, String hint, String[] options,
                                 int selected, int focusIndex, View returnFocus,
                                 Pick pick, Runnable onDismissCancel) {
        Dialog d = new Dialog(host);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(host);
        Ui.padding(panel, 24, 20, 24, 18);
        panel.setBackground(Ui.roundStroke(host, Ui.SURFACE, 16, Ui.SEPARATOR, 1));
        panel.addView(Ui.text(host, title, 21, Ui.TEXT, true), Ui.lp(-1, -2));
        if (hint != null && !hint.isEmpty()) {
            TextView h = Ui.text(host, hint, 13, Ui.TEXT_3, false);
            LinearLayout.LayoutParams hl = Ui.lp(-1, -2);
            hl.topMargin = Ui.dp(host, 6);
            panel.addView(h, hl);
        }
        ScrollView scroll = new FocusScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.column(host);
        Ui.padding(list, 0, 14, 0, 2);
        scroll.addView(list);
        final boolean[] selectedOption = {false};
        View focus = null;
        int wantFocus = Math.max(0, Math.min(options.length - 1, focusIndex));
        for (int n = 0; n < options.length; n++) {
            final int idx = n;
            final boolean on = n == selected;
            LinearLayout row = Ui.row(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) row.setDefaultFocusHighlightEnabled(false);
            Ui.padding(row, 18, 0, 14, 0);
            String[] parts = options[n].split("\t", 2);
            TextView label = Ui.text(host, parts[0], 17, Ui.TEXT, on);
            row.addView(label, Ui.lp(0, -2, 1));
            final TextView value = parts.length > 1 ? Ui.text(host, parts[1], 15, Ui.TEXT_3, false) : null;
            if (value != null) {
                LinearLayout.LayoutParams vl = Ui.lp(-2, -2);
                vl.rightMargin = Ui.dp(host, 6);
                row.addView(value, vl);
            }
            final boolean isParent = value != null;
            IconView check = new IconView(host, isParent ? IconView.CHEVRON_RIGHT : IconView.CHECK, isParent ? 15 : 18);
            check.setVisibility(isParent || on ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, Ui.lp(Ui.dp(host, isParent ? 15 : 18), Ui.dp(host, isParent ? 15 : 18)));
            Runnable paint = () -> {
                boolean f = row.hasFocus();
                row.setBackground(f ? Ui.round(host, Ui.FOCUS_FILL, 12) : null);
                int fg = f ? Ui.ON_FOCUS : on ? Ui.ACCENT_TEXT : Ui.TEXT;
                label.setTextColor(fg);
                label.setTypeface(android.graphics.Typeface.defaultFromStyle(
                        f || on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
                check.setColor(f ? Ui.ON_FOCUS : isParent ? Ui.TEXT_4 : fg);
                if (value != null) value.setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT_3);
            };
            paint.run();
            row.setOnFocusChangeListener((v, f) -> paint.run());
            row.setOnClickListener(v -> {
                selectedOption[0] = true;
                d.dismiss();
                pick.pick(idx);
            });
            LinearLayout.LayoutParams lp = Ui.lp(-1, Ui.dp(host, 50));
            lp.bottomMargin = Ui.dp(host, 4);
            list.addView(row, lp);
            if (n == wantFocus) focus = row;
        }
        int maxH = host.getResources().getDisplayMetrics().heightPixels - Ui.dp(host, 160);
        panel.addView(scroll, Ui.lp(-1, Math.min(maxH, Ui.dp(host, 16 + options.length * 54))));
        d.setContentView(panel);
        Ui.popIn(panel);
        d.setCanceledOnTouchOutside(true);
        Ui.closeOnBack(d);
        d.setOnDismissListener(x -> {
            if (!selectedOption[0]) {
                if (returnFocus != null) returnFocus.post(returnFocus::requestFocus);
                if (onDismissCancel != null) onDismissCancel.run();
            }
        });
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(host, 440),
                    host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 48)), -2);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.55f);
        }
        if (focus != null) focus.requestFocus();
        return d;
    }
}
