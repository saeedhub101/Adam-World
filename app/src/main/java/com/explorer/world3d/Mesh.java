package com.explorer.world3d;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** شبكة مرفوعة إلى ذاكرة الشاشة (VBO). */
final class Mesh {
    private int vbo;
    private final int count;
    private final int stride;

    private Mesh(int vbo, int count, int stride) { this.vbo = vbo; this.count = count; this.stride = stride; }

    static Mesh from(GeoBuf b) {
        if (b == null || b.n == 0) return null;
        return create(b.a, b.n, GeoBuf.STRIDE);
    }

    static Mesh create(float[] data, int n, int stride) {
        FloatBuffer fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data, 0, n);
        fb.position(0);
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return new Mesh(id[0], n / stride, stride);
    }

    /** للشبكات ذات التنسيق الكامل (11 رقماً). */
    void draw() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        int s = stride * 4;
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, s, 0);
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, s, 12);
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, s, 24);
        GLES20.glVertexAttribPointer(3, 3, GLES20.GL_FLOAT, false, s, 32);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
    }

    /** للسماء: موضع ثنائي الأبعاد فقط. */
    void drawSky() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
    }

    void release() {
        if (vbo != 0) { GLES20.glDeleteBuffers(1, new int[]{vbo}, 0); vbo = 0; }
    }
}
