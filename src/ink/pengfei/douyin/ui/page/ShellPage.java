package ink.pengfei.douyin.ui.page;

import android.widget.FrameLayout;

/** 左侧导航对应的嵌入式页面。 */
public abstract class ShellPage extends FrameLayout {
    public ShellPage(android.content.Context c) {
        super(c);
    }

    public abstract void onShow();

    /** 首页加载完成后在后台预载页面数据（不播放音视频），切换标签时直接显示。 */
    public void preload() {
    }

    public void onHide() {
    }

    public boolean onBack() {
        return false;
    }

    public abstract void focusFirst();
}
