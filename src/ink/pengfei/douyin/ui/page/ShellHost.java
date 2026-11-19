package ink.pengfei.douyin.ui.page;

import ink.pengfei.douyin.util.Store;

/** 页面与主框架之间的桥。 */
public interface ShellHost {
    void toast(String msg);

    void openLogin();

    void openRelay();

    void openShareEntry(Store.ShareEntry e);

    void playRecent(int index);

    void refreshDanmakuSettings();

    void refreshPlaybackSettings();

    void onTabStep(int dir);

    boolean isFullscreen();

    void onToggleFullscreen();

    default void onChromeVisibilityChanged(boolean visible) {}
}
