package ink.pengfei.douyin.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 向更新服务查询最新版本。后端尚未配置当前包或网络不可达时返回无更新，不影响使用。 */
final class UpdateChecker {
    private UpdateChecker() {}

    static int versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "";
        }
    }

    private static volatile String ua = "DouyinTV";

    static String userAgent() {
        return ua;
    }

    static final class Result {
        final boolean ok;
        final UpdateInfo info;

        Result(boolean ok, UpdateInfo info) {
            this.ok = ok;
            this.info = info;
        }
    }

    static Result check(Context c) {
        int cur = versionCode(c);
        return checkPackage(c, c.getPackageName(), cur, versionName(c));
    }

    private static Result checkPackage(Context c, String packageName, int cur, String version) {
        try {
            if (cur < 0) return new Result(true, null);
            ua = "DouyinTV/" + version + " (Android " + Build.VERSION.RELEASE + "; "
                    + Build.MODEL + "; code=" + cur + "; pkg=" + packageName + ")";
            String url = UpdateConfig.BASE + "/api/v1/check?app=" + URLEncoder.encode(packageName, "UTF-8")
                    + "&versionCode=" + cur
                    + "&versionName=" + URLEncoder.encode(version == null ? "" : version, "UTF-8")
                    + "&channel=" + UpdateConfig.CHANNEL;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            try {
                h.setConnectTimeout(UpdateConfig.CONNECT_TIMEOUT_MS);
                h.setReadTimeout(UpdateConfig.READ_TIMEOUT_MS);
                h.setRequestProperty("User-Agent", ua);
                h.setRequestProperty("Accept", "application/json");
                int code = h.getResponseCode();
                if (code == 404 || code == 204) return new Result(true, null);
                if (code != 200) return new Result(true, null);
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0 && (total += n) < 65536) bo.write(buf, 0, n);
                    JSONObject j = new JSONObject(bo.toString("UTF-8"));
                    if (!j.optBoolean("update")) return new Result(true, null);
                    UpdateInfo info = new UpdateInfo(j);
                    if (info.valid() && info.versionCode > cur && olderNumericVersion(info.versionName, version)) {
                        return new Result(true, null);
                    }
                    return info.valid() && info.versionCode > cur
                            ? new Result(true, info)
                            : new Result(true, null);
                }
            } finally {
                h.disconnect();
            }
        } catch (Throwable e) {
            return new Result(true, null);
        }
    }

    private static boolean olderNumericVersion(String candidate, String current) {
        if (candidate == null || current == null
                || !candidate.matches("\\d+(?:\\.\\d+)*")
                || !current.matches("\\d+(?:\\.\\d+)*")) return false;
        String[] next = candidate.split("\\."), installed = current.split("\\.");
        int count = Math.max(next.length, installed.length);
        for (int i = 0; i < count; i++) {
            long n, c;
            try {
                n = i < next.length ? Long.parseLong(next[i]) : 0;
                c = i < installed.length ? Long.parseLong(installed[i]) : 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (n != c) return n < c;
        }
        return false;
    }
}
