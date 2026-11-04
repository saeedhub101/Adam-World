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

    // Robinsons Place Manila — photo-guided landmark reconstruction.
    static final double ROBINSONS_LAT = 14.57644165, ROBINSONS_LON = 120.98379392;
    private static final float ROBINSONS_RX = 78f, ROBINSONS_RZ = 49f;

    private static boolean insideRobinsons(float x, float z) {
        float dx = x - Geo.x(ROBINSONS_LON), dz = z - Geo.z(ROBINSONS_LAT);
        float a = dx / ROBINSONS_RX, b = dz / ROBINSONS_RZ;
        return a * a + b * b <= 1.10f;
    }

    static boolean suppress(float x, float z) {
        return near(x, z, BURJ_LAT, BURJ_LON, 45f)
                || near(x, z, RT_LAT, RT_LON, 75f)
                || insideRobinsons(x, z);
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
        x = Geo.x(ROBINSONS_LON); z = Geo.z(ROBINSONS_LAT);
        if (x >= xMin && x < xMax && z >= zMin && z < zMax) {
            robinsonsPlace(td, x, z);
        }
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
                float bx0 = cx + r0 * s0, bz0 = cz + r0 * c0, bx1 = cx + r0 * s1, bz1 = cz + r0 * c1;
                b.quad(bx0, Terrain.h(bx0, bz0) + h - 0.5f, bz0, bx1, Terrain.h(bx1, bz1) + h - 0.5f, bz1,
                        bx1, Terrain.h(bx1, bz1) + h, bz1, bx0, Terrain.h(bx0, bz0) + h, bz0, cr * 0.9f, cg * 0.9f, cb * 0.9f);
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
            float ya = Terrain.h(xa, za), yb = Terrain.h(xb, zb);
            b.quad(xa, ya, za, xb, yb, zb, xb, yb + hTop + 3f, zb, xa, ya + hTop + 3f, za, 0.68f, 0.62f, 0.50f);
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

    /* ---------- Robinsons Place Manila: curved multi-level shopping mall ---------- */
    private static float[] ellipsePoint(float cx, float cz, float rx, float rz, float a) {
        return new float[]{cx + rx * (float)Math.cos(a), cz + rz * (float)Math.sin(a)};
    }

    private static void mallCylinder(GeoBuf b, float cx, float cz, float y0, float y1,
                                     float radius, float r, float g, float bl) {
        final int sides = 10;
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
            float x0 = cx + radius * (float)Math.cos(a0), z0 = cz + radius * (float)Math.sin(a0);
            float x1 = cx + radius * (float)Math.cos(a1), z1 = cz + radius * (float)Math.sin(a1);
            b.quad(x0,y0,z0, x1,y0,z1, x1,y1,z1, x0,y1,z0, r,g,bl);
        }
    }

    private static void mallDecal(TileData td, String text, float x, float z, float y, float scale) {
        Decal d = new Decal();
        d.text = text;
        d.x = x; d.z = z; d.y = y;
        d.dx = scale; d.dz = 0f; d.vertical = true;
        td.decals.add(d);
    }

    private static void mallChair(GeoBuf b, float x, float z, float y, float angle) {
        float ux = (float)Math.cos(angle), uz = (float)Math.sin(angle);
        float wx = -uz, wz = ux;
        // Timber-look seat and back, with four slim metal legs.
        b.box(x, y + 0.48f, z, 0.72f, 0.10f, 0.72f, 0.43f, 0.31f, 0.22f);
        b.box(x - ux*0.30f, y + 0.91f, z - uz*0.30f, 0.10f, 0.78f, 0.72f, 0.48f, 0.36f, 0.25f);
        for (int sx=-1; sx<=1; sx+=2) for (int sz=-1; sz<=1; sz+=2) {
            float lx=x+ux*sx*0.25f+wx*sz*0.25f, lz=z+uz*sx*0.25f+wz*sz*0.25f;
            b.box(lx,y+0.22f,lz,0.055f,0.46f,0.055f,0.28f,0.30f,0.31f);
        }
    }

    private static void robinsonsPlace(TileData td, float cx, float cz) {
        GeoBuf wall = td.bufs[TileBuilder.B_FACADE];
        GeoBuf glass = td.bufs[TileBuilder.B_GLASS];
        GeoBuf plaza = td.bufs[TileBuilder.B_PLAIN];
        GeoBuf signs = td.bufs[TileBuilder.B_SIGN];
        final int segments = 64;
        final float rx = ROBINSONS_RX, rz = ROBINSONS_RZ;
        final float base = Terrain.h(cx, cz);
        final float concreteR=.79f, concreteG=.78f, concreteB=.75f;
        final float trimR=.91f, trimG=.89f, trimB=.84f;

        // A real-footprint-scale oval mass with a broad, south-facing curved frontage.
        float[] fx=new float[segments], fz=new float[segments];
        for(int i=0;i<segments;i++){
            float a=(float)(2*Math.PI*i/segments);
            float[] p=ellipsePoint(cx,cz,rx,rz,a); fx[i]=p[0]; fz[i]=p[1];
        }
        foot(td,fx,fz);

        // Continuous four-storey exterior shell and inset dark glazing bands.
        for(int i=0;i<segments;i++){
            float a0=(float)(2*Math.PI*i/segments), a1=(float)(2*Math.PI*(i+1)/segments);
            float[] p0=ellipsePoint(cx,cz,rx,rz,a0), p1=ellipsePoint(cx,cz,rx,rz,a1);
            float x0=p0[0],z0=p0[1],x1=p1[0],z1=p1[1];
            float yGround=(Terrain.h(x0,z0)+Terrain.h(x1,z1))*.5f;
            // Ground-floor shop glazing and upper window ribbons.
            wall.quad(x0,yGround,z0,x1,yGround,z1,x1,yGround+4.45f,z1,x0,yGround+4.45f,z0,
                    concreteR*.92f,concreteG*.92f,concreteB*.92f);
            glass.quad(x0,yGround+.72f,z0,x1,yGround+.72f,z1,x1,yGround+3.95f,z1,x0,yGround+3.95f,z0,
                    .19f,.31f,.36f);
            for(int floor=1;floor<=3;floor++){
                float y0=yGround+4.55f+(floor-1)*4.35f, y1=y0+3.15f;
                wall.quad(x0,y0-.28f,z0,x1,y0-.28f,z1,x1,y0,z1,x0,y0,z0,trimR,trimG,trimB);
                glass.quad(x0,y0+.22f,z0,x1,y0+.22f,z1,x1,y1,z1,x0,y1,z0,.30f,.46f,.52f);
                wall.quad(x0,y1,z0,x1,y1,z1,x1,y1+.28f,z1,x0,y1+.28f,z0,trimR,trimG,trimB);
            }
            // Heavy horizontal balcony slabs and parapet at each storey.
            for(int floor=1;floor<=3;floor++){
                float y=yGround+4.45f+floor*4.35f;
                plaza.quad(x0,y,z0,x1,y,z1,
                        cx+(rx-1.2f)*(float)Math.cos(a1),y,cz+(rz-1.2f)*(float)Math.sin(a1),
                        cx+(rx-1.2f)*(float)Math.cos(a0),y,cz+(rz-1.2f)*(float)Math.sin(a0),
                        .78f,.77f,.73f);
                // Glass balustrade with a narrow silver top rail.
                float rox=rx+.52f, roz=rz+.52f;
                float[] r0=ellipsePoint(cx,cz,rox,roz,a0),r1=ellipsePoint(cx,cz,rox,roz,a1);
                glass.quad(r0[0],y+.18f,r0[1],r1[0],y+.18f,r1[1],
                        r1[0],y+1.12f,r1[1],r0[0],y+1.12f,r0[1],.58f,.72f,.74f);
                wall.quad(r0[0],y+1.10f,r0[1],r1[0],y+1.10f,r1[1],
                        r1[0],y+1.22f,r1[1],r0[0],y+1.22f,r0[1],.70f,.71f,.69f);
            }
        }

        // White cylindrical columns on the curved front colonnade, spaced like the photo reference.
        for(int i=0;i<=20;i++){
            float a=(float)(Math.PI*i/20);
            float[] p=ellipsePoint(cx,cz,rx+1.15f,rz+1.15f,a);
            mallCylinder(plaza,p[0],p[1],base+.15f,base+22.7f,.72f,.90f,.89f,.84f);
            // Column capital and base collars.
            plaza.box(p[0],base+22.35f,p[1],1.72f,.30f,1.72f,.84f,.83f,.78f);
            plaza.box(p[0],base+.35f,p[1],1.65f,.38f,1.65f,.78f,.77f,.72f);
        }

        // Roof parapet, mechanical penthouses and long skylight strips.
        for(int i=0;i<segments;i++){
            float a0=(float)(2*Math.PI*i/segments),a1=(float)(2*Math.PI*(i+1)/segments);
            float[] p0=ellipsePoint(cx,cz,rx,rz,a0),p1=ellipsePoint(cx,cz,rx,rz,a1);
            wall.quad(p0[0],base+22.0f,p0[1],p1[0],base+22.0f,p1[1],
                    p1[0],base+23.0f,p1[1],p0[0],base+23.0f,p0[1],.88f,.86f,.81f);
        }
        for(int i=0;i<5;i++){
            float x=cx-35f+i*17.5f, z=cz-9f+(i%2)*18f;
            plaza.box(x,base+24.0f,z,9f,2.0f,7f,.70f,.70f,.67f);
            glass.box(x,base+25.1f,z,8.2f,.25f,6.2f,.32f,.45f,.49f);
        }

        // Sweeping cream entrance canopy and recessed glazed doors on the south-facing front.
        plaza.box(cx,base+4.8f,cz+rz+5.0f,54f,.82f,9.5f,.83f,.81f,.75f);
        plaza.box(cx,base+4.30f,cz+rz+5.0f,50f,.18f,8.8f,.40f,.42f,.41f);
        for(int i=-4;i<=4;i+=2) mallCylinder(plaza,cx+i*5.3f,cz+rz+7.1f,base+.15f,base+4.55f,.20f,.88f,.87f,.81f);
        // Entrance glass doors, mullions and handles.
        for(int i=-4;i<=4;i++){
            float x=cx+i*2.2f, z=cz+rz+1.1f;
            glass.quad(x-.98f,base+.25f,z,x+.98f,base+.25f,z,x+.98f,base+4.05f,z,x-.98f,base+4.05f,z,
                    .16f,.28f,.32f);
            plaza.box(x-.98f,base+2.12f,z-.06f,.10f,3.9f,.12f,.67f,.69f,.67f);
            plaza.box(x+.98f,base+2.12f,z-.06f,.10f,3.9f,.12f,.67f,.69f,.67f);
        }

        // Large red Robinsons rooftop signboard and raised white emblem, matching the photo silhouette.
        float signZ=cz+rz+2.0f, signY=base+25.4f;
        signs.quad(cx-25f,signY,signZ,cx+25f,signY,signZ,
                cx+25f,signY+5.3f,signZ,cx-25f,signY+5.3f,signZ,.70f,.035f,.10f);
        signs.quad(cx-25.5f,signY,signZ+.16f,cx-25f,signY,signZ+.16f,
                cx-25f,signY+5.3f,signZ+.16f,cx-25.5f,signY+5.3f,signZ+.16f,.42f,.02f,.06f);
        // White ribbon-like Robinsons mark, built as raised strokes rather than a flat texture.
        float lx=cx-18.5f, lz=signZ+.28f;
        signs.quad(lx-2.8f,signY+.8f,lz,lx-1.9f,signY+.8f,lz,
                lx+2.7f,signY+4.45f,lz,lx+1.7f,signY+4.45f,lz,.98f,.97f,.92f);
        signs.quad(lx+1.7f,signY+4.45f,lz,lx+2.7f,signY+4.45f,lz,
                lx+1.2f,signY+2.5f,lz,lx+.3f,signY+2.5f,lz,.98f,.97f,.92f);
        signs.quad(lx+.3f,signY+2.5f,lz,lx+1.2f,signY+2.5f,lz,
                lx+3.6f,signY+.8f,lz,lx+2.6f,signY+.8f,lz,.98f,.97f,.92f);
        mallDecal(td,"ROBINSONS",cx+5.5f,signZ+.32f,signY+3.15f,1f);
        mallDecal(td,"PLACE MANILA",cx+5.5f,signZ+.32f,signY+1.35f,1f);

        // Retail fascia panels and names visible in the available facade photographs.
        String[] shops={"UNIQLO","HUAWEI","THE COFFEE BEAN & TEA LEAF","YAKIMIX","SUSHI TEI","GUMBO"};
        float[] shopAngles={.28f,.57f,.88f,1.20f,1.57f,1.91f};
        for(int i=0;i<shops.length;i++){
            float a=shopAngles[i], x=cx+rx*(float)Math.cos(a), z=cz+rz*(float)Math.sin(a);
            float tx=x*.0f+cx+(x-cx)*.985f, tz=cz+(z-cz)*1.02f;
            plaza.box(x,base+5.0f,z,10.5f,.82f,.28f,.16f,.20f,.22f);
            mallDecal(td,shops[i],tx,tz,base+4.72f,1f);
        }

        // Planters, low benches and outdoor chairs in the entrance forecourt.
        for(int i=-3;i<=3;i++){
            float px=cx+i*15f, pz=cz+rz+17f;
            plaza.box(px,base+.48f,pz,4.2f,.95f,2.0f,.55f,.53f,.47f);
            plaza.box(px,base+1.05f,pz,3.7f,.18f,1.55f,.28f,.36f,.22f);
            mallCylinder(plaza,px, pz,base+1.0f,base+3.4f,.13f,.34f,.43f,.27f);
            for(int k=0;k<5;k++){
                float a=(float)(k*2*Math.PI/5);
                float[] leaf=ellipsePoint(px,pz,1.05f,.85f,a);
                mallCylinder(plaza,leaf[0],leaf[1],base+2.0f,base+3.5f,.045f,.24f,.39f,.22f);
            }
        }
        for(int i=-2;i<=2;i++){
            float tx=cx+i*11f, tz=cz+rz+27f;
            plaza.box(tx,base+.78f,tz,.95f,.12f,.95f,.34f,.25f,.16f);
            for(int k=0;k<4;k++){
                float a=(float)(k*Math.PI/2);
                mallChair(plaza,tx+2.1f*(float)Math.cos(a),tz+2.1f*(float)Math.sin(a),base, a);
            }
        }
    }

}
