package ink.pengfei.douyin.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Async {
    public interface Fn<T> { T run() throws Exception; }
    public interface Cb<T> { void done(T value, Exception error); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {}

    public static <T> void run(final Fn<T> fn, final Cb<T> cb) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                T value = null;
                Exception err = null;
                try {
                    value = fn.run();
                } catch (Exception e) {
                    err = e;
                }
                final T v = value;
                final Exception e = err;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.done(v, e);
                    }
                });
            }
        });
    }

    public static void ui(Runnable r) {
        MAIN.post(r);
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
