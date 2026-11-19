package ink.pengfei.douyin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.util.Images;
import ink.pengfei.douyin.util.ShareHub;
import ink.pengfei.douyin.util.Theme;

/** Standalone phone relay screen. */
public class ShareActivity extends Activity {
    private TextView doneButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!App.get().api().loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Theme.applyWindow(this);
        setContentView(build());
        doneButton.post(doneButton::requestFocus);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        String relay = ShareHub.get() == null ? "中转服务启动中…" : ShareHub.get().lanUrl();
        Bitmap qr = Images.qr(relay, Ui.dp(this, 260));

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 48), Ui.dp(this, 64), Ui.dp(this, 48), Ui.dp(this, 20));

        LinearLayout left = Ui.column(this);
        left.setPadding(0, 0, Ui.dp(this, 24), 0);
        TextView title = Ui.bold(this, "手机发送到电视", 28, Ui.TEXT);
        left.addView(title);

        TextView d = Ui.text(this, "手机与电视连接同一 Wi-Fi。用手机相机扫码，粘贴抖音分享链接。",
                14, Ui.TEXT_3, false);
        d.setLineSpacing(Ui.dp(this, 4), 1f);
        d.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 20));
        left.addView(d);
        TextView url = Ui.bold(this, relay, 20, Ui.ACCENT_TEXT);
        left.addView(url);

        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(this, 0xFFFFFFFF, 22));
        card.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 18));
        card.setElevation(Ui.dp(this, 14));
        ImageView qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (qr != null) qrView.setImageBitmap(qr);
        card.addView(qrView, Ui.lp(Ui.dp(this, 220), Ui.dp(this, 220)));
        TextView tip = Ui.text(this, "扫码打开手机中转页", 12, Ui.TEXT_3, false);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        card.addView(tip);
        TextView done = Ui.action(this, "完成", IconView.CHECK, false);
        done.setOnClickListener(v -> finish());
        doneButton = done;
        LinearLayout side = Ui.column(this);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.addView(card, Ui.lp(Ui.dp(this, 266), ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(done, Ui.margin(Ui.dp(this, 168), Ui.dp(this, 46), 0, 16, 0, 0));
        row.addView(side);

        root.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView backTop = Ui.action(this, "返回", IconView.BACK, false);
        backTop.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
        bp.leftMargin = Ui.dp(this, 40);
        bp.topMargin = Ui.dp(this, 26);
        root.addView(backTop, bp);
        return root;
    }
}
