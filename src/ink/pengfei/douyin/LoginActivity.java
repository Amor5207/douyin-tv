package ink.pengfei.douyin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ink.pengfei.douyin.ui.IconView;
import ink.pengfei.douyin.ui.Ui;
import ink.pengfei.douyin.ui.widget.RoundFrame;
import ink.pengfei.douyin.util.Async;
import ink.pengfei.douyin.util.Theme;

import org.json.JSONObject;

import java.util.Random;

/**
 * 手机抖音扫码登录：
 * - 扫码后立即展示清晰的「已扫码」提示，不闪现误导性的失败文案；
 * - 若抖音要求短信安全验证，在右侧原位切换为绿果风格原生数字输入框，直接调用系统数字输入法。
 */
public class LoginActivity extends Activity {
    private static final long POLL_BASE = 2200;
    private static final long POLL_PRESCAN = 3000;
    private static final long POLL_PRESCAN_SLOW = 3500;
    private static final long POLL_SCAN = 1800;
    private static final long POLL_AFTER_LIMIT = 4000;
    private static final long REFRESH_EARLY = 2000;

    private static final String[] QR_STEPS = {
            "打开手机上的「抖音」App",
            "进入首页右上角搜索或「我的」，点扫一扫",
            "扫描右侧二维码，在手机上确认登录",
    };
    private static final String[] MFA_STEPS = {
            "已扫码，请查收手机收到的短信验证码",
            "选中右侧输入框，按确认键唤起数字键盘输入",
            "输入 6 位验证码后点「确认验证」即可登录",
    };

    private FrameLayout rootView;
    private TextView leftTitle;
    private final TextView[] stepLabels = new TextView[3];
    private View qrSide;
    private View mfaForm;

    private TextView status;
    private TextView subStatus;
    private ImageView qrView;
    private LinearLayout scannedMask;
    private TextView scannedTitle;
    private TextView scannedHint;
    private TextView refreshBtn;

    private boolean mfaVisible;
    private JSONObject mfaData;
    private String verifyTicket;
    private String stdVerifyWay = "mobile_sms_verify";
    private boolean mfaAttempted;
    private boolean mfaSending;
    private boolean mfaValidating;
    private int mfaCooldown;
    private EditText mfaInput;
    private TextView mfaResendBtn;
    private TextView mfaConfirmBtn;
    private TextView mfaSub;
    private TextView mfaError;

    private String token;
    private boolean polling;
    private boolean pollInFlight;
    private boolean finishing;
    private boolean completing;
    private boolean scanned;
    private long scannedAt;
    private JSONObject pendingRotation;
    private boolean rotationConfirm;
    private boolean qrLoading;
    private boolean rateLimitedNow;
    private long rateLimitDelay = 10000;
    private int rateLimitStreak;
    private long lastRateLimitAt;
    private long lastPollAt;
    private long expireAt;
    private long refreshAt;
    private int pollCount;
    private int status3Polls;
    private int completeFailures;
    private int qrAttempts;
    private int qrSeq;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final Runnable pollTask = new Runnable() {
        @Override public void run() { poll(); }
    };

    private final Runnable expireTask = new Runnable() {
        @Override public void run() { checkExpiry(); }
    };

    private final Runnable cooldownTask = new Runnable() {
        @Override public void run() {
            if (isFinishing() || mfaCooldown <= 0) return;
            mfaCooldown--;
            paintResend();
            if (mfaCooldown > 0) handler.postDelayed(this, 1000);
        }
    };

    private final Runnable tickTask = new Runnable() {
        @Override public void run() {
            if (isFinishing() || !polling) return;
            if (scanned && !completing && scannedAt > 0
                    && System.currentTimeMillis() - scannedAt > 180000) {
                scanned = false;
                scannedAt = 0;
                rateLimitedNow = false;
                rateLimitDelay = 5000;
                loadQr();
                setStatusText("二维码已过期，已自动刷新", false);
                setSub("");
                handler.postDelayed(this, 1000);
                return;
            }
            if (!scanned && !completing && !rateLimitedNow && !mfaVisible) {
                long left = (refreshAt > 0 ? refreshAt : expireAt) - System.currentTimeMillis();
                if (left > 0) {
                    setStatusText(((left + 999) / 1000) + " 秒后刷新", false);
                    setSub("");
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (App.get().api().loggedIn()) {
            setResult(RESULT_OK);
            if (isTaskRoot() && getCallingActivity() == null) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            finish();
            return;
        }
        Theme.applyWindow(this);
        setContentView(build());
        loadQr();
    }

    /** 仅限 debug 包本地验证扫码状态与内联短信验证码界面。 */
    public void debugShowState(String mode) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if ("scanned".equals(mode)) {
            hideMfaForm(false);
            scanned = true;
            setScanned(true, "已扫码", "请在手机上点击「确认登录」");
            setStatusText("已扫码，等待手机确认…", true);
            setSub("");
        } else if ("mfa".equals(mode)) {
            try {
                JSONObject fake = new JSONObject();
                fake.put("mobile", "138****8888");
                mfaData = fake;
                scanned = true;
                showMfaForm("验证码已发送至 138****8888");
                startResendCooldown(60);
            } catch (Exception ignored) {}
        } else if ("qr".equals(mode)) {
            hideMfaForm(false);
            scanned = false;
            setScanned(false, null, null);
            setStatusText("请用手机抖音 App 扫码", false);
            setSub("");
        }
    }

    private void setScanned(boolean on) {
        setScanned(on, "已扫码", "请在手机上点击「确认登录」");
    }

    private void setScanned(boolean on, String titleText, String hintText) {
        if (!on) scannedAt = 0;
        else if (scannedAt == 0) scannedAt = System.currentTimeMillis();
        if (scannedTitle != null && titleText != null) scannedTitle.setText(titleText);
        if (scannedHint != null && hintText != null) scannedHint.setText(hintText);
        if (scannedMask != null) scannedMask.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void setStatusText(String text, boolean highlight) {
        if (status == null) return;
        status.setText(text == null ? "" : text);
        status.setTextColor(highlight ? Ui.ACCENT_TEXT : Ui.TEXT_3);
        status.setTypeface(Typeface.defaultFromStyle(highlight ? Typeface.BOLD : Typeface.NORMAL));
    }

    private void setSub(String s) {
        if (subStatus == null) return;
        String text = s == null ? "" : s;
        subStatus.setText(text);
        subStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Ui.BG);

        LinearLayout brand = Ui.row(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(Ui.brandIcon(this, 36), Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView dou = Ui.wordmark(this, 20);
        LinearLayout.LayoutParams dl = Ui.lp(-2, -2);
        dl.leftMargin = Ui.dp(this, 12);
        brand.addView(dou, dl);
        String verText = version();
        if (!verText.isEmpty()) {
            TextView ver = Ui.text(this, "v" + verText, 13, Ui.TEXT_3, false);
            LinearLayout.LayoutParams vl = Ui.lp(-2, -2);
            vl.leftMargin = Ui.dp(this, 10);
            brand.addView(ver, vl);
        }
        FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        brandLp.setMargins(Ui.dp(this, 56), Ui.dp(this, 32), 0, 0);
        root.addView(brand, brandLp);

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = Ui.column(this);
        leftTitle = Ui.text(this, "用抖音 App 扫码登录", 28, Ui.TEXT, true);
        left.addView(leftTitle);
        for (int i = 0; i < QR_STEPS.length; i++) {
            LinearLayout line = Ui.row(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView number = Ui.text(this, String.valueOf(i + 1), 14, Ui.PRIMARY, true);
            number.setGravity(Gravity.CENTER);
            number.setBackground(Ui.round(this, Ui.TINT_PRIMARY, 14));
            line.addView(number, Ui.lp(Ui.dp(this, 28), Ui.dp(this, 28)));
            TextView instruction = Ui.text(this, QR_STEPS[i], 15, Ui.TEXT, false);
            stepLabels[i] = instruction;
            LinearLayout.LayoutParams il = Ui.lp(-2, -2);
            il.leftMargin = Ui.dp(this, 14);
            line.addView(instruction, il);
            LinearLayout.LayoutParams ll = Ui.lp(-1, -2);
            ll.topMargin = Ui.dp(this, i == 0 ? 22 : 12);
            left.addView(line, ll);
        }
        LinearLayout.LayoutParams leftLp = Ui.lp(Ui.dp(this, 450), -2);
        leftLp.rightMargin = Ui.dp(this, 72);
        leftLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(left, leftLp);

        FrameLayout rightSlot = new FrameLayout(this);
        qrSide = buildQrSide();
        rightSlot.addView(qrSide, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        mfaForm = buildMfaForm();
        mfaForm.setVisibility(View.GONE);
        rightSlot.addView(mfaForm, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout.LayoutParams rightLp = Ui.lp(Ui.dp(this, 380), -2);
        rightLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(rightSlot, rightLp);

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER);
        rowLp.topMargin = Ui.dp(this, 36);
        root.addView(row, rowLp);
        Ui.enter(row, 0, 14, 220);
        return root;
    }

    private View buildQrSide() {
        LinearLayout side = Ui.column(this);
        side.setGravity(Gravity.CENTER_HORIZONTAL);

        RoundFrame card = new RoundFrame(this, 22);
        card.setBackground(Ui.round(this, 0xFFFFFFFF, 22));
        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(qrView, new FrameLayout.LayoutParams(
                Ui.dp(this, 228), Ui.dp(this, 228), Gravity.CENTER));

        LinearLayout mask = Ui.column(this);
        mask.setGravity(Gravity.CENTER);
        mask.setBackgroundColor(0xF5FFFFFF);
        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(Ui.round(this, Ui.PRIMARY, 30));
        IconView check = new IconView(this, IconView.CHECK, 30);
        check.setColor(Ui.ON_ACCENT);
        circle.addView(check, new FrameLayout.LayoutParams(
                Ui.dp(this, 30), Ui.dp(this, 30), Gravity.CENTER));
        mask.addView(circle, Ui.lp(Ui.dp(this, 60), Ui.dp(this, 60)));

        scannedTitle = Ui.text(this, "已扫码", 20, 0xFF1C1C1E, true);
        LinearLayout.LayoutParams okLp = Ui.lp(-2, -2);
        okLp.topMargin = Ui.dp(this, 14);
        mask.addView(scannedTitle, okLp);

        scannedHint = Ui.text(this, "请在手机上点击「确认登录」", 14, 0xFF6E6E73, false);
        scannedHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = Ui.lp(-2, -2);
        hintLp.topMargin = Ui.dp(this, 6);
        mask.addView(scannedHint, hintLp);

        mask.setVisibility(View.GONE);
        scannedMask = mask;
        card.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        side.addView(card, Ui.lp(Ui.dp(this, 264), Ui.dp(this, 264)));

        status = Ui.text(this, "正在获取二维码…", 14, Ui.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = Ui.lp(Ui.dp(this, 320), -2);
        sl.topMargin = Ui.dp(this, 14);
        side.addView(status, sl);

        subStatus = Ui.text(this, "", 12, Ui.TEXT_4, false);
        subStatus.setGravity(Gravity.CENTER);
        subStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams subLp = Ui.lp(Ui.dp(this, 320), -2);
        subLp.topMargin = Ui.dp(this, 6);
        side.addView(subStatus, subLp);

        refreshBtn = Ui.action(this, "重新获取二维码", IconView.REFRESH, false);
        refreshBtn.setOnClickListener(v -> loadQr());
        refreshBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams rl = Ui.lp(-2, Ui.dp(this, 46));
        rl.topMargin = Ui.dp(this, 14);
        side.addView(refreshBtn, rl);
        return side;
    }

    /** 参考绿果 TV 登录表单：原生数字输入框 + 倒计时重发 + 主色确认按钮，不弹窗、不自绘九宫格。 */
    private View buildMfaForm() {
        LinearLayout box = Ui.column(this);

        mfaSub = Ui.text(this, "正在发送验证码…", 14, Ui.TEXT_2, false);
        mfaSub.setLineSpacing(Ui.dp(this, 3), 1f);
        box.addView(mfaSub, Ui.lp(-1, -2));

        LinearLayout codeRow = Ui.row(this);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        mfaInput = buildNumericInput("请输入6位验证码", 6);
        mfaInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        mfaInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                hideIme(mfaInput);
                submitMfaCode();
                return true;
            }
            return false;
        });
        codeRow.addView(wrapField(mfaInput), Ui.lp(0, Ui.dp(this, 56), 1f));

        mfaResendBtn = Ui.text(this, "重新发送", 15, Ui.PRIMARY, true);
        mfaResendBtn.setGravity(Gravity.CENTER);
        mfaResendBtn.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) mfaResendBtn.setDefaultFocusHighlightEnabled(false);
        mfaResendBtn.setOnFocusChangeListener((v, f) -> paintResend());
        mfaResendBtn.setOnClickListener(v -> {
            if (mfaCooldown > 0 || mfaSending) return;
            sendMfaCode();
        });
        paintResend();
        LinearLayout.LayoutParams rbl = Ui.lp(Ui.dp(this, 124), Ui.dp(this, 56));
        rbl.leftMargin = Ui.dp(this, 10);
        codeRow.addView(mfaResendBtn, rbl);
        LinearLayout.LayoutParams crl = Ui.lp(-1, Ui.dp(this, 56));
        crl.topMargin = Ui.dp(this, 14);
        box.addView(codeRow, crl);

        mfaConfirmBtn = Ui.text(this, "确认验证", 18, Ui.PRIMARY, true);
        mfaConfirmBtn.setGravity(Gravity.CENTER);
        mfaConfirmBtn.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) mfaConfirmBtn.setDefaultFocusHighlightEnabled(false);
        paintConfirm(false);
        mfaConfirmBtn.setOnFocusChangeListener((v, f) -> paintConfirm(f));
        mfaConfirmBtn.setOnClickListener(v -> {
            hideIme(mfaInput);
            submitMfaCode();
        });
        LinearLayout.LayoutParams cbl = Ui.lp(-1, Ui.dp(this, 56));
        cbl.topMargin = Ui.dp(this, 16);
        box.addView(mfaConfirmBtn, cbl);

        TextView backQrBtn = Ui.text(this, "返回重新扫码", 14, Ui.TEXT_2, false);
        backQrBtn.setGravity(Gravity.CENTER);
        backQrBtn.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) backQrBtn.setDefaultFocusHighlightEnabled(false);
        backQrBtn.setBackground(Ui.round(this, Ui.FILL, 14));
        backQrBtn.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? Ui.roundStroke(this, Ui.SURFACE_2, 14, Ui.PRIMARY, 2)
                    : Ui.round(this, Ui.FILL, 14));
            ((TextView) v).setTextColor(f ? Ui.TEXT : Ui.TEXT_2);
        });
        backQrBtn.setOnClickListener(v -> {
            hideIme(mfaInput);
            hideMfaForm(true);
        });
        LinearLayout.LayoutParams bql = Ui.lp(-1, Ui.dp(this, 46));
        bql.topMargin = Ui.dp(this, 12);
        box.addView(backQrBtn, bql);

        mfaError = Ui.text(this, "", 13, 0xFFFF7A7A, false);
        mfaError.setGravity(Gravity.CENTER_HORIZONTAL);
        mfaError.setVisibility(View.GONE);
        LinearLayout.LayoutParams el = Ui.lp(-1, -2);
        el.topMargin = Ui.dp(this, 12);
        box.addView(mfaError, el);

        return box;
    }

    private EditText buildNumericInput(String hint, int maxLen) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(18);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_4);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        e.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showIme(e);
                return true;
            }
            return false;
        });
        e.setOnClickListener(v -> showIme(e));
        return e;
    }

    private View wrapField(EditText e) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.padding(row, 18, 0, 18, 0);
        row.addView(e, Ui.lp(0, -1, 1f));
        paintField(row, false);
        e.setOnFocusChangeListener((v, f) -> paintField(row, f));
        return row;
    }

    private void paintField(View row, boolean focused) {
        row.setBackground(focused
                ? Ui.roundStroke(this, Ui.SURFACE_2, 14, Ui.PRIMARY, 2)
                : Ui.round(this, Ui.FILL, 14));
    }

    private void paintResend() {
        if (mfaResendBtn == null) return;
        boolean focused = mfaResendBtn.hasFocus();
        boolean waiting = mfaCooldown > 0 || mfaSending;
        mfaResendBtn.setText(mfaSending ? "发送中…" : (mfaCooldown > 0 ? (mfaCooldown + "s") : "重新发送"));
        if (waiting) {
            mfaResendBtn.setBackground(focused
                    ? Ui.roundStroke(this, Ui.SURFACE_2, 14, Ui.PRIMARY, 2)
                    : Ui.round(this, Ui.FILL, 14));
            mfaResendBtn.setTextColor(Ui.TEXT_3);
        } else {
            mfaResendBtn.setBackground(Ui.round(this, focused ? Ui.PRIMARY : Ui.TINT_PRIMARY, 14));
            mfaResendBtn.setTextColor(focused ? Ui.ON_ACCENT : Ui.PRIMARY);
        }
    }

    private void paintConfirm(boolean focused) {
        if (mfaConfirmBtn == null) return;
        mfaConfirmBtn.setBackground(Ui.round(this, focused ? Ui.PRIMARY : Ui.TINT_PRIMARY, 14));
        mfaConfirmBtn.setTextColor(focused ? Ui.ON_ACCENT : Ui.PRIMARY);
    }

    private void showIme(View e) {
        if (e == null) return;
        e.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_FORCED);
    }

    private void hideIme(View e) {
        if (e == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }

    private void setLeftMode(boolean mfa) {
        if (leftTitle != null) {
            leftTitle.setText(mfa ? "完成短信安全验证" : "用抖音 App 扫码登录");
        }
        String[] steps = mfa ? MFA_STEPS : QR_STEPS;
        for (int i = 0; i < stepLabels.length && i < steps.length; i++) {
            if (stepLabels[i] != null) stepLabels[i].setText(steps[i]);
        }
    }

    private void setMfaError(String msg) {
        if (mfaError == null) return;
        String text = msg == null ? "" : msg;
        mfaError.setText(text);
        mfaError.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void startResendCooldown(int seconds) {
        handler.removeCallbacks(cooldownTask);
        mfaCooldown = Math.max(0, seconds);
        paintResend();
        if (mfaCooldown > 0) handler.postDelayed(cooldownTask, 1000);
    }

    private void loadQr() {
        handler.removeCallbacksAndMessages(null);
        polling = false;
        pollInFlight = false;
        completing = false;
        scanned = false;
        qrLoading = false;
        completeFailures = 0;
        qrAttempts = 0;
        pollCount = 0;
        token = null;
        rateLimitedNow = false;
        rateLimitDelay = 10000;
        expireAt = 0;
        qrSeq++;
        setStatusText("正在获取二维码…", false);
        setSub("");
        qrView.setAlpha(0.25f);
        refreshBtn.setVisibility(View.GONE);
        setScanned(false);
        hideMfaForm(false);
        mfaData = null;
        verifyTicket = null;
        stdVerifyWay = "mobile_sms_verify";
        mfaAttempted = false;
        pendingRotation = null;
        rotationConfirm = false;
        fetchQr();
    }

    private void fetchQr() {
        if (isFinishing() || qrLoading) return;
        qrLoading = true;
        final int seq = qrSeq;
        Async.run(new Async.Fn<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                App.get().api().bootstrap();
                return App.get().api().getQrCode();
            }
        }, new Async.Cb<JSONObject>() {
            @Override public void done(JSONObject data, Exception error) {
                if (isFinishing() || seq != qrSeq) return;
                qrLoading = false;
                if (data == null) {
                    retryQr("二维码获取失败，请检查网络");
                    return;
                }
                token = data.optString("token", "");
                String b64 = data.optString("qrcode", "");
                Bitmap bmp = decode(b64);
                if (token.isEmpty() || bmp == null) {
                    String desc = data.optString("description", "");
                    retryQr(desc.isEmpty() ? "二维码获取失败" : desc);
                    return;
                }
                qrAttempts = 0;
                status3Polls = 0;
                rateLimitedNow = false;
                rateLimitStreak = 0;
                rateLimitDelay = 10000;
                long exp = data.optLong("expire_time", 0);
                setExpiryFrom(exp);
                qrView.setImageBitmap(bmp);
                qrView.setAlpha(1f);
                refreshBtn.setVisibility(View.GONE);
                setScanned(false);
                setStatusText("请用手机抖音 App 扫码", false);
                setSub("");
                polling = true;
                handler.removeCallbacks(tickTask);
                handler.postDelayed(tickTask, 1000);
                schedulePoll(2000);
                scheduleExpiry();
            }
        });
    }

    private void scheduleExpiry() {
        handler.removeCallbacks(expireTask);
        if (!polling || isFinishing()) return;
        long target = refreshAt > 0 ? refreshAt : expireAt - REFRESH_EARLY;
        long delay = target - System.currentTimeMillis();
        handler.postDelayed(expireTask, Math.max(1500, delay));
    }

    private void checkExpiry() {
        if (!polling || isFinishing()) return;
        if (scanned || pollInFlight || rateLimitedNow) return;
        if (System.currentTimeMillis() - lastPollAt < 2500L) return;
        handler.removeCallbacks(pollTask);
        poll();
    }

    private void setExpiryFrom(long exp) {
        if (exp > 0) expireAt = exp > 100000000000L ? exp : exp * 1000L;
        else expireAt = System.currentTimeMillis() + 60000L;
        refreshAt = Math.max(System.currentTimeMillis() + 10000L, expireAt - REFRESH_EARLY);
    }

    private void retryQr(String reason) {
        qrAttempts++;
        if (qrAttempts <= 3 && !isFinishing()) {
            setStatusText("正在重试获取二维码…", false);
            setSub(reason);
            refreshBtn.setVisibility(View.VISIBLE);
            refreshBtn.requestFocus();
            final int attempt = qrAttempts;
            handler.postDelayed(new Runnable() {
                @Override public void run() { fetchQr(); }
            }, (rateLimited(reason) ? 6000L : 2500L) * attempt);
            return;
        }
        fail(reason + "，请点击「重新获取二维码」");
    }

    private void poll() {
        if (!polling || isFinishing() || token == null || completing || pollInFlight) return;
        final String polledToken = token;
        final int seq = qrSeq;
        final String ticket = verifyTicket;
        final String verifyWay = stdVerifyWay;
        pollInFlight = true;
        lastPollAt = System.currentTimeMillis();
        pollCount++;
        if (pollCount == 90 && !scanned) {
            setSub("二维码可能已过期，可点击「重新获取二维码」");
        }
        Async.run(new Async.Fn<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                return App.get().api().checkQr(polledToken, ticket, verifyWay);
            }
        }, new Async.Cb<JSONObject>() {
            @Override public void done(JSONObject data, Exception error) {
                if (seq != qrSeq) return;
                pollInFlight = false;
                if (!polling || isFinishing() || !polledToken.equals(token)) return;
                if (data == null) {
                    schedulePoll(scanned ? POLL_SCAN : POLL_PRESCAN);
                    return;
                }
                String desc = data.optString("description", "");
                int err = data.optInt("error_code", 0);
                String raw = statusOf(data);

                // 优先识别明确扫码 / 确认 / 跳转态，避免被其它字段误判成失败
                String redirect = firstUrl(data);
                if (!redirect.isEmpty()) {
                    scanned = true;
                    setScanned(true, "已确认", "正在进入抖音TV…");
                    complete(redirect);
                    return;
                }
                if (App.get().api().loggedIn()) {
                    complete("");
                    return;
                }
                if ("2".equals(raw) || "scan".equalsIgnoreCase(raw) || "scanned".equalsIgnoreCase(raw)) {
                    scanned = true;
                    pendingRotation = null;
                    rotationConfirm = false;
                    rateLimitedNow = false;
                    setScanned(true, "已扫码", "请在手机上点击「确认登录」");
                    rateLimitDelay = 5000;
                    setStatusText("已扫码，等待手机确认…", true);
                    setSub("");
                    schedulePoll(nextPollDelay(true));
                    return;
                }
                if ("3".equals(raw) || "confirmed".equalsIgnoreCase(raw) || "confirm".equalsIgnoreCase(raw)) {
                    scanned = true;
                    pendingRotation = null;
                    rotationConfirm = false;
                    rateLimitedNow = false;
                    setScanned(true, "已确认", "正在进入抖音TV…");
                    status3Polls++;
                    setStatusText("登录成功，正在进入…", true);
                    setSub("");
                    complete(firstUrl(data));
                    return;
                }

                // 短信 / 二次安全验证（扫码确认后触发）
                if (err == 2046) {
                    scanned = true;
                    rateLimitedNow = false;
                    setScanned(true, "已扫码", "请完成短信安全验证");
                    if (!ink.pengfei.douyin.net.DouyinApi.mfaNeedsSms(data)) {
                        setStatusText("已扫码，请在手机上完成安全验证", true);
                        setSub("");
                        schedulePoll(nextPollDelay(true));
                        return;
                    }
                    if (verifyTicket != null && !mfaVisible) {
                        // 上一次验证票据已失效或需再次验证，允许重新输入验证码而不是无限等待
                        verifyTicket = null;
                        mfaData = null;
                        mfaAttempted = false;
                    }
                    if (verifyTicket == null && mfaData == null && !mfaAttempted) {
                        beginMfa(data);
                        return;
                    }
                    setStatusText("已扫码，等待验证完成…", true);
                    setSub("");
                    schedulePoll(nextPollDelay(true));
                    return;
                }

                if (err == 4031 || desc.contains("安全风险") || desc.contains("阻止")) {
                    rateLimitedNow = true;
                    if (scanned) {
                        setStatusText("已扫码，等待手机确认…", true);
                        setSub("");
                        schedulePoll(Math.max(rateLimitDelay, 8000));
                        rateLimitDelay = Math.min(60000, rateLimitDelay * 2);
                        return;
                    }
                    setStatusText("正在等待登录响应…", false);
                    setSub("稍后自动重试");
                    schedulePoll(Math.max(rateLimitDelay, 8000));
                    rateLimitDelay = Math.min(60000, rateLimitDelay * 2);
                    return;
                }
                if (err == 7 || rateLimited(desc)) {
                    rateLimitedNow = true;
                    rateLimitStreak++;
                    lastRateLimitAt = System.currentTimeMillis();
                    if (scanned && scannedAt == 0) scannedAt = lastRateLimitAt;
                    rateLimitDelay = rateLimitStreak <= 1 ? 4500L
                            : (rateLimitStreak == 2 ? 9000L : 18000L);
                    if (scanned) {
                        setStatusText("已扫码，等待手机确认…", true);
                        setSub("");
                    } else if (rateLimitStreak >= 3) {
                        setStatusText("正在等待扫码状态…", false);
                        setSub((rateLimitDelay / 1000) + " 秒后自动重试");
                        if (refreshBtn.getVisibility() != View.VISIBLE) {
                            refreshBtn.setVisibility(View.VISIBLE);
                        }
                    }
                    if (System.currentTimeMillis() > expireAt + 30000 && !scanned) {
                        qrView.setImageDrawable(null);
                        fail("二维码已过期，请重新获取");
                        return;
                    }
                    schedulePoll(rateLimitDelay);
                    return;
                }
                rateLimitedNow = false;
                rateLimitStreak = 0;
                rateLimitDelay = 10000;

                if (err != 0 && err != -1) {
                    if (desc.contains("过期") || desc.contains("失效") || desc.contains("expired")) {
                        if (pendingRotation != null) {
                            JSONObject fresh = pendingRotation;
                            pendingRotation = null;
                            rotationConfirm = false;
                            if (applyRotatedQr(fresh)) return;
                        }
                        if (scanned && pollCount <= 60) {
                            setStatusText("已扫码，等待手机确认…", true);
                            setSub("");
                            schedulePoll(POLL_SCAN);
                            return;
                        }
                        scanned = false;
                        scannedAt = 0;
                        loadQr();
                        setStatusText("二维码已过期，已自动刷新", false);
                        setSub("");
                        return;
                    }
                    if (desc.contains("验证") || desc.contains("验证码")) {
                        scanned = true;
                        setScanned(true, "已扫码", "请完成安全验证");
                        setStatusText("已扫码，等待安全验证…", true);
                        setSub("");
                        schedulePoll(POLL_SCAN);
                        return;
                    }
                    // 扫码后绝不闪现失败文案，保持「已扫码」状态静默续询
                    if (scanned) {
                        setStatusText("已扫码，等待手机确认…", true);
                        setSub("");
                    }
                    schedulePoll(scanned ? POLL_SCAN : POLL_PRESCAN);
                    return;
                }

                boolean hasQr = !data.optString("qrcode", "").isEmpty()
                        && !data.optString("token", "").isEmpty();
                boolean rotation = hasQr && (raw.isEmpty() || isRotationStatus(raw));
                if (rotation && scanned) {
                    pendingRotation = null;
                    rotationConfirm = false;
                    setStatusText("已扫码，等待手机确认…", true);
                    setSub("");
                    schedulePoll(POLL_SCAN);
                    return;
                }
                if (rotation) {
                    if (!rotationConfirm) {
                        pendingRotation = data;
                        rotationConfirm = true;
                        setStatusText("正在确认扫码状态…", false);
                        setSub("");
                        schedulePoll(2000);
                        return;
                    }
                    rotationConfirm = false;
                    pendingRotation = null;
                    if (applyRotatedQr(data)) return;
                }
                if ("4".equals(raw) || "5".equals(raw) || "refused".equalsIgnoreCase(raw)
                        || "expired".equalsIgnoreCase(raw)) {
                    if (!applyRotatedQr(data)) {
                        loadQr();
                        setStatusText("二维码已过期，已自动刷新", false);
                        setSub("");
                    }
                    return;
                }
                if (desc.contains("验证") || desc.contains("验证码")) {
                    scanned = true;
                    setScanned(true, "已扫码", "请完成安全验证");
                    setStatusText("已扫码，等待安全验证…", true);
                    setSub("");
                } else if (!scanned) {
                    long left = (refreshAt > 0 ? refreshAt : expireAt) - System.currentTimeMillis();
                    setStatusText(left > 0 ? (((left + 999) / 1000) + " 秒后刷新") : "请用手机抖音 App 扫码", false);
                    setSub("");
                }
                if (!scanned && System.currentTimeMillis() >= refreshAt - 1500) schedulePoll(3000);
                else schedulePoll(nextPollDelay(scanned));
            }
        });
    }

    /** 过期二维码由 check_qrconnect 原地下发新码。 */
    private boolean applyRotatedQr(JSONObject data) {
        String b64 = data.optString("qrcode", "");
        String newToken = data.optString("token", "");
        if (b64.isEmpty() || newToken.isEmpty()) return false;
        Bitmap bmp = decode(b64);
        if (bmp == null) return false;
        pendingRotation = null;
        rotationConfirm = false;
        token = newToken;
        qrView.setImageBitmap(bmp);
        qrView.setAlpha(1f);
        scanned = false;
        setExpiryFrom(data.optLong("expire_time", 0));
        status3Polls = 0;
        rateLimitedNow = false;
        rateLimitStreak = 0;
        rateLimitDelay = 10000;
        setScanned(false);
        setStatusText("请用手机抖音 App 扫码", false);
        setSub("");
        schedulePoll(nextPollDelay(false));
        scheduleExpiry();
        return true;
    }

    private long nextPollDelay(boolean hasScanned) {
        boolean recentLimit = lastRateLimitAt > 0
                && System.currentTimeMillis() - lastRateLimitAt < 90_000L;
        if (hasScanned) return recentLimit ? POLL_AFTER_LIMIT : POLL_SCAN;
        if (recentLimit) return POLL_PRESCAN_SLOW;
        return pollCount >= 12 ? POLL_PRESCAN_SLOW : POLL_PRESCAN;
    }

    private void schedulePoll(long delay) {
        if (!polling || isFinishing()) return;
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, delay + random.nextInt(150));
    }

    private static boolean isRotationStatus(String s) {
        return "expired".equalsIgnoreCase(s) || "4".equals(s) || "5".equals(s)
                || "refused".equalsIgnoreCase(s);
    }

    private static boolean rateLimited(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String d = desc.toLowerCase(java.util.Locale.US);
        return desc.contains("频繁") || desc.contains("稍后") || desc.contains("太快")
                || desc.contains("限流") || d.contains("too frequent") || d.contains("too many")
                || d.contains("rate limit") || d.contains("try again later");
    }

    private static String strOf(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key, "");
        if (!v.isEmpty()) return v;
        JSONObject inner = o.optJSONObject("data");
        if (inner != null) {
            v = inner.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String statusOf(JSONObject o) {
        String v = strOf(o, "status");
        if (!v.isEmpty()) return "new".equalsIgnoreCase(v) ? "1" : v;
        return strOf(o, "status_code");
    }

    private String firstUrl(JSONObject data) {
        String[] keys = {"redirect_url", "redirect_uri", "login_url"};
        for (String k : keys) {
            String v = strOf(data, k);
            if (v.startsWith("//")) return "https:" + v;
            if (v.startsWith("https://") || v.startsWith("/")) return v;
        }
        return "";
    }

    private void complete(final String redirect) {
        if (completing || finishing) return;
        completing = true;
        polling = false;
        handler.removeCallbacks(pollTask);
        setStatusText("登录成功，正在进入…", true);
        setSub("");
        Async.run(new Async.Fn<String>() {
            @Override public String run() {
                return App.get().api().completeLogin(redirect) ? "已登录" : null;
            }
        }, new Async.Cb<String>() {
            @Override public void done(String name, Exception error) {
                if (isFinishing() || finishing) return;
                completing = false;
                if (!App.get().api().loggedIn()) {
                    completeFailures++;
                    if (completeFailures <= 6) {
                        setStatusText("已扫码，等待手机确认…", true);
                        setSub("如手机要求短信验证，请完成后自动登录");
                        polling = true;
                        schedulePoll(POLL_BASE);
                    } else if (scanned && pollCount <= 280) {
                        setStatusText("已扫码，等待手机确认…", true);
                        setSub("请在手机上完成安全验证");
                        polling = true;
                        schedulePoll(POLL_BASE);
                    } else {
                        fail("请在手机上完成安全验证后重新扫码");
                    }
                    return;
                }
                finishing = true;
                hideIme(mfaInput);
                setStatusText("登录成功，正在进入…", true);
                setSub("");
                setResult(RESULT_OK);
                if (isTaskRoot() || getCallingActivity() == null) {
                    startActivity(new Intent(LoginActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                }
                finish();
            }
        });
    }

    // ---------- 短信二次验证（右侧原位切换，绿果风格原生数字输入） ----------

    private void beginMfa(JSONObject data) {
        mfaData = data;
        mfaAttempted = true;
        // 用户正在电视端输入短信验证码期间暂停 check_qrconnect 轮询，避免触发 code=7 限流导致提交票据被卡住
        handler.removeCallbacks(pollTask);
        boolean wasAlreadyScanned = scanned && scannedMask != null && scannedMask.getVisibility() == View.VISIBLE;
        setScanned(true, "已扫码", "需要完成短信安全验证");
        setStatusText("已扫码，正在进入短信验证…", true);
        setSub("");
        String mobile = ink.pengfei.douyin.net.DouyinApi.mfaMobile(data);
        final String hint = (mobile == null || mobile.isEmpty())
                ? "正在发送短信验证码…" : ("正在向 " + mobile + " 发送验证码…");
        sendMfaCode();
        // 先让用户明确看到「已扫码」反馈，再平滑切入短信验证码输入表单，避免感觉页面卡住后突然跳变
        handler.postDelayed(() -> {
            if (isFinishing() || mfaData != data || mfaVisible) return;
            showMfaForm(hint);
        }, wasAlreadyScanned ? 220L : 650L);
    }

    private void showMfaForm(String subText) {
        mfaVisible = true;
        setLeftMode(true);
        if (qrSide != null) qrSide.setVisibility(View.GONE);
        if (mfaForm != null) mfaForm.setVisibility(View.VISIBLE);
        if (mfaSub != null) mfaSub.setText(subText == null ? "" : subText);
        setMfaError("");
        if (mfaInput != null) {
            mfaInput.setText("");
            mfaInput.requestFocus();
            handler.postDelayed(() -> {
                if (mfaVisible && !isFinishing() && mfaInput != null) {
                    showIme(mfaInput);
                }
            }, 180);
        }
        paintResend();
    }

    private void sendMfaCode() {
        if (mfaSending || mfaData == null) return;
        mfaSending = true;
        paintResend();
        final JSONObject data = mfaData;
        String mobile = ink.pengfei.douyin.net.DouyinApi.mfaMobile(data);
        if (mfaSub != null) {
            mfaSub.setText((mobile == null || mobile.isEmpty())
                    ? "正在发送验证码…" : ("正在向 " + mobile + " 发送验证码…"));
        }
        setMfaError("");
        Async.run(new Async.Fn<Void>() {
            @Override public Void run() throws Exception {
                App.get().api().sendMfaCode(data);
                return null;
            }
        }, new Async.Cb<Void>() {
            @Override public void done(Void value, Exception error) {
                mfaSending = false;
                if (isFinishing() || mfaData != data) return;
                if (error != null) {
                    paintResend();
                    String msg = error.getMessage() == null ? "验证码发送失败，请点「重新发送」" : error.getMessage();
                    if (mfaSub != null) mfaSub.setText("未能自动发送验证码");
                    setMfaError(msg);
                    return;
                }
                startResendCooldown(60);
                if (mfaSub != null) {
                    mfaSub.setText((mobile == null || mobile.isEmpty())
                            ? "短信验证码已发送，请查收" : ("验证码已发送至 " + mobile));
                }
                setMfaError("");
            }
        });
    }

    private void submitMfaCode() {
        if (mfaValidating || mfaData == null || mfaInput == null) return;
        String code = mfaInput.getText().toString().trim();
        if (code.length() < 4) {
            setMfaError("请输入短信验证码");
            mfaInput.requestFocus();
            return;
        }
        mfaValidating = true;
        setMfaError("");
        final JSONObject data = mfaData;
        if (mfaSub != null) mfaSub.setText("正在验证验证码…");
        Async.run(new Async.Fn<String>() {
            @Override public String run() throws Exception {
                return App.get().api().validateMfaCode(data, code);
            }
        }, new Async.Cb<String>() {
            @Override public void done(String ticket, Exception error) {
                mfaValidating = false;
                if (isFinishing()) return;
                if (error != null || ticket == null || ticket.isEmpty()) {
                    if (mfaInput != null) {
                        mfaInput.setText("");
                        mfaInput.requestFocus();
                    }
                    setMfaError(error != null && error.getMessage() != null
                            ? error.getMessage() : "验证码错误，请重新输入");
                    String mobile = ink.pengfei.douyin.net.DouyinApi.mfaMobile(data);
                    if (mfaSub != null) {
                        mfaSub.setText((mobile == null || mobile.isEmpty())
                                ? "请输入手机短信验证码" : ("验证码已发送至 " + mobile));
                    }
                    return;
                }
                verifyTicket = ticket;
                stdVerifyWay = "mobile_sms_verify";
                rateLimitedNow = false;
                rateLimitStreak = 0;
                hideMfaForm(false);
                setScanned(true, "验证成功", "正在进入抖音TV…");
                setStatusText("验证成功，正在进入…", true);
                setSub("");
                polling = true;
                handler.removeCallbacks(pollTask);
                poll();
            }
        });
    }

    private void hideMfaForm(boolean reloadQr) {
        handler.removeCallbacks(cooldownTask);
        mfaCooldown = 0;
        mfaVisible = false;
        hideIme(mfaInput);
        setLeftMode(false);
        if (mfaForm != null) mfaForm.setVisibility(View.GONE);
        if (qrSide != null) qrSide.setVisibility(View.VISIBLE);
        setMfaError("");
        if (reloadQr && !finishing) {
            mfaData = null;
            mfaAttempted = false;
            loadQr();
        }
    }

    @Override
    public void onBackPressed() {
        if (mfaVisible) {
            hideMfaForm(true);
            return;
        }
        if (!App.get().api().loggedIn()) {
            finishAffinity();
            return;
        }
        super.onBackPressed();
    }

    private void fail(String message) {
        polling = false;
        handler.removeCallbacks(pollTask);
        setStatusText("二维码获取失败，请检查网络", false);
        setSub(message);
        refreshBtn.setVisibility(View.VISIBLE);
        refreshBtn.requestFocus();
    }

    private static Bitmap decode(String b64) {
        try {
            int comma = b64.indexOf(',');
            if (comma >= 0) b64 = b64.substring(comma + 1);
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        polling = false;
        handler.removeCallbacksAndMessages(null);
        if (qrView != null && qrView.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            qrView.setImageDrawable(null);
        }
    }
}
