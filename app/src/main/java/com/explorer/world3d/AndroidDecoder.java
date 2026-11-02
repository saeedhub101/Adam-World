package com.explorer.world3d;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;

/** فكّ صورة Terrarium: الارتفاع = R*256 + G + B/256 - 32768 (متر). */
final class AndroidDecoder implements Terrain.Decoder {
    @Override public float[] decode(byte[] png) throws IOException {
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (b == null) throw new IOException("decode failed");
        int w = b.getWidth(), h = b.getHeight();
        if (w != 256 || h != 256) { b.recycle(); throw new IOException("unexpected DEM size"); }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        float[] out = new float[w * h];
        for (int i = 0; i < out.length; i++) {
            int c = px[i];
            out[i] = ((c >> 16) & 255) * 256f + ((c >> 8) & 255) + (c & 255) / 256f - 32768f;
        }
        return out;
    }
}
