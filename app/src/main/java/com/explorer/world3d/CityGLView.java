package com.explorer.world3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;\nimport android.view.ScaleGestureDetector;

/** سطح الرسم: السحب على الشاشة يدير الكاميرا. */
public class CityGLView extends GLSurfaceView {
    final CityRenderer renderer;
    private int lookId = -1;
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
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
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
