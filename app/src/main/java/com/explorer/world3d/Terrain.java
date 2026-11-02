package com.explorer.world3d;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/** تضاريس حقيقية من بلاطات الارتفاع المفتوحة (Terrarium) بدون مفتاح. الارتفاعات نسبية لنقطة البداية. */
final class Terrain {
    interface Decoder { float[] decode(byte[] png) throws IOException; }

    static final int Z = 14;
    private static final double NPIX = (double) (1 << Z) * 256.0;
    private static final ConcurrentHashMap<Long, float[]> dem = new ConcurrentHashMap<>();
    private static volatile File cacheDir;
    private static volatile Decoder decoder;
    private static volatile AssetManager assets;
    static volatile boolean enabled = false;
    static volatile float base = 0f;
    static volatile boolean baseSet = false;
    static volatile boolean flat = false;      // فشل تحميل الارتفاعات: عالم مسطح
    static volatile int demFails = 0;

    private Terrain() {}

    static void init(File dir, Decoder d, AssetManager manager) {
        cacheDir = dir;
        decoder = d;
        assets = manager;
        if (dir != null) dir.mkdirs();
    }

    static void reset() { enabled = false; baseSet = false; flat = false; demFails = 0; base = 0f; }

    /** التضاريس مفعّلة داخل نطاق عمّان فقط؛ بقية المدن تستخدم سطحاً مستوياً. */
    static boolean isAmman(double lat, double lon) {
        double p1 = Math.toRadians(31.9522646), p2 = Math.toRadians(lat);
        double dp = p2 - p1, dl = Math.toRadians(lon - 35.9389907);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        double distance = 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return distance <= 12000.0;
    }

    static void configureForCity(double lat, double lon) {
        enabled = isAmman(lat, lon);
        if (!enabled) { base = 0f; baseSet = false; }
    }

    static boolean ready() { return enabled && baseSet; }

    static double gx(double lon) { return (lon + 180.0) / 360.0 * NPIX; }
    static double gy(double lat) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * NPIX;
    }
    private static long key(int tx, int ty) { return (((long) tx) << 32) | (ty & 0xffffffffL); }

    static boolean has(double lat, double lon) {
        return dem.containsKey(key((int) Math.floor(gx(lon) / 256.0), (int) Math.floor(gy(lat) / 256.0)));
    }

    static void ensure(double lat, double lon) throws IOException {
        if (!enabled) return;
        int tx = (int) Math.floor(gx(lon) / 256.0), ty = (int) Math.floor(gy(lat) / 256.0);
        long k = key(tx, ty);
        if (dem.containsKey(k)) return;
        synchronized (Terrain.class) {
            if (dem.containsKey(k)) return;
            File f = cacheDir == null ? null : new File(cacheDir, "d_" + Z + "_" + tx + "_" + ty + ".png");
            byte[] data = null;
            if (f != null && f.exists() && f.length() > 100) {
                try { data = readAll(new FileInputStream(f)); } catch (IOException ignore) { data = null; }
            }
            if (data == null && assets != null) {
                try {
                    data = readAll(assets.open("cities/tabarbour/terrain/" + Z + "/" + tx + "/" + ty + ".png"));
                } catch (IOException missingBundledTile) {
                    data = null;
                }
            }
            if (data == null) {
                String url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" + Z + "/" + tx + "/" + ty + ".png";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                try {
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "World3DExplorer/1.0 (android)");
                    if (c.getResponseCode() != 200) throw new IOException("DEM HTTP " + c.getResponseCode());
                    data = readAll(c.getInputStream());
                } finally { c.disconnect(); }
                if (f != null) {
                    try { FileOutputStream o = new FileOutputStream(f); o.write(data); o.close(); } catch (IOException ignore) {}
                }
            }
            float[] arr = decoder.decode(data);
            if (arr == null || arr.length != 65536) throw new IOException("bad DEM tile");
            dem.put(k, arr);
        }
    }

    /** يحمّل كل بلاطات DEM التي قد تحتاجها المنطقة، بما فيها بكسل الاستيفاء المجاور. */
    static void ensureRect(double s, double w, double n, double e) throws IOException {
        if (!enabled) return;
        double x0 = gx(w) - 0.5, x1 = gx(e) - 0.5;
        double y0 = gy(s) - 0.5, y1 = gy(n) - 0.5;
        int minTx = (int) Math.floor(Math.min(x0, x1) / 256.0);
        int maxTx = (int) Math.floor((Math.max(x0, x1) + 1.0) / 256.0);
        int minTy = (int) Math.floor(Math.min(y0, y1) / 256.0);
        int maxTy = (int) Math.floor((Math.max(y0, y1) + 1.0) / 256.0);
        int scale = 1 << Z;
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                double lon = ((tx + 0.5) / scale) * 360.0 - 180.0;
                double merc = Math.PI - 2.0 * Math.PI * (ty + 0.5) / scale;
                double lat = Math.toDegrees(Math.atan(Math.sinh(merc)));
                ensure(lat, lon);
            }
        }
    }

    private static float pix(int px, int py) {
        float[] t = dem.get(key(px >> 8, py >> 8));
        return t == null ? Float.NaN : t[(py & 255) * 256 + (px & 255)];
    }

    /** الارتفاع المطلق (م) فوق سطح البحر، أو NaN إذا لم تُحمَّل البيانات. */
    static float rawElev(double lat, double lon) {
        double x = gx(lon) - 0.5, y = gy(lat) - 0.5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = (float) (x - ix), fy = (float) (y - iy);
        float a = pix(ix, iy), b = pix(ix + 1, iy), c = pix(ix, iy + 1), d = pix(ix + 1, iy + 1);
        float any = !Float.isNaN(a) ? a : !Float.isNaN(b) ? b : !Float.isNaN(c) ? c : d;
        if (Float.isNaN(any)) return Float.NaN;
        if (Float.isNaN(a)) a = any;
        if (Float.isNaN(b)) b = any;
        if (Float.isNaN(c)) c = any;
        if (Float.isNaN(d)) d = any;
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    static void setBaseAt(double lat, double lon) {
        if (!enabled) { base = 0f; baseSet = false; return; }
        float e = rawElev(lat, lon);
        base = Float.isNaN(e) ? 0f : e;
        baseSet = !Float.isNaN(e);
    }

    /** الارتفاع النسبي لنقطة بداية المدينة (م). */
    static float h(float x, float z) {
        if (!enabled || !baseSet) return 0f;
        float e = rawElev(Geo.latOf(z), Geo.lonOf(x));
        return Float.isNaN(e) ? 0f : e - base;
    }

    /** مستوى سطح البحر نسبةً لنقطة البداية. */
    static float seaY() { return enabled && baseSet ? -base : 0f; }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
