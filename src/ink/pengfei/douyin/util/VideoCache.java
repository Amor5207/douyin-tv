package ink.pengfei.douyin.util;

import android.content.Context;
import android.net.Uri;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import ink.pengfei.douyin.App;
import ink.pengfei.douyin.model.Aweme;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Media3 SimpleCache 的分段视频缓存与双路并行预加载器。
 * 并行预取接下来两条视频开篇前 1.2 MB 数据，不启动任何后台解码器。
 */
public final class VideoCache {
    private static final long MAX_BYTES = 192L * 1024L * 1024L;
    private static final long PRELOAD_BYTES = 2048L * 1024L;

    private static SimpleCache instance;
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "dytv-preload");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Map<String, CacheWriter> ACTIVE_WRITERS = new ConcurrentHashMap<>();

    private VideoCache() {}

    public static synchronized SimpleCache cache(Context c) {
        if (instance == null) {
            Context app = c.getApplicationContext();
            File dir = new File(app.getCacheDir(), "media3_video");
            if (!dir.exists()) dir.mkdirs();
            instance = new SimpleCache(
                    dir,
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    new StandaloneDatabaseProvider(app));
        }
        return instance;
    }

    public static DefaultHttpDataSource.Factory httpFactory() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.douyin.com/");
        headers.put("Accept", "*/*");
        String cookies = App.get().api().cookies().header();
        if (!cookies.isEmpty()) headers.put("Cookie", cookies);
        return new DefaultHttpDataSource.Factory()
                .setUserAgent(ink.pengfei.douyin.net.Http.UA)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(7000);
    }

    public static DataSource.Factory dataSourceFactory(Context c) {
        return new CacheDataSource.Factory()
                .setCache(cache(c))
                .setUpstreamDataSourceFactory(httpFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static void cancelPreload() {
        SEQ.incrementAndGet();
        for (String key : new ArrayList<>(ACTIVE_WRITERS.keySet())) {
            CacheWriter w = ACTIVE_WRITERS.remove(key);
            if (w != null) {
                try { w.cancel(); } catch (Exception ignored) {}
            }
        }
    }

    /** 仅取消当前准备开播 URL 的预载写入，保留后续视频的并发预载任务。 */
    public static void cancelPreloadFor(String url) {
        if (url == null || url.isEmpty()) return;
        CacheWriter w = ACTIVE_WRITERS.remove(url);
        if (w != null) {
            try { w.cancel(); } catch (Exception ignored) {}
        }
    }

    public static void preloadNext(Context c, final Aweme next) {
        preloadUpcoming(c, next, null);
    }

    /** 双路并行预载接下来的两条视频（覆盖 + 首段数据），互不打断。 */
    public static void preloadUpcoming(Context c, Aweme first, Aweme second) {
        if (!App.get().store().preload()) return;
        final int token = SEQ.incrementAndGet();
        submitPreload(c, first, token);
        submitPreload(c, second, token);
    }

    private static void submitPreload(Context c, Aweme item, final int token) {
        if (item == null) return;
        final List<String> candidates = item.playCandidates(App.get().store().quality());
        String chosen = null;
        for (String u : candidates) {
            if (u != null && u.startsWith("http") && !u.contains("-web-prime.")) {
                chosen = u;
                break;
            }
        }
        if (chosen == null && !candidates.isEmpty()) chosen = candidates.get(0);
        if (chosen == null) return;
        final String targetUrl = chosen;
        if (ACTIVE_WRITERS.containsKey(targetUrl)) return;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            if (SEQ.get() != token) return;
            CacheWriter writer = null;
            try {
                SimpleCache sc = cache(app);
                if (sc.getCachedBytes(targetUrl, 0, PRELOAD_BYTES) >= PRELOAD_BYTES / 2) return;
                CacheDataSource ds = new CacheDataSource.Factory()
                        .setCache(sc)
                        .setUpstreamDataSourceFactory(httpFactory())
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSourceForDownloading();
                DataSpec spec = new DataSpec(Uri.parse(targetUrl), 0, PRELOAD_BYTES);
                writer = new CacheWriter(ds, spec, null, null);
                if (ACTIVE_WRITERS.putIfAbsent(targetUrl, writer) != null) return;
                if (SEQ.get() == token) writer.cache();
            } catch (Exception ignored) {
            } finally {
                if (writer != null) ACTIVE_WRITERS.remove(targetUrl, writer);
            }
        });
    }

    public static long usedBytes(Context c) {
        try {
            return cache(c).getCacheSpace();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static void clear(Context c) {
        cancelPreload();
        try {
            SimpleCache sc = cache(c);
            for (String key : new ArrayList<>(sc.getKeys())) {
                sc.removeResource(key);
            }
        } catch (Exception ignored) {}
    }
}
