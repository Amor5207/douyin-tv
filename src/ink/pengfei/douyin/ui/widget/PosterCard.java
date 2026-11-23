package ink.pengfei.douyin.ui.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.util.Images;

/** 海报卡片：RoundFrame 同心裁切 + 聚焦环 + 渐变底 + 角标 + 双行元信息。 */
public class PosterCard extends LinearLayout {
    private final RoundFrame frame;
    private final ImageView cover;
    private final TextView missing;
    private final TextView topBadge;
    private final TextView badge;
    private final TextView titleView;
    private final int cardW;
    private final int cardH;
    public String id = "";
    public Object payload;
    private String sub = "";
    private TextView subtitle;

    public PosterCard(Context c, int widthDp, int heightDp) {
        super(c);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), 0);
        cardW = Ui.dp(c, widthDp);
        cardH = Ui.dp(c, heightDp);

        frame = new RoundFrame(c, 12);
        frame.setBackgroundColor(Ui.SKELETON);
        addView(frame, new LinearLayout.LayoutParams(cardW, cardH));

        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        missing = Ui.text(c, "暂无封面", 12, Ui.TEXT_4, false);
        missing.setGravity(Gravity.CENTER);
        frame.addView(missing, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View gradient = new View(c);
        gradient.setBackground(Ui.scrimBottom(c));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        gp.gravity = Gravity.BOTTOM;
        frame.addView(gradient, gp);

        topBadge = makeCornerBadge(c, Gravity.TOP | Gravity.LEFT);
        frame.addView(topBadge, badgeParams(c, Gravity.TOP | Gravity.LEFT));

        badge = makeCornerBadge(c, Gravity.BOTTOM | Gravity.RIGHT);
        frame.addView(badge, badgeParams(c, Gravity.BOTTOM | Gravity.RIGHT));

        titleView = Ui.text(c, "", 14, Ui.TEXT_2, false);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = Ui.lp(cardW, -2);
        tp.topMargin = Ui.dp(c, 7);
        addView(titleView, tp);

        setFocusable(true);
        setOnFocusChangeListener((v, hasFocus) -> {
            frame.setRing(hasFocus, Ui.FOCUS_RING, 3f);
            titleView.setTextColor(hasFocus ? Ui.TEXT : Ui.TEXT_2);
            titleView.setTypeface(null, hasFocus ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            titleView.setAlpha(hasFocus ? 1f : .92f);
            Ui.focus(v);
        });
    }

    private static TextView makeCornerBadge(Context c, int gravity) {
        TextView t = Ui.text(c, "", 11, Ui.VIDEO_TEXT, true);
        t.setBackground(Ui.round(c, 0x99000000, 6));
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        t.setVisibility(View.GONE);
        return t;
    }

    private static FrameLayout.LayoutParams badgeParams(Context c, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        int m = Ui.dp(c, 7);
        if ((gravity & Gravity.LEFT) == Gravity.LEFT) lp.leftMargin = m;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) lp.rightMargin = m;
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = m;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) lp.bottomMargin = m;
        return lp;
    }

    public void bind(String coverUrl, String title, String badgeText) {
        bind(coverUrl, title, badgeText, "", "");
    }

    public void bind(String coverUrl, String title, String badgeText, String subtitleText) {
        bind(coverUrl, title, badgeText, "", subtitleText);
    }

    public void bind(String coverUrl, String title, String badgeText, String topBadgeText, String subtitleText) {
        titleView.setText(title == null ? "" : title);
        sub = subtitleText == null ? "" : subtitleText;
        applyBadge(topBadge, topBadgeText);
        applyBadge(badge, badgeText);
        setSubtitle(sub);
        if (coverUrl != null && !coverUrl.isEmpty()) {
            missing.setVisibility(View.GONE);
            Images.load(coverUrl, cover, cardW, 0);
        } else {
            cover.setTag(null);
            cover.setImageDrawable(null);
            missing.setVisibility(View.VISIBLE);
        }
    }

    private static void applyBadge(TextView view, String text) {
        if (text != null && !text.isEmpty()) {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    public void setSubtitle(String text) {
        sub = text == null ? "" : text;
        if (subtitle == null) {
            subtitle = Ui.text(getContext(), "", 12, Ui.TEXT_3, false);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = Ui.lp(cardW, -2);
            addView(subtitle, sp);
        }
        subtitle.setText(sub);
        subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setBadgeTint(int color) {
        badge.setTextColor(color);
    }

    public TextView titleView() { return titleView; }

    public int cardWidth() { return cardW; }

    @Override
    public void setOnClickListener(OnClickListener l) {
        super.setOnClickListener(l);
        setClickable(true);
    }
}
