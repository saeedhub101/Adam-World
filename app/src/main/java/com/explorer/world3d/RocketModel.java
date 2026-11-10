package com.explorer.world3d;

/**
 * Detailed low-poly PBR-ready missile mesh built once on the GL thread.
 * Local forward axis is +Z. Vertex colors separate warhead, body, bands,
 * machined metal, fins, seeker glass and nozzle surfaces.
 */
final class RocketModel {
    private static final int SIDES=24;
    private RocketModel(){}

    static Mesh build(){
        GeoBuf b=new GeoBuf();
        // Multi-section machined body and tapered nose.
        float[][] profile={
            {-.88f,.072f},{-.82f,.095f},{-.70f,.108f},{-.60f,.112f},
            {.48f,.112f},{.59f,.106f},{.68f,.090f},{.78f,.064f},{.91f,.003f}
        };
        for(int p=0;p<profile.length-1;p++){
            float z0=profile[p][0],r0=profile[p][1],z1=profile[p+1][0],r1=profile[p+1][1];
            float slope=(r1-r0)/(z1-z0);
            for(int i=0;i<SIDES;i++){
                float a0=(float)(Math.PI*2*i/SIDES),a1=(float)(Math.PI*2*(i+1)/SIDES);
                float c0=(float)Math.cos(a0),s0=(float)Math.sin(a0),c1=(float)Math.cos(a1),s1=(float)Math.sin(a1);
                float[] col=sectionColor((z0+z1)*.5f);
                vertex(b,r0*c0,r0*s0,z0,c0,-slope,s0,col);
                vertex(b,r0*c1,r0*s1,z0,c1,-slope,s1,col);
                vertex(b,r1*c1,r1*s1,z1,c1,-slope,s1,col);
                vertex(b,r0*c0,r0*s0,z0,c0,-slope,s0,col);
                vertex(b,r1*c1,r1*s1,z1,c1,-slope,s1,col);
                vertex(b,r1*c0,r1*s0,z1,c0,-slope,s0,col);
            }
        }
        // Warhead nose tip and dark optical seeker aperture.
        addRing(b,.78f,.064f,.805f,.052f,.74f,.76f,.78f);
        addRing(b,.805f,.052f,.835f,.031f,.16f,.18f,.20f);
        addRing(b,.835f,.031f,.855f,.024f,.08f,.18f,.23f);
        // Tail collar, nozzle lip and recessed exhaust throat.
        addRing(b,-.88f,.073f,-.91f,.086f,.31f,.33f,.35f);
        addRing(b,-.91f,.086f,-.95f,.073f,.55f,.57f,.59f);
        addRing(b,-.95f,.073f,-.965f,.052f,.13f,.14f,.15f);
        addRing(b,-.965f,.052f,-.89f,.044f,.035f,.04f,.045f);
        // Precision-machined circumferential bands and segmented collars.
        addRing(b,-.69f,.111f,-.665f,.114f,.52f,.54f,.56f);
        addRing(b,-.635f,.113f,-.615f,.114f,.12f,.14f,.16f);
        addRing(b,.48f,.112f,.505f,.111f,.50f,.52f,.54f);
        addRing(b,.535f,.109f,.552f,.108f,.12f,.14f,.16f);
        // Four swept stabilizer fins with metallic leading edges.
        for(int i=0;i<4;i++) addFin(b,(float)(Math.PI*.5*i));
        // Small service panel, fasteners and caution markings on upper body.
        addPanel(b);
        // Rear motor flutes and dark inner nozzle vanes.
        for(int i=0;i<8;i++){
            float a=(float)(Math.PI*2*i/8),x=(float)Math.cos(a),y=(float)Math.sin(a);
            addBar(b,x*.075f,y*.075f,-.91f,.018f,.018f,.075f,.24f,.25f,.26f);
        }
        return Mesh.from(b);
    }

    private static void vertex(GeoBuf b,float x,float y,float z,float nx,float ny,float nz,float[] c){
        float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        b.vn(x,y,z,nx/l,ny/l,nz/l,.5f,.5f,c[0],c[1],c[2]);
    }

    private static float[] sectionColor(float z){
        if(z>.68f)return new float[]{.72f,.75f,.77f};
        if(z<-.86f)return new float[]{.27f,.29f,.31f};
        if(z<-.70f)return new float[]{.48f,.50f,.52f};
        if(z>.47f)return new float[]{.62f,.65f,.67f};
        return new float[]{.78f,.79f,.77f};
    }

    private static void addRing(GeoBuf b,float z0,float r0,float z1,float r1,float cr,float cg,float cb){
        for(int i=0;i<SIDES;i++){
            float a0=(float)(Math.PI*2*i/SIDES),a1=(float)(Math.PI*2*(i+1)/SIDES);
            float x0=(float)Math.cos(a0),y0=(float)Math.sin(a0),x1=(float)Math.cos(a1),y1=(float)Math.sin(a1);
            b.quad(x0*r0,y0*r0,z0,x1*r0,y1*r0,z0,x1*r1,y1*r1,z1,x0*r1,y0*r1,z1,cr,cg,cb);
        }
    }

    private static void addFin(GeoBuf b,float a){
        float ux=(float)Math.cos(a),uy=(float)Math.sin(a),vx=-uy,vy=ux;
        float bx=.094f,tip=.285f,half=.045f;
        float zBack=-.66f,zFront=-.25f,tipZ=-.57f;
        float x0=ux*bx,y0=uy*bx,x1=ux*tip,y1=uy*tip;
        float[] p0={x0+vx*half,y0+vy*half,zBack};
        float[] p1={x0-vx*half,y0-vy*half,zBack};
        float[] p2={x0-vx*half,y0-vy*half,zFront};
        float[] p3={x0+vx*half,y0+vy*half,zFront};
        float[] pt={x1,y1,tipZ};
        tri(b,p0,p3,pt,.40f,.43f,.45f);tri(b,p1,pt,p2,.31f,.34f,.36f);
        tri(b,p0,pt,p1,.64f,.66f,.67f);tri(b,p3,p2,pt,.18f,.20f,.22f);
        // Bright narrow leading edge gives the fin a machined-metal silhouette.
        tri(b,p0,p1,pt,.73f,.75f,.76f);
    }

    private static void tri(GeoBuf b,float[] a,float[] c,float[] d,float r,float g,float bl){
        b.tri(a[0],a[1],a[2],c[0],c[1],c[2],d[0],d[1],d[2],r,g,bl);
        b.tri(d[0],d[1],d[2],c[0],c[1],c[2],a[0],a[1],a[2],r,g,bl);
    }

    private static void addPanel(GeoBuf b){
        // A small raised equipment panel and contrasting warning bars.
        b.box(0f,.109f,-.12f,.17f,.009f,.22f,.22f,.25f,.27f);
        b.box(0f,.116f,-.12f,.125f,.004f,.16f,.36f,.39f,.40f);
        for(int i=0;i<3;i++)b.box(-.037f+i*.037f,.119f,-.12f,.012f,.003f,.105f,.82f,.58f,.12f);
        for(int i=0;i<4;i++){
            float x=-.064f+i*.043f;
            b.box(x,.112f,-.245f,.012f,.006f,.012f,.12f,.13f,.14f);
        }
    }

    private static void addBar(GeoBuf b,float x,float y,float z,float sx,float sy,float sz,float r,float g,float bl){
        b.box(x,y,z,sx,sy,sz,r,g,bl);
    }
}
