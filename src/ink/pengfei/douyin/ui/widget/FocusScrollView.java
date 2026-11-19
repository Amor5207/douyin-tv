package ink.pengfei.douyin.ui.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/** 让获得焦点的子项平滑滚入视窗（TV 遥控焦点驱动）。 */
public class FocusScrollView extends ScrollView {
    private Runnable onNearBottom;

    public FocusScrollView(Context context) {
        super(context);
        setSmoothScrollingEnabled(true);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
    }

    public void setOnNearBottom(Runnable listener) {
        onNearBottom = listener;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        checkNearBottom();
    }

    public void checkNearBottom() {
        if (onNearBottom == null || getChildCount() == 0 || getHeight() <= 0) return;
        View content = getChildAt(0);
        if (content.getHeight() <= 0) return;
        int remaining = content.getBottom() - (getScrollY() + getHeight());
        if (remaining <= ink.pengfei.douyin.ui.Ui.dp(getContext(), 420)) {
            onNearBottom.run();
        }
    }

    protected boolean shouldScrollOnFocus(View focused) {
        return true;
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused != null && shouldScrollOnFocus(focused)) {
            post(() -> scrollFocusedChildIntoView(focused));
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (child == null || rectangle == null) return false;
        Rect rect = new Rect(rectangle);
        offsetDescendantRectToMyCoords(child, rect);
        int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
        if (delta == 0) return false;
        if (immediate) scrollBy(0, delta);
        else smoothScrollBy(0, delta);
        return true;
    }

    public void scrollFocusedChildIntoView(View focused) {
        if (focused == null || !shouldScrollOnFocus(focused) || !isDescendant(focused)) return;
        Rect rect = new Rect(0, 0, focused.getWidth(), focused.getHeight());
        offsetDescendantRectToMyCoords(focused, rect);
        int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
        if (delta != 0) smoothScrollBy(0, delta);
    }

    private boolean isDescendant(View view) {
        View current = view;
        while (current != null && current != this) {
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return current == this;
    }
}
