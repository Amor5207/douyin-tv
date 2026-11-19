package ink.pengfei.douyin.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Aweme {
    public String id = "";
    public String desc = "";
    public long createTime;
    public long durationMs;
    public int videoWidth;
    public int videoHeight;
    public String authorSecUid = "";
    public String authorName = "";
    public String authorUid = "";
    public String authorAvatar = "";
    public String cover = "";
    public long diggCount;
    public long commentCount;
    public long shareCount;
    public int userDigged;
    public boolean images;
    public boolean isAd;
    public String videoUri = "";
    public final List<String> playUrls = new ArrayList<>();
    private final List<Rendition> compatible = new ArrayList<>();

    private static final class Rendition {
        final int pixels;
        final int bitrate;
        final Set<String> urls = new LinkedHashSet<>();

        Rendition(JSONObject address, int bitrate) {
            pixels = Math.min(address.optInt("width"), address.optInt("height"));
            this.bitrate = bitrate;
            addUrls(urls, address);
        }
    }

    public List<String> playCandidates(String ratio) {
        Set<String> out = new LinkedHashSet<>();
        boolean autoQuality = ratio == null || "auto".equals(ratio);
        String resolvedRatio = autoQuality ? "1080p" : ratio;
        final int target = "1080p".equals(resolvedRatio) ? 1080
                : "540p".equals(resolvedRatio) ? 540 : 720;
        List<Rendition> sorted = new ArrayList<>(compatible);
        java.util.Collections.sort(sorted, (a, b) -> {
            int da = Math.abs(a.pixels - target);
            int db = Math.abs(b.pixels - target);
            if (da != db) return Integer.compare(da, db);
            // 相同清晰度优先选更高码率，避免模糊
            return Integer.compare(b.bitrate, a.bitrate);
        });
        for (Rendition r : sorted) {
            for (String url : r.urls) {
                if (url != null && !url.contains("/aweme/v1/play/") && out.size() < 4) out.add(url);
            }
        }
        for (Rendition r : sorted) {
            for (String url : r.urls) {
                if (url != null && out.size() < 5) out.add(url);
            }
        }
        for (String url : playUrls) {
            if (url != null && out.size() < 6) out.add(url);
        }
        String play = videoUri.isEmpty() ? "" : "https://www.douyin.com/aweme/v1/play/?video_id="
                + videoUri + "&ratio=" + resolvedRatio + "&line=0";
        if (!play.isEmpty() && out.size() < 6) out.add(play);
        return new ArrayList<>(out);
    }

    public String shareUrl() {
        return "https://www.douyin.com/video/" + id;
    }

    public boolean playable() {
        return !videoUri.isEmpty() || !playUrls.isEmpty();
    }

    public void copyStreamsFrom(Aweme fresh) {
        if (fresh == null) return;
        playUrls.clear();
        playUrls.addAll(fresh.playUrls);
        compatible.clear();
        compatible.addAll(fresh.compatible);
        if (!fresh.videoUri.isEmpty()) videoUri = fresh.videoUri;
        if (fresh.videoWidth > 0 && fresh.videoHeight > 0) {
            videoWidth = fresh.videoWidth;
            videoHeight = fresh.videoHeight;
        }
        if (fresh.durationMs > 0) durationMs = fresh.durationMs;
        if (!fresh.cover.isEmpty()) cover = fresh.cover;
    }

    public static Aweme from(JSONObject o) {
        if (o == null) return null;
        Aweme a = new Aweme();
        a.id = optId(o, "aweme_id");
        a.desc = o.optString("desc", "");
        a.createTime = o.optLong("create_time");
        a.userDigged = o.optBoolean("user_digged", false) || o.optInt("user_digged", 0) == 1 ? 1 : 0;
        a.isAd = looksLikeAd(o);
        JSONObject stats = o.optJSONObject("statistics");
        if (stats != null) {
            a.diggCount = stats.optLong("digg_count");
            a.commentCount = stats.optLong("comment_count");
            a.shareCount = stats.optLong("share_count");
        }
        JSONObject author = o.optJSONObject("author");
        if (author != null) {
            a.authorUid = optId(author, "uid");
            a.authorSecUid = author.optString("sec_uid", "");
            a.authorName = author.optString("nickname", "");
            a.authorAvatar = firstUrl(author.optJSONObject("avatar_thumb"));
            if (a.authorAvatar.isEmpty()) a.authorAvatar = firstUrl(author.optJSONObject("avatar_medium"));
        }
        JSONObject video = o.optJSONObject("video");
        if (video != null) {
            a.durationMs = video.optLong("duration");
            a.videoWidth = video.optInt("width");
            a.videoHeight = video.optInt("height");
            a.cover = firstUrl(video.optJSONObject("cover"));
            if (a.cover.isEmpty()) a.cover = firstUrl(video.optJSONObject("origin_cover"));
            if (a.cover.isEmpty()) a.cover = firstUrl(video.optJSONObject("dynamic_cover"));
            Set<String> urls = new LinkedHashSet<>();
            JSONObject play = video.optJSONObject("play_addr_h264");
            if (play == null) play = video.optJSONObject("play_addr");
            if (play != null) {
                a.videoUri = play.optString("uri", "");
                if (a.videoUri.isEmpty()) a.videoUri = play.optString("url_key", "");
                if (a.videoWidth <= 0) a.videoWidth = play.optInt("width");
                if (a.videoHeight <= 0) a.videoHeight = play.optInt("height");
            }
            JSONObject avc = video.optJSONObject("play_addr_h264");
            if (avc != null) {
                a.addCompatible(avc, 0);
                addUrls(urls, avc);
            }
            addUrls(urls, video.optJSONObject("play_addr"));
            JSONArray rates = video.optJSONArray("bit_rate");
            if (rates != null) {
                for (int i = 0; i < rates.length(); i++) {
                    JSONObject rate = rates.optJSONObject(i);
                    if (rate == null) continue;
                    String fmt = rate.optString("format", "");
                    if ("dash".equalsIgnoreCase(fmt)) continue;
                    if (rate.optInt("is_h265") != 0 || rate.optInt("is_bytevc1") != 0) continue;
                    JSONObject address = rate.optJSONObject("play_addr");
                    if (address != null) {
                        a.addCompatible(address, rate.optInt("bit_rate"));
                        addUrls(urls, address);
                    }
                }
            }
            a.playUrls.addAll(urls);
        }
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null && imgs.length() > 0) a.images = true;
        return a;
    }

    private void addCompatible(JSONObject address, int bitrate) {
        Rendition rendition = new Rendition(address, bitrate);
        if (rendition.pixels > 0 && !rendition.urls.isEmpty()) compatible.add(rendition);
    }

    private static boolean looksLikeAd(JSONObject o) {
        if (o.optBoolean("is_ad", false) || o.optBoolean("is_ads", false)) return true;
        if (o.optInt("is_ad", 0) == 1 || o.optInt("is_ads", 0) == 1) return true;
        if (o.optJSONObject("raw_ad_data") != null || o.optJSONObject("adv_promotion") != null
                || o.optJSONObject("ad_info") != null) return true;
        if (o.optLong("ad_id", 0) > 0) return true;
        String src = o.optString("ad_aweme_source", "");
        return !src.isEmpty() && !"0".equals(src);
    }

    private static boolean isCleanStreamUrl(String u) {
        if (u == null || !u.startsWith("http")) return false;
        return !u.contains("playwm") && !u.contains("watermark=1");
    }

    private static void addUrls(Set<String> out, JSONObject addr) {
        if (addr == null) return;
        JSONArray list = addr.optJSONArray("url_list");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            String u = list.optString(i, "");
            if (isCleanStreamUrl(u)) out.add(u);
        }
    }

    public static String firstUrl(JSONObject img) {
        if (img == null) return "";
        JSONArray list = img.optJSONArray("url_list");
        if (list == null) return "";
        for (int i = 0; i < list.length(); i++) {
            String u = list.optString(i, "");
            if (u.startsWith("http")) return u;
        }
        return "";
    }

    public static String optId(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return String.format(java.util.Locale.US, "%.0f", d);
        }
        return String.valueOf(v);
    }

    public static List<Aweme> list(JSONArray arr) {
        List<Aweme> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Aweme a = from(arr.optJSONObject(i));
            if (a != null && !a.id.isEmpty() && !a.isAd) out.add(a);
        }
        return out;
    }
}
