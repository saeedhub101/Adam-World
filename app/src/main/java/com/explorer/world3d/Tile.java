package com.explorer.world3d;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;

import java.util.ArrayList;
import java.util.HashMap;

/** مربع محمّل على الـGPU. */
final class Tile {
    final long key;
    final int ix, iz;
    final Mesh[] meshes = new Mesh[TileData.N_BUFS];
    final HashMap<Long, ArrayList<Footprint>> grid;
    final ArrayList<RoadPath> paths;
    final boolean hasCoast;
    final boolean manila;
    final ArrayList<Mesh> decalMeshes = new ArrayList<>();
    final ArrayList<Integer> decalTextures = new ArrayList<>();
    float cx, cz;
    boolean dead;

    Tile(TileData d) {
        this.ix = d.ix; this.iz = d.iz;
        this.key = Geo.key(d.ix, d.iz);
        this.grid = d.grid;
        this.paths = d.paths;
        this.hasCoast = d.hasCoast;
        this.manila = d.manila;
        double west = ix * Geo.dLon, east = (ix + 1) * Geo.dLon, south = iz * Geo.D_LAT, north = (iz + 1) * Geo.D_LAT;
        cx = (Geo.x(west) + Geo.x(east)) / 2f;
        cz = (Geo.z(north) + Geo.z(south)) / 2f;
        for (int i = 0; i < meshes.length; i++) {
            meshes[i] = Mesh.from(d.bufs[i]);
            // CPU vertex data is no longer needed after the GPU VBO upload.
            if (d.bufs[i] != null) d.bufs[i].releaseStorage();
        }
        buildDecals(d);
    }

    /** Text atlases are paged so every sourced building name/address can be rendered. */
    private void buildDecals(TileData d) {
        if (d.decals.isEmpty()) return;
        final int labelsPerPage = 64;
        ArrayList<ArrayList<String>> pages = new ArrayList<>();
        HashMap<String, Integer> pageByName = new HashMap<>();
        for (Decal dc : d.decals) {
            if (pageByName.containsKey(dc.text)) continue;
            int page = pages.size() - 1;
            if (page < 0 || pages.get(page).size() >= labelsPerPage) {
                pages.add(new ArrayList<>());
                page = pages.size() - 1;
            }
            pages.get(page).add(dc.text);
            pageByName.put(dc.text, page);
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        for (int page = 0; page < pages.size(); page++) {
            ArrayList<String> names = pages.get(page);
            Bitmap bmp = Bitmap.createBitmap(1024, 2048, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            float[] wpx = new float[names.size()];
            for (int i = 0; i < names.size(); i++) {
                float fontSize = manila ? 30f : 24f;
                p.setTextSize(fontSize);
                float w = p.measureText(names.get(i));
                if (w > 1000f) { p.setTextSize(fontSize * 1000f / w); w = 1000f; }
                canvas.drawText(names.get(i), 8f, i * 32f + 25f, p);
                wpx[i] = w + 8f;
            }
            int texture = Textures.upload(bmp);
            GeoBuf g = new GeoBuf();
            float hw = 1.2f;
            for (Decal dc : d.decals) {
                Integer ownerPage = pageByName.get(dc.text);
                if (ownerPage == null || ownerPage != page) continue;
                int idx = names.indexOf(dc.text);
                if (idx < 0) continue;
                float len = wpx[idx] * (manila ? 0.045f : 0.0375f);
                float dx = dc.dx, dz = dc.dz;
                float lx = dz, lz = -dx, rx = -dz, rz = dx;
                float sx = dc.x - dx * len / 2f, sz = dc.z - dz * len / 2f;
                float ex = dc.x + dx * len / 2f, ez = dc.z + dz * len / 2f;
                float bsx = sx + rx * hw, bsz = sz + rz * hw;
                float bex = ex + rx * hw, bez = ez + rz * hw;
                float tex_ = ex + lx * hw, tez = ez + lz * hw;
                float tsx = sx + lx * hw, tsz = sz + lz * hw;
                float baseY = Float.isFinite(dc.y) ? dc.y : Float.NaN;
                float u1 = wpx[idx] / 1024f;
                float v0 = idx / 64f + 0.002f, v1 = (idx + 1) / 64f - 0.002f;
                if (dc.vertical && Float.isFinite(baseY)) {
                    float vlx = dc.x - dx * len / 2f, vlz = dc.z - dz * len / 2f;
                    float vrx = dc.x + dx * len / 2f, vrz = dc.z + dz * len / 2f;
                    float top = baseY + (manila ? 1.35f : 1.15f);
                    g.v(vlx, baseY, vlz, 0, v1, 1, 1, 1); g.v(vrx, baseY, vrz, u1, v1, 1, 1, 1); g.v(vrx, top, vrz, u1, v0, 1, 1, 1); g.fixTri();
                    g.v(vlx, baseY, vlz, 0, v1, 1, 1, 1); g.v(vrx, top, vrz, u1, v0, 1, 1, 1); g.v(vlx, top, vlz, 0, v0, 1, 1, 1); g.fixTri();
                } else {
                    float yBs = Float.isFinite(baseY) ? baseY : Terrain.h(bsx, bsz) + 0.34f;
                    float yBe = Float.isFinite(baseY) ? baseY : Terrain.h(bex, bez) + 0.34f;
                    float yTe = Float.isFinite(baseY) ? baseY : Terrain.h(tex_, tez) + 0.34f;
                    float yTs = Float.isFinite(baseY) ? baseY : Terrain.h(tsx, tsz) + 0.34f;
                    g.v(bsx, yBs, bsz, 0, v1, 1, 1, 1); g.v(bex, yBe, bez, u1, v1, 1, 1, 1); g.v(tex_, yTe, tez, u1, v0, 1, 1, 1); g.fixTri();
                    g.v(bsx, yBs, bsz, 0, v1, 1, 1, 1); g.v(tex_, yTe, tez, u1, v0, 1, 1, 1); g.v(tsx, yTs, tsz, 0, v0, 1, 1, 1); g.fixTri();
                }
            }
            decalTextures.add(texture);
            decalMeshes.add(Mesh.from(g));
        }
    }

    void release() {
        dead = true;
        for (int i = 0; i < meshes.length; i++) {
            if (meshes[i] != null) { meshes[i].release(); meshes[i] = null; }
        }
        for (Mesh mesh : decalMeshes) if (mesh != null) mesh.release();
        decalMeshes.clear();
        for (Integer texture : decalTextures) if (texture != null && texture != 0)
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        decalTextures.clear();
        // Collision and path data are CPU-side tile payloads too. Drop them as
        // soon as the tile leaves the active world; live agents are removed by renderer.
        grid.clear();
        paths.clear();
    }
}
