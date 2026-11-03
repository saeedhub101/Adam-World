package com.explorer.world3d;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.Random;

/** تكستشرات إجرائية (نوافذ، زجاج، أسفلت، رصيف) تُرسم برمجياً. */
final class Textures {
    int sign, facade, facadeEm, glass, glassEm, roadW, roadN, sidewalk, ground, white, black;

    private boolean tabarbourProfile;
    Textures() { rebuild(false); }
    void setTabarbourProfile(boolean enabled) {
        if (tabarbourProfile == enabled) return;
        deleteTextures();
        rebuild(enabled);
    }
    private void rebuild(boolean tabarbour) {
        tabarbourProfile = tabarbour;
        facade = upload(facadeBmp(tabarbour));
        facadeEm = upload(facadeEmBmp());
        glass = upload(glassBmp(tabarbour));
        glassEm = upload(glassEmBmp());
        roadW = upload(roadBmp(true, tabarbour));
        roadN = upload(roadBmp(false, tabarbour));
        sidewalk = upload(sidewalkBmp(tabarbour));
        ground = upload(groundBmp(tabarbour));
        sign = upload(signBmp());
        white = upload(solid(0xffffffff));
        black = upload(solid(0xff000000));
    }
    private void deleteTextures() {
        int[] ids = {facade, facadeEm, glass, glassEm, roadW, roadN, sidewalk, ground, sign, white, black};
        for (int id : ids) if (id != 0) GLES20.glDeleteTextures(1, new int[]{id}, 0);
    }

    static int upload(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        b.recycle();
        return t[0];
    }

    private static Bitmap signBmp() {
        Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(31);
        int[][] pal = {{0xffe91e63, 0xff2196f3}, {0xffffc107, 0xffd32f2f}, {0xff00bcd4, 0xff4a148c},
                {0xff76ff03, 0xff006064}, {0xffff5722, 0xff311b92}, {0xfffafafa, 0xffe53935}};
        String[] words = {"BROADWAY", "LIVE", "SHOW", "NEW YORK", "SALE", "TIMES SQ", "OPEN 24H", "TICKETS",
                "MUSIC", "NEON", "HOTEL", "FOOD", "MOVIES", "NEWS", "FASHION", "PLAY"};
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 16; i++) {
            int x = (i % 4) * 128, y = (i / 4) * 128;
            int[] pc = pal[r.nextInt(pal.length)];
            p.setShader(new LinearGradient(x, y, x + 128, y + 128, pc[0], pc[1], Shader.TileMode.CLAMP));
            c.drawRect(x, y, x + 128, y + 128, p);
            p.setShader(null);
            p.setColor(0x44ffffff);
            c.drawCircle(x + 30 + r.nextInt(70), y + 25 + r.nextInt(30), 20 + r.nextInt(25), p);
            p.setColor(0xffffffff);
            p.setTextSize(22);
            c.drawText(words[i], x + 64, y + 76, p);
            p.setColor(0xff000000);
            c.drawRect(x, y, x + 128, y + 3, p);
            c.drawRect(x, y + 125, x + 128, y + 128, p);
        }
        return b;
    }

    private static Bitmap solid(int color) {
        Bitmap b = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        return b;
    }

    private static void noise(Canvas c, Paint p, Random r, int w, int h, int n, float a) {
        p.setShader(null);
        for (int i = 0; i < n; i++) {
            p.setColor(Color.argb((int) (r.nextFloat() * a * 255), 0, 0, 0));
            float x = r.nextFloat() * w, y = r.nextFloat() * h;
            c.drawRect(x, y, x + 1 + r.nextFloat() * 5, y + 1 + r.nextFloat() * 5, p);
        }
    }

    private static Bitmap facadeBmp(boolean tabarbour) {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(7);
        c.drawColor(tabarbour ? 0xffeee3cd : 0xfff4f4f4);
        if (tabarbour) {
            noise(c, p, r, 256, 256, 2600, 0.035f);
            p.setShader(null);
            for (int y = 0; y <= 256; y += 32) {
                p.setColor(0x397b705e);
                c.drawRect(0, y, 256, y + 1.4f, p);
                int offset = ((y / 32) % 2) * 27;
                for (int x = offset; x <= 256; x += 54) {
                    p.setColor(0x287b705e);
                    c.drawRect(x, y, x + 1.2f, Math.min(256, y + 32), p);
                }
            }
            return b;
        }
        noise(c, p, r, 256, 256, 1600, 0.06f);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int x = col * 64, y = row * 64;
                p.setShader(null);
                p.setColor(0x1f000000);
                c.drawRect(x, y + 61, x + 64, y + 64, p);
                int wx = x + 13, wy = y + 11, ww = 38, wh = 40;
                p.setColor(0xffdcdcdc);
                c.drawRect(wx - 3, wy - 3, wx + ww + 3, wy + wh + 3, p);
                if (r.nextFloat() < 0.18f) {
                    p.setShader(new LinearGradient(0, wy, 0, wy + wh, new int[]{0xffcdbf9f, 0xffa8987a}, null, Shader.TileMode.CLAMP));
                } else {
                    p.setShader(new LinearGradient(0, wy, 0, wy + wh, new int[]{0xff6b8aa6, 0xff33475c, 0xff1d2a38},
                            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                }
                c.drawRect(wx, wy, wx + ww, wy + wh, p);
                p.setShader(null);
                p.setColor(0xffececec);
                c.drawRect(wx + ww / 2 - 1, wy, wx + ww / 2 + 1, wy + wh, p);
                p.setColor(0xffb9b9b9);
                c.drawRect(wx - 5, wy + wh + 3, wx + ww + 5, wy + wh + 6, p);
            }
        }
        return b;
    }

    private static Bitmap facadeEmBmp() {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(11);
        c.drawColor(0xff000000);
        int[] cols = {0xffffd27a, 0xffffe9b0, 0xfffff6d8, 0xff9ec5ff};
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                if (r.nextFloat() < 0.45f) {
                    p.setColor(cols[r.nextInt(cols.length)]);
                    c.drawRect(col * 64 + 13, row * 64 + 11, col * 64 + 51, row * 64 + 51, p);
                }
            }
        }
        return b;
    }

    private static Bitmap glassBmp(boolean tabarbour) {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int x = col * 64, y = row * 64;
                p.setShader(new LinearGradient(x, y, x + 64, y + 64, tabarbour ? new int[]{0xffb9cbd0, 0xff829da5, 0xff607982} : new int[]{0xff9db8cf, 0xff4f7392, 0xff24394d},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(x, y, x + 64, y + 64, p);
                p.setShader(null);
                p.setColor(0x1fffffff);
                c.drawRect(x + 6, y + 4, x + 16, y + 60, p);
            }
        }
        p.setShader(null);
        p.setColor(tabarbour ? 0xff68777a : 0xff2b3b49);
        for (int i = 0; i <= 4; i++) {
            c.drawRect(i * 64 - 1.5f, 0, i * 64 + 1.5f, 256, p);
            c.drawRect(0, i * 64 - 3, 256, i * 64 + 3, p);
        }
        return b;
    }

    private static Bitmap glassEmBmp() {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(13);
        c.drawColor(0xff000000);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                if (r.nextFloat() < 0.35f) {
                    p.setColor(r.nextFloat() < 0.3f ? 0xffbcd8ff : 0xffffe3a0);
                    c.drawRect(col * 64 + 3, row * 64 + 6, col * 64 + 61, row * 64 + 58, p);
                }
            }
        }
        return b;
    }

    private static Bitmap roadBmp(boolean line, boolean tabarbour) {
        Bitmap b = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(17);
        c.drawColor(tabarbour ? 0xff85898a : 0xff3b3c40);
        noise(c, p, r, 128, 256, tabarbour ? 1900 : 1400, tabarbour ? 0.10f : 0.18f);
        if (tabarbour) {
            p.setShader(null);
            for (int i = 0; i < 34; i++) {
                float x = r.nextFloat() * 128f, y = r.nextFloat() * 256f;
                float dx = (r.nextFloat() - 0.5f) * 18f, dy = 5f + r.nextFloat() * 24f;
                p.setColor(i % 3 == 0 ? 0x55727677 : 0x3d55595a);
                p.setStrokeWidth(0.35f + r.nextFloat() * 0.65f);
                c.drawLine(x, y, x + dx, y + dy, p);
                c.drawLine(x + dx, y + dy, x + dx * 0.65f - 2f, y + dy + 4f, p);
            }
            for (int i = 0; i < 85; i++) {
                p.setColor(0x33706c62);
                c.drawCircle(r.nextFloat() * 128f, r.nextFloat() * 256f, 0.5f + r.nextFloat() * 1.2f, p);
            }
        }
        if (line) {
            p.setShader(null);
            p.setColor(0xfff1f1f1);
            c.drawRect(62, 0, 66, 128, p);
            p.setColor(0xa6f0f0f0);
            c.drawRect(5, 0, 8, 256, p);
            c.drawRect(120, 0, 123, 256, p);
        }
        return b;
    }

    private static Bitmap sidewalkBmp(boolean tabarbour) {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(19);
        c.drawColor(tabarbour ? 0xffc9c4b8 : 0xffb4b0a7);
        noise(c, p, r, 128, 128, 500, 0.1f);
        p.setShader(null);
        p.setColor(0xff8f8c84);
        c.drawRect(0, 0, 128, 2, p);
        c.drawRect(0, 0, 2, 128, p);
        return b;
    }

    private static Bitmap groundBmp(boolean tabarbour) {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        c.drawColor(tabarbour ? 0xffc9ad70 : 0xff8a8a7c);
        Random r = new Random(23);
        noise(c, p, r, 128, 128, tabarbour ? 1700 : 900, tabarbour ? 0.13f : 0.16f);
        if (tabarbour) {
            for (int i = 0; i < 520; i++) {
                float x = r.nextFloat() * 128f, y = r.nextFloat() * 128f;
                float radius = 0.35f + r.nextFloat() * 1.45f;
                int[] stones = {0x66eee0b5, 0x667e704e, 0x667f7661, 0x668f805f, 0x66e1d1a4};
                p.setColor(stones[r.nextInt(stones.length)]);
                c.drawCircle(x, y, radius, p);
            }
        }
        return b;
    }
}
