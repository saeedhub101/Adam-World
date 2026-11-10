package com.explorer.world3d;

import java.util.ArrayList;

/** حالة اللعبة: اللاعب، السيارة، الكاميرا، والمشاة/السيارات الآلية. */
final class Game {
    interface World { boolean blocked(float x, float z, float r); float heightAt(float x, float z); }

    static final class Agent {
        boolean car;
        boolean parked, trafficStopped, driverPresent = true;
        float cruiseSpeed;
        long stopStartedMs, lastHornMs;
        int hornCount;
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
        float knockbackX, knockbackZ;
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
    volatile float liftInput, throttleInput, rudderInput;
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
    long lastPlayerAttackMs, lastComboMs;
    volatile boolean weaponFireHeld, weaponAimHeld, reloadRequested;
    int equippedWeapon=0, cash=2500, magazineAmmo=12;
    final boolean[] ownedWeapons={true,false,false,false,false,false,false};
    final int[] reserveAmmo={48,0,0,0,0,0,0};
    final int[] weaponMagazines={12,0,0,0,0,0,0};
    long lastWeaponShotMs;
    float weaponReloadSeconds, weaponReloadDuration, weaponRecoil, muzzleFlashSeconds;
    int weaponReloadPendingAmmo, weaponReloadWeaponId=-1;
    long explosionEventCount;
    int comboStep;
    float camYaw, camPitch = 0.38f, camDistance = 5.2f, camX, camY = 3f, camZ, tgtX, tgtY = 1.5f, tgtZ;
    long lastLookMs;
    volatile float sx, sy;
    // Independent driving channel: walking input never mutates vehicle steering/throttle.
    volatile float vehicleSteeringInput, vehicleForwardInput;
    volatile long trafficHornEvents;
    float vehicleY;
    volatile boolean sprint, jogging, stealthMode;
    volatile long lastSprintTapMs;
    volatile boolean cinematicCamera;
    volatile int cameraMode = 1;
    final ArrayList<Agent> agents = new ArrayList<>();
    final ArrayList<ProjectileSystem.Rocket> rockets = new ArrayList<>();
    final ArrayList<ProjectileSystem.Blast> blasts = new ArrayList<>();
    private final float[] tmp = new float[4];
    private final PlayerMotionSystem playerMotion = new PlayerMotionSystem();

    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    static float angDiff(float a, float b) {
        float d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    void reset() {
        sx = sy = vehicleSteeringInput = vehicleForwardInput = 0f; vehicleY = 0f; trafficHornEvents = 0L; sprint = false; jogging = false; stealthMode = false; lastSprintTapMs = 0L;
        cinematicCamera = false; cameraMode = 1;
        playerMotion.reset();
        aircraftPitch = aircraftRoll = aircraftVerticalSpeed = aircraftDamage = 0f;
        engineThrottle = 0f; landingGearDown = true; aircraftOnGround = true; aircraftStall = false;
        py = 0; px = pz = pyaw = 0; cx = 4; cz = 0; cyaw = 0; cv = 0; driving = false; vehicleType = ProceduralVehicleModels.SEDAN; altitude = 0; liftInput = throttleInput = rudderInput = 0; brakeRequested = false; camYaw = 0; health = 100; dead = false;
        paused = attackRequested = punchRequested = kickRequested = jumpRequested = handbrake = guardRequested = dodgeRequested = false; cameraLookOffset = 0f; cameraZoomInput = 0f;
        weaponFireHeld=weaponAimHeld=reloadRequested=false;weaponReloadSeconds=weaponReloadDuration=weaponRecoil=muzzleFlashSeconds=0f;weaponReloadPendingAmmo=0;weaponReloadWeaponId=-1;
        stamina = 100f; dodgeSeconds = 0f; dodgeX = dodgeZ = 0f; lastDodgeMs = 0L;
        guardBlend=hurtFlash=playerPoseTime=playerFall=0f; playerPose=0;
        jumpVelocity = jumpHeight = attackSeconds = 0f; comboStep = 0;
        vehicleBumpAmplitude = vehicleBumpTime = vehicleBumpOffset = vehicleBumpRoll = vehicleBumpPitch = vehicleBumpSide = 0f;
        vehicleFrontStepContact = vehicleRearStepContact = false;
        vehicleWheelFL = vehicleWheelFR = vehicleWheelRL = vehicleWheelRR = 0f;
        vehicleWheelBodyLift = vehicleWheelBodyRoll = vehicleWheelBodyPitch = 0f;
        lastPlayerAttackMs = lastComboMs = lastDamageMs = deathStartedMs = 0L; explosionEventCount=0L; agents.clear(); rockets.clear(); blasts.clear();
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
        float maxSpeed = vehicleType == ProceduralVehicleModels.BUS ? 34f
                : vehicleType == ProceduralVehicleModels.TANK ? 24f
                : vehicleType == ProceduralVehicleModels.BICYCLE ? 16f
                : vehicleType == ProceduralVehicleModels.MOTORBIKE ? 52f
                : vehicleType == ProceduralVehicleModels.PICKUP ? 46f
                : vehicleType == ProceduralVehicleModels.SUV ? 50f : 58f;
        if (jy > 0.1f) cv += jy * 18f * dt;
        else if (jy < -0.1f) cv += jy * (cv > 0 ? 34f : 10f) * dt;
        else cv -= Math.signum(cv) * Math.min(Math.abs(cv), 2.6f * dt);
        if (handbrake) cv -= Math.signum(cv) * Math.min(Math.abs(cv), 28f * dt);
        cv = clamp(cv, -15f, maxSpeed);
        float steeringRate = vehicleType == ProceduralVehicleModels.BUS ? 0.72f
                : vehicleType == ProceduralVehicleModels.TANK ? 0.62f : 1.12f;
        cyaw -= jx * steeringRate * dt * clamp(cv / 9f, -1f, 1f);
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
            cyaw -= rudderInput * 0.72f * dt;
            // GTA-style collective: RT climbs, LT descends, releasing both stabilizes hover.
            aircraftVerticalSpeed += (liftInput * (8f + engineThrottle * 3f)
                    - aircraftVerticalSpeed * 1.8f) * dt;
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
            cyaw -= rudderInput * turnRate * 0.72f * dt;
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
                    boolean fatalLanding = (!landingGearDown && touchdownSpeed > 58f) || touchdownSpeed > 78f;
                    if (fatalLanding && !dead) {
                        aircraftDamage = 100f;
                        health = 0; dead = true; deathStartedMs = System.currentTimeMillis();
                        playerPose = 2; playerPoseTime = 0f; playerFall = 0f;
                        sx = sy = 0f;
                    }
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
        if (sprint && lastSprintTapMs > 0L && now - lastSprintTapMs > 430L) sprint = false;
        if (dead) {
            sx = sy = 0f;
            if (now - deathStartedMs >= 4500L) { dead = false; health = 50; px = cx; pz = cz; cv = 0f; altitude = 0f; aircraftDamage = 0f; engineThrottle = 0f; aircraftOnGround = true; playerPose=4; playerPoseTime=0f; playerFall=1f; }
            updateCamera(dt);
            return;
        }
        if (health > 0 && health < 100 && now - lastDamageMs >= 5000L)
            health = Math.min(100, health + Math.max(1, (int)(dt * 12f)));
        attackSeconds = Math.max(0f, attackSeconds - dt);
        if(weaponReloadSeconds>0f){
            weaponReloadSeconds=Math.max(0f,weaponReloadSeconds-dt);
            if(weaponReloadSeconds<=0f){
                int reloadId=weaponReloadWeaponId;
                if(reloadId>=0&&reloadId==equippedWeapon){
                    magazineAmmo=Math.min(WeaponSystem.MAGAZINE[reloadId],magazineAmmo+weaponReloadPendingAmmo);
                    weaponMagazines[reloadId]=magazineAmmo;
                }
                weaponReloadDuration=0f;weaponReloadPendingAmmo=0;weaponReloadWeaponId=-1;
            }
        }
        weaponRecoil=Math.max(0f,weaponRecoil-dt*5.8f);
        muzzleFlashSeconds=Math.max(0f,muzzleFlashSeconds-dt*8.5f);
        if(weaponAimHeld&&!driving)pyaw+=angDiff(pyaw,camYaw)*Math.min(1f,dt*10f);
        if(reloadRequested){reloadRequested=false;reloadWeapon();}
        if(weaponFireHeld&&!driving&&weaponReloadSeconds<=0f)fireWeapon(now,w);
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
        // Character locomotion owns jump buffering, coyote time and vertical integration.
        // Consume the input here only through PlayerMotionSystem so touch/gamepad share one path.
        boolean punch = punchRequested || attackRequested;
        boolean kick = kickRequested;
        punchRequested = kickRequested = attackRequested = false;
        if ((punch || kick) && !driving && !dead && playerPose == 0) {
            if (punch && now - lastComboMs > 900L) comboStep = 0;
            long cooldown = kick ? 620L : 245L;
            if (now - lastPlayerAttackMs >= cooldown) {
                lastPlayerAttackMs = now; lastComboMs = now;
                if (kick) { attackType = 3; comboStep = 0; }
                else { comboStep = comboStep % 3 + 1; attackType = comboStep == 1 ? 1 : 2; }
                attackSeconds = kick ? 0.62f : 0.42f;
                float range = kick ? 2.45f : 1.82f;
                int damage = kick ? 38 : comboStep == 3 ? 32 : comboStep == 2 ? 23 : 17;
                float fx = (float)Math.sin(pyaw), fz = (float)Math.cos(pyaw);
                Agent target = null; float best = range;
                for (Agent a : agents) if (!a.car && !a.knockedOut) {
                    float dx=a.x-px, dz=a.z-pz, d=(float)Math.hypot(dx,dz);
                    float facing=(dx*fx+dz*fz)/(d+0.0001f);
                    if (d < best && facing > (kick ? -0.08f : 0.28f)) { best=d; target=a; }
                }
                if (target != null) {
                    float dx=target.x-px, dz=target.z-pz, len=Math.max(0.001f,(float)Math.hypot(dx,dz));
                    target.health = Math.max(0, target.health-damage);
                    target.hostile = true; target.state=1; target.stateTime=0f;
                    target.stunSeconds = kick ? 0.78f : comboStep == 3 ? 0.58f : 0.38f;
                    target.hitFlash=.30f;
                    float impulse = kick ? 5.8f : comboStep == 3 ? 4.2f : 2.5f;
                    target.knockbackX += dx/len*impulse; target.knockbackZ += dz/len*impulse;
                    if (target.health == 0) {
                        target.knockedOut = true; target.hostile = false; target.state=3;
                        target.stateTime=0f; target.knockdownSeconds = 22f; target.sp = 0f;
                    }
                }
            }
        }
        // Character and vehicle consume different input channels.
        float jx = driving ? vehicleSteeringInput : sx;
        float jy = driving ? vehicleForwardInput : sy;
        if (driving) {
            // Drop character-only actions while seated so they cannot fire after exiting.
            jumpRequested = punchRequested = kickRequested = attackRequested = dodgeRequested = false;
            guardRequested = false; weaponAimHeld = weaponFireHeld = reloadRequested = false;
            sx = sy = 0f;
        }
        if(playerPose==2||playerPose==3||playerPose==4){jx=0f;jy=0f;}
        float mag = Math.min(1f, (float) Math.hypot(jx, jy));
        if (!driving) {
            playerMotion.update(this, dt, w);
            // The parked car keeps its own ground height; character jump/height never moves it.
            vehicleY = (w instanceof CityRenderer)
                    ? ((CityRenderer) w).baseHeightAt(cx, cz) : w.heightAt(cx, cz);
        } else {
            boolean aircraft = vehicleType == ProceduralVehicleModels.HELICOPTER || vehicleType == ProceduralVehicleModels.AIRPLANE;
            if (aircraft) updateAircraft(dt, jx, jy);
            else updateGroundVehicle(dt, w, jx, jy);
            float ground = (w instanceof CityRenderer && !aircraft)
                    ? ((CityRenderer) w).baseHeightAt(cx, cz) + vehicleWheelBodyLift
                    : w.heightAt(cx, cz);
            // Vehicle elevation is independent from the on-foot character transform.
            vehicleY = ground + altitude;
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
                a.hitFlash = Math.max(0f, a.hitFlash - dt);
                if (a.knockedOut || a.parked) {
                    a.sp = 0f;
                } else {
                    if (!driving && !a.trafficStopped) {
                        float dx = px - a.x, dz = pz - a.z;
                        float forward = (float)Math.sin(a.yaw), forwardZ = (float)Math.cos(a.yaw);
                        float longitudinal = dx * forward + dz * forwardZ;
                        float lateral = Math.abs(dx * forwardZ - dz * forward);
                        if (longitudinal > 0.55f && longitudinal < 7.2f && lateral < 1.75f) {
                            a.trafficStopped = true; a.stopStartedMs = now; a.hornCount = 1; a.lastHornMs = now;
                            a.sp = 0f; trafficHornEvents++;
                        }
                    }
                    if (a.trafficStopped) {
                        a.sp = 0f;
                        float distanceToPlayer = (float)Math.hypot(px - a.x, pz - a.z);
                        if (now - a.stopStartedMs > 850L && a.hornCount < 2) {
                            a.hornCount = 2; a.lastHornMs = now; trafficHornEvents++;
                        }
                        if (driving || distanceToPlayer > 8.5f) {
                            a.trafficStopped = false; a.hornCount = 0; a.sp = a.cruiseSpeed;
                        }
                    } else {
                        a.s += a.dir * a.sp * dt;
                        if (a.s > a.path.total) { a.s = a.path.total; a.dir = -1; }
                        else if (a.s < 0f) { a.s = 0f; a.dir = 1; }
                        a.path.posAt(a.s, a.off * a.dir, tmp);
                        a.x = tmp[0]; a.z = tmp[1];
                        a.yaw = (float)Math.atan2(tmp[2] * a.dir, tmp[3] * a.dir);
                    }
                }
            }
            if (!a.car) {
                a.stateTime+=dt; a.hitFlash=Math.max(0f,a.hitFlash-dt);
                if (Math.abs(a.knockbackX)+Math.abs(a.knockbackZ)>0.015f) {
                    float kx=a.x+a.knockbackX*dt, kz=a.z+a.knockbackZ*dt;
                    if (!w.blocked(kx,kz,0.36f)) { a.x=kx; a.z=kz; }
                    else { a.knockbackX*=0.22f; a.knockbackZ*=0.22f; }
                    float drag=(float)Math.exp(-7.5f*dt);
                    a.knockbackX*=drag; a.knockbackZ*=drag;
                } else { a.knockbackX=0f; a.knockbackZ=0f; }
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

        ProjectileSystem.update(this,dt,w);
        updateCamera(dt);
    }

    void fireWeapon(long now, World w){
        int id=equippedWeapon;
        if(id<0||id>=WeaponSystem.NAME.length||now-lastWeaponShotMs<WeaponSystem.FIRE_INTERVAL_MS[id])return;
        if(magazineAmmo<=0){reloadWeapon();return;}
        lastWeaponShotMs=now;magazineAmmo--;weaponMagazines[id]=magazineAmmo;
        weaponRecoil=1f; muzzleFlashSeconds=1f;
        if(id>=5){ ProjectileSystem.launch(this,id); return; }

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
        for(Agent a:agents)if(!a.knockedOut){
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
            float hitDx=target.x-px, hitDz=target.z-pz, hitLen=Math.max(0.001f,(float)Math.hypot(hitDx,hitDz));
            target.knockbackX += hitDx/hitLen*(id==3?2.2f:id==4?3.4f:1.25f);
            target.knockbackZ += hitDz/hitLen*(id==3?2.2f:id==4?3.4f:1.25f);
            target.hitFlash=0.28f;
            if(target.car){
                // Vehicles are valid ballistic targets too; repeated hits disable them.
                if(target.health==0){target.knockedOut=true;target.sp=0f;target.state=3;target.stateTime=0f;}
            }else{
                target.hostile=true;target.state=1;target.stateTime=0f;
                target.stunSeconds=id==4?0.85f:0.5f;
                if(target.health==0){
                    target.knockedOut=true;target.hostile=false;target.state=3;
                    target.stateTime=0f;target.knockdownSeconds=22f;target.sp=0f;
                }
            }
        }
    }
    void reloadWeapon(){
        int id=equippedWeapon;
        if(weaponReloadSeconds>0f||id<0||id>=ownedWeapons.length||magazineAmmo>=WeaponSystem.MAGAZINE[id]||reserveAmmo[id]<=0)return;
        int moved=Math.min(WeaponSystem.MAGAZINE[id]-magazineAmmo,reserveAmmo[id]);
        reserveAmmo[id]-=moved;weaponReloadPendingAmmo=moved;weaponReloadWeaponId=id;
        weaponReloadDuration=weaponReloadSeconds=WeaponSystem.RELOAD_SECONDS[id];weaponFireHeld=false;
    }
    boolean equipWeapon(int id){
        if(id<0||id>=ownedWeapons.length||!ownedWeapons[id])return false;
        if(weaponReloadWeaponId>=0&&weaponReloadPendingAmmo>0)reserveAmmo[weaponReloadWeaponId]+=weaponReloadPendingAmmo;
        weaponReloadSeconds=weaponReloadDuration=0f;weaponReloadPendingAmmo=0;weaponReloadWeaponId=-1;
        weaponMagazines[equippedWeapon]=magazineAmmo;equippedWeapon=id;
        magazineAmmo=Math.min(WeaponSystem.MAGAZINE[id],Math.max(0,weaponMagazines[id]));weaponFireHeld=false;return true;
    }
    boolean purchaseWeapon(int id){if(id<0||id>=ownedWeapons.length||ownedWeapons[id]||cash<WeaponSystem.PRICE[id])return false;cash-=WeaponSystem.PRICE[id];ownedWeapons[id]=true;reserveAmmo[id]=WeaponSystem.START_AMMO[id];weaponMagazines[id]=WeaponSystem.MAGAZINE[id];weaponMagazines[equippedWeapon]=magazineAmmo;equippedWeapon=id;magazineAmmo=weaponMagazines[id];return true;}
    void cycleWeapon(){for(int n=1;n<=ownedWeapons.length;n++){int id=(equippedWeapon+n)%ownedWeapons.length;if(ownedWeapons[id]){equipWeapon(id);return;}}}
    boolean buyAmmo(){int id=equippedWeapon;int cost=WeaponSystem.AMMO_PACK_PRICE[id];if(cash<cost)return false;cash-=cost;reserveAmmo[id]+=WeaponSystem.AMMO_PACK[id];return true;}

    private void updateCamera(float dt) {
        float tx = focusX(), tz = focusZ();
        float focusY = driving ? vehicleY : py;
        boolean aircraft = driving && (vehicleType == ProceduralVehicleModels.HELICOPTER || vehicleType == ProceduralVehicleModels.AIRPLANE);
        float modeScale = cameraMode == 0 ? 0.72f : cameraMode == 2 ? 1.55f : 1f;
        float dist = camDistance * modeScale * (driving ? (aircraft ? 2.15f : 1.6f) : 1f), th = driving ? (aircraft ? 3.0f : 1.6f) : 1.55f;
        if (aircraft && altitude > 80f) dist = Math.max(dist, camDistance * modeScale * 2.8f);
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        float cinematicYaw = cinematicCamera ? (float)Math.sin(System.currentTimeMillis() * 0.00022f) * 0.48f : 0f;
        float effectiveYaw = camYaw + cameraLookOffset + cinematicYaw;
        float dx = tx - (float) Math.sin(effectiveYaw) * dist * cp;
        float dz = tz - (float) Math.cos(effectiveYaw) * dist * cp;
        float dy = Math.max(focusY + 0.6f, focusY + th + dist * sp);
        float k = 1f - (float) Math.exp(-dt * 14f);
        camX += (dx - camX) * k; camY += (dy - camY) * k; camZ += (dz - camZ) * k;
        tgtX = tx; tgtY = th + focusY; tgtZ = tz;
    }

    void snapCamera() {
        float tx = focusX(), tz = focusZ();
        float focusY = driving ? vehicleY : py;
        float cp = (float) Math.cos(camPitch), sp = (float) Math.sin(camPitch);
        camX = tx - (float) Math.sin(camYaw) * camDistance * cp; camZ = tz - (float) Math.cos(camYaw) * camDistance * cp; camY = focusY + 1.55f + camDistance * sp;
    }
}
