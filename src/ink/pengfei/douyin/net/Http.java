package ink.pengfei.douyin.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Http {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public static final class Resp {
        public int status;
        public String url;
        public String text;
        public byte[] bytes;
        public Map<String, List<String>> headers;
        public String header(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private Http() {}

    public static Resp request(String method, String url, Map<String, String> headers, String body,
                               CookieStore cookies, boolean follow, boolean binary) throws IOException {
        return request(method, url, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                cookies, follow, binary);
    }

    public static Resp request(String method, String url, Map<String, String> headers, byte[] body,
                               CookieStore cookies, boolean follow, boolean binary) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(follow);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (cookies != null) {
            String ck = cookies.headerFor(url);
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
        }
        Resp r = new Resp();
        try {
            r.status = c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        r.url = c.getURL().toString();
        r.headers = c.getHeaderFields();
        if (cookies != null) cookies.mergeResponseHeaders(r.url, r.headers);
        InputStream is;
        try {
            is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (IOException e) {
            is = c.getErrorStream();
        }
        byte[] data = readAll(is);
        r.bytes = data;
        if (!binary) r.text = new String(data, StandardCharsets.UTF_8);
        c.disconnect();
        return r;
    }

    public static Resp get(String url, Map<String, String> headers, CookieStore cookies) throws IOException {
        return request("GET", url, headers, (String) null, cookies, true, false);
    }

    public static Resp getNoRedirect(String url, Map<String, String> headers, CookieStore cookies) throws IOException {
        return request("GET", url, headers, (String) null, cookies, false, false);
    }

    public static Resp postForm(String url, Map<String, String> headers, String body, CookieStore cookies) throws IOException {
        return request("POST", url, headers, body, cookies, true, false);
    }

    public static Resp postFormNoRedirect(String url, Map<String, String> headers, String body,
                                          CookieStore cookies) throws IOException {
        return request("POST", url, headers, body, cookies, false, false);
    }

    public static Resp getBytes(String url, Map<String, String> headers, CookieStore cookies) throws IOException {
        return request("GET", url, headers, (String) null, cookies, true, true);
    }

    public static Resp postBytes(String url, Map<String, String> headers, byte[] body,
                                 CookieStore cookies) throws IOException {
        return request("POST", url, headers, body, cookies, true, true);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    /** JS encodeURIComponent equivalent for ASCII/UTF-8. */
    public static String enc(String s) {
        if (s == null) return "";
        try {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(bytes.length * 3);
            for (byte b : bytes) {
                int c = b & 0xff;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
                        || c == '*' || c == '\'' || c == '(' || c == ')') {
                    sb.append((char) c);
                } else {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    public static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    public static Map<String, String> map(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }
}
