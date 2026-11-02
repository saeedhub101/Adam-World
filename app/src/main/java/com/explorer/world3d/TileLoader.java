package com.explorer.world3d;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
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

    static final int LOAD_R = 3;   // 7×7 مربعات: تحميل أوسع يطابق نطاق الاحتفاظ المرئي
    static final String[] SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"};

    volatile int ptx, ptz, gen = 0;
    volatile boolean running = true, havePlayer = false;
    final ConcurrentLinkedQueue<TileData> ready = new ConcurrentLinkedQueue<>();
    final Set<Long> requested = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<Long, Long> failedAt = new ConcurrentHashMap<>();
    private final Context context;
    private final File cacheDir;
    private final File offlineDir;
    private final File manilaOfflineDir;
    private final ErrorCallback cb;
    private final ArrayList<int[]> order = new ArrayList<>();
    private static final double AMMAN_LAT0 = 31.9522646;
    private static final double AMMAN_LON0 = 35.9389907;
    private static final double AMMAN_KX = 111320.0 * Math.cos(Math.toRadians(AMMAN_LAT0));
    private static final double AMMAN_KY = 111132.0;
    private static final String OFFLINE_BASE = "https://raw.githubusercontent.com/saeedhub101/Adam-World/main/data/offline-city-stream/amman";
    private static final String MANILA_OFFLINE_BASE = "https://raw.githubusercontent.com/saeedhub101/Adam-World/main/data/offline-city-stream/manila";
    private static final double MANILA_LAT0 = 14.57394;
    private static final double MANILA_LON0 = 120.98283;
    private static final double MANILA_KX = 111320.0 * Math.cos(Math.toRadians(MANILA_LAT0));
    private static final double MANILA_KY = 111132.0;
    private static final long OFFLINE_CACHE_MAX_BYTES = 384L * 1024L * 1024L;
    private static final long ONLINE_CACHE_MAX_BYTES = 128L * 1024L * 1024L;
    private static final long CACHE_MAX_AGE_MS = 3L * 24L * 3600L * 1000L;
    volatile boolean offlineAmman = false;
    volatile boolean offlineManila = false;
    volatile String offlineCitySlug = null;
    private JSONObject bundledCityRoot;
    private String bundledCitySlug;
    private volatile Set<String> offlineCells;
    private volatile String offlineDatasetVersion;
    private final LinkedHashMap<String, JSONObject> offlineShardCache =
            new LinkedHashMap<String, JSONObject>(12, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                    return size() > 4;
                }
            };

    TileLoader(Context context, File cacheDir, ErrorCallback cb) {
        super("tile-loader");
        this.context = context.getApplicationContext();
        this.cacheDir = cacheDir;
        this.offlineDir = new File(cacheDir, "offline-amman");
        this.manilaOfflineDir = new File(cacheDir, "offline-manila");
        this.cb = cb;
        cacheDir.mkdirs();
        offlineDir.mkdirs();
        pruneOnlineCache();
        for (int dx = -LOAD_R; dx <= LOAD_R; dx++) for (int dz = -LOAD_R; dz <= LOAD_R; dz++) order.add(new int[]{dx, dz});
        Collections.sort(order, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                return (a[0] * a[0] + a[1] * a[1]) - (b[0] * b[0] + b[1] * b[1]);
            }
        });
        setDaemon(true);
    }

    void shutdown() { running = false; interrupt(); }

    void resetOfflineShardState() {
        synchronized (this) { offlineCells = null; offlineDatasetVersion = null; }
        synchronized (offlineShardCache) { offlineShardCache.clear(); }
    }

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
        JSONObject root = null;
        String json = null;
        if (offlineAmman) {
            root = loadOfflineRoot(south, west, north, east);
        } else if (offlineManila) {
            root = loadManilaRoot(south, west, north, east);
        } else if (offlineCitySlug != null) {
            root = loadBundledCityRoot(south, west, north, east);
        } else {
            File f = new File(cacheDir, String.format(Locale.US, "t_%d_%d.json", Math.round(south * 1e5), Math.round(west * 1e5)));
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
                try { writeFile(f, json); pruneOnlineCache(); } catch (IOException ignore) {}
            }
        }
        root = new JSONObject(json);
        }
        // DEM يُحمّل فقط للمربعات القريبة من عمّان؛ لا تُحمّل بيانات المدينة كاملة.
        if (Terrain.enabled) {
            if (!Terrain.ready()) {
                synchronized (Terrain.class) {
                    if (Terrain.enabled && !Terrain.ready()) {
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
            if (Terrain.enabled) {
                Terrain.ensureRect(south - 2 * Geo.D_LAT, west - 2 * Geo.dLon,
                        north + 2 * Geo.D_LAT, east + 2 * Geo.dLon);
            }
        }
        return TileBuilder.build(root, ix, iz);
    }

    /** Read a bundled OSM city extract and select only features intersecting this render tile. */
    private JSONObject loadBundledCityRoot(double south, double west, double north, double east) throws Exception {
        final String slug = offlineCitySlug;
        if (slug == null || slug.trim().isEmpty()) return emptyElements();
        JSONObject source;
        synchronized (this) {
            if (bundledCityRoot == null || !slug.equals(bundledCitySlug)) {
                StringBuilder json = new StringBuilder(2 * 1024 * 1024);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open("elements.json"), "UTF-8"), 32768)) {
                    char[] buffer = new char[16384];
                    int n;
                    while ((n = reader.read(buffer)) != -1) json.append(buffer, 0, n);
                }
                JSONObject loaded = new JSONObject(json.toString());
                if (loaded.optJSONArray("elements") == null) throw new IOException("Invalid bundled OSM city dataset: " + slug);
                if ("manila".equals(slug)) addRizalParkInventory(loaded);
                bundledCityRoot = loaded;
                bundledCitySlug = slug;
            }
            source = bundledCityRoot;
        }
        JSONArray all = source.optJSONArray("elements");
        JSONArray selected = new JSONArray();
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject element = all.optJSONObject(i);
                if (element != null && intersectsTile(element, south, west, north, east)) selected.put(element);
            }
        }
        JSONObject result = new JSONObject();
        result.put("elements", selected);
        return result;
    }

    /** Preserve the old_app NPDC tree inventory as geolocated tree features in offline Manila. */
    private void addRizalParkInventory(JSONObject city) {
        try (InputStream in = context.getAssets().open(
                "regions/philippines/manila/rizal-park/rizal-park-inventory.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            JSONArray trees = new JSONObject(json.toString()).optJSONArray("trees");
            JSONArray elements = city.optJSONArray("elements");
            if (trees == null || elements == null) return;
            for (int i = 0; i < trees.length(); i++) {
                JSONObject tree = trees.optJSONObject(i);
                if (tree == null) continue;
                double lat = tree.optDouble("latitude", Double.NaN);
                double lon = tree.optDouble("longitude", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                JSONObject tags = new JSONObject();
                tags.put("natural", "tree");
                tags.put("name", tree.optString("common_name", "Tree"));
                tags.put("species", tree.optString("scientific_name", ""));
                tags.put("height", tree.optString("height_m", ""));
                tags.put("ref", tree.optString("id", ""));
                JSONObject node = new JSONObject();
                node.put("type", "node");
                node.put("id", 9000000000L + Math.abs((long) tree.optString("id", "0").hashCode()));
                node.put("lat", lat);
                node.put("lon", lon);
                node.put("tags", tags);
                elements.put(node);
            }
        } catch (Exception ignored) {
            // The OSM city remains usable if the optional legacy inventory is unavailable.
        }
    }

    private static boolean intersectsTile(JSONObject element, double south, double west, double north, double east) {
        String type = element.optString("type", "");
        if ("node".equals(type)) {
            double lat = element.optDouble("lat", Double.NaN);
            double lon = element.optDouble("lon", Double.NaN);
            return !Double.isNaN(lat) && !Double.isNaN(lon)
                    && lat >= south && lat <= north && lon >= west && lon <= east;
        }
        JSONArray geometry = element.optJSONArray("geometry");
        if (geometry == null || geometry.length() < 2) return false;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < geometry.length(); i++) {
            JSONObject point = geometry.optJSONObject(i);
            if (point == null) continue;
            double lat = point.optDouble("lat", Double.NaN);
            double lon = point.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
        }
        return maxLat >= south && minLat <= north && maxLon >= west && minLon <= east;
    }


    private JSONObject loadManilaRoot(double south, double west, double north, double east) throws Exception {
        ensureManilaManifest();
        double lat = (south + north) * 0.5, lon = (west + east) * 0.5;
        int sx = (int) Math.floor((lon - MANILA_LON0) * MANILA_KX / 1000.0);
        int sz = (int) Math.floor(-(lat - MANILA_LAT0) * MANILA_KY / 1000.0);
        String cell = sx + "_" + sz;
        if (!offlineCells.contains(cell)) return emptyElements();
        String version = offlineDatasetVersion == null ? "current" : offlineDatasetVersion;
        String key = version + ":" + cell;
        synchronized (offlineShardCache) {
            JSONObject cached = offlineShardCache.get(key);
            if (cached != null) return cached;
        }
        manilaOfflineDir.mkdirs();
        File versionDir = new File(manilaOfflineDir, version); versionDir.mkdirs();
        File shard = new File(versionDir, cell + ".jsonl.gz");
        if (!shard.isFile() || shard.length() == 0) {
            byte[] bytes = downloadBytes(MANILA_OFFLINE_BASE + "/shards/" + cell + ".jsonl.gz");
            File tmp = new File(shard.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
            if (!tmp.renameTo(shard)) { tmp.delete(); throw new IOException("Cannot cache Manila shard " + cell); }
            pruneOfflineCache(shard);
        }
        JSONArray elements = new JSONArray();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(shard)), "UTF-8"), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) elements.put(new JSONObject(line));
        } catch (Exception ex) { shard.delete(); throw ex; }
        JSONObject result = new JSONObject(); result.put("elements", elements);
        synchronized (offlineShardCache) { offlineShardCache.put(key, result); }
        return result;
    }

    private void ensureManilaManifest() throws Exception {
        if (offlineCells != null && offlineDatasetVersion != null) return;
        synchronized (this) {
            if (offlineCells != null && offlineDatasetVersion != null) return;
            manilaOfflineDir.mkdirs();
            File manifestFile = new File(manilaOfflineDir, "manifest.json");
            if (!manifestFile.isFile() || System.currentTimeMillis() - manifestFile.lastModified() > 5L * 60 * 1000) {
                try {
                    byte[] bytes = downloadBytes(MANILA_OFFLINE_BASE + "/manifest.json");
                    File tmp = new File(manifestFile.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                    if (!tmp.renameTo(manifestFile)) { tmp.delete(); throw new IOException("Cannot cache Manila manifest"); }
                } catch (IOException networkError) {
                    if (!manifestFile.isFile() || manifestFile.length() == 0) throw networkError;
                }
            }
            JSONObject manifest = new JSONObject(readFile(manifestFile));
            JSONArray list = manifest.optJSONArray("cells");
            if (list == null || list.length() == 0) throw new IOException("Invalid Manila offline manifest");
            HashSet<String> cells = new HashSet<>();
            for (int i=0;i<list.length();i++) cells.add(list.optString(i));
            String nextVersion = manifest.optString("dataset_version", "current");
            if (offlineDatasetVersion != null && !offlineDatasetVersion.equals(nextVersion)) {
                synchronized (offlineShardCache) { offlineShardCache.clear(); }
            }
            offlineDatasetVersion = nextVersion; offlineCells = cells;
            pruneOfflineCache(null);
        }
    }

    private JSONObject loadOfflineRoot(double south, double west, double north, double east) throws Exception {
        ensureOfflineManifest();
        double lat = (south + north) * 0.5;
        double lon = (west + east) * 0.5;
        int sx = (int) Math.floor((lon - AMMAN_LON0) * AMMAN_KX / 1000.0);
        int sz = (int) Math.floor(-(lat - AMMAN_LAT0) * AMMAN_KY / 1000.0);
        String cell = sx + "_" + sz;
        if (!offlineCells.contains(cell)) return emptyElements();

        String version = offlineDatasetVersion == null ? "current" : offlineDatasetVersion;
        String key = version + ":" + cell;
        synchronized (offlineShardCache) {
            JSONObject cached = offlineShardCache.get(key);
            if (cached != null) return cached;
        }
        File versionDir = new File(offlineDir, version);
        versionDir.mkdirs();
        File shard = new File(versionDir, cell + ".jsonl.gz");
        if (!shard.isFile() || shard.length() == 0) {
            byte[] bytes = downloadBytes(OFFLINE_BASE + "/shards/" + cell + ".jsonl.gz");
            File tmp = new File(shard.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
            if (!tmp.renameTo(shard)) {
                tmp.delete();
                throw new IOException("Cannot cache Amman shard " + cell);
            }
            pruneOfflineCache(shard);
        }
        JSONArray elements = new JSONArray();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(shard)), "UTF-8"), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) elements.put(new JSONObject(line));
            }
        } catch (Exception ex) {
            shard.delete();
            throw ex;
        }
        JSONObject result = new JSONObject();
        result.put("elements", elements);
        synchronized (offlineShardCache) { offlineShardCache.put(key, result); }
        return result;
    }

    private void ensureOfflineManifest() throws Exception {
        if (offlineCells != null && offlineDatasetVersion != null) return;
        synchronized (this) {
            if (offlineCells != null && offlineDatasetVersion != null) return;
            File manifestFile = new File(offlineDir, "manifest.json");
            if (!manifestFile.isFile() || System.currentTimeMillis() - manifestFile.lastModified() > 5L * 60 * 1000) {
                try {
                    byte[] bytes = downloadBytes(OFFLINE_BASE + "/manifest.json");
                    File tmp = new File(manifestFile.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                    if (!tmp.renameTo(manifestFile)) {
                        tmp.delete();
                        throw new IOException("Cannot cache Amman manifest");
                    }
                } catch (IOException networkError) {
                    // Keep the last valid manifest usable when the player is offline.
                    if (!manifestFile.isFile() || manifestFile.length() == 0) throw networkError;
                }
            }
            JSONObject manifest = new JSONObject(readFile(manifestFile));
            JSONArray list = manifest.optJSONArray("cells");
            if (list == null || list.length() == 0) throw new IOException("Invalid Amman offline manifest");
            HashSet<String> cells = new HashSet<>();
            for (int i = 0; i < list.length(); i++) cells.add(list.optString(i));
            String nextVersion = manifest.optString("dataset_version", "current");
            if (offlineDatasetVersion != null && !offlineDatasetVersion.equals(nextVersion)) {
                synchronized (offlineShardCache) { offlineShardCache.clear(); }
            }
            offlineDatasetVersion = nextVersion;
            offlineCells = cells;
            pruneOfflineCache(null);
        }
    }

    /** Keep offline storage bounded: expire old shards and evict least-recently-used files. */
    private void pruneOfflineCache(File keep) {
        File activeDir = offlineManila ? manilaOfflineDir : offlineDir;
        File[] versions = activeDir.listFiles();
        if (versions == null) return;
        long now = System.currentTimeMillis(), total = 0L;
        ArrayList<File> shards = new ArrayList<>();
        for (File dir : versions) {
            if (!dir.isDirectory()) continue;
            if (offlineDatasetVersion != null && !dir.getName().equals(offlineDatasetVersion)) {
                deleteTree(dir);
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".jsonl.gz")) continue;
                if (now - file.lastModified() > CACHE_MAX_AGE_MS && (keep == null || !file.equals(keep))) {
                    file.delete(); continue;
                }
                total += file.length(); shards.add(file);
            }
        }
        Collections.sort(shards, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (File file : shards) {
            if (total <= OFFLINE_CACHE_MAX_BYTES) break;
            if (keep != null && file.equals(keep)) continue;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    /** Online tile JSON is cache-only too; cap it independently from offline shards. */
    private void pruneOnlineCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis(), total = 0L;
        ArrayList<File> cached = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().startsWith("t_") || !file.getName().endsWith(".json")) continue;
            if (now - file.lastModified() > CACHE_MAX_AGE_MS) { file.delete(); continue; }
            total += file.length(); cached.add(file);
        }
        Collections.sort(cached, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (File file : cached) {
            if (total <= ONLINE_CACHE_MAX_BYTES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static JSONObject emptyElements() throws Exception {
        JSONObject root = new JSONObject();
        root.put("elements", new JSONArray());
        return root;
    }

    private static byte[] downloadBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setRequestProperty("User-Agent", "Adam-World-Android");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (c.getResponseCode() != 200) throw new IOException("Offline city HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
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
