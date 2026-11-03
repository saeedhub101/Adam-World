package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CityRenderer implements GLSurfaceView.Renderer, Game.World {
    public interface Listener {
        void onLoading(boolean show, String msg);
        void onHud(String s);
        void onToast(String s);
    }

    static final int KEEP_R = 3;
    private static final String VS =
            "uniform mat4 uVP; uniform mat4 uModel; uniform vec3 uCam;\n"
            + "attribute vec3 aPos; attribute vec3 aNor; attribute vec2 aUV; attribute vec3 aCol;\n"
            + "varying vec2 vUV; varying vec3 vCol; varying vec3 vNor; varying float vDist; varying vec3 vWorld; varying vec3 vRel;\n"
            + "void main(){ vec4 wp=uModel*vec4(aPos,1.0); gl_Position=uVP*wp; vUV=aUV; vCol=aCol;\n"
            + " vNor=(uModel*vec4(aNor,0.0)).xyz; vDist=length(wp.xyz-uCam); vWorld=wp.xyz; vRel=wp.xyz-uCam; }";
    private static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
            + "uniform sampler2D uTex; uniform sampler2D uEmis; uniform sampler2D uNormalMap; uniform sampler2D uRoughMap; uniform sampler2D uMetalMap; uniform samplerCube uEnvMap; uniform vec3 uTint;\n"
            + "uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform vec3 uFog;\n"
            + "uniform float uFogN; uniform float uFogF; uniform float uEmisK; uniform float uWater; uniform float uDecal; uniform float uTime; uniform float uPbr; uniform float uGlass;\n"
            + "varying vec2 vUV; varying vec3 vCol; varying vec3 vNor; varying float vDist; varying vec3 vWorld; varying vec3 vRel;\n"
            + "void main(){\n"
            + " float f=clamp((vDist-uFogN)/(uFogF-uFogN),0.0,1.0);\n"
            + " if(uWater>0.5){\n"
            + "  vec2 p=vWorld.xz; float t=uTime;\n"
            + "  vec3 nrm=normalize(vec3(0.10*cos(p.x*0.35+t*1.2)+0.06*cos((p.x+p.y)*0.8+t*0.9)+0.03*cos(p.x*1.9-t*2.1), 1.0,\n"
            + "                          0.10*cos(p.y*0.31-t*1.0)+0.06*cos((p.x-p.y)*0.7-t*1.1)+0.03*cos(p.y*2.3+t*1.8)));\n"
            + "  vec3 V=normalize(-vRel);\n"
            + "  float fres=pow(1.0-max(dot(V,nrm),0.0),3.0);\n"
            + "  float lightK=0.35+0.65*clamp(uAmb.g*1.2+uSunCol.g*0.6,0.0,1.0);\n"
            + "  vec3 deep=vec3(0.03,0.24,0.34)*lightK;\n"
            + "  vec3 col=mix(deep,uFog*1.05,clamp(0.10+fres*0.85,0.0,1.0));\n"
            + "  vec3 R=reflect(-V,nrm);\n"
            + "  float spec=pow(max(dot(R,normalize(uSunDir)),0.0),90.0);\n"
            + "  col+=uSunCol*spec*1.3;\n"
            + "  gl_FragColor=vec4(mix(col,uFog,f),1.0);\n"
            + " } else {\n"
            + "  vec3 n=normalize(vNor); if(!gl_FrontFacing) n=-n;\n"
            + "  float d=max(dot(n,normalize(uSunDir)),0.0);\n"
            + "  vec4 tx=texture2D(uTex,vUV);\n"
            + "  vec3 base=tx.rgb*vCol*uTint;\n"
            + "  vec3 lit=base*(uAmb*(0.75+0.25*n.y)+uSunCol*d);\n"
            + "  vec3 V=normalize(uCam-vWorld);\n"
            + "  if(uGlass>0.5){\n"
            + "   vec3 env=textureCube(uEnvMap,reflect(-V,n)).rgb; float fres=pow(1.0-max(dot(n,V),0.0),5.0);\n"
            + "   gl_FragColor=vec4(mix(base*0.22,env,0.35+0.55*fres),0.32+0.38*fres);\n"
            + "  } else {\n"
            + "   if(uPbr>0.5){\n"
            + "    vec3 an=abs(n); vec3 w=pow(an,vec3(4.0)); w/=max(w.x+w.y+w.z,0.0001);\n"
            + "    vec2 uvX=vWorld.zy*0.42, uvY=vWorld.xz*0.42, uvZ=vWorld.xy*0.42;\n"
            + "    vec3 sx=texture2D(uNormalMap,uvX).xyz*2.0-1.0; vec3 sy=texture2D(uNormalMap,uvY).xyz*2.0-1.0; vec3 sz=texture2D(uNormalMap,uvZ).xyz*2.0-1.0;\n"
            + "    vec3 nx=normalize(vec3(sign(n.x)*sx.z,sx.y,-sign(n.x)*sx.x));\n"
            + "    vec3 ny=normalize(vec3(sy.x,sign(n.y)*sy.z,-sign(n.y)*sy.y));\n"
            + "    vec3 nz=normalize(vec3(sign(n.z)*sz.x,sz.y,sign(n.z)*sz.z));\n"
            + "    n=normalize(nx*w.x+ny*w.y+nz*w.z); d=max(dot(n,normalize(uSunDir)),0.0);\n"
            + "    vec3 L=normalize(uSunDir), H=normalize(V+L);\n"
            + "    float rough=clamp(texture2D(uRoughMap,uvX).r*w.x+texture2D(uRoughMap,uvY).r*w.y+texture2D(uRoughMap,uvZ).r*w.z,0.08,0.96);\n"
            + "    float metal=clamp(texture2D(uMetalMap,uvX).r*w.x+texture2D(uMetalMap,uvY).r*w.y+texture2D(uMetalMap,uvZ).r*w.z,0.0,1.0);\n"
            + "    float a=rough*rough, a2=a*a; float nh=max(dot(n,H),0.0), nv=max(dot(n,V),0.001), vh=max(dot(V,H),0.0);\n"
            + "    float den=nh*nh*(a2-1.0)+1.0; float D=a2/max(3.14159*den*den,0.001);\n"
            + "    float k=(rough+1.0)*(rough+1.0)/8.0; float G=(nv/(nv*(1.0-k)+k))*(d/(d*(1.0-k)+k));\n"
            + "    vec3 F0=mix(vec3(0.04),base,metal); vec3 F=F0+(1.0-F0)*pow(1.0-vh,5.0);\n"
            + "    vec3 spec=D*G*F/max(4.0*nv*max(d,0.001),0.001); vec3 diffuse=(1.0-F)*(1.0-metal)*base/3.14159;\n"
            + "    vec3 env=textureCube(uEnvMap,reflect(-V,n)).rgb;\n"
            + "    lit=base*uAmb*0.62+(diffuse+spec)*uSunCol*d*2.4+env*F*(0.12+metal*0.32);\n"
            + "   }\n"
            + "   lit+=texture2D(uEmis,vUV).rgb*uEmisK;\n"
            + "  }\n"
            + "  if(uGlass>0.5){} else if(uDecal>0.5){ gl_FragColor=vec4(mix(lit,uFog*tx.a,f),tx.a); }\n"
            + "  else { gl_FragColor=vec4(mix(lit,uFog,f),1.0); }\n"
            + " }\n"
            + "}";
    // ES 2.0 guarantees only 16 fragment uniform vectors. The full PBR/glass
    // shader exceeds that minimum on some devices, so retain a compatible path.
    private static final String FS_COMPAT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\\nprecision highp float;\\n#else\\nprecision mediump float;\\n#endif\\n"
            + "uniform sampler2D uTex; uniform sampler2D uEmis; uniform vec3 uTint;\\n"
            + "uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform vec3 uFog;\\n"
            + "uniform float uFogN; uniform float uFogF; uniform float uEmisK; uniform float uWater; uniform float uTime;\\n"
            + "varying vec2 vUV; varying vec3 vCol; varying vec3 vNor; varying float vDist; varying vec3 vWorld; varying vec3 vRel;\\n"
            + "void main(){ float f=clamp((vDist-uFogN)/max(uFogF-uFogN,0.001),0.0,1.0);\\n"
            + " if(uWater>0.5){ vec2 p=vWorld.xz; float wave=0.5+0.5*sin(p.x*0.22+p.y*0.17+uTime); vec3 c=mix(vec3(0.02,0.16,0.23),uFog,0.18+0.24*wave); gl_FragColor=vec4(mix(c,uFog,f),1.0); }\\n"
            + " else { vec3 n=normalize(vNor); if(!gl_FrontFacing)n=-n; float d=max(dot(n,normalize(uSunDir)),0.0); vec4 tx=texture2D(uTex,vUV); vec3 base=tx.rgb*vCol*uTint; vec3 lit=base*(uAmb*(0.75+0.25*n.y)+uSunCol*d)+texture2D(uEmis,vUV).rgb*uEmisK; gl_FragColor=vec4(mix(lit,uFog,f),tx.a); } }";
    private boolean advancedShader = false;

    private static final String SKY_VS =
            "attribute vec2 aP; varying float vY;\n"
            + "void main(){ gl_Position=vec4(aP,0.999,1.0); vY=aP.y*0.5+0.5; }";
    private static final String SKY_FS =
            "precision mediump float; uniform vec3 uTop; uniform vec3 uHor; varying float vY;\n"
            + "void main(){ gl_FragColor=vec4(mix(uHor,uTop,smoothstep(0.35,1.0,vY)),1.0); }";

    // نهار / غروب / ليل
    private static final float[][] TIME = {
            // sunDir(3) sunCol(3) amb(3) fog(3) top(3) hor(3) fogN fogF emisK
            {0.5f, 0.8f, 0.3f, 0.62f, 0.60f, 0.55f, 0.55f, 0.58f, 0.64f, 0.81f, 0.90f, 0.96f, 0.31f, 0.56f, 0.84f, 0.81f, 0.90f, 0.96f, 220f, 1900f, 0f},
            {0.9f, 0.25f, -0.3f, 0.75f, 0.45f, 0.25f, 0.48f, 0.40f, 0.40f, 0.91f, 0.69f, 0.53f, 0.18f, 0.25f, 0.45f, 0.95f, 0.65f, 0.37f, 200f, 1800f, 0.35f},
            {-0.4f, 0.8f, 0.3f, 0.14f, 0.18f, 0.32f, 0.20f, 0.24f, 0.38f, 0.05f, 0.08f, 0.15f, 0.02f, 0.03f, 0.06f, 0.09f, 0.13f, 0.23f, 150f, 1500f, 1f}};

    private final Context ctx;
    private final Listener L;
    final Game game = new Game();
    private final TileLoader loader;
    private final HashMap<Long, Tile> tiles = new HashMap<>();
    private int prog, skyProg;
    private int uVP, uModel, uCam, uTint, uTex, uEmis, uSunDir, uSunCol, uAmb, uFog, uFogN, uFogF, uEmisK, uTop, uHor, uWater, uDecal, uTime, uPbr, uGlass, uNormalMap, uRoughMap, uMetalMap, uEnvMap;
    private Textures tex;
    private ProceduralMaterialMaps vehicleMaps;
    private Mesh cube, groundMesh, skyQuad, seaMesh;
    private Mesh[] vehicleModels;
    private Mesh[] vehicleGlassModels;
    private Mesh wheelMesh;
    private final float[] wheelMatrix = new float[16];
    private Mesh[][] humanRigModels;
    private volatile int playerVehicleType = ProceduralVehicleModels.SEDAN;
    private final HashMap<Long, Mesh> terrain = new HashMap<>();
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] ident = new float[16], tmp = new float[16], base = new float[16];
    private float aspect = 1f;
    private long lastN = System.nanoTime(), lastUnload, lastAgentMs, lastHudMs, spawnDeadline;
    private boolean started, needSpawn, spawned, spawnTimeoutNotified;
    private float lookDx, lookDy;
    private volatile float gamepadLookX, gamepadLookY;
    public volatile int timeIdx = 0;
    private int environmentTimeIdx = -1;
    public volatile boolean undergroundCutaway = false;

    public void toggleUndergroundCutaway() { undergroundCutaway = !undergroundCutaway; }
    private long lastErrToast;
    private final Random rng = new Random();

    public CityRenderer(Context ctx, Listener l) {
        this.ctx = ctx;
        this.L = l;
        Matrix.setIdentityM(ident, 0);
        Terrain.init(new File(ctx.getCacheDir(), "dem"), new AndroidDecoder(), ctx.getAssets());
        loader = new TileLoader(ctx, new File(ctx.getCacheDir(), "tiles"), new TileLoader.ErrorCallback() {
            @Override public void onError() {
                long now = System.currentTimeMillis();
                if (now - lastErrToast > 20000) {
                    lastErrToast = now;
                    L.onToast(loader.offlineAmman ? "تعذر تنزيل بيانات عمّان المحلية. تحقق من الإنترنت"
                            : loader.offlineManila ? "تعذر تنزيل بيانات مانيلا المحلية" : loader.offlineCitySlug != null ? "تعذر قراءة بيانات المدينة المحلية"
                            : "تعذر تحميل بيانات الخريطة، تحقق من الإنترنت");
                }
            }
        });
        loader.start();
    }

    public void shutdown() { loader.shutdown(); }

    public synchronized void addLook(float dx, float dy) { lookDx += dx; lookDy += dy; }

    public void setGamepadLook(float x, float y) {
        gamepadLookX = Math.max(-1f, Math.min(1f, x));
        gamepadLookY = Math.max(-1f, Math.min(1f, y));
    }

    /* ---------- GL ---------- */
    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs, String[] attrs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        for (int i = 0; i < attrs.length; i++) GLES20.glBindAttribLocation(p, i, attrs[i]);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static Mesh makeCube() {
        GeoBuf b = new GeoBuf();
        b.box(0, 0, 0, 1, 1, 1, 1, 1, 1);
        // الوجه السفلي
        b.quad(-.5f, -.5f, -.5f, -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, -.5f, -.5f, 1, 1, 1);
        return Mesh.from(b);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        try {
            prog = link(VS, FS, new String[]{"aPos", "aNor", "aUV", "aCol"});
            advancedShader = true;
        } catch (RuntimeException incompatiblePbrShader) {
            Log.w("AdamWorldGL", "PBR shader unavailable; using GLES 2.0 compatible renderer", incompatiblePbrShader);
            prog = link(VS, FS_COMPAT, new String[]{"aPos", "aNor", "aUV", "aCol"});
            advancedShader = false;
        }
        uVP = GLES20.glGetUniformLocation(prog, "uVP");
        uModel = GLES20.glGetUniformLocation(prog, "uModel");
        uCam = GLES20.glGetUniformLocation(prog, "uCam");
        uTint = GLES20.glGetUniformLocation(prog, "uTint");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uEmis = GLES20.glGetUniformLocation(prog, "uEmis");
        uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir");
        uSunCol = GLES20.glGetUniformLocation(prog, "uSunCol");
        uAmb = GLES20.glGetUniformLocation(prog, "uAmb");
        uFog = GLES20.glGetUniformLocation(prog, "uFog");
        uFogN = GLES20.glGetUniformLocation(prog, "uFogN");
        uFogF = GLES20.glGetUniformLocation(prog, "uFogF");
        uEmisK = GLES20.glGetUniformLocation(prog, "uEmisK");
        uWater = GLES20.glGetUniformLocation(prog, "uWater");
        uDecal = GLES20.glGetUniformLocation(prog, "uDecal");
        uTime = GLES20.glGetUniformLocation(prog, "uTime");
        uPbr = GLES20.glGetUniformLocation(prog, "uPbr");
        uGlass = GLES20.glGetUniformLocation(prog, "uGlass");
        uNormalMap = GLES20.glGetUniformLocation(prog, "uNormalMap");
        uRoughMap = GLES20.glGetUniformLocation(prog, "uRoughMap");
        uMetalMap = GLES20.glGetUniformLocation(prog, "uMetalMap");
        uEnvMap = GLES20.glGetUniformLocation(prog, "uEnvMap");
        skyProg = link(SKY_VS, SKY_FS, new String[]{"aP"});
        uTop = GLES20.glGetUniformLocation(skyProg, "uTop");
        uHor = GLES20.glGetUniformLocation(skyProg, "uHor");

        tex = new Textures();
        vehicleMaps = advancedShader ? ProceduralMaterialMaps.create() : null;
        cube = makeCube();
        vehicleModels = ProceduralVehicleModels.buildAll();
        vehicleGlassModels = advancedShader ? ProceduralVehicleModels.buildGlassAll() : null;
        wheelMesh = Mesh.from(ProceduralVehicleModels.buildWheel());
        humanRigModels = ProceduralHumanRig.buildAll();
        GeoBuf g = new GeoBuf();
        float E = 4000f, T = E * 2f / 13.3333f;
        g.v(-E, 0, -E, 0, 0, 1, 1, 1); g.v(E, 0, -E, T, 0, 1, 1, 1); g.v(E, 0, E, T, T, 1, 1, 1); g.fixTri();
        g.v(-E, 0, -E, 0, 0, 1, 1, 1); g.v(E, 0, E, T, T, 1, 1, 1); g.v(-E, 0, E, 0, T, 1, 1, 1); g.fixTri();
        groundMesh = Mesh.from(g);
        GeoBuf sg = new GeoBuf();
        float SE = 9000f;
        sg.v(-SE, 0, -SE, 0, 0, 1, 1, 1); sg.v(SE, 0, -SE, 0, 0, 1, 1, 1); sg.v(SE, 0, SE, 0, 0, 1, 1, 1); sg.fixTri();
        sg.v(-SE, 0, -SE, 0, 0, 1, 1, 1); sg.v(SE, 0, SE, 0, 0, 1, 1, 1); sg.v(-SE, 0, SE, 0, 0, 1, 1, 1); sg.fixTri();
        seaMesh = Mesh.from(sg);
        terrain.clear();
        skyQuad = Mesh.create(new float[]{-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1}, 12, 2);

        for (Tile t : tiles.values()) t.dead = true;
        tiles.clear();
        loader.requested.clear();
        loader.ready.clear();

        if (!started) {
            started = true;
            loader.offlineAmman = false;
            loader.offlineManila = false;
            loader.offlineCitySlug = "tabarbour";
            Geo.setOrigin(31.997579, 35.940358);
            Terrain.configureForCity(Geo.lat0, Geo.lon0);
            game.reset();
            beginSpawn();
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        aspect = w / (float) Math.max(1, h);
    }

    private void beginSpawn() {
        needSpawn = true;
        spawned = false;
        spawnTimeoutNotified = false;
        spawnDeadline = System.currentTimeMillis() + 30000;
        // Seed the loader with the selected city's origin before waking its worker;
        // otherwise the first request can incorrectly target tile (0,0) on Earth.
        loader.ptx = Geo.tileX(0f);
        loader.ptz = Geo.tileZ(0f);
        loader.havePlayer = true;
        L.onLoading(true, loader.offlineAmman ? "جاري تحميل عمّان المحلية…" : loader.offlineManila ? "جاري تحميل مانيلا المحلية…" : loader.offlineCitySlug != null ? "جاري تحميل المدينة المحلية…" : "جاري تحميل المدينة…");
    }

    /** ينقل اللاعب إلى مدينة/موقع جديد. يُستدعى من خيط الرسم. */
    public void teleport(double lat, double lon) { teleportInternal(lat, lon, false, null); }

    public void teleportOfflineAmman() { teleportInternal(31.9522646, 35.9389907, true, null); }

    public void teleportOfflineManila() { teleportInternal(14.57394, 120.98283, false, "manila"); }

    public void teleportOfflineTabarbour() {
        // Home always refreshes cached cells so the latest merged footprints,
        // heights, addresses and road attributes are shown instead of an old snapshot.
        loader.refreshTabarbourDataset();
        teleportInternal(31.997579, 35.940358, false, "tabarbour");
    }

    private void teleportInternal(double lat, double lon, boolean offline, String offlineCity) {
        loader.gen++;
        loader.cancelCurrentLoad();
        Terrain.cancelPending();
        loader.offlineAmman = offline;
        loader.offlineManila = "manila".equals(offlineCity);
        loader.offlineCitySlug = loader.offlineManila ? null : offlineCity;
        loader.resetOfflineShardState();
        loader.requested.clear(); loader.failedAt.clear(); loader.ready.clear();
        for (Tile t : tiles.values()) t.release();
        tiles.clear();
        for (Mesh m : terrain.values()) m.release();
        terrain.clear();
        Terrain.reset();
        Geo.setOrigin(lat, lon);
        Terrain.configureForCity(lat, lon);
        game.reset();
        beginSpawn();
    }

    public void toggleCar() {
        Game g = game;
        if (!spawned) return;
        if (!g.driving) {
            float d = (float) Math.hypot(g.px - g.cx, g.pz - g.cz);
            if (d < 8f) {
                g.driving = true; g.cv = 0; g.vehicleType = playerVehicleType; g.altitude = 0f;
                L.onToast("قيادة: العصا للتسارع والتوجيه");
            } else {
                float fx = (float) Math.sin(g.pyaw), fz = (float) Math.cos(g.pyaw);
                float[][] tries = {{fx * 5, fz * 5}, {-fz * 4, fx * 4}, {fz * 4, -fx * 4}, {-fx * 5, -fz * 5}};
                boolean ok = false;
                for (float[] t : tries) {
                    float x = g.px + t[0], z = g.pz + t[1];
                    if (!blocked(x, z, 2.2f)) { g.cx = x; g.cz = z; g.cyaw = g.pyaw; g.cv = 0; ok = true; break; }
                }
                L.onToast(ok ? "تم استدعاء السيارة، اضغط 🚗 للركوب" : "لا توجد مساحة لاستدعاء السيارة");
            }
        } else {
            float rx = -(float) Math.cos(g.cyaw), rz = (float) Math.sin(g.cyaw);
            float x = g.cx + rx * 2.6f, z = g.cz + rz * 2.6f;
            if (blocked(x, z, 0.5f)) { x = g.cx - rx * 2.6f; z = g.cz - rz * 2.6f; }
            if (blocked(x, z, 0.5f)) { x = g.cx; z = g.cz; }
            g.px = x; g.pz = z; g.pyaw = g.cyaw; g.driving = false; g.cv = 0; g.altitude = 0f; g.liftInput = 0f;
        }
    }

    @Override public float heightAt(float x, float z) {
        if (loader.offlineManila) return 0f;
        float lm = Landmarks.heightAt(x, z);
        return Float.isNaN(lm) ? Terrain.h(x, z) : lm;
    }

    /** يبني مربعات الأرض من الارتفاعات الحقيقية حول اللاعب ويحذف البعيد منها. */
    private void updateTerrain(int ptx, int ptz) {
        if (loader.offlineManila || !Terrain.enabled || !Terrain.baseSet) return;
        Iterator<Map.Entry<Long, Mesh>> it = terrain.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Mesh> en = it.next();
            long k = en.getKey();
            int kx = (int) (k >> 32), kz = (int) k;
            if (Math.abs(kx - ptx) > 3 || Math.abs(kz - ptz) > 3) { en.getValue().release(); it.remove(); }
        }
        int bx = 0, bz = 0, bd = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int ix = ptx + dx, iz = ptz + dz;
                if (terrain.containsKey(Geo.key(ix, iz))) continue;
                double lat = (iz + 0.5) * Geo.D_LAT, lon = (ix + 0.5) * Geo.dLon;
                if (!Terrain.has(lat, lon)) continue;
                int d = dx * dx + dz * dz;
                if (d < bd) { bd = d; bx = ix; bz = iz; }
            }
        }
        if (bd != Integer.MAX_VALUE) {
            Mesh m = Mesh.from(TerrainMesh.build(bx, bz, "tabarbour".equals(loader.offlineCitySlug)));
            if (m != null) terrain.put(Geo.key(bx, bz), m);
        }
    }

    /* ---------- التصادم ---------- */
    private boolean hit(float x, float z) {
        int ix = Geo.tileX(x), iz = Geo.tileZ(z);
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                Tile t = tiles.get(Geo.key(ix + di, iz + dj));
                if (t != null && Collision.hit(t.grid, x, z)) return true;
            }
        }
        return false;
    }

    @Override public boolean blocked(float x, float z, float r) {
        return hit(x, z) || hit(x + r, z) || hit(x - r, z) || hit(x, z + r) || hit(x, z - r);
    }

    /* ---------- حلقة الرسم ---------- */
    private boolean doSpawn(Tile c) {
        float bestScore = Float.MAX_VALUE;
        float bestX = 0f, bestZ = 0f, bestYaw = 0f, bestCarX = 0f, bestCarZ = 0f;
        boolean bestHasRoad = false;
        float[] point = new float[4], car = new float[4];

        // Search every tile already loaded around the start, not only the single
        // tile containing (0,0). The old code also placed the player beside a road
        // without checking whether that position was inside a building footprint.
        for (Tile tile : tiles.values()) {
            for (RoadPath rp : tile.paths) {
                if (rp == null || rp.xs == null || rp.cum == null || rp.xs.length == 0) continue;
                boolean vehicleRoad = rp.veh && rp.w >= 5f;
                for (int i = 0; i < rp.xs.length && i < rp.cum.length; i++) {
                    float d = (float) Math.hypot(rp.xs[i], rp.zs[i]);
                    if (d > 220f) continue;
                    float s = Math.min(rp.cum[i], Math.max(0f, rp.total - 0.2f));
                    rp.posAt(s, 0f, point);
                    float yaw = (float) Math.atan2(point[2], point[3]);
                    float sideX = -point[3], sideZ = point[2];
                    float offset = vehicleRoad ? rp.w * 0.5f + 1.8f : 0f;
                    for (int side : new int[]{-1, 1}) {
                        float x = point[0] + sideX * offset * side;
                        float z = point[1] + sideZ * offset * side;
                        if (blocked(x, z, 0.7f)) continue;
                        float score = d + (vehicleRoad ? 0f : 18f);
                        if (score >= bestScore) continue;
                        bestScore = score;
                        bestX = x; bestZ = z; bestYaw = yaw;
                        bestHasRoad = vehicleRoad;
                        if (vehicleRoad) {
                            rp.posAt(s, rp.w * 0.25f, car);
                            bestCarX = car[0]; bestCarZ = car[1];
                        } else {
                            bestCarX = x; bestCarZ = z;
                        }
                    }
                }
            }
        }

        // If no mapped road/path candidate is collision-free, search for a genuinely
        // clear nearby point. Do not fall back to the fixed origin inside a building.
        if (bestScore == Float.MAX_VALUE && !tiles.isEmpty()) {
            for (int radius = 4; radius <= 100 && bestScore == Float.MAX_VALUE; radius += 4) {
                for (int step = 0; step < 24; step++) {
                    float angle = (float) (step * Math.PI / 12.0);
                    float x = (float) Math.cos(angle) * radius;
                    float z = (float) Math.sin(angle) * radius;
                    if (!blocked(x, z, 0.7f)) {
                        bestX = bestCarX = x; bestZ = bestCarZ = z;
                        bestYaw = 0f; bestScore = radius; bestHasRoad = false;
                        break;
                    }
                }
            }
        }

        // Wait for the origin/nearby collision tiles instead of spawning inside
        // an uninspected building when the first tile has not arrived yet.
        if (bestScore == Float.MAX_VALUE) return false;

        game.reset();
        game.px = bestX; game.pz = bestZ; game.pyaw = bestYaw; game.camYaw = bestYaw;
        game.cx = bestCarX; game.cz = bestCarZ; game.cyaw = bestYaw;
        game.py = heightAt(game.px, game.pz);
        game.snapCamera();
        needSpawn = false;
        spawned = true;
        L.onLoading(false, "");
        return true;
    }

    private void uploadTile(TileData td) {
        Tile t = new Tile(td);
        Tile old = tiles.put(t.key, t);
        if (old != null) old.release();
    }

    private void spawnAgents() {
        if (game.agents.size() >= 34 || tiles.isEmpty()) return;
        ArrayList<Tile> list = new ArrayList<>(tiles.values());
        Tile t = list.get(rng.nextInt(list.size()));
        if (t.paths.isEmpty()) return;
        RoadPath p = t.paths.get(rng.nextInt(t.paths.size()));
        float fx = game.focusX(), fz = game.focusZ();
        float[] o = new float[4];
        float s = rng.nextFloat() * p.total;
        p.posAt(s, 0, o);
        float d = (float) Math.hypot(o[0] - fx, o[1] - fz);
        if (d < 25f || d > 380f) return;
        Game.Agent a = new Game.Agent();
        a.path = p; a.tile = t; a.s = s; a.dir = rng.nextBoolean() ? 1 : -1;
        a.car = p.veh && rng.nextFloat() < 0.72f;
        if (a.car) {
            int[] roadTypes = {ProceduralVehicleModels.SEDAN, ProceduralVehicleModels.SUV, ProceduralVehicleModels.PICKUP, ProceduralVehicleModels.BUS, ProceduralVehicleModels.POLICE, ProceduralVehicleModels.MOTORBIKE, ProceduralVehicleModels.BICYCLE};
            a.vehicleType = roadTypes[rng.nextInt(roadTypes.length)];
            a.off = p.w / 4f; a.sp = 7f + rng.nextFloat() * 7f;
            float[][] cols = {{.93f, .94f, .95f}, {.17f, .24f, .31f}, {.5f, .55f, .55f}, {.95f, .77f, .06f}, {.16f, .5f, .74f}, {.83f, .33f, 0f}, {.15f, .68f, .38f}, {.07f, .07f, .07f}};
            float[] c = cols[rng.nextInt(cols.length)];
            a.cr = c[0]; a.cg = c[1]; a.cb = c[2];
        } else {
            a.off = p.veh ? (p.w / 2f + 1.6f) * (rng.nextBoolean() ? 1 : -1) : (rng.nextFloat() - 0.5f) * 0.8f;
            a.sp = 1.1f + rng.nextFloat() * 0.7f; a.ph = rng.nextFloat() * 6f;
            float[][] cols = {{.2f, .6f, .86f}, {.9f, .5f, .15f}, {.1f, .74f, .6f}, {.6f, .35f, .72f}, {.93f, .94f, .95f}, {.95f, .6f, .07f}};
            float[] c = cols[rng.nextInt(cols.length)];
            a.cr = c[0]; a.cg = c[1]; a.cb = c[2];
        }
        game.agents.add(a);
    }

    @Override public void onDrawFrame(GL10 gl) {
        long nowN = System.nanoTime();
        float dt = Math.min(0.1f, (nowN - lastN) / 1e9f);
        if (dt <= 0f) dt = 0.016f;
        lastN = nowN;
        long nowMs = System.currentTimeMillis();

        float ldx, ldy;
        synchronized (this) { ldx = lookDx; ldy = lookDy; lookDx = 0; lookDy = 0; }
        if (ldx != 0 || ldy != 0) game.look(ldx, ldy);
        float gx = gamepadLookX, gy = gamepadLookY;
        if (gx != 0f || gy != 0f) game.look(gx * dt * 260f, gy * dt * 260f);

        TileData td;
        int ups = 0;
        long uploadDeadline = System.nanoTime() + 7000000L;
        while (ups < 2 && System.nanoTime() < uploadDeadline && (td = loader.ready.poll()) != null) {
            if (td.gen != loader.gen) continue;
            uploadTile(td);
            ups++;
        }

        int ptx = Geo.tileX(game.focusX()), ptz = Geo.tileZ(game.focusZ());
        loader.ptx = ptx; loader.ptz = ptz;

        if (nowMs - lastUnload > 1000) {
            lastUnload = nowMs;
            Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
            while (it.hasNext()) {
                Tile t = it.next().getValue();
                if (Math.abs(t.ix - ptx) > KEEP_R || Math.abs(t.iz - ptz) > KEEP_R) {
                    t.release();
                    it.remove();
                    loader.requested.remove(t.key);
                }
            }
        }

        updateTerrain(ptx, ptz);

        if (needSpawn) {
            Tile c = tiles.get(Geo.key(Geo.tileX(0f), Geo.tileZ(0f)));
            if (c != null || nowMs > spawnDeadline) {
                if (!doSpawn(c)) {
                    // Keep retrying while allowing city controls to remain usable.
                    // A missing/failed tile must never trap the user behind a loading screen.
                    if (!spawnTimeoutNotified) {
                        spawnTimeoutNotified = true;
                        L.onLoading(false, "");
                        L.onToast("تأخر تحميل المدينة. يمكنك اختيار مدينة أخرى أو الانتظار؛ سيستمر التحميل في الخلفية.");
                    }
                    spawnDeadline = nowMs + 5000;
                }
            }
        }

        if (spawned) {
            game.update(dt, this);
            if (nowMs - lastAgentMs > 700) { lastAgentMs = nowMs; spawnAgents(); }
            if (nowMs - lastHudMs > 200) {
                lastHudMs = nowMs;
                L.onHud(game.driving ? Math.round(Math.abs(game.cv) * 3.6f) + " كم/س" : "");
            }
        }

        draw();
    }

    private void draw() {
        if (tex != null) tex.setCityProfile("tabarbour".equals(loader.offlineCitySlug), loader.offlineManila);
        int ti = timeIdx % TIME.length;
        float[] T = TIME[ti];
        if(vehicleMaps!=null && environmentTimeIdx!=ti) {
            vehicleMaps.updateEnvironment(T[12],T[13],T[14],T[15],T[16],T[17],T[9],T[10],T[11]);
            environmentTimeIdx=ti;
        }
        GLES20.glClearColor(T[9], T[10], T[11], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // السماء
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(skyProg);
        GLES20.glUniform3f(uTop, T[12], T[13], T[14]);
        GLES20.glUniform3f(uHor, T[15], T[16], T[17]);
        GLES20.glDisableVertexAttribArray(1);
        GLES20.glDisableVertexAttribArray(2);
        GLES20.glDisableVertexAttribArray(3);
        GLES20.glEnableVertexAttribArray(0);
        skyQuad.drawSky();
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        Game g = game;
        Matrix.perspectiveM(proj, 0, 65f, aspect, 0.3f, 2600f);
        Matrix.setLookAtM(view, 0, g.camX, g.camY, g.camZ, g.tgtX, g.tgtY, g.tgtZ, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(prog);
        for (int i = 0; i < 4; i++) GLES20.glEnableVertexAttribArray(i);
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        GLES20.glUniform3f(uCam, g.camX, g.camY, g.camZ);
        GLES20.glUniform3f(uSunDir, T[0], T[1], T[2]);
        GLES20.glUniform3f(uSunCol, T[3], T[4], T[5]);
        GLES20.glUniform3f(uAmb, T[6], T[7], T[8]);
        GLES20.glUniform3f(uFog, T[9], T[10], T[11]);
        GLES20.glUniform1f(uFogN, T[18]);
        GLES20.glUniform1f(uFogF, T[19]);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1i(uEmis, 1);
        GLES20.glUniform1i(uNormalMap, 2);
        GLES20.glUniform1i(uRoughMap, 3);
        GLES20.glUniform1i(uMetalMap, 4);
        GLES20.glUniform1i(uEnvMap, 5);
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0);
        GLES20.glUniform1f(uWater, 0f);
        GLES20.glUniform1f(uDecal, 0f);
        GLES20.glUniform1f(uTime, (float) ((System.nanoTime() / 1000000L) % 600000L) / 1000f);

        float em = T[20];
        // الأرض: مستوى بعيد احتياطي ثم مربعات التضاريس الحقيقية
        bindTex(tex.ground, tex.black);
        GLES20.glUniform1f(uEmisK, 0f);
        float step = 13.3333f;
        Matrix.setIdentityM(tmp, 0);
        float farY = Terrain.baseSet ? Terrain.seaY() - 300f : -0.02f;
        Matrix.translateM(tmp, 0, Math.round(g.camX / step) * step, farY, Math.round(g.camZ / step) * step);
        GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
        groundMesh.draw();
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0);
        if (!undergroundCutaway) {
            for (Mesh m : terrain.values()) m.draw();
        }

        // المربعات
        for (Tile t : tiles.values()) {
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > 1750) continue;
            drawMesh(t.meshes[TileBuilder.B_SIDEWALK], tex.sidewalk, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_ROADN], tex.roadN, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_ROADW], tex.roadW, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_PLAIN], tex.white, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_FACADE], tex.facade, tex.facadeEm, em);
            drawMesh(t.meshes[TileBuilder.B_GLASS], tex.glass, tex.glassEm, em);
            drawMesh(t.meshes[TileBuilder.B_SIGN], tex.sign, tex.sign, 1f);
        }

        // الماء: بحر (إن وُجد ساحل) + أنهار وبحيرات، بمظلل يعكس السماء
        GLES20.glUniform1f(uWater, 1f);
        bindTex(tex.white, tex.black);
        GLES20.glUniform1f(uEmisK, 0f);
        boolean coast = false;
        for (Tile t : tiles.values()) {
            if (t.hasCoast) coast = true;
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > 1750) continue;
            if (t.meshes[TileBuilder.B_WATER] != null) t.meshes[TileBuilder.B_WATER].draw();
        }
        if (coast) {
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, g.camX, (Terrain.enabled && Terrain.baseSet ? Terrain.seaY() : 0f) + 0.05f, g.camZ);
            GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
            seaMesh.draw();
            GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0);
        }
        GLES20.glUniform1f(uWater, 0f);

        // أسماء الشوارع المطبوعة على الأسفلت
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uDecal, 1f);
        for (Tile t : tiles.values()) {
            if (t.decalMeshes.isEmpty()) continue;
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > 850) continue;
            for (int page = 0; page < t.decalMeshes.size(); page++) {
                bindTex(t.decalTextures.get(page), tex.black);
                t.decalMeshes.get(page).draw();
            }
        }
        GLES20.glUniform1f(uDecal, 0f);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);

        // الكائنات المتحركة
        bindTex(tex.white, tex.black);
        GLES20.glUniform1f(uEmisK, 0f);
        for (Game.Agent a : g.agents) {
            if (Math.hypot(a.x - g.camX, a.z - g.camZ) > 350) continue;
            if (a.car) drawVehicle(a.x, a.z, a.yaw, a.vehicleType, 0f, a.cr, a.cg, a.cb, a.sp * a.dir, 0f);
            else drawPerson(a.x, Terrain.h(a.x, a.z), a.z, a.yaw, a.ph, a.humanType);
        }
        if (spawned) {
            drawVehicle(g.cx, g.cz, g.cyaw, playerVehicleType, g.altitude, 0.75f, 0.22f, 0.17f, g.cv, g.sx);
            if (!g.driving) drawKid(g.px, g.py, g.pz, g.pyaw, g.phase, g.swingAmp);
        }
    }

    private void bindTex(int t0, int t1) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void drawMesh(Mesh m, int t0, int t1, float emK) {
        if (m == null) return;
        bindTex(t0, t1);
        GLES20.glUniform1f(uEmisK, emK);
        m.draw();
    }

    private void bindVehicleMaps() {
        if (!advancedShader || vehicleMaps == null) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vehicleMaps.normal);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vehicleMaps.roughness);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vehicleMaps.metalness);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5); GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, vehicleMaps.environment);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void drawVehicle(float x,float z,float yaw,int type,float yOffset,float cr,float cg,float cb,float speed,float steering) {
        if(vehicleModels==null || type<0 || type>=vehicleModels.length || vehicleModels[type]==null) return;
        float length=(type==ProceduralVehicleModels.AIRPLANE?7.0f:type==ProceduralVehicleModels.BUS?8.3f:5.0f);
        float sy=(float)Math.sin(yaw), cy=(float)Math.cos(yaw);
        float hf=Terrain.h(x+sy*length*.42f,z+cy*length*.42f);
        float hb=Terrain.h(x-sy*length*.42f,z-cy*length*.42f);
        float pitch=-(float)Math.toDegrees(Math.atan2(hf-hb,length*.84f));
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,(hf+hb)*.5f+yOffset,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        Matrix.rotateM(base,0,pitch,1f,0f,0f);
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
        // اللون يظل في رؤوس الشبكة؛ التلوين هنا يستخدم فقط كتعديل خفيف للطلاء.
        GLES20.glUniform3f(uTint,.70f+cr*.30f,.70f+cg*.30f,.70f+cb*.30f);
        GLES20.glUniform1f(uGlass,0f);
        GLES20.glUniform1f(uPbr,1f);
        bindVehicleMaps();
        vehicleModels[type].draw();
        GLES20.glUniform1f(uPbr,0f);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        drawVehicleGlass(type);
        if(type==ProceduralVehicleModels.TANK) drawTankTracks(speed);
        if(type==ProceduralVehicleModels.HELICOPTER) drawHelicopterRotor();
        if(type!=ProceduralVehicleModels.TANK && type!=ProceduralVehicleModels.HELICOPTER && type!=ProceduralVehicleModels.AIRPLANE) drawVehicleWheels(type,speed,steering);
    }

    private void drawVehicleGlass(int type) {
        if(!advancedShader || vehicleGlassModels==null || type<0 || type>=vehicleGlassModels.length || vehicleGlassModels[type]==null) return;
        bindVehicleMaps();
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uPbr,0f);
        GLES20.glUniform1f(uGlass,1f);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        vehicleGlassModels[type].draw();
        GLES20.glUniform1f(uGlass,0f);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawVehicleWheels(int type,float speed,float steering) {
        if(wheelMesh==null) return;
        if(type==ProceduralVehicleModels.BICYCLE || type==ProceduralVehicleModels.MOTORBIKE) {
            float radius=type==ProceduralVehicleModels.BICYCLE?.34f:.43f;
            float half=type==ProceduralVehicleModels.BICYCLE?1.65f:1.45f;
            float angle=(float)(System.nanoTime()*1.0e-9*speed/radius);
            for(int axle=-1;axle<=1;axle+=2) {
                System.arraycopy(base,0,wheelMatrix,0,16);
                Matrix.translateM(wheelMatrix,0,0,radius,axle*half);
                if(axle>0) Matrix.rotateM(wheelMatrix,0,steering*24f,0f,1f,0f);
                Matrix.rotateM(wheelMatrix,0,(float)Math.toDegrees(angle),1f,0f,0f);
                Matrix.scaleM(wheelMatrix,0,.50f,radius/.32f,radius/.32f);
                GLES20.glUniformMatrix4fv(uModel,1,false,wheelMatrix,0);
                wheelMesh.draw();
            }
            GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
            return;
        }
        float halfZ=type==ProceduralVehicleModels.BUS?2.85f:type==ProceduralVehicleModels.PICKUP?1.65f:type==ProceduralVehicleModels.SUV?1.52f:1.38f;
        float wheelAngle=(float)(System.nanoTime()*1.0e-9*speed/.32f);
        for(int side=-1;side<=1;side+=2) for(int axle=-1;axle<=1;axle+=2) {
            System.arraycopy(base,0,wheelMatrix,0,16);
            Matrix.translateM(wheelMatrix,0,side*.91f,.42f,axle*halfZ);
            if(axle>0) Matrix.rotateM(wheelMatrix,0,steering*27f,0f,1f,0f);
            Matrix.rotateM(wheelMatrix,0,(float)Math.toDegrees(wheelAngle),1f,0f,0f);
            GLES20.glUniformMatrix4fv(uModel,1,false,wheelMatrix,0);
            GLES20.glUniform3f(uTint,1f,1f,1f);
            wheelMesh.draw();
        }
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
    }

    private void drawVehiclePart(float px,float py,float pz,float angle,float ax,float ay,float az,
                                 float sx,float sy,float sz,float r,float g,float b) {
        System.arraycopy(base,0,wheelMatrix,0,16);
        Matrix.translateM(wheelMatrix,0,px,py,pz);
        Matrix.rotateM(wheelMatrix,0,angle,ax,ay,az);
        Matrix.scaleM(wheelMatrix,0,sx,sy,sz);
        GLES20.glUniformMatrix4fv(uModel,1,false,wheelMatrix,0);
        GLES20.glUniform3f(uTint,r,g,b);
        cube.draw();
    }

    private void drawTankTracks(float speed) {
        float perimeter=10.8f;
        float travel=((float)(System.nanoTime()*1.0e-9*speed)%perimeter+perimeter)%perimeter;
        for(int side=-1;side<=1;side+=2) for(int i=0;i<28;i++) {
            float s=(i*perimeter/28f+travel)%perimeter, y=.42f, z=-2.2f, rot=0f;
            if(s<4.4f){z=-2.2f+s;y=.87f;}
            else if(s<5.4f){z=2.2f;y=.87f-(s-4.4f);rot=90f;}
            else if(s<9.8f){z=2.2f-(s-5.4f);y=-.03f;}
            else {z=-2.2f;y=-.03f+(s-9.8f);rot=90f;}
            drawVehiclePart(side*1.48f,y,z,rot,1f,0f,0f,.10f,.11f,.25f,.19f,.21f,.18f);
        }
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
    }

    private void drawHelicopterRotor() {
        float angle=(float)((System.nanoTime()*1.0e-9*900.0)%360.0);
        drawVehiclePart(0,1.55f,0,angle,0f,1f,0f,7.4f,.045f,.18f,.13f,.15f,.13f);
        drawVehiclePart(0,1.55f,0,angle+90f,0f,1f,0f,7.4f,.045f,.18f,.13f,.15f,.13f);
        float tailAngle=(float)((System.nanoTime()*1.0e-9*1450.0)%360.0);
        drawVehiclePart(.18f,1.10f,-3.55f,tailAngle,0f,0f,1f,.62f,.055f,.09f,.13f,.15f,.13f);
        drawVehiclePart(.18f,1.10f,-3.55f,tailAngle+90f,0f,0f,1f,.62f,.055f,.09f,.13f,.15f,.13f);
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
    }

    /** تبديل نوع المركبة الإجرائية التي يستدعيها اللاعب. */
    public void cycleVehicleType() {
        if(game.driving){L.onToast("اخرج من المركبة أولاً لتغيير النوع");return;}
        playerVehicleType=(playerVehicleType+1)%ProceduralVehicleModels.COUNT;
        L.onToast("المركبة: "+ProceduralVehicleModels.name(playerVehicleType)+" — اضغط 🚗 لاستدعائها أو ركوبها");
    }

    /** اللاعب: نسخة طفولية من الشخصية الإجرائية نفسها مع مفاصل ووجه وملابس مولّدة. */
    private void drawKid(float x,float y,float z,float yaw,float ph,float amp) {
        if(humanRigModels==null||humanRigModels.length==0)return;
        Mesh[] rig=humanRigModels[0];
        float swing=(float)Math.sin(ph)*amp*.58f;
        float bob=(float)Math.sin(ph*2f)*.018f;
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,y+bob,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        Matrix.scaleM(base,0,.86f,.86f,.86f);
        drawHumanPart(rig[0],0,1.08f,0,0,0,0,0,1,1,1);
        drawHumanPart(rig[1],0,1.60f,0,0,0,0,0,1.10f,1.10f,1.10f);
        drawHumanPart(rig[2],0,1.72f,-.018f,0,0,0,0,1.08f,1.05f,1.08f);
        drawHumanPart(rig[10],0,1.39f,.10f,0,0,0,0,1,1,1);
        drawHumanPart(rig[11],0,1.59f,.145f,0,0,0,0,1.10f,1.10f,1.10f);
        drawHumanPart(rig[13],.13f,1.18f,.14f,0,0,0,0,1,1,1);
        drawHumanPart(rig[14],0,1.25f,.151f,0,0,0,0,1,1,1);
        for(int side=-1;side<=1;side+=2) {
            float leg=(side<0?swing:-swing)*57.29578f;
            float arm=(side<0?-swing:swing)*42f;
            drawHumanPart(rig[3],side*.21f,1.32f,0,arm,side*.075f,-.15f,0,1,1,1);
            drawHumanPart(rig[4],side*.29f,1.00f,0,arm*.42f,side*.012f,-.12f,.01f,1,1,1);
            drawHumanPart(rig[5],side*.115f,.78f,0,leg,0,-.16f,0,1,1,1);
            drawHumanPart(rig[6],side*.115f,.44f,0,-leg*.30f,0,-.11f,0,1,1,1);
            drawHumanPart(rig[7],side*.115f,.15f,.045f,leg,0,-.02f,.06f,1,1,1);
            drawHumanPart(rig[8],side*.052f,1.645f,.127f,0,0,0,0,1,1,1);
            drawHumanPart(rig[12],side*.052f,1.667f,.137f,0,0,0,0,1,1,1);
            drawHumanPart(rig[15],side*.31f,.78f,.02f,arm*.42f,0,0,0,1,1,1);
        }
        drawHumanPart(rig[9],0,1.555f,.137f,0,0,0,0,1,1,1);
    }

    private void drawHumanPart(Mesh mesh,float px,float py,float pz,float rz,
                               float ox,float oy,float oz,float sx,float sy,float sz) {
        if(mesh==null)return;
        System.arraycopy(base,0,wheelMatrix,0,16);
        Matrix.translateM(wheelMatrix,0,px,py,pz);
        if(rz!=0f) Matrix.rotateM(wheelMatrix,0,rz,0f,0f,1f);
        Matrix.translateM(wheelMatrix,0,ox,oy,oz);
        Matrix.scaleM(wheelMatrix,0,sx,sy,sz);
        GLES20.glUniformMatrix4fv(uModel,1,false,wheelMatrix,0);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        mesh.draw();
    }

    private void drawPerson(float x,float y,float z,float yaw,float phase,int variant) {
        if(humanRigModels==null||humanRigModels.length==0)return;
        int id=(variant%humanRigModels.length+humanRigModels.length)%humanRigModels.length;
        Mesh[] rig=humanRigModels[id];
        float bob=(float)Math.sin(phase*2f)*.025f;
        float swing=(float)Math.sin(phase)*.58f;
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,y+bob,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        drawHumanPart(rig[0],0,1.08f,0,0,0,0,0,1,1,1);
        drawHumanPart(rig[1],0,1.58f,0,0,0,0,0,1,1,1);
        drawHumanPart(rig[2],0,1.69f,-.018f,0,0,0,0,1,1,1);
        drawHumanPart(rig[10],0,1.39f,.10f,0,0,0,0,1,1,1);
        drawHumanPart(rig[11],0,1.575f,.145f,0,0,0,0,1,1,1);
        drawHumanPart(rig[13],.13f,1.18f,.14f,0,0,0,0,1,1,1);
        drawHumanPart(rig[14],0,1.25f,.151f,0,0,0,0,1,1,1);
        for(int side=-1;side<=1;side+=2) {
            float legAngle=(side<0?swing:-swing)*57.29578f;
            float armAngle=(side<0?-swing:swing)*42f;
            drawHumanPart(rig[3],side*.21f,1.32f,0,armAngle,side*.075f,-.15f,0,1,1,1);
            drawHumanPart(rig[4],side*.29f,1.00f,0,armAngle*.42f,side*.012f,-.12f,.01f,1,1,1);
            drawHumanPart(rig[5],side*.115f,.78f,0,legAngle,0,-.16f,0,1,1,1);
            drawHumanPart(rig[6],side*.115f,.44f,0,-legAngle*.30f,0,-.11f,0,1,1,1);
            drawHumanPart(rig[7],side*.115f,.15f,.045f,legAngle,0,-.02f,.06f,1,1,1);
            drawHumanPart(rig[8],side*.052f,1.625f,.127f,0,0,0,0,1,1,1);
            drawHumanPart(rig[12],side*.052f,1.647f,.137f,0,0,0,0,1,1,1);
            drawHumanPart(rig[15],side*.31f,.78f,.02f,armAngle*.42f,0,0,0,1,1,1);
        }
        drawHumanPart(rig[9],0,1.535f,.137f,0,0,0,0,1,1,1);
    }}
