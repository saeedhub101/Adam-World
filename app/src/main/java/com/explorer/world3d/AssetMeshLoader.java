package com.explorer.world3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Backwards-compatible AWM1/AWM2/AWM3 reader plus AWM4 base-color textures. */
final class AssetMeshLoader {
    private static final int V1=0x314d5741, V2=0x324d5741, V3=0x334d5741, V4=0x344d5741;
    private static final int MAX_VERTICES=1500000, MAX_PARTS=4096, MAX_MATERIALS=4096, MAX_TEXTURES=256;
    private AssetMeshLoader() {}
    static final class Material {
        final float r,g,b,metallic,roughness; final int texture;
        Material(float r,float g,float b,float m,float rough,int t){this.r=r;this.g=g;this.b=b;metallic=m;roughness=rough;texture=t;}
    }
    static final class Part { final int first,count,material; Part(int f,int c,int m){first=f;count=c;material=m;} }
    static final class Model {
        final Mesh mesh; final Part[] parts; final Material[] materials; final int[] textures;
        Model(Mesh m,Part[] p,Material[] a,int[] t){mesh=m;parts=p;materials=a;textures=t;}
        void draw(int params,int fallbackTexture){
            if(mesh==null)return;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            for(Part p:parts){
                Material m=materials[p.material];
                GLES20.glUniform2f(params,m.metallic,m.roughness);
                int tex=m.texture>=0&&m.texture<textures.length?textures[m.texture]:fallbackTexture;
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex);
                mesh.drawRange(p.first,p.count);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,fallbackTexture);
            GLES20.glUniform2f(params,1f,1f);
        }
        void release(){if(mesh!=null)mesh.release();if(textures.length>0)GLES20.glDeleteTextures(textures.length,textures,0);}
    }
    static Model load(Context c,String path){
        try(InputStream in=c.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] chunk=new byte[32768];int n;while((n=in.read(chunk))!=-1)out.write(chunk,0,n);
            byte[] raw=out.toByteArray();if(raw.length<8)return null;
            ByteBuffer d=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int magic=d.getInt();
            if(magic==V4)return loadV4(d);if(magic==V3)return loadV3(d);
            int stride=magic==V2?11:magic==V1?9:0;if(stride==0)return null;
            int count=d.getInt();if(count<3||count>MAX_VERTICES||(long)d.remaining()!=(long)count*stride*4)return null;
            GeoBuf b=new GeoBuf();
            for(int i=0;i<count;i++){float x=d.getFloat(),y=d.getFloat(),z=d.getFloat(),nx=d.getFloat(),ny=d.getFloat(),nz=d.getFloat();float u=stride==11?d.getFloat():.5f,v=stride==11?d.getFloat():.5f;b.vn(x,y,z,nx,ny,nz,u,v,d.getFloat(),d.getFloat(),d.getFloat());}
            return new Model(Mesh.from(b),new Part[]{new Part(0,count,0)},new Material[]{new Material(1,1,1,0,.72f,-1)},new int[0]);
        }catch(Exception ignored){return null;}
    }
    private static GeoBuf readGeometry(ByteBuffer d,int vertices){
        GeoBuf b=new GeoBuf();
        for(int i=0;i<vertices;i++){
            float x=d.getFloat(),y=d.getFloat(),z=d.getFloat(),nx=d.getFloat(),ny=d.getFloat(),nz=d.getFloat(),u=d.getFloat(),v=d.getFloat(),r=d.getFloat(),g=d.getFloat(),bl=d.getFloat();
            if(!finite(x)||!finite(y)||!finite(z)||!finite(nx)||!finite(ny)||!finite(nz)||!finite(u)||!finite(v)||!finite(r)||!finite(g)||!finite(bl))return null;
            b.vn(x,y,z,nx,ny,nz,u,v,r,g,bl);
        }return b;
    }
    private static Part[] readParts(ByteBuffer d,int vertices,int partCount,int materialCount){
        Part[] parts=new Part[partCount];
        for(int i=0;i<partCount;i++){int first=d.getInt(),count=d.getInt(),mat=d.getInt();if(first<0||count<3||count%3!=0||first+(long)count>vertices||mat<0||mat>=materialCount)return null;parts[i]=new Part(first,count,mat);}return parts;
    }
    private static Material[] readMaterialsV3(ByteBuffer d,int count){
        Material[] a=new Material[count];
        for(int i=0;i<count;i++){float r=d.getFloat(),g=d.getFloat(),b=d.getFloat(),alpha=d.getFloat();float er=d.getFloat(),eg=d.getFloat(),eb=d.getFloat(),metal=d.getFloat(),rough=d.getFloat(),cutoff=d.getFloat();int mode=d.getInt(),flags=d.getInt();if(!finite(r)||!finite(g)||!finite(b)||!finite(metal)||!finite(rough))return null;a[i]=new Material(clamp(r),clamp(g),clamp(b),clamp(metal),Math.max(.04f,Math.min(1f,rough)),-1);}return a;
    }
    private static Model loadV3(ByteBuffer d){
        if(d.remaining()<16)return null;int v=d.getInt(),p=d.getInt(),m=d.getInt(),s=d.getInt();
        if(!valid(v,p,m,s)||((long)v*44L+(long)p*12L+(long)m*48L)!=d.remaining())return null;
        GeoBuf b=readGeometry(d,v);if(b==null)return null;Part[] parts=readParts(d,v,p,m);if(parts==null)return null;Material[] a=readMaterialsV3(d,m);if(a==null)return null;
        return new Model(Mesh.from(b),parts,a,new int[0]);
    }
    private static Model loadV4(ByteBuffer d){
        if(d.remaining()<20)return null;int v=d.getInt(),p=d.getInt(),m=d.getInt(),s=d.getInt(),tc=d.getInt();
        if(!valid(v,p,m,s)||tc<0||tc>MAX_TEXTURES)return null;
        long fixed=(long)v*44L+(long)p*12L+(long)m*52L;if(fixed>d.remaining())return null;
        GeoBuf b=readGeometry(d,v);if(b==null)return null;Part[] parts=readParts(d,v,p,m);if(parts==null)return null;
        Material[] a=new Material[m];
        for(int i=0;i<m;i++){float r=d.getFloat(),g=d.getFloat(),bl=d.getFloat(),alpha=d.getFloat();float er=d.getFloat(),eg=d.getFloat(),eb=d.getFloat(),metal=d.getFloat(),rough=d.getFloat(),cutoff=d.getFloat();int mode=d.getInt(),flags=d.getInt(),ti=d.getInt();if(!finite(r)||!finite(g)||!finite(bl)||!finite(metal)||!finite(rough)||ti< -1||ti>=tc)return null;a[i]=new Material(clamp(r),clamp(g),clamp(bl),clamp(metal),Math.max(.04f,Math.min(1f,rough)),ti);}
        int[] gl=new int[tc];long total=0;
        for(int i=0;i<tc;i++){
            if(d.remaining()<16){delete(gl);return null;}int w=d.getInt(),h=d.getInt(),codec=d.getInt(),len=d.getInt();
            if(w<1||h<1||w>4096||h>4096||codec!=1||len<8||len>8*1024*1024||len>d.remaining()){delete(gl);return null;}
            total+=len;if(total>64L*1024L*1024L){delete(gl);return null;}byte[] png=new byte[len];d.get(png);
            Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,len);if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h){if(bitmap!=null)bitmap.recycle();delete(gl);return null;}gl[i]=Textures.upload(bitmap);
        }
        if(d.hasRemaining()){delete(gl);return null;}return new Model(Mesh.from(b),parts,a,gl);
    }
    private static void delete(int[] ids){for(int id:ids)if(id!=0)GLES20.glDeleteTextures(1,new int[]{id},0);}
    private static boolean valid(int v,int p,int m,int s){return v>=3&&v<=MAX_VERTICES&&p>=1&&p<=MAX_PARTS&&m>=1&&m<=MAX_MATERIALS&&s==11;}
    private static boolean finite(float x){return !Float.isNaN(x)&&!Float.isInfinite(x);}
    private static float clamp(float x){return Math.max(0f,Math.min(1f,x));}
}