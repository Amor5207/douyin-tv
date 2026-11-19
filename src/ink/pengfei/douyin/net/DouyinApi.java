package ink.pengfei.douyin.net;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.model.Friend;
import ink.pengfei.douyin.model.LiveRoom;
import ink.pengfei.douyin.model.UserProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DouyinApi {
    private static final String HOST = "https://www.douyin.com";
    /** Passport calls must use the same origin as the web client, or the phone flow will not bind the QR token. */
    private static final String LOGIN_HOST = HOST;
    private static final String LIVE_HOST = "https://live.douyin.com";
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final Pattern SHARE_PAGE_DATA = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", Pattern.DOTALL);

    /** Webmssdk environment fingerprint; without it the passport QR poll is risk-blocked (4031). */
    private static final String ACCOUNT_SDK_INFO = decodeXor5("7e276d64776172647760466a6b66707777606b667c273f3437292772606761776c736077273f63646976602927666d776a686061776c736077273f63646976602927766d60696961776c736077273f63646976602927756970626c6b76273f3029276c6b6b60774d606c626d71273f32323529276c6b6b6077526c61716d273f3437323729276a707160774d606c626d71273f3529276a70716077526c61716d273f3529277260676269273f7e2773606b616a77273f27426a6a626960254c6b662b252d486c66776a766a63712c27292777606b6160776077273f27444b424940252d486c66776a766a63712925486c66776a766a6371254764766c662557606b6160772541776c736077252d357d3535353535353d462c25416c77606671364134342573765a305a352575765a305a35292541364134342c277829276470716a6864716c6a6b273f7e2776273f27353535353535353527292766273f273535353427292775273f27353535353535352729277634273f2735353535353535352729276634273f27353535352729277534273f273527782927756077636a7768646b6660273f7e27716c68604a776c626c6b273f34323c31333031313334333d3d2b322927707660614f564d606475566c7f60273f34313c34363333303129276b64736c6264716c6a6b516c686c6b62273f7e276160666a616061476a617c566c7f60273f3c343c3235362927606b71777c517c7560273f276b64736c6264716c6a6b2729276c6b6c716c64716a77517c7560273f276b64736c6264716c6a6b2729276b646860273f276d717175763f2a2a7272722b616a707c6c6b2b666a682a27292777606b61607747696a666e6c6b62567164717076273f276b6a6b2867696a666e6c6b62272927766077736077516c686c6b62273f2766616b286664666d602960616260296a776c626c6b296c6b6b60772971715a646272272927627069605671647771273f3d31362b3029276270696041707764716c6a6b273f34372b307878292767776a72766077273f7e2771273f27373d303c3231313033313c3234272927676c715a75776a716a666a69273f276364697660272927676c715a6d6069756077273f63646976607878");

    private final CookieStore cookies;
    private final TicketGuard ticketGuard;
    private final java.util.concurrent.atomic.AtomicLong likeRevision = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> likedInSession =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastLikeError = "";
    private volatile boolean lastLikeAuthError = false;
    private int refreshIndex = 1;
    private int liveFeedOffset = 0;
    private int liveFollowOffset = 0;
    private long initializedAt;
    private volatile JSONObject cachedSelfUser;
    private volatile long selfUserAt;

    public DouyinApi(CookieStore cookies) {
        this.cookies = cookies;
        ticketGuard = new TicketGuard(cookies);
        String ms = cookies.get("msToken");
        msTokenCaptured = cookies.isMsCaptured() || (ms != null && ms.length() > 120);
    }

    public CookieStore cookies() {
        return cookies;
    }

    public synchronized void bootstrap() throws IOException {
        bootstrap(false);
    }

    public synchronized void bootstrap(boolean force) throws IOException {
        long elapsed = android.os.SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        boolean staleWeb = cookies.lastWebBootstrapMs() == 0L
                || wall - cookies.lastWebBootstrapMs() > 30 * 60 * 1000L;
        if (!force && !staleWeb && initializedAt != 0 && elapsed - initializedAt < 60000L) return;

        if (force || staleWeb || !cookies.has("__ac_nonce") || !msTokenCaptured) {
            try {
                Http.Resp home = Http.get(HOST + "/",
                        Http.map("Accept", "text/html,application/xhtml+xml"), cookies);
                captureResponseMeta(home);
                cookies.markWebBootstrap();
            } catch (Exception ignored) {
            }
        }
        boolean staleTtwid = cookies.lastTtwidMs() == 0L
                || wall - cookies.lastTtwidMs() > 12 * 3600 * 1000L;
        if (force || !cookies.has("ttwid") || staleTtwid) {
            registerTtwid();
        }
        if (!cookies.has("msToken")) cookies.put("msToken", rand(107));
        if (!cookies.has("UIFID_TEMP")) cookies.put("UIFID_TEMP", hex(80));
        if (!cookies.has("s_v_web_id")) cookies.put("s_v_web_id", verifyFp());
        if (cookies.get("webid") == null || cookies.get("webid").isEmpty()) fetchWebId();
        initializedAt = android.os.SystemClock.elapsedRealtime();
    }

    /** 网页端每个请求都会带 webid/uifid；写操作（点赞等）缺 webid 会被静默拒绝。 */
    private void fetchWebId() {
        try {
            String body = "{\"app_id\":6383,\"url\":\"" + HOST + "/\",\"user_agent\":\""
                    + Http.UA + "\",\"referer\":\"\",\"user_unique_id\":\"\"}";
            Map<String, String> h = Http.map("Content-Type", "application/json");
            Http.Resp r = Http.postForm("https://mcs.zijieapi.com/webid", h, body, null);
            JSONObject o = json(r);
            String id = o == null ? "" : o.optString("web_id", "");
            if (id.matches("[0-9]{6,25}")) cookies.put("webid", id);
        } catch (Exception ignored) {
        }
    }

    private void registerTtwid() {
        try {
            String body = "{\"aid\":10006,\"service\":\"sso.douyin.com\",\"needFid\":false,"
                    + "\"union\":true,\"unionHost\":\"https://ttwid.bytedance.com\",\"migrate_info\":null,\"fid\":\"\"}";
            Map<String, String> h = Http.map("Content-Type", "application/json",
                    "Accept", "application/json, text/plain, */*");
            Http.Resp r = Http.postForm("https://ttwid.bytedance.com/ttwid/union/register/", h, body, cookies);
            JSONObject o = json(r);
            String redirect = o == null ? "" : o.optString("redirect_url", "");
            if (redirect.startsWith("http")) {
                Http.get(redirect, Http.map("Accept", "application/json, text/plain, */*"), cookies);
            }
            if (cookies.has("ttwid")) cookies.markTtwid();
        } catch (Exception ignored) {
        }
    }

    public boolean loggedIn() {
        String sid = cookies.get("sessionid");
        if (sid == null || sid.isEmpty()) sid = cookies.get("sessionid_ss");
        return sid != null && !sid.isEmpty() && !sid.equals(cookies.readInvalidSession());
    }

    private Map<String, String> base() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("pc_client_type", "1");
        p.put("version_code", "190500");
        p.put("version_name", "19.5.0");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "1536");
        p.put("screen_height", "864");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "131.0.0.0");
        p.put("browser_online", "true");
        p.put("engine_name", "Blink");
        p.put("engine_version", "131.0.0.0");
        p.put("os_name", "Windows");
        p.put("os_version", "10");
        p.put("cpu_core_num", "16");
        p.put("device_memory", "8");
        p.put("platform", "PC");
        p.put("downlink", "10");
        p.put("effective_type", "4g");
        p.put("round_trip_time", "50");
        String webid = cookies.get("webid");
        if (webid != null && !webid.isEmpty()) p.put("webid", webid);
        String uifid = cookies.get("UIFID");
        if (uifid == null || uifid.isEmpty()) uifid = cookies.get("UIFID_TEMP");
        if (uifid != null && !uifid.isEmpty()) p.put("uifid", uifid);
        return p;
    }

    private Map<String, String> headers(String referer, boolean argus) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Referer", referer);
        h.put("Origin", referer.startsWith(LIVE_HOST) ? LIVE_HOST : HOST);
        if (argus) {
            h.put("x-tt-argus", "1");
            String uifid = cookies.get("UIFID");
            if (uifid == null) uifid = cookies.get("UIFID_TEMP");
            if (uifid != null && !uifid.isEmpty()) h.put("uifid", uifid);
        }
        return h;
    }

    private Http.Resp getJson(String host, String path, Map<String, String> params, String referer, boolean argus) throws IOException {
        String qs = Http.query(params);
        String full = path + "?" + qs + "&a_bogus=" + Http.enc(Abogus.sign(qs));
        Http.Resp r = Http.get(host + full, headers(referer, argus), cookies);
        captureResponseMeta(r);
        return r;
    }

    /** GET for protected endpoints: adds ticket-guard signature and session dTrait. */
    private Http.Resp getAuthedJson(String host, String path, Map<String, String> params, String referer) throws IOException {
        String qs = Http.query(params);
        String full = path + "?" + qs + "&a_bogus=" + Http.enc(Abogus.sign(qs));
        Map<String, String> h = headers(referer, true);
        try {
            h.putAll(ticketGuard.headers(path));
        } catch (Exception e) {
            try { h.putAll(ticketGuard.publicHeaders()); } catch (Exception ignored) {}
        }
        if (sessionDtrait == null) sessionDtrait = Dtrait.build();
        h.put("x-tt-session-dtrait", sessionDtrait);
        Http.Resp r = Http.get(host + full, h, cookies);
        captureResponseMeta(r);
        return r;
    }

    private JSONObject json(Http.Resp r) {
        if (r == null || r.text == null) return null;
        try {
            return new JSONObject(r.text);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Aweme> feed(int count) throws IOException {
        bootstrap();
        List<Aweme> out = new ArrayList<>();
        int reqCount = Math.max(count, 16);
        for (int attempt = 0; attempt < 2 && out.isEmpty(); attempt++) {
            Map<String, String> p = base();
            p.put("count", String.valueOf(reqCount));
            p.put("offset", "0");
            p.put("type", "0");
            p.put("max_cursor", "0");
            p.put("min_cursor", "0");
            p.put("refresh_index", String.valueOf(refreshIndex++));
            p.put("video_type_select", "1");
            p.put("aweme_publish_time", "0");
            p.put("pull_type", "0");
            p.put("tag_id", "");
            p.put("music_id", "");
            p.put("msToken", cookies.get("msToken"));
            Http.Resp r = getJson(HOST, "/aweme/v1/web/tab/feed/", p, HOST + "/", false);
            JSONObject o = json(r);
            if (o != null) {
                JSONArray arr = o.optJSONArray("aweme_list");
                int raw = arr == null ? 0 : arr.length();
                for (Aweme a : Aweme.list(arr)) {
                    if (a.playable() && !a.images) {
                        boolean dup = false;
                        for (Aweme old : out) if (old.id.equals(a.id)) { dup = true; break; }
                        if (!dup) out.add(a);
                    }
                }
                android.util.Log.d("dytv-feed", "tab/feed attempt=" + attempt + " raw=" + raw + " playable=" + out.size());
                if (raw == 0) break;
            } else {
                android.util.Log.d("dytv-feed", "tab/feed attempt=" + attempt + " null json status=" + (r == null ? -1 : r.status));
                break;
            }
        }
        return out;
    }

    public List<LiveRoom> liveFeed(int count) throws IOException {
        return liveList("/webcast/feed/", count, true);
    }

    public List<LiveRoom> liveFollowing(int count) throws IOException {
        return liveList("/webcast/web/feed/follow/", count, false);
    }

    public void resetLiveOffsets() {
        liveFeedOffset = 0;
        liveFollowOffset = 0;
    }

    private List<LiveRoom> liveList(String path, int count, boolean recommend) throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("aid", "6383");
        p.put("app_name", "douyin_web");
        p.put("live_id", "1");
        p.put("device_platform", "web");
        p.put("language", "zh-CN");
        p.put("enter_from", "web_live");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "1536");
        p.put("screen_height", "864");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "131.0.0.0");
        p.put("webcast_sdk_version", "1.0.14");
        p.put("count", String.valueOf(count));
        p.put("offset", String.valueOf(recommend ? liveFeedOffset : liveFollowOffset));
        p.put("msToken", cookies.get("msToken"));
        Http.Resp r = getJson(LIVE_HOST, path, p, LIVE_HOST + "/", false);
        JSONObject o = json(r);
        if (o == null) return new ArrayList<>();
        List<LiveRoom> list = LiveRoom.list(o.optJSONArray("data"));
        if (!list.isEmpty()) {
            if (recommend) liveFeedOffset += list.size();
            else liveFollowOffset += list.size();
        }
        return list;
    }

    public UserProfile profile(String secUid) throws IOException {
        Map<String, String> p = base();
        p.put("sec_user_id", secUid);
        p.put("publish_video_strategy_type", "2");
        p.put("personal_center_strategy", "1");
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/user/profile/other/", p, HOST + "/user/" + secUid, false));
        if (o == null) return null;
        return UserProfile.from(o.optJSONObject("user"));
    }

    public List<Aweme> posts(String secUid, long maxCursor) throws IOException {
        return postPage(secUid, maxCursor).items;
    }

    public AwemePage postPage(String secUid, long maxCursor) throws IOException {
        Map<String, String> p = base();
        p.put("sec_user_id", secUid);
        p.put("count", "18");
        p.put("max_cursor", String.valueOf(maxCursor));
        p.put("locate_query", "false");
        p.put("publish_video_strategy_type", "2");
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/aweme/post/", p, HOST + "/user/" + secUid, true));
        return new AwemePage(o, "max_cursor", false);
    }

    public Aweme detail(String awemeId) {
        if (awemeId == null || !awemeId.matches("[0-9]{6,22}")) return null;
        try {
            Map<String, String> p = base();
            p.put("aweme_id", awemeId);
            p.put("msToken", cookies.get("msToken"));
            p.put("verifyFp", cookies.get("s_v_web_id"));
            p.put("fp", cookies.get("s_v_web_id"));
            JSONObject o = json(getJson(HOST, "/aweme/v1/web/aweme/detail/", p, HOST + "/video/" + awemeId, true));
            if (o != null) {
                Aweme a = Aweme.from(o.optJSONObject("aweme_detail"));
                if (a != null && awemeId.equals(a.id) && a.playable() && !a.images) return a;
            }
        } catch (Exception ignored) {
        }
        return resolveSharePage(awemeId);
    }

    public List<String> comments(String awemeId, int count) {
        List<String> out = new ArrayList<>();
        if (awemeId == null || awemeId.isEmpty()) return out;
        try {
            Map<String, String> p = base();
            p.put("aweme_id", awemeId);
            p.put("cursor", "0");
            p.put("count", String.valueOf(Math.max(10, Math.min(50, count))));
            p.put("item_type", "0");
            p.put("insert_ids", "");
            p.put("whale_cut_token", "");
            p.put("cut_version", "1");
            p.put("msToken", cookies.get("msToken"));
            JSONObject o = json(getJson(HOST, "/aweme/v1/web/comment/list/", p, HOST + "/video/" + awemeId, false));
            if (o != null) {
                JSONArray arr = o.optJSONArray("comments");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        String text = c.optString("text", "");
                        if (!text.isEmpty()) out.add(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Fallback: public iesdouyin share page, no signature required. */
    private Aweme resolveSharePage(String awemeId) {
        try {
            Map<String, String> h = Http.map("User-Agent", MOBILE_UA,
                    "Accept", "text/html,application/xhtml+xml", "Referer", HOST + "/");
            Http.Resp r = Http.get("https://www.iesdouyin.com/share/video/" + awemeId + "/", h, null);
            if (r.text == null) return null;
            Matcher m = SHARE_PAGE_DATA.matcher(r.text);
            if (!m.find()) return null;
            JSONObject root = new JSONObject(m.group(1));
            JSONObject item = findItem(root.optJSONObject("loaderData"), awemeId);
            if (item == null) return null;
            Aweme a = Aweme.from(item);
            return a != null && a.playable() && !a.images ? a : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject findItem(Object node, String awemeId) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (awemeId.equals(Aweme.optId(o, "aweme_id")) && o.optJSONObject("video") != null) {
                return o;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                JSONObject r = findItem(o.opt(it.next()), awemeId);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = findItem(arr.opt(i), awemeId);
                if (r != null) return r;
            }
        }
        return null;
    }

    private Map<String, String> minimalWebParams(boolean includeWebId) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("version_code", "170400");
        p.put("version_name", "17.4.0");
        if (includeWebId) {
            String webid = cookies.get("webid");
            if (webid != null && !webid.isEmpty()) p.put("webid", webid);
        }
        String uifid = cookies.get("UIFID");
        if (uifid == null || uifid.isEmpty()) uifid = cookies.get("UIFID_TEMP");
        if (uifid != null && !uifid.isEmpty()) p.put("uifid", uifid);
        return p;
    }

    private String ensureVerifyFp() {
        String svid = cookies.get("s_v_web_id");
        if (svid == null || svid.isEmpty()) {
            svid = verifyFp();
            cookies.put("s_v_web_id", svid);
        }
        return svid;
    }

    private Http.Resp postSignedForm(String path, Map<String, String> p, String form, String referer)
            throws IOException {
        return postSignedForm(path, p, form, referer, true);
    }

    private Http.Resp postSignedForm(String path, Map<String, String> p, String form, String referer,
                                     boolean includeClientData) throws IOException {
        String qs = Http.query(p);
        String url = HOST + path + "?" + qs + "&a_bogus=" + Http.enc(Abogus.sign(qs));
        Map<String, String> h = headers(referer, true);
        String uifid = cookies.get("UIFID");
        if (uifid == null || uifid.isEmpty()) uifid = cookies.get("UIFID_TEMP");
        if (uifid != null && !uifid.isEmpty()) h.put("uifid", uifid);
        if (includeClientData) {
            try {
                h.putAll(ticketGuard.headers(path));
            } catch (Exception e) {
                android.util.Log.d("dytv-actions", "ticketGuard.headers fallback: " + e);
                h.putAll(ticketGuard.publicHeaders());
            }
        } else {
            h.putAll(ticketGuard.publicHeaders());
        }
        if (sessionDtrait == null) sessionDtrait = Dtrait.build();
        h.put("x-tt-session-dtrait", sessionDtrait);
        String csrf = webCsrf(referer);
        if (!csrf.isEmpty()) h.put("x-secsdk-csrf-token", csrf);
        h.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        Http.Resp r = Http.postFormNoRedirect(url, h, form, cookies);
        captureResponseMeta(r);
        return r;
    }

    public String lastLikeError() { return lastLikeError; }
    public boolean lastLikeAuthError() { return lastLikeAuthError; }

    public boolean like(String awemeId, boolean like) {
        lastLikeError = "";
        lastLikeAuthError = false;
        if (!loggedIn()) {
            lastLikeAuthError = true;
            lastLikeError = "请先扫码登录";
            return false;
        }
        if (awemeId == null || !awemeId.matches("[0-9]{6,22}")) return false;
        try {
            boolean ok = like ? commitDigg(awemeId, true, 2)
                    : (cancelDiggMulti(awemeId) || commitDigg(awemeId, false, 2));
            if (ok) {
                lastLikeError = "";
                lastLikeAuthError = false;
                likedInSession.put(awemeId, like);
                likeRevision.incrementAndGet();
            }
            return ok;
        } catch (Exception e) {
            android.util.Log.d("dytv-actions", (like ? "like" : "unlike") + " error " + e);
            lastLikeError = e.getMessage() == null ? "网络异常" : e.getMessage();
            return false;
        }
    }

    private boolean commitDigg(String awemeId, boolean like, int retriesLeft) throws IOException {
        Map<String, String> p = base();
        String svid = ensureVerifyFp();
        p.put("verifyFp", svid);
        p.put("fp", svid);
        p.put("msToken", cookies.get("msToken"));
        String form = Http.query(Http.map("aweme_id", awemeId, "item_type", "0", "type", like ? "1" : "0"));
        Http.Resp r = postSignedForm("/aweme/v1/web/commit/item/digg/", p, form,
                HOST + "/?recommend=1", true);
        JSONObject o = json(r);
        int code = o == null ? -1 : o.optInt("status_code", -1);
        boolean ok = o != null && code == 0;
        String bdt = r.header("X-Vc-Bdturing-Parameters");
        String tgRes = r.header("Bd-Ticket-Guard-Result");
        String tgKey = r.header("Bd-Ticket-Guard-Key-Sign-Result");
        android.util.Log.d("dytv-actions", (like ? "like" : "unlike-commit")
                + " HTTP " + r.status + ", code=" + code
                + ", bdt=" + (bdt != null) + ", tg=" + tgRes + "/" + tgKey
                + ", body=" + (r.text == null ? "null" : r.text.substring(0, Math.min(200, r.text.length()))));
        if (!ok && retriesLeft > 0 && bdt != null) {
            // bdturing 滑块拦截会绑定旧 s_v_web_id；轮换并短暂停顿后重试即可自动恢复点赞。
            cookies.put("s_v_web_id", verifyFp());
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            return commitDigg(awemeId, like, retriesLeft - 1);
        }
        if (!ok && retriesLeft > 0 && code == 8) {
            bootstrap(true);
            return commitDigg(awemeId, like, retriesLeft - 1);
        }
        if (!ok) {
            if (code == 8) {
                lastLikeAuthError = true;
                lastLikeError = "登录会话已失效，请重新扫码";
            } else if (bdt != null) {
                lastLikeError = "触发安全验证，请稍后再试";
            } else if (r.status == 403 || "1104".equals(tgRes)) {
                lastLikeError = "请求签名校验未通过（" + r.status + "）";
            } else if (o != null && !o.optString("status_msg").isEmpty()) {
                lastLikeError = o.optString("status_msg");
            }
        }
        return ok;
    }

    private boolean cancelDiggMulti(String awemeId) throws IOException {
        Map<String, String> p = minimalWebParams(true);
        String svid = ensureVerifyFp();
        p.put("verifyFp", svid);
        p.put("fp", svid);
        p.put("msToken", cookies.get("msToken"));
        String itemTypeMap = "{\"" + awemeId + "\":0}";
        String form = Http.query(Http.map("aweme_ids", awemeId, "item_type_map", itemTypeMap));
        Http.Resp r = postSignedForm("/aweme/v1/web/cancel/item/digg/multi/", p, form,
                HOST + "/user/self?showTab=like", true);
        JSONObject o = json(r);
        int code = o == null ? -1 : o.optInt("status_code", -1);
        boolean ok = o != null && code == 0;
        String tgRes = r.header("Bd-Ticket-Guard-Result");
        android.util.Log.d("dytv-actions", "unlike-multi HTTP " + r.status
                + ", code=" + code + ", tg=" + tgRes
                + ", body=" + (r.text == null ? "null" : r.text.substring(0, Math.min(200, r.text.length()))));
        if (!ok && code == 8) {
            lastLikeAuthError = true;
            lastLikeError = "登录会话已失效，请重新扫码";
        }
        return ok;
    }

    public long likeRevision() { return likeRevision.get(); }

    public void applyKnownLike(Aweme item) {
        Boolean liked = likedInSession.get(item.id);
        if (liked != null) item.userDigged = liked ? 1 : 0;
    }

    private String webCsrf(String referer) {
        try {
            Http.Resp response = Http.request("HEAD", HOST + "/service/2/abtest_config/",
                    Http.map("Referer", referer, "x-secsdk-csrf-request", "1", "x-secsdk-csrf-version", "1.2.22"),
                    (String) null, cookies, false, false);
            String raw = response.header("X-Ware-Csrf-Token");
            if (raw != null) {
                String[] parts = raw.split(",", -1);
                if (parts.length > 1) return parts[1].trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    public String selfSecUid() {
        JSONObject u = selfUser();
        if (u == null) return null;
        String sec = u.optString("sec_uid", "");
        return sec.isEmpty() ? null : sec;
    }

    public AwemePage likedPage(String secUid, long maxCursor) throws IOException {
        // 喜欢列表需要 base() 浏览器参数 + uifid + verifyFp/fp（不能带 webid）且始终保留 x-tt-argus=1。
        Map<String, String> p = base();
        p.put("sec_user_id", secUid);
        p.put("max_cursor", String.valueOf(maxCursor));
        p.put("min_cursor", "0");
        p.put("count", "18");
        p.put("msToken", cookies.get("msToken"));
        String svid = ensureVerifyFp();
        p.put("verifyFp", svid);
        p.put("fp", svid);
        p.remove("webid");
        String path = "/aweme/v1/web/aweme/favorite/";
        String referer = HOST + "/user/" + Http.enc(secUid) + "?showTab=like";
        Http.Resp response = getJson(HOST, path, p, referer, true);
        JSONObject o = json(response);
        int[] waits = {500, 800, 1200, 1500};
        for (int attempt = 0; attempt < waits.length && o == null && response.status == 200; attempt++) {
            // Douyin /aweme/favorite/ 对短时连续请求容易返回 200 空响应(bdt)；保持原 s_v_web_id 退避重试。
            android.os.SystemClock.sleep(waits[attempt]);
            if (attempt % 2 == 0) {
                p.put("msToken", "");
            } else {
                String tok = cookies.get("msToken");
                p.put("msToken", tok == null ? "" : tok);
            }
            response = getJson(HOST, path, p, referer, true);
            o = json(response);
        }
        int status = o == null ? -1 : o.optInt("status_code", -1);
        if (status != 0) {
            android.util.Log.d("dytv-actions", "Liked list HTTP " + response.status + ", status=" + status
                    + ", bdt=" + (response.header("X-Vc-Bdturing-Parameters") != null)
                    + ", body=" + (response.text == null ? "null"
                    : response.text.substring(0, Math.min(200, response.text.length()))));
            throw new IOException("喜欢列表加载失败");
        }
        return new AwemePage(o, "max_cursor", true);
    }

    public List<Aweme> collections(int count) throws IOException {
        return collectionPage(count, 0).items;
    }

    public AwemePage collectionPage(int count, long cursor) throws IOException {
        Map<String, String> p = base();
        p.put("count", String.valueOf(count));
        p.put("cursor", String.valueOf(cursor));
        p.put("publish_video_strategy_type", "2");
        p.put("msToken", cookies.get("msToken"));
        String qs = Http.query(p);
        String url = HOST + "/aweme/v1/web/aweme/listcollection/?" + qs
                + "&a_bogus=" + Http.enc(Abogus.signForm(qs, "{}"));
        Map<String, String> h = headers(HOST + "/user/self", true);
        h.put("Content-Type", "application/json");
        Http.Resp r = Http.postForm(url, h, "{}", cookies);
        JSONObject o = json(r);
        return new AwemePage(o, "cursor", false);
    }

    public static final class AwemePage {
        public final List<Aweme> items = new ArrayList<>();
        public final long nextCursor;
        public final boolean hasMore;

        private AwemePage(JSONObject response, String cursorField, boolean liked) throws IOException {
            if (response == null || response.optInt("status_code", 0) != 0) {
                throw new IOException("抖音未返回作品列表");
            }
            nextCursor = response.optLong(cursorField);
            hasMore = response.optBoolean("has_more") || response.optInt("has_more") == 1;
            for (Aweme item : Aweme.list(response.optJSONArray("aweme_list"))) {
                if (item.images) continue;
                if (liked) item.userDigged = 1;
                items.add(item);
            }
        }
    }

    /** 抖音好友（互关 + 最近私信），按最近互动排序。 */
    public List<Friend> contacts() throws IOException {
        Map<String, String> p = base();
        p.put("count", "50");
        p.put("source", "coldup");
        p.put("max_time", "0");
        p.put("min_time", "0");
        p.put("need_remove_share_panel", "true");
        p.put("need_sorted_info", "true");
        p.put("with_fstatus", "1");
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/im/spotlight/relation/", p, HOST + "/", false));
        List<Friend> out = new ArrayList<>();
        if (o == null) return out;
        Map<String, Long> recent = new HashMap<>();
        JSONArray shares = o.optJSONArray("single_recent_share_users");
        if (shares != null) {
            for (int i = 0; i < shares.length(); i++) {
                JSONObject s = shares.optJSONObject(i);
                if (s == null) continue;
                recent.put(s.optString("uid", ""), s.optLong("timestamp"));
            }
        }
        JSONArray arr = o.optJSONArray("followings");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject u = arr.optJSONObject(i);
                if (u == null) continue;
                Friend f = new Friend();
                f.id = u.optString("uid", "");
                f.name = u.optString("nickname", "");
                f.secUid = u.optString("sec_uid", "");
                f.uniqueId = u.optString("unique_id", "");
                f.avatar = Aweme.firstUrl(u.optJSONObject("avatar_small"));
                if (f.avatar.isEmpty()) f.avatar = Aweme.firstUrl(u.optJSONObject("avatar_thumb"));
                Long t = recent.get(f.id);
                if (t != null) f.lastActive = t;
                if (f.id.isEmpty()) f.id = f.secUid.isEmpty() ? f.name : f.secUid;
                if (!f.name.isEmpty()) out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastActive, a.lastActive));
        return out;
    }

    /** 朋友页信息流：好友发布 / 分享的视频（分页）。 */
    public FamiliarPage familiarPage(int count, long cursor) throws IOException {
        Map<String, String> p = base();
        p.put("count", String.valueOf(count));
        p.put("cursor", String.valueOf(cursor));
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/familiar/feed/", p, HOST + "/", true));
        FamiliarPage page = new FamiliarPage();
        if (o == null || o.optInt("status_code", 0) != 0) {
            throw new IOException("朋友分享加载失败");
        }
        JSONArray arr = o.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                Aweme a = Aweme.from(item.optJSONObject("aweme"));
                if (a != null && !a.id.isEmpty() && !a.images) page.items.add(a);
            }
        }
        page.nextCursor = o.optLong("cursor");
        page.hasMore = !page.items.isEmpty();
        return page;
    }

    public static final class FamiliarPage {
        public final List<Aweme> items = new ArrayList<>();
        public long nextCursor;
        public boolean hasMore;
    }

    /** 朋友页信息流：好友发布 / 分享的视频。 */
    public List<Aweme> familiarFeed(int count) throws IOException {
        Map<String, String> p = base();
        p.put("count", String.valueOf(count));
        p.put("cursor", "0");
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/familiar/feed/", p, HOST + "/", true));
        List<Aweme> out = new ArrayList<>();
        if (o == null) return out;
        JSONArray arr = o.optJSONArray("data");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            Aweme a = Aweme.from(item.optJSONObject("aweme"));
            if (a != null && !a.id.isEmpty() && !a.images) out.add(a);
        }
        return out;
    }

    public List<Aweme> history() throws IOException {
        Map<String, String> p = base();
        p.put("count", "30");
        p.put("max_cursor", "0");
        p.put("msToken", cookies.get("msToken"));
        JSONObject o = json(getJson(HOST, "/aweme/v1/web/history/read/", p, HOST + "/user/self", true));
        List<Aweme> out = new ArrayList<>();
        if (o != null) {
            for (Aweme a : Aweme.list(o.optJSONArray("aweme_list"))) {
                if (!a.images) out.add(a);
            }
        }
        return out;
    }

    public void writeHistory(Aweme a, long progressMs) {
        if (!loggedIn() || a == null) return;
        try {
            Map<String, String> p = base();
            p.put("aweme_id", a.id);
            p.put("item_id", a.id);
            p.put("play_progress", String.valueOf(progressMs));
            p.put("duration", String.valueOf(a.durationMs));
            p.put("msToken", cookies.get("msToken"));
            String qs = Http.query(p);
            String url = HOST + "/aweme/v1/web/history/write/?" + qs + "&a_bogus=" + Http.enc(Abogus.sign(qs));
            Http.postForm(url, headers(HOST + "/video/" + a.id, true), "", cookies);
        } catch (Exception ignored) {
        }
    }

    // ---------- QR login ----------

    private static final String PASSPORT_APP_KEY = "163e7ce78d58971a41f5b969996d85c2";
    private String passportTraceId = hex(4);
    private String verifyPortrait = java.util.UUID.randomUUID().toString() + ".login";
    private String sessionDtrait;
    private boolean msTokenCaptured;

    private void startLoginSession() {
        passportTraceId = hex(4);
        verifyPortrait = java.util.UUID.randomUUID().toString() + ".login";
        sessionDtrait = Dtrait.build();
        cookies.put("biz_trace_id", passportTraceId);
    }

    private enum PassportRequest { STRATEGY, QR, STATUS }

    /** Common parameters for the three steps of a QR login session. */
    private Map<String, String> passportBase(PassportRequest request) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("passport_jssdk_version", "3.4.13");
        p.put("passport_jssdk_type", "normal");
        p.put("is_from_ttaccountsdk", "1");
        p.put("aid", "6383");
        p.put("language", "zh");
        p.put("account_app_language", "zh-CN");
        p.put("ts", dayTs());
        if (request == PassportRequest.QR) {
            p.put("next", HOST);
            p.put("need_short_url", "true");
            p.put("need_logo", "false");
            p.put("is_new_login", "1");
        }
        if (request != PassportRequest.STRATEGY) {
            p.put("is_from_iesaccountsaas", "1");
            p.put("p_ui", "2.4.15");
            p.put("p_ca", "4.0.26");
            p.put("p_ca_real", "1.0.0.1013");
            String fp = cookies.get("s_v_web_id");
            if (fp == null || fp.isEmpty()) {
                fp = verifyFp();
                cookies.put("s_v_web_id", fp);
            }
            p.put("fp", fp);
            p.put("verifyFp", fp);
        }
        p.put("account_sdk_source", "web");
        // The hex-encoded value participates in sign as well as in the transmitted query.
        p.put("account_sdk_source_info", x5(dynamicAccountSdkInfo()));
        p.put("p_js_v", "3.4.13");
        p.put("p_js_t", "pro");
        p.put("p_zt", "3.3.34-beta.10");
        p.put("p_ver", "1.1.4-beta.5");
        p.put("p_ver_real", "0");
        // The web client sends request_host already URL-encoded, then encodes it again.
        p.put("request_host", "https%3A%2F%2Fwww.douyin.com");
        p.put("p_bd", "1.0.1.19-fix.01");
        String pTs = String.valueOf(System.currentTimeMillis());
        p.put("p_ts", pTs);
        p.put("p_no", pNo(pTs, p.containsKey("p_ca") ? p.get("p_ca") : "0"));
        p.put("biz_trace_id", passportTraceId);
        p.put("device_platform", "web_app");
        return p;
    }

    private static String pNo(String pTs, String pCa) {
        String raw = "p_bd=1.0.1.19-fix.01"
                + "&p_ca=" + pCa
                + "&p_ts=" + pTs
                + "&p_ver=1.1.4-beta.5"
                + "&p_zt=3.3.34-beta.10"
                + "&passport_jssdk_version=3.4.13";
        return sha256Hex(raw);
    }

    private static String dayTs() {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        c.add(java.util.Calendar.HOUR_OF_DAY, 12);
        return String.valueOf(c.getTimeInMillis() / 1000L);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes("UTF-8"));
            return toHex(d);
        } catch (Exception e) {
            return hex(32);
        }
    }

    private static String decodeXor5(String h) {
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) ^ 0x05);
        }
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] hkdf(byte[] prk, int length) throws Exception {
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            byte[] input = new byte[block.length + 1];
            System.arraycopy(block, 0, input, 0, block.length);
            input[block.length] = (byte) counter;
            block = hmacSha256(prk, input);
            int n = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, out, offset, n);
            offset += n;
            counter++;
        }
        return out;
    }

    /** x-tt-passport-aid-sign: HMAC-SHA256(HKDF(HMAC-SHA256(ts, appKey)), aid&path&ts). */
    private static String aidSign(String path) {
        try {
            String ts = dayTs();
            byte[] prk = hmacSha256(ts.getBytes("UTF-8"), PASSPORT_APP_KEY.getBytes("UTF-8"));
            byte[] okm = hkdf(prk, 32);
            byte[] msg = ("aid=6383&path=" + path + "&ts=" + ts).getBytes("UTF-8");
            return toHex(hmacSha256(okm, msg));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    /** enc(s) = hex(utf8(s) XOR 0x05), used for qs and body fields. */
    private static String x5(String text) {
        try {
            byte[] b = text.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte v : b) sb.append(String.format(java.util.Locale.US, "%02x", (v ^ 0x05) & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** encodeURIComponent-compatible percent encoding (space -> %20). */
    private static String encComponent(String s) {
        try {
            byte[] b = s.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(b.length);
            for (byte v : b) {
                int c = v & 0xff;
                boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
                        || c == '*' || c == '\'' || c == '(' || c == ')';
                if (plain) {
                    sb.append((char) c);
                } else {
                    sb.append('%')
                            .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
                            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String joinSorted(Map<String, String> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(map.get(k));
        }
        return sb.toString();
    }

    /**
     * Adds sign/qs (and captured msToken) to the params, serialises the query and
     * appends a_bogus signed over the wire query and form.
     */
    private String signedQuery(Map<String, String> params, Map<String, String> body) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        List<String> first = keys.subList(0, Math.min(10, keys.size()));
        StringBuilder raw = new StringBuilder();
        for (String k : first) {
            if (raw.length() > 0) raw.append('&');
            raw.append(k).append('=').append(params.get(k));
        }
        raw.append('&').append(joinSorted(body)).append("&app_key=").append(PASSPORT_APP_KEY);
        params.put("sign", sha256Hex(raw.toString()));
        StringBuilder qs = new StringBuilder();
        for (String k : first) {
            if (qs.length() > 0) qs.append(',');
            qs.append(k);
        }
        params.put("qs", x5(qs.toString()));
        if (msTokenCaptured) {
            String ms = cookies.get("msToken");
            if (ms != null && !ms.isEmpty()) params.put("msToken", ms);
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(e.getKey()).append('=').append(encComponent(e.getValue() == null ? "" : e.getValue()));
        }
        String rawQuery = query.toString();
        return rawQuery + "&a_bogus=" + encComponent(Abogus.signForm(rawQuery, Http.query(body)));
    }

    private Map<String, String> passportHeaders(String path, boolean post) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json, text/javascript");
        h.put("Origin", HOST);
        h.put("Referer", HOST + "/");
        h.put("web-sdk-version", "1");
        if (sessionDtrait == null) sessionDtrait = Dtrait.build();
        h.put("x-tt-session-dtrait", sessionDtrait);
        h.put("x-tt-passport-aid-sign", aidSign(path));
        String csrf = cookies.get("passport_csrf_token");
        if (csrf == null || csrf.isEmpty()) csrf = cookies.get("passport_csrf_token_default");
        if (csrf != null && !csrf.isEmpty()) h.put("x-tt-passport-csrf-token", csrf);
        h.put("x-tt-passport-trace-id", passportTraceId);
        h.put("x-tt-passport-verify-portrait", verifyPortrait);
        if (post) {
            h.put("Content-Type", "application/x-www-form-urlencoded");
            h.put("x-secsdk-csrf-token", "DOWNGRADE");
        }
        return h;
    }

    private static String dynamicAccountSdkInfo() {
        String origin = String.format(java.util.Locale.US, "%.1f", System.currentTimeMillis() - 1250.3);
        return ACCOUNT_SDK_INFO.replaceFirst("\"timeOrigin\":[0-9.]+", "\"timeOrigin\":" + origin);
    }

    private void captureResponseMeta(Http.Resp r) {
        if (r == null) return;
        captureMsToken(r);
        ticketGuard.capture(r);
    }

    private void captureMsToken(Http.Resp r) {
        if (r == null) return;
        String v = r.header("X-Ms-Token");
        if (v != null && !v.isEmpty()) {
            cookies.put("msToken", v);
            cookies.setMsCaptured(true);
            msTokenCaptured = true;
        }
    }

    public JSONObject getQrCode() throws Exception {
        startLoginSession();
        ticketGuard.prepareLogin();
        prepareQrLogin();
        Map<String, String> p = passportBase(PassportRequest.QR);
        String query = signedQuery(p, new LinkedHashMap<String, String>());
        Http.Resp r = Http.get(LOGIN_HOST + "/passport/web/get_qrcode/?" + query,
                passportHeaders("/passport/web/get_qrcode/", false), cookies);
        captureMsToken(r);
        ticketGuard.capture(r);
        JSONObject o = json(r);
        JSONObject data = o == null ? null : o.optJSONObject("data");
        android.util.Log.d("dytvlogin", "QR HTTP " + r.status + ", ready="
                + (data != null && !data.optString("qrcode").isEmpty()));
        return data == null ? o : data;
    }

    private void prepareQrLogin() {
        String path = "/passport/general/login_guiding_strategy/";
        try {
            String query = signedQuery(passportBase(PassportRequest.STRATEGY), Collections.emptyMap());
            Map<String, String> headers = passportHeaders(path, false);
            headers.remove("web-sdk-version");
            headers.remove("x-tt-session-dtrait");
            headers.remove("x-tt-passport-verify-portrait");
            captureMsToken(Http.getNoRedirect(LOGIN_HOST + path + "?" + query, headers, cookies));
        } catch (IOException e) {
            android.util.Log.d("dytvlogin", "Login strategy request failed");
        }
    }

    public JSONObject checkQr(String token, String verifyTicket, String stdVerifyWay)
            throws Exception {
        Map<String, String> p = passportBase(PassportRequest.STATUS);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("need_logo", "false");
        // 原生端通过 HTTP 接收扫码确认，避免等待网页 WebSocket 推送。
        body.put("is_frontier", "false");
        body.put("token", token);
        body.put("is_new_login", "1");
        body.put("next", HOST);
        body.put("need_short_url", "true");
        if (verifyTicket != null && !verifyTicket.isEmpty()) {
            body.put("verify_ticket", verifyTicket);
            body.put("std_verify_way", stdVerifyWay == null ? "mobile_sms_verify" : stdVerifyWay);
        }
        String query = signedQuery(p, body);
        Http.Resp r = Http.postFormNoRedirect(LOGIN_HOST + "/passport/web/check_qrconnect/?" + query,
                passportHeaders("/passport/web/check_qrconnect/", true), Http.query(body), cookies);
        captureMsToken(r);
        ticketGuard.capture(r);
        String location = r.header("Location");
        if (location != null && !location.isEmpty()) {
            JSONObject d = new JSONObject();
            d.put("redirect_url", location);
            d.put("status", "3");
            return d;
        }
        JSONObject o = json(r);
        if (o == null) return null;
        JSONObject data = o.optJSONObject("data");
        if (data == null) data = o;
        String[] promoted = {"redirect_url", "redirect_uri", "login_url", "scan_url", "url",
                "status", "status_code", "error_code", "description"};
        for (String k : promoted) {
            if (!data.has(k) && o.has(k)) data.put(k, o.opt(k));
        }
        String status = data.optString("status", "");
        android.util.Log.d("dytvlogin", "Poll HTTP " + r.status + ", code="
                + data.optInt("error_code", 0) + ", state="
                + (status.matches("[a-zA-Z_0-9]{1,20}") ? status : "?"));
        return data;
    }

    public synchronized void logout() {
        cachedSelfUser = null;
        selfUserAt = 0;
        likedInSession.clear();
        likeRevision.incrementAndGet();
        ticketGuard.clear();
        cookies.clear();
        initializedAt = 0;
        msTokenCaptured = false;
        if (ink.pengfei.douyin.App.get() != null && ink.pengfei.douyin.App.get().store() != null) {
            ink.pengfei.douyin.App.get().store().clearRecent();
            ink.pengfei.douyin.App.get().store().setAccountName("");
            ink.pengfei.douyin.App.get().store().setAccountAvatar("");
        }
    }

    // ---------- SMS secondary verification (account_flow=verify) ----------

    public static boolean mfaNeedsSms(JSONObject data) {
        return smsWay(data) != null;
    }

    public static String mfaMobile(JSONObject data) {
        JSONObject way = smsWay(data);
        String mobile = way == null ? "" : way.optString("mobile", "");
        if (mobile.length() == 11) return mobile.substring(0, 3) + "****" + mobile.substring(7);
        return mobile;
    }

    private static JSONObject smsWay(JSONObject data) {
        if (data == null) return null;
        JSONArray ways = data.optJSONArray("verify_ways");
        if (ways == null) return null;
        for (int i = 0; i < ways.length(); i++) {
            JSONObject w = ways.optJSONObject(i);
            if (w != null && "mobile_sms_verify".equals(w.optString("verify_way"))) return w;
        }
        return null;
    }

    public void sendMfaCode(JSONObject mfa) throws Exception {
        mfaRequest("/passport/web/send_code/", mfaForm(mfa, null));
    }

    /** Returns the verify ticket that must accompany the following check_qrconnect calls. */
    public String validateMfaCode(JSONObject mfa, String code) throws Exception {
        JSONObject data = mfaRequest("/passport/web/validate_code/", mfaForm(mfa, code));
        String ticket = data.optString("ticket", "");
        if (ticket.isEmpty() && data.optJSONObject("data") != null) {
            ticket = data.optJSONObject("data").optString("ticket", "");
        }
        if (ticket.isEmpty()) throw new IOException("验证成功但未返回登录票据");
        return ticket;
    }

    private Map<String, String> mfaForm(JSONObject data, String code) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("encrypt_uid", data.optString("encrypt_uid", ""));
        f.put("verify_ticket", data.optString("verify_ticket", ""));
        JSONObject common = data.optJSONObject("common_params");
        if (common != null) {
            java.util.Iterator<String> it = common.keys();
            while (it.hasNext()) {
                String k = it.next();
                f.put(k, String.valueOf(common.opt(k)));
            }
        }
        String actType = "22";
        JSONObject way = smsWay(data);
        if (way != null) {
            String at = way.optString("act_type", "");
            if (!at.isEmpty()) actType = at;
        }
        f.put("type", xor5Hex(actType));
        f.put("aid", "6383");
        f.put("std_verify_way", "mobile_sms_verify");
        f.put("new_authn_sdk_version", "1.0.0.413");
        f.put("mix_mode", "1");
        if (code != null && !code.isEmpty()) f.put("code", xor5Hex(code));
        return f;
    }

    private JSONObject mfaRequest(String path, Map<String, String> form) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("passport_jssdk_version", "5.1.2");
        q.put("passport_jssdk_type", "lite");
        q.put("is_from_ttaccountsdk", "1");
        q.put("aid", "6383");
        q.put("language", "zh");
        q.put("account_app_language", "zh-CN");
        q.put("new_authn_sdk_version", "1.0.0.413");
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (payload.length() > 0) payload.append('&');
            payload.append(e.getKey()).append('=').append(encComponent(e.getValue()));
        }
        Http.Resp r = Http.postForm(LOGIN_HOST + path + "?" + Http.query(q),
                Http.map("Accept", "application/json, text/javascript",
                        "Origin", HOST, "Referer", HOST + "/",
                        "Content-Type", "application/x-www-form-urlencoded"),
                payload.toString(), cookies);
        JSONObject o = json(r);
        if (o == null) throw new IOException("验证服务无响应");
        JSONObject data = o.optJSONObject("data");
        int err = data != null ? data.optInt("error_code", 0) : o.optInt("error_code", 0);
        if (err != 0) {
            String desc = data != null ? data.optString("description", "") : o.optString("description", "");
            throw new IOException(desc.isEmpty() ? "验证服务返回错误 (" + err + ")" : desc);
        }
        return data == null ? new JSONObject() : data;
    }

    private static String xor5Hex(String value) {
        byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) raw[i] ^= 5;
        return toHex(raw);
    }

    /** Follows the confirmed redirect chain, merging session cookies. */
    public boolean completeLogin(String redirectUrl) {
        cookies.saveInvalidSession(null);
        cachedSelfUser = null;
        selfUserAt = 0;
        try {
            java.net.URL url = new java.net.URL(new java.net.URL(HOST), redirectUrl);
            for (int i = 0; i < 6 && !redirectUrl.isEmpty() && isDouyinHttps(url); i++) {
                Http.Resp r = Http.getNoRedirect(url.toString(),
                        Http.map("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                "Referer", HOST + "/"),
                        cookies);
                captureResponseMeta(r);
                String loc = r.header("Location");
                if (r.status >= 300 && r.status < 400 && loc != null) {
                    url = new java.net.URL(url, loc);
                } else {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        if (!loggedIn() || !ticketGuard.hasPendingTicket() || !msTokenCaptured) {
            // Ensure homepage hop runs so fresh msToken / server_data / __ac_nonce settle on the new session.
            try {
                Http.Resp home = Http.getNoRedirect(HOST + "/",
                        Http.map("Accept", "text/html,application/xhtml+xml"), cookies);
                captureResponseMeta(home);
                cookies.markWebBootstrap();
            } catch (Exception ignored) {
            }
        }
        boolean ready = loggedIn();
        if (ready) {
            cookies.saveInvalidSession(null);
            android.util.Log.d("dytvlogin", "Session ready, private-message ticket="
                    + ticketGuard.finishLogin());
        }
        return ready;
    }

    private static boolean isDouyinHttps(java.net.URL url) {
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        return "https".equals(url.getProtocol())
                && (host.equals("douyin.com") || host.endsWith(".douyin.com")
                || host.equals("snssdk.com") || host.endsWith(".snssdk.com"));
    }

    public String selfName() {
        JSONObject u = selfUser();
        if (u != null && !u.optString("nickname", "").isEmpty()) return u.optString("nickname");
        return "已登录";
    }

    public String selfUid() throws IOException {
        JSONObject user = selfUser();
        String uid = user == null ? "" : Aweme.optId(user, "uid");
        if (uid.isEmpty() && user != null) uid = Aweme.optId(user, "user_id");
        if (!uid.matches("[0-9]+")) throw new IOException("未取得抖音账号信息");
        return uid;
    }

    Map<String, String> imIdentity() throws Exception {
        String path = "/passport/safe/get_identity_security_token/";
        String trace = hex(4);
        String query = Http.query(Http.map("passport_jssdk_version", "4.2.3",
                "passport_jssdk_type", "lite", "is_from_ttaccountsdk", "1", "aid", "6383",
                "language", "zh", "scene", "web_im", "auto_retry_req", "0",
                "skip_verify", "false", "identity_token_force_get_tag", "0",
                "biz_trace_id", trace, "id_token_version", "1.2.10",
                "msToken", cookies.get("msToken")));
        Map<String, String> headers = passportHeaders(path, false);
        headers.putAll(imSignedHeaders(path));
        headers.put("Referer", HOST + "/chat?isPopup=1");
        headers.put("x-tt-passport-trace-id", trace);
        JSONObject response = json(Http.getNoRedirect(HOST + path + "?" + query
                + "&a_bogus=" + Http.enc(Abogus.sign(query)), headers, cookies));
        JSONObject data = response == null ? null : response.optJSONObject("data");
        String token = data == null ? "" : data.optString("identity_security_token", "");
        String did = data == null ? "" : data.optString("device_id", "");
        android.util.Log.d("dytv-im", "imIdentity tokenLen=" + token.length()
                + " didLen=" + did.length());
        if (token.isEmpty()) throw new IOException("抖音未授权当前会话发送私信");
        return Http.map("identity_security_token", new JSONObject().put("token", token).toString(),
                "identity_security_device_id", did);
    }

    /** 换取 ticket-guard 客户端证书与服务器证书（ECDH 密钥协商材料），结果缓存在 ticket 数据里。 */
    private void ensureTicketCert() {
        try {
            if (!ticketGuard.needsCert() || !loggedIn()) return;
            String csrB64 = ticketGuard.csrBase64();
            String body = "csr=" + Http.enc(csrB64) + "&server_data=1&aid=6383";
            Http.Resp r = Http.postForm(LOGIN_HOST + "/passport/ticket_guard/get_client_cert/"
                    + "?aid=6383&is_from_ttaccountsdk=1",
                    Http.map("Accept", "application/json", "Content-Type", "application/x-www-form-urlencoded",
                            "Referer", HOST + "/", "Origin", HOST), body, cookies);
            JSONObject o = json(r);
            JSONObject data = o == null ? null : o.optJSONObject("data");
            String serverPem = data == null ? "" : data.optString("server_cert", "");
            String clientPem = data == null ? "" : data.optString("cert", "");
            android.util.Log.d("dytv-actions", "ticket cert HTTP " + r.status
                    + ", server=" + serverPem.length() + ", client=" + clientPem.length());
            if (!serverPem.isEmpty() || !clientPem.isEmpty()) {
                ticketGuard.setCertBundle(serverPem, clientPem);
            }
        } catch (Exception e) {
            android.util.Log.d("dytv-actions", "ticket cert error " + e);
        }
    }

    Map<String, String> imSignedHeaders(String path) throws Exception {
        Map<String, String> headers = ticketGuard.headers(path);
        if (sessionDtrait == null) sessionDtrait = Dtrait.build();
        headers.put("x-tt-session-dtrait", sessionDtrait);
        headers.put("x-tt-argus", "1");
        String uifid = cookies.get("UIFID");
        if (uifid == null || uifid.isEmpty()) uifid = cookies.get("UIFID_TEMP");
        if (uifid != null && !uifid.isEmpty()) headers.put("uifid", uifid);
        if ("/v1/message/send".equals(path)) {
            String csrf = webCsrf(HOST + "/");
            if (!csrf.isEmpty()) headers.put("x-secsdk-csrf-token", csrf);
        }
        return headers;
    }

    private final Object selfUserLock = new Object();

    public JSONObject selfUser() {
        JSONObject cached = cachedSelfUser;
        if (cached != null && android.os.SystemClock.elapsedRealtime() - selfUserAt < 120000L) {
            return cached;
        }
        synchronized (selfUserLock) {
            cached = cachedSelfUser;
            if (cached != null && android.os.SystemClock.elapsedRealtime() - selfUserAt < 120000L) {
                return cached;
            }
            try {
                bootstrap(false);
            } catch (Exception ignored) {
            }
            boolean sawNotLogged = false;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt == 1) {
                    try {
                        bootstrap(true);
                    } catch (Exception ignored) {
                    }
                }
                JSONObject o = querySelfProfile(attempt == 1 && ticketGuard.hasTicket());
                if (o == null) continue;
                int sc = o.optInt("status_code", 0);
                if (sc == 8 || o.optString("status_msg", "").contains("未登录")) {
                    sawNotLogged = true;
                    continue;
                }
                JSONObject u = extractSelfUser(o);
                if (u != null && !u.optString("nickname", "").isEmpty()) {
                    cookies.saveInvalidSession(null);
                    cachedSelfUser = u;
                    selfUserAt = android.os.SystemClock.elapsedRealtime();
                    UserProfile up = UserProfile.from(u);
                    if (up != null && ink.pengfei.douyin.App.get() != null
                            && ink.pengfei.douyin.App.get().store() != null) {
                        if (!up.nickname.isEmpty()) ink.pengfei.douyin.App.get().store().setAccountName(up.nickname);
                        if (!up.avatar.isEmpty()) ink.pengfei.douyin.App.get().store().setAccountAvatar(up.avatar);
                    }
                    return u;
                }
            }
            if (sawNotLogged && confirmPassportExpired()) {
                String sid = cookies.get("sessionid");
                if (sid == null || sid.isEmpty()) sid = cookies.get("sessionid_ss");
                if (sid != null && !sid.isEmpty()) cookies.saveInvalidSession(sid);
            }
            return null;
        }
    }

    private JSONObject querySelfProfile(boolean useAuthed) {
        try {
            String svid = ensureVerifyFp();
            Map<String, String> p = base();
            p.put("verifyFp", svid);
            p.put("fp", svid);
            p.put("msToken", cookies.get("msToken"));
            Http.Resp r = useAuthed
                    ? getAuthedJson(HOST, "/aweme/v1/web/user/profile/self/", p, HOST + "/")
                    : getJson(HOST, "/aweme/v1/web/user/profile/self/", p, HOST + "/", true);
            JSONObject o = json(r);
            android.util.Log.d("dytv-actions", "selfUser profile/self (auth=" + useAuthed + ") -> "
                    + (o == null ? "null" : o.toString().substring(0, Math.min(300, o.toString().length()))));
            return o;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject extractSelfUser(JSONObject o) {
        if (o == null) return null;
        JSONObject u = o.optJSONObject("user");
        if (u == null) u = o.optJSONObject("user_info");
        if (u == null && o.optJSONObject("data") != null) {
            u = o.optJSONObject("data").optJSONObject("user");
            if (u == null) u = o.optJSONObject("data").optJSONObject("user_info");
        }
        return u;
    }

    private boolean confirmPassportExpired() {
        try {
            Http.Resp r = Http.get(LOGIN_HOST + "/passport/account/info/v2/?aid=6383",
                    Http.map("Accept", "application/json, text/plain, */*",
                            "Referer", HOST + "/", "Origin", HOST),
                    cookies);
            JSONObject o = json(r);
            JSONObject data = o == null ? null : o.optJSONObject("data");
            int err = data == null ? 0 : data.optInt("error_code", 0);
            android.util.Log.d("dytv-actions", "passport/account/info/v2 HTTP " + r.status + " err=" + err);
            return err == 13 || r.status == 401;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String debugProbeSession() {
        StringBuilder sb = new StringBuilder();
        try {
            String path = "/passport/web/account/info/";
            Map<String, String> p = passportBase(PassportRequest.STRATEGY);
            String q = signedQuery(p, Collections.emptyMap());
            Http.Resp r1 = Http.get(LOGIN_HOST + path + "?" + q, passportHeaders(path, false), cookies);
            sb.append("accInfo=").append(r1.status).append(':').append(r1.text == null ? "null"
                    : r1.text.substring(0, Math.min(200, r1.text.length())));
        } catch (Exception e) {
            sb.append("accInfoErr=").append(e);
        }
        try {
            Map<String, String> p = base();
            String svid = ensureVerifyFp();
            p.put("verifyFp", svid);
            p.put("fp", svid);
            p.put("msToken", cookies.get("msToken"));
            Http.Resp r2 = getAuthedJson(HOST, "/aweme/v1/web/user/profile/self/", p, HOST + "/");
            sb.append(" | authedSelf=").append(r2.status)
                    .append(" tg=").append(r2.header("Bd-Ticket-Guard-Result"))
                    .append('/').append(r2.header("Bd-Ticket-Guard-Key-Sign-Result"))
                    .append(':').append(r2.text == null ? "null"
                            : r2.text.substring(0, Math.min(200, r2.text.length())));
        } catch (Exception e) {
            sb.append(" | authedSelfErr=").append(e);
        }
        return sb.toString();
    }

    public static String rand(int n) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

    public static String verifyFp() {
        String c = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("verify_");
        int[] lens = {8, 8, 4, 4, 11};
        for (int k = 0; k < lens.length; k++) {
            if (k > 0) sb.append('_');
            for (int i = 0; i < lens[k]; i++) sb.append(c.charAt(r.nextInt(c.length())));
        }
        return sb.toString();
    }
}
