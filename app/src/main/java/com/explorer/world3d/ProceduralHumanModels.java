package com.explorer.world3d;

/** Fictional, fully procedural human meshes with varied skin, clothing and hairstyles. */
final class ProceduralHumanModels {
    static final int COUNT=6;
    private static final float PI=(float)Math.PI;
    private static final float[][] SKIN={{.72f,.48f,.34f},{.86f,.65f,.51f},{.58f,.36f,.25f},{.78f,.55f,.42f},{.91f,.72f,.59f},{.66f,.43f,.31f}};
    private static final float[][] SHIRT={{.10f,.28f,.48f},{.72f,.19f,.13f},{.16f,.42f,.29f},{.24f,.24f,.29f},{.78f,.64f,.24f},{.36f,.22f,.48f}};
    private static final float[][] PANTS={{.12f,.16f,.22f},{.16f,.23f,.35f},{.22f,.25f,.20f},{.12f,.13f,.16f},{.28f,.24f,.20f},{.18f,.16f,.25f}};
    private ProceduralHumanModels(){}
    static Mesh[] buildAll(){Mesh[] m=new Mesh[COUNT];for(int i=0;i<COUNT;i++)m[i]=Mesh.from(build(i));return m;}
    private static GeoBuf build(int v){
        GeoBuf b=new GeoBuf();float[] s=SKIN[v],c=SHIRT[v],p=PANTS[v];
        ellipsoid(b,0,1.08f,0,.25f,.36f,.15f,c);
        ellipsoid(b,0,1.58f,0,.145f,.205f,.14f,s);
        // Hair cap, side locks and ears
        ellipsoid(b,0,1.69f,-.018f,.15f,.12f,.145f,new float[]{.10f,.075f,.055f});
        ellipsoid(b,-.15f,1.57f,0,.035f,.065f,.05f,s);
        ellipsoid(b,.15f,1.57f,0,.035f,.065f,.05f,s);
        // Facial details: brows, eyes, nose and mouth are actual small meshes.
        for(int side=-1;side<=1;side+=2){
            ellipsoid(b,side*.052f,1.60f,.128f,.018f,.012f,.012f,new float[]{.07f,.055f,.045f});
            ellipsoid(b,side*.052f,1.625f,.127f,.021f,.009f,.009f,new float[]{.96f,.91f,.82f});
        }
        ellipsoid(b,0,1.575f,.145f,.018f,.035f,.022f,s);
        ellipsoid(b,0,1.535f,.137f,.035f,.008f,.012f,new float[]{.38f,.16f,.14f});
        // Neck, arms, forearms, legs and shoes.
        ellipsoid(b,0,1.36f,0,.065f,.10f,.065f,s);
        for(int side=-1;side<=1;side+=2){
            ellipsoid(b,side*.29f,1.16f,0,.095f,.25f,.095f,c);
            ellipsoid(b,side*.31f,.88f,.015f,.065f,.20f,.07f,s);
            ellipsoid(b,side*.115f,.57f,0,.085f,.32f,.09f,p);
            ellipsoid(b,side*.115f,.22f,.045f,.075f,.20f,.08f,p);
            ellipsoid(b,side*.115f,.075f,.11f,.085f,.065f,.16f,new float[]{.12f,.13f,.15f});
        }
        // Clothing details: collar, placket and restrained seams.
        ellipsoid(b,0,1.39f,.10f,.10f,.055f,.035f,new float[]{.82f,.82f,.78f});
        for(int i=0;i<3;i++) ellipsoid(b,0,1.27f-i*.07f,.151f,.009f,.009f,.006f,new float[]{.85f,.82f,.70f});
        return b;
    }
    private static void ellipsoid(GeoBuf b,float cx,float cy,float cz,float rx,float ry,float rz,float[] col){
        int rings=12,seg=16;
        float[][] q=new float[rings+1][seg*3];
        for(int j=0;j<=rings;j++){
            float ph=-PI*.5f+PI*j/rings;
            for(int k=0;k<seg;k++){
                float th=2*PI*k/seg, cp=(float)Math.cos(ph);
                q[j][k*3]=cx+rx*cp*(float)Math.cos(th);
                q[j][k*3+1]=cy+ry*(float)Math.sin(ph);
                q[j][k*3+2]=cz+rz*cp*(float)Math.sin(th);
            }
        }
        for(int j=0;j<rings;j++)for(int k=0;k<seg;k++){
            int n=(k+1)%seg;float[] a=q[j],d=q[j+1];
            b.quad(a[k*3],a[k*3+1],a[k*3+2],a[n*3],a[n*3+1],a[n*3+2],
                d[n*3],d[n*3+1],d[n*3+2],d[k*3],d[k*3+1],d[k*3+2],col[0],col[1],col[2]);
        }
    }
}
