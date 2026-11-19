package ink.pengfei.douyin.model;

import org.json.JSONObject;

public class UserProfile {
    public String secUid = "";
    public String nickname = "";
    public String avatar = "";
    public String signature = "";
    public long followers;
    public long following;
    public long awemes;
    public long likes;

    public static UserProfile from(JSONObject user) {
        if (user == null) return null;
        UserProfile p = new UserProfile();
        p.secUid = user.optString("sec_uid", "");
        p.nickname = user.optString("nickname", "");
        p.signature = user.optString("signature", "");
        p.avatar = Aweme.firstUrl(user.optJSONObject("avatar_thumb"));
        if (p.avatar.isEmpty()) p.avatar = Aweme.firstUrl(user.optJSONObject("avatar_medium"));
        if (p.avatar.isEmpty()) p.avatar = Aweme.firstUrl(user.optJSONObject("avatar_larger"));
        if (p.avatar.isEmpty()) p.avatar = Aweme.firstUrl(user.optJSONObject("avatar_168x168"));
        if (p.avatar.isEmpty()) p.avatar = Aweme.firstUrl(user.optJSONObject("avatar_300x300"));
        if (p.avatar.isEmpty()) {
            String direct = user.optString("avatar_url", "");
            if (direct.startsWith("http")) p.avatar = direct;
        }
        p.followers = user.optLong("follower_count");
        p.following = user.optLong("following_count");
        p.awemes = user.optLong("aweme_count");
        p.likes = user.optLong("total_favorited");
        return p;
    }
}
