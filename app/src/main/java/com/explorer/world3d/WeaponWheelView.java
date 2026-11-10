package com.explorer.world3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Hold-to-open radial weapon selector shared by touch and gamepad controls. */
final class WeaponWheelView extends View {
    interface Listener { void onSelected(int weaponId); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private Game game;
    private Listener listener;
    private int selected;
    private final float density;

    WeaponWheelView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setVisibility(GONE);
    }

    void setGame(Game value) { game = value; if (game != null) selected = game.equippedWeapon; invalidate(); }
    void setListener(Listener value) { listener = value; }
    int selectedWeapon() { return selected; }
    void showWheel() { if (game != null) selected = game.equippedWeapon; setVisibility(VISIBLE); invalidate(); }
    void hideWheel() { setVisibility(GONE); }
    boolean isOpen() { return getVisibility() == VISIBLE; }

    void moveSelection(int delta) {
        if (game == null) return;
        for (int n = 0; n < WeaponSystem.NAME.length; n++) {
            selected = (selected + delta + WeaponSystem.NAME.length) % WeaponSystem.NAME.length;
            if (game.ownedWeapons[selected]) break;
        }
        invalidate();
    }

    /** Select the radial sector from a normalized right-stick direction. */
    void selectDirection(float x, float y) {
        if (game == null || Math.hypot(x, y) < 0.32) return;
        float cx = getWidth() * .5f, cy = getHeight() * .5f;
        float radius = Math.min(getWidth(), getHeight()) * .42f;
        selected = selectionAt(cx + x * radius, cy + y * radius);
        invalidate();
    }

    private int selectionAt(float x, float y) {
        float cx = getWidth() * .5f, cy = getHeight() * .5f;
        double a = Math.atan2(x - cx, cy - y);
        if (a < 0) a += Math.PI * 2.0;
        int slot = ((int)Math.floor((a + Math.PI / WeaponSystem.NAME.length) / (2.0 * Math.PI / WeaponSystem.NAME.length))) % WeaponSystem.NAME.length;
        if (game == null || game.ownedWeapons[slot]) return slot;
        for (int n = 1; n < WeaponSystem.NAME.length; n++) {
            int next = (slot + n) % WeaponSystem.NAME.length;
            if (game.ownedWeapons[next]) return next;
        }
        return game.equippedWeapon;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (game == null) return;
        float cx = getWidth() * .5f, cy = getHeight() * .5f;
        float radius = Math.min(getWidth(), getHeight()) * .43f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xc9000000);
        canvas.drawCircle(cx, cy, radius + 12f * density, paint);
        arc.set(cx-radius, cy-radius, cx+radius, cy+radius);
        float sweep = 360f / WeaponSystem.NAME.length;
        for (int i = 0; i < WeaponSystem.NAME.length; i++) {
            boolean owned = game.ownedWeapons[i];
            paint.setColor(i == selected ? 0xffd7a83c : owned ? 0xe52d3b49 : 0xe51b2028);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawArc(arc, -90f + i*sweep + 2f, sweep - 4f, true, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f*density);
            paint.setColor(0xffc5cbd2);
            canvas.drawArc(arc, -90f + i*sweep + 2f, sweep - 4f, true, paint);
            double angle = Math.toRadians(-90f + (i+.5f)*sweep);
            float tx = cx + (float)Math.cos(angle)*radius*.68f;
            float ty = cy + (float)Math.sin(angle)*radius*.68f;
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(13f*density);
            paint.setFakeBoldText(i == selected);
            paint.setColor(owned ? Color.WHITE : 0xff9aa1aa);
            String name = WeaponSystem.NAME[i];
            canvas.drawText(name, tx, ty, paint);
            paint.setTextSize(10f*density);
            String ammo = owned ? game.weaponMagazines[i] + " / " + game.reserveAmmo[i] : "مغلق";
            canvas.drawText(ammo, tx, ty + 17f*density, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setFakeBoldText(true);
        paint.setTextSize(12f*density);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawCircle(cx, cy, radius*.28f, paint);
        paint.setColor(0xff1b2634);
        canvas.drawText("WEAPON", cx, cy + 4f*density, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            selected = selectionAt(event.getX(), event.getY());
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            selected = selectionAt(event.getX(), event.getY());
            if (listener != null && game.ownedWeapons[selected]) listener.onSelected(selected);
            hideWheel();
            return true;
        }
        return true;
    }
}
