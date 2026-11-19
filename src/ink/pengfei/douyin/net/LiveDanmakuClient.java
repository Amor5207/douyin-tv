package ink.pengfei.douyin.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Douyin live chat via HTTP polling. Each request carries the previous response cursor.
 */
public final class LiveDanmakuClient {
    public interface Listener {
        void onChat(String nick, String content);
    }

    private static final String IM_FETCH = "https://live.douyin.com/webcast/im/fetch/";
    private static final String UA = Http.UA;
    private static final String UID = randomUid();

    private final String roomId;
    private final String webRid;
    private final CookieStore cookies;
    private final Listener listener;
    private volatile boolean running;
    private volatile Thread thread;
    private String cursor = "";
    private String internalExt = "";
    private int pollAttempts;
    private final Map<String, String> route = new LinkedHashMap<>();
    private final java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();

    public LiveDanmakuClient(String roomId, String webRid, CookieStore cookies, Listener listener) {
        this.roomId = roomId == null ? "" : roomId;
        this.webRid = webRid == null ? "" : webRid;
        this.cookies = cookies;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        if (roomId.isEmpty()) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "live-danmaku");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void loop() {
        int errors = 0;
        while (active()) {
            long delay;
            try {
                delay = pollOnce();
            } catch (IOException | RuntimeException e) {
                android.util.Log.d("dytv-live", "Chat poll failed: " + e.getClass().getSimpleName());
                delay = -1;
            }
            if (!active()) return;
            if (delay > 0) {
                errors = 0;
                sleep(delay);
            } else {
                errors++;
                if (errors == 3) {
                    cursor = "";
                    internalExt = "";
                    route.clear();
                }
                sleep(Math.min(8000, 500L * (1L << Math.min(4, errors))));
            }
        }
    }

    private boolean active() {
        return running && Thread.currentThread() == thread;
    }

    private long pollOnce() throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("resp_content_type", "protobuf");
        p.put("aid", "6383");
        p.put("app_name", "douyin_web");
        p.put("live_id", "1");
        p.put("device_platform", "web");
        p.put("language", "zh-CN");
        p.put("enter_from", "web_live");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "1920");
        p.put("screen_height", "1080");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Mozilla");
        p.put("browser_version", UA.substring(UA.indexOf("Mozilla")));
        p.put("browser_online", "true");
        p.put("tz_name", "Asia/Shanghai");
        p.put("host", "https://live.douyin.com");
        p.put("version_code", "180800");
        p.put("webcast_sdk_version", "1.0.15");
        p.put("update_version_code", "1.0.15");
        p.put("compress", "gzip");
        p.put("did_rule", "3");
        p.put("endpoint", "live_pc");
        p.put("support_wrds", "1");
        p.put("im_path", "/webcast/im/fetch/");
        p.put("identity", "audience");
        p.put("need_persist_msg_count", cursor.isEmpty() ? "15" : "0");
        p.put("insert_task_id", "");
        p.put("live_reason", "");
        p.put("room_id", roomId);
        p.put("web_rid", webRid);
        p.put("user_unique_id", UID);
        p.put("heartbeatDuration", "0");
        boolean continuation = !cursor.isEmpty();
        if (continuation) {
            p.put("cursor", cursor);
        }
        if (!internalExt.isEmpty()) p.put("internal_ext", internalExt);
        for (Map.Entry<String, String> option : route.entrySet()) {
            if (!p.containsKey(option.getKey())) p.put(option.getKey(), option.getValue());
        }

        Map<String, String> h = new HashMap<>();
        h.put("Referer", "https://live.douyin.com/" + webRid);
        h.put("Origin", "https://live.douyin.com");
        h.put("Accept", "application/x-protobuf, application/octet-stream, */*");
        boolean trace = ++pollAttempts <= 5;
        Http.Resp r = Http.getBytes(IM_FETCH + "?" + Http.query(p), h, cookies);
        if (!active()) return -1;
        if (r.status != 200 || r.bytes == null) {
            android.util.Log.d("dytv-live", "Chat poll HTTP " + r.status + ", continuation=" + continuation);
            return -1;
        }
        byte[] buf = maybeInflate(r.bytes);
        if (buf.length == 0) {
            if (trace) android.util.Log.d("dytv-live", "Empty chat poll, continuation=" + continuation);
            return 2000;
        }

        Map<Integer, List<Object>> rf = fields(buf);
        if (rf.isEmpty()) return -1;
        String nextCursor = str(rf, 2);
        if (!nextCursor.isEmpty()) cursor = nextCursor;
        String ext = str(rf, 5);
        if (!ext.isEmpty()) internalExt = ext;
        List<Object> routing = rf.get(7);
        if (routing != null) {
            route.clear();
            for (Object option : routing) {
                if (!(option instanceof byte[])) continue;
                Map<Integer, List<Object>> kv = fields((byte[]) option);
                String key = str(kv, 1);
                if (key.matches("[a-zA-Z0-9_]{1,40}")) route.put(key, str(kv, 2));
            }
        }
        long nextPoll = num(rf, 3);
        long delay = nextPoll > 0 ? Math.max(800, Math.min(3000, nextPoll)) : 1500;

        List<Object> list = rf.get(1);
        if (trace) {
            android.util.Log.d("dytv-live", "Chat poll: continuation=" + continuation
                    + ", cursor=" + !nextCursor.isEmpty() + ", extension=" + !ext.isEmpty()
                    + ", suggestedType=" + num(rf, 6) + ", messages=" + (list == null ? 0 : list.size()));
        }
        if (list == null) return delay;
        int delivered = 0;
        for (Object o : list) {
            if (!active() || !(o instanceof byte[])) continue;
            Map<Integer, List<Object>> mf = fields((byte[]) o);
            String method = str(mf, 1);
            boolean isChat = "WebcastChatMessage".equals(method);
            boolean isScreen = "WebcastScreenChatMessage".equals(method);
            if (!isChat && !isScreen) continue;
            byte[] payload = raw(mf, 2);
            if (payload == null || payload.length == 0) payload = raw(mf, 3);
            if (payload == null || payload.length == 0) continue;
            Map<Integer, List<Object>> cf = fields(maybeInflate(payload));
            String content = str(cf, 3);
            if (content.isEmpty() && isScreen) content = str(cf, 4);
            if (content.isEmpty()) continue;
            long id = num(mf, 3);
            if (id != 0 && !seen.add(id)) continue;
            if (seen.size() > 300) seen.remove(seen.iterator().next());
            Map<Integer, List<Object>> uf = fields(raw(cf, 2));
            String nick = str(uf, 3);
            if (nick.isEmpty()) nick = str(uf, 4);
            listener.onChat(nick, content);
            delivered++;
        }
        if (delivered > 0) android.util.Log.d("dytv-live", "Chat batch: " + delivered
                + ", continuation=" + continuation);
        return delay;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String randomUid() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder(19);
        sb.append(7);
        for (int i = 1; i < 19; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private static byte[] maybeInflate(byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        try {
            return readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
        } catch (Exception ignored) {
        }
        try {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(data), new Inflater()));
        } catch (Exception ignored) {
        }
        return data;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    private static Map<Integer, List<Object>> fields(byte[] data) {
        Map<Integer, List<Object>> out = new HashMap<>();
        if (data == null) return out;
        int i = 0;
        try {
            while (i < data.length) {
                long tag = 0;
                int shift = 0;
                while (true) {
                    int c = data[i++] & 0xff;
                    tag |= (long) (c & 0x7f) << shift;
                    if ((c & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 63) return out;
                }
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long v = 0;
                    shift = 0;
                    while (true) {
                        int c = data[i++] & 0xff;
                        v |= (long) (c & 0x7f) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                        if (shift > 63) return out;
                    }
                    add(out, field, v);
                } else if (wire == 2) {
                    int len = 0;
                    shift = 0;
                    while (true) {
                        int c = data[i++] & 0xff;
                        len |= (c & 0x7f) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                        if (shift > 28) return out;
                    }
                    if (len < 0 || len > data.length - i) return out;
                    byte[] v = new byte[len];
                    System.arraycopy(data, i, v, 0, len);
                    i += len;
                    add(out, field, v);
                } else if (wire == 1) {
                    i += 8;
                } else if (wire == 5) {
                    i += 4;
                } else {
                    return out;
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
        }
        return out;
    }

    private static void add(Map<Integer, List<Object>> map, int field, Object v) {
        List<Object> list = map.get(field);
        if (list == null) {
            list = new ArrayList<>();
            map.put(field, list);
        }
        list.add(v);
    }

    private static byte[] raw(Map<Integer, List<Object>> map, int field) {
        List<Object> list = map.get(field);
        if (list == null) return null;
        for (Object o : list) if (o instanceof byte[]) return (byte[]) o;
        return null;
    }

    private static String str(Map<Integer, List<Object>> map, int field) {
        byte[] b = raw(map, field);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static long num(Map<Integer, List<Object>> map, int field) {
        List<Object> values = map.get(field);
        if (values != null) for (Object v : values) if (v instanceof Long) return (Long) v;
        return 0L;
    }
}
