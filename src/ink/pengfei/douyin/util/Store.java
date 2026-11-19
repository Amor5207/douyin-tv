package ink.pengfei.douyin.util;

import android.content.Context;
import android.content.SharedPreferences;

import ink.pengfei.douyin.model.Aweme;
import ink.pengfei.douyin.model.Friend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Store {
    public static class ShareEntry {
        public String awemeId = "";
        public String title = "";
        public String fromId = "";
        public String fromName = "";
        public boolean outgoing;
        public long time;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", awemeId);
                o.put("title", title);
                o.put("fromId", fromId);
                o.put("fromName", fromName);
                o.put("outgoing", outgoing);
                o.put("time", time);
            } catch (Exception ignored) {}
            return o;
        }

        public static ShareEntry from(JSONObject o) {
            ShareEntry e = new ShareEntry();
            e.awemeId = o.optString("id", "");
            e.title = o.optString("title", "");
            e.fromId = o.optString("fromId", "");
            e.fromName = o.optString("fromName", "");
            e.outgoing = o.optBoolean("outgoing", false);
            e.time = o.optLong("time");
            return e;
        }
    }

    private final SharedPreferences prefs;

    public Store(Context c) {
        prefs = c.getSharedPreferences("data", Context.MODE_PRIVATE);
    }

    public void addRecent(Aweme a) {        if (a == null) return;
        try {
            JSONArray arr = recentJson();
            JSONArray out = new JSONArray();
            out.put(awemeJson(a));
            for (int i = 0; i < arr.length() && out.length() < 60; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !a.id.equals(o.optString("id"))) out.put(o);
            }
            prefs.edit().putString("recent", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    public List<Aweme> recent() {
        List<Aweme> out = new ArrayList<>();
        JSONArray arr = recentJson();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Aweme a = new Aweme();
            a.id = o.optString("id");
            a.desc = o.optString("desc");
            a.cover = o.optString("cover");
            a.authorName = o.optString("author");
            a.authorUid = o.optString("authorUid", "");
            a.authorSecUid = o.optString("secUid");
            a.authorAvatar = o.optString("authorAvatar", "");
            a.videoWidth = o.optInt("videoWidth");
            a.videoHeight = o.optInt("videoHeight");
            a.durationMs = o.optLong("duration");
            a.videoUri = o.optString("uri");
            a.userDigged = o.optInt("userDigged");
            a.diggCount = o.optLong("diggCount");
            JSONArray urls = o.optJSONArray("urls");
            if (urls != null) {
                for (int k = 0; k < urls.length(); k++) a.playUrls.add(urls.optString(k));
            }
            if (!a.id.isEmpty()) out.add(a);
        }
        return out;
    }

    private JSONArray recentJson() {
        try {
            return new JSONArray(prefs.getString("recent", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void clearRecent() {
        prefs.edit().remove("recent").apply();
    }

    private JSONObject awemeJson(Aweme a) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", a.id);
        o.put("desc", a.desc);
        o.put("cover", a.cover);
        o.put("author", a.authorName);
        o.put("authorUid", a.authorUid);
        o.put("secUid", a.authorSecUid);
        o.put("authorAvatar", a.authorAvatar);
        o.put("videoWidth", a.videoWidth);
        o.put("videoHeight", a.videoHeight);
        o.put("duration", a.durationMs);
        o.put("uri", a.videoUri);
        o.put("userDigged", a.userDigged);
        o.put("diggCount", a.diggCount);
        JSONArray urls = new JSONArray();
        for (int i = 0; i < Math.min(a.playUrls.size(), 3); i++) urls.put(a.playUrls.get(i));
        o.put("urls", urls);
        return o;
    }

    public synchronized void addShare(ShareEntry e) {
        if (e == null || e.awemeId.isEmpty()) return;
        try {
            JSONArray arr = sharesJson();
            JSONArray out = new JSONArray();
            JSONObject jo = e.toJson();
            out.put(jo);
            for (int i = 0; i < arr.length() && out.length() < 80; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                boolean same = e.awemeId.equals(o.optString("id"))
                        && e.fromId.equals(o.optString("fromId"))
                        && e.outgoing == o.optBoolean("outgoing", false);
                if (!same) out.put(o);
            }
            prefs.edit().putString("shares", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    public List<ShareEntry> sharesFor(String friendId) {
        List<ShareEntry> out = new ArrayList<>();
        for (ShareEntry e : shares()) {
            if (friendId != null && !friendId.isEmpty() && friendId.equals(e.fromId)) out.add(e);
        }
        return out;
    }

    public List<ShareEntry> shares() {
        List<ShareEntry> out = new ArrayList<>();
        JSONArray arr = sharesJson();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(ShareEntry.from(o));
        }
        return out;
    }

    public synchronized void renameShare(String awemeId, String title) {
        try {
            JSONArray arr = sharesJson();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && awemeId.equals(o.optString("id"))) o.put("title", title);
            }
            prefs.edit().putString("shares", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONArray sharesJson() {
        try {
            return new JSONArray(prefs.getString("shares", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public String lastAccountName() {
        return prefs.getString("account", "");
    }

    public void setAccountName(String name) {
        prefs.edit().putString("account", name).apply();
    }

    public String lastAccountAvatar() {
        return prefs.getString("account_avatar", "");
    }

    public void setAccountAvatar(String url) {
        prefs.edit().putString("account_avatar", url == null ? "" : url).apply();
    }

    public List<Friend> friends() {
        List<Friend> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                Friend f = Friend.from(arr.optJSONObject(i));
                if (f != null) out.add(f);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(out, new java.util.Comparator<Friend>() {
            @Override public int compare(Friend a, Friend b) {
                return Long.compare(b.lastActive, a.lastActive);
            }
        });
        return out;
    }

    public Friend friend(String id) {
        for (Friend f : friends()) {
            if (f.id.equals(id) || (!f.secUid.isEmpty() && f.secUid.equals(id))) return f;
        }
        return null;
    }

    public synchronized Friend upsertFriend(Friend f) {
        if (f == null || (f.id.isEmpty() && f.name.isEmpty())) return null;
        if (f.id.isEmpty()) f.id = f.name;
        try {
            JSONArray arr = new JSONArray(prefs.getString("friends", "[]"));
            JSONArray out = new JSONArray();
            Friend existing = null;
            for (int i = 0; i < arr.length(); i++) {
                Friend old = Friend.from(arr.optJSONObject(i));
                if (old != null && (old.id.equals(f.id) || (!f.secUid.isEmpty() && old.secUid.equals(f.secUid)))) {
                    existing = old;
                } else if (old != null) {
                    out.put(old.toJson());
                }
            }
            if (existing != null) {
                if (f.name.isEmpty()) f.name = existing.name;
                if (f.avatar.isEmpty()) f.avatar = existing.avatar;
                if (f.secUid.isEmpty()) f.secUid = existing.secUid;
                f.lastActive = Math.max(f.lastActive, existing.lastActive);
            }
            out.put(f.toJson());
            prefs.edit().putString("friends", out.toString()).apply();
            return f;
        } catch (Exception e) {
            return f;
        }
    }

    public String quality() {
        return prefs.getString("quality", "720p");
    }

    public void setQuality(String q) {
        prefs.edit().putString("quality", q).apply();
    }

    public boolean preload() {
        return prefs.getBoolean("preload", true);
    }

    public void setPreload(boolean value) {
        prefs.edit().putBoolean("preload", value).apply();
    }

    public boolean danmakuOn() {
        return prefs.getBoolean("dm_on", true);
    }    public void setDanmakuOn(boolean value) {
        prefs.edit().putBoolean("dm_on", value).apply();
    }

    public int danmakuSpeed() {
        return prefs.getInt("dm_speed", 1);
    }

    public void setDanmakuSpeed(int value) {
        prefs.edit().putInt("dm_speed", value).apply();
    }

    public int danmakuSize() {
        return prefs.getInt("dm_size", 1);
    }

    public void setDanmakuSize(int value) {
        prefs.edit().putInt("dm_size", value).apply();
    }

    public int danmakuAlpha() {
        return prefs.getInt("dm_alpha", 2);
    }

    public void setDanmakuAlpha(int value) {
        prefs.edit().putInt("dm_alpha", value).apply();
    }

    public int speedIndex() {
        return prefs.getInt("speed", 0);
    }

    public void setSpeedIndex(int value) {
        prefs.edit().putInt("speed", value).apply();
    }

    public boolean autoNext() {
        return prefs.getBoolean("auto_next", true);
    }

    public void setAutoNext(boolean value) {
        prefs.edit().putBoolean("auto_next", value).apply();
    }

    public String liveQuality() {
        return prefs.getString("live_quality", "auto");
    }

    public void setLiveQuality(String q) {
        prefs.edit().putString("live_quality", q).apply();
    }

    public int liveProtocol() {
        return prefs.getInt("live_protocol_v2", 1);
    }

    public void setLiveProtocol(int mode) {
        prefs.edit().putInt("live_protocol_v2", mode).apply();
    }

    // ---- 消息角标：记住看过的分享视频，未看过的数量显示在「消息」图标上

    private String seenKey() {
        String name = lastAccountName();
        return "msg_seen_" + (name == null || name.isEmpty() ? "anon" : name);
    }

    public java.util.Set<String> messagesSeen() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String raw = prefs.getString(seenKey(), "");
        if (raw == null || raw.isEmpty()) return out;
        for (String id : raw.split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    /** 把已看过的分享视频记入已读，最多保留最近 300 条。 */
    public void markMessagesSeen(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(messagesSeen());
        seen.addAll(ids);
        while (seen.size() > 300) {
            java.util.Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        StringBuilder sb = new StringBuilder();
        for (String id : seen) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs.edit().putString(seenKey(), sb.toString()).apply();
    }

    public int unreadMessages() {
        return prefs.getInt("msg_unread", 0);
    }

    public void setUnreadMessages(int value) {
        prefs.edit().putInt("msg_unread", Math.max(0, value)).apply();
    }
}
