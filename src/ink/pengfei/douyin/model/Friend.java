package ink.pengfei.douyin.model;

import org.json.JSONObject;

public class Friend {
    public String id = "";
    public String name = "";
    public String avatar = "";
    public String secUid = "";
    public String uniqueId = "";
    public String source = "douyin";
    public long lastActive;

    public static Friend from(JSONObject o) {
        if (o == null) return null;
        Friend f = new Friend();
        f.id = o.optString("id", "");
        f.name = o.optString("name", "");
        f.avatar = o.optString("avatar", "");
        f.secUid = o.optString("secUid", "");
        f.uniqueId = o.optString("uniqueId", "");
        f.source = o.optString("source", "douyin");
        f.lastActive = o.optLong("lastActive");
        if (f.id.isEmpty()) f.id = !f.secUid.isEmpty() ? f.secUid : f.name;
        return f.name.isEmpty() && f.id.isEmpty() ? null : f;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("avatar", avatar);
            o.put("secUid", secUid);
            o.put("uniqueId", uniqueId);
            o.put("source", source);
            o.put("lastActive", lastActive);
        } catch (Exception ignored) {}
        return o;
    }
}
