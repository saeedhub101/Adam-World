package com.explorer.world3d;

/** إسقاط خطوط الطول والعرض إلى متر محلي + شبكة المربعات (Tiles). */
public final class Geo {
    private Geo() {}

    public static final double KY = 111132.0;
    public static final double D_LAT = 0.003; // ~333 متر
    public static volatile double lat0 = 31.9516, lon0 = 35.9394;
    public static volatile double kx = 111320.0 * Math.cos(Math.toRadians(31.9516));
    public static volatile double dLon = D_LAT / Math.cos(Math.toRadians(31.9516));

    public static synchronized void setOrigin(double lat, double lon) {
        double c = Math.cos(Math.toRadians(lat));
        lat0 = lat;
        lon0 = lon;
        kx = 111320.0 * c;
        dLon = D_LAT / c;
    }

    public static float x(double lon) { return (float) ((lon - lon0) * kx); }
    public static float z(double lat) { return (float) (-(lat - lat0) * KY); }
    public static double latOf(float z) { return lat0 - z / KY; }
    public static double lonOf(float x) { return lon0 + x / kx; }

    public static int tileX(float x) { return (int) Math.floor(lonOf(x) / dLon); }
    public static int tileZ(float z) { return (int) Math.floor(latOf(z) / D_LAT); }

    public static long key(int ix, int iz) { return (((long) ix) << 32) | (iz & 0xffffffffL); }
}
