package com.explorer.world3d;

/** Lightweight GPU-ready flight-school airport scene: runway, taxiway, apron, hangar, terminal and helipad. */
final class Airfield {
    private Airfield() {}

    static Mesh build() {
        GeoBuf b = new GeoBuf();
        surface(b, -660f, -620f, 660f, 620f, -0.08f, .30f, .39f, .27f);
        surface(b, -43f, -470f, 43f, 470f, 0.005f, .42f, .44f, .45f);
        surface(b, -32f, -460f, 32f, 460f, 0.025f, .19f, .21f, .23f);
        surface(b, 32f, -205f, 86f, -125f, 0.04f, .24f, .26f, .27f);
        surface(b, 70f, -245f, 270f, 18f, 0.045f, .34f, .36f, .37f);
        surface(b, -27.5f, -450f, -26.7f, 450f, 0.052f, .82f, .83f, .80f);
        surface(b, 26.7f, -450f, 27.5f, 450f, 0.052f, .82f, .83f, .80f);
        for (int z = -420; z <= 420; z += 34) surface(b, -0.35f, z, 0.35f, z + 17f, 0.055f, .92f, .93f, .90f);
        for (int side = -1; side <= 1; side += 2)
            for (int z = -435; z <= 435; z += 30) b.box(side * 35.5f, 0.28f, z, 0.65f, 0.42f, 0.65f, .92f, .95f, 1f);
        for (int end : new int[]{-1, 1}) {
            float z = end * 426f;
            for (int i = 0; i < 10; i++) {
                float x = -22.5f + i * 5f;
                surface(b, x, z, x + 2.8f, z + end * 9f, 0.06f, .96f, .96f, .93f);
            }
        }
        for (int side = -1; side <= 1; side += 2) {
            surface(b, side * 12f - 1.1f, -205f, side * 12f + 1.1f, -155f, 0.058f, .95f, .95f, .91f);
            surface(b, side * 12f - 1.1f, 155f, side * 12f + 1.1f, 205f, 0.058f, .95f, .95f, .91f);
        }
        for (int z = -250; z <= 250; z += 50) surface(b, 34f, z, 36f, z + 20f, 0.06f, .96f, .77f, .12f);
        for (int z = -195; z <= -130; z += 14) surface(b, 56f, z, 56.7f, z + 7f, 0.07f, .98f, .78f, .10f);

        // Hangar with open aircraft door.
        b.box(145f, 8f, -185f, 3f, 16f, 64f, .62f, .66f, .69f);
        b.box(215f, 8f, -185f, 3f, 16f, 64f, .62f, .66f, .69f);
        b.box(180f, 8f, -217f, 70f, 16f, 3f, .55f, .59f, .62f);
        b.box(180f, 16.5f, -185f, 74f, 2f, 70f, .40f, .45f, .49f);
        b.box(150f, 4f, -153f, 2f, 8f, 2f, .30f, .34f, .38f);
        b.box(210f, 4f, -153f, 2f, 8f, 2f, .30f, .34f, .38f);
        b.box(180f, 0.7f, -185f, 72f, 1.2f, 68f, .43f, .45f, .46f);

        // Passenger terminal, glazed front and control tower.
        b.box(165f, 8f, 95f, 112f, 16f, 58f, .72f, .73f, .70f);
        b.box(165f, 4.5f, 65.4f, 98f, 7f, 1.1f, .18f, .37f, .47f);
        b.box(165f, 17f, 95f, 116f, 1.6f, 62f, .45f, .49f, .52f);
        for (int x = 125; x <= 205; x += 20) b.box(x, 8f, 64.4f, 1.3f, 12f, 1f, .12f, .27f, .34f);
        b.box(282f, 15f, 65f, 15f, 30f, 15f, .66f, .68f, .67f);
        b.box(282f, 29f, 65f, 22f, 7f, 22f, .16f, .32f, .39f);
        b.box(282f, 33f, 65f, 24f, 1.2f, 24f, .54f, .58f, .60f);
        b.box(282f, 35f, 65f, 1f, 3f, 1f, .75f, .18f, .12f);

        // Helicopter pad with perimeter ring and H.
        ring(b, -125f, -105f, 23f, 0.09f, .92f, .92f, .86f);
        surface(b, -126f, -107f, -124f, -85f, 0.11f, .96f, .96f, .92f);
        surface(b, -136f, -99f, -114f, -97f, 0.11f, .96f, .96f, .92f);
        surface(b, -136f, -111f, -114f, -109f, 0.11f, .96f, .96f, .92f);
        b.box(100f, 2.2f, -45f, 8f, 4.4f, 5f, .76f, .76f, .70f);
        b.box(100f, 5f, -45f, 2f, 1.2f, 2f, .85f, .13f, .08f);
        return Mesh.from(b);
    }

    private static void surface(GeoBuf b, float x0, float z0, float x1, float z1, float y,
                               float r, float g, float blue) {
        b.quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, r, g, blue);
    }

    private static void ring(GeoBuf b, float cx, float cz, float radius, float y, float r, float g, float blue) {
        int segments = 48;
        float inner = radius - 0.8f;
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2.0 * i / segments, n = Math.PI * 2.0 * (i + 1) / segments;
            b.quad(cx + (float)Math.cos(a) * inner, y, cz + (float)Math.sin(a) * inner,
                    cx + (float)Math.cos(n) * inner, y, cz + (float)Math.sin(n) * inner,
                    cx + (float)Math.cos(n) * radius, y, cz + (float)Math.sin(n) * radius,
                    cx + (float)Math.cos(a) * radius, y, cz + (float)Math.sin(a) * radius, r, g, blue);
        }
    }
}
