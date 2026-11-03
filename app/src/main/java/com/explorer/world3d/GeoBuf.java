package com.explorer.world3d;

import java.util.Arrays;

/** مخزن رؤوس: موضع(3) + عمودي(3) + UV(2) + لون(3) = 11 رقم لكل رأس. */
final class GeoBuf {
    static final int STRIDE = 11;
    float[] a = new float[512];
    int n = 0;

    private void ensure(int k) {
        if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k));
    }

    int count() { return n / STRIDE; }

    /** Release the large CPU vertex array immediately after its VBO has been uploaded. */
    void releaseStorage() {
        a = new float[0];
        n = 0;
    }


    void v(float x, float y, float z, float u, float v, float r, float g, float b) {
        ensure(STRIDE);
        a[n++] = x; a[n++] = y; a[n++] = z;
        a[n++] = 0; a[n++] = 1; a[n++] = 0;
        a[n++] = u; a[n++] = v;
        a[n++] = r; a[n++] = g; a[n++] = b;
    }

    void vn(float x, float y, float z, float nx, float ny, float nz, float u, float v, float r, float g, float b) {
        ensure(STRIDE);
        a[n++] = x; a[n++] = y; a[n++] = z;
        a[n++] = nx; a[n++] = ny; a[n++] = nz;
        a[n++] = u; a[n++] = v;
        a[n++] = r; a[n++] = g; a[n++] = b;
    }

    void shiftY(int fromIndex, float dy) {
        for (int i = fromIndex; i + 1 < n; i += STRIDE) a[i + 1] += dy;
    }

    /** يحسب العمودي لآخر ثلاثة رؤوس. */
    void fixTri() {
        int i2 = n - STRIDE, i1 = i2 - STRIDE, i0 = i1 - STRIDE;
        float ax = a[i1] - a[i0], ay = a[i1 + 1] - a[i0 + 1], az = a[i1 + 2] - a[i0 + 2];
        float bx = a[i2] - a[i0], by = a[i2 + 1] - a[i0 + 1], bz = a[i2 + 2] - a[i0 + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9f) { nx = 0; ny = 1; nz = 0; } else { nx /= l; ny /= l; nz /= l; }
        for (int k = 0; k < 3; k++) {
            int o = i0 + k * STRIDE + 3;
            a[o] = nx; a[o + 1] = ny; a[o + 2] = nz;
        }
    }

    void quad(float ax, float ay, float az, float bx, float by, float bz,
              float cx, float cy, float cz, float dx, float dy, float dz,
              float r, float g, float b) {
        v(ax, ay, az, .5f, .5f, r, g, b); v(bx, by, bz, .5f, .5f, r, g, b); v(cx, cy, cz, .5f, .5f, r, g, b); fixTri();
        v(ax, ay, az, .5f, .5f, r, g, b); v(cx, cy, cz, .5f, .5f, r, g, b); v(dx, dy, dz, .5f, .5f, r, g, b); fixTri();
    }

    void box(float cx, float cy, float cz, float sx, float sy, float sz, float r, float g, float b) {
        float x0 = cx - sx / 2, x1 = cx + sx / 2, y0 = cy - sy / 2, y1 = cy + sy / 2, z0 = cz - sz / 2, z1 = cz + sz / 2;
        quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b);
        quad(x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, r, g, b);
        quad(x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, r, g, b);
        quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b);
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b);
    }

    void tri(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
             float r, float g, float b) {
        v(ax, ay, az, .5f, .5f, r, g, b); v(bx, by, bz, .5f, .5f, r, g, b); v(cx, cy, cz, .5f, .5f, r, g, b); fixTri();
    }
}
