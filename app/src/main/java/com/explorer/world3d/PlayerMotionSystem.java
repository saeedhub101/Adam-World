package com.explorer.world3d;

/**
 * Character motor shared by touch and gamepad input.
 *
 * Separates acceleration, collision resolution, grounded/air movement and
 * buffered jumping from the high-level game state. All state is frame-rate
 * independent and bounded; it does not allocate in the update loop.
 */
final class PlayerMotionSystem {
    private static final float WALK_SPEED = 4.6f;
    private static final float RUN_SPEED = 8.5f;
    private static final float DODGE_SPEED = 12.5f;
    private static final float GROUND_ACCEL = 24f;
    private static final float AIR_ACCEL = 7.5f;
    private static final float GROUND_BRAKE = 28f;
    private static final float AIR_DRAG = 0.65f;
    private static final float GRAVITY = 15.5f;
    private static final float JUMP_SPEED = 7.4f; // Enough vertical clearance to vault onto a standard car roof.
    private static final float COYOTE_SECONDS = 0.12f;
    private static final float JUMP_BUFFER_SECONDS = 0.15f;
    private static final float BODY_RADIUS = 0.38f;

    private float vx, vz, verticalVelocity;
    private float coyoteTimer, jumpBufferTimer, gaitBlend, landingPulse;
    private boolean grounded = true;

    void reset() {
        vx = vz = verticalVelocity = 0f;
        coyoteTimer = COYOTE_SECONDS;
        jumpBufferTimer = gaitBlend = landingPulse = 0f;
        grounded = true;
    }

    void update(Game g, float dt, Game.World world) {
        if (dt <= 0f) return;
        if (g.jumpRequested) jumpBufferTimer = JUMP_BUFFER_SECONDS;
        else jumpBufferTimer = Math.max(0f, jumpBufferTimer - dt);
        g.jumpRequested = false;

        float groundY = world.heightAt(g.px, g.pz);
        float supportHeight = vehicleSupportHeight(g, world, g.px, g.pz, groundY);
        boolean onGround = grounded && Math.abs(g.jumpHeight - supportHeight) <= 0.035f;
        if (onGround) coyoteTimer = COYOTE_SECONDS;
        else coyoteTimer = Math.max(0f, coyoteTimer - dt);

        if (jumpBufferTimer > 0f && coyoteTimer > 0f && !g.dead
                && g.playerPose == 0 && g.dodgeSeconds <= 0f) {
            verticalVelocity = JUMP_SPEED;
            grounded = false;
            coyoteTimer = 0f;
            jumpBufferTimer = 0f;
        }

        float ix = g.sx, iy = g.sy;
        float magnitude = Math.min(1f, (float)Math.hypot(ix, iy));
        float desiredX = 0f, desiredZ = 0f, desiredSpeed = 0f;
        if (magnitude > 0.06f && g.playerPose != 2 && g.playerPose != 3 && g.playerPose != 4) {
            float fx = (float)Math.sin(g.camYaw), fz = (float)Math.cos(g.camYaw);
            float rx = -(float)Math.cos(g.camYaw), rz = (float)Math.sin(g.camYaw);
            desiredX = fx * iy + rx * ix;
            desiredZ = fz * iy + rz * ix;
            float len = Math.max(0.0001f, (float)Math.hypot(desiredX, desiredZ));
            desiredX /= len;
            desiredZ /= len;
            float locomotionSpeed = g.sprint ? RUN_SPEED : (g.jogging ? WALK_SPEED * 1.32f : WALK_SPEED);
            desiredSpeed = (g.stealthMode ? locomotionSpeed * 0.58f : locomotionSpeed) * magnitude;
        }
        if (g.dodgeSeconds > 0f) {
            desiredX = g.dodgeX;
            desiredZ = g.dodgeZ;
            desiredSpeed = DODGE_SPEED;
        }

        float targetVX = desiredX * desiredSpeed;
        float targetVZ = desiredZ * desiredSpeed;
        boolean hasInput = desiredSpeed > 0.05f;
        float accel = grounded ? (hasInput ? GROUND_ACCEL : GROUND_BRAKE) : AIR_ACCEL;
        float maxDelta = accel * dt;
        vx = approach(vx, targetVX, maxDelta);
        vz = approach(vz, targetVZ, maxDelta);
        if (!grounded) {
            vx *= Math.max(0f, 1f - AIR_DRAG * dt);
            vz *= Math.max(0f, 1f - AIR_DRAG * dt);
        }

        float nx = g.px + vx * dt;
        float nz = g.pz + vz * dt;
        float stepClearance = Math.max(0.58f, g.jumpHeight - 0.18f);
        boolean blockedAll = blocked(g, world, nx, nz, stepClearance);
        if (!blockedAll) {
            g.px = nx;
            g.pz = nz;
        } else {
            if (!blocked(g, world, nx, g.pz, stepClearance)) g.px = nx;
            else vx = 0f;
            if (!blocked(g, world, g.px, nz, stepClearance)) g.pz = nz;
            else vz = 0f;
        }

        float horizontalSpeed = (float)Math.hypot(vx, vz);
        if (horizontalSpeed > 0.12f && g.dodgeSeconds <= 0f) {
            float targetYaw = (float)Math.atan2(vx, vz);
            g.pyaw += Game.angDiff(g.pyaw, targetYaw) * Math.min(1f, dt * 11.5f);
        }
        g.phase += horizontalSpeed * dt * 1.7f;
        float targetGait = Math.min(1f, horizontalSpeed / RUN_SPEED);
        gaitBlend += (targetGait - gaitBlend) * Math.min(1f, dt * (hasInput ? 10f : 14f));
        g.swingAmp = gaitBlend * (g.sprint ? 1f : (g.stealthMode ? 0.35f : (g.jogging ? 0.9f : 0.78f)));

        groundY = world.heightAt(g.px, g.pz);
        supportHeight = vehicleSupportHeight(g, world, g.px, g.pz, groundY);
        if (!grounded || verticalVelocity > 0f || g.jumpHeight > supportHeight + 0.002f) {
            float previousHeight = g.jumpHeight;
            g.jumpHeight += verticalVelocity * dt;
            verticalVelocity -= GRAVITY * dt;
            if (verticalVelocity <= 0f && g.jumpHeight <= supportHeight) {
                float impact = Math.max(0f, -verticalVelocity);
                g.jumpHeight = supportHeight;
                verticalVelocity = 0f;
                grounded = true;
                landingPulse = Math.min(1f, impact / 8f);
                if (impact > 3.2f) {
                    g.playerPose = 1;
                    g.playerPoseTime = 0f;
                }
            } else {
                grounded = false;
            }
        } else {
            grounded = true;
            g.jumpHeight = supportHeight;
        }
        landingPulse = Math.max(0f, landingPulse - dt * 3.5f);
        float targetGround = groundY + g.jumpHeight;
        // While grounded, anchor the feet directly to the sampled terrain height. A smoothed
        // ground target lags behind slopes and causes the actor to visibly sink into hills.
        if (grounded) g.py = targetGround;
        else g.py += (targetGround - g.py) * (1f - (float)Math.exp(-dt * 7f));
        g.jumpVelocity = verticalVelocity;
    }

    private static boolean blocked(Game g, Game.World world, float x, float z, float stepClearance) {
        boolean worldBlocked = world instanceof CityRenderer
                ? ((CityRenderer)world).blockedAtHeight(x, z, BODY_RADIUS, stepClearance)
                : world.blocked(x, z, BODY_RADIUS);
        return worldBlocked || vehicleSideBlocked(g, world, x, z, g.jumpHeight);
    }

    /*
     * Dynamic vehicle proxies are deliberately independent of render meshes.
     * The same oriented footprint blocks the player's capsule and exposes a
     * walkable roof plane when the player lands from above.
     */
    private static float vehicleSupportHeight(Game g, Game.World world, float x, float z, float playerGround) {
        float support = 0f;
        if (!g.driving) {
            support = Math.max(support, roofHeightAt(x, z, g.cx, g.cz, g.cyaw,
                    g.vehicleType, world, playerGround, g.jumpHeight));
            for (int i = 0; i < g.agents.size(); i++) {
                Game.Agent a = g.agents.get(i);
                if (a.car) support = Math.max(support, roofHeightAt(x, z, a.x, a.z, a.yaw,
                        a.vehicleType, world, playerGround, g.jumpHeight));
            }
        }
        return support;
    }

    private static boolean vehicleSideBlocked(Game g, Game.World world, float x, float z, float feetHeight) {
        if (g.driving) return false;
        if (insideVehicle(x, z, g.cx, g.cz, g.cyaw, g.vehicleType, BODY_RADIUS)) {
            float roof = world.heightAt(g.cx, g.cz) - world.heightAt(x, z) + roofHeight(g.vehicleType);
            if (feetHeight < roof - 0.06f) return true;
        }
        for (int i = 0; i < g.agents.size(); i++) {
            Game.Agent a = g.agents.get(i);
            if (a.car && insideVehicle(x, z, a.x, a.z, a.yaw, a.vehicleType, BODY_RADIUS)) {
                float roof = world.heightAt(a.x, a.z) - world.heightAt(x, z) + roofHeight(a.vehicleType);
                if (feetHeight < roof - 0.06f) return true;
            }
        }
        return false;
    }

    private static float roofHeightAt(float x, float z, float vx, float vz, float yaw,
                                      int type, Game.World world, float playerGround,
                                      float playerFeetHeight) {
        // A roof is a one-way landing platform: it supports the character only
        // while descending from above, never lifts a player walking beside/under it.
        if (!insideVehicle(x, z, vx, vz, yaw, type, 0.08f)) return 0f;
        float vehicleGround = world.heightAt(vx, vz);
        float roof = Math.max(0f, vehicleGround - playerGround + roofHeight(type));
        return playerFeetHeight >= roof - 0.15f ? roof : 0f;
    }

    private static boolean insideVehicle(float x, float z, float vx, float vz,
                                         float yaw, int type, float expand) {
        float dx = x - vx, dz = z - vz;
        float forward = dx * (float)Math.sin(yaw) + dz * (float)Math.cos(yaw);
        float side = dx * (float)Math.cos(yaw) - dz * (float)Math.sin(yaw);
        float halfLength = type == ProceduralVehicleModels.BUS ? 4.05f
                : type == ProceduralVehicleModels.TANK ? 2.45f
                : type == ProceduralVehicleModels.MOTORBIKE || type == ProceduralVehicleModels.BICYCLE ? 1.48f : 2.42f;
        float halfWidth = type == ProceduralVehicleModels.BUS ? 1.38f
                : type == ProceduralVehicleModels.TANK ? 1.48f
                : type == ProceduralVehicleModels.MOTORBIKE || type == ProceduralVehicleModels.BICYCLE ? 0.48f : 1.02f;
        return Math.abs(forward) <= halfLength + expand && Math.abs(side) <= halfWidth + expand;
    }

    private static float roofHeight(int type) {
        if (type == ProceduralVehicleModels.BUS) return 2.90f;
        if (type == ProceduralVehicleModels.TANK) return 2.00f;
        if (type == ProceduralVehicleModels.MOTORBIKE || type == ProceduralVehicleModels.BICYCLE) return 1.18f;
        if (type == ProceduralVehicleModels.SUV || type == ProceduralVehicleModels.PICKUP) return 1.72f;
        return 1.55f;
    }

    private static float approach(float value, float target, float amount) {
        if (value < target) return Math.min(target, value + amount);
        return Math.max(target, value - amount);
    }
}
