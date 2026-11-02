package com.explorer.world3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** عصا تحكم افتراضية. */
public class JoystickView extends View {
    public interface Listener { void onMove(float x, float y); }

    private Listener listener;
    private int pid = -1;
    private float kx, ky;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);

    public JoystickView(Context c) {
        super(c);
        bg.setColor(0x24ffffff);
        ring.setColor(0x59ffffff);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        knob.setColor(0xb3ffffff);
    }

    public void setListener(Listener l) { listener = l; }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = getWidth() / 2f - 3f;
        c.drawCircle(cx, cy, r, bg);
        c.drawCircle(cx, cy, r, ring);
        c.drawCircle(cx + kx, cy + ky, r * 0.28f, knob);
    }

    private void update(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dx = x - cx, dy = y - cy, max = getWidth() / 2f * 0.7f;
        float m = (float) Math.hypot(dx, dy);
        if (m > max) { dx *= max / m; dy *= max / m; }
        kx = dx; ky = dy;
        if (listener != null) listener.onMove(dx / max, -dy / max);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pid == -1) {
                    int i = e.getActionIndex();
                    pid = e.getPointerId(i);
                    update(e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pid != -1) {
                    int i = e.findPointerIndex(pid);
                    if (i >= 0) update(e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL || e.getPointerId(e.getActionIndex()) == pid) {
                    pid = -1; kx = 0; ky = 0;
                    if (listener != null) listener.onMove(0f, 0f);
                    invalidate();
                }
                break;
            default:
                break;
        }
        return true;
    }
}
