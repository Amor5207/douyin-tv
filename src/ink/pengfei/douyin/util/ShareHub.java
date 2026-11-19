package ink.pengfei.douyin.util;

import ink.pengfei.douyin.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tiny LAN relay: phone opens the TV page, pastes a share link, TV receives it. */
public final class ShareHub {
    private static ShareHub instance;
    private final Store store;
    private volatile int port = 8899;
    private volatile boolean running;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));

    private ShareHub(Store store) {
        this.store = store;
    }

    public static synchronized void start(Store store) {
        if (instance != null) return;
        instance = new ShareHub(store);
        instance.boot();
    }

    public static ShareHub get() {
        return instance;
    }

    public int port() {
        return port;
    }

    public String lanUrl() {
        return "http://" + lanIp() + ":" + port + "/";
    }

    public String shareUrl(String code) {
        return "http://" + lanIp() + ":" + port + "/s?code=" + Http.enc(code);
    }

    private void boot() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int p = 8899; p <= 8909; p++) {
                    try {
                        ServerSocket ss = new ServerSocket(p);
                        port = p;
                        running = true;
                        acceptLoop(ss);
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "share-hub");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop(final ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                try {
                    clients.execute(() -> handle(s));
                } catch (RejectedExecutionException e) {
                    s.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int trail = 0, c, count = 0;
            while ((c = in.read()) >= 0 && count++ < 16384) {
                head.write(c);
                trail = (trail << 8) | (c & 0xff);
                if (trail == 0x0D0A0D0A || (trail & 0xFFFF) == 0x0A0A) break;
            }
            String header = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\r\n");
            String[] first = lines.length > 0 ? lines[0].split(" ") : new String[0];
            if (first.length < 2) return;
            String method = first[0];
            String target = first[1];
            int contentLength = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[Math.min(contentLength, 32768)];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            String resp;
            String type = "text/html; charset=utf-8";
            if (path.equals("/") || path.equals("/index.html")) {
                resp = pageHome();
            } else if (path.equals("/send")) {
                String text = param(query, "text");
                if (text.isEmpty()) text = param(body, "text");
                String from = param(query, "from");
                if (from.isEmpty()) from = param(body, "from");
                String id = awemeIdFrom(text);
                if (id.isEmpty()) {
                    resp = pageMsg("没有识别到视频", "请在手机上粘贴抖音分享链接后重试。", false);
                } else {
                    if (from.isEmpty()) from = "手机朋友";
                    ink.pengfei.douyin.model.Friend friend = new ink.pengfei.douyin.model.Friend();
                    friend.id = "lan:" + from;
                    friend.name = from;
                    friend.source = "lan";
                    friend.lastActive = System.currentTimeMillis();
                    store.upsertFriend(friend);
                    Store.ShareEntry e = new Store.ShareEntry();
                    e.awemeId = id;
                    e.fromId = friend.id;
                    e.fromName = friend.name;
                    e.time = System.currentTimeMillis();
                    store.addShare(e);
                    resp = pageMsg("已发送到电视", "在电视「收到的视频」中播放。", true);
                }
            } else if (path.equals("/api/inbox")) {
                type = "application/json; charset=utf-8";
                resp = inboxJson();
            } else if (path.equals("/favicon.ico")) {
                s.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                s.close();
                return;
            } else {
                resp = pageMsg("页面不存在", "", false);
            }
            byte[] data = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            String h = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
                    + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private String inboxJson() {
        List<Store.ShareEntry> list = store.shares();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(list.get(i).awemeId)
                    .append("\",\"from\":\"").append(esc(list.get(i).fromName))
                    .append("\",\"time\":").append(list.get(i).time).append('}');
        }
        return sb.append(']').toString();
    }

    private static String param(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) {
                try {
                    return URLDecoder.decode(part.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return part.substring(i + 1);
                }
            }
        }
        return "";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String shell(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>抖音TV 手机中转</title><style>"
                + "body{margin:0;font-family:-apple-system,'PingFang SC',sans-serif;background:#0e0e12;color:#f4f4f7;padding:24px}"
                + ".card{max-width:520px;margin:0 auto;background:#17171e;border-radius:18px;padding:22px}"
                + "h1{font-size:22px;margin:0 0 6px}.red{color:#fe2c55}p{color:#a9a9b3;font-size:14px;line-height:1.6}"
                + "input,textarea{width:100%;box-sizing:border-box;background:#0e0e12;border:1px solid #2a2a33;color:#fff;"
                + "border-radius:12px;padding:12px;font-size:15px;margin:10px 0}"
                + "button{width:100%;background:#fe2c55;border:0;color:#fff;border-radius:12px;padding:13px;font-size:16px;font-weight:600}"
                + "a{color:#25f4ee;word-break:break-all}"
                + ".code{font-size:26px;letter-spacing:3px;font-weight:700;margin:10px 0;color:#25f4ee}"
                + "</style></head><body><div class=\"card\">" + body + "</div></body></html>";
    }

    private String pageHome() {
        return shell("<h1>抖音<span class=\"red\">TV</span> 手机中转</h1>"
                + "<p>粘贴抖音分享链接，然后在电视「收到的视频」中播放。</p>"
                + "<form action=\"/send\" method=\"get\">"
                + "<input name=\"from\" placeholder=\"你的名字（电视上显示）\">"
                + "<input name=\"text\" placeholder=\"粘贴 v.douyin.com 链接 / 视频链接\">"
                + "<button type=\"submit\">发送到电视</button></form>");
    }

    private String pageMsg(String title, String detail, boolean ok) {
        return shell("<h1>" + (ok ? "<span class=\"red\">✓</span> " : "") + esc(title) + "</h1>"
                + "<p>" + esc(detail) + "</p><p><a href=\"/\">返回</a></p>");
    }

    private static String awemeIdFrom(String text) {
        if (text == null) return "";
        if (text.trim().matches("\\d{6,22}")) return text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s<>，。！？]+").matcher(text);
        while (m.find()) {
            try {
                URL url = new URL(m.group());
                for (int i = 0; i < 5 && isShareHost(url); i++) {
                    String id = idIn(url.getFile());
                    if (!id.isEmpty()) return id;
                    if (!"v.douyin.com".equalsIgnoreCase(url.getHost())) break;
                    Http.Resp r = Http.getNoRedirect(url.toString(), null, null);
                    String location = r.header("Location");
                    if (location == null || r.status < 300 || r.status >= 400) break;
                    url = new URL(url, location);
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static boolean isShareHost(URL url) {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) return false;
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("v.douyin.com") || host.equals("www.douyin.com")
                || host.equals("douyin.com") || host.equals("www.iesdouyin.com");
    }

    private static String idIn(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:/video/|[?&]modal_id=)(\\d{6,22})(?:[/?&#]|$)")
                .matcher(path);
        return m.find() ? m.group(1) : "";
    }

    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
