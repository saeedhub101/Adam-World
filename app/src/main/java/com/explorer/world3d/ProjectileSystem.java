package com.explorer.world3d;

import java.util.ArrayList;

/** Bounded, frame-rate independent rocket simulation for Adam World. */
final class ProjectileSystem {
    static final int MAX_ROCKETS=12, MAX_BLASTS=8;
    static final class Rocket {
        float x,y,z,vx,vy,vz,yaw,pitch,age,trailClock;
        boolean guided;
        Game.Agent target;
        final float[] tx=new float[7],ty=new float[7],tz=new float[7];
        int trailCount;
    }
    static final class Blast {
        float x,y,z,life;
        Blast(float x,float y,float z){this.x=x;this.y=y;this.z=z;life=.72f;}
    }
    private ProjectileSystem(){}

    static void launch(Game g,int weaponId){
        if(g.rockets.size()>=MAX_ROCKETS) return;
        float yaw=g.pyaw,fx=(float)Math.sin(yaw),fz=(float)Math.cos(yaw);
        Rocket r=new Rocket();r.guided=weaponId==6;
        r.x=g.px+fx*.78f;r.y=g.py+g.jumpHeight+1.25f;r.z=g.pz+fz*.78f;
        r.yaw=yaw;r.vx=fx*9f;r.vz=fz*9f;
        if(r.guided){
            float best=95f;
            for(Game.Agent a:g.agents){
                if(a.knockedOut)continue;
                float dx=a.x-r.x,dz=a.z-r.z,d=(float)Math.hypot(dx,dz);
                float dot=(dx*fx+dz*fz)/(d+.0001f);
                if(d<best&&dot>.68f){best=d;r.target=a;}
            }
        }
        g.rockets.add(r);
    }

    static void update(Game g,float dt,Game.World world){
        for(int i=g.blasts.size()-1;i>=0;i--){Blast b=g.blasts.get(i);b.life-=dt;if(b.life<=0)g.blasts.remove(i);}
        for(int i=g.rockets.size()-1;i>=0;i--){
            Rocket r=g.rockets.get(i);r.age+=dt;
            float speed=(float)Math.sqrt(r.vx*r.vx+r.vy*r.vy+r.vz*r.vz);
            if(r.guided&&r.target!=null&&!r.target.knockedOut){
                float dx=r.target.x-r.x,dz=r.target.z-r.z;
                float desired=(float)Math.atan2(dx,dz),current=(float)Math.atan2(r.vx,r.vz);
                float next=current+Game.clamp(Game.angDiff(current,desired),-2.25f*dt,2.25f*dt);
                float wanted=Math.min(43f,Math.max(22f,speed+13f*dt));
                r.vx=(float)Math.sin(next)*wanted;r.vz=(float)Math.cos(next)*wanted;
                float targetY=world.heightAt(r.target.x,r.target.z)+1.05f;
                r.vy+=Game.clamp(targetY-r.y,-10f,10f)*Math.min(1f,dt*1.7f);
                r.vy=Game.clamp(r.vy,-18f,18f);
            }else if(speed<34f){
                float k=Math.min(1f,dt*2.4f);r.vx+=(float)Math.sin(r.yaw)*34f*k;r.vz+=(float)Math.cos(r.yaw)*34f*k;
            }
            float nx=r.x+r.vx*dt,ny=r.y+r.vy*dt,nz=r.z+r.vz*dt;
            r.yaw=(float)Math.atan2(r.vx,r.vz);r.pitch=(float)Math.atan2(r.vy,(float)Math.hypot(r.vx,r.vz));
            r.trailClock+=dt;
            if(r.trailClock>=.055f){
                r.trailClock=0f;int p=r.trailCount<r.tx.length?r.trailCount++:r.tx.length-1;
                if(r.trailCount==r.tx.length)for(int k=0;k<r.tx.length-1;k++){r.tx[k]=r.tx[k+1];r.ty[k]=r.ty[k+1];r.tz[k]=r.tz[k+1];}
                r.tx[p]=r.x;r.ty[p]=r.y;r.tz[p]=r.z;
            }
            boolean impact=ny<=world.heightAt(nx,nz)+.25f||world.blocked(nx,nz,.22f);
            for(Game.Agent a:g.agents){
                if(a.knockedOut)continue;
                float dx=a.x-nx,dz=a.z-nz,d=(float)Math.hypot(dx,dz);
                if(d<1.45f&&Math.abs((world.heightAt(a.x,a.z)+1f)-ny)<2.5f){impact=true;break;}
            }
            if(impact||r.age>7f||Math.abs(nx-r.x)>1000f||Math.abs(nz-r.z)>1000f){detonate(g,r,world);g.rockets.remove(i);continue;}
            r.x=nx;r.y=ny;r.z=nz;
        }
    }

    private static void detonate(Game g,Rocket r,Game.World world){
        if(g.blasts.size()>=MAX_BLASTS)g.blasts.remove(0);
        g.blasts.add(new Blast(r.x,r.y,r.z));g.explosionEventCount++;
        final float radius=7.5f;
        for(Game.Agent a:g.agents){
            float dx=a.x-r.x,dz=a.z-r.z,d=(float)Math.hypot(dx,dz);
            float dy=(world.heightAt(a.x,a.z)+1f)-r.y;
            float dist=(float)Math.sqrt(dx*dx+dz*dz+dy*dy);
            if(dist>=radius||a.knockedOut)continue;
            float factor=1f-dist/radius;
            int damage=Math.max(8,(int)((r.guided?150f:125f)*factor));
            a.health=Math.max(0,a.health-damage);a.hitFlash=.45f;
            float len=Math.max(.001f,d),impulse=(r.guided?13f:11f)*factor;
            a.knockbackX+=dx/len*impulse;a.knockbackZ+=dz/len*impulse;
            if(!a.car){a.hostile=true;a.state=1;a.stunSeconds=.9f;}
            if(a.health==0){a.knockedOut=true;a.hostile=false;a.state=3;a.stateTime=0f;a.knockdownSeconds=22f;a.sp=0f;}
        }
        float pdx=g.px-r.x,pdz=g.pz-r.z,pd=(float)Math.hypot(pdx,pdz);
        if(pd<radius&&Math.abs(g.py+1f-r.y)<4f&&!g.dead){
            int damage=(int)(42f*(1f-pd/radius));
            if(damage>0){g.health=Math.max(0,g.health-damage);g.lastDamageMs=System.currentTimeMillis();g.hurtFlash=.4f;}
            if(g.health<=0){g.dead=true;g.deathStartedMs=System.currentTimeMillis();g.playerPose=2;g.playerPoseTime=0f;}
        }
    }
}
