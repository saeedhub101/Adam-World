package com.explorer.world3d;

/** Data-driven Battle Royale weapon archetypes and handling profiles. */
final class WeaponSystem {
 static final int SEMI_AUTO=0, FULL_AUTO=1, PUMP_ACTION=2, BOLT_ACTION=3, LAUNCHER=4;
 static final String[] NAME={"مسدس","رشاش خفيف","بندقية هجومية","Shotgun","قناصة","قاذف صواريخ","قاذف موجّه"};
 static final int[] PRICE={0,900,1800,1500,2600,4200,6800};
 static final int[] DAMAGE={24,13,19,42,90,125,150};
 static final int[] RANGE={38,48,75,22,180,220,260};
 static final long[] FIRE_INTERVAL_MS={360,105,145,850,1200,1500,1900};
 static final int[] MAGAZINE={12,30,30,8,5,1,1};
 static final int[] START_AMMO={48,120,120,32,20,6,5};
 static final int[] AMMO_PACK={36,90,90,24,15,3,2};
 static final int[] AMMO_PACK_PRICE={120,220,260,180,300,650,900};
 static final float[] RELOAD_SECONDS={1.15f,1.55f,1.65f,2.0f,2.25f,2.8f,3.1f};
 static final float[] RECOIL_IMPULSE={0.28f,0.12f,0.19f,0.52f,0.72f,0.88f,1.0f};
 static final float[] SPREAD_MULTIPLIER={1.0f,1.28f,0.92f,1.0f,0.48f,1.4f,1.55f};
 static final int[] RARITY={0,1,2,2,3,4,4};
 static final String[] RARITY_LABEL={"شائع","غير شائع","نادر","ملحمي","أسطوري"};
 static final int[] FIRE_MODE={SEMI_AUTO,FULL_AUTO,FULL_AUTO,PUMP_ACTION,BOLT_ACTION,LAUNCHER,LAUNCHER};
 static final int[] PELLETS={1,1,1,9,1,1,1};
 static final float[] ADS_CAMERA_SCALE={0.78f,0.82f,0.76f,0.88f,0.54f,0.94f,0.94f};
 static final float[] RECOIL_RECOVERY={6.4f,5.1f,5.5f,4.1f,3.2f,2.8f,2.5f};
 static final float[] MODEL_LENGTH={0.30f,0.58f,0.72f,0.68f,0.88f,0.82f,0.82f};
 static boolean isAutomatic(int id){return id>=0&&id<FIRE_MODE.length&&FIRE_MODE[id]==FULL_AUTO;}
 private WeaponSystem(){}
}
