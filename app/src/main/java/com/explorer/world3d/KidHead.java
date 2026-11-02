package com.explorer.world3d;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** يحمّل رأس الطفل المنحوت من assets/kid_head.bin (رؤوس + عمودي + لون + فهارس). */
final class KidHead {
    private KidHead() {}

    static Mesh load(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("kid_head.bin");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            ByteBuffer bb = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            int nv = bb.getInt(), ni = bb.getInt();
            float[] v = new float[nv * 9];
            for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
            GeoBuf g = new GeoBuf();
            for (int i = 0; i < ni; i++) {
                int k = bb.getInt() * 9;
                g.vn(v[k], v[k + 1], v[k + 2], v[k + 3], v[k + 4], v[k + 5], 0.5f, 0.5f, v[k + 6], v[k + 7], v[k + 8]);
            }
            return Mesh.from(g);
        } catch (Exception e) {
            return null;
        }
    }
}
