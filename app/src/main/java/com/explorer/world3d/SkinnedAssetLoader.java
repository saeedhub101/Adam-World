package com.explorer.world3d;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Bounded AWM5 reader for skinned triangle meshes, skeletons and sampled TRS clips. */
final class SkinnedAssetLoader {
    private static final int MAGIC_AWM5=0x354d5741, MAGIC_AWM6=0x364d5741;
    private static final int AWM6_VERTEX_BYTES=30;
    private static final int MAX_VERTICES=250000,MAX_CLIPS=64,MAX_KEYS=20000,MAX_NAME=96;
    private SkinnedAssetLoader(){}
    static final class Asset {
        final SkinnedMesh mesh;
        final SkeletalAnimationRuntime.Animator animator;
        Asset(SkinnedMesh m,SkeletalAnimationRuntime.Animator a){mesh=m;animator=a;}
        void release(){mesh.release();}
    }
    static Asset load(Context context,String path) {
        try(InputStream in=context.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[32768];int n;while((n=in.read(block))!=-1){if(out.size()+n>96*1024*1024)return null;out.write(block,0,n);}
            ByteBuffer d=ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            if(d.remaining()<24)return null;
            int magic=d.getInt();
            int vertices=d.getInt(),bones=d.getInt(),clipCount=d.getInt(),stride=d.getInt(),flags=d.getInt();
            boolean compact=magic==MAGIC_AWM6;
            if((!compact&&magic!=MAGIC_AWM5)||vertices<3||vertices>MAX_VERTICES
                    ||bones<1||bones>SkeletalAnimationRuntime.MAX_BONES||clipCount<0||clipCount>MAX_CLIPS
                    ||(compact?stride!=AWM6_VERTEX_BYTES:stride!=SkinnedMesh.STRIDE_FLOATS))return null;
            long vertexBytes=(long)vertices*(compact?AWM6_VERTEX_BYTES:(long)stride*4L);
            if(vertexBytes>d.remaining())return null;
            float[] data=new float[vertices*SkinnedMesh.STRIDE_FLOATS];
            if(compact){
                for(int v=0;v<vertices;v++){
                    int o=v*SkinnedMesh.STRIDE_FLOATS;
                    for(int j=0;j<11;j++)data[o+j]=halfToFloat(Short.toUnsignedInt(d.getShort()));
                    for(int j=0;j<4;j++)data[o+11+j]=d.get()&0xff;
                    for(int j=0;j<4;j++)data[o+15+j]=(d.get()&0xff)/255f;
                }
            }else{
                for(int i=0;i<data.length;i++)data[i]=d.getFloat();
            }
            for(float value:data)if(!Float.isFinite(value))return null;
            int[] parent=new int[bones];String[] names=new String[bones];float[] bt=new float[bones*3],br=new float[bones*4],bs=new float[bones*3],ib=new float[bones*16];
            for(int b=0;b<bones;b++){
                parent[b]=d.getInt();names[b]=readString(d);
                for(int j=0;j<3;j++)bt[b*3+j]=d.getFloat();
                for(int j=0;j<4;j++)br[b*4+j]=d.getFloat();
                for(int j=0;j<3;j++)bs[b*3+j]=d.getFloat();
                for(int j=0;j<16;j++)ib[b*16+j]=d.getFloat();
                if(!finite(bt,b*3,3)||!finite(br,b*4,4)||!finite(bs,b*3,3)||!finite(ib,b*16,16))return null;
            }
            SkeletalAnimationRuntime.Skeleton skeleton=new SkeletalAnimationRuntime.Skeleton(parent,names,bt,br,bs,ib);
            SkeletalAnimationRuntime.Clip[] clips=new SkeletalAnimationRuntime.Clip[clipCount];
            int totalKeys=0;
            for(int c=0;c<clipCount;c++){
                String name=readString(d);float duration=d.getFloat();int tracks=d.getInt();
                if(!Float.isFinite(duration)||duration<=0f||tracks<0||tracks>bones)return null;
                SkeletalAnimationRuntime.Track[] trackArray=new SkeletalAnimationRuntime.Track[tracks];
                for(int t=0;t<tracks;t++){
                    int bone=d.getInt(),keys=d.getInt();totalKeys+=keys;
                    if(bone<0||bone>=bones||keys<1||totalKeys>MAX_KEYS)return null;
                    float[] times=new float[keys],tr=new float[keys*3],rot=new float[keys*4],scale=new float[keys*3];
                    for(int k=0;k<keys;k++){
                        times[k]=d.getFloat();for(int j=0;j<3;j++)tr[k*3+j]=d.getFloat();
                        for(int j=0;j<4;j++)rot[k*4+j]=d.getFloat();
                        for(int j=0;j<3;j++)scale[k*3+j]=d.getFloat();
                        if(!Float.isFinite(times[k])||times[k]<0f||times[k]>duration||!finite(tr,k*3,3)||!finite(rot,k*4,4)||!finite(scale,k*3,3))return null;
                        if(k>0&&times[k]<times[k-1])return null;
                    }
                    trackArray[t]=new SkeletalAnimationRuntime.Track(bone,times,tr,rot,scale);
                }
                clips[c]=new SkeletalAnimationRuntime.Clip(name,duration,trackArray);
            }
            if(d.hasRemaining())return null;
            for(int v=0;v<vertices;v++){
                int o=v*stride+11;
                float sum=0f;
                for(int j=0;j<4;j++){
                    float joint=data[o+j],weight=data[o+4+j];
                    if(joint<0||joint>=bones||joint!=(int)joint||weight<0||!Float.isFinite(weight))return null;
                    sum+=weight;
                }
                if(sum<1e-5f)return null;
                for(int j=0;j<4;j++)data[o+4+j]/=sum;
            }
            return new Asset(SkinnedMesh.upload(data,vertices),new SkeletalAnimationRuntime.Animator(skeleton,clips));
        }catch(Exception ignored){return null;}
    }
    private static float halfToFloat(int bits){
        float sign=(bits&0x8000)!=0?-1f:1f;
        int exponent=(bits>>>10)&0x1f,mantissa=bits&0x03ff;
        if(exponent==0)return sign*(float)Math.scalb(mantissa/1024.0,-14);
        if(exponent==31)return mantissa==0?sign*Float.POSITIVE_INFINITY:Float.NaN;
        return sign*(float)Math.scalb(1.0+mantissa/1024.0,exponent-15);
    }
    private static String readString(ByteBuffer d){int n=d.getInt();if(n<1||n>MAX_NAME||n>d.remaining())throw new IllegalArgumentException("Invalid string");byte[] b=new byte[n];d.get(b);return new String(b,StandardCharsets.UTF_8);}
    private static boolean finite(float[] a,int o,int n){for(int i=0;i<n;i++)if(!Float.isFinite(a[o+i]))return false;return true;}
}
