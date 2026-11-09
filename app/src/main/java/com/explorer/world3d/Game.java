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
    float altitude, aircraftPitch, aircraftRoll;
    volatile float liftInput, throttleInput;
    volatile boolean brakeRequested;
    float engineThrottle, aircraftVerticalSpeed, aircraftDamage;
    boolean landingGearDown = true, aircraftOnGround = true, aircraftStall;
    volatile boolean paused, attackRequested, punchRequested, kickRequested, jumpRequested, handbrake, guardRequested, dodgeRequested;
    volatile float stamina = 100f;
    float dodgeSeconds, dodgeX, dodgeZ;
    long lastDodgeMs;
    float guardBlend, hurtFlash, playerPoseTime, playerFall;
    int playerPose;
    volatile float cameraLookOffset;
    // Hold the camera modifier and use D-pad up/down for smooth zoom.
    volatile float cameraZoomInput;
    float jumpVelocity, jumpHeight, attackSeconds;
    // Damped suspension response when wheels climb/drop over curbs and islands.
    float vehicleBumpAmplitude, vehicleBumpTime, vehicleBumpOffset, vehicleBumpRoll, vehicleBumpPitch;
    float vehicleBumpSide;
    boolean vehicleFrontStepContact, vehicleRearStepContact;
    // Smoothed independent wheel travel over kerbs/raised islands.
    float vehicleWheelFL, vehicleWheelFR, vehicleWheelRL, vehicleWheelRR;
    float vehicleWheelBodyLift, vehicleWheelBodyRoll, vehicleWheelBodyPitch;
    int attackType;
    long lastPlayerAttackMs;
    volatile boolean weaponFireHeld, weaponAimHeld, reloadRequested;
    int equippedWeapon=0, cash=2500, magazineAmmo=12;
    final boolean[] ownedWeapons={true,false,false,false,false};
    final int[] reserveAmmo={48,0,0,0,0};
    final int[] weaponMagazines={12,0,0,0,0};
    long lastWeaponShotMs;
    float weaponReloadSeconds;
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
        sx = sy = 0f; sprint = false;
        aircraftPitch = aircraftRoll = aircraftVerticalSpeed = aircraftDamage = 0f;
        engineThrottle = 0f; landingGearDown = true; aircraftOnGround = true; aircraftStall = false;
        py = 0; px = pz = pyaw = 0; cx = 4; cz = 0; cyaw = 0; cv = 0; driving = false; vehicleType = ProceduralVehicleModels.SEDAN; altitude = 0; liftInput = throttleInput = 0; brakeRequested = false; camYaw = 0; health = 100; dead = false;
        paused = attackRequested = punchRequested = kickRequested = jumpRequested = handbrake = guardRequested = dodgeRequested = false; cameraLookOffset = 0f; cameraZoomInput = 0f;
        weaponFireHeld=weaponAimHeld=reloadRequested=false;weaponReloadSeconds=0f;
        stamina = 100f; dodgeSeconds = 0f; dodgeX = dodgeZ = 0f; lastDodgeMs = 0L;
        guardBlend=hurtFlash=playerPoseTime=playerFall=0f; playerPose=0;
        jumpVelocity = jumpHeight = attackSeconds = 0f; comboStep = 0;
        vehicleBumpAmplitude = vehicleBumpTime = vehicleBumpOffset = vehicleBumpRoll = vehicleBumpPitch = vehicleBumpSide = 0f;
        vehicleFrontStepContact = vehicleRearStepContact = false;
        vehicleWheelFL = vehicleWheelFR = vehicleWheelRL = vehicleWheelRR = 0f;
        vehicleWheelBodyLift = vehicleWheelBodyRoll = vehicleWheelBodyPitch = 0f;
        lastPlayerAttackMs = lastDamageMs = deathStartedMs = 0L; agents.clear();
    }

    float focusX() { return driving ? cx : px; }
    float focusZ() { return driving ? cz : pz; }

    void zoom(float factor) {
        if (Float.isFinite(factor) && factor > 0f) camDistance = clamp(camDistance * factor, 3.2f, 180f);
    }

    void look(float dx, float dy) {
        camYaw -= dx * 0.006f;
        camPitch = clamp(camPitch - dy * 0.004f, 0.05f, 1.3f);
        lastLookMs = System.currentTimeMillis();
    }

    private void updateGroundVehicle(float dt, World w, float jx, float jy) {
        if (jy > 0.1f) cv += jy * 14f * dt;
        else if (jy < -0.1f) cv += jy * (cv > 0 ? 24f : 8f) * dt;
        else cv -= Math.signum(cv) * Math.min(Math.abs(cv), 5f * dt);
        if (handbrake) cv -= Math.signum(cv) * Math.min(Math.abs(cv), 18f * dt);
        cv = clamp(cv, -8f, 32f);
        cyaw -= jx * 1.7f * dt * clamp(cv / 8f, -1f, 1f);
        float fx = (float) Math.sin(cyaw), fz = (float) Math.cos(cyaw);
        float nx = cx + fx * cv * dt, nz = cz + fz * cv * dt;
        float vehicleProbe = vehicleType == ProceduralVehicleModels.BUS ? 3.0f
                : vehicleType == ProceduralVehicleModels.MOTORBIKE || vehicleType == ProceduralVehicleModels.BICYCLE ? 0.95f : 1.55f;
        float vehicleRadius = vehicleType == ProceduralVehicleModels.BUS ? 1.05f
                : vehicleType == ProceduralVehicleModels.MOTORBIKE || vehicleType == ProceduralVehicleModels.BICYCLE ? 0.38f : 0.72f;
        boolean blockedVehicle = (w instanceof CityRenderer)
                ? ((CityRenderer) w).blockedAtHeight(nx + fx * vehicleProbe, nz + fz * vehicleProbe, vehicleRadius, 0.58f)
                || ((CityRenderer) w).blockedAtHeight(nx - fx * vehicleProbe, nz - fz * vehicleProbe, vehicleRadius, 0.58f)
                || ((CityRenderer) w).blockedAtHeight(nx, nz, vehicleRadius, 0.58f)
                : w.blocked(nx + fx * vehicleProbe, nz + fz * vehicleProbe, vehicleRadius)
                || w.blocked(nx - fx * vehicleProbe, nz - fz * vehicleProbe, vehicleRadius)
                || w.blocked(nx, nz, vehicleRadius);
        if (blockedVehicle) cv = -cv * 0.25f;
        else { cx = nx; cz = nz; }
        if (w instanceof CityRenderer) {
            CityRenderer city = (CityRenderer) w;
            float rx = fz, rz = -fx, halfWidth = 0.91f;
            float halfLength = vehicleType == ProceduralVehicleModels.BUS ? 2.85f
                    : vehicleType == ProceduralVehicleModels.SUV ? 1.52f
                    : vehicleType == ProceduralVehicleModels.PICKUP ? 1.65f
                    : vehicleType == ProceduralVehicleModels.MOTORBIKE || vehicleType == ProceduralVehicleModels.BICYCLE ? 1.45f : 1.38f;
            float fl = city.lowObstacleHeightAt(cx + fx * halfLength - rx * halfWidth, cz + fz * halfLength - rz * halfWidth);
            float fr = city.lowObstacleHeightAt(cx + fx * halfLength + rx * halfWidth, cz + fz * halfLength + rz * halfWidth);
            float rl = city.lowObstacleHeightAt(cx - fx * halfLength - rx * halfWidth, cz - fz * halfLength - rz * halfWidth);
            float rr = city.lowObstacleHeightAt(cx - fx * halfLength + rx * halfWidth, cz - fz * halfLength + rz * halfWidth);
            float spring = Math.min(1f, dt * 18f);
            vehicleWheelFL += (fl - vehicleWheelFL) * spring; vehicleWheelFR += (fr - vehicleWheelFR) * spring;
            vehicleWheelRL += (rl - vehicleWheelRL) * spring; vehicleWheelRR += (rr - vehicleWheelRR) * spring;
            vehicleWheelBodyLift = (vehicleWheelFL + vehicleWheelFR + vehicleWheelRL + vehicleWheelRR) * 0.25f;
            float front = (vehicleWheelFL + vehicleWheelFR) * 0.5f, rear = (vehicleWheelRL + vehicleWheelRR) * 0.5f;
            float left = (vehicleWheelFL + vehicleWheelRL) * 0.5f, right = (vehicleWheelFR + vehicleWheelRR) * 0.5f;
            vehicleWheelBodyPitch = (float) Math.toDegrees(Math.atan2(front - rear, halfLength * 2f));
            vehicleWheelBodyRoll = (float) Math.toDegrees(Math.atan2(left - right, halfWidth * 2f));
            float frontStep = Math.max(fl, fr), rearStep = Math.max(rl, rr);
            boolean frontContact = frontStep > 0.015f, rearContact = rearStep > 0.015f;
            float impactSpeed = Math.min(0.16f, 0.055f + Math.abs(cv) * 0.003f);
            if (frontContact != vehicleFrontStepContact) {
                float edge = Math.max(frontStep, Math.max(vehicleWheelFL, vehicleWheelFR));
                vehicleBumpAmplitude = Math.max(vehicleBumpAmplitude, impactSpeed * Math.min(1.25f, edge / 0.18f));
                vehicleBumpTime = 0f; vehicleBumpSide = Math.signum(fl - fr);
            }
            if (rearContact != vehicleRearStepContact) {
                float edge = Math.max(rearStep, Math.max(vehicleWheelRL, vehicleWheelRR));
                vehicleBumpAmplitude = Math.max(vehicleBumpAmplitude, impactSpeed * Math.min(1.25f, edge / 0.18f));
                vehicleBumpTime = 0f; vehicleBumpSide = Math.signum(rl - rr);
            }
            vehicleFrontStepContact = frontContact; vehicleRearStepContact = rearContact;
        }
    }

    private void updateAircraft(float dt, float jx, float jy) {
        boolean helicopter = vehicleType == ProceduralVehicleModels.HELICOPTER;
        engineThrottle = clamp(engineThrottle + throttleInput * 0.48f * dt, 0f, 1f);
        float targetRoll = -clamp(jx, -1f, 1f) * (helicopter ? 11f : 27f);
        float pitchCommand = liftInput != 0f ? liftInput : (helicopter ? jy : -jy);
        float targetPitch = helicopter ? clamp(jy, -1f, 1f) * 9f : -clamp(pitchCommand, -1f, 1f) * 15f;
        aircraftPitch += (targetPitch - aircraftPitch) * Math.min(1f, dt * (helicopter ? 3.2f : 2.6f));
        aircraftRoll += (targetRoll - aircraftRoll) * Math.min(1f, dt * 3.6f);

        if (helicopter) {
            cv += jy * 13f * dt;
            cv -= Math.signum(cv) * Math.min(Math.abs(cv), 4.2f * dt);
            cv = clamp(cv, -25f, 48f);
            cyaw -= jx * 0.92f * dt;
            aircraftVerticalSpeed += (engineThrottle * 19f - 9.81f + liftInput * 10.5f
                    - aircraftVerticalSpeed * 0.72f) * dt;
            aircraftVerticalSpeed = clamp(aircraftVerticalSpeed, -13f, 13f);
            altitude += aircraftVerticalSpeed * dt;
            if (altitude <= 0f) {
                altitude = 0f;
                if (aircraftVerticalSpeed < 0f) aircraftVerticalSpeed = 0f;
                aircraftOnGround = true;
            } else aircraftOnGround = false;
            aircraftStall = false;
        } else {
            cv += engineThrottle * 20f * dt;
            float drag = aircraftOnGround ? 1.25f + cv * 0.045f : 1.8f + cv * 0.025f;
            cv -= drag * dt;
            if (brakeRequested) cv -= (aircraftOnGround ? 28f : 5.5f) * dt;
            cv = clamp(cv, 0f, 92f);
            float turnRate = aircraftOnGround ? 0.42f : 0.52f * clamp(cv / 30f, 0.25f, 1.45f);
            cyaw -= jx * turnRate * dt;
            if (aircraftOnGround) {
                if (!brakeRequested && cv >= 32f && aircraftPitch < -5.5f) {
                    aircraftOnGround = false;
                    aircraftVerticalSpeed = 1.2f;
                } else {
                    altitude = 0f;
                    aircraftVerticalSpeed = 0f;
                }
            }
            if (!aircraftOnGround) {
                float pitchLift = clamp(-aircraftPitch / 15f, -0.8f, 1.15f);
                float speedRatio = cv / 36f;
                float lift = 9.81f * speedRatio * speedRatio * (1f + 0.72f * pitchLift);
                aircraftStall = cv < 24f;
                if (aircraftStall) lift *= 0.48f;
                aircraftVerticalSpeed += (lift - 9.81f - aircraftVerticalSpeed * 0.58f) * dt;
                aircraftVerticalSpeed = clamp(aircraftVerticalSpeed, -22f, 20f);
                altitude += aircraftVerticalSpeed * dt;
                if (altitude <= 0f) {
                    float touchdownSpeed = cv;
                    altitude = 0f; aircraftVerticalSpeed = 0f; aircraftOnGround = true;
                    if (!landingGearDown || touchdownSpeed > 58f) aircraftDamage = Math.min(100f, aircraftDamage + 32f);
                    else if (touchdownSpeed > 46f) aircraftDamage = Math.min(100f, aircraftDamage + 8f);
                    cv *= landingGearDown ? 0.88f : 0.70f;
                    aircraftPitch = Math.max(0f, aircraftPitch);
                }
            }
        }
        float fx = (float) Math.sin(cyaw), fz = (float) Math.cos(cyaw);
        cx += fx * cv * dt; cz += fz * cv * dt;
        if (aircraftDamage >= 100f) {
            engineThrottle = Math.min(engineThrottle, 0.12f);
            cv *= Math.max(0f, 1f - dt * 0.35f);
        }
    }

    void update(float dt, World w) {
        if (paused) return;
        dt = clamp(dt, 0f, 0.05f);
        if (cameraZoomInput != 0f) {
            float zoomFactor = (float) Math.exp(-clamp(cameraZoomInput, -1f, 1f) * dt * 1.45f);
            camDistance = clamp(camDistance * zoomFactor, 3.2f, 180f);
        }
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
        weaponReloadSeconds=Math.max(0f,weaponReloadSeconds-dt);
        if(weaponAimHeld&&!driving)pyaw+=angDiff(pyaw,camYaw)*Math.min(1f,dt*10f);
        if(reloadRequested){reloadRequested=false;reloadWeapon();}
        if(weaponFireHeld&&!driving&&weaponReloadSeconds<=0f)fireWeapon(now);
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
            if (aircraft) updateAircraft(dt, jx, jy);
            else updateGroundVehicle(dt, w, jx, jy);
            px = cx; pz = cz;
            float ground = (w instanceof CityRenderer && !aircraft)
                    ? ((CityRenderer) w).baseHeightAt(cx, cz) + vehicleWheelBodyLift
                    : w.heightAt(cx, cz);
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
            vehicleBumpRoll = vehicleBumpAmplitude * 75f * envelope * (float) Math.sin(22f * vehicleBumpTime) * vehicleBumpSide;
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

    void fireWeapon(long now){
        int id=equippedWeapon;
        if(id<0||id>=WeaponSystem.NAME.length||now-lastWeaponShotMs<WeaponSystem.FIRE_INTERVAL_MS[id])return;
        if(magazineAmmo<=0){reloadWeapon();return;}
        lastWeaponShotMs=now;magazineAmmo--;weaponMagazines[id]=magazineAmmo;

        // Hitscan with deterministic weapon-specific dispersion and world occlusion.
        // A target behind a building/wall can no longer be damaged through geometry.
        float baseYaw=pyaw;
        float spread=weaponAimHeld?0.006f:0.022f;
        if(id==3)spread*=2.8f;       // shotgun pellet cone
        if(id==4)spread*=0.22f;      // precision rifle
        long seed=now^(id*0x9E3779B97F4A7C15L);
        seed^=seed>>>12;seed^=seed<<25;seed^=seed>>>27;
        float jitter=(((seed*2685821657736338717L)>>>40)/(float)(1L<<24)-0.5f)*2f*spread;
        float shotYaw=baseYaw+jitter;
        float fx=(float)Math.sin(shotYaw),fz=(float)Math.cos(shotYaw);
        float range=WeaponSystem.RANGE[id];
        Agent target=null;float best=range;
        for(Agent a:agents)if(!a.car&&!a.knockedOut){
            float dx=a.x-px,dz=a.z-pz,d=(float)Math.hypot(dx,dz);
            if(d<0.65f||d>best)continue;
            float facing=(dx*fx+dz*fz)/(d+0.0001f);
            float hitCone=weaponAimHeld?0.992f:0.965f;
            if(id==3)hitCone=weaponAimHeld?0.975f:0.91f;
            if(facing<=hitCone)continue;
            boolean occluded=false;
            if(w instanceof CityRenderer){
                int samples=Math.max(1,(int)(d/0.55f));
                for(int n=1;n<samples;n++){
                    float t=n/(float)samples;
                    if(((CityRenderer)w).blockedAtHeight(px+dx*t,pz+dz*t,0.16f,0.65f)){
                        occluded=true;break;
                    }
                }
            }else{
                int samples=Math.max(1,(int)(d/0.55f));
                for(int n=1;n<samples;n++){
                    float t=n/(float)samples;
                    if(w.blocked(px+dx*t,pz+dz*t,0.16f)){occluded=true;break;}
                }
            }
            if(!occluded){best=d;target=a;}
        }
        if(target!=null){
            int damage=WeaponSystem.DAMAGE[id];
            if(id==3)damage=Math.max(12,damage-(int)(best*2.0f));
            target.health=Math.max(0,target.health-damage);
            target.hostile=true;target.state=1;target.stateTime=0f;
            target.stunSeconds=id==4?0.85f:0.5f;target.hitFlash=0.28f;
            if(target.health==0){
                target.knockedOut=true;target.hostile=false;target.state=3;
                target.stateTime=0f;target.knockdownSeconds=22f;target.sp=0f;
            }
        }
    }
    void reloadWeapon(){int id=equippedWeapon;if(weaponReloadSeconds>0f||magazineAmmo>=WeaponSystem.MAGAZINE[id]||reserveAmmo[id]<=0)return;int moved=Math.min(WeaponSystem.MAGAZINE[id]-magazineAmmo,reserveAmmo[id]);reserveAmmo[id]-=moved;magazineAmmo+=moved;weaponMagazines[id]=magazineAmmo;weaponReloadSeconds=WeaponSystem.RELOAD_SECONDS[id];weaponFireHeld=false;}
    boolean equipWeapon(int id){if(id<0||id>=ownedWeapons.length||!ownedWeapons[id])return false;weaponMagazines[equippedWeapon]=magazineAmmo;equippedWeapon=id;magazineAmmo=Math.min(WeaponSystem.MAGAZINE[id],Math.max(0,weaponMagazines[id]));weaponFireHeld=false;weaponReloadSeconds=0f;return true;}
    boolean purchaseWeapon(int id){if(id<0||id>=ownedWeapons.length||ownedWeapons[id]||cash<WeaponSystem.PRICE[id])return false;cash-=WeaponSystem.PRICE[id];ownedWeapons[id]=true;reserveAmmo[id]=WeaponSystem.START_AMMO[id];weaponMagazines[id]=WeaponSystem.MAGAZINE[id];weaponMagazines[equippedWeapon]=magazineAmmo;equippedWeapon=id;magazineAmmo=weaponMagazines[id];return true;}
    void cycleWeapon(){for(int n=1;n<=ownedWeapons.length;n++){int id=(equippedWeapon+n)%ownedWeapons.length;if(ownedWeapons[id]){equipWeapon(id);return;}}}
    boolean buyAmmo(){int id=equippedWeapon;int cost=WeaponSystem.AMMO_PACK_PRICE[id];if(cash<cost)return false;cash-=cost;reserveAmmo[id]+=WeaponSystem.AMMO_PACK[id];return true;}

    private void updateCamera(float dt) {
        float tx = focusX(), tz = focusZ();
        boolean aircraft = driving && (vehicleType == ProceduralVehicleModels.HELICOPTER || vehicleType == ProceduralVehicleModels.AIRPLANE);
        float dist = camDistance * (driving ? (aircraft ? 2.15f : 1.6f) : 1f), th = driving ? (aircraft ? 3.0f : 1.6f) : 1.55f;
        if (aircraft && altitude > 80f) dist = Math.max(dist, camDistance * 2.8f);
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
