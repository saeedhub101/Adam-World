package com.explorer.world3d;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** GLES2 vertex buffer for position/normal/UV/color plus four joint indices and weights. */
final class SkinnedMesh {
    static final int STRIDE_FLOATS=19;
    private int vbo;
    private final int vertices;
    private SkinnedMesh(int id,int count){vbo=id;vertices=count;}
    static SkinnedMesh upload(float[] data,int vertexCount) {
        if(data==null||vertexCount<3||data.length!=vertexCount*STRIDE_FLOATS)throw new IllegalArgumentException("Invalid skinned vertex data");
        FloatBuffer fb=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        int[] id=new int[1];GLES20.glGenBuffers(1,id,0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,id[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,data.length*4,fb,GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
        return new SkinnedMesh(id[0],vertexCount);
    }
    void draw() {
        if(vbo==0||vertices<3)return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,vbo);
        int stride=STRIDE_FLOATS*4;
        GLES20.glVertexAttribPointer(0,3,GLES20.GL_FLOAT,false,stride,0);
        GLES20.glVertexAttribPointer(1,3,GLES20.GL_FLOAT,false,stride,12);
        GLES20.glVertexAttribPointer(2,2,GLES20.GL_FLOAT,false,stride,24);
        GLES20.glVertexAttribPointer(3,3,GLES20.GL_FLOAT,false,stride,32);
        GLES20.glVertexAttribPointer(4,4,GLES20.GL_FLOAT,false,stride,44);
        GLES20.glVertexAttribPointer(5,4,GLES20.GL_FLOAT,false,stride,60);
        GLES20.glEnableVertexAttribArray(4);GLES20.glEnableVertexAttribArray(5);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices);
        GLES20.glDisableVertexAttribArray(4);GLES20.glDisableVertexAttribArray(5);
    }
    void release(){if(vbo!=0){GLES20.glDeleteBuffers(1,new int[]{vbo},0);vbo=0;}}
}
