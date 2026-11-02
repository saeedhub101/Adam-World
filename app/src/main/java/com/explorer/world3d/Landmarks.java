package com.explorer.world3d;

/** معالم مشهورة مرسومة يدوياً بأبعادها المعروفة (تقريبية)، تحلّ محل مبانٍ OSM العامة. */
final class Landmarks {
    private Landmarks() {}

    static final double BURJ_LAT = 25.19720, BURJ_LON = 55.27440;
    static final double RT_LAT = 31.95165805, RT_LON = 35.93939340; // إحداثيات مركز المدرج من سجل دائرة الآثار الأردنية
    static final double TS_LAT = 40.7580, TS_LON = -73.9855;       // تايمز سكوير
    static final double TK_LAT = 40.7588, TK_LON = -73.9851;       // درجات TKTS الحمراء

    // المدرج: 33 صفاً في ثلاثة أقسام
    static final int ROWS = 33;
    static final float[] ROW_R0 = new float[ROWS], ROW_R1 = new float[ROWS], ROW_H = new float[ROWS];
    static final float RT_OUT;

    static {
        float r = 13f, h = 0f;
        for (int i = 0; i < ROWS; i++) {
            if (i == 11 || i == 22) r += 2.5f; // ممرّات أفقية بين الأقسام
            ROW_R0[i] = r;
            h += 0.5f;
            ROW_H[i] = h;
            r += 1f;
        }
        for (int i = 0; i < ROWS; i++) ROW_R1[i] = (i + 1 < ROWS) ? ROW_R0[i + 1] : ROW_R0[i] + 1f;
        RT_OUT = ROW_R1[ROWS - 1];
    }

    private static boolean near(float x, float z, double lat, double lon, float rad) {
        return Math.hypot(x - Geo.x(lon), z - Geo.z(lat)) < rad;
    }

    static boolean suppress(float x, float z) {
        return near(x, z, BURJ_LAT, BURJ_LON, 45f) || near(x, z, RT_LAT, RT_LON, 75f);
    }

    static boolean nearSigns(float x, float z) {
        return near(x, z, TS_LAT, TS_LON, 170f);
    }

    /** ارتفاع الأرض للاعب فوق المعالم (نسبة لنقطة البداية)، أو NaN خارج المعالم. */
    static float heightAt(float x, float z) {
        float rx = Geo.x(RT_LON), rz = Geo.z(RT_LAT);
        float dx = x - rx, dz = z - rz;
        if (Math.abs(dx) < 60f && Math.abs(dz) < 60f) {
            float base = Terrain.h(x, z);
            if (dz >= 0) {
                float r = (float) Math.hypot(dx, dz);
                if (r < ROW_R0[0]) return base + 0.05f;
                if (r < RT_OUT) {
                    for (int i = ROWS - 1; i >= 0; i--) if (r >= ROW_R0[i]) return base + ROW_H[i];
                }
            } else if (Math.abs(dx) < 25f && dz > -10f && dz < -2f) {
                return base + 1.6f;
            }
        }
        float tx = Geo.x(TK_LON), tz = Geo.z(TK_LAT);
        dx = x - tx; dz = z - tz;
        if (Math.abs(dx) < 7f && dz < 0 && dz > -10.8f) return Terrain.h(tx, tz) + (((int) (-dz / 0.9f)) + 1) * 0.3f;
        return Float.NaN;
    }

    private static int[] starts(TileData td) {
        int[] s = new int[td.bufs.length];
        for (int i = 0; i < s.length; i++) s[i] = td.bufs[i].n;
        return s;
    }

    private static void shift(TileData td, int[] st, float dy) {
        if (dy == 0f) return;
        for (int i = 0; i < st.length; i++) td.bufs[i].shiftY(st[i], dy);
    }

    static void addToTile(TileData td, float xMin, float xMax, float zMin, float zMax) {
        float x = Geo.x(BURJ_LON), z = Geo.z(BURJ_LAT);
        if (x >= xMin && x < xMax && z >= zMin && z < zMax) { int[] st = starts(td); burj(td, x, z); shift(td, st, Terrain.h(x, z)); }
        x = Geo.x(RT_LON); z = Geo.z(RT_LAT);
        if (x >= xMin && x < xMax && z >= zMin && z < zMax) { theatre(td, x, z); }
        x = Geo.x(TK_LON); z = Geo.z(TK_LAT);
        if (x >= xMin && x < xMax && z >= zMin && z < zMax) { int[] st = starts(td); tkts(td, x, z); shift(td, st, Terrain.h(x, z)); }
    }

    /* ---------- أدوات هندسية ---------- */
    private static void polyPrism(GeoBuf b, float[] xs, float[] zs, float y0, float y1, float r, float g, float bl) {
        int n = xs.length;
        float vb = (y1 - y0) / 13.6f, lo = 0.85f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float len = (float) Math.hypot(xs[j] - xs[i], zs[j] - zs[i]);
            if (len < 0.05f) continue;
            float u = Math.max(1, Math.round(len / 4f)) / 4f;
            b.v(xs[i], y0, zs[i], 0, vb, r * lo, g * lo, bl * lo);
            b.v(xs[j], y0, zs[j], u, vb, r * lo, g * lo, bl * lo);
            b.v(xs[j], y1, zs[j], u, 0, r, g, bl);
            b.fixTri();
            b.v(xs[i], y0, zs[i], 0, vb, r * lo, g * lo, bl * lo);
            b.v(xs[j], y1, zs[j], u, 0, r, g, bl);
            b.v(xs[i], y1, zs[i], 0, 0, r, g, bl);
            b.fixTri();
        }
        int[] tr = Poly.earClip(xs, zs);
        for (int i = 0; i + 2 < tr.length; i += 3) {
            for (int k = 0; k < 3; k++) b.v(xs[tr[i + k]], y1, zs[tr[i + k]], 0.01f, 0.01f, r * 0.8f, g * 0.8f, bl * 0.8f);
            b.fixTri();
        }
    }

    private static float[][] corners(float cx, float cz, float ang, float sx, float sz) {
        float ux = (float) Math.cos(ang), uz = (float) Math.sin(ang), wx = -uz, wz = ux;
        float hx = sx / 2f, hz = sz / 2f;
        return new float[][]{
                {cx - ux * hx - wx * hz, cx + ux * hx - wx * hz, cx + ux * hx + wx * hz, cx - ux * hx + wx * hz},
                {cz - uz * hx - wz * hz, cz + uz * hx - wz * hz, cz + uz * hx + wz * hz, cz - uz * hx + wz * hz}};
    }

    private static void frustum(GeoBuf b, float cx, float cz, float y0, float y1, float r0, float r1, int sides,
                                float r, float g, float bl) {
        float vb = (y1 - y0) / 13.6f;
        for (int s = 0; s < sides; s++) {
            float a0 = (float) (2 * Math.PI * s / sides), a1 = (float) (2 * Math.PI * (s + 1) / sides);
            float x00 = cx + r0 * (float) Math.cos(a0), z00 = cz + r0 * (float) Math.sin(a0);
            float x01 = cx + r0 * (float) Math.cos(a1), z01 = cz + r0 * (float) Math.sin(a1);
            float x10 = cx + r1 * (float) Math.cos(a0), z10 = cz + r1 * (float) Math.sin(a0);
            float x11 = cx + r1 * (float) Math.cos(a1), z11 = cz + r1 * (float) Math.sin(a1);
            float u0 = s * 0.5f, u1 = (s + 1) * 0.5f;
            b.v(x00, y0, z00, u0, vb, r * .85f, g * .85f, bl * .85f);
            b.v(x01, y0, z01, u1, vb, r * .85f, g * .85f, bl * .85f);
            b.v(x11, y1, z11, u1, 0, r, g, bl);
            b.fixTri();
            b.v(x00, y0, z00, u0, vb, r * .85f, g * .85f, bl * .85f);
            b.v(x11, y1, z11, u1, 0, r, g, bl);
            b.v(x10, y1, z10, u0, 0, r, g, bl);
            b.fixTri();
        }
    }

    private static void disc(GeoBuf b, float cx, float cz, float rad, float y, float r, float g, float bl, int sides) {
        for (int s = 0; s < sides; s++) {
            float a0 = (float) (2 * Math.PI * s / sides), a1 = (float) (2 * Math.PI * (s + 1) / sides);
            b.tri(cx, y, cz, cx + rad * (float) Math.cos(a0), y, cz + rad * (float) Math.sin(a0),
                    cx + rad * (float) Math.cos(a1), y, cz + rad * (float) Math.sin(a1), r, g, bl);
        }
    }

    private static void foot(TileData td, float[] xs, float[] zs) {
        Footprint f = new Footprint();
        f.xs = xs; f.zs = zs;
        f.minx = 1e9f; f.maxx = -1e9f; f.minz = 1e9f; f.maxz = -1e9f;
        for (int i = 0; i < xs.length; i++) {
            f.minx = Math.min(f.minx, xs[i]); f.maxx = Math.max(f.maxx, xs[i]);
            f.minz = Math.min(f.minz, zs[i]); f.maxz = Math.max(f.maxz, zs[i]);
        }
        Collision.add(td.grid, f);
    }

    private static float[] pol(float cx, float r, int n, float off, boolean xs, float cz) {
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            float a = off + (float) (2 * Math.PI * i / n);
            o[i] = xs ? cx + r * (float) Math.cos(a) : cz + r * (float) Math.sin(a);
        }
        return o;
    }

    /* ---------- برج خليفة ---------- */
    private static void burj(TileData td, float cx, float cz) {
        GeoBuf gl = td.bufs[TileBuilder.B_GLASS], pl = td.bufs[TileBuilder.B_PLAIN];
        disc(pl, cx, cz, 110f, 0.04f, 0.80f, 0.76f, 0.68f, 28);   // ساحة حجرية
        final float R = 0.74f, G = 0.82f, B = 0.90f;
        int N = 24;
        float top = 585f, step = top / N;
        for (int i = 0; i < N; i++) {
            float y0 = i * step, y1 = (i + 1) * step, t = i / (float) N;
            float L = 8f + 62f * (float) Math.pow(1f - t, 0.9f);
            if (i >= N - 3) L = 0;
            float W = Math.max(11f, 22f - 10f * t);
            if (L > 9f) {
                for (int k = 0; k < 3; k++) {
                    float a = (float) Math.toRadians(90 + 120 * k);
                    float ux = (float) Math.cos(a), uz = (float) Math.sin(a);
                    float[][] c = corners(cx + ux * L / 2f, cz + uz * L / 2f, a, L, W);
                    polyPrism(gl, c[0], c[1], y0, y1, R, G, B);
                }
            }
        }
        // النواة السداسية المتدرجة
        float[][] stages = {{0, 420, 15}, {420, 500, 11}, {500, 585, 7.5f}};
        for (float[] s : stages) {
            polyPrism(gl, pol(cx, s[2], 6, 0f, true, cz), pol(cx, s[2], 6, 0f, false, cz), s[0], s[1], R, G, B);
        }
        // الإبرة
        frustum(gl, cx, cz, 585f, 700f, 5f, 2.2f, 10, 0.86f, 0.87f, 0.89f);
        frustum(gl, cx, cz, 700f, 828f, 2.2f, 0.4f, 10, 0.9f, 0.9f, 0.92f);
        // التصادم عند القاعدة
        foot(td, pol(cx, 15f, 6, 0f, true, cz), pol(cx, 15f, 6, 0f, false, cz));
        for (int k = 0; k < 3; k++) {
            float a = (float) Math.toRadians(90 + 120 * k);
            float[][] c = corners(cx + (float) Math.cos(a) * 35f, cz + (float) Math.sin(a) * 35f, a, 70f, 22f);
            foot(td, c[0], c[1]);
        }
    }

    /* ---------- المدرج الروماني ---------- */
    private static void theatre(TileData td, float cx, float cz) {
        GeoBuf b = td.bufs[TileBuilder.B_PLAIN];
        final int SEG = 30;
        float hTop = ROW_H[ROWS - 1];
        double d2r = Math.PI / 180.0;
        for (int i = 0; i < ROWS; i++) {
            float r0 = ROW_R0[i], r1 = ROW_R1[i], h = ROW_H[i];
            for (int k = 0; k < SEG; k++) {
                float t0 = (float) ((-90 + 6 * k) * d2r), t1 = (float) ((-90 + 6 * (k + 1)) * d2r);
                boolean aisle = (k - 2) % 5 == 0;
                float v = 0.82f + 0.05f * (float) Math.sin(i * 1.7 + k * 0.9);
                float cr = v, cg = v * 0.92f, cb = v * 0.76f;
                if (aisle) { cr *= 0.72f; cg *= 0.72f; cb *= 0.72f; }
                float s0 = (float) Math.sin(t0), c0 = (float) Math.cos(t0), s1 = (float) Math.sin(t1), c1 = (float) Math.cos(t1);
                // السطح
                float x00 = cx + r0 * s0, z00 = cz + r0 * c0, x10 = cx + r1 * s0, z10 = cz + r1 * c0;
                float x11 = cx + r1 * s1, z11 = cz + r1 * c1, x01 = cx + r0 * s1, z01 = cz + r0 * c1;
                b.quad(x00, Terrain.h(x00, z00) + h, z00, x10, Terrain.h(x10, z10) + h, z10,
                        x11, Terrain.h(x11, z11) + h, z11, x01, Terrain.h(x01, z01) + h, z01, cr, cg, cb);
                // الحاجز الأمامي
                b.quad(cx + r0 * s0, h - 0.5f, cz + r0 * c0, cx + r0 * s1, h - 0.5f, cz + r0 * c1,
                        cx + r0 * s1, h, cz + r0 * c1, cx + r0 * s0, h, cz + r0 * c0, cr * 0.9f, cg * 0.9f, cb * 0.9f);
            }
            // الجدران الجانبية عند طرفي نصف الدائرة
            for (int side = -1; side <= 1; side += 2) {
                float s = side, c = 0f;
                b.quad(cx + r0 * s, 0, cz, cx + r1 * s, 0, cz, cx + r1 * s, h, cz, cx + r0 * s, h, cz, 0.72f, 0.66f, 0.54f);
            }
        }
        // الجدار الخلفي المنحني
        for (int k = 0; k < SEG; k++) {
            float t0 = (float) ((-90 + 6 * k) * d2r), t1 = (float) ((-90 + 6 * (k + 1)) * d2r);
            float xa = cx + RT_OUT * (float) Math.sin(t0), za = cz + RT_OUT * (float) Math.cos(t0);
            float xb = cx + RT_OUT * (float) Math.sin(t1), zb = cz + RT_OUT * (float) Math.cos(t1);
            b.quad(xa, 0, za, xb, 0, zb, xb, hTop + 3f, zb, xa, hTop + 3f, za, 0.68f, 0.62f, 0.50f);
        }
        // الأوركسترا
        for (int k = 0; k < SEG; k++) {
            float t0 = (float) ((-90 + 6 * k) * d2r), t1 = (float) ((-90 + 6 * (k + 1)) * d2r);
            b.tri(cx, 0.05f, cz, cx + 13f * (float) Math.sin(t0), 0.05f, cz + 13f * (float) Math.cos(t0),
                    cx + 13f * (float) Math.sin(t1), 0.05f, cz + 13f * (float) Math.cos(t1), 0.86f, 0.82f, 0.72f);
        }
        // مبنى المسرح: واجهة مقسمة إلى كتل حجرية مع بوابتين حقيقيتين قابلتين للعبور.
        float stageY = Terrain.h(cx, cz - 6f);
        b.box(cx, stageY + 0.8f, cz - 6f, 50f, 1.6f, 8f, 0.80f, 0.74f, 0.62f);
        float wallY = Terrain.h(cx, cz - 10.5f);
        b.box(cx - 19.5f, wallY + 6f, cz - 10.5f, 11f, 12f, 1.5f, 0.76f, 0.70f, 0.58f);
        b.box(cx, wallY + 6f, cz - 10.5f, 20f, 12f, 1.5f, 0.76f, 0.70f, 0.58f);
        b.box(cx + 19.5f, wallY + 6f, cz - 10.5f, 11f, 12f, 1.5f, 0.76f, 0.70f, 0.58f);
        // عتبات وأكتاف حجرية حول فتحتي الدخول، مع إبقاء الممرين مفتوحين.
        for (float gx : new float[]{-12f, 12f}) {
            b.box(cx + gx, wallY + 11.1f, cz - 10.5f, 4.2f, 1.8f, 1.5f, 0.82f, 0.76f, 0.64f);
            b.box(cx + gx - 2.1f, wallY + 5f, cz - 10.5f, 0.55f, 10f, 1.7f, 0.84f, 0.78f, 0.66f);
            b.box(cx + gx + 2.1f, wallY + 5f, cz - 10.5f, 0.55f, 10f, 1.7f, 0.84f, 0.78f, 0.66f);
        }
        for (int i = 0; i < 9; i++) {
            float px = -24f + 6f * i;
            if (Math.abs(px + 12f) < 0.1f || Math.abs(px - 12f) < 0.1f) continue;
            b.box(cx + px, wallY + 4.2f, cz - 8.5f, 0.9f, 8.4f, 0.9f, 0.84f, 0.78f, 0.66f);
        }
        b.box(cx, wallY + 9.2f, cz - 8.5f, 50f, 1.4f, 1.6f, 0.82f, 0.76f, 0.64f);
        // التصادم يطابق الكتل الصلبة فقط؛ فتحات البوابتين خالية.
        foot(td, new float[]{cx - 25f, cx - 14f, cx - 14f, cx - 25f}, new float[]{cz - 11.25f, cz - 11.25f, cz - 9.75f, cz - 9.75f});
        foot(td, new float[]{cx - 10f, cx + 10f, cx + 10f, cx - 10f}, new float[]{cz - 11.25f, cz - 11.25f, cz - 9.75f, cz - 9.75f});
        foot(td, new float[]{cx + 14f, cx + 25f, cx + 25f, cx + 14f}, new float[]{cz - 11.25f, cz - 11.25f, cz - 9.75f, cz - 9.75f});
        int n = SEG + 1;
        float[] fx = new float[n * 2], fz = new float[n * 2];
        for (int k = 0; k <= SEG; k++) {
            float t = (float) ((-90 + 6 * k) * d2r);
            fx[k] = cx + (RT_OUT + 0.3f) * (float) Math.sin(t); fz[k] = cz + (RT_OUT + 0.3f) * (float) Math.cos(t);
            fx[n * 2 - 1 - k] = cx + (RT_OUT - 1f) * (float) Math.sin(t); fz[n * 2 - 1 - k] = cz + (RT_OUT - 1f) * (float) Math.cos(t);
        }
        foot(td, fx, fz);
    }

    /* ---------- درجات TKTS الحمراء ---------- */
    private static void tkts(TileData td, float cx, float cz) {
        GeoBuf b = td.bufs[TileBuilder.B_PLAIN];
        for (int k = 0; k < 12; k++) {
            float h = (k + 1) * 0.3f, zf = cz - k * 0.9f, zb = cz - (k + 1) * 0.9f;
            b.quad(cx - 7f, h, zf, cx + 7f, h, zf, cx + 7f, h, zb, cx - 7f, h, zb, 0.82f, 0.08f, 0.10f);
            b.quad(cx - 7f, h - 0.3f, zf, cx + 7f, h - 0.3f, zf, cx + 7f, h, zf, cx - 7f, h, zf, 0.70f, 0.05f, 0.08f);
            b.quad(cx - 7f, 0, zf, cx - 7f, 0, zb, cx - 7f, h, zb, cx - 7f, h, zf, 0.6f, 0.05f, 0.07f);
            b.quad(cx + 7f, 0, zf, cx + 7f, 0, zb, cx + 7f, h, zb, cx + 7f, h, zf, 0.6f, 0.05f, 0.07f);
        }
    }
}
