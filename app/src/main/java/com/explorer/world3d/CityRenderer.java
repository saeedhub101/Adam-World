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
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CityRenderer implements GLSurfaceView.Renderer, Game.World {
    public interface Listener {
        void onLoading(boolean show, String msg);
        void onHud(String s);
        void onToast(String s);
    }

    static final int KEEP_R = 1; // Keep only a 3×3 GPU tile window; the loader prefetches the next ring.
    private static final String VS =
            "uniform mat4 uVP; uniform mat4 uModel; uniform vec3 uCam;\n"
            + "attribute vec3 aPos; attribute vec3 aNor; attribute vec2 aUV; attribute vec3 aCol; attribute vec4 aJoints; attribute vec4 aWeights;\n"
            + "uniform mat4 uBones[24]; uniform float uSkinning;\n"
            + "varying vec2 vUV; varying vec3 vCol; varying vec3 vNor; varying float vDist; varying vec3 vWorld; varying vec3 vRel;\n"
            + "void main(){ mat4 skin=mat4(1.0); if(uSkinning>0.5){ skin=uBones[int(aJoints.x)]*aWeights.x+uBones[int(aJoints.y)]*aWeights.y+uBones[int(aJoints.z)]*aWeights.z+uBones[int(aJoints.w)]*aWeights.w; } vec4 lp=skin*vec4(aPos,1.0); vec4 wp=uModel*lp; gl_Position=uVP*wp; vUV=aUV; vCol=aCol;\n"
            + " vNor=(uModel*skin*vec4(aNor,0.0)).xyz; vDist=length(wp.xyz-uCam); vWorld=wp.xyz; vRel=wp.xyz-uCam; }";
    private static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
            + "uniform sampler2D uTex; uniform sampler2D uEmis; uniform sampler2D uNormalMap; uniform sampler2D uRoughMap; uniform sampler2D uMetalMap; uniform samplerCube uEnvMap; uniform vec3 uTint; uniform vec3 uCam;\n"
            + "uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform vec3 uFog;\n"
            + "uniform float uFogN; uniform float uFogF; uniform float uEmisK; uniform vec2 uMatParams; uniform float uWater; uniform float uDecal; uniform float uTime; uniform float uPbr; uniform float uGlass;\n"
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
            + "    float rough=clamp((texture2D(uRoughMap,uvX).r*w.x+texture2D(uRoughMap,uvY).r*w.y+texture2D(uRoughMap,uvZ).r*w.z)*uMatParams.y,0.08,0.96);\n"
            + "    float metal=clamp((texture2D(uMetalMap,uvX).r*w.x+texture2D(uMetalMap,uvY).r*w.y+texture2D(uMetalMap,uvZ).r*w.z)*uMatParams.x,0.0,1.0);\n"
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
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
            + "uniform sampler2D uTex; uniform sampler2D uEmis; uniform vec3 uTint;\n"
            + "uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform vec3 uFog;\n"
            + "uniform float uFogN; uniform float uFogF; uniform float uEmisK; uniform float uWater; uniform float uTime;\n"
            + "varying vec2 vUV; varying vec3 vCol; varying vec3 vNor; varying float vDist; varying vec3 vWorld; varying vec3 vRel;\n"
            + "void main(){ float f=clamp((vDist-uFogN)/max(uFogF-uFogN,0.001),0.0,1.0);\n"
            + " if(uWater>0.5){ vec2 p=vWorld.xz; float wave=0.5+0.5*sin(p.x*0.22+p.y*0.17+uTime); vec3 c=mix(vec3(0.02,0.16,0.23),uFog,0.18+0.24*wave); gl_FragColor=vec4(mix(c,uFog,f),1.0); }\n"
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
    private int uVP, uModel, uCam, uTint, uMatParams, uTex, uEmis, uSkinning, uBones, uSunDir, uSunCol, uAmb, uFog, uFogN, uFogF, uEmisK, uTop, uHor, uWater, uDecal, uTime, uPbr, uGlass, uNormalMap, uRoughMap, uMetalMap, uEnvMap;
    private Textures tex;
    private ProceduralMaterialMaps vehicleMaps;
    private Mesh cube, groundMesh, skyQuad, seaMesh, flightSchoolMesh, rocketMesh, guidedRocketMesh;
    private volatile boolean flightSchoolMode, airportVisit;
    private boolean savedOfflineAmman;
    private String savedOfflineCitySlug;
    private double savedCityLat, savedCityLon;
    private Mesh[] vehicleModels;
    private AssetMeshLoader.Model[] importedVehicleModels;
    private Mesh[] vehicleGlassModels;
    private Mesh wheelMesh;
    private final float[] wheelMatrix = new float[16];
    private Mesh[][] humanRigModels;
    private SkinnedAssetLoader.Asset playerCharacterAsset;
    private SkinnedAssetLoader.Asset[] pedestrianCharacterAssets = new SkinnedAssetLoader.Asset[0];
    private int[] pedestrianWalkClips = new int[0];
    private final WeakHashMap<Game.Agent, SkeletalAnimationRuntime.Animator> pedestrianAnimators = new WeakHashMap<>();
    private long lastCharacterAnimationNanos;
    private volatile int playerVehicleType = ProceduralVehicleModels.SEDAN;
    private final HashMap<Long, Mesh> terrain = new HashMap<>();
    private static final class TerrainBuild {
        final long key; final int generation; final GeoBuf geometry;
        TerrainBuild(long key, int generation, GeoBuf geometry) { this.key=key; this.generation=generation; this.geometry=geometry; }
    }
    private final ExecutorService terrainExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AdamWorld-TerrainBuilder"); t.setDaemon(true); return t;
    });
    private final ConcurrentLinkedQueue<TerrainBuild> terrainReady = new ConcurrentLinkedQueue<>();
    private final Set<Long> terrainBuilding = ConcurrentHashMap.newKeySet();
    private volatile int terrainGeneration;
    private float frameDeltaSeconds = 1f / 60f;
    private volatile int graphicsQuality = 1;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] ident = new float[16], tmp = new float[16], base = new float[16];
    private float aspect = 1f;
    private long lastN = System.nanoTime(), lastUnload, lastAgentMs, lastHudMs, spawnDeadline, lastAudioWeaponShot, lastMiniMapUpdate, lastAudioExplosionEvent;
    private long lastTerrainBuildMs;
    private volatile float[] miniMapRoadSnapshot = new float[0];
    private volatile float[] fullMapRoadSnapshot = new float[0];
    private boolean started, needSpawn, spawned, spawnTimeoutNotified;
    private float lookDx, lookDy;
    private volatile float gamepadLookX, gamepadLookY;
    private volatile boolean gamepadZoomModifier;
    private float smoothGamepadLookX, smoothGamepadLookY;
    private volatile float lookSensitivity = 1f;
    public volatile int timeIdx = 0;
    private int environmentTimeIdx = -1;
    public volatile boolean undergroundCutaway = false;

    public void toggleUndergroundCutaway() { undergroundCutaway = !undergroundCutaway; }
    public float[] miniMapRoads() { return miniMapRoadSnapshot; }
    public float[] fullMapRoads() { return fullMapRoadSnapshot; }

    private void refreshMiniMapRoads(long nowMs) {
        if (nowMs - lastMiniMapUpdate < 900L) return;
        lastMiniMapUpdate = nowMs;
        if (flightSchoolMode || tiles.isEmpty()) {
            miniMapRoadSnapshot = new float[0];
            fullMapRoadSnapshot = new float[0];
            return;
        }
        float px = game.focusX(), pz = game.focusZ();
        float[] mini = new float[1600];
        float[] full = new float[24000];
        int miniUsed = 0, fullUsed = 0;
        for (Tile tile : tiles.values()) {
            if (Math.abs(tile.cx - px) > 950f || Math.abs(tile.cz - pz) > 950f) continue;
            for (RoadPath road : tile.paths) {
                if (road == null || road.xs == null || road.zs == null) continue;
                for (int i = 1; i < road.xs.length && (miniUsed + 4 <= mini.length || fullUsed + 4 <= full.length); i++) {
                    float x0 = road.xs[i-1], z0 = road.zs[i-1], x1 = road.xs[i], z1 = road.zs[i];
                    float mx = (x0+x1)*0.5f-px, mz = (z0+z1)*0.5f-pz;
                    float distance2 = mx*mx + mz*mz;
                    if (distance2 <= 900f*900f && fullUsed + 4 <= full.length) {
                        full[fullUsed++] = x0; full[fullUsed++] = z0;
                        full[fullUsed++] = x1; full[fullUsed++] = z1;
                    }
                    if (distance2 <= 260f*260f && miniUsed + 4 <= mini.length) {
                        mini[miniUsed++] = x0; mini[miniUsed++] = z0;
                        mini[miniUsed++] = x1; mini[miniUsed++] = z1;
                    }
                }
            }
        }
        miniMapRoadSnapshot = java.util.Arrays.copyOf(mini, miniUsed);
        fullMapRoadSnapshot = java.util.Arrays.copyOf(full, fullUsed);
    }
    private long lastErrToast;
    private final Random rng = new Random();
    private final WorldAudio worldAudio;

    public CityRenderer(Context ctx, Listener l) {
        this.ctx = ctx;
        this.L = l;
        worldAudio = new WorldAudio();
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

    public void shutdown() { loader.shutdown(); terrainExecutor.shutdownNow(); terrainReady.clear(); worldAudio.release(); }

    private void resetTerrainJobs() {
        terrainGeneration++;
        terrainReady.clear();
        terrainBuilding.clear();
    }

    private void uploadTerrain() {
        TerrainBuild build = terrainReady.poll();
        if (build == null) return;
        terrainBuilding.remove(build.key);
        if (build.generation != terrainGeneration || build.geometry == null) return;
        int x = (int)(build.key >> 32), z = (int)build.key;
        int px = Geo.tileX(game.focusX()), pz = Geo.tileZ(game.focusZ());
        if (Math.abs(x-px)>3 || Math.abs(z-pz)>3) return;
        Mesh mesh = Mesh.from(build.geometry);
        if (mesh != null) {
            Mesh previous = terrain.put(build.key, mesh);
            if (previous != null) previous.release();
        }
    }
    public void pauseAudio() { worldAudio.pause(); }
    public void resumeAudio() { worldAudio.resume(); }
    public void setAudioEnabled(boolean enabled) { worldAudio.setEnabled(enabled); }
    public void setAudioVolumes(float master, float engine, float ambience, float effects) { worldAudio.setVolumes(master, engine, ambience, effects); }
    public void playHorn() { worldAudio.playHorn(); }
    /** Called on the GL thread after Android signals memory pressure. */
    public void trimMemory() {
        loader.trimMemory();
        resetTerrainJobs();
        game.agents.clear();
        int px = Geo.tileX(game.focusX()), pz = Geo.tileZ(game.focusZ());
        Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
        while (it.hasNext()) {
            Tile tile = it.next().getValue();
            if (Math.abs(tile.ix - px) > 1 || Math.abs(tile.iz - pz) > 1) {
                tile.release();
                it.remove();
                loader.requested.remove(tile.key);
            }
        }
        Iterator<Map.Entry<Long, Mesh>> terrainIt = terrain.entrySet().iterator();
        while (terrainIt.hasNext()) {
            Map.Entry<Long, Mesh> entry = terrainIt.next();
            entry.getValue().release();
            terrainIt.remove();
        }
    }


    public synchronized void addLook(float dx, float dy) { lookDx += dx; lookDy += dy; }

    public void setGraphicsQuality(int quality) { graphicsQuality = Math.max(0, Math.min(2, quality)); }

    private float visibleTileRadius() { return graphicsQuality == 0 ? 1200f : graphicsQuality == 1 ? 850f : 620f; }

    public void setLookSensitivity(float sensitivity) {
        if (Float.isFinite(sensitivity)) lookSensitivity = Math.max(0.35f, Math.min(2.0f, sensitivity));
    }

    public void setGamepadZoomModifier(boolean enabled) { gamepadZoomModifier = enabled; if (!enabled) game.cameraZoomInput = 0f; }

    public boolean isGamepadZoomModifier() { return gamepadZoomModifier; }

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
            prog = link(VS, FS, new String[]{"aPos", "aNor", "aUV", "aCol", "aJoints", "aWeights"});
            advancedShader = true;
        } catch (RuntimeException incompatiblePbrShader) {
            Log.w("AdamWorldGL", "PBR shader unavailable; using GLES 2.0 compatible renderer", incompatiblePbrShader);
            prog = link(VS, FS_COMPAT, new String[]{"aPos", "aNor", "aUV", "aCol", "aJoints", "aWeights"});
            advancedShader = false;
        }
        uVP = GLES20.glGetUniformLocation(prog, "uVP");
        uModel = GLES20.glGetUniformLocation(prog, "uModel");
        uCam = GLES20.glGetUniformLocation(prog, "uCam");
        uTint = GLES20.glGetUniformLocation(prog, "uTint");
        uMatParams = GLES20.glGetUniformLocation(prog, "uMatParams");
        uSkinning = GLES20.glGetUniformLocation(prog, "uSkinning");
        uBones = GLES20.glGetUniformLocation(prog, "uBones[0]");
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
        rocketMesh = RocketModel.build(false);
        guidedRocketMesh = RocketModel.build(true);
        flightSchoolMesh = Airfield.build();
        // The former runway/apron/terminal scene is reused as an airport overlay at real airport coordinates.
        vehicleModels = ProceduralVehicleModels.buildAll();
        // Prefer build-time converted real meshes; procedural meshes remain a safe fallback.
        importedVehicleModels = new AssetMeshLoader.Model[ProceduralVehicleModels.COUNT];
        playerCharacterAsset = SkinnedAssetLoader.load(ctx, "models/runtime/characters/adam.awmesh5");
        String[] pedestrianFiles = {
                "models/runtime/characters/pedestrian-office.awmesh5",
                "models/runtime/characters/pedestrian-elder.awmesh5",
                "models/runtime/characters/pedestrian-manager.awmesh5"
        };
        pedestrianCharacterAssets = new SkinnedAssetLoader.Asset[pedestrianFiles.length];
        pedestrianWalkClips = new int[pedestrianFiles.length];
        java.util.Arrays.fill(pedestrianWalkClips, -1);
        pedestrianAnimators.clear();
        for (int p = 0; p < pedestrianFiles.length; p++) {
            SkinnedAssetLoader.Asset asset = SkinnedAssetLoader.load(ctx, pedestrianFiles[p]);
            pedestrianCharacterAssets[p] = asset;
            if (asset == null) continue;
            SkeletalAnimationRuntime.Clip[] clips = asset.animator.clips;
            for (int i = 0; i < clips.length; i++) {
                if (clips[i].name.toLowerCase(java.util.Locale.ROOT).contains("walk")) {
                    pedestrianWalkClips[p] = i;
                    break;
                }
            }
            if (pedestrianWalkClips[p] < 0 && clips.length > 0) pedestrianWalkClips[p] = 0;
        }
        importedVehicleModels[ProceduralVehicleModels.SEDAN] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/sedan.awmesh");
        importedVehicleModels[ProceduralVehicleModels.PICKUP] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/pickup.awmesh");
importedVehicleModels[ProceduralVehicleModels.SUV] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/suv.awmesh");
        importedVehicleModels[ProceduralVehicleModels.POLICE] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/police.awmesh");
        importedVehicleModels[ProceduralVehicleModels.BUS] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/city-bus.awmesh");
        importedVehicleModels[ProceduralVehicleModels.MOTORBIKE] = AssetMeshLoader.load(ctx, "models/runtime/vehicles/motorcycle.awmesh");
        // Imported replacements make their procedural GPU copies redundant. Release them
        // immediately to avoid keeping both full meshes resident on memory-constrained TVs.
        for (int type = 0; type < importedVehicleModels.length; type++) {
            if (importedVehicleModels[type] != null && vehicleModels[type] != null) {
                vehicleModels[type].release();
                vehicleModels[type] = null;
            }
        }
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
        flightSchoolMode = false;
        resetTerrainJobs();
        loader.havePlayer = true;
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

    /** Visit a real mapped airport while staying in the same streamed city world. */
    public void enterFlightSchool() { enterAirport(31.9726, 35.9916); }

    public void enterAirport(double latitude, double longitude) {
        if (flightSchoolMode || airportVisit || !spawned) return;
        savedCityLat = Geo.latOf(game.focusZ());
        savedCityLon = Geo.lonOf(game.focusX());
        savedOfflineAmman = loader.offlineAmman;
        savedOfflineCitySlug = loader.offlineManila ? "manila" : loader.offlineCitySlug;
        airportVisit = true;
        playerVehicleType = ProceduralVehicleModels.AIRPLANE;
        teleportInternal(latitude, longitude, false, null);
        L.onToast("تم الانتقال إلى المطار الحقيقي داخل المدينة — المدرج والممرات من بيانات الخريطة");
    }

    /** Restore the exact city coordinates and offline dataset used before the airport visit. */
    public void leaveFlightSchool() {
        if (!flightSchoolMode && !airportVisit) return;
        double lat = savedCityLat, lon = savedCityLon;
        boolean offline = savedOfflineAmman;
        String slug = savedOfflineCitySlug;
        flightSchoolMode = false;
        airportVisit = false;
        teleportInternal(lat, lon, offline, slug);
    }

    public boolean isFlightSchoolMode() { return flightSchoolMode || airportVisit; }

    private void spawnFlightSchoolVehicle(boolean enter) {
        Game g = game;
        g.reset();
        g.vehicleType = playerVehicleType;
        if (playerVehicleType == ProceduralVehicleModels.HELICOPTER) {
            g.cx = g.px = -125f; g.cz = g.pz = -105f; g.engineThrottle = 0.52f;
        } else if (playerVehicleType == ProceduralVehicleModels.AIRPLANE) {
            g.cx = g.px = 0f; g.cz = g.pz = -390f; g.engineThrottle = 0f;
        } else {
            g.cx = g.px = 165f; g.cz = g.pz = -35f;
        }
        g.py = 0f; g.pyaw = g.cyaw = 0f;
        g.driving = enter; g.landingGearDown = true; g.aircraftOnGround = true;
        g.camYaw = 0f; g.camPitch = 0.30f; g.camDistance = 25f; g.snapCamera();
        spawned = true; needSpawn = false; spawnTimeoutNotified = false;
    }

    public void toggleAircraftGear() {
        if (!game.driving || game.vehicleType != ProceduralVehicleModels.AIRPLANE) return;
        game.landingGearDown = !game.landingGearDown;
        L.onToast(game.landingGearDown ? "عجلات الهبوط مفتوحة" : "عجلات الهبوط مطوية");
    }

    public void toggleCar() {
        Game g = game;
        if (!spawned) return;
        // Prevent held on-foot or vehicle inputs from leaking across the enter/exit transition.
        g.sprint = false; g.stealthMode = false;
        g.weaponAimHeld = false; g.weaponFireHeld = false;
        g.handbrake = false; g.brakeRequested = false;
        g.liftInput = 0f; g.rudderInput = 0f; g.throttleInput = 0f;
        if (flightSchoolMode) {
            if (g.driving) {
                boolean aircraft = g.vehicleType == ProceduralVehicleModels.HELICOPTER || g.vehicleType == ProceduralVehicleModels.AIRPLANE;
                if (Math.abs(g.cv) > 3f || g.altitude > 1f) { L.onToast("أوقف المركبة واهبط قبل الخروج"); return; }
                g.driving = false; g.cv = 0f; g.engineThrottle = g.vehicleType == ProceduralVehicleModels.HELICOPTER ? 0.52f : 0f;
                g.px = g.cx + 3.2f; g.pz = g.cz; g.py = 0f;
            } else spawnFlightSchoolVehicle(true);
            return;
        }
        if (!g.driving) {
            float d = (float) Math.hypot(g.px - g.cx, g.pz - g.cz);
            Game.Agent nearestTrafficCar = null;
            float nearestTrafficDistance = 8f;
            for (Game.Agent agent : g.agents) {
                if (!agent.car || agent.knockedOut) continue;
                float distance = (float)Math.hypot(g.px - agent.x, g.pz - agent.z);
                if (distance < nearestTrafficDistance) {
                    nearestTrafficDistance = distance;
                    nearestTrafficCar = agent;
                }
            }
            if (nearestTrafficCar != null && nearestTrafficDistance < d) {
                g.cx = nearestTrafficCar.x; g.cz = nearestTrafficCar.z; g.cyaw = nearestTrafficCar.yaw;
                g.cv = nearestTrafficCar.sp * nearestTrafficCar.dir;
                g.vehicleType = nearestTrafficCar.vehicleType;
                playerVehicleType = nearestTrafficCar.vehicleType;
                g.driving = true; g.altitude = 0f; g.engineThrottle = 0f;
                g.aircraftVerticalSpeed = 0f; g.aircraftOnGround = true; g.landingGearDown = true;
                g.agents.remove(nearestTrafficCar);
                L.onToast("تم ركوب سيارة الشارع");
                return;
            }
            if (d < 8f) {
                g.driving = true; g.cv = 0; g.vehicleType = playerVehicleType; g.altitude = 0f; g.liftInput = g.throttleInput = 0f; g.brakeRequested = false; g.engineThrottle = playerVehicleType == ProceduralVehicleModels.HELICOPTER ? 0.52f : 0f; g.aircraftVerticalSpeed = 0f; g.aircraftOnGround = true; g.aircraftDamage = 0f; g.landingGearDown = true; g.aircraftPitch = g.aircraftRoll = 0f;
                L.onToast("قيادة: العصا للتسارع والتوجيه");
            } else {
                float fx = (float) Math.sin(g.pyaw), fz = (float) Math.cos(g.pyaw);
                float[][] tries = {{fx * 5, fz * 5}, {-fz * 4, fx * 4}, {fz * 4, -fx * 4}, {-fx * 5, -fz * 5}};
                boolean ok = false;
                for (float[] t : tries) {
                    float x = g.px + t[0], z = g.pz + t[1];
                    if (!blocked(x, z, 2.2f)) { g.cx = x; g.cz = z; g.cyaw = g.pyaw; g.cv = 0; ok = true; break; }
                }
                if (ok) {
                    g.driving = true; g.vehicleType = playerVehicleType; g.cv = 0f;
                    g.altitude = 0f; g.engineThrottle = 0f; g.aircraftVerticalSpeed = 0f;
                    g.landingGearDown = true; g.aircraftOnGround = true;
                    L.onToast("تم استدعاء المركبة — أنت الآن تقودها");
                } else L.onToast("لا توجد مساحة آمنة لاستدعاء المركبة");
            }
        } else {
            boolean aircraft = g.vehicleType == ProceduralVehicleModels.HELICOPTER || g.vehicleType == ProceduralVehicleModels.AIRPLANE;
            if (aircraft && (g.altitude > 1f || Math.abs(g.cv) > 3f)) { L.onToast("لا يمكنك الخروج من الطائرة أثناء الحركة أو الطيران"); return; }
            float rx = -(float) Math.cos(g.cyaw), rz = (float) Math.sin(g.cyaw);
            float x = g.cx + rx * 2.6f, z = g.cz + rz * 2.6f;
            if (blocked(x, z, 0.5f)) { x = g.cx - rx * 2.6f; z = g.cz - rz * 2.6f; }
            if (blocked(x, z, 0.5f)) { x = g.cx; z = g.cz; }
            g.px = x; g.pz = z; g.pyaw = g.cyaw; g.driving = false; g.cv = 0; g.altitude = 0f; g.liftInput = g.throttleInput = 0f; g.brakeRequested = false; g.engineThrottle = 0f; g.aircraftVerticalSpeed = 0f; g.aircraftOnGround = true; g.aircraftPitch = g.aircraftRoll = 0f;
            g.vehicleBumpAmplitude = g.vehicleBumpTime = g.vehicleBumpOffset = g.vehicleBumpRoll = g.vehicleBumpPitch = 0f;
            g.vehicleWheelFL = g.vehicleWheelFR = g.vehicleWheelRL = g.vehicleWheelRR = 0f;
            g.vehicleWheelBodyLift = g.vehicleWheelBodyRoll = g.vehicleWheelBodyPitch = 0f;
            g.vehicleFrontStepContact = g.vehicleRearStepContact = false;
        }
    }

    /** Ground height excluding raised walkable curbs; used by wheel suspension. */
    public float baseHeightAt(float x, float z) {
        if (flightSchoolMode) return 0f;
        if (loader.offlineManila) return 0f;
        float lm = Landmarks.heightAt(x, z);
        return Float.isNaN(lm) ? Terrain.h(x, z) : lm;
    }

    @Override public float heightAt(float x, float z) {
        if (flightSchoolMode) return 0f;
        return baseHeightAt(x, z) + lowObstacleHeightAt(x, z);
    }

    /** يبني مربعات الأرض من الارتفاعات الحقيقية حول اللاعب ويحذف البعيد منها. */
    private void updateTerrain(int ptx, int ptz) {
        if (flightSchoolMode || loader.offlineManila || !Terrain.enabled || !Terrain.baseSet) return;
        // Terrain height-grid generation is dispatched to a single worker; the GL thread only uploads one completed VBO.
        long terrainNow = System.currentTimeMillis();
        if (terrainNow - lastTerrainBuildMs < 350L || terrainBuilding.size() >= 1) return;
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
                long candidateKey = Geo.key(ix, iz);
                if (terrain.containsKey(candidateKey) || terrainBuilding.contains(candidateKey)) continue;
                double lat = (iz + 0.5) * Geo.D_LAT, lon = (ix + 0.5) * Geo.dLon;
                if (!Terrain.has(lat, lon)) continue;
                int d = dx * dx + dz * dz;
                if (d < bd) { bd = d; bx = ix; bz = iz; }
            }
        }
        if (bd != Integer.MAX_VALUE) {
            lastTerrainBuildMs = terrainNow;
            final int buildX = bx, buildZ = bz;
            final long key = Geo.key(buildX, buildZ);
            final int generation = terrainGeneration;
            final boolean tabarbour = "tabarbour".equals(loader.offlineCitySlug);
            if (terrainBuilding.add(key)) terrainExecutor.execute(() -> {
                try {
                    GeoBuf geometry = TerrainMesh.build(buildX, buildZ, tabarbour);
                    terrainReady.add(new TerrainBuild(key, generation, geometry));
                } catch (RuntimeException ignored) {
                    terrainBuilding.remove(key);
                }
            });
        }
    }

    /* ---------- التصادم ---------- */
    private boolean hit(float x, float z, float clearance) {
        int ix = Geo.tileX(x), iz = Geo.tileZ(z);
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                Tile t = tiles.get(Geo.key(ix + di, iz + dj));
                if (t != null && Collision.hit(t.grid, x, z, clearance)) return true;
            }
        }
        return false;
    }

    /** Low curb/median surface used for step-up movement and vehicle suspension. */
    public float lowObstacleHeightAt(float x, float z) {
        if (flightSchoolMode) return 0f;
        int ix = Geo.tileX(x), iz = Geo.tileZ(z);
        float h = 0f;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                Tile t = tiles.get(Geo.key(ix + di, iz + dj));
                if (t != null) h = Math.max(h, Collision.lowObstacleHeightAt(t.grid, x, z, 0.55f));
            }
        }
        return h;
    }

    @Override public boolean blocked(float x, float z, float r) {
        return blockedAtHeight(x, z, r, 0f);
    }

    public boolean blockedAtHeight(float x, float z, float r, float clearance) {
        if (flightSchoolMode) return false;
        return hit(x, z, clearance) || hit(x + r, z, clearance) || hit(x - r, z, clearance)
                || hit(x, z + r, clearance) || hit(x, z - r, clearance);
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
        if (airportVisit) {
            // Spawn directly on the reused runway so the player can take off immediately.
            game.vehicleType = ProceduralVehicleModels.AIRPLANE;
            playerVehicleType = ProceduralVehicleModels.AIRPLANE;
            game.px = game.cx = 0f;
            game.pz = game.cz = -390f;
            game.py = baseHeightAt(0f, -390f);
            game.pyaw = game.cyaw = 0f;
            game.cv = 0f;
            game.altitude = 0f;
            game.engineThrottle = 0f;
            game.aircraftVerticalSpeed = 0f;
            game.aircraftOnGround = true;
            game.landingGearDown = true;
            game.driving = true;
            game.camYaw = 0f;
            game.camPitch = 0.30f;
            game.camDistance = 25f;
        } else {
            game.px = bestX; game.pz = bestZ; game.pyaw = bestYaw; game.camYaw = bestYaw;
            game.cx = bestCarX; game.cz = bestCarZ; game.cyaw = bestYaw;
            game.py = heightAt(game.px, game.pz);
        }
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
        if (game.agents.size() >= 8 || tiles.isEmpty()) return;
        ArrayList<Tile> list = new ArrayList<>(tiles.values());
        Tile t = list.get(rng.nextInt(list.size()));
        if (t.paths.isEmpty()) return;
        RoadPath p = t.paths.get(rng.nextInt(t.paths.size()));
        float fx = game.focusX(), fz = game.focusZ();
        float[] o = new float[4];
        float s = rng.nextFloat() * p.total;
        p.posAt(s, 0, o);
        float d = (float) Math.hypot(o[0] - fx, o[1] - fz);
        if (d < 38f || d > 240f) return;
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
            a.humanType = rng.nextInt(3);
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
        frameDeltaSeconds = dt;
        long nowMs = System.currentTimeMillis();

        float ldx, ldy;
        synchronized (this) { ldx = lookDx; ldy = lookDy; lookDx = 0; lookDy = 0; }
        if (ldx != 0 || ldy != 0) game.look(ldx * lookSensitivity, ldy * lookSensitivity);
        float gx = gamepadLookX, gy = gamepadLookY;
        float lookBlend = 1f - (float) Math.exp(-dt * 16f);
        smoothGamepadLookX += (gx - smoothGamepadLookX) * lookBlend;
        smoothGamepadLookY += (gy - smoothGamepadLookY) * lookBlend;
        if (Math.abs(smoothGamepadLookX) < 0.0015f && gx == 0f) smoothGamepadLookX = 0f;
        if (Math.abs(smoothGamepadLookY) < 0.0015f && gy == 0f) smoothGamepadLookY = 0f;
        if (smoothGamepadLookX != 0f || smoothGamepadLookY != 0f)
            game.look(smoothGamepadLookX * dt * 235f * lookSensitivity,
                    smoothGamepadLookY * dt * 235f * lookSensitivity);

        if (!flightSchoolMode) {
        int ptx = Geo.tileX(game.focusX()), ptz = Geo.tileZ(game.focusZ());
        TileData td;
        int ups = 0;
        long uploadDeadline = System.nanoTime() + 7000000L;
        // Upload at most one nearby tile per frame. Discard obsolete worker
        // results instead of uploading geometry from places the player has left.
        while (ups < 1 && System.nanoTime() < uploadDeadline && (td = loader.ready.poll()) != null) {
            if (td.gen != loader.gen) continue;
            if (Math.abs(td.ix - ptx) > KEEP_R + 1 || Math.abs(td.iz - ptz) > KEEP_R + 1) {
                loader.requested.remove(Geo.key(td.ix, td.iz));
                continue;
            }
            uploadTile(td);
            ups++;
        }
        loader.ptx = ptx; loader.ptz = ptz;

        if (nowMs - lastUnload > 250) {
            lastUnload = nowMs;
            // Prune stale CPU-side work as the player moves; retain one extra
            // ring for forward streaming so nearby buildings are ready before arrival.
            loader.retainAround(ptx, ptz, KEEP_R + 1);
            Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
            while (it.hasNext()) {
                Tile t = it.next().getValue();
                if (Math.abs(t.ix - ptx) > KEEP_R || Math.abs(t.iz - ptz) > KEEP_R) {
                    // Agents hold RoadPath/Tile references; release those first.
                    Iterator<Game.Agent> agents = game.agents.iterator();
                    while (agents.hasNext()) if (agents.next().tile == t) agents.remove();
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

        } else {
            loader.havePlayer = false;
        }

        uploadTerrain();
        if (!flightSchoolMode) refreshMiniMapRoads(nowMs);
        if (spawned) {
            game.update(dt, this);
            if (game.lastWeaponShotMs != 0L && game.lastWeaponShotMs != lastAudioWeaponShot) {
                lastAudioWeaponShot = game.lastWeaponShotMs;
                worldAudio.playEffect(game.equippedWeapon);
            }
            if(game.explosionEventCount!=lastAudioExplosionEvent){lastAudioExplosionEvent=game.explosionEventCount;worldAudio.playEffect(6);}
            if (!flightSchoolMode && nowMs - lastAgentMs > 700) { lastAgentMs = nowMs; spawnAgents(); }
            if (nowMs - lastHudMs > 200) {
                lastHudMs = nowMs;
                if (flightSchoolMode && game.driving && (game.vehicleType == ProceduralVehicleModels.HELICOPTER || game.vehicleType == ProceduralVehicleModels.AIRPLANE)) {
                    String phase = game.vehicleType == ProceduralVehicleModels.HELICOPTER
                            ? (game.altitude < 0.5f ? "على المنصة" : "في الجو")
                            : (game.aircraftStall ? "تحذير: سرعة منخفضة" : game.aircraftOnGround ? "على المدرج" : "في الجو");
                    L.onHud((game.vehicleType == ProceduralVehicleModels.HELICOPTER ? "هليكوبتر" : "طائرة")
                            + " | " + phase + " | " + Math.round(Math.abs(game.cv) * 3.6f) + " كم/س"
                            + " | ارتفاع " + Math.round(game.altitude) + " م"
                            + " | دفع " + Math.round(game.engineThrottle * 100f) + "%"
                            + (game.vehicleType == ProceduralVehicleModels.AIRPLANE ? (game.landingGearDown ? " | العجلات مفتوحة" : " | العجلات مطوية") + (game.aircraftDamage > 0f ? " | ضرر " + Math.round(game.aircraftDamage) + "%" : "") : ""));
                } else L.onHud(game.driving ? Math.round(Math.abs(game.cv) * 3.6f) + " كم/س" : "");
            }
        }

        worldAudio.update(game);
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
        GLES20.glUniform1f(uSkinning, 0f);
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
        float farY = flightSchoolMode ? -0.15f : Terrain.baseSet ? Terrain.seaY() - 300f : -0.02f;
        Matrix.translateM(tmp, 0, Math.round(g.camX / step) * step, farY, Math.round(g.camZ / step) * step);
        GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
        groundMesh.draw();
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0);
        if (!flightSchoolMode && !undergroundCutaway) {
            for (Mesh m : terrain.values()) m.draw();
        }

        // المربعات
        if (!flightSchoolMode) for (Tile t : tiles.values()) {
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > visibleTileRadius()) continue;
            drawMesh(t.meshes[TileBuilder.B_SIDEWALK], tex.sidewalk, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_ROADN], tex.roadN, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_ROADW], tex.roadW, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_PLAIN], tex.white, tex.black, 0f);
            drawMesh(t.meshes[TileBuilder.B_FACADE], tex.facade, tex.facadeEm, em);
            drawMesh(t.meshes[TileBuilder.B_GLASS], tex.glass, tex.glassEm, em);
            drawMesh(t.meshes[TileBuilder.B_SIGN], tex.sign, tex.sign, 1f);
        }
        // Reuse the complete former flight-school runway, taxiway, apron, hangar,
        // terminal and helipad at the selected airport's geographic origin.
        // The city tile stream remains active beneath/around this local airport scene.
        if (airportVisit && flightSchoolMesh != null) {
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, 0f, baseHeightAt(0f, -390f), 0f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
            drawMesh(flightSchoolMesh, tex.white, tex.black, 0f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0);
        }

        // الماء: بحر (إن وُجد ساحل) + أنهار وبحيرات، بمظلل يعكس السماء
        GLES20.glUniform1f(uWater, 1f);
        bindTex(tex.white, tex.black);
        GLES20.glUniform1f(uEmisK, 0f);
        boolean coast = false;
        if (!flightSchoolMode) for (Tile t : tiles.values()) {
            if (t.hasCoast) coast = true;
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > visibleTileRadius()) continue;
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
        if (!flightSchoolMode) for (Tile t : tiles.values()) {
            if (t.decalMeshes.isEmpty()) continue;
            if (Math.hypot(t.cx - g.camX, t.cz - g.camZ) > Math.min(760f, visibleTileRadius() * 0.72f)) continue;
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
        int detailedPedestrians = 0;
        int pedestrianBudget = graphicsQuality == 0 ? 6 : graphicsQuality == 1 ? 4 : 2;
        for (Game.Agent a : g.agents) {
            float agentDistance = (float)Math.hypot(a.x - g.camX, a.z - g.camZ);
            if (agentDistance > (a.car ? 190f : 95f)) continue;
            if (a.car) {
                float damageTint = a.knockedOut ? 0.48f : a.hitFlash > 0f ? 0.72f : 0f;
                float cr = a.cr * (1f-damageTint) + damageTint;
                float cg = a.cg * (1f-damageTint) + damageTint * 0.28f;
                float cb = a.cb * (1f-damageTint) + damageTint * 0.22f;
                drawVehicle(a.x, a.z, a.yaw, a.vehicleType, 0f, cr, cg, cb,
                        a.sp * a.dir, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            } else if (pedestrianCharacterAssets.length > 0 && detailedPedestrians < pedestrianBudget
                    && agentDistance < 42f && !a.knockedOut && a.state != 3
                    && pedestrianCharacterAssets[Math.floorMod(a.humanType, pedestrianCharacterAssets.length)] != null
                    && pedestrianWalkClips[Math.floorMod(a.humanType, pedestrianWalkClips.length)] >= 0) {
                drawSkinnedPerson(a, Terrain.h(a.x,a.z), frameDeltaSeconds,
                        Math.floorMod(a.humanType, pedestrianCharacterAssets.length));
                detailedPedestrians++;
            } else drawPerson(a.x,Terrain.h(a.x,a.z),a.z,a.yaw,a.ph,a.humanType,a.hostile,a.knockedOut,a.state,a.stateTime,a.hitFlash);
        }
        drawProjectileEffects();
        if (spawned) {
            drawVehicle(g.cx, g.cz, g.cyaw, playerVehicleType, g.py - Terrain.h(g.cx, g.cz), 0.75f, 0.22f, 0.17f, g.cv, g.sx,
                    g.vehicleBumpOffset, g.vehicleBumpRoll + g.vehicleWheelBodyRoll + g.aircraftRoll,
                    g.vehicleBumpPitch + g.vehicleWheelBodyPitch + g.aircraftPitch,
                    g.vehicleWheelFL - g.vehicleWheelBodyLift, g.vehicleWheelFR - g.vehicleWheelBodyLift,
                    g.vehicleWheelRL - g.vehicleWheelBodyLift, g.vehicleWheelRR - g.vehicleWheelBodyLift);
            if (!g.driving) drawKid(g.px,g.py,g.pz,g.pyaw,g.phase,g.swingAmp,g.attackSeconds,g.attackType,g.playerPose,g.playerPoseTime,g.guardBlend,g.hurtFlash,g.equippedWeapon,g.weaponAimHeld,g.weaponRecoil,g.muzzleFlashSeconds,g.weaponReloadSeconds,g.weaponReloadDuration);
        }
    }


    /** Draw short-lived rocket meshes, exhaust and bounded smoke / blast effects. */
    private void drawProjectileEffects() {
        if (cube == null) return;
        GLES20.glUniform1f(uWater,0f);
        GLES20.glUniform1f(uGlass,0f);
        GLES20.glUniform1f(uPbr,0f);
        bindTex(tex.white,tex.black);
        for (ProjectileSystem.Rocket r : game.rockets) {
            if (Math.hypot(r.x-game.camX,r.z-game.camZ)>520f) continue;
            Matrix.setIdentityM(tmp,0);
            Matrix.translateM(tmp,0,r.x,r.y,r.z);
            Matrix.rotateM(tmp,0,(float)Math.toDegrees(r.yaw),0f,1f,0f);
            Matrix.rotateM(tmp,0,(float)-Math.toDegrees(r.pitch),1f,0f,0f);
            GLES20.glUniformMatrix4fv(uModel,1,false,tmp,0);
            GLES20.glUniform3f(uTint,1f,1f,1f);
            GLES20.glUniform1f(uEmisK,.035f);
            Mesh activeRocketMesh=r.guided?guidedRocketMesh:rocketMesh;
            if(activeRocketMesh!=null)activeRocketMesh.draw();
            Matrix.setIdentityM(tmp,0);
            Matrix.translateM(tmp,0,r.x-(float)Math.sin(r.yaw)*.53f,r.y,r.z-(float)Math.cos(r.yaw)*.53f);
            Matrix.scaleM(tmp,0,.24f,.15f,.15f);
            GLES20.glUniformMatrix4fv(uModel,1,false,tmp,0);
            GLES20.glUniform3f(uTint,1f,.34f,.035f);
            GLES20.glUniform1f(uEmisK,1.15f);
            cube.draw();
            for(int k=0;k<r.trailCount;k++){
                float fade=1f-k/(float)Math.max(1,r.tx.length);
                Matrix.setIdentityM(tmp,0);
                Matrix.translateM(tmp,0,r.tx[k],r.ty[k],r.tz[k]);
                float size=.10f+.12f*(1f-fade);
                Matrix.scaleM(tmp,0,size,size,size);
                GLES20.glUniformMatrix4fv(uModel,1,false,tmp,0);
                GLES20.glUniform3f(uTint,.52f,.54f,.57f);
                GLES20.glUniform1f(uEmisK,.04f);
                cube.draw();
            }
        }
        for(ProjectileSystem.Blast b:game.blasts){
            float t=Math.max(0f,b.life/.72f),size=(1f-t)*4.8f+.35f;
            Matrix.setIdentityM(tmp,0);
            Matrix.translateM(tmp,0,b.x,b.y,b.z);
            Matrix.scaleM(tmp,0,size,size,size);
            GLES20.glUniformMatrix4fv(uModel,1,false,tmp,0);
            GLES20.glUniform3f(uTint,1f,.34f,.045f);
            GLES20.glUniform1f(uEmisK,1.25f*t);
            cube.draw();
            Matrix.setIdentityM(tmp,0);
            Matrix.translateM(tmp,0,b.x,b.y+.35f,b.z);
            Matrix.scaleM(tmp,0,size*.72f,size*.82f,size*.72f);
            GLES20.glUniformMatrix4fv(uModel,1,false,tmp,0);
            GLES20.glUniform3f(uTint,.30f,.29f,.27f);
            GLES20.glUniform1f(uEmisK,.02f);
            cube.draw();
        }
        GLES20.glUniformMatrix4fv(uModel,1,false,ident,0);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        GLES20.glUniform1f(uEmisK,0f);
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

    private void drawVehicle(float x,float z,float yaw,int type,float yOffset,float cr,float cg,float cb,float speed,float steering,
                             float suspensionY,float suspensionRoll,float suspensionPitch,
                             float wheelFL,float wheelFR,float wheelRL,float wheelRR) {
        if(type<0 || vehicleModels==null || type>=vehicleModels.length) return;
        AssetMeshLoader.Model imported = importedVehicleModels != null ? importedVehicleModels[type] : null;
        if(imported==null && vehicleModels[type]==null) return;
        float length=(type==ProceduralVehicleModels.AIRPLANE?7.0f:type==ProceduralVehicleModels.BUS?8.3f:5.0f);
        float sy=(float)Math.sin(yaw), cy=(float)Math.cos(yaw);
        float hf=Terrain.h(x+sy*length*.42f,z+cy*length*.42f);
        float hb=Terrain.h(x-sy*length*.42f,z-cy*length*.42f);
        float pitch=-(float)Math.toDegrees(Math.atan2(hf-hb,length*.84f));
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,(hf+hb)*.5f+yOffset+suspensionY,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        Matrix.rotateM(base,0,pitch+suspensionPitch,1f,0f,0f);
        Matrix.rotateM(base,0,suspensionRoll,0f,0f,1f);
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
        // اللون يظل في رؤوس الشبكة؛ التلوين هنا يستخدم فقط كتعديل خفيف للطلاء.
        GLES20.glUniform3f(uTint,.70f+cr*.30f,.70f+cg*.30f,.70f+cb*.30f);
        GLES20.glUniform1f(uGlass,0f);
        // Keep the costly multi-texture PBR path for the player's own vehicle only.
        // AI traffic uses the lit base material to protect mobile/TV frame time.
        boolean detailedPlayerVehicle = advancedShader && game.driving
                && Math.abs(x - game.cx) < 0.01f && Math.abs(z - game.cz) < 0.01f;
        GLES20.glUniform1f(uPbr,detailedPlayerVehicle ? 1f : 0f);
        GLES20.glUniform2f(uMatParams, 1f, 1f);
        if (detailedPlayerVehicle) bindVehicleMaps();
        if(imported != null) imported.draw(uMatParams, tex.white);
        else { GLES20.glUniform2f(uMatParams, 1f, 1f); vehicleModels[type].draw(); }
        GLES20.glUniform1f(uPbr,0f);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        if(imported == null) {
            drawVehicleGlass(type);
            if(type==ProceduralVehicleModels.TANK) drawTankTracks(speed);
            if(type==ProceduralVehicleModels.HELICOPTER) drawHelicopterRotor();
            if(type==ProceduralVehicleModels.AIRPLANE) { drawAirplanePropeller(); if (game.landingGearDown) drawAirplaneLandingGear(); }
            if(type!=ProceduralVehicleModels.TANK && type!=ProceduralVehicleModels.HELICOPTER && type!=ProceduralVehicleModels.AIRPLANE) drawVehicleWheels(type,speed,steering,wheelFL,wheelFR,wheelRL,wheelRR);
        }
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

    private void drawVehicleWheels(int type,float speed,float steering,float wheelFL,float wheelFR,float wheelRL,float wheelRR) {
        if(wheelMesh==null) return;
        if(type==ProceduralVehicleModels.BICYCLE || type==ProceduralVehicleModels.MOTORBIKE) {
            float radius=type==ProceduralVehicleModels.BICYCLE?.34f:.43f;
            float half=type==ProceduralVehicleModels.BICYCLE?1.65f:1.45f;
            float angle=(float)(System.nanoTime()*1.0e-9*speed/radius);
            for(int axle=-1;axle<=1;axle+=2) {
                System.arraycopy(base,0,wheelMatrix,0,16);
                float wheelStep = axle > 0 ? (wheelFL + wheelFR) * 0.5f : (wheelRL + wheelRR) * 0.5f;
                Matrix.translateM(wheelMatrix,0,0,radius + wheelStep - (wheelFL + wheelFR + wheelRL + wheelRR) * 0.25f,axle*half);
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
            float wheelStep = axle > 0 ? (side < 0 ? wheelFL : wheelFR) : (side < 0 ? wheelRL : wheelRR);
            Matrix.translateM(wheelMatrix,0,side*.91f,.42f+wheelStep,axle*halfZ);
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

    private void drawAirplanePropeller() {
        float angle = (float)((System.nanoTime() * 1.0e-9 * (180f + Math.abs(game.cv) * 55f)) % 360f);
        drawVehiclePart(0f, 0.84f, 3.58f, angle, 0f, 0f, 1f, 0.12f, 1.65f, 0.12f, .16f, .17f, .18f);
        drawVehiclePart(0f, 0.84f, 3.58f, angle + 90f, 0f, 0f, 1f, 0.12f, 1.65f, 0.12f, .16f, .17f, .18f);
        GLES20.glUniformMatrix4fv(uModel, 1, false, base, 0);
    }

    private void drawAirplaneLandingGear() {
        float[][] positions = {{-1.12f, .34f, -.45f}, {1.12f, .34f, -.45f}, {0f, .32f, 2.15f}};
        for (float[] p : positions) {
            drawVehiclePart(p[0], p[1] + .20f, p[2], 0f, 1f, 0f, 0f, .10f, .42f, .10f, .30f, .31f, .32f);
            System.arraycopy(base, 0, wheelMatrix, 0, 16);
            Matrix.translateM(wheelMatrix, 0, p[0], p[1], p[2]);
            Matrix.scaleM(wheelMatrix, 0, .62f, .62f, .62f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, wheelMatrix, 0);
            wheelMesh.draw();
        }
        GLES20.glUniformMatrix4fv(uModel, 1, false, base, 0);
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

    /** اختيار مباشر لنوع المركبة من واجهة الهاتف. */
    public void selectVehicleType(int type) {
        if (type < 0 || type >= ProceduralVehicleModels.COUNT) return;
        if (game.driving) {
            if (!flightSchoolMode || Math.abs(game.cv) > 1f || game.altitude > 0.8f) { L.onToast("أوقف المركبة واهبط قبل تغيير النوع"); return; }
            game.driving = false;
        }
        playerVehicleType = type;
        if (flightSchoolMode) { spawnFlightSchoolVehicle(true); L.onToast("تم تجهيز " + ProceduralVehicleModels.name(type) + " في المطار"); }
        else L.onToast("المركبة المحددة: " + ProceduralVehicleModels.name(playerVehicleType) + " — اضغط استدعاء / دخول");
    }

    /** Imported, skinned player path. Animation state follows the same combat inputs as the fallback rig. */
    private void drawImportedKid(float x,float y,float z,float yaw,float movement,float attack,int attackType,
                                 int pose,float poseTime,float guard,float hurt,int weaponId,boolean aiming,
                                 float recoil,float muzzleFlash,float reloadSeconds,float reloadDuration) {
        SkeletalAnimationRuntime.Animator animator=playerCharacterAsset.animator;
        int clip=-1;
        boolean repeat=true;
        // Combat and recovery take priority over locomotion; clip names are optional
        // so packs with a smaller animation set still degrade to a valid idle/walk cycle.
        if(pose==2 || pose==3) {
            clip=animator.findClip(pose==3?"down":"fall");
            if(clip<0) clip=animator.findClip("knockdown");
            if(clip<0) clip=animator.findClip("hit");
            repeat=pose==3;
        } else if(pose==4) {
            clip=animator.findClip("getup");
            if(clip<0) clip=animator.findClip("stand_up");
            if(clip<0) clip=animator.findClip("recover");
            repeat=false;
        } else if(hurt>0.02f) {
            clip=animator.findClip("hurt");
            if(clip<0) clip=animator.findClip("hit");
            repeat=false;
        } else if(attack>0.01f) {
            clip=animator.findClip(attackType==3?"kick":attackType==2?"heavy_punch":"punch");
            if(clip<0) clip=animator.findClip("attack");
            repeat=false;
        } else if(reloadSeconds>0.01f && weaponId>=0) {
            clip=animator.findClip("reload");
            repeat=false;
        } else if(guard>0.05f) {
            clip=animator.findClip("guard");
            if(clip<0) clip=animator.findClip("block");
        } else if(aiming || muzzleFlash>0.01f) {
            clip=animator.findClip(muzzleFlash>0.01f?"shoot":"aim");
            if(clip<0) clip=animator.findClip("aim");
        } else if(game.jumpHeight>0.12f) {
            clip=animator.findClip("jump");
            repeat=false;
        } else if(movement>0.08f) {
            clip=animator.findClip(game.sprint?"run":"walk");
        }
        if(clip<0) clip=animator.findClip("idle");
        if(clip<0 && animator.clips.length>0) clip=0;
        if(clip>=0 && animator.current!=clip) animator.play(clip,.16f,repeat);
        long now=System.nanoTime();
        float dt=lastCharacterAnimationNanos==0L?0f:(now-lastCharacterAnimationNanos)*1.0e-9f;
        lastCharacterAnimationNanos=now;
        animator.update(dt);
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,y,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        Matrix.scaleM(base,0,1.28f,1.28f,1.28f);
        GLES20.glUniformMatrix4fv(uModel,1,false,base,0);
        GLES20.glUniformMatrix4fv(uBones,animator.boneCount(),false,animator.skinMatrices(),0);
        GLES20.glUniform1f(uSkinning,1f);
        GLES20.glUniform1f(uPbr,0f);
        GLES20.glUniform1f(uGlass,0f);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        bindTex(tex.white,tex.black);
        playerCharacterAsset.mesh.draw();
        GLES20.glUniform1f(uSkinning,0f);
    }

    /** اللاعب: نسخة طفولية من الشخصية الإجرائية نفسها مع مفاصل ووجه وملابس مولّدة. */
    private void drawKid(float x,float y,float z,float yaw,float ph,float amp,float attack,int attackType,int pose,float poseTime,float guard,float hurt,int weaponId,boolean aiming,float recoil,float muzzleFlash,float reloadSeconds,float reloadDuration) {
        if(playerCharacterAsset!=null){drawImportedKid(x,y,z,yaw,Math.max(Math.abs(game.sx),Math.abs(game.sy)),attack,attackType,pose,poseTime,guard,hurt,weaponId,aiming,recoil,muzzleFlash,reloadSeconds,reloadDuration);return;}
        if(humanRigModels==null||humanRigModels.length==0)return;
        Mesh[] rig=humanRigModels[0];
        float swing=(float)Math.sin(ph)*amp*.58f;
        float bob=(float)Math.sin(ph*2f)*.018f;
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,y+bob,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        float fall=pose==2?Math.min(1f,poseTime/.25f):pose==3?1f:pose==4?Math.max(0f,1f-poseTime/.55f):0f;
        Matrix.translateM(base,0,0f,.82f*fall,0f); Matrix.rotateM(base,0,-82f*fall,0f,0f,1f); Matrix.translateM(base,0,0f,-.82f*fall,0f);
        Matrix.scaleM(base,0,1.08f,1.08f,1.08f);
        float attackDuration = attackType == 3 ? .62f : .42f;
        float attackPhase = attack > 0f ? Math.max(0f,Math.min(1f,1f-attack/attackDuration)) : 0f;
        float attackPulse = attack > 0f ? (float)Math.sin(attackPhase*Math.PI) : 0f;
        if (attackPulse > 0f) Matrix.rotateM(base,0,-7.5f*attackPulse,1f,0f,0f);
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
            if(guard>.05f)arm=side>0?-72f*guard:-58f*guard;
            if(aiming && weaponId>=0 && weaponId<WeaponSystem.NAME.length) arm=side>0?-104f:-76f;
            float reloadProgress=reloadDuration>0f?Math.max(0f,Math.min(1f,1f-reloadSeconds/reloadDuration)):0f;
            float reloadMotion=reloadSeconds>0f?(float)Math.sin(reloadProgress*Math.PI):0f;
            if(reloadSeconds>0f&&weaponId>=0&&weaponId<WeaponSystem.NAME.length)arm=side>0?-94f+18f*reloadMotion:-18f-78f*reloadMotion;
            if(hurt>0f)arm=side>0?65f:-55f;
            if(fall>.05f)arm=side>0?-65f:55f;
            if (attack > 0f && attackType == 1) {
                if (side > 0) arm = -128f * attackPulse;
                else arm = 24f * attackPulse;
            }
            if (attack > 0f && attackType == 2) {
                if (side > 0) arm = -158f * attackPulse;
                else arm = 34f * attackPulse;
            }
            if (attack > 0f && attackType == 3 && side > 0) leg = -104f * attackPulse;
            drawHumanPart(rig[3],side*.21f,1.32f,0,arm,side*.075f,-.15f,0,1,1,1);
            drawHumanPart(rig[4],side*.29f,1.00f,0,arm*.42f,side*.012f,-.12f,.01f,1,1,1);
            drawHumanPart(rig[5],side*.115f,.78f,0,leg,0,-.16f,0,1,1,1);
            drawHumanPart(rig[6],side*.115f,.44f,0,-leg*.30f,0,-.11f,0,1,1,1);
            drawHumanPart(rig[7],side*.115f,.15f,.045f,leg,0,-.02f,.06f,1,1,1);
            drawHumanPart(rig[8],side*.052f,1.645f,.127f,0,0,0,0,1,1,1);
            drawHumanPart(rig[12],side*.052f,1.667f,.137f,0,0,0,0,1,1,1);
            drawHumanPart(rig[15],side*.31f,.78f,.02f,arm*.42f,0,0,0,1,1,1);
            if(side>0&&weaponId>=0&&weaponId<WeaponSystem.NAME.length){
                float gunRot=arm*.42f + recoil*10f + reloadMotion*34f;
                if(weaponId==0){
                    // Pistol: slide, muzzle and grip are separate solid parts held in the palm.
                    drawHumanPartY(cube,.40f,.92f,.19f,gunRot,-90f,0,0,0,.25f,.075f,.060f);
                    drawHumanPartY(cube,.53f,.93f,.19f,gunRot,-90f,0,0,0,.13f,.035f,.038f);
                    drawHumanPartY(cube,.34f,.82f,.20f,gunRot,-90f,0,0,0,.075f,.15f,.060f);
                    if(muzzleFlash>0.02f) drawHumanPartY(cube,.70f,.93f,.19f,gunRot,-90f,0,0,0,.10f*muzzleFlash,.10f*muzzleFlash,.10f*muzzleFlash);
                }else if(weaponId>=5){
                    // Shoulder-fired launcher: long reinforced tube, muzzle ring, rear cap and stabilizing fins.
                    drawHumanPartY(cube,.48f,1.00f,.18f,gunRot,-90f,0,0,0,.62f,.125f,.125f);
                    drawHumanPartY(cube,.91f,1.00f,.18f,gunRot,-90f,0,0,0,.095f,.17f,.17f);
                    drawHumanPartY(cube,.02f,1.00f,.18f,gunRot,-90f,0,0,0,.12f,.15f,.15f);
                    drawHumanPartY(cube,.36f,.85f,.20f,gunRot,-90f,0,0,0,.12f,.20f,.075f);
                    drawHumanPartY(cube,.74f,.88f,.18f,gunRot,-90f,0,0,0,.10f,.08f,.24f);
                    if(muzzleFlash>0.02f)drawHumanPartY(cube,1.04f,1.00f,.18f,gunRot,-90f,0,0,0,.18f*muzzleFlash,.18f*muzzleFlash,.18f*muzzleFlash);
                }else{
                    // Long guns: receiver, barrel, stock, foregrip and magazine create a readable silhouette.
                    drawHumanPartY(cube,.43f,.94f,.19f,gunRot,-90f,0,0,0,.30f,.085f,.070f);
                    drawHumanPartY(cube,.68f,.96f,.19f,gunRot,-90f,0,0,0,.34f,.035f,.035f);
                    drawHumanPartY(cube,.22f,.94f,.19f,gunRot,-90f,0,0,0,.20f,.070f,.065f);
                    drawHumanPartY(cube,.39f,.82f-reloadMotion*.075f,.20f,gunRot,-90f,0,0,0,.10f,.18f,.060f);
                    if(weaponId==4)drawHumanPartY(cube,.75f,.96f,.19f,gunRot,-90f,0,0,0,.12f,.07f,.06f);
                    if(weaponId==3)drawHumanPartY(cube,.48f,.80f,.20f,gunRot,-90f,0,0,0,.11f,.18f,.065f);
                    if(muzzleFlash>0.02f) drawHumanPartY(cube,1.02f,.96f,.19f,gunRot,-90f,0,0,0,.13f*muzzleFlash,.11f*muzzleFlash,.11f*muzzleFlash);
                }
            }
        }
        drawHumanPart(rig[9],0,1.555f,.137f,0,0,0,0,1,1,1);
    }

    private void drawHumanPartY(Mesh mesh,float px,float py,float pz,float rz,float ry,
                                 float ox,float oy,float oz,float sx,float sy,float sz) {
        if(mesh==null)return;
        System.arraycopy(base,0,wheelMatrix,0,16);
        Matrix.translateM(wheelMatrix,0,px,py,pz);
        if(rz!=0f) Matrix.rotateM(wheelMatrix,0,rz,0f,0f,1f);
        if(ry!=0f) Matrix.rotateM(wheelMatrix,0,ry,0f,1f,0f);
        Matrix.translateM(wheelMatrix,0,ox,oy,oz);
        Matrix.scaleM(wheelMatrix,0,sx,sy,sz);
        GLES20.glUniformMatrix4fv(uModel,1,false,wheelMatrix,0);
        GLES20.glUniform3f(uTint,1f,1f,1f);
        mesh.draw();
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

    /** Render nearby pedestrians with one shared textured rig and an independent animator per agent. */
    private void drawSkinnedPerson(Game.Agent agent, float y, float dt, int modelIndex) {
        SkinnedAssetLoader.Asset asset = pedestrianCharacterAssets[modelIndex];
        int walkClip = pedestrianWalkClips[modelIndex];
        if (asset == null || walkClip < 0) return;
        SkeletalAnimationRuntime.Animator animator = pedestrianAnimators.get(agent);
        if (animator == null) {
            SkeletalAnimationRuntime.Animator template = asset.animator;
            animator = new SkeletalAnimationRuntime.Animator(template.skeleton, template.clips);
            animator.play(walkClip, 0f, true);
            pedestrianAnimators.put(agent, animator);
        } else if (animator.current != walkClip) {
            animator.play(walkClip, 0.12f, true);
        }
        animator.speed = agent.state == 2 ? 1.12f : 1f;
        animator.update(dt);
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, agent.x, y, agent.z);
        Matrix.rotateM(base, 0, (float)Math.toDegrees(agent.yaw), 0f, 1f, 0f);
        Matrix.scaleM(base, 0, 1.0f, 1.0f, 1.0f);
        GLES20.glUniformMatrix4fv(uModel, 1, false, base, 0);
        GLES20.glUniformMatrix4fv(uBones, animator.boneCount(), false, animator.skinMatrices(), 0);
        GLES20.glUniform1f(uSkinning, 1f);
        GLES20.glUniform1f(uPbr, 0f);
        GLES20.glUniform1f(uGlass, 0f);
        GLES20.glUniform1f(uWater, 0f);
        GLES20.glUniform1f(uDecal, 0f);
        GLES20.glUniform1f(uEmisK, 0f);
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
        bindTex(tex.white, tex.black);
        asset.mesh.draw();
        GLES20.glUniform1f(uSkinning, 0f);
    }

    private void drawPerson(float x,float y,float z,float yaw,float phase,int variant,boolean hostile,boolean knockedOut,int state,float stateTime,float hitFlash) {
        if(humanRigModels==null||humanRigModels.length==0)return;
        int id=(variant%humanRigModels.length+humanRigModels.length)%humanRigModels.length;
        Mesh[] rig=humanRigModels[id];
        float bob=(float)Math.sin(phase*2f)*.025f;
        float swing=(float)Math.sin(phase)*.58f;
        Matrix.setIdentityM(base,0);
        Matrix.translateM(base,0,x,y+bob,z);
        Matrix.rotateM(base,0,(float)Math.toDegrees(yaw),0f,1f,0f);
        float down=knockedOut||state==3?1f:state==4?Math.max(0f,1f-stateTime):0f;
        Matrix.translateM(base,0,0f,.82f*down,0f); Matrix.rotateM(base,0,-82f*down,0f,0f,1f); Matrix.translateM(base,0,0f,-.82f*down,0f);
        if(hitFlash>0f)GLES20.glUniform3f(uTint,1f,.55f,.45f);
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
            if(hostile&&side>0)armAngle=(float)Math.sin(phase*2.5f)*24f-62f;
            if(state==2)armAngle=(side>0?-42f:38f)+(float)Math.sin(phase*2f)*18f;
            if(down>.05f)armAngle=side>0?-18f:22f;
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
        GLES20.glUniform3f(uTint,1f,1f,1f);
    }}
