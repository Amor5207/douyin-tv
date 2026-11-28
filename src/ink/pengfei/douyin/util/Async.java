package ink.pengfei.douyin.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Async {
    public interface Fn<T> { T run() throws Exception; }
    public interface Cb<T> { void done(T value, Exception error); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /** 登录/扫码走独立线程池：首页播放任务把公共池占满时也不能挡住登录。 */
    private static final ExecutorService LOGIN_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "login-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {}

    public static <T> void run(final Fn<T> fn, final Cb<T> cb) {
        submit(POOL, fn, cb);
    }

    public static <T> void runLogin(final Fn<T> fn, final Cb<T> cb) {
        submit(LOGIN_POOL, fn, cb);
    }

    private static <T> void submit(final ExecutorService pool, final Fn<T> fn, final Cb<T> cb) {
        pool.execute(new Runnable() {
            @Override public void run() {
                T value = null;
                Exception err = null;
                try {
                    value = fn.run();
                } catch (Exception e) {
                    err = e;
                } catch (Throwable t) {
                    err = new RuntimeException(t);
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
