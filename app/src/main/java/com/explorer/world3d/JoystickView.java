package com.explorer.world3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Fixed-anchor Battle Royale movement stick; movement is independent from combat touch pointers. */
public final class JoystickView extends View {
    public interface Listener { void onMove(float x, float y); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private int pointerId = -1;
    private float knobX, knobY, density, radius;
    private boolean pressed;

    public JoystickView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        radius = 67f * density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setClickable(true);
        setFocusable(false);
    }

    public void setListener(Listener value) { listener = value; }

    private void update(float x, float y) {
        float dx = x - getWidth() * .5f;
        float dy = y - getHeight() * .5f;
        float max = radius * .70f;
        float length = (float)Math.hypot(dx, dy);
        if (length > max && length > 0f) { dx *= max / length; dy *= max / length; }
        knobX = dx;
        knobY = dy;
        if (listener != null) listener.onMove(dx / max, -dy / max);
        invalidate();
    }

    private void release() {
        pointerId = -1;
        pressed = false;
        knobX = knobY = 0f;
        if (listener != null) listener.onMove(0f, 0f);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * .5f, cy = getHeight() * .5f;
        float outer = Math.min(radius, Math.min(getWidth(), getHeight()) * .47f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0x503B4658 : 0x38202B3B);
        canvas.drawCircle(cx, cy, outer, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * density);
        paint.setColor(pressed ? 0xDDE8F0FA : 0xAFC7D4E3);
        canvas.drawCircle(cx, cy, outer - 1.5f * density, paint);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(cx, cy, outer * .58f, paint);
        float kx = cx + knobX, ky = cy + knobY;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xDDEAF1FA);
        canvas.drawCircle(kx, ky, outer * .31f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(0xFFF8FBFF);
        canvas.drawCircle(kx, ky, outer * .31f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (pointerId == -1) {
                pointerId = event.getPointerId(index);
                pressed = true;
                update(event.getX(index), event.getY(index));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && pointerId != -1) {
            int i = event.findPointerIndex(pointerId);
            if (i >= 0) update(event.getX(i), event.getY(i));
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == pointerId) {
            release();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pointerId != -1) release();
            return true;
        }
        return pointerId != -1;
    }

    @Override protected void onDetachedFromWindow() {
        if (pointerId != -1) release();
        super.onDetachedFromWindow();
    }
}