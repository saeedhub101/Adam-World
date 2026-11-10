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

    private static float readAxis(MotionEvent event, int primary, int fallback) {
        android.view.InputDevice device = event.getDevice();
        if (device != null && device.getMotionRange(primary, event.getSource()) != null)
            return event.getAxisValue(primary);
        return event.getAxisValue(fallback);
    }

    void setWeaponWheelView(WeaponWheelView wheel) { weaponWheelView = wheel; }
    void setWeaponWheelOpen(boolean open) { weaponWheelOpen = open; }

    private static float trigger(MotionEvent e, int axis, int fallback) {
        float v = readAxis(e, axis, fallback);
        return v < 0f ? (v + 1f) * 0.5f : Math.min(1f, v);
    }

    /** USB/Bluetooth pads: left stick + hat move, right stick looks, triggers throttle/brake. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        int source = e.getSource();
        boolean joystick = (source & InputDevice.SOURCE_CLASS_JOYSTICK)
                == InputDevice.SOURCE_CLASS_JOYSTICK;
        if (joystick && e.getAction() == MotionEvent.ACTION_MOVE) {
            float moveX = stick(e.getAxisValue(MotionEvent.AXIS_X));
            float moveY = stick(e.getAxisValue(MotionEvent.AXIS_Y));
            float lookX = stick(readAxis(e, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z));
            float lookY = stick(-readAxis(e, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ));
            float gas = trigger(e, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);
            float brake = trigger(e, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);
            float hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);

            if (weaponWheelOpen && weaponWheelView != null) {
                if (Math.abs(lookX)+Math.abs(lookY) > 0.28f) weaponWheelView.selectDirection(lookX, lookY);
                else if (Math.abs(hatX)+Math.abs(hatY) > 0.5f) weaponWheelView.selectDirection(hatX, -hatY);
            } else {
                renderer.setGamepadLook(lookX, lookY);
            }

            renderer.game.sx = moveX;
            if (renderer.game.driving) {
                renderer.game.guardRequested = false;
                renderer.game.weaponAimHeld = false;
                renderer.game.weaponFireHeld = false;
                if (renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                    renderer.game.sy = -moveY;
                    renderer.game.throttleInput = gas;
                    renderer.game.brakeRequested = brake > 0.08f;
                } else if (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                    renderer.game.sy = -moveY;
                    renderer.game.throttleInput = gas - brake;
                } else {
                    renderer.game.sy = (gas + brake > 0.08f) ? gas - brake : -moveY;
                }
            } else {
                renderer.game.sy = -moveY;
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
