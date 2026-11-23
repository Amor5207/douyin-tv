package ink.pengfei.douyin.ui;

import android.app.Activity;
import android.view.View;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.ui.widget.TvDialog;

/** 播放页弹幕设置弹窗：一级菜单展示当前值，进入后在二级菜单单选，选完回到一级保持焦点。 */
public final class PlayerOverlays {
    private PlayerOverlays() {}

    public static void openDanmakuSettings(Activity host, View returnFocus,
                                            Runnable onChanged, Runnable onDismiss) {
        openDanmakuLevel1(host, 0, returnFocus, onChanged, onDismiss);
    }

    private static void openDanmakuLevel1(Activity host, int focusIndex, View returnFocus,
                                          Runnable onChanged, Runnable onDismiss) {
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        boolean on = DanmakuView.enabled();
        String[] alphas = new String[DanmakuView.ALPHAS.length];
        for (int i = 0; i < DanmakuView.ALPHAS.length; i++) {
            alphas[i] = DanmakuView.ALPHAS[i] + "%";
        }
        String[] rows = {
                "弹幕\t" + (on ? "开启" : "关闭"),
                "滚动速度\t" + DanmakuView.SPEED_LABELS[DanmakuView.speedIndex()],
                "文字大小\t" + DanmakuView.SIZE_LABELS[DanmakuView.sizeIndex()],
                "不透明度\t" + alphas[DanmakuView.alphaIndex()]
        };
        TvDialog.options(host, "弹幕设置", null, rows, -1, focusIndex, returnFocus, idx -> {
            if (idx == 0) {
                TvDialog.options(host, "弹幕开关", null, new String[]{"开启", "关闭"},
                        on ? 0 : 1, on ? 0 : 1, null, k -> {
                            App.get().store().setDanmakuOn(k == 0);
                            if (onChanged != null) onChanged.run();
                            openDanmakuLevel1(host, 0, returnFocus, onChanged, onDismiss);
                        }, () -> openDanmakuLevel1(host, 0, returnFocus, onChanged, onDismiss));
            } else if (idx == 1) {
                int cur = DanmakuView.speedIndex();
                TvDialog.options(host, "滚动速度", null, DanmakuView.SPEED_LABELS,
                        cur, cur, null, k -> {
                            App.get().store().setDanmakuSpeed(k);
                            if (onChanged != null) onChanged.run();
                            openDanmakuLevel1(host, 1, returnFocus, onChanged, onDismiss);
                        }, () -> openDanmakuLevel1(host, 1, returnFocus, onChanged, onDismiss));
            } else if (idx == 2) {
                int cur = DanmakuView.sizeIndex();
                TvDialog.options(host, "文字大小", null, DanmakuView.SIZE_LABELS,
                        cur, cur, null, k -> {
                            App.get().store().setDanmakuSize(k);
                            if (onChanged != null) onChanged.run();
                            openDanmakuLevel1(host, 2, returnFocus, onChanged, onDismiss);
                        }, () -> openDanmakuLevel1(host, 2, returnFocus, onChanged, onDismiss));
            } else {
                int cur = DanmakuView.alphaIndex();
                TvDialog.options(host, "不透明度", null, alphas,
                        cur, cur, null, k -> {
                            App.get().store().setDanmakuAlpha(k);
                            if (onChanged != null) onChanged.run();
                            openDanmakuLevel1(host, 3, returnFocus, onChanged, onDismiss);
                        }, () -> openDanmakuLevel1(host, 3, returnFocus, onChanged, onDismiss));
            }
        }, onDismiss);
    }
}
