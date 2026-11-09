package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLES20;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Backwards-compatible AWM1/AWM2 reader and AWM3 submesh/material reader. */
final class AssetMeshLoader {
    private static final int V1=0x314d5741, V2=0x324d5741, V3=0x334d5741;
    private static final int MAX_VERTICES=1500000, MAX_PARTS=4096, MAX_MATERIALS=4096;
    private AssetMeshLoader() {}
    static final class Material {
        final float r,g,b,metallic,roughness;
        Material(float r,float g,float b,float m,float rough) { this.r=r;this.g=g;this.b=b;metallic=m;roughness=rough; }
    }
    static final class Part {
        final int first,count,material;
        Part(int f,int c,int m) { first=f;count=c;material=m; }
    }
    static final class Model {
        final Mesh mesh; final Part[] parts; final Material[] materials;
        Model(Mesh m,Part[] p,Material[] a) { mesh=m;parts=p;materials=a; }
        void draw(int tint,int params) {
            if(mesh==null)return;
            for(Part p:parts) {
                Material m=materials[p.material];
                // Vertex RGB currently contains the source material base color.
                // Preserve the caller's vehicle paint tint; only apply per-part PBR factors.
                GLES20.glUniform2f(params,m.metallic,m.roughness);
                mesh.drawRange(p.first,p.count);
            }
            GLES20.glUniform2f(params,1f,1f);
        }
        void release(){if(mesh!=null)mesh.release();}
    }
    static Model load(Context c,String path) {
        try(InputStream in=c.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] chunk=new byte[32768];int n;while((n=in.read(chunk))!=-1)out.write(chunk,0,n);
            byte[] raw=out.toByteArray();if(raw.length<8)return null;
            ByteBuffer d=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int magic=d.getInt();
            if(magic==V3)return loadV3(d);
            int stride=magic==V2?11:magic==V1?9:0;if(stride==0)return null;
            int count=d.getInt();if(count<3||count>MAX_VERTICES||(long)d.remaining()!=(long)count*stride*4)return null;
            GeoBuf b=new GeoBuf();
            for(int i=0;i<count;i++){
                float x=d.getFloat(),y=d.getFloat(),z=d.getFloat(),nx=d.getFloat(),ny=d.getFloat(),nz=d.getFloat();
                float u=stride==11?d.getFloat():.5f,v=stride==11?d.getFloat():.5f;
                b.vn(x,y,z,nx,ny,nz,u,v,d.getFloat(),d.getFloat(),d.getFloat());
            }
            return new Model(Mesh.from(b),new Part[]{new Part(0,count,0)},new Material[]{new Material(1,1,1,0,.72f)});
        }catch(Exception ignored){return null;}
    }
    private static Model loadV3(ByteBuffer d) {
        if(d.remaining()<16)return null;
        int vertices=d.getInt(),partCount=d.getInt(),materialCount=d.getInt(),stride=d.getInt();
        if(vertices<3||vertices>MAX_VERTICES||partCount<1||partCount>MAX_PARTS||materialCount<1||materialCount>MAX_MATERIALS||stride!=11)return null;
        long expected=(long)vertices*44L+(long)partCount*12L+(long)materialCount*48L;
        if(expected!=d.remaining())return null;
        GeoBuf b=new GeoBuf();
        for(int i=0;i<vertices;i++){
            float x=d.getFloat(),y=d.getFloat(),z=d.getFloat(),nx=d.getFloat(),ny=d.getFloat(),nz=d.getFloat();
            float u=d.getFloat(),v=d.getFloat(),r=d.getFloat(),g=d.getFloat(),bl=d.getFloat();
            if(!finite(x)||!finite(y)||!finite(z)||!finite(nx)||!finite(ny)||!finite(nz)||!finite(u)||!finite(v)||!finite(r)||!finite(g)||!finite(bl))return null;
            b.vn(x,y,z,nx,ny,nz,u,v,r,g,bl);
        }
        Part[] parts=new Part[partCount];
        for(int i=0;i<partCount;i++){
            int first=d.getInt(),count=d.getInt(),mat=d.getInt();
            if(first<0||count<3||count%3!=0||first+(long)count>vertices||mat<0||mat>=materialCount)return null;
            parts[i]=new Part(first,count,mat);
        }
        Material[] materials=new Material[materialCount];
        for(int i=0;i<materialCount;i++){
            float r=d.getFloat(),g=d.getFloat(),b0=d.getFloat(),a=d.getFloat();
            float er=d.getFloat(),eg=d.getFloat(),eb=d.getFloat(),metal=d.getFloat(),rough=d.getFloat(),cutoff=d.getFloat();
            int alphaMode=d.getInt(),flags=d.getInt();
            if(!finite(r)||!finite(g)||!finite(b0)||!finite(metal)||!finite(rough))return null;
            materials[i]=new Material(clamp(r),clamp(g),clamp(b0),clamp(metal),Math.max(.04f,Math.min(1f,rough)));
        }
        return new Model(Mesh.from(b),parts,materials);
    }
    private static boolean finite(float x){return !Float.isNaN(x)&&!Float.isInfinite(x);}
    private static float clamp(float x){return Math.max(0f,Math.min(1f,x));}
}