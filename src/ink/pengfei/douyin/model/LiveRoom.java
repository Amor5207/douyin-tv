package ink.pengfei.douyin.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiveRoom {
    public String id = "";
    public String title = "";
    public String cover = "";
    public String nick = "";
    public String avatar = "";
    public String webRid = "";
    public String hlsUrl = "";
    public String flvUrl = "";
    public final Map<String, String> hlsMap = new LinkedHashMap<>();
    public final Map<String, String> flvMap = new LinkedHashMap<>();
    public long viewers;

    public static List<LiveRoom> list(JSONArray data) {
        List<LiveRoom> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject wrap = data.optJSONObject(i);
            if (wrap == null) continue;
            JSONObject o = wrap.optJSONObject("data");
            if (o == null) o = wrap;
            LiveRoom r = from(o);
            if (r != null && !r.id.isEmpty() && (!r.hlsUrl.isEmpty() || !r.flvUrl.isEmpty())) out.add(r);
        }
        return out;
    }

    public static LiveRoom from(JSONObject o) {
        if (o == null) return null;
        LiveRoom r = new LiveRoom();
        r.id = Aweme.optId(o, "id_str");
        if (r.id.isEmpty()) r.id = Aweme.optId(o, "id");
        r.title = o.optString("title", "");
        r.cover = Aweme.firstUrl(o.optJSONObject("cover"));
        if (r.cover.isEmpty()) r.cover = Aweme.firstUrl(o.optJSONObject("dynamic_cover"));
        JSONObject owner = o.optJSONObject("owner");
        if (owner != null) {
            r.nick = owner.optString("nickname", "");
            r.webRid = owner.optString("web_rid", "");
            r.avatar = Aweme.firstUrl(owner.optJSONObject("avatar_thumb"));
        }
        JSONObject stats = o.optJSONObject("stats");
        if (stats != null) r.viewers = stats.optLong("user_count");
        JSONObject su = o.optJSONObject("stream_url");
        if (su != null) {
            fillMap(su.optJSONObject("hls_pull_url_map"), r.hlsMap);
            r.hlsUrl = pick(su.optJSONObject("hls_pull_url_map"));
            if (r.hlsUrl.isEmpty()) r.hlsUrl = su.optString("hls_pull_url", "");
            fillMap(su.optJSONObject("flv_pull_url"), r.flvMap);
            r.flvUrl = pick(su.optJSONObject("flv_pull_url"));
            if (r.flvUrl.isEmpty()) r.flvUrl = su.optString("rtmp_pull_url", "");
        }
        return r;
    }

    private static void fillMap(JSONObject map, Map<String, String> out) {
        if (map == null) return;
        JSONArray names = map.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i, "");
            String v = map.optString(k, "");
            if (!k.isEmpty() && v.startsWith("http")) out.put(k, v);
        }
    }

    public String[] urlList(String qualityKey, boolean preferFlv) {
        List<String> flvs = new ArrayList<>();
        List<String> hlss = new ArrayList<>();
        String qHls = qualityKey != null && !"auto".equals(qualityKey) ? hlsMap.get(qualityKey) : null;
        String qFlv = qualityKey != null && !"auto".equals(qualityKey) ? flvMap.get(qualityKey) : null;
        addUnique(flvs, qFlv);
        addUnique(flvs, flvMap.get("HD1"));
        addUnique(flvs, flvUrl);
        for (String u : flvMap.values()) addUnique(flvs, u);

        addUnique(hlss, qHls);
        addUnique(hlss, hlsMap.get("HD1"));
        addUnique(hlss, hlsUrl);
        for (String u : hlsMap.values()) addUnique(hlss, u);

        // 交替排列首选协议与备用协议（FLV/HLS），避免某条协议域名慢或失效时连续重试多条同协议浪费十几秒
        List<String> primary = preferFlv ? flvs : hlss;
        List<String> secondary = preferFlv ? hlss : flvs;
        List<String> out = new ArrayList<>();
        int max = Math.max(primary.size(), secondary.size());
        for (int i = 0; i < max && out.size() < 4; i++) {
            if (i < primary.size()) addUnique(out, primary.get(i));
            if (out.size() < 4 && i < secondary.size()) addUnique(out, secondary.get(i));
        }
        return out.toArray(new String[0]);
    }

    private static void addUnique(List<String> out, String u) {
        if (u != null && u.startsWith("http") && !out.contains(u)) out.add(u);
    }

    private static String pick(JSONObject map) {
        if (map == null) return "";
        String[] prefer = {"HD1", "FULL_HD1", "SD1", "SD2", "FULL_HD"};
        for (String k : prefer) {
            String v = map.optString(k, "");
            if (v.startsWith("http")) return v;
        }
        JSONArray names = map.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String v = map.optString(names.optString(i), "");
                if (v.startsWith("http")) return v;
            }
        }
        return "";
    }
}
