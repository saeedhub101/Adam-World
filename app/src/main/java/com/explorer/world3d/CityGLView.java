package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** سطح الرسم: السحب على الشاشة يدير الكاميرا. */
public class CityGLView extends GLSurfaceView {
    final CityRenderer renderer;
    private int lookId = -1;
    private WeaponWheelView weaponWheelView;
    private volatile boolean weaponWheelOpen;
    private float lx, ly;
    private float analogMoveX, analogMoveY, analogLookX, analogLookY;
    private float hatMoveX, hatMoveY, padGas, padBrake;
    private boolean digitalMoveUp, digitalMoveDown, digitalMoveLeft, digitalMoveRight;
    private boolean digitalLookUp, digitalLookDown, digitalLookLeft, digitalLookRight;
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

    void clearDigitalInputs() {
        digitalMoveUp = digitalMoveDown = digitalMoveLeft = digitalMoveRight = false;
        digitalLookUp = digitalLookDown = digitalLookLeft = digitalLookRight = false;
        hatMoveX = hatMoveY = analogMoveX = analogMoveY = analogLookX = analogLookY = 0f;
        padGas = padBrake = 0f;
        applyControllerState();
    }

    private static float clampInput(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    /** D-pad is the digital twin of the left analogue stick. */
    void setDigitalMoveDirection(int keyCode, boolean pressed) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) digitalMoveUp = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) digitalMoveDown = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) digitalMoveLeft = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) digitalMoveRight = pressed;
        applyControllerState();
    }

    /** A/B/X provide digital camera directions; Y is reserved for vehicle interaction. */
    void setDigitalLookDirection(int keyCode, boolean pressed) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) digitalLookDown = pressed;
        else if (keyCode == KeyEvent.KEYCODE_BUTTON_X) digitalLookLeft = pressed;
        else if (keyCode == KeyEvent.KEYCODE_BUTTON_B) digitalLookRight = pressed;
        applyControllerState();
    }

    private void applyControllerState() {
        float moveX = clampInput(analogMoveX
                + (digitalMoveRight ? 1f : 0f) - (digitalMoveLeft ? 1f : 0f) + hatMoveX);
        float moveForward = clampInput(-analogMoveY
                + (digitalMoveUp ? 1f : 0f) - (digitalMoveDown ? 1f : 0f) - hatMoveY);
        float lookX = clampInput(analogLookX
                + (digitalLookRight ? 1f : 0f) - (digitalLookLeft ? 1f : 0f));
        float lookY = clampInput(analogLookY
                + (digitalLookUp ? 1f : 0f) - (digitalLookDown ? 1f : 0f));

        if (weaponWheelOpen && weaponWheelView != null) {
            if (Math.abs(lookX) + Math.abs(lookY) > 0.12f)
                weaponWheelView.selectDirection(lookX, lookY);
        } else {
            renderer.setGamepadLook(lookX, lookY);
        }

        Game game = renderer.game;
        if (game.driving) {
            game.vehicleSteeringInput = moveX;
            game.sx = game.sy = 0f;
            game.guardRequested = false;
            game.weaponAimHeld = false;
            game.weaponFireHeld = false;
            if (game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                game.vehicleForwardInput = moveForward;
                game.throttleInput = padGas;
                game.brakeRequested = padBrake > 0.08f;
            } else if (game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                game.vehicleForwardInput = moveForward;
                game.throttleInput = padGas - padBrake;
            } else {
                game.vehicleForwardInput = (padGas + padBrake > 0.08f)
                        ? padGas - padBrake : moveForward;
            }
        } else {
            game.sx = moveX;
            game.sy = moveForward;
            game.vehicleSteeringInput = game.vehicleForwardInput = 0f;
            game.weaponAimHeld = padBrake > 0.08f;
            game.weaponFireHeld = padGas > 0.08f;
            game.guardRequested = false;
        }
    }

    /** One input path: left stick + D-pad move; right stick + Y/X/A/B look. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        int source = e.getSource();
        boolean joystick = (source & InputDevice.SOURCE_CLASS_JOYSTICK)
                == InputDevice.SOURCE_CLASS_JOYSTICK;
        if (joystick && e.getAction() == MotionEvent.ACTION_MOVE) {
            analogMoveX = stick(readAxis(e, MotionEvent.AXIS_X, MotionEvent.AXIS_X));
            analogMoveY = stick(readAxis(e, MotionEvent.AXIS_Y, MotionEvent.AXIS_Y));
            analogLookX = stick(readStickAxis(e, MotionEvent.AXIS_RX));
            analogLookY = stick(-readStickAxis(e, MotionEvent.AXIS_RY));
            padGas = trigger(e, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);
            padBrake = trigger(e, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);

            float hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            hatMoveX = Math.abs(hatX) > 0.5f ? Math.signum(hatX) : 0f;
            hatMoveY = Math.abs(hatY) > 0.5f ? Math.signum(hatY) : 0f;
            applyControllerState();
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
