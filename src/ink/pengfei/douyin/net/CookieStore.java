package ink.pengfei.douyin.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Persistent cookie jar (name=value only) with auth-cookie & host protection. */
public final class CookieStore {
    private static final Set<String> AUTH_COOKIES = new HashSet<>(Arrays.asList(
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard",
            "uid_tt", "uid_tt_ss", "sid_ucp_v1", "ssid_ucp_v1",
            "x_tt_token", "d_ticket", "passport_assist_user"
    ));

    private final SharedPreferences prefs;
    private final Map<String, String> map = new LinkedHashMap<>();

    public CookieStore(Context ctx) {
        prefs = ctx.getSharedPreferences("cookies", Context.MODE_PRIVATE);
        String raw = prefs.getString("jar", "");
        if (!raw.isEmpty()) {
            for (String part : raw.split(";;")) {
                int i = part.indexOf('=');
                if (i > 0) map.put(part.substring(0, i), part.substring(i + 1));
            }
        }
    }

    public synchronized String get(String name) {
        return map.get(name);
    }

    public synchronized void put(String name, String value) {
        if (name == null || name.isEmpty()) return;
        if (value == null || value.isEmpty()) map.remove(name);
        else map.put(name, value);
        save();
    }

    public synchronized boolean has(String name) {
        String v = map.get(name);
        return v != null && !v.isEmpty();
    }

    public synchronized String header() {
        return formatJar();
    }

    public synchronized String headerFor(String urlOrHost) {
        if (!isDouyinHost(extractHost(urlOrHost))) return "";
        return formatJar();
    }

    private String formatJar() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public synchronized void mergeSetCookie(String setCookieHeader) {
        mergeSetCookie(null, setCookieHeader);
    }

    public synchronized void mergeSetCookie(String urlOrHost, String setCookieHeader) {
        if (setCookieHeader == null || setCookieHeader.isEmpty()) return;
        String host = extractHost(urlOrHost);
        boolean trustedDouyin = host.isEmpty() || isDouyinHost(host);
        boolean changed = false;
        for (String piece : setCookieHeader.split("\\n")) {
            String[] segs = piece.split(";");
            if (segs.length == 0) continue;
            String first = segs[0].trim();
            int i = first.indexOf('=');
            if (i <= 0) continue;
            String name = first.substring(0, i).trim();
            String value = first.substring(i + 1).trim();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (lowerName.contains("expires")) continue;
            if (!trustedDouyin && !"ttwid".equals(name)) continue;

            boolean expired = false;
            String domain = "";
            for (int s = 1; s < segs.length; s++) {
                String attr = segs[s].trim();
                int eq = attr.indexOf('=');
                String ak = (eq > 0 ? attr.substring(0, eq) : attr).trim().toLowerCase(Locale.ROOT);
                String av = eq > 0 ? attr.substring(eq + 1).trim() : "";
                if ("max-age".equals(ak)) {
                    try {
                        if (Long.parseLong(av) <= 0) expired = true;
                    } catch (NumberFormatException ignored) {}
                } else if ("expires".equals(ak) && (av.contains("1970") || av.contains("1969"))) {
                    expired = true;
                } else if ("domain".equals(ak)) {
                    domain = av.toLowerCase(Locale.ROOT);
                }
            }
            if (!domain.isEmpty() && !domain.endsWith("douyin.com")
                    && !domain.endsWith("iesdouyin.com") && !"ttwid".equals(name)) {
                continue;
            }

            boolean isAuth = AUTH_COOKIES.contains(name);
            boolean emptyOrDeleted = value.isEmpty() || "deleted".equalsIgnoreCase(value) || "\"\"".equals(value);
            if (isAuth) {
                // Never wipe persisted auth cookies on transient empty/expired Set-Cookie headers.
                if (expired || emptyOrDeleted) continue;
                map.put(name, value);
                changed = true;
            } else if (expired || value.isEmpty()) {
                if (map.remove(name) != null) changed = true;
            } else {
                map.put(name, value);
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized void mergeResponseHeaders(Map<String, java.util.List<String>> headers) {
        mergeResponseHeaders(null, headers);
    }

    public synchronized void mergeResponseHeaders(String urlOrHost, Map<String, java.util.List<String>> headers) {
        if (headers == null) return;
        for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String v : e.getValue()) mergeSetCookie(urlOrHost, v);
            }
        }
    }

    public synchronized void clear() {
        map.clear();
        prefs.edit()
                .remove("jar")
                .remove("ticket_guard")
                .remove("invalid_session")
                .remove("web_bootstrap_ms")
                .remove("ttwid_ms")
                .remove("ms_captured")
                .apply();
    }

    private static String extractHost(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isEmpty()) return "";
        if (!urlOrHost.contains("://")) return urlOrHost.toLowerCase(Locale.ROOT);
        try {
            String h = new URL(urlOrHost).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isDouyinHost(String host) {
        return host.equals("douyin.com") || host.endsWith(".douyin.com")
                || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com");
    }

    synchronized long lastWebBootstrapMs() {
        return prefs.getLong("web_bootstrap_ms", 0L);
    }

    synchronized void markWebBootstrap() {
        prefs.edit().putLong("web_bootstrap_ms", System.currentTimeMillis()).apply();
    }

    synchronized long lastTtwidMs() {
        return prefs.getLong("ttwid_ms", 0L);
    }

    synchronized void markTtwid() {
        prefs.edit().putLong("ttwid_ms", System.currentTimeMillis()).apply();
    }

    synchronized boolean isMsCaptured() {
        return prefs.getBoolean("ms_captured", false);
    }

    synchronized void setMsCaptured(boolean captured) {
        prefs.edit().putBoolean("ms_captured", captured).apply();
    }

    synchronized String readTicket() {
        return prefs.getString("ticket_guard", null);
    }

    synchronized void saveTicket(String value) {
        if (value == null || value.isEmpty()) prefs.edit().remove("ticket_guard").apply();
        else prefs.edit().putString("ticket_guard", value).apply();
    }

    synchronized String readEcKey() {
        return prefs.getString("ec_key", null);
    }

    synchronized void saveEcKey(String value) {
        if (value == null || value.isEmpty()) prefs.edit().remove("ec_key").apply();
        else prefs.edit().putString("ec_key", value).apply();
    }

    synchronized String readInvalidSession() {
        return prefs.getString("invalid_session", null);
    }

    synchronized void saveInvalidSession(String value) {
        if (value == null || value.isEmpty()) prefs.edit().remove("invalid_session").apply();
        else prefs.edit().putString("invalid_session", value).apply();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(";;");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString("jar", sb.toString()).apply();
    }
}
