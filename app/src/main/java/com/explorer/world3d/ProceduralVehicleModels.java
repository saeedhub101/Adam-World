package com.explorer.world3d;

/**
 * نماذج مركبات إجرائية بالكامل؛ لا تقرأ ملفات GLB أو أصولاً خارجية.
 * الأسطح تُولّد كشبكات parametric مع مقاطع Bezier طولية، وزجاج وعجلات وتفاصيل منفصلة.
 */
final class ProceduralVehicleModels {
    static final int SEDAN=0, SUV=1, PICKUP=2, BUS=3, POLICE=4, MOTORBIKE=5,
            BICYCLE=6, TANK=7, HELICOPTER=8, AIRPLANE=9, COUNT=10;
    private static final float PI=(float)Math.PI;
    private ProceduralVehicleModels() {}

    static Mesh[] buildAll() {
        Mesh[] out=new Mesh[COUNT];
        for(int i=0;i<COUNT;i++) out[i]=Mesh.from(build(i));
        return out;
    }
    static String name(int t) {
        switch(t){case SUV:return "SUV";case PICKUP:return "بيك أب";case BUS:return "باص";
            case POLICE:return "شرطة";case MOTORBIKE:return "دراجة نارية";case BICYCLE:return "بسكليت";
            case TANK:return "دبابة";case HELICOPTER:return "هليكوبتر";case AIRPLANE:return "طائرة";
            default:return "سيدان";}
    }
    private static GeoBuf build(int type) {
        GeoBuf b=new GeoBuf();
        float[] paint=type==POLICE?new float[]{.91f,.93f,.96f}:type==TANK?new float[]{.25f,.31f,.20f}:
                type==BUS?new float[]{.86f,.73f,.18f}:type==HELICOPTER?new float[]{.24f,.34f,.27f}:
                type==AIRPLANE?new float[]{.82f,.85f,.88f}:new float[]{.16f,.30f,.48f};
        if(type==BICYCLE||type==MOTORBIKE){ frame(b,type); }
        else if(type==TANK){ hull(b,paint,2.8f,5.1f,1.15f,.78f); turret(b); }
        else if(type==HELICOPTER){ hull(b,paint,1.1f,4.0f,.95f,.92f); tail(b); }
        else if(type==AIRPLANE){ hull(b,paint,.72f,7.0f,.82f,.82f); wings(b); tail(b); }
        else {
            float w=type==BUS?1.25f:type==PICKUP?1.0f:type==SUV?1.05f:.92f;
            float len=type==BUS?8.3f:type==PICKUP?5.2f:type==SUV?4.9f:4.35f;
            float h=type==BUS?2.25f:type==SUV?1.48f:1.28f;
            curvedBody(b,paint,w,len,h,type);
            cabin(b,type,w,len,h);
            details(b,type,w,len,h);
        }
        return b;
    }
    // Bezier longitudinal profile controls width/shoulder and roof height.
    private static float bez(float a,float b,float c,float d,float t){
        float q=1-t; return q*q*q*a+3*q*q*t*b+3*q*t*t*c+t*t*t*d;
    }
    private static void curvedBody(GeoBuf b,float[] c,float width,float len,float h,int type){
        int rings=28, around=20;
        float[][] p=new float[rings][around*3];
        for(int j=0;j<rings;j++){
            float t=j/(float)(rings-1), z=(t-.5f)*len;
            float end=(float)Math.min(1,Math.min(t*7,(1-t)*7));
            float w=width*(.76f+.24f*(float)Math.sin(Math.min(1,end)*PI/2));
            float roof=bez(.78f,.96f,1.02f,.72f,t)*h;
            for(int k=0;k<around;k++){
                float a=2*PI*k/around;
                p[j][k*3]=(float)Math.cos(a)*w;
                p[j][k*3+1]=h*.48f+(float)Math.sin(a)*roof*.48f;
                p[j][k*3+2]=z;
            }
        }
        for(int j=0;j<rings-1;j++) for(int k=0;k<around;k++){
            int n=(k+1)%around;
            float[] a=p[j], d=p[j+1];
            b.quad(a[k*3],a[k*3+1],a[k*3+2],a[n*3],a[n*3+1],a[n*3+2],
                    d[n*3],d[n*3+1],d[n*3+2],d[k*3],d[k*3+1],d[k*3+2],c[0],c[1],c[2]);
        }
        b.box(0,.42f*h,len*.49f,width*1.72f,.18f,.16f,.12f,.13f,.15f);
        b.box(0,.42f*h,-len*.49f,width*1.72f,.18f,.16f,.12f,.13f,.15f);
    }
    private static void hull(GeoBuf b,float[] color,float width,float len,float height,float centerY){
        int rings=30,around=18;
        float[][] p=new float[rings][around*3];
        for(int j=0;j<rings;j++){
            float t=j/(float)(rings-1),z=(t-.5f)*len;
            float taper=bez(.58f,.92f,1.0f,.52f,t);
            float w=width*(.62f+.38f*taper);
            float h=height*(.48f+.52f*(float)Math.sin(Math.min(1f,Math.min(t*5f,(1f-t)*5f))*PI*.5f));
            for(int k=0;k<around;k++){
                float a=2*PI*k/around;
                p[j][k*3]=(float)Math.cos(a)*w;
                p[j][k*3+1]=centerY+(float)Math.sin(a)*h*.5f;
                p[j][k*3+2]=z;
            }
        }
        for(int j=0;j<rings-1;j++)for(int k=0;k<around;k++){
            int n=(k+1)%around;float[] a=p[j],d=p[j+1];
            b.quad(a[k*3],a[k*3+1],a[k*3+2],a[n*3],a[n*3+1],a[n*3+2],
                    d[n*3],d[n*3+1],d[n*3+2],d[k*3],d[k*3+1],d[k*3+2],color[0],color[1],color[2]);
        }
    }
    /** Separate alpha glass geometry; the opaque body no longer paints over these panes. */
    static Mesh[] buildGlassAll() {
        Mesh[] out = new Mesh[COUNT];
        for (int type = 0; type < COUNT; type++) out[type] = Mesh.from(buildGlass(type));
        return out;
    }

    private static GeoBuf buildGlass(int type) {
        GeoBuf b = new GeoBuf();
        if (type == BICYCLE || type == MOTORBIKE || type == TANK) return b;
        if (type == AIRPLANE) {
            b.quad(-.46f,.93f,2.82f,.46f,.93f,2.82f,.34f,1.36f,2.25f,-.34f,1.36f,2.25f,.10f,.23f,.31f);
            b.quad(-.46f,.93f,2.82f,-.34f,1.36f,2.25f,-.34f,1.36f,1.72f,-.46f,.93f,1.72f,.10f,.23f,.31f);
            b.quad(.46f,.93f,2.82f,.46f,.93f,1.72f,.34f,1.36f,1.72f,.34f,1.36f,2.25f,.10f,.23f,.31f);
            return b;
        }
        if (type == HELICOPTER) {
            b.quad(-.72f,.83f,1.42f,.72f,.83f,1.42f,.58f,1.32f,1.05f,-.58f,1.32f,1.05f,.16f,.32f,.40f);
            b.quad(.72f,.83f,1.42f,-.72f,.83f,1.42f,-.58f,1.32f,1.05f,.58f,1.32f,1.05f,.16f,.32f,.40f);
            return b;
        }
        float w=type==BUS?1.25f:type==PICKUP?1.0f:type==SUV?1.05f:.92f;
        float len=type==BUS?8.3f:type==PICKUP?5.2f:type==SUV?4.9f:4.35f;
        float h=type==BUS?2.25f:type==SUV?1.48f:1.28f;
        float z0=type==BUS?-len*.34f:-len*.22f, z1=len*.20f;
        float top=type==BUS?h*1.02f:h*1.30f, cw=type==BUS?w*.91f:w*.78f;
        for(int side=-1;side<=1;side+=2){
            float x=side*cw;
            b.quad(x*1.006f,h*.67f,z0+.08f,x*.99f,top-.08f,z0+.38f,
                    x*.99f,top-.08f,z1-.28f,x*1.006f,h*.67f,z1-.08f,.10f,.23f,.31f);
        }
        b.quad(-cw,h*.66f,z1+.015f,cw,h*.66f,z1+.015f,cw*.92f,top,z1-.22f,-cw*.92f,top,z1-.22f,.10f,.23f,.31f);
        b.quad(cw,h*.66f,z0-.015f,-cw,h*.66f,z0-.015f,-cw*.92f,top,z0+.3f,cw*.92f,top,z0+.3f,.10f,.23f,.31f);
        return b;
    }

    /** Opaque roof and narrow structural pillars; window panes are drawn separately. */
    private static void cabin(GeoBuf b,int type,float w,float len,float h){
        float z0=type==BUS?-len*.34f:-len*.22f, z1=len*.20f;
        float top=type==BUS?h*1.02f:h*1.30f;
        float cw=type==BUS?w*.91f:w*.78f;
        b.box(0,top+.025f,(z0+z1)*.5f,cw*1.9f,.10f,z1-z0,.74f,.77f,.80f);
        for(int side=-1;side<=1;side+=2){
            b.box(side*cw*.99f,(h*.67f+top)*.5f,(z0+z1)*.5f,.045f,top-h*.67f,z1-z0,.28f,.30f,.32f);
        }
    }

    /** A wheel mesh centred at the origin; the renderer supplies spin and steering transforms. */
    static GeoBuf buildWheel() {
        GeoBuf b = new GeoBuf();
        float[] rubber={.025f,.03f,.035f}, rim={.48f,.52f,.56f};
        cylinderX(b,0,0,0,.32f,.24f,rubber);
        cylinderX(b,.125f,0,0,.22f,.012f,rim);
        cylinderX(b,-.125f,0,0,.22f,.012f,rim);
        cylinderX(b,.137f,0,0,.065f,.018f,new float[]{.16f,.18f,.20f});
        cylinderX(b,-.137f,0,0,.065f,.018f,new float[]{.16f,.18f,.20f});
        return b;
    }
    private static void details(GeoBuf b,int type,float w,float len,float h){
        float lamp=type==POLICE?1f:.96f;
        for(int side=-1;side<=1;side+=2){
            b.box(side*w*.66f,.58f*h,len*.505f,.22f,.16f,.035f,lamp,.88f,.58f);
            b.box(side*w*.66f,.55f*h,-len*.505f,.22f,.13f,.035f,.78f,.035f,.025f);
            b.box(side*w,.47f*h,0,.035f,.06f,len*.36f,.08f,.09f,.10f);
        }
        if(type==POLICE){ b.box(0,h*1.22f,-.05f,w*1.2f,.10f,.42f,.08f,.10f,.16f);
            b.box(-.30f,h*1.29f,-.05f,.35f,.10f,.18f,.12f,.25f,.95f);
            b.box(.30f,h*1.29f,-.05f,.35f,.10f,.18f,.95f,.10f,.08f); }
        if(type==PICKUP) b.box(0,h*.70f,-len*.30f,w*1.65f,.12f,len*.30f,.24f,.25f,.27f);
        if(type==BUS) for(int i=0;i<7;i++) b.box(0,.18f*h,(i-3)*.85f,w*1.9f,.07f,.025f,.16f,.17f,.18f);
    }
    private static void wheels(GeoBuf b,float radius,float halfZ){
        float[] rubber={.035f,.04f,.045f}, rim={.48f,.51f,.54f};
        for(int sx=-1;sx<=1;sx+=2) for(int sz=-1;sz<=1;sz+=2){
            float x=sx*.91f,z=sz*halfZ;
            cylinderX(b,x,.42f,z,radius*.72f,.23f,rubber);
            cylinderX(b,x+sx*.115f,.42f,z,radius*.43f,.018f,rim);
            for(int k=0;k<8;k++){float a=2*PI*k/8;
                b.box(x+sx*.13f,.42f+(float)Math.sin(a)*radius*.23f,z+(float)Math.cos(a)*radius*.23f,
                        .025f,.035f,.11f,.20f,.22f,.24f);}
        }
    }
    private static void bikeWheels(GeoBuf b,float radius,float halfZ){
        float[] rubber={.035f,.04f,.045f}, rim={.55f,.57f,.59f};
        for(int sz=-1;sz<=1;sz+=2){
            float z=sz*halfZ;
            cylinderX(b,0,radius,z,radius,.12f,rubber);
            cylinderX(b,0,radius,z,radius*.78f,.14f,rim);
            for(int k=0;k<12;k++){
                float a=2*PI*k/12;
                b.box(0,radius+(float)Math.sin(a)*radius*.5f,z+(float)Math.cos(a)*radius*.5f,
                        .035f,.018f,radius*1.35f,.45f,.47f,.49f);
            }
        }
    }
    private static void cylinderX(GeoBuf b,float x,float y,float z,float r,float width,float[] c){
        int n=18;
        for(int i=0;i<n;i++){
            float a=2*PI*i/n,d=2*PI*(i+1)/n;
            float y0=y+(float)Math.sin(a)*r,z0=z+(float)Math.cos(a)*r;
            float y1=y+(float)Math.sin(d)*r,z1=z+(float)Math.cos(d)*r;
            b.quad(x-width/2,y0,z0,x+width/2,y0,z0,x+width/2,y1,z1,x-width/2,y1,z1,c[0],c[1],c[2]);
        }
    }
    private static void frame(GeoBuf b,int type){
        float r=type==BICYCLE?.035f:.09f;
        float y=type==BICYCLE?.72f:.82f, z=type==BICYCLE?1.65f:1.45f;
        b.box(0,y,0,r,r,z,.75f,.12f,.035f);
        b.box(0,y*.8f,z*.38f,r,r,z*.40f,.72f,.12f,.03f);
        b.box(0,y*1.65f,-z*.35f,r,r,z*.28f,.65f,.13f,.035f);
        b.box(0,y*2.0f,-z*.34f,.34f,.06f,.12f,.08f,.08f,.08f);
        if(type==MOTORBIKE){b.box(0,y*1.55f,0,.48f,.42f,.75f,.16f,.18f,.20f);
            b.box(0,y*2.15f,.25f,.42f,.34f,.38f,.08f,.12f,.16f);}
    }
    private static void tracks(GeoBuf b){
        for(int s=-1;s<=1;s+=2){
            b.box(s*1.30f,.42f,0,.42f,.72f,4.55f,.09f,.10f,.09f);
            for(int i=0;i<12;i++) b.box(s*1.52f,.42f,-2.05f+i*.37f,.05f,.12f,.25f,.32f,.34f,.30f);
        }
    }
    private static void turret(GeoBuf b){
        b.box(0,1.28f,0,1.55f,.48f,1.8f,.22f,.27f,.18f);
        b.box(0,1.58f,.82f,.24f,.22f,2.3f,.18f,.20f,.16f);
        b.box(0,1.65f,1.95f,.16f,.15f,.42f,.10f,.11f,.10f);
    }
    private static void rotor(GeoBuf b){
        b.box(0,1.18f,0,.16f,.55f,.16f,.12f,.14f,.12f);
        b.box(0,1.55f,0,7.4f,.045f,.18f,.13f,.15f,.13f);
        b.box(0,1.55f,0,.18f,.045f,7.4f,.13f,.15f,.13f);
    }
    private static void tail(GeoBuf b){
        b.box(0,.82f,-2.7f,.16f,.16f,2.2f,.30f,.32f,.34f);
        b.box(0,1.10f,-3.55f,1.2f,.08f,.42f,.28f,.30f,.32f);
    }
    private static void wings(GeoBuf b){
        b.box(0,.85f,-.2f,10.5f,.14f,1.45f,.78f,.80f,.82f);
        b.box(0,.85f,-3.0f,3.1f,.12f,.85f,.74f,.76f,.78f);
        b.box(0,1.25f,-3.2f,.12f,.9f,.12f,.75f,.77f,.79f);
    }
}
