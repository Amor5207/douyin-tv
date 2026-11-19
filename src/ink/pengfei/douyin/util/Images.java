package ink.pengfei.douyin.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class Images {
    private static final java.util.concurrent.ExecutorService IMAGE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6);
    private static final int CACHE_BYTES = (int) Math.min(24L * 1024 * 1024,
            Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10L));
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Images() {}

    public static void clear() {
        CACHE.evictAll();
    }

    public static long usedBytes() {
        return CACHE.size();
    }

    public static int cacheCount() {
        return CACHE.snapshot().size();
    }

    public static void preload(final String url, final int widthPx) {
        if (url == null || url.isEmpty()) return;
        final String key = url + "#" + widthPx + "#0";
        if (CACHE.get(key) != null) return;
        IMAGE_POOL.execute(() -> fetchAndDecode(url, key, widthPx, 0));
    }

    public static void load(final String url, final ImageView view, final int widthPx, final int radiusPx) {
        if (url == null || url.isEmpty()) return;
        final String key = url + "#" + widthPx + "#" + radiusPx;
        final Bitmap cached = CACHE.get(key);
        view.setTag(key);
        view.animate().cancel();
        if (cached != null) {
            view.setAlpha(1f);
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        IMAGE_POOL.execute(() -> {
            final Bitmap loaded = fetchAndDecode(url, key, widthPx, radiusPx);
            Async.ui(() -> {
                if (loaded != null && key.equals(view.getTag())) {
                    view.animate().cancel();
                    if (view.isShown()) {
                        view.setAlpha(0f);
                        view.setImageBitmap(loaded);
                        view.animate().alpha(1f).setDuration(160)
                                .setInterpolator(ink.pengfei.douyin.ui.Ui.ease()).start();
                    } else {
                        view.setAlpha(1f);
                        view.setImageBitmap(loaded);
                    }
                }
            });
        });
    }

    private static Bitmap fetchAndDecode(String url, String key, int widthPx, int radiusPx) {
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", ink.pengfei.douyin.net.Http.UA);
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            if (c.getResponseCode() >= 400) {
                c.disconnect();
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            byte[] data = read(c);
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int cap = widthPx > 420 ? Math.min(widthPx, 960) : 420;
            int targetWidth = widthPx > 0 ? Math.min(widthPx, cap) : 420;
            int sample = 1;
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            if (radiusPx == 0) {
                o.inPreferredConfig = Bitmap.Config.RGB_565;
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp != null) {
                if (bmp.getWidth() > targetWidth) {
                    int h = Math.max(1, bmp.getHeight() * targetWidth / bmp.getWidth());
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetWidth, h, true);
                    if (scaled != bmp) bmp.recycle();
                    bmp = scaled;
                }
                if (radiusPx != 0) bmp = round(bmp, radiusPx);
                CACHE.put(key, bmp);
                return bmp;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] read(HttpURLConnection c) throws Exception {
        InputStream is = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        c.disconnect();
        return bos.toByteArray();
    }

    private static Bitmap round(Bitmap src, int radiusPx) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        Path path = new Path();
        if (radiusPx < 0) {
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, paint);
        } else {
            path.addRoundRect(new RectF(0, 0, w, h), radiusPx, radiusPx, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
        src.recycle();
        return out;
    }

    public static Bitmap qr(String text, int size) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
