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
    private static final float JUMP_SPEED = 5.45f;
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
        boolean onGround = grounded && g.jumpHeight <= 0.002f;
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
            desiredSpeed = (g.sprint ? RUN_SPEED : (g.stealthMode ? WALK_SPEED * 0.58f : WALK_SPEED)) * magnitude;
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
        boolean blockedAll = blocked(world, nx, nz, stepClearance);
        if (!blockedAll) {
            g.px = nx;
            g.pz = nz;
        } else {
            if (!blocked(world, nx, g.pz, stepClearance)) g.px = nx;
            else vx = 0f;
            if (!blocked(world, g.px, nz, stepClearance)) g.pz = nz;
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
        g.swingAmp = gaitBlend * (g.sprint ? 1f : (g.stealthMode ? 0.35f : 0.78f));

        groundY = world.heightAt(g.px, g.pz);
        if (!grounded || verticalVelocity > 0f || g.jumpHeight > 0f) {
            float previousHeight = g.jumpHeight;
            g.jumpHeight += verticalVelocity * dt;
            verticalVelocity -= GRAVITY * dt;
            if (g.jumpHeight <= 0f) {
                float impact = Math.max(0f, -verticalVelocity);
                g.jumpHeight = 0f;
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
            g.jumpHeight = 0f;
        }
        landingPulse = Math.max(0f, landingPulse - dt * 3.5f);
        float targetGround = groundY + g.jumpHeight;
        // While grounded, anchor the feet directly to the sampled terrain height. A smoothed
        // ground target lags behind slopes and causes the actor to visibly sink into hills.
        if (grounded) g.py = targetGround;
        else g.py += (targetGround - g.py) * (1f - (float)Math.exp(-dt * 7f));
        g.jumpVelocity = verticalVelocity;
    }

    private static boolean blocked(Game.World world, float x, float z, float stepClearance) {
        if (world instanceof CityRenderer) {
            return ((CityRenderer)world).blockedAtHeight(x, z, BODY_RADIUS, stepClearance);
        }
        return world.blocked(x, z, BODY_RADIUS);
    }

    private static float approach(float value, float target, float amount) {
        if (value < target) return Math.min(target, value + amount);
        return Math.max(target, value - amount);
    }
}
