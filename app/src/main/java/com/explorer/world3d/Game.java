package com.explorer.world3d;

import java.util.ArrayList;

/** حالة اللعبة: اللاعب، السيارة، الكاميرا، والمشاة/السيارات الآلية. */
final class Game {
    interface World { boolean blocked(float x, float z, float r); float heightAt(float x, float z); }

    static final class Agent {
        boolean car;
        int vehicleType = ProceduralVehicleModels.SEDAN;
        int humanType = 0;
        RoadPath path;
        Tile tile;
        float off, s, sp, ph, x, z, yaw;
        int dir = 1;
        float cr, cg, cb;
    }

    float py, px, pz, pyaw, cx, cz, cyaw, cv, phase, swingAmp;
    boolean driving;
    volatile boolean paused, jumpRequested, handbrake;
    volatile float cameraLookOffset;
    float jumpHeight, jumpVelocity;
    int vehicleType = ProceduralVehicleModels.SEDAN;
    float altitude;
    volatile float liftInput;
    float camYaw, camPitch = 0.38f, camDistance = 5.2f, camX, camY = 3f, camZ, tgtX, tgtY = 1.5f, tgtZ;
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
        py = 0; px = pz = pyaw = 0; cx = 4; cz = 0; cyaw = 0; cv = 0; driving = false; vehicleType = ProceduralVehicleModels.SEDAN; altitude = 0; liftInput = 0; camYaw = 0; paused = false; jumpRequested = false; handbrake = false; cameraLookOffset = 0f; jumpHeight = 0f; jumpVelocity = 0f; agents.clear();
    }

    float focusX() { return driving ? cx : px; }
    float focusZ() { return driving ? cz : pz; }

    void setCameraPreset(String preset) {
        switch (preset) {
            case "street": camDistance = 5.2f; camPitch = 0.28f; break;
            case "near": camDistance = 13f; camPitch = 0.52f; break;
            case "high": camDistance = 38f; camPitch = 0.92f; break;
            case "drone": camDistance = 105f; camPitch = 1.48f; break;
            default: return;
        }
    }

    void zoom(float factor) {
        if (Float.isFinite(factor) && factor > 0f) camDistance = clamp(camDistance * factor, 3.2f, 180f);
    }

    void look(float dx, float dy) {
        camYaw -= dx * 0.006f;
        camPitch = clamp(camPitch - dy * 0.004f, 0.05f, 1.3f);
        lastLookMs = System.currentTimeMillis();
    }

    void update(float dt, World w) {
        if (paused) return;
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
            float ground = w.heightAt(px, pz);
            if (jumpRequested && jumpHeight <= 0.02f) jumpVelocity = 5.2f;
            jumpRequested = false;
            jumpVelocity -= 14f * dt;
            jumpHeight += jumpVelocity * dt;
            if (jumpHeight < 0f) { jumpHeight = 0f; jumpVelocity = 0f; }
            py = ground + jumpHeight;
        } else {
            boolean aircraft = vehicleType == ProceduralVehicleModels.HELICOPTER || vehicleType == ProceduralVehicleModels.AIRPLANE;
            if (handbrake && !aircraft) {
                cv -= Math.signum(cv) * Math.min(Math.abs(cv), 18f * dt);
            } else if (jy > 0.1f) cv += jy * (aircraft ? 24f : 14f) * dt;
            else if (jy < -0.1f) cv += jy * (cv > 0 ? (aircraft ? 30f : 24f) : 8f) * dt;
            else cv -= Math.signum(cv) * Math.min(Math.abs(cv), (aircraft ? 3f : 5f) * dt);
            cv = clamp(cv, aircraft ? -12f : -8f, aircraft ? (vehicleType == ProceduralVehicleModels.AIRPLANE ? 90f : 48f) : 32f);
            cyaw -= jx * (aircraft ? 0.85f : 1.7f) * dt * clamp(cv / 8f, -1f, 1f);
            float fx = (float) Math.sin(cyaw), fz = (float) Math.cos(cyaw);
            float nx = cx + fx * cv * dt, nz = cz + fz * cv * dt;
            if (aircraft) {
                float liftRate = vehicleType == ProceduralVehicleModels.HELICOPTER ? 10f : 5f;
                if (vehicleType == ProceduralVehicleModels.HELICOPTER || cv > 13f) altitude += liftInput * liftRate * dt;
                if (vehicleType == ProceduralVehicleModels.AIRPLANE && cv < 9f && altitude > 0f) altitude -= 3.5f * dt;
                altitude = clamp(altitude, 0f, vehicleType == ProceduralVehicleModels.HELICOPTER ? 260f : 700f);
                cx = nx; cz = nz;
            } else if (w.blocked(nx + fx * 2.1f, nz + fz * 2.1f, 0.9f) || w.blocked(nx - fx * 2.1f, nz - fz * 2.1f, 0.9f)
                    || w.blocked(nx, nz, 1.0f)) {
                cv = -cv * 0.25f;
            } else { cx = nx; cz = nz; }
            px = cx; pz = cz;
            float ground = w.heightAt(cx, cz);
            py += (ground + altitude - py) * Math.min(1f, dt * (aircraft ? 4f : 10f));
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
        float dist = camDistance * (driving ? 1.6f : 1f), th = driving ? 1.6f : 1.55f;
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float viewYaw = camYaw + cameraLookOffset;
        float dx = tx - (float) Math.sin(viewYaw) * dist * cp;
        float dz = tz - (float) Math.cos(viewYaw) * dist * cp;
        float dy = Math.max(py + 0.6f, py + th + dist * sp);
        float k = 1f - (float) Math.exp(-dt * 14f);
        camX += (dx - camX) * k; camY += (dy - camY) * k; camZ += (dz - camZ) * k;
        tgtX = tx; tgtY = th + py; tgtZ = tz;
    }

    void snapCamera() {
        float tx = focusX(), tz = focusZ();
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float viewYaw = camYaw + cameraLookOffset;
        camX = tx - (float) Math.sin(viewYaw) * camDistance * cp; camZ = tz - (float) Math.cos(viewYaw) * camDistance * cp; camY = py + 1.55f + camDistance * sp;
    }
}
