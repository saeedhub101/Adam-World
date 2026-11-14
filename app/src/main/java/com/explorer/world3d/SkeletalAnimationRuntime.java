package com.explorer.world3d;

/**
 * Compact, allocation-free skeletal pose evaluator for the native GLES2 actor path.
 * Matrices are column-major and compatible with android.opengl.Matrix.
 */
final class SkeletalAnimationRuntime {
    static final int MAX_BONES = 24;
    private SkeletalAnimationRuntime() {}

    static final class Skeleton {
        final int count;
        final int[] parent;
        final String[] names;
        final float[] bindT, bindR, bindS, inverseBind;
        Skeleton(int[] parent, String[] names, float[] bindT, float[] bindR,
                 float[] bindS, float[] inverseBind) {
            if (parent == null || parent.length == 0 || parent.length > MAX_BONES
                    || names.length != parent.length || bindT.length != parent.length * 3
                    || bindR.length != parent.length * 4 || bindS.length != parent.length * 3
                    || inverseBind.length != parent.length * 16) throw new IllegalArgumentException("Invalid skeleton");
            count = parent.length; this.parent = parent; this.names = names;
            this.bindT = bindT; this.bindR = bindR; this.bindS = bindS; this.inverseBind = inverseBind;
            for (int i=0;i<count;i++) if (parent[i] >= i || parent[i] < -1)
                throw new IllegalArgumentException("Skeleton must be parent-first");
        }
    }

    static final class Track {
        final int bone, keys;
        final float[] time, t, r, s;
        Track(int bone, float[] time, float[] t, float[] r, float[] s) {
            if (bone < 0 || time == null || time.length == 0 || t.length != time.length*3
                    || r.length != time.length*4 || s.length != time.length*3)
                throw new IllegalArgumentException("Invalid animation track");
            this.bone=bone; this.time=time; this.t=t; this.r=r; this.s=s; keys=time.length;
        }
    }

    static final class Clip {
        final String name;
        final float duration;
        final Track[] tracks;
        Clip(String name, float duration, Track[] tracks) {
            if (duration <= 0f || !Float.isFinite(duration)) throw new IllegalArgumentException("Invalid clip duration");
            this.name=name; this.duration=duration; this.tracks=tracks;
        }
    }

    static final class Animator {
        final Skeleton skeleton;
        final Clip[] clips;
        final float[] localT, localR, localS, fromT, fromR, fromS, targetT, targetR, targetS;
        final float[] global, skin, matrixScratch = new float[16];
        int current=-1, previous=-1;
        float time, previousTime, fade, fadeDuration, speed=1f;
        float proceduralCrouch, proceduralMantle, proceduralMantlePhase, proceduralWalk;
        boolean loop=true;

        /** Semantic retargeting overlay: imported rigs need no authored crouch/mantle clips. */
        void setProceduralPose(float crouch,float mantle,float mantlePhase,float walk){
            proceduralCrouch=clamp(crouch,0f,1f);
            proceduralMantle=clamp(mantle,0f,1f);
            proceduralMantlePhase=clamp(mantlePhase,0f,1f);
            proceduralWalk=clamp(walk,0f,1f);
        }

        private void applyProceduralPose(){
            for(int i=0;i<skeleton.count;i++){
                String n=skeleton.names[i]==null?"":skeleton.names[i].toLowerCase(java.util.Locale.US)
                        .replace("-","").replace("_","").replace(" ","").replace(".","");
                if(n.isEmpty())continue;
                boolean left=containsAny(n,"left","lupper","lthigh","arm_l","leg_l");
                boolean right=containsAny(n,"right","rupper","rthigh","arm_r","leg_r");
                float side=left?-1f:right?1f:0f,x=0f,z=0f;
                if(containsAny(n,"pelvis","hips","hip")){x+=proceduralCrouch*18f; x+=proceduralMantle*(8f-18f*proceduralMantlePhase);}
                else if(containsAny(n,"spine","chest","torso","abdomen")){x+=proceduralCrouch*24f; x+=proceduralMantle*(-24f+42f*proceduralMantlePhase);}
                else if(containsAny(n,"neck","head")){x-=proceduralCrouch*8f; x+=proceduralMantle*8f;}
                else if(containsAny(n,"thigh","upleg","upperleg","femur","legupper")){
                    x-=proceduralCrouch*38f;
                    x+=proceduralMantle*(side==0f?0f:(side>0?1f:-1f)*(18f+12f*(float)Math.sin(proceduralMantlePhase*Math.PI)));
                    z+=proceduralWalk*(side==0f?0f:side*18f);
                }else if(containsAny(n,"calf","lowerleg","shin","leglower")){x+=proceduralCrouch*68f; x+=proceduralMantle*24f;}
                else if(containsAny(n,"upperarm","shoulder","arm")){
                    if(side!=0f){x+=proceduralCrouch*(side>0?-20f:-14f);x+=proceduralMantle*(side>0?-105f:-92f)*(0.65f+0.35f*(float)Math.sin(proceduralMantlePhase*Math.PI));z+=side*proceduralMantle*12f;}
                }else if(containsAny(n,"forearm","lowerarm","elbow")){x+=proceduralCrouch*(side==0f?-8f:-12f);x+=proceduralMantle*(side>0?-38f:-32f);}
                else if(containsAny(n,"foot","ankle")){x+=proceduralCrouch*12f;x+=proceduralMantle*-12f;}
                if(Math.abs(x)>0.01f||Math.abs(z)>0.01f)rotateLocal(i,(float)Math.toRadians(x),(float)Math.toRadians(z));
            }
        }

        private void rotateLocal(int bone,float rx,float rz){
            float hx=(float)Math.sin(rx*.5f),hz=(float)Math.sin(rz*.5f);
            float cx=(float)Math.cos(rx*.5f),cz=(float)Math.cos(rz*.5f);
            float[] q=localR;int o=bone*4;
            float ax=q[o],ay=q[o+1],az=q[o+2],aw=q[o+3];
            float bx=hx*cz,by=-hx*hz,bz=cx*hz,bw=cx*cz;
            q[o]=aw*bx+ax*bw+ay*bz-az*by;
            q[o+1]=aw*by-ax*bz+ay*bw+az*bx;
            q[o+2]=aw*bz+ax*by-ay*bx+az*bw;
            q[o+3]=aw*bw-ax*bx-ay*by-az*bz;
            float inv=1f/(float)Math.sqrt(Math.max(1e-12f,q[o]*q[o]+q[o+1]*q[o+1]+q[o+2]*q[o+2]+q[o+3]*q[o+3]));
            q[o]*=inv;q[o+1]*=inv;q[o+2]*=inv;q[o+3]*=inv;
        }

        private static boolean containsAny(String value,String... terms){
            for(String term:terms)if(value.contains(term))return true;
            return false;
        }

        Animator(Skeleton skeleton, Clip[] clips) {
            this.skeleton=skeleton; this.clips=clips;
            int n=skeleton.count;
            localT=new float[n*3]; localR=new float[n*4]; localS=new float[n*3];
            fromT=new float[n*3]; fromR=new float[n*4]; fromS=new float[n*3];
            targetT=new float[n*3]; targetR=new float[n*4]; targetS=new float[n*3];
            global=new float[n*16]; skin=new float[n*16];
            resetBindPose();
        }

        void resetBindPose() {
            System.arraycopy(skeleton.bindT,0,localT,0,localT.length);
            System.arraycopy(skeleton.bindR,0,localR,0,localR.length);
            System.arraycopy(skeleton.bindS,0,localS,0,localS.length);
            evaluateMatrices();
        }

        void play(int clip, float blendSeconds, boolean repeat) {
            if (clip < 0 || clip >= clips.length || clip == current) return;
            previous=current; previousTime=time; current=clip; time=0f;
            fade=0f; fadeDuration=Math.max(0f,blendSeconds); loop=repeat;
            System.arraycopy(localT,0,fromT,0,localT.length);
            System.arraycopy(localR,0,fromR,0,localR.length);
            System.arraycopy(localS,0,fromS,0,localS.length);
            if (fadeDuration == 0f) previous=-1;
        }

        int findClip(String name) {
            for(int i=0;i<clips.length;i++) if(clips[i].name.equalsIgnoreCase(name)) return i;
            return -1;
        }

        void update(float dt) {
            if (current < 0 || clips.length == 0) {
                resetBindPose();
                applyProceduralPose();
                evaluateMatrices();
                return;
            }
            dt=Math.max(0f,Math.min(.05f,dt));
            Clip c=clips[current]; time=advance(time,dt*speed,c.duration,loop);
            sample(c,time,targetT,targetR,targetS);
            if(previous>=0 && fadeDuration>0f && fade<fadeDuration) {
                Clip p=clips[previous]; previousTime=advance(previousTime,dt*speed,p.duration,true);
                fade=Math.min(fadeDuration,fade+dt);
                float a=fade/fadeDuration;
                blend(fromT,fromR,fromS,targetT,targetR,targetS,a,localT,localR,localS);
                if(fade>=fadeDuration) previous=-1;
            } else {
                System.arraycopy(targetT,0,localT,0,localT.length);
                System.arraycopy(targetR,0,localR,0,localR.length);
                System.arraycopy(targetS,0,localS,0,localS.length);
            }
            applyProceduralPose();
            evaluateMatrices();
        }

        private void sample(Clip clip,float at,float[] outT,float[] outR,float[] outS) {
            System.arraycopy(skeleton.bindT,0,outT,0,outT.length);
            System.arraycopy(skeleton.bindR,0,outR,0,outR.length);
            System.arraycopy(skeleton.bindS,0,outS,0,outS.length);
            for(Track tr:clip.tracks) {
                if(tr.bone<0 || tr.bone>=skeleton.count) continue;
                int k=0; while(k<tr.keys-2 && tr.time[k+1]<at) k++;
                int k1=Math.min(tr.keys-1,k+1);
                float span=tr.time[k1]-tr.time[k];
                float a=span>1e-6f?clamp((at-tr.time[k])/span,0f,1f):0f;
                int b=tr.bone;
                for(int j=0;j<3;j++) {
                    outT[b*3+j]=lerp(tr.t[k*3+j],tr.t[k1*3+j],a);
                    outS[b*3+j]=lerp(tr.s[k*3+j],tr.s[k1*3+j],a);
                }
                slerp(tr.r,k*4,k1*4,a,outR,b*4);
            }
        }

        private void evaluateMatrices() {
            float[] m=matrixScratch;
            for(int i=0;i<skeleton.count;i++) {
                compose(localT,i*3,localR,i*4,localS,i*3,m);
                int parent=skeleton.parent[i];
                if(parent>=0) multiply(global,parent*16,m,0,global,i*16);
                else System.arraycopy(m,0,global,i*16,16);
                multiply(global,i*16,skeleton.inverseBind,i*16,skin,i*16);
            }
        }

        float[] skinMatrices() { return skin; }
        int boneCount() { return skeleton.count; }
    }

    /** Returns the analytically solved middle-joint position for a two-bone IK chain. */
    static void solveTwoBone(float rx,float ry,float rz,float tx,float ty,float tz,
                             float upper,float lower,float poleX,float poleY,float poleZ,float[] out) {
        float dx=tx-rx,dy=ty-ry,dz=tz-rz;
        float d=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        float min=Math.abs(upper-lower)+1e-4f, max=upper+lower-1e-4f;
        d=Math.max(min,Math.min(max,d));
        float inv=1f/(float)Math.max(1e-6,Math.sqrt(dx*dx+dy*dy+dz*dz));
        float nx=dx*inv,ny=dy*inv,nz=dz*inv;
        float px=poleY*nz-poleZ*ny, py=poleZ*nx-poleX*nz, pz=poleX*ny-poleY*nx;
        float pl=(float)Math.sqrt(px*px+py*py+pz*pz);
        if(pl<1e-5f){px=1f;py=pz=0f;pl=1f;}
        px/=pl;py/=pl;pz/=pl;
        float along=(upper*upper-lower*lower+d*d)/(2f*d);
        float h=(float)Math.sqrt(Math.max(0f,upper*upper-along*along));
        out[0]=rx+nx*along+px*h;out[1]=ry+ny*along+py*h;out[2]=rz+nz*along+pz*h;
    }

    private static float advance(float t,float dt,float duration,boolean loop) {
        t+=dt;
        if(loop) return t%duration;
        return Math.min(duration,t);
    }
    private static void blend(float[] at,float[] ar,float[] as,float[] bt,float[] br,float[] bs,float a,float[] ot,float[] or,float[] os) {
        for(int i=0;i<ot.length;i++){ot[i]=lerp(at[i],bt[i],a);os[i]=lerp(as[i],bs[i],a);}
        for(int i=0;i<or.length;i+=4) slerpPair(ar,i,br,i,a,or,i);
    }
    private static void compose(float[] t,int ti,float[] q,int qi,float[] s,int si,float[] m) {
        float x=q[qi],y=q[qi+1],z=q[qi+2],w=q[qi+3];
        float x2=x+x,y2=y+y,z2=z+z,xx=x*x2,xy=x*y2,xz=x*z2,yy=y*y2,yz=y*z2,zz=z*z2,wx=w*x2,wy=w*y2,wz=w*z2;
        m[0]=(1f-(yy+zz))*s[si];m[1]=(xy+wz)*s[si];m[2]=(xz-wy)*s[si];m[3]=0;
        m[4]=(xy-wz)*s[si+1];m[5]=(1f-(xx+zz))*s[si+1];m[6]=(yz+wx)*s[si+1];m[7]=0;
        m[8]=(xz+wy)*s[si+2];m[9]=(yz-wx)*s[si+2];m[10]=(1f-(xx+yy))*s[si+2];m[11]=0;
        m[12]=t[ti];m[13]=t[ti+1];m[14]=t[ti+2];m[15]=1;
    }
    private static void multiply(float[] a,int ao,float[] b,int bo,float[] out,int oo) {
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)out[oo+c*4+r]=a[ao+r]*b[bo+c*4]+a[ao+4+r]*b[bo+c*4+1]+a[ao+8+r]*b[bo+c*4+2]+a[ao+12+r]*b[bo+c*4+3];
    }
    private static void slerp(float[] q,int a,int b,float t,float[] out,int o){slerpPair(q,a,q,b,t,out,o);}
    private static void slerpPair(float[] a,int ai,float[] b,int bi,float t,float[] out,int oi) {
        float dot=a[ai]*b[bi]+a[ai+1]*b[bi+1]+a[ai+2]*b[bi+2]+a[ai+3]*b[bi+3];
        float sign=dot<0f?-1f:1f;dot=Math.abs(dot);
        float x,y;
        if(dot>.9995f){x=1f-t;y=t*sign;}
        else {float theta=(float)Math.acos(clamp(dot,-1f,1f)),sin=(float)Math.sin(theta);x=(float)Math.sin((1f-t)*theta)/sin;y=sign*(float)Math.sin(t*theta)/sin;}
        float qx=a[ai]*x+b[bi]*y,qy=a[ai+1]*x+b[bi+1]*y,qz=a[ai+2]*x+b[bi+2]*y,qw=a[ai+3]*x+b[bi+3]*y;
        float n=1f/(float)Math.sqrt(Math.max(1e-12f,qx*qx+qy*qy+qz*qz+qw*qw));
        out[oi]=qx*n;out[oi+1]=qy*n;out[oi+2]=qz*n;out[oi+3]=qw*n;
    }
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float clamp(float x,float a,float b){return Math.max(a,Math.min(b,x));}
}
