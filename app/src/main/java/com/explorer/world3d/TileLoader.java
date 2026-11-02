package com.explorer.world3d;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** خيط خلفي يجلب مربعات الخريطة القريبة فقط (مع تخزين محلي) ويبني هندستها. */
final class TileLoader extends Thread {
    interface ErrorCallback { void onError(); }

    static final int LOAD_R = 1;   // 3×3 مربعات حول اللاعب
    static final String[] SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"};

    volatile int ptx, ptz, gen = 0;
    volatile boolean running = true, havePlayer = false;
    final ConcurrentLinkedQueue<TileData> ready = new ConcurrentLinkedQueue<>();
    final Set<Long> requested = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<Long, Long> failedAt = new ConcurrentHashMap<>();
    private final File cacheDir;
    private final ErrorCallback cb;
    private final ArrayList<int[]> order = new ArrayList<>();

    TileLoader(File cacheDir, ErrorCallback cb) {
        super("tile-loader");
        this.cacheDir = cacheDir;
        this.cb = cb;
        cacheDir.mkdirs();
        for (int dx = -LOAD_R; dx <= LOAD_R; dx++) for (int dz = -LOAD_R; dz <= LOAD_R; dz++) order.add(new int[]{dx, dz});
        Collections.sort(order, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                return (a[0] * a[0] + a[1] * a[1]) - (b[0] * b[0] + b[1] * b[1]);
            }
        });
        setDaemon(true);
    }

    void shutdown() { running = false; interrupt(); }

    @Override public void run() {
        while (running) {
            try {
                if (!havePlayer) { Thread.sleep(200); continue; }
                int cx = ptx, cz = ptz, g = gen;
                int[] pick = null;
                long k = 0;
                long now = System.currentTimeMillis();
                for (int[] o : order) {
                    int ix = cx + o[0], iz = cz + o[1];
                    long key = Geo.key(ix, iz);
                    if (requested.contains(key)) continue;
                    Long f = failedAt.get(key);
                    if (f != null && now - f < 10000) continue;
                    pick = new int[]{ix, iz};
                    k = key;
                    break;
                }
                if (pick == null) { Thread.sleep(250); continue; }
                requested.add(k);
                try {
                    TileData td = loadTile(pick[0], pick[1]);
                    td.gen = g;
                    if (g == gen) { ready.add(td); failedAt.remove(k); }
                    else requested.remove(k);
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception ex) {
                    requested.remove(k);
                    failedAt.put(k, System.currentTimeMillis());
                    if (cb != null) cb.onError();
                }
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private TileData loadTile(int ix, int iz) throws Exception {
        double west = ix * Geo.dLon, east = (ix + 1) * Geo.dLon, south = iz * Geo.D_LAT, north = (iz + 1) * Geo.D_LAT;
        File f = new File(cacheDir, String.format(Locale.US, "t_%d_%d.json", Math.round(south * 1e5), Math.round(west * 1e5)));
        String json = null;
        if (f.exists() && f.length() > 2 && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) {
            try { json = readFile(f); } catch (IOException ignore) { json = null; }
        }
        if (json == null) {
            String bb = String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)", south, west, north, east);
            String q = "[out:json][timeout:60];("
                    + "way[\"building\"]" + bb + ";way[\"highway\"]" + bb + ";"
                    + "way[\"leisure\"~\"park|garden|pitch|playground\"]" + bb + ";"
                    + "way[\"landuse\"~\"grass|forest|recreation_ground|village_green|meadow|sand|farmland|orchard|vineyard|cemetery\"]" + bb + ";"
                    + "way[\"natural\"~\"water|wood|scrub|beach|sand|dune|wetland|heath|grassland|coastline\"]" + bb + ";"
                    + "way[\"waterway\"~\"riverbank|river|stream|canal\"]" + bb + ";way[\"landuse\"~\"reservoir|basin\"]" + bb + ";"
                    + "node[\"natural\"=\"tree\"]" + bb + ";node[\"highway\"=\"street_lamp\"]" + bb + ";"
                    + ");out geom;";
            Exception last = null;
            for (String s : SERVERS) {
                if (!running) throw new InterruptedException();
                try {
                    String r = http(s, "data=" + java.net.URLEncoder.encode(q, "UTF-8"));
                    if (r.trim().startsWith("{")) { json = r; break; }
                } catch (IOException ex) { last = ex; }
            }
            if (json == null) throw last != null ? last : new IOException("no data");
            if (!json.contains("\"remark\"")) {
                try { writeFile(f, json); } catch (IOException ignore) {}
            }
        }
        // التضاريس الحقيقية: نقطة المرجع ثم بلاطات الارتفاع حول المربع
        if (!Terrain.ready()) {
            synchronized (Terrain.class) {
                if (!Terrain.ready()) {
                    try {
                        Terrain.ensureRect(Geo.lat0 - 0.00001, Geo.lon0 - 0.00001,
                                Geo.lat0 + 0.00001, Geo.lon0 + 0.00001);
                        Terrain.setBaseAt(Geo.lat0, Geo.lon0);
                    } catch (IOException ex) {
                        Terrain.demFails++;
                        throw ex;
                    }
                    if (!Terrain.baseSet) throw new IOException("DEM reference elevation unavailable");
                }
            }
        }
        // لا نبني الطرق أو المباني قبل ضمان توفر جميع بلاطات الارتفاع للمنطقة.
        Terrain.ensureRect(south - 2 * Geo.D_LAT, west - 2 * Geo.dLon, north + 2 * Geo.D_LAT, east + 2 * Geo.dLon);
        return TileBuilder.build(new JSONObject(json), ix, iz);
    }

    static String http(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "World3DExplorer/1.0 (android)");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "World3DExplorer/1.0 (android)");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static String readFile(File f) throws IOException {
        return readAll(new FileInputStream(f));
    }

    private static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write(s.getBytes("UTF-8"));
        o.close();
        tmp.renameTo(f);
    }
}
