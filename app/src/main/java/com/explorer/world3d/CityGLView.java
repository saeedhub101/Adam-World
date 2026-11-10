package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** سطح الرسم: السحب على الشاشة يدير الكاميرا. */
public class CityGLView extends GLSurfaceView {
    final CityRenderer renderer;
    private int lookId = -1;
    private WeaponWheelView weaponWheelView;
    private volatile boolean weaponWheelOpen;
    private float lx, ly;
    private float padHatX, padHatY;
    private long lastHatWeaponChangeMs;
    private final ScaleGestureDetector scaleDetector;

    public CityGLView(Context c, CityRenderer r) {
        super(c);
        renderer = r;
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                final float factor = 1f / detector.getScaleFactor();
                queueEvent(new Runnable() {
                    @Override public void run() { renderer.game.zoom(factor); }
                });
                return true;
            }
        });
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(r);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static float stick(float value) {
        float a = Math.abs(value);
        if (a < 0.16f) return 0f;
        return Math.signum(value) * Math.min(1f, (a - 0.16f) / 0.84f);
    }

    // Never guess that Z/RZ are camera axes: on several USB/Bluetooth pads
    // they are triggers or auxiliary axes. Only use a reported right-stick axis.
    private static float readStickAxis(MotionEvent event, int axis) {
        android.view.InputDevice device = event.getDevice();
        return device != null && device.getMotionRange(axis, event.getSource()) != null
                ? event.getAxisValue(axis) : 0f;
    }

    void setWeaponWheelView(WeaponWheelView wheel) { weaponWheelView = wheel; }
    void setWeaponWheelOpen(boolean open) { weaponWheelOpen = open; }

    private static float readAxis(MotionEvent event, int primary, int fallback) {
        InputDevice device = event.getDevice();
        if (device != null && device.getMotionRange(primary, event.getSource()) != null)
            return event.getAxisValue(primary);
        if (device != null && device.getMotionRange(fallback, event.getSource()) != null)
            return event.getAxisValue(fallback);
        return 0f;
    }

    private static float trigger(MotionEvent e, int axis, int fallback) {
        float v = readAxis(e, axis, fallback);
        return v < 0f ? (v + 1f) * 0.5f : Math.min(1f, v);
    }

    private void cycleWeapon(int direction) {
        Game g = renderer.game;
        int selected = g.equippedWeapon;
        for (int n = 0; n < WeaponSystem.NAME.length; n++) {
            selected = (selected + direction + WeaponSystem.NAME.length) % WeaponSystem.NAME.length;
            if (g.ownedWeapons[selected]) break;
        }
        g.equipWeapon(selected);
    }

    /** USB/Bluetooth pads: left stick + hat move, right stick looks, triggers throttle/brake. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        int source = e.getSource();
        boolean joystick = (source & InputDevice.SOURCE_CLASS_JOYSTICK)
                == InputDevice.SOURCE_CLASS_JOYSTICK;
        if (joystick && e.getAction() == MotionEvent.ACTION_MOVE) {
            float moveX = stick(e.getAxisValue(MotionEvent.AXIS_X));
            float moveY = stick(e.getAxisValue(MotionEvent.AXIS_Y));
            // Right stick controls camera; do not fall back to Z/RZ because
            // those axes are not consistently mapped across Android controllers.
            float lookX = stick(readStickAxis(e, MotionEvent.AXIS_RX));
            float lookY = stick(-readStickAxis(e, MotionEvent.AXIS_RY));
            float gas = trigger(e, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);
            float brake = trigger(e, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);
            float hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);

            // D-pad hats are joystick axes, not key events on many Bluetooth pads.
            // This view consumes joystick motion, so route the hat here as well.
            float nx = Math.abs(hatX) > 0.5f ? Math.signum(hatX) : 0f;
            float ny = Math.abs(hatY) > 0.5f ? Math.signum(hatY) : 0f;
            if (weaponWheelOpen && weaponWheelView != null) {
                if (Math.abs(lookX)+Math.abs(lookY) > 0.28f) weaponWheelView.selectDirection(lookX, lookY);
                else if (Math.abs(nx)+Math.abs(ny) > 0f) weaponWheelView.selectDirection(nx, -ny);
            } else {
                renderer.setGamepadLook(lookX, lookY);
                if (renderer.game.driving && renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                    renderer.game.liftInput = ny == 0f ? 0f : (ny < 0f ? 1f : -1f);
                } else {
                    renderer.game.cameraZoomInput = ny == 0f ? 0f : (ny < 0f ? 1f : -1f);
                }
                if (nx != 0f && nx != padHatX && !renderer.game.driving) {
                    long now = System.currentTimeMillis();
                    if (now - lastHatWeaponChangeMs > 180L) {
                        cycleWeapon(nx > 0f ? 1 : -1);
                        lastHatWeaponChangeMs = now;
                    }
                }
            }
            padHatX = nx;
            padHatY = ny;

            if (renderer.game.driving) {
                renderer.game.vehicleSteeringInput = moveX;
                renderer.game.sx = renderer.game.sy = 0f;
                renderer.game.guardRequested = false;
                renderer.game.weaponAimHeld = false;
                renderer.game.weaponFireHeld = false;
                if (renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                    renderer.game.vehicleForwardInput = -moveY;
                    renderer.game.throttleInput = gas;
                    renderer.game.brakeRequested = brake > 0.08f;
                } else if (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                    renderer.game.vehicleForwardInput = -moveY;
                    renderer.game.throttleInput = gas - brake;
                } else {
                    renderer.game.vehicleForwardInput = (gas + brake > 0.08f) ? gas - brake : -moveY;
                }
            } else {
                renderer.game.sx = moveX;
                renderer.game.sy = -moveY;
                renderer.game.vehicleSteeringInput = renderer.game.vehicleForwardInput = 0f;
                renderer.game.weaponAimHeld = brake > 0.08f;
                renderer.game.weaponFireHeld = gas > 0.08f;
                renderer.game.guardRequested = false;
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (scaleDetector.isInProgress()) {
            lookId = -1;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (lookId == -1) {
                    int i = e.getActionIndex();
                    lookId = e.getPointerId(i);
                    lx = e.getX(i); ly = e.getY(i);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (lookId != -1) {
                    int i = e.findPointerIndex(lookId);
                    if (i >= 0) {
                        renderer.addLook(e.getX(i) - lx, e.getY(i) - ly);
                        lx = e.getX(i); ly = e.getY(i);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL || e.getPointerId(e.getActionIndex()) == lookId) lookId = -1;
                break;
            default:
                break;
        }
        return true;
    }
}
