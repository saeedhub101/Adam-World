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
        int health = 100;
        boolean hostile, knockedOut;
        long lastAttackMs;
        float stunSeconds, knockdownSeconds, stateTime, hitFlash;
        int state; // 0 patrol, 1 hostile, 2 flee, 3 down, 4 recovery
        float cr, cg, cb;
    }

    float py, px, pz, pyaw, cx, cz, cyaw, cv, phase, swingAmp;
    boolean driving;
    volatile int health = 100;
    volatile boolean dead;
    long lastDamageMs, deathStartedMs;
    int vehicleType = ProceduralVehicleModels.SEDAN;
    float altitude;
    volatile float liftInput;
    volatile boolean paused, attackRequested, punchRequested, kickRequested, jumpRequested, handbrake, guardRequested, dodgeRequested;
    volatile float stamina = 100f;
    float dodgeSeconds, dodgeX, dodgeZ;
    long lastDodgeMs;
    float guardBlend, hurtFlash, playerPoseTime, playerFall;
    int playerPose;
    volatile float cameraLookOffset;
    float jumpVelocity, jumpHeight, attackSeconds;
    // Damped suspension response when wheels climb/drop over curbs and islands.
    float vehicleBumpAmplitude, vehicleBumpTime, vehicleBumpOffset, vehicleBumpRoll, vehicleBumpPitch;
    float vehicleBumpSide;
    boolean vehicleFrontStepContact, vehicleRearStepContact;
    int attackType;
    long lastPlayerAttackMs;
    int comboStep;
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
        py = 0; px = pz = pyaw = 0; cx = 4; cz = 0; cyaw = 0; cv = 0; driving = false; vehicleType = ProceduralVehicleModels.SEDAN; altitude = 0; liftInput = 0; camYaw = 0; health = 100; dead = false;
        paused = attackRequested = punchRequested = kickRequested = jumpRequested = handbrake = guardRequested = dodgeRequested = false; cameraLookOffset = 0f;
        stamina = 100f; dodgeSeconds = 0f; dodgeX = dodgeZ = 0f; lastDodgeMs = 0L;
        guardBlend=hurtFlash=playerPoseTime=playerFall=0f; playerPose=0;
        jumpVelocity = jumpHeight = attackSeconds = 0f; comboStep = 0;
        vehicleBumpAmplitude = vehicleBumpTime = vehicleBumpOffset = vehicleBumpRoll = vehicleBumpPitch = vehicleBumpSide = 0f;
        vehicleFrontStepContact = vehicleRearStepContact = false;
        lastPlayerAttackMs = lastDamageMs = deathStartedMs = 0L; agents.clear();
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
        dt = clamp(dt, 0f, 0.05f);
        long now = System.currentTimeMillis();
        if (dead) {
            sx = sy = 0f;
            if (now - deathStartedMs >= 4500L) { dead = false; health = 50; px = cx; pz = cz; cv = 0f; altitude = 0f; playerPose=4; playerPoseTime=0f; playerFall=1f; }
            updateCamera(dt);
            return;
        }
        if (health > 0 && health < 100 && now - lastDamageMs >= 5000L)
            health = Math.min(100, health + Math.max(1, (int)(dt * 12f)));
        attackSeconds = Math.max(0f, attackSeconds - dt);
        dodgeSeconds = Math.max(0f, dodgeSeconds - dt);
        hurtFlash=Math.max(0f,hurtFlash-dt);
        guardBlend += ((guardRequested && !driving && stamina>0f ? 1f:0f)-guardBlend)*Math.min(1f,dt*12f);
        if(playerPose==1){playerPoseTime+=dt;if(playerPoseTime>.24f){playerPose=0;playerPoseTime=0f;}}
        else if(playerPose==2){playerPoseTime+=dt;playerFall=Math.min(1f,playerPoseTime/.25f);if(playerFall>=1f){playerPose=3;playerPoseTime=0f;}}
        else if(playerPose==3){playerPoseTime+=dt;if(playerPoseTime>.8f){playerPose=4;playerPoseTime=0f;}}
        else if(playerPose==4){playerPoseTime+=dt;playerFall=Math.max(0f,1f-playerPoseTime/.55f);if(playerPoseTime>=.55f){playerPose=0;playerPoseTime=0f;}}
        if (!guardRequested && dodgeSeconds <= 0f) stamina = Math.min(100f, stamina + 18f * dt);
        if (dodgeRequested) {
            dodgeRequested = false;
            if (!driving && !dead && stamina >= 24f && now - lastDodgeMs >= 650L) {
                lastDodgeMs = now; stamina -= 24f; dodgeSeconds = 0.34f;
                float side = sx < -0.15f ? -1f : sx > 0.15f ? 1f : 0f;
                float forward = sy < -0.15f ? -1f : 1f;
                float fx = (float)Math.sin(camYaw), fz = (float)Math.cos(camYaw);
                float rx = -(float)Math.cos(camYaw), rz = (float)Math.sin(camYaw);
                float dx = fx * forward + rx * side, dz = fz * forward + rz * side;
                float len = Math.max(0.001f, (float)Math.hypot(dx,dz));
                dodgeX = dx / len; dodgeZ = dz / len;
            }
        }
        if (guardRequested && !driving) stamina = Math.max(0f, stamina - 12f * dt);
        if (stamina <= 0f) guardRequested = false;
        if (jumpRequested && !driving && jumpHeight <= 0.001f && !dead && playerPose==0) jumpVelocity = 5.1f;
        jumpRequested = false;
        if (jumpHeight > 0f || jumpVelocity > 0f) {
            jumpHeight += jumpVelocity * dt;
            jumpVelocity -= 13.8f * dt;
            if (jumpHeight <= 0f) { jumpHeight = 0f; jumpVelocity = 0f; }
        }
        boolean punch = punchRequested || attackRequested;
        boolean kick = kickRequested;
        punchRequested = kickRequested = attackRequested = false;
        if ((punch || kick) && !driving && !dead && playerPose == 0) {
            long cooldown = kick ? 620L : 280L;
            if (now - lastPlayerAttackMs >= cooldown) {
                lastPlayerAttackMs = now;
                attackType = kick ? 2 : 1;
                comboStep = punch ? (comboStep + 1) % 3 : 0;
                attackSeconds = kick ? 0.58f : 0.34f;
                float range = kick ? 2.65f : 1.72f;
                float damage = kick ? 38f : (comboStep == 2 ? 26f : 19f);
                float fx = (float)Math.sin(pyaw), fz = (float)Math.cos(pyaw);
                Agent target = null; float best = range;
                for (Agent a : agents) if (!a.car && !a.knockedOut) {
                    float dx=a.x-px, dz=a.z-pz, d=(float)Math.hypot(dx,dz);
                    float facing=(dx*fx+dz*fz)/(d+0.0001f);
                    if (d < best && facing > (kick ? -0.05f : 0.18f)) { best=d; target=a; }
                }
                if (target != null) {
                    target.health = Math.max(0, target.health-(int)damage);
                    target.hostile = true; target.state=1; target.stateTime=0f;
                    target.stunSeconds = kick ? 0.62f : 0.34f; target.hitFlash=.24f;
                    if (target.health == 0) { target.knockedOut = true; target.hostile = false; target.state=3; target.stateTime=0f; target.knockdownSeconds = 18f; target.sp = 0f; }
                }
            }
        }
        float jx = sx, jy = sy;
        if(playerPose==2||playerPose==3||playerPose==4){jx=0f;jy=0f;}
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
                if (dodgeSeconds > 0f) { dx = dodgeX; dz = dodgeZ; speed = 12.5f; }
                float nx = px + dx * speed * dt, nz = pz + dz * speed * dt;
                // Low curbs and traffic islands are step-up surfaces, not walls.
                // Keep tall building footprints solid while allowing normal walking
                // across street/sidewalk edges even without a ramp mesh.
                float stepClearance = Math.max(0.58f, jumpHeight - 0.18f);
                boolean blockedAhead = (w instanceof CityRenderer) ? ((CityRenderer) w).blockedAtHeight(nx, nz, 0.45f, stepClearance) : w.blocked(nx, nz, 0.45f);
                boolean blockedX = (w instanceof CityRenderer) ? ((CityRenderer) w).blockedAtHeight(nx, pz, 0.45f, stepClearance) : w.blocked(nx, pz, 0.45f);
                boolean blockedZ = (w instanceof CityRenderer) ? ((CityRenderer) w).blockedAtHeight(px, nz, 0.45f, stepClearance) : w.blocked(px, nz, 0.45f);
                if (!blockedAhead) { px = nx; pz = nz; }
                else if (!blockedX) px = nx;
                else if (!blockedZ) pz = nz;
                pyaw += angDiff(pyaw, (float) Math.atan2(dx, dz)) * Math.min(1f, dt * 12f);
                phase += speed * dt * 1.7f;
                swingAmp = 0.8f * Math.min(1f, speed / 4f);
            } else swingAmp = 0;
            py += (w.heightAt(px, pz) - py) * Math.min(1f, dt * 10f);
        } else {
            boolean aircraft = vehicleType == ProceduralVehicleModels.HELICOPTER || vehicleType == ProceduralVehicleModels.AIRPLANE;
            if (jy > 0.1f) cv += jy * (aircraft ? 24f : 14f) * dt;
            else if (jy < -0.1f) cv += jy * (cv > 0 ? (aircraft ? 30f : 24f) : 8f) * dt;
            else cv -= Math.signum(cv) * Math.min(Math.abs(cv), (aircraft ? 3f : 5f) * dt);
            if (handbrake && !aircraft) cv -= Math.signum(cv) * Math.min(Math.abs(cv), 18f * dt);
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
            } else {
                // Match the collision probes to the actual vehicle footprint. The
                // previous 2.1 m front/rear probes made narrow sidewalks feel like walls.
                float vehicleProbe = vehicleType == ProceduralVehicleModels.BUS ? 3.0f
                        : vehicleType == ProceduralVehicleModels.MOTORBIKE || vehicleType == ProceduralVehicleModels.BICYCLE ? 0.95f : 1.55f;
                float vehicleRadius = vehicleType == ProceduralVehicleModels.BUS ? 1.05f
                        : vehicleType == ProceduralVehicleModels.MOTORBIKE || vehicleType == ProceduralVehicleModels.BICYCLE ? 0.38f : 0.72f;
                float stepClearance = 0.58f;
                boolean blockedVehicle = (w instanceof CityRenderer)
                        ? ((CityRenderer) w).blockedAtHeight(nx + fx * vehicleProbe, nz + fz * vehicleProbe, vehicleRadius, stepClearance)
                        || ((CityRenderer) w).blockedAtHeight(nx - fx * vehicleProbe, nz - fz * vehicleProbe, vehicleRadius, stepClearance)
                        || ((CityRenderer) w).blockedAtHeight(nx, nz, vehicleRadius, stepClearance)
                        : w.blocked(nx + fx * vehicleProbe, nz + fz * vehicleProbe, vehicleRadius)
                        || w.blocked(nx - fx * vehicleProbe, nz - fz * vehicleProbe, vehicleRadius)
                        || w.blocked(nx, nz, vehicleRadius);
                if (blockedVehicle) cv = -cv * 0.25f;
                else { cx = nx; cz = nz; }

                // Sample the four wheel contact patches. A front/rear axle entering
                // a raised curb or island triggers a damped body jolt; the rear axle
                // produces its own delayed response as it climbs and drops.
                if (w instanceof CityRenderer) {
                    CityRenderer city = (CityRenderer) w;
                    float rx = -fz, rz = fx;
                    float halfWidth = vehicleType == ProceduralVehicleModels.BUS ? 1.05f : 0.78f;
                    float frontStep = Math.max(
                            city.lowObstacleHeightAt(cx + fx * vehicleProbe + rx * halfWidth, cz + fz * vehicleProbe + rz * halfWidth),
                            city.lowObstacleHeightAt(cx + fx * vehicleProbe - rx * halfWidth, cz + fz * vehicleProbe - rz * halfWidth));
                    float rearStep = Math.max(
                            city.lowObstacleHeightAt(cx - fx * vehicleProbe + rx * halfWidth, cz - fz * vehicleProbe + rz * halfWidth),
                            city.lowObstacleHeightAt(cx - fx * vehicleProbe - rx * halfWidth, cz - fz * vehicleProbe - rz * halfWidth));
                    boolean frontContact = frontStep > 0.015f;
                    boolean rearContact = rearStep > 0.015f;
                    float impactSpeed = Math.min(0.22f, 0.085f + Math.abs(cv) * 0.0045f);
                    if (frontContact && !vehicleFrontStepContact) {
                        vehicleBumpAmplitude = Math.max(vehicleBumpAmplitude, impactSpeed * Math.max(0.45f, frontStep / 0.12f));
                        vehicleBumpTime = 0f;
                        vehicleBumpSide = frontStep;
                    }
                    if (rearContact && !vehicleRearStepContact) {
                        vehicleBumpAmplitude = Math.max(vehicleBumpAmplitude, impactSpeed * Math.max(0.45f, rearStep / 0.12f));
                        vehicleBumpTime = 0f;
                        vehicleBumpSide = rearStep;
                    }
                    vehicleFrontStepContact = frontContact;
                    vehicleRearStepContact = rearContact;
                }
            }
            }
            px = cx; pz = cz;
            float ground = w.heightAt(cx, cz);
            py += (ground + altitude - py) * Math.min(1f, dt * (aircraft ? 4f : 10f));
            if (System.currentTimeMillis() - lastLookMs > 2500) {
                camYaw += angDiff(camYaw, cyaw) * Math.min(1f, dt * 2.5f);
            }
        }

        // Spring-damper body motion: quick vertical rebound, subtle pitch and roll,
        // then a smooth return to neutral after each wheel crosses a raised edge.
        if (vehicleBumpAmplitude > 0.001f) {
            vehicleBumpTime += dt;
            float envelope = (float) Math.exp(-4.2f * vehicleBumpTime);
            float wave = (float) Math.sin(24f * vehicleBumpTime);
            vehicleBumpOffset = vehicleBumpAmplitude * envelope * wave;
            vehicleBumpPitch = vehicleBumpAmplitude * 95f * envelope * (float) Math.sin(19f * vehicleBumpTime);
            vehicleBumpRoll = vehicleBumpAmplitude * 75f * envelope * (float) Math.sin(22f * vehicleBumpTime + vehicleBumpSide);
            if (vehicleBumpTime > 1.15f) {
                vehicleBumpAmplitude = vehicleBumpOffset = vehicleBumpRoll = vehicleBumpPitch = 0f;
            }
        } else {
            vehicleBumpOffset = vehicleBumpRoll = vehicleBumpPitch = 0f;
            vehicleBumpTime = 0f;
        }

        for (int i = agents.size() - 1; i >= 0; i--) {
            Agent a = agents.get(i);
            if (a.tile.dead) { agents.remove(i); continue; }
            if (a.car) {
                a.s += a.dir * a.sp * dt;
                if (a.s > a.path.total) { a.s = a.path.total; a.dir = -1; }
                else if (a.s < 0f) { a.s = 0f; a.dir = 1; }
                a.path.posAt(a.s, a.off * a.dir, tmp);
                a.x = tmp[0]; a.z = tmp[1];
                a.yaw = (float)Math.atan2(tmp[2] * a.dir, tmp[3] * a.dir);
            }
            if (!a.car) {
                a.stateTime+=dt; a.hitFlash=Math.max(0f,a.hitFlash-dt);
                if(a.knockedOut){a.state=3;a.knockdownSeconds=Math.max(0f,a.knockdownSeconds-dt);if(a.knockdownSeconds<=0f){a.knockedOut=false;a.health=55;a.hostile=false;a.state=4;a.stateTime=0f;a.sp=.7f;a.stunSeconds=1f;}}
                else if(a.hostile && !driving){a.state=1;
                    a.stunSeconds = Math.max(0f, a.stunSeconds-dt);
                    float dx=px-a.x, dz=pz-a.z, d=(float)Math.hypot(dx,dz);
                    if (d < 9f && d > 0.001f && a.stunSeconds <= 0f) {
                        float step=Math.min(2.0f*dt, Math.max(0f,d-0.82f));
                        float nx=a.x+dx/d*step, nz=a.z+dz/d*step;
                        if (!w.blocked(nx,nz,0.38f)) { a.x=nx; a.z=nz; }
                        a.yaw=(float)Math.atan2(dx,dz);
                    }
                    if (d < 1.15f && now-a.lastAttackMs>1350L && now-lastDamageMs>700L) {
                        a.lastAttackMs=now;
                        if (dodgeSeconds <= 0f) {
                            boolean blocked=guardRequested && stamina>=8f;
                            health=Math.max(0,health-(blocked?2:9)); lastDamageMs=now; hurtFlash=.3f;
                            if(blocked) stamina=Math.max(0f,stamina-16f); else if(health>0){playerPose=1;playerPoseTime=0f;}
                        }
                        if (health==0) { dead=true; deathStartedMs=now; sx=sy=0f; playerPose=2; playerPoseTime=0f; }
                    }
                    a.ph += dt*5.2f;
                } else {
                    float threatX=0f,threatZ=0f; boolean threatened=false;
                    for(Agent other:agents) if(other!=a&&!other.car&&other.hostile&&!other.knockedOut){float tx=a.x-other.x,tz=a.z-other.z;if(tx*tx+tz*tz<36f){threatened=true;threatX+=tx;threatZ+=tz;}}
                    if(threatened){a.state=2;float len=(float)Math.hypot(threatX,threatZ);if(len>.001f){float nx=a.x+threatX/len*2.8f*dt,nz=a.z+threatZ/len*2.8f*dt;if(!w.blocked(nx,nz,.36f)){a.x=nx;a.z=nz;}a.yaw=(float)Math.atan2(threatX,threatZ);}a.ph+=dt*6f;}
                    else {if(a.state==2){a.state=0;a.stateTime=0f;}a.s+=a.dir*a.sp*dt;if(a.s>a.path.total){a.s=a.path.total;a.dir=-1;}else if(a.s<0){a.s=0;a.dir=1;}a.path.posAt(a.s,a.off,tmp);a.x=tmp[0];a.z=tmp[1];a.yaw=(float)Math.atan2(tmp[2]*a.dir,tmp[3]*a.dir);a.ph+=a.sp*dt*2.2f;if(a.state==4&&a.stateTime>1f){a.state=0;a.stateTime=0f;}}
                }
            }
        }

        updateCamera(dt);
    }

    private void updateCamera(float dt) {
        float tx = focusX(), tz = focusZ();
        float dist = camDistance * (driving ? 1.6f : 1f), th = driving ? 1.6f : 1.55f;
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float effectiveYaw = camYaw + cameraLookOffset;
        float dx = tx - (float) Math.sin(effectiveYaw) * dist * cp;
        float dz = tz - (float) Math.cos(effectiveYaw) * dist * cp;
        float dy = Math.max(py + 0.6f, py + th + dist * sp);
        float k = 1f - (float) Math.exp(-dt * 14f);
        camX += (dx - camX) * k; camY += (dy - camY) * k; camZ += (dz - camZ) * k;
        tgtX = tx; tgtY = th + py; tgtZ = tz;
    }

    void snapCamera() {
        float tx = focusX(), tz = focusZ();
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        camX = tx - (float) Math.sin(camYaw) * camDistance * cp; camZ = tz - (float) Math.cos(camYaw) * camDistance * cp; camY = py + 1.55f + camDistance * sp;
    }
}
