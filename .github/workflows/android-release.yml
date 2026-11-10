package com.explorer.world3d;

/** يبني شبكة أرض (Terrain) لمربع واحد من الارتفاعات الحقيقية. */
final class TerrainMesh {
    static final int N = 32;

    private TerrainMesh() {}

    static GeoBuf build(int ix, int iz) { return build(ix, iz, false); }

    static GeoBuf build(int ix, int iz, boolean tabarbour) {
        double west = ix * Geo.dLon, east = (ix + 1) * Geo.dLon, south = iz * Geo.D_LAT, north = (iz + 1) * Geo.D_LAT;
        float xMin = Geo.x(west), xMax = Geo.x(east), zMin = Geo.z(north), zMax = Geo.z(south);
        int V = N + 1;
        float[] hx = new float[V * V], nx = new float[V * V], ny = new float[V * V], nz = new float[V * V];
        float dx = (xMax - xMin) / N, dz = (zMax - zMin) / N;
        for (int j = 0; j < V; j++) {
            for (int i = 0; i < V; i++) {
                float x = xMin + i * dx, z = zMin + j * dz;
                int k = j * V + i;
                hx[k] = Terrain.h(x, z);
                float hl = Terrain.h(x - 3f, z), hr = Terrain.h(x + 3f, z), hu = Terrain.h(x, z - 3f), hd = Terrain.h(x, z + 3f);
                float gx = (hl - hr) / 6f, gz = (hu - hd) / 6f;
                float l = (float) Math.sqrt(gx * gx + 1f + gz * gz);
                nx[k] = gx / l; ny[k] = 1f / l; nz[k] = gz / l;
            }
        }
        GeoBuf b = new GeoBuf();
        float sea = Terrain.seaY();
        int[][] cell = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                for (int[] c : cell) {
                    int ii = i + c[0], jj = j + c[1], k = jj * V + ii;
                    float x = xMin + ii * dx, z = zMin + jj * dz, y = hx[k];
                    float slope = 1f - ny[k];
                    float tone = 0.94f + 0.06f * (float) Math.sin(x * 0.21f) * (float) Math.sin(z * 0.17f);
                    float r = tabarbour ? 0.76f : 0.60f, g = tabarbour ? 0.65f : 0.56f, bl = tabarbour ? 0.43f : 0.46f;
                    float t = Math.min(1f, Math.max(0f, (slope - 0.10f) / 0.30f));
                    r = r * (1 - t) + (tabarbour ? 0.57f : 0.50f) * t; g = g * (1 - t) + (tabarbour ? 0.53f : 0.49f) * t; bl = bl * (1 - t) + 0.44f * t;
                    float near = Math.abs(y - sea);
                    if (near < 3f) {
                        float s = 1f - near / 3f;
                        r = r * (1 - s) + 0.83f * s; g = g * (1 - s) + 0.76f * s; bl = bl * (1 - s) + 0.58f * s;
                    }
                    b.vn(x, y, z, nx[k], ny[k], nz[k], x / 13.3333f, z / 13.3333f, r * tone, g * tone, bl * tone);
                }
            }
        }
        return b;
    }
}
