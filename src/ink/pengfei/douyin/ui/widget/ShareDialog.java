package ink.pengfei.douyin.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.util.Images;

/**
 * TV 端扫码分享弹窗：
 * 生成当前视频的抖音官方链接二维码，手机抖音/微信扫一扫即可直接转发给好友，
 * 零网络开销、不触发网页端私信风控踢出。
 */
public final class ShareDialog {
    private final Dialog dialog;

    public static void show(Activity activity, Aweme video) {
        if (activity == null || activity.isFinishing() || video == null || video.id == null || video.id.isEmpty()) {
            return;
        }
        new ShareDialog(activity, video).open();
    }

    private ShareDialog(Activity activity, Aweme video) {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Ui.closeOnBack(dialog);

        String shareUrl = (video.images ? "https://www.douyin.com/note/" : "https://www.douyin.com/video/")
                + video.id;

        int dialogWidth = Ui.dp(activity, 700);
        int qrSize = Ui.dp(activity, 196);
        int qrPad = Ui.dp(activity, 12);
        int qrBoxSize = qrSize + qrPad * 2;

        LinearLayout panel = Ui.row(activity);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(panel, 30, 26, 30, 26);
        panel.setBackground(Ui.roundStroke(activity, Ui.SURFACE, 20, Ui.SEPARATOR, 1));

        // 左侧：标题 + 视频信息卡片 + 扫码引导 + 关闭按钮
        LinearLayout left = Ui.column(activity);
        left.addView(Ui.bold(activity, "扫码分享视频", 22, Ui.TEXT));

        LinearLayout preview = Ui.row(activity);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(preview, 12, 12, 14, 12);
        preview.setBackground(Ui.roundStroke(activity, Ui.SURFACE_2, 14, Ui.SEPARATOR, 1));

        ImageView cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.round(activity, Ui.BG, 10));
        if (video.cover != null && !video.cover.isEmpty()) {
            Images.load(video.cover, cover, Ui.dp(activity, 144), Ui.dp(activity, 10));
        }
        preview.addView(cover, new LinearLayout.LayoutParams(Ui.dp(activity, 64), Ui.dp(activity, 86)));

        LinearLayout meta = Ui.column(activity);
        String author = video.authorName == null || video.authorName.isEmpty()
                ? "抖音创作者" : "@" + video.authorName;
        TextView authorView = Ui.bold(activity, author, 15, Ui.TEXT);
        authorView.setSingleLine(true);
        authorView.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(authorView);

        String descText = video.desc == null || video.desc.trim().isEmpty()
                ? "精彩抖音视频" : video.desc.trim();
        TextView descView = Ui.text(activity, descText, 13, Ui.TEXT_2, false);
        descView.setMaxLines(3);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        descView.setLineSpacing(Ui.dp(activity, 3), 1f);
        meta.addView(descView, Ui.margin(-1, -2, 0, Ui.dp(activity, 6), 0, 0));

        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, -2, 1f);
        metaLp.leftMargin = Ui.dp(activity, 14);
        preview.addView(meta, metaLp);
        left.addView(preview, Ui.margin(-1, -2, 0, Ui.dp(activity, 16), 0, 0));

        TextView guide = Ui.text(activity,
                "使用手机「抖音」或「微信」扫一扫右侧二维码，\n即可在手机端打开并一键转发给好友。",
                13, Ui.TEXT_2, false);
        guide.setLineSpacing(Ui.dp(activity, 4), 1f);
        left.addView(guide, Ui.margin(-1, -2, 0, Ui.dp(activity, 16), 0, 0));

        TextView linkView = Ui.text(activity, shareUrl, 12, Ui.TEXT_3, false);
        linkView.setSingleLine(true);
        linkView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        left.addView(linkView, Ui.margin(-1, -2, 0, Ui.dp(activity, 6), 0, 0));

        TextView closeBtn = buildCloseButton(activity);
        left.addView(closeBtn, Ui.margin(Ui.dp(activity, 136), Ui.dp(activity, 42), 0, Ui.dp(activity, 18), 0, 0));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
        leftLp.rightMargin = Ui.dp(activity, 26);
        panel.addView(left, leftLp);

        // 右侧：固定正方形白底高清二维码卡片（防止水平挤压截断）
        LinearLayout right = Ui.column(activity);
        right.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout qrFrame = new FrameLayout(activity);
        qrFrame.setBackground(Ui.round(activity, Color.WHITE, 16));
        qrFrame.setPadding(qrPad, qrPad, qrPad, qrPad);

        ImageView qrView = new ImageView(activity);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap qrBitmap = Images.qr(shareUrl, qrSize);
        if (qrBitmap != null) {
            qrView.setImageBitmap(qrBitmap);
        }
        qrFrame.addView(qrView, new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER));
        right.addView(qrFrame, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        TextView qrTip = Ui.text(activity, "抖音 / 微信 扫码分享", 12, Ui.TEXT_3, false);
        qrTip.setGravity(Gravity.CENTER);
        right.addView(qrTip, Ui.margin(-2, -2, 0, Ui.dp(activity, 10), 0, 0));

        panel.addView(right, new LinearLayout.LayoutParams(qrBoxSize, -2));

        dialog.setContentView(panel, new ViewGroup.LayoutParams(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.72f);
        }
        dialog.setOnKeyListener((d, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK) {
                d.dismiss();
                return true;
            }
            return false;
        });

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        closeBtn.post(closeBtn::requestFocus);
    }

    private static TextView buildCloseButton(Activity activity) {
        TextView btn = Ui.bold(activity, "关闭", 15, Ui.TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        Runnable paint = () -> {
            boolean focused = btn.isFocused();
            btn.setBackground(focused
                    ? Ui.round(activity, Ui.ACCENT, 12)
                    : Ui.roundStroke(activity, Ui.SURFACE_2, 12, Ui.SEPARATOR, 1));
            btn.setTextColor(focused ? Ui.ON_ACCENT : Ui.TEXT);
        };
        paint.run();
        btn.setOnFocusChangeListener((v, hasFocus) -> paint.run());
        return btn;
    }

    private void open() {
        dialog.show();
    }
}
