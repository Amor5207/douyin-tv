package ink.pengfei.douyin;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import ink.pengfei.douyin.net.CookieStore;
import ink.pengfei.douyin.net.DouyinApi;
import ink.pengfei.douyin.util.ShareHub;
import ink.pengfei.douyin.util.Store;

public class App extends Application {
    private static App instance;
    private static Activity currentActivity;
    private DouyinApi api;
    private Store store;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        final Thread.UncaughtExceptionHandler prevHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                android.util.Log.e("dytv-crash", "Uncaught on " + t.getName(), e);
                java.io.File f = new java.io.File(getCacheDir(), "last_crash.txt");
                try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f))) {
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                }
            } catch (Throwable ignored) {}
            if (prevHandler != null) prevHandler.uncaughtException(t, e);
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { currentActivity = a; }
            @Override public void onActivityPaused(Activity a) {
                if (currentActivity == a) currentActivity = null;
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        store = new Store(this);
        api = new DouyinApi(new CookieStore(this));
        ShareHub.start(store);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    api.bootstrap();
                    // 冷启动校验一次登录态：服务端已踢掉的 sessionid 会被标记，
                    // 之后 loggedIn() 返回 false，各页面正确显示未登录。
                    if (api.loggedIn()) api.selfName();
                } catch (Exception ignored) {
                }
                while (true) {
                    try {
                        Thread.sleep(15 * 60 * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        api.bootstrap();
                        if (api.loggedIn()) api.selfName();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "session-heartbeat").start();
    }

    public static App get() {
        return instance;
    }

    public static Activity currentActivity() {
        return currentActivity;
    }

    public DouyinApi api() {
        return api;
    }

    public Store store() {
        return store;
    }
}
