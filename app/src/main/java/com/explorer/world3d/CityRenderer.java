package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

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
            + "uniform sampler2D uTex; uniform sampler2D uEmis; uniform vec3 uTint;\n"
            + "uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform vec3 uFog;\n"
            + "uniform float uFogN; uniform float uFogF; uniform float uEmisK; uniform float uWater; uniform float uDecal; uniform float uTime;\n"
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
            + "  lit+=texture2D(uEmis,vUV).rgb*uEmisK;\n"
            + "  if(uDecal>0.5){ gl_FragColor=vec4(mix(lit,uFog*tx.a,f),tx.a); }\n"
            + "  else { gl_FragColor=vec4(mix(lit,uFog,f),1.0); }\n"
            + " }\n"
            + "}";
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
    private int uVP, uModel, uCam, uTint, uTex, uEmis, uSunDir, uSunCol, uAmb, uFog, uFogN, uFogF, uEmisK, uTop, uHor, uWater, uDecal, uTime;
    private Textures tex;
    private Mesh cube, groundMesh, skyQuad, kidHead, seaMesh;
    private final HashMap<Long, Mesh> terrain = new HashMap<>();
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] ident = new float[16], tmp = new float[16], base = new float[16];
    private float aspect = 1f;
    private long lastN = System.nanoTime(), lastUnload, lastAgentMs, lastHudMs, spawnDeadline;
    private boolean started, needSpawn, spawned, spawnTimeoutNotified;
    private float lookDx, lookDy;
    private volatile float gamepadLookX, gamepadLookY;
    public volatile int timeIdx = 0;
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
        prog = link(VS, FS, new String[]{"aPos", "aNor", "aUV", "aCol"});
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
        skyProg = link(SKY_VS, SKY_FS, new String[]{"aP"});
        uTop = GLES20.glGetUniformLocation(skyProg, "uTop");
        uHor = GLES20.glGetUniformLocation(skyProg, "uHor");

        tex = new Textures();
        cube = makeCube();
        kidHead = KidHead.load(ctx);
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
                g.driving = true; g.cv = 0;
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
            g.px = x; g.pz = z; g.pyaw = g.cyaw; g.driving = false; g.cv = 0;
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
        a.car = p.veh && rng.nextFloat() < 0.55f;
        if (a.car) {
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
            if (a.car) drawCar(a.x, a.z, a.yaw, a.cr, a.cg, a.cb);
            else drawPerson(a.x, Terrain.h(a.x, a.z), a.z, a.yaw, a.ph, 0.6f, a.cr, a.cg, a.cb);
        }
        if (spawned) {
            drawCar(g.cx, g.cz, g.cyaw, 0.75f, 0.22f, 0.17f);
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

    private void part(float[] b, float px, float py, float pz, float rotDeg,
                      float cx, float cy, float cz, float sx, float sy, float sz,
                      float r, float g, float bl) {
        System.arraycopy(b, 0, tmp, 0, 16);
        Matrix.translateM(tmp, 0, px, py, pz);
        if (rotDeg != 0f) Matrix.rotateM(tmp, 0, rotDeg, 1f, 0f, 0f);
        Matrix.translateM(tmp, 0, cx, cy, cz);
        Matrix.scaleM(tmp, 0, sx, sy, sz);
        GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
        GLES20.glUniform3f(uTint, r, g, bl);
        cube.draw();
    }

    private void drawCar(float x, float z, float yaw, float r, float g, float b) {
        float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
        float hf = Terrain.h(x + sy * 2.1f, z + cy * 2.1f), hb = Terrain.h(x - sy * 2.1f, z - cy * 2.1f);
        float pitch = -(float) Math.toDegrees(Math.atan2(hf - hb, 4.2f));
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, x, (hf + hb) / 2f, z);
        Matrix.rotateM(base, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        Matrix.rotateM(base, 0, pitch, 1f, 0f, 0f);
        float[] bb = base.clone();
        part(bb, 0, 0, 0, 0, 0, 0.65f, 0, 1.9f, 0.7f, 4.3f, r, g, b);
        part(bb, 0, 0, 0, 0, 0, 1.25f, -0.2f, 1.7f, 0.6f, 2.2f, 0.14f, 0.18f, 0.22f);
        for (float sx : new float[]{-0.95f, 0.95f}) {
            for (float sz : new float[]{-1.4f, 1.4f}) part(bb, 0, 0, 0, 0, sx, 0.36f, sz, 0.3f, 0.72f, 0.72f, 0.07f, 0.07f, 0.07f);
        }
        for (float sx : new float[]{-0.65f, 0.65f}) {
            part(bb, 0, 0, 0, 0, sx, 0.7f, 2.16f, 0.4f, 0.15f, 0.08f, 1f, 0.95f, 0.75f);
            part(bb, 0, 0, 0, 0, sx, 0.7f, -2.16f, 0.4f, 0.15f, 0.08f, 0.8f, 0.07f, 0.07f);
        }
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
    }

    /** الطفل (اللاعب): هودي أزرق وجينز وحذاء رياضي، ورأس منحوت من الصورة المرجعية. */
    private void drawKid(float x, float y, float z, float yaw, float ph, float amp) {
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, x, y, z);
        Matrix.rotateM(base, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        float[] bb = base.clone();
        float sw = (float) Math.sin(ph) * amp * 57.3f;
        // الهودي
        part(bb, 0, 0, 0, 0, 0, 0.80f, 0, 0.34f, 0.44f, 0.20f, 0.10f, 0.27f, 0.65f);
        part(bb, 0, 0, 0, 0, 0, 1.03f, -0.03f, 0.20f, 0.07f, 0.17f, 0.78f, 0.80f, 0.84f);
        part(bb, 0, 0, 0, 0, 0, 0.88f, 0.103f, 0.07f, 0.07f, 0.01f, 0.95f, 0.95f, 0.98f);
        part(bb, 0, 0, 0, 0, 0, 0.67f, 0.103f, 0.20f, 0.07f, 0.01f, 0.08f, 0.22f, 0.55f);
        // الساقان (جينز) والحذاء
        for (int s = -1; s <= 1; s += 2) {
            float rot = s < 0 ? sw : -sw;
            part(bb, s * 0.075f, 0.60f, 0, rot, 0, -0.29f, 0, 0.12f, 0.58f, 0.13f, 0.17f, 0.28f, 0.47f);
            part(bb, s * 0.075f, 0.60f, 0, rot, 0, -0.615f, 0.03f, 0.12f, 0.07f, 0.23f, 0.92f, 0.92f, 0.95f);
            part(bb, s * 0.075f, 0.60f, 0, rot, 0, -0.655f, 0.03f, 0.125f, 0.025f, 0.24f, 0.10f, 0.20f, 0.60f);
            // الذراعان
            float arot = s < 0 ? -sw * 0.8f : sw * 0.8f;
            part(bb, s * 0.215f, 1.0f, 0, arot, 0, -0.21f, 0, 0.085f, 0.42f, 0.10f, 0.10f, 0.27f, 0.65f);
            part(bb, s * 0.215f, 1.0f, 0, arot, 0, -0.455f, 0, 0.07f, 0.08f, 0.07f, 0.93f, 0.74f, 0.62f);
        }
        // الرأس المنحوت
        if (kidHead != null) {
            System.arraycopy(bb, 0, tmp, 0, 16);
            Matrix.translateM(tmp, 0, 0f, 1.16f, 0f);
            Matrix.scaleM(tmp, 0, 0.0125f, 0.0125f, 0.0125f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
            GLES20.glUniform3f(uTint, 1f, 1f, 1f);
            kidHead.draw();
        } else {
            part(bb, 0, 0, 0, 0, 0, 1.17f, 0, 0.2f, 0.24f, 0.2f, 0.93f, 0.74f, 0.62f);
        }
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
    }

    private void drawPerson(float x, float y, float z, float yaw, float ph, float amp, float r, float g, float b) {
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, x, y, z);
        Matrix.rotateM(base, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        float[] bb = base.clone();
        float sw = (float) Math.sin(ph) * amp * 57.3f;
        part(bb, 0, 0, 0, 0, 0, 1.2f, 0, 0.5f, 0.7f, 0.3f, r, g, b);
        part(bb, 0, 0, 0, 0, 0, 1.72f, 0, 0.28f, 0.28f, 0.28f, 0.85f, 0.65f, 0.5f);
        part(bb, -0.13f, 0.82f, 0, sw, 0, -0.39f, 0, 0.2f, 0.78f, 0.22f, 0.17f, 0.24f, 0.31f);
        part(bb, 0.13f, 0.82f, 0, -sw, 0, -0.39f, 0, 0.2f, 0.78f, 0.22f, 0.17f, 0.24f, 0.31f);
        part(bb, -0.3f, 1.5f, 0, -sw * 0.8f, 0, -0.3f, 0, 0.12f, 0.62f, 0.14f, r, g, b);
        part(bb, 0.3f, 1.5f, 0, sw * 0.8f, 0, -0.3f, 0, 0.12f, 0.62f, 0.14f, r, g, b);
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
    }
}
