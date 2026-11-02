package com.explorer.world3d;

import java.util.Arrays;

final class Poly {
    private Poly() {}

    static float area(float[] xs, float[] zs) {
        float a = 0;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += xs[i] * zs[j] - xs[j] * zs[i];
        }
        return a / 2f;
    }

    static boolean contains(float[] xs, float[] zs, float x, float z) {
        boolean ins = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if (((zs[i] > z) != (zs[j] > z))
                    && (x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i])) ins = !ins;
        }
        return ins;
    }

    private static boolean inTri(float px, float pz, float ax, float az, float bx, float bz, float cx, float cz) {
        float d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
        float d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
        float d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
        boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(neg && pos);
    }

    /** تقسيم مضلع بسيط إلى مثلثات (قص الآذان). يعيد فهارس ثلاثية. */
    static int[] earClip(float[] px, float[] pz) {
        int n = px.length;
        if (n < 3) return new int[0];
        int[] V = new int[n];
        if (area(px, pz) > 0) { for (int i = 0; i < n; i++) V[i] = i; }
        else { for (int i = 0; i < n; i++) V[i] = n - 1 - i; }
        int[] out = new int[(n - 2) * 3];
        int oc = 0, cnt = n;
        while (cnt > 3) {
            boolean cut = false;
            for (int k = 0; k < cnt; k++) {
                int a = V[(k + cnt - 1) % cnt], b = V[k], c = V[(k + 1) % cnt];
                float cross = (px[b] - px[a]) * (pz[c] - pz[a]) - (pz[b] - pz[a]) * (px[c] - px[a]);
                if (cross <= 1e-6f) continue;
                boolean ok = true;
                for (int m = 0; m < cnt; m++) {
                    int q = V[m];
                    if (q == a || q == b || q == c) continue;
                    if (inTri(px[q], pz[q], px[a], pz[a], px[b], pz[b], px[c], pz[c])) { ok = false; break; }
                }
                if (!ok) continue;
                out[oc++] = a; out[oc++] = b; out[oc++] = c;
                System.arraycopy(V, k + 1, V, k, cnt - k - 1);
                cnt--;
                cut = true;
                break;
            }
            if (!cut) break;
        }
        for (int k = 1; k + 1 < cnt; k++) {
            out[oc++] = V[0]; out[oc++] = V[k]; out[oc++] = V[k + 1];
        }
        return Arrays.copyOf(out, oc);
    }
}
