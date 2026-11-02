package com.explorer.world3d;

import java.util.ArrayList;

/** حالة اللعبة: اللاعب، السيارة، الكاميرا، والمشاة/السيارات الآلية. */
final class Game {
    interface World { boolean blocked(float x, float z, float r); float heightAt(float x, float z); }

    static final class Agent {
        boolean car;
        RoadPath path;
        Tile tile;
        float off, s, sp, ph, x, z, yaw;
        int dir = 1;
        float cr, cg, cb;
    }

    float py, px, pz, pyaw, cx, cz, cyaw, cv, phase, swingAmp;
    boolean driving;
    float camYaw, camPitch = 0.38f, camX, camY = 3f, camZ, tgtX, tgtY = 1.5f, tgtZ;
    long lastLookMs;
    volatile float sx, sy;
    volatile boolean sprint;
    final ArrayList<Agent> agents = new ArrayList<>();
    private final float[] tmp = new float[4];

    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    static float angDiff(float a, float b) {
        float d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    void reset() {
        py = 0; px = pz = pyaw = 0; cx = 4; cz = 0; cyaw = 0; cv = 0; driving = false; camYaw = 0; agents.clear();
    }

    float focusX() { return driving ? cx : px; }
    float focusZ() { return driving ? cz : pz; }

    void look(float dx, float dy) {
        camYaw -= dx * 0.006f;
        camPitch = clamp(camPitch - dy * 0.004f, 0.05f, 1.3f);
        lastLookMs = System.currentTimeMillis();
    }

    void update(float dt, World w) {
        float jx = sx, jy = sy;
        float mag = Math.min(1f, (float) Math.hypot(jx, jy));
        if (!driving) {
            if (mag > 0.08f) {
                float fx = (float) Math.sin(camYaw), fz = (float) Math.cos(camYaw);
                float rx = -fz, rz = fx; // يمين الكاميرا
                rx = -(float) Math.cos(camYaw); rz = (float) Math.sin(camYaw);
                float dx = fx * jy + rx * jx, dz = fz * jy + rz * jx;
                float dl = (float) Math.hypot(dx, dz);
                if (dl < 1e-5f) dl = 1;
                dx /= dl; dz /= dl;
                float speed = (sprint ? 8.5f : 4.6f) * mag;
                float nx = px + dx * speed * dt, nz = pz + dz * speed * dt;
                if (!w.blocked(nx, nz, 0.45f)) { px = nx; pz = nz; }
                else if (!w.blocked(nx, pz, 0.45f)) px = nx;
                else if (!w.blocked(px, nz, 0.45f)) pz = nz;
                pyaw += angDiff(pyaw, (float) Math.atan2(dx, dz)) * Math.min(1f, dt * 12f);
                phase += speed * dt * 1.7f;
                swingAmp = 0.8f * Math.min(1f, speed / 4f);
            } else swingAmp = 0;
            py += (w.heightAt(px, pz) - py) * Math.min(1f, dt * 10f);
        } else {
            if (jy > 0.1f) cv += jy * 14f * dt;
            else if (jy < -0.1f) cv += jy * (cv > 0 ? 24f : 8f) * dt;
            else cv -= Math.signum(cv) * Math.min(Math.abs(cv), 5f * dt);
            cv = clamp(cv, -8f, 32f);
            cyaw -= jx * 1.7f * dt * clamp(cv / 5f, -1f, 1f);
            float fx = (float) Math.sin(cyaw), fz = (float) Math.cos(cyaw);
            float nx = cx + fx * cv * dt, nz = cz + fz * cv * dt;
            if (w.blocked(nx + fx * 2.1f, nz + fz * 2.1f, 0.9f) || w.blocked(nx - fx * 2.1f, nz - fz * 2.1f, 0.9f)
                    || w.blocked(nx, nz, 1.0f)) {
                cv = -cv * 0.25f;
            } else { cx = nx; cz = nz; }
            px = cx; pz = cz;
            py += (w.heightAt(cx, cz) - py) * Math.min(1f, dt * 10f);
            if (System.currentTimeMillis() - lastLookMs > 2500) {
                camYaw += angDiff(camYaw, cyaw) * Math.min(1f, dt * 2.5f);
            }
        }

        for (int i = agents.size() - 1; i >= 0; i--) {
            Agent a = agents.get(i);
            if (a.tile.dead) { agents.remove(i); continue; }
            a.s += a.dir * a.sp * dt;
            if (a.s > a.path.total) { a.s = a.path.total; a.dir = -1; }
            else if (a.s < 0) { a.s = 0; a.dir = 1; }
            a.path.posAt(a.s, a.car ? a.off * a.dir : a.off, tmp);
            a.x = tmp[0]; a.z = tmp[1];
            a.yaw = (float) Math.atan2(tmp[2] * a.dir, tmp[3] * a.dir);
            if (!a.car) a.ph += a.sp * dt * 2.2f;
        }

        updateCamera(dt);
    }

    private void updateCamera(float dt) {
        float tx = focusX(), tz = focusZ();
        float dist = driving ? 9.5f : 4.0f, th = driving ? 1.6f : 1.1f;
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float dx = tx - (float) Math.sin(camYaw) * dist * cp;
        float dz = tz - (float) Math.cos(camYaw) * dist * cp;
        float dy = Math.max(py + 0.6f, th + py + dist * sp);
        float k = 1f - (float) Math.exp(-dt * 14f);
        camX += (dx - camX) * k; camY += (dy - camY) * k; camZ += (dz - camZ) * k;
        tgtX = tx; tgtY = th + py; tgtZ = tz;
    }

    void snapCamera() {
        float tx = focusX(), tz = focusZ();
        camX = tx - (float) Math.sin(camYaw) * 5f; camZ = tz - (float) Math.cos(camYaw) * 5f; camY = py + 3f;
    }
}
