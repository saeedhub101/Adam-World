package com.explorer.world3d;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.Random;

/** Runtime-generated independent PBR maps and a sky/ground environment cubemap. */
final class ProceduralMaterialMaps {
    final int normal;
    final int roughness;
    final int metalness;
    final int environment;

    private ProceduralMaterialMaps() {
        normal = upload(normalBitmap());
        roughness = upload(scalarBitmap(0.68f, 0.16f, 41));
        metalness = upload(scalarBitmap(0.22f, 0.10f, 73));
        environment = createEnvironment();
    }

    static ProceduralMaterialMaps create() { return new ProceduralMaterialMaps(); }

    private static int upload(Bitmap bitmap) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        bitmap.recycle();
        return id[0];
    }

    private static Bitmap normalBitmap() {
        int size = 128;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Random random = new Random(19);
        int[] pixels = new int[size * size];
        float[][] h = new float[size + 2][size + 2];
        for (int y = 0; y < size + 2; y++)
            for (int x = 0; x < size + 2; x++)
                h[y][x] = (float) (random.nextDouble() * 0.22 + 0.39 * Math.sin(x * 0.19) * Math.cos(y * 0.17));
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float dx = (h[y + 1][x + 2] - h[y + 1][x]) * 2.4f;
            float dy = (h[y + 2][x + 1] - h[y][x + 1]) * 2.4f;
            float nx = -dx, ny = -dy, nz = 1f;
            float inv = 1f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            int r = clamp((int) ((nx * inv * 0.5f + 0.5f) * 255));
            int g = clamp((int) ((ny * inv * 0.5f + 0.5f) * 255));
            int bl = clamp((int) ((nz * inv * 0.5f + 0.5f) * 255));
            pixels[y * size + x] = 0xff000000 | r << 16 | g << 8 | bl;
        }
        b.setPixels(pixels, 0, size, 0, 0, size, size);
        return b;
    }

    private static Bitmap scalarBitmap(float mean, float variation, int seed) {
        int size = 128;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Random random = new Random(seed);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float wave = 0.5f + 0.25f * (float) Math.sin(x * 0.11) * (float) Math.cos(y * 0.13);
            int v = clamp((int) ((mean + (wave - 0.5f) * variation + (random.nextFloat() - 0.5f) * variation) * 255));
            pixels[y * size + x] = 0xff000000 | v << 16 | v << 8 | v;
        }
        b.setPixels(pixels, 0, size, 0, 0, size, size);
        return b;
    }

    void updateEnvironment(float topR,float topG,float topB,
                            float horR,float horG,float horB,
                            float fogR,float fogG,float fogB) {
        float[][] colors = {
                {horR,horG,horB},{horR,horG,horB},{topR,topG,topB},
                {fogR*.55f,fogG*.55f,fogB*.55f},{horR,horG,horB},{horR,horG,horB}
        };
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP,environment);
        int size=16;
        for(int face=0;face<6;face++) {
            Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            int[] pixels=new int[size*size];
            for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
                float k=face==2 ? .70f+.30f*y/(size-1f) : face==3 ? .55f+.45f*y/(size-1f) : .78f+.22f*y/(size-1f);
                int r=clamp((int)(colors[face][0]*k*255f));
                int g=clamp((int)(colors[face][1]*k*255f));
                int b=clamp((int)(colors[face][2]*k*255f));
                pixels[y*size+x]=0xff000000|r<<16|g<<8|b;
            }
            bitmap.setPixels(pixels,0,size,0,0,size,size);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X+face,0,bitmap,0);
            bitmap.recycle();
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private static int createEnvironment() {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, id[0]);
        int[][] colors = {
                {92, 139, 183}, {92, 139, 183}, {135, 177, 209},
                {66, 82, 91}, {112, 157, 194}, {112, 157, 194}
        };
        for (int face = 0; face < 6; face++) {
            int size = 16;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                float horizon = y / (float) (size - 1);
                float k = face == 2 ? 0.78f + 0.22f * horizon : 0.72f + 0.28f * horizon;
                int r = clamp((int) (colors[face][0] * k));
                int g = clamp((int) (colors[face][1] * k));
                int bl = clamp((int) (colors[face][2] * k));
                pixels[y * size + x] = 0xff000000 | r << 16 | g << 8 | bl;
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, bitmap, 0);
            bitmap.recycle();
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    private static int clamp(int x) { return Math.max(0, Math.min(255, x)); }
}