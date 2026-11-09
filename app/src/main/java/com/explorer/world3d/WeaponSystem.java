package com.explorer.world3d;

/** Original Adam World weapon archetypes and balance data. */
final class WeaponSystem {
 static final String[] NAME={"مسدس","رشاش خفيف","بندقية هجومية","Shotgun","قناصة"};
 static final int[] PRICE={0,900,1800,1500,2600};
 static final int[] DAMAGE={24,13,19,42,90};
 static final int[] RANGE={38,48,75,22,180};
 static final long[] FIRE_INTERVAL_MS={360,105,145,850,1200};
 static final int[] MAGAZINE={12,30,30,8,5};
 static final int[] START_AMMO={48,120,120,32,20};
 static final int[] AMMO_PACK={36,90,90,24,15};
 static final int[] AMMO_PACK_PRICE={120,220,260,180,300};
 static final float[] RELOAD_SECONDS={1.15f,1.55f,1.65f,2.0f,2.25f};
 private WeaponSystem(){}
}
