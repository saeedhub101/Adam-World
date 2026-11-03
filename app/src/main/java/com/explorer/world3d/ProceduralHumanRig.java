package com.explorer.world3d;

/** Articulated procedural pedestrian rig. Every part is generated from smooth parametric ellipsoids. */
final class ProceduralHumanRig {
    static final int VARIANTS = 6;
    static final int PARTS = 16;
    private static final float PI=(float)Math.PI;
    private static final float[][] SKIN={{.72f,.48f,.34f},{.86f,.65f,.51f},{.58f,.36f,.25f},{.78f,.55f,.42f},{.91f,.72f,.59f},{.66f,.43f,.31f}};
    private static final float[][] SHIRT={{.10f,.28f,.48f},{.72f,.19f,.13f},{.16f,.42f,.29f},{.24f,.24f,.29f},{.78f,.64f,.24f},{.36f,.22f,.48f}};
    private static final float[][] PANTS={{.12f,.16f,.22f},{.16f,.23f,.35f},{.22f,.25f,.20f},{.12f,.13f,.16f},{.28f,.24f,.20f},{.18f,.16f,.25f}};
    private static final float[] HAIR={.10f,.075f,.055f}, SHOE={.12f,.13f,.15f}, EYE={.075f,.055f,.045f};
    private ProceduralHumanRig(){}

    static Mesh[][] buildAll() {
        Mesh[][] out=new Mesh[VARIANTS][PARTS];
        for(int v=0;v<VARIANTS;v++) {
            out[v][0]=ellipsoid(.25f,.36f,.15f,SHIRT[v]);
            out[v][1]=ellipsoid(.145f,.205f,.14f,SKIN[v]);
            out[v][2]=ellipsoid(.15f,.12f,.145f,HAIR);
            out[v][3]=ellipsoid(.095f,.25f,.095f,SHIRT[v]);
            out[v][4]=ellipsoid(.065f,.20f,.07f,SKIN[v]);
            out[v][5]=ellipsoid(.085f,.32f,.09f,PANTS[v]);
            out[v][6]=ellipsoid(.075f,.20f,.08f,PANTS[v]);
            out[v][7]=ellipsoid(.085f,.065f,.16f,SHOE);
            out[v][8]=ellipsoid(.018f,.012f,.012f,EYE);
            out[v][9]=ellipsoid(.035f,.008f,.012f,new float[]{.38f,.16f,.14f});
            out[v][10]=ellipsoid(.10f,.055f,.035f,new float[]{.82f,.82f,.78f});
            out[v][11]=ellipsoid(.018f,.034f,.025f,SKIN[v]);
            out[v][12]=ellipsoid(.025f,.007f,.010f,HAIR);
            out[v][13]=ellipsoid(.055f,.065f,.015f,SHIRT[v]);
            out[v][14]=ellipsoid(.008f,.075f,.008f,new float[]{.78f,.78f,.73f});
            out[v][15]=ellipsoid(.055f,.060f,.055f,SKIN[v]);
        }
        return out;
    }

    private static Mesh ellipsoid(float rx,float ry,float rz,float[] color) {
        GeoBuf b=new GeoBuf(); int rings=16, seg=20;
        float[][] p=new float[rings+1][seg*3];
        for(int j=0;j<=rings;j++) {
            float ph=-PI*.5f+PI*j/rings, cp=(float)Math.cos(ph);
            for(int k=0;k<seg;k++) {
                float th=2*PI*k/seg;
                p[j][k*3]=rx*cp*(float)Math.cos(th);
                p[j][k*3+1]=ry*(float)Math.sin(ph);
                p[j][k*3+2]=rz*cp*(float)Math.sin(th);
            }
        }
        for(int j=0;j<rings;j++)for(int k=0;k<seg;k++) {
            int n=(k+1)%seg; float[] a=p[j],d=p[j+1];
            b.quad(a[k*3],a[k*3+1],a[k*3+2],d[k*3],d[k*3+1],d[k*3+2],
                    d[n*3],d[n*3+1],d[n*3+2],a[n*3],a[n*3+1],a[n*3+2],color[0],color[1],color[2]);
        }
        return Mesh.from(b);
    }
}