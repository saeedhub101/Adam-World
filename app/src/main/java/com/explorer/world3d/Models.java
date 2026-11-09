package com.explorer.world3d;

import java.util.ArrayList;
import java.util.HashMap;

final class Footprint {
    float[] xs, zs;
    float minx, maxx, minz, maxz;
    float obstacleHeight = Float.POSITIVE_INFINITY;
}

final class Decal {
    String text;
    float x, z, dx, dz, y = Float.NaN;
    boolean vertical;
}

final class RoadPath {
    String name = "";
    final float[] xs, zs, cum;
    final float total, w;
    final boolean veh;

    RoadPath(float[] xs, float[] zs, float w, boolean veh) {
        this.xs = xs; this.zs = zs; this.w = w; this.veh = veh;
        cum = new float[xs.length];
        for (int i = 1; i < xs.length; i++) {
            cum[i] = cum[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        total = cum[xs.length - 1];
    }

    /** out = {x, z, dx, dz}  مع إزاحة جانبية يمين اتجاه السير. */
    void posAt(float s, float off, float[] out) {
        if (s < 0) s = 0;
        if (s > total) s = total;
        int i = 1;
        while (i < xs.length - 1 && cum[i] < s) i++;
        float L = Math.max(0.0001f, cum[i] - cum[i - 1]);
        float t = (s - cum[i - 1]) / L;
        float dx = (xs[i] - xs[i - 1]) / L, dz = (zs[i] - zs[i - 1]) / L;
        out[0] = xs[i - 1] + (xs[i] - xs[i - 1]) * t - dz * off;
        out[1] = zs[i - 1] + (zs[i] - zs[i - 1]) * t + dx * off;
        out[2] = dx;
        out[3] = dz;
    }
}

/** نتيجة بناء مربع على الخيط الخلفي (بيانات CPU فقط). */
final class TileData {
    static final int N_BUFS = 8;
    final int ix, iz;
    int gen;
    final GeoBuf[] bufs = new GeoBuf[N_BUFS];
    final HashMap<Long, ArrayList<Footprint>> grid = new HashMap<>();
    final ArrayList<RoadPath> paths = new ArrayList<>();
    final ArrayList<Decal> decals = new ArrayList<>();
    boolean hasCoast;
    boolean manila;

    TileData(int ix, int iz) {
        this.ix = ix; this.iz = iz;
        for (int i = 0; i < N_BUFS; i++) bufs[i] = new GeoBuf();
    }
}

final class Collision {
    static final float CELL = 40f;

    static long ck(int cx, int cz) { return (((long) cx) << 32) | (cz & 0xffffffffL); }

    static void add(HashMap<Long, ArrayList<Footprint>> grid, Footprint f) {
        int x0 = (int) Math.floor(f.minx / CELL), x1 = (int) Math.floor(f.maxx / CELL);
        int z0 = (int) Math.floor(f.minz / CELL), z1 = (int) Math.floor(f.maxz / CELL);
        for (int i = x0; i <= x1; i++) {
            for (int j = z0; j <= z1; j++) {
                long k = ck(i, j);
                ArrayList<Footprint> l = grid.get(k);
                if (l == null) { l = new ArrayList<>(); grid.put(k, l); }
                l.add(f);
            }
        }
    }

    static boolean hit(HashMap<Long, ArrayList<Footprint>> grid, float x, float z) {
        return hit(grid, x, z, 0f);
    }

    static boolean hit(HashMap<Long, ArrayList<Footprint>> grid, float x, float z, float clearance) {
        ArrayList<Footprint> l = grid.get(ck((int) Math.floor(x / CELL), (int) Math.floor(z / CELL)));
        if (l == null) return false;
        for (Footprint f : l) {
            if (f.obstacleHeight <= clearance) continue;
            if (x < f.minx || x > f.maxx || z < f.minz || z > f.maxz) continue;
            if (Poly.contains(f.xs, f.zs, x, z)) return true;
        }
        return false;
    }

    /** Highest low step/curb surface at this point; tall buildings are excluded. */
    static float lowObstacleHeightAt(HashMap<Long, ArrayList<Footprint>> grid, float x, float z, float maxStep) {
        ArrayList<Footprint> l = grid.get(ck((int) Math.floor(x / CELL), (int) Math.floor(z / CELL)));
        if (l == null) return 0f;
        float highest = 0f;
        for (Footprint f : l) {
            if (f.obstacleHeight <= 0f || f.obstacleHeight > maxStep) continue;
            if (x < f.minx || x > f.maxx || z < f.minz || z > f.maxz) continue;
            if (Poly.contains(f.xs, f.zs, x, z)) highest = Math.max(highest, f.obstacleHeight);
        }
        return highest;
    }
}
