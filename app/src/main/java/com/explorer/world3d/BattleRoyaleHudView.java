package com.explorer.world3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Purpose-built multi-touch Battle Royale HUD.
 * The minimap and movement joystick remain owned by MainActivity and are not replaced.
 */
final class BattleRoyaleHudView extends View {
    interface Listener {
        void jump();
        void aim(boolean down);
        void fire(boolean down);
        void crouch(boolean down);
        void sprint(boolean down);
        void reload();
        void interact();
        void melee();
        void swapWeapon();
        void heal();
        void shield();
        void vehicle();
        void inventory();
    }

    private enum Action { FIRE, AIM, JUMP, CROUCH, SPRINT, RELOAD, INTERACT, MELEE, SWAP, HEAL, SHIELD, VEHICLE, INVENTORY }
    private static final class Control {
        final Action action; final float x, y, r; final String glyph, label;
        Control(Action a,float x,float y,float r,String glyph,String label){this.action=a;this.x=x;this.y=y;this.r=r;this.glyph=glyph;this.label=label;}
    }

    private final Paint p = new Paint(3);
    private final float density;
    private final int touchSlop;
    private Listener listener;
    private final int[] pointerActions = new int[16];
    private final boolean[] pointerUsed = new boolean[16];
    private boolean fireHeld, aimHeld, crouchHeld, sprintHeld;
    private float downX, downY;
    private Control[] controls;

    BattleRoyaleHudView(Context context, Listener listener) {
        super(context);
        this.listener=listener;
        density=getResources().getDisplayMetrics().density;
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
        setClickable(true);
        setFocusable(false);
    }
    void setListener(Listener value){listener=value;}
    private float d(float v){return v*density;}
    private float cx(Control c){return c.x*getWidth();}
    private float cy(Control c){return c.y*getHeight();}
    private void buildControls(){
        controls=new Control[]{
            new Control(Action.SWAP,.875f,.205f,d(23),"⇄","تبديل"),
            new Control(Action.HEAL,.665f,.205f,d(22),"+","علاج"),
            new Control(Action.SHIELD,.745f,.205f,d(22),"◇","درع"),
            new Control(Action.AIM,.735f,.485f,d(31),"⌖","تصويب"),
            new Control(Action.FIRE,.885f,.635f,d(48),"●","إطلاق"),
            new Control(Action.JUMP,.885f,.405f,d(31),"↑","قفز"),
            new Control(Action.CROUCH,.735f,.705f,d(28),"↓","انحناء"),
            new Control(Action.SPRINT,.625f,.835f,d(27),"»","ركض"),
            new Control(Action.RELOAD,.885f,.835f,d(25),"↻","تلقيم"),
            new Control(Action.INTERACT,.745f,.865f,d(25),"✋","تفاعل"),
            new Control(Action.MELEE,.595f,.665f,d(25),"✦","ضرب"),
            new Control(Action.VEHICLE,.965f,.49f,d(22),"🚘","مركبة"),
            new Control(Action.INVENTORY,.535f,.205f,d(22),"▤","حقيبة")
        };
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){buildControls();}
    private int hit(float x,float y){
        if(controls==null)buildControls();
        for(int i=controls.length-1;i>=0;i--){
            Control c=controls[i]; float dx=x-cx(c),dy=y-cy(c);
            if(dx*dx+dy*dy<=c.r*c.r*1.65f)return i;
        }
        return -1;
    }
    private void invokeDown(Action a){
        if(listener==null)return;
        switch(a){
            case FIRE: fireHeld=true; listener.fire(true); break;
            case AIM: aimHeld=true; listener.aim(true); break;
            case CROUCH: crouchHeld=true; listener.crouch(true); break;
            case SPRINT: sprintHeld=true; listener.sprint(true); break;
            case JUMP: listener.jump(); break;
            case RELOAD: listener.reload(); break;
            case INTERACT: listener.interact(); break;
            case MELEE: listener.melee(); break;
            case SWAP: listener.swapWeapon(); break;
            case HEAL: listener.heal(); break;
            case SHIELD: listener.shield(); break;
            case VEHICLE: listener.vehicle(); break;
            case INVENTORY: listener.inventory(); break;
        }
        invalidate();
    }
    private void invokeUp(Action a){
        if(listener==null)return;
        switch(a){
            case FIRE: fireHeld=false; listener.fire(false); break;
            case AIM: aimHeld=false; listener.aim(false); break;
            case CROUCH: crouchHeld=false; listener.crouch(false); break;
            case SPRINT: sprintHeld=false; listener.sprint(false); break;
            default: break;
        }
        invalidate();
    }
    private int slotFor(int pointerId){return pointerId>=0&&pointerId<pointerUsed.length?pointerId:-1;}
    private void releaseAll(){
        if(fireHeld)invokeUp(Action.FIRE);
        if(aimHeld)invokeUp(Action.AIM);
        if(crouchHeld)invokeUp(Action.CROUCH);
        if(sprintHeld)invokeUp(Action.SPRINT);
        for(int i=0;i<pointerUsed.length;i++)pointerUsed[i]=false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked(), index=e.getActionIndex(), id=e.getPointerId(index);
        if(action==MotionEvent.ACTION_CANCEL){releaseAll();return true;}
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int slot=slotFor(id); if(slot<0)return true;
            float x=e.getX(index),y=e.getY(index); int hit=hit(x,y);
            if(hit<0)return false;
            pointerUsed[slot]=true; pointerActions[slot]=hit;
            downX=x;downY=y;invokeDown(controls[hit].action);return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
            int slot=slotFor(id);
            if(slot>=0&&pointerUsed[slot]){invokeUp(controls[pointerActions[slot]].action);pointerUsed[slot]=false;}
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE)return true;
        return true;
    }
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}\n    private boolean active(Action a){
        switch(a){case FIRE:return fireHeld;case AIM:return aimHeld;case CROUCH:return crouchHeld;case SPRINT:return sprintHeld;default:return false;}
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c); if(controls==null)buildControls();
        // Soft right-side combat vignette; transparent in the middle to preserve the world view.
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x16050A12);
        c.drawRoundRect(new RectF(getWidth()*.55f,getHeight()*.15f,getWidth()*.995f,getHeight()*.96f),d(34),d(34),p);
        for(Control b:controls){
            float x=cx(b),y=cy(b); boolean on=active(b.action);
            boolean primary=b.action==Action.FIRE;
            p.setShader(null);p.setStyle(Paint.Style.FILL);
            p.setColor(on?0xCC28A9F5:(primary?0xB51A2638:0x9C101C2B));
            p.setShadowLayer(d(primary?12:7),0,d(3),0x88000000);
            c.drawCircle(x,y,b.r,p);p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(on?2.8f:1.5f));
            p.setColor(on?0xFFF1FAFF:(primary?0xFFE8F5FF:0xAFC6D8E9));
            c.drawCircle(x,y,b.r-d(1),p);
            if(primary){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(2));p.setColor(0x99FFFFFF);c.drawCircle(x,y,b.r-d(6),p);}
            p.setStyle(Paint.Style.FILL);p.setColor(0xFFF4F8FC);p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));
            p.setTextSize(d(primary?27:23));c.drawText(b.glyph,x,y+d(primary?9:8),p);
            p.setTextSize(d(9));p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            p.setColor(0xFFEAF1F8);c.drawText(b.label,x,y+b.r+d(13),p);
        }
    }
}
