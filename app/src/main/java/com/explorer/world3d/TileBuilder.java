package com.explorer.world3d;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** يحوّل JSON القادم من OpenStreetMap إلى هندسة ثلاثية الأبعاد لمربع واحد، موضوعة على التضاريس الحقيقية. */
final class TileBuilder {
    static final int B_FACADE = 0, B_GLASS = 1, B_SIDEWALK = 2, B_ROADN = 3, B_ROADW = 4, B_PLAIN = 5, B_SIGN = 6, B_WATER = 7;
    private static final JSONObject EMPTY = new JSONObject();
    private static final HashMap<String, Float> RW = new HashMap<>();
    private static final int[] PALETTE = {0xd9c9a8, 0xcfcfcf, 0xc4876b, 0xece6d8, 0xc2a27a, 0xaeb8c2, 0xd8b98e, 0xe3dccb};

    static {
        RW.put("motorway", 14f); RW.put("trunk", 12f); RW.put("primary", 11f); RW.put("secondary", 9f);
        RW.put("tertiary", 8f); RW.put("unclassified", 6f); RW.put("residential", 6f); RW.put("living_street", 5f);
        RW.put("service", 4.5f); RW.put("road", 5.5f); RW.put("busway", 3.5f); RW.put("raceway", 9f);
        RW.put("motorway_link", 7f); RW.put("trunk_link", 7f); RW.put("primary_link", 7f);
        RW.put("secondary_link", 6f); RW.put("tertiary_link", 6f); RW.put("pedestrian", 6f);
        RW.put("footway", 2.5f); RW.put("bridleway", 2.5f); RW.put("escape", 2.5f);
        RW.put("path", 2f); RW.put("steps", 2f); RW.put("cycleway", 2.5f); RW.put("track", 3.5f);
    }

    private TileBuilder() {}

    static float rnd(long id, int k) {
        double x = Math.sin(id * 12.9898 + k * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    private static String tg(JSONObject t, String k) {
        return t.has(k) ? t.optString(k, "") : "";
    }

    /** شبكة ارتفاعات محلية للمربع لتسريع تعبئة المساحات. */
    private static final class Hg {
        final float x0, z0, dx, dz;
        final int nx, nz;
        final float[] h;

        Hg(float xMin, float xMax, float zMin, float zMax, float step) {
            nx = Math.max(2, Math.round((xMax - xMin) / step)) + 1;
            nz = Math.max(2, Math.round((zMax - zMin) / step)) + 1;
            x0 = xMin; z0 = zMin;
            dx = (xMax - xMin) / (nx - 1);
            dz = (zMax - zMin) / (nz - 1);
            h = new float[nx * nz];
            for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) h[j * nx + i] = Terrain.h(x0 + i * dx, z0 + j * dz);
        }

        float s(float x, float z) {
            float fx = (x - x0) / dx, fz = (z - z0) / dz;
            fx = Math.max(0f, Math.min(nx - 1.001f, fx));
            fz = Math.max(0f, Math.min(nz - 1.001f, fz));
            int i = (int) fx, j = (int) fz;
            float ax = fx - i, az = fz - j;
            float a = h[j * nx + i], b = h[j * nx + i + 1], c = h[(j + 1) * nx + i], d = h[(j + 1) * nx + i + 1];
            return (a * (1 - ax) + b * ax) * (1 - az) + (c * (1 - ax) + d * ax) * az;
        }
    }

    static TileData build(JSONObject root, int ix, int iz) throws JSONException { return build(root, ix, iz, false, false); }

    static TileData build(JSONObject root, int ix, int iz, boolean tabarbour) throws JSONException {
        return build(root, ix, iz, tabarbour, false);
    }

    static TileData build(JSONObject root, int ix, int iz, boolean tabarbour, boolean manila) throws JSONException {
        TileData td = new TileData(ix, iz);
        td.manila = manila;
        double west = ix * Geo.dLon, east = (ix + 1) * Geo.dLon, south = iz * Geo.D_LAT, north = (iz + 1) * Geo.D_LAT;
        float xMin = Geo.x(west), xMax = Geo.x(east), zMin = Geo.z(north), zMax = Geo.z(south);
        JSONArray els = root.optJSONArray("elements");
        if (els == null) return td;
        Hg hg = new Hg(xMin, xMax, zMin, zMax, 5f);

        ArrayList<float[]> trees = new ArrayList<>(), lamps = new ArrayList<>();
        int bcount = 0;
        ArrayList<float[][]> parentFootprints = new ArrayList<>();
        Random rng = new Random(ix * 73856093L ^ iz * 19349663L);
        HashSet<String> names = new HashSet<>();

        for (int e = 0; e < els.length(); e++) {
            JSONObject el = els.optJSONObject(e);
            if (el == null) continue;
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) tags = EMPTY;
            String type = el.optString("type", "");

            if (type.equals("node")) {
                double lat = el.optDouble("lat", Double.NaN), lon = el.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                float x = Geo.x(lon), z = Geo.z(lat);
                if (x < xMin || x >= xMax || z < zMin || z >= zMax) continue;
                if (tg(tags, "natural").equals("tree") && trees.size() < 260) trees.add(new float[]{x, z});
                else if (tg(tags, "highway").equals("street_lamp") && lamps.size() < 60) lamps.add(new float[]{x, z});
                else if (tg(tags,"highway").equals("traffic_signals") || tg(tags,"crossing").equals("traffic_signals")) addTrafficSignal(td,x,z);
                else if (poiCategory(tags) > 0) addPoi(td, x, z, poiName(tags), poiCategory(tags), Terrain.h(x, z) + 2.8f);
                else if (isWaterUtility(tags)) addUtilityMarker(td, x, z, tags);
                else if (tg(tags,"railway").matches("station|halt|tram_stop|subway_entrance") || tg(tags,"public_transport").matches("station|platform|stop_position")) addPoi(td,x,z,poiName(tags),7,Terrain.h(x,z)+2.8f);
                else if (tg(tags,"highway").equals("bus_stop")) addPoi(td,x,z,poiName(tags),6,Terrain.h(x,z)+2.8f);
                continue;
            }
            if (!type.equals("way")) continue;
            JSONArray g = el.optJSONArray("geometry");
            if (g == null || g.length() < 2) continue;
            int n = g.length();
            float[] xs = new float[n], zs = new float[n];
            for (int i = 0; i < n; i++) {
                JSONObject p = g.getJSONObject(i);
                xs[i] = Geo.x(p.getDouble("lon"));
                zs[i] = Geo.z(p.getDouble("lat"));
            }
            long id = el.optLong("id", 0);

            String bld = tg(tags, "building");
            if (bld.isEmpty() && !tg(tags, "building:part").isEmpty()) bld = "yes";
            String hw = tg(tags, "highway");
            String leisure = tg(tags, "leisure"), landuse = tg(tags, "landuse"), natural = tg(tags, "natural");
            String waterway = tg(tags, "waterway");

            if (natural.equals("coastline")) {
                td.hasCoast = true;
                addCoastSand(td, xs, zs, xMin, xMax, zMin, zMax);
            } else if (!bld.isEmpty() && !bld.equals("no")) {
                if (bcount > 2500) continue;
                float[][] P = clean(xs, zs);
                if (P == null) continue;
                float[] px = P[0], pz = P[1];
                if (Math.abs(Poly.area(px, pz)) < 10f) continue;
                float[] center = centroid(px, pz);
                float cx = center[0], cz = center[1];
                if (cx < xMin || cx >= xMax || cz < zMin || cz >= zMax) continue;
                if (Landmarks.suppress(cx, cz)) continue;
                boolean isPart = !tg(tags, "building:part").isEmpty();
                if (isPart) {
                    boolean coveredByParent = false;
                    for (float[][] parent : parentFootprints) {
                        if (Poly.contains(parent[0], parent[1], cx, cz)) {
                            coveredByParent = true;
                            break;
                        }
                    }
                    if (coveredByParent) continue;
                } else {
                    // Primary footprints are retained; overlapping building:part polygons
                    // are suppressed so their facade/window meshes are not rendered twice.
                    parentFootprints.add(P);
                }
                bcount++;
                if (isBalconyPart(tags)) {
                    addBalconyPart(td, tags, px, pz);
                    continue; // A balcony is a suspended slab/railing, never a ground-level building/collision.
                }
                addBuilding(td, tags, id, bld, px, pz, tabarbour, manila);
                int poi = poiCategory(tags);
                String buildingLabel = poiName(tags);
                String houseNumber = tg(tags, "addr:housenumber");
                String streetName = tg(tags, "addr:street:ar");
                if (streetName.isEmpty()) streetName = tg(tags, "addr:street");
                if (streetName.isEmpty()) streetName = tg(tags, "addr:street:en");
                if (!houseNumber.isEmpty()) {
                    if (buildingLabel.equals(houseNumber)) buildingLabel = "رقم " + houseNumber;
                    else buildingLabel = buildingLabel.isEmpty() ? "رقم " + houseNumber : buildingLabel + " · رقم " + houseNumber;
                }
                // Do not turn internal OSM IDs into fictional storefront signs.
                // Only mapped names, brands, business names or real house numbers
                // are eligible for a facade label.
                if (houseNumber.isEmpty() && buildingLabel.isEmpty()) {
                    String explicitRef = tg(tags, "ref");
                    if (!explicitRef.isEmpty()) buildingLabel = explicitRef;
                }
                if (!streetName.isEmpty() && !buildingLabel.contains(streetName))
                    buildingLabel += " · " + streetName;
                float mappedFloors = measure(tg(tags, "building:levels"));
                boolean estimatedFloors = tg(tags, "adam:floors_quality").equals("estimated_range");
                String floorLabel = mappedFloors > 0f
                        ? (estimatedFloors ? "≈" : "") + Math.round(mappedFloors) + " طوابق"
                        : (bHeight(tags, id, bld, tabarbour) > 0f
                            ? "≈" + Math.max(1, Math.round(bHeight(tags, id, bld, tabarbour) / 3.2f)) + " طوابق"
                            : "الطوابق غير موثقة");
                float shownHeight = bHeight(tags, id, bld, tabarbour);
                float mappedHeight = metricMeasure(tg(tags, "height"));
                if (!(mappedHeight > 0f)) mappedHeight = metricMeasure(tg(tags, "building:height"));
                boolean estimatedHeight = !(mappedHeight > 0f)
                        || tg(tags, "adam:height_source").contains("OpenBuildingMap/GHSL");
                String heightLabel = shownHeight > 0f
                        ? (estimatedHeight ? "≈" : "") + (Math.round(shownHeight * 10f) / 10f) + " م"
                        : "الارتفاع غير موثق";
                buildingLabel += " · " + floorLabel + " · " + heightLabel;
                if (!buildingLabel.trim().isEmpty()) {
                    addFacadeLabel(td, px, pz, buildingLabel, bMinHeight(tags, bHeight(tags, id, bld, tabarbour)), poi);
                }
            } else if (!hw.isEmpty() && RW.containsKey(hw)) {
                String nm = tg(tags, "name:ar");
                if (nm.isEmpty()) nm = tg(tags, "name");
                if (nm.isEmpty()) nm = tg(tags, "name:en");
                if (nm.isEmpty()) nm = tg(tags, "ref");
                addRoad(td, tags, hw, nm, names, xs, zs, xMin, xMax, zMin, zMax, tabarbour);
            } else if (tg(tags,"railway").equals("platform") || tg(tags,"public_transport").equals("platform")) {
                boolean elevatedPlatform = (!tg(tags,"bridge").isEmpty() && !tg(tags,"bridge").equals("no"))
                        || measure(tg(tags,"layer")) > 0f;
                float platformY = 0.24f + (elevatedPlatform ? bridgeDeckOffset(tags,true,false,true) : 0f);
                float[][] platform = clean(xs,zs);
                if (platform != null && Math.abs(Poly.area(platform[0],platform[1])) >= 2f
                        && overlaps(platform,xMin,xMax,zMin,zMax)) {
                    int[] tris = Poly.earClip(platform[0],platform[1]);
                    GeoBuf platformBuf = td.bufs[B_ROADN];
                    for (int i=0;i+2<tris.length;i+=3) {
                        for (int k=0;k<3;k++) {
                            int v=tris[i+k];
                            platformBuf.v(platform[0][v],Terrain.h(platform[0][v],platform[1][v])+platformY,
                                    platform[1][v],0,0,0.46f,0.48f,0.50f);
                        }
                        platformBuf.fixTri();
                    }
                } else {
                    addClippedRibbon(td,xs,zs,3.2f,platformY,td.bufs[B_ROADN],0.46f,0.48f,0.50f);
                }
            } else if (tg(tags,"railway").matches("rail|light_rail|tram|subway|narrow_gauge|monorail")) {
                boolean railBridge = !tg(tags,"bridge").isEmpty() && !tg(tags,"bridge").equals("no");
                boolean railTunnel = !tg(tags,"tunnel").isEmpty() && !tg(tags,"tunnel").equals("no");
                float railDeck = 0.24f + bridgeDeckOffset(tags, railBridge, railTunnel, true);
                float railWidth = tg(tags,"railway").equals("rail") ? 4.2f : 3.2f;
                addClippedRibbon(td,xs,zs,railWidth,railDeck,td.bufs[B_ROADN],0.25f,0.27f,0.29f);
                if (railBridge) {
                    double railWest=td.ix*Geo.dLon,railEast=(td.ix+1)*Geo.dLon;
                    double railSouth=td.iz*Geo.D_LAT,railNorth=(td.iz+1)*Geo.D_LAT;
                    for (ArrayList<float[]> piece : clipLine(xs,zs,Geo.x(railWest),Geo.x(railEast),Geo.z(railNorth),Geo.z(railSouth))) {
                        float[][] clipped=toArrays(piece);
                        if (clipped!=null) addBridgeParapets(clipped[0],clipped[1],railWidth,railDeck,td.bufs[B_ROADN],1.2f);
                    }
                }
            } else if (tg(tags,"route").matches("bus|trolleybus")) {
                addClippedRibbon(td,xs,zs,2.4f,0.25f,td.bufs[B_ROADN],0.12f,0.42f,0.88f);
            } else if (isWaterUtility(tags)) {
                if (tg(tags,"man_made").matches("pipeline") || waterway.matches("drain|ditch|sewer")) {
                    if (g.length() >= 2) addWaterUtilityLine(td,tags,xs,zs);
                } else {
                    float[][] utilityPolygon=clean(xs,zs);
                    if (utilityPolygon!=null && overlaps(utilityPolygon,xMin,xMax,zMin,zMax)) {
                        float[] utilityCenter=centroid(utilityPolygon[0],utilityPolygon[1]);
                        addUtilityMarker(td,utilityCenter[0],utilityCenter[1],tags);
                    }
                }
            } else if (waterway.matches("river|stream|canal|ditch|drain|wadi|tidal_channel")) {
                float w = waterway.equals("river") ? 14f : waterway.equals("canal") ? 8f : 3f;
                String ws = tg(tags, "width");
                if (!ws.isEmpty()) { try { w = Math.max(1.5f, Math.min(80f, Float.parseFloat(ws))); } catch (Exception ignore) {} }
                addClippedRibbon(td,xs,zs,w,0.22f,td.bufs[B_WATER],0.12f,0.48f,0.86f);
            } else if (natural.equals("water") || waterway.equals("riverbank") || landuse.matches("reservoir|basin")) {
                float[][] P = clean(xs, zs);
                if (P == null || !overlaps(P, xMin, xMax, zMin, zMax)) continue;
                float lvl = 1e9f;
                for (int i = 0; i < P[0].length; i++) lvl = Math.min(lvl, hg.s(P[0][i], P[1][i]));
                fillCells(td.bufs[B_WATER], P[0], P[1], xMin, xMax, zMin, zMax, 5f, hg, lvl + 0.1f, 0f, 1f, 1f, 1f, id);
            } else if (poiCategory(tags) > 0) {
                float[][] P = clean(xs, zs);
                if (P == null || !overlaps(P, xMin, xMax, zMin, zMax)) continue;
                float[] center = centroid(P[0], P[1]);
                addPoi(td, center[0], center[1], poiName(tags), poiCategory(tags), Terrain.h(center[0], center[1]) + 3.2f);
            } else if (leisure.matches("park|garden|pitch|playground")
                    || landuse.matches("grass|forest|recreation_ground|village_green|meadow|sand|farmland|orchard|vineyard|cemetery")
                    || natural.matches("wood|scrub|beach|sand|dune|wetland|heath|grassland")) {
                float[][] P = clean(xs, zs);
                if (P == null || !overlaps(P, xMin, xMax, zMin, zMax)) continue;
                boolean pitch = leisure.equals("pitch") || !tg(tags, "sport").isEmpty();
                boolean playground = leisure.equals("playground");
                boolean garden = leisure.equals("garden") || leisure.equals("park")
                        || landuse.matches("recreation_ground|village_green");
                boolean sand = natural.matches("beach|sand|dune") || landuse.equals("sand") || playground;
                boolean forest = natural.equals("wood") || landuse.equals("forest");
                boolean farm = landuse.matches("farmland|orchard|vineyard");
                float gr = 0.44f, gg = 0.56f, gb = 0.31f, yo = 0.13f;
                if (sand) { gr = 0.78f; gg = 0.68f; gb = 0.48f; yo = 0.12f; }
                else if (pitch) { gr = 0.22f; gg = 0.43f; gb = 0.20f; yo = 0.15f; }
                else if (garden) { gr = 0.43f; gg = 0.39f; gb = 0.29f; yo = 0.11f; }
                else if (forest) { gr = 0.27f; gg = 0.40f; gb = 0.21f; yo = 0.14f; }
                else if (farm) { gr = 0.58f; gg = 0.55f; gb = 0.33f; yo = 0.10f; }
                else if (natural.matches("scrub|heath|grassland")) { gr = 0.50f; gg = 0.52f; gb = 0.32f; yo = 0.12f; }
                else if (natural.equals("wetland")) { gr = 0.36f; gg = 0.50f; gb = 0.40f; yo = 0.11f; }
                int cells = fillCells(td.bufs[B_PLAIN], P[0], P[1], xMin, xMax, zMin, zMax, 5f, hg, Float.NaN, yo, gr, gg, gb, id);
                if (pitch) {
                    float[] centerPitch = centroid(P[0], P[1]);
                    float minPX = 1e9f, maxPX = -1e9f, minPZ = 1e9f, maxPZ = -1e9f;
                    for (int pi = 0; pi < P[0].length; pi++) {
                        minPX = Math.min(minPX, P[0][pi]); maxPX = Math.max(maxPX, P[0][pi]);
                        minPZ = Math.min(minPZ, P[1][pi]); maxPZ = Math.max(maxPZ, P[1][pi]);
                    }
                    // Painted field boundary, halfway line and small goals, clipped to the mapped pitch polygon.
                    float[][] lines = {
                        {minPX,minPZ,maxPX,minPZ}, {maxPX,minPZ,maxPX,maxPZ},
                        {maxPX,maxPZ,minPX,maxPZ}, {minPX,maxPZ,minPX,minPZ},
                        {centerPitch[0],minPZ,centerPitch[0],maxPZ}
                    };
                    for (float[] line : lines) {
                        float[] lx = {line[0], line[2]}, lz = {line[1], line[3]};
                        addClippedRibbon(td, lx, lz, 0.12f, 0.18f, td.bufs[B_PLAIN], 0.95f, 0.95f, 0.88f);
                    }
                    float goalZ = Math.max(1.5f, Math.min(3.0f, (maxPZ-minPZ)*0.12f));
                    td.bufs[B_PLAIN].box(minPX+0.5f, Terrain.h(minPX+0.5f,centerPitch[1])+0.7f, centerPitch[1],
                            0.18f,1.4f,goalZ,0.88f,0.88f,0.82f);
                    td.bufs[B_PLAIN].box(maxPX-0.5f, Terrain.h(maxPX-0.5f,centerPitch[1])+0.7f, centerPitch[1],
                            0.18f,1.4f,goalZ,0.88f,0.88f,0.82f);
                }
                if (playground) {
                    float[] playCenter = centroid(P[0], P[1]);
                    float py = Terrain.h(playCenter[0], playCenter[1]);
                    // Simple, low-profile playground equipment on the mapped sand surface.
                    td.bufs[B_PLAIN].box(playCenter[0]-2.0f,py+0.65f,playCenter[1],2.4f,0.12f,0.8f,0.82f,0.18f,0.12f);
                    td.bufs[B_PLAIN].box(playCenter[0]-2.9f,py+1.15f,playCenter[1],0.12f,1.0f,0.12f,0.75f,0.72f,0.62f);
                    td.bufs[B_PLAIN].box(playCenter[0]+1.4f,py+0.8f,playCenter[1],0.12f,1.5f,0.12f,0.16f,0.32f,0.62f);
                }
                int nt = sand ? 0 : Math.min(forest ? 40 : 25, (int) (cells * 25f / (forest ? 70f : 220f)));
                float mnx = 1e9f, mxx = -1e9f, mnz = 1e9f, mxz = -1e9f;
                for (int i = 0; i < P[0].length; i++) {
                    mnx = Math.min(mnx, P[0][i]); mxx = Math.max(mxx, P[0][i]);
                    mnz = Math.min(mnz, P[1][i]); mxz = Math.max(mxz, P[1][i]);
                }
                mnx = Math.max(mnx, xMin); mxx = Math.min(mxx, xMax); mnz = Math.max(mnz, zMin); mxz = Math.min(mxz, zMax);
                for (int k = 0; nt > 0 && k < nt * 4 && trees.size() < 260; k++) {
                    float x = mnx + rng.nextFloat() * (mxx - mnx), z = mnz + rng.nextFloat() * (mxz - mnz);
                    if (Poly.contains(P[0], P[1], x, z)) { trees.add(new float[]{x, z}); nt--; }
                }
            }
        }

        // أعمدة إنارة على الطرق الواسعة
        for (RoadPath rp : td.paths) {
            if (!rp.veh || rp.w < 6f) continue;
            float[] o = new float[4];
            int k = 0;
            for (float s = 15; s < rp.total - 8 && lamps.size() < 120; s += 42, k++) {
                float off = (rp.w / 2f + 1.7f) * (k % 2 == 0 ? 1 : -1);
                rp.posAt(s, off, o);
                lamps.add(new float[]{o[0], o[1]});
            }
        }
        Landmarks.addToTile(td, xMin, xMax, zMin, zMax);
        GeoBuf pl = td.bufs[B_PLAIN];
        for (float[] t : trees) addTree(pl, t[0], Terrain.h(t[0], t[1]), t[1], 0.8f + rng.nextFloat() * 0.7f, rng);
        for (float[] l : lamps) {
            float y0 = Terrain.h(l[0], l[1]);
            pl.box(l[0], y0 + 3.5f, l[1], 0.12f, 7f, 0.12f, 0.33f, 0.35f, 0.38f);
            pl.box(l[0], y0 + 7.1f, l[1], 0.5f, 0.25f, 0.5f, 1f, 0.95f, 0.7f);
        }
        return td;
    }

    /** يزيل النقاط المكررة والنقطة الأخيرة المطابقة للأولى. */
    private static float[][] clean(float[] xs, float[] zs) {
        int n = xs.length;
        float[] ox = new float[n], oz = new float[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && Math.hypot(xs[i] - ox[m - 1], zs[i] - oz[m - 1]) <= 0.05) continue;
            ox[m] = xs[i]; oz[m] = zs[i]; m++;
        }
        if (m > 2 && Math.hypot(ox[0] - ox[m - 1], oz[0] - oz[m - 1]) < 0.05) m--;
        if (m < 3) return null;
        return new float[][]{java.util.Arrays.copyOf(ox, m), java.util.Arrays.copyOf(oz, m)};
    }

    private static boolean overlaps(float[][] P, float x0, float x1, float z0, float z1) {
        float mnx = 1e9f, mxx = -1e9f, mnz = 1e9f, mxz = -1e9f;
        for (int i = 0; i < P[0].length; i++) {
            mnx = Math.min(mnx, P[0][i]); mxx = Math.max(mxx, P[0][i]);
            mnz = Math.min(mnz, P[1][i]); mxz = Math.max(mxz, P[1][i]);
        }
        return !(mxx < x0 || mnx > x1 || mxz < z0 || mnz > z1);
    }

    /** يملأ مضلعاً بخلايا صغيرة تتبع التضاريس داخل حدود المربع. flatY غير NaN يعني مستوى ثابتاً (بحيرة). */
    private static int fillCells(GeoBuf b, float[] px, float[] pz, float xMin, float xMax, float zMin, float zMax,
                                 float step, Hg hg, float flatY, float yOff, float r, float g, float bl, long id) {
        float mnx = 1e9f, mxx = -1e9f, mnz = 1e9f, mxz = -1e9f;
        for (int i = 0; i < px.length; i++) {
            mnx = Math.min(mnx, px[i]); mxx = Math.max(mxx, px[i]);
            mnz = Math.min(mnz, pz[i]); mxz = Math.max(mxz, pz[i]);
        }
        int nx = Math.max(1, Math.round((xMax - xMin) / step)), nz = Math.max(1, Math.round((zMax - zMin) / step));
        float cx = (xMax - xMin) / nx, cz = (zMax - zMin) / nz;
        int i0 = Math.max(0, (int) Math.floor((mnx - xMin) / cx)), i1 = Math.min(nx - 1, (int) Math.floor((mxx - xMin) / cx));
        int j0 = Math.max(0, (int) Math.floor((mnz - zMin) / cz)), j1 = Math.min(nz - 1, (int) Math.floor((mxz - zMin) / cz));
        int count = 0;
        boolean flat = !Float.isNaN(flatY);
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                float xa = xMin + i * cx, xb = xa + cx, za = zMin + j * cz, zb = za + cz;
                if (!Poly.contains(px, pz, (xa + xb) / 2f, (za + zb) / 2f)) continue;
                float ya, yb, yc, yd;
                if (flat) { ya = yb = yc = yd = flatY; }
                else {
                    ya = hg.s(xa, za) + yOff; yb = hg.s(xb, za) + yOff; yc = hg.s(xb, zb) + yOff; yd = hg.s(xa, zb) + yOff;
                }
                float v = flat ? 1f : 0.96f + 0.08f * rnd(id + i * 7L + j * 131L, 1);
                float rr = r * v, gg = g * v, bb = bl * v;
                b.v(xa, ya, za, .5f, .5f, rr, gg, bb); b.v(xb, yb, za, .5f, .5f, rr, gg, bb); b.v(xb, yc, zb, .5f, .5f, rr, gg, bb); b.fixTri();
                b.v(xa, ya, za, .5f, .5f, rr, gg, bb); b.v(xb, yc, zb, .5f, .5f, rr, gg, bb); b.v(xa, yd, zb, .5f, .5f, rr, gg, bb); b.fixTri();
                count++;
                if (count > 9000) return count;
            }
        }
        return count;
    }

    /* ---------- المباني ---------- */
    private static float[] hex(String s) {
        if (s.startsWith("#") && s.length() == 7) {
            try {
                int c = Integer.parseInt(s.substring(1), 16);
                return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
            } catch (Exception ignore) {}
        }
        switch (s) {
            case "white": return new float[]{0.92f, 0.92f, 0.9f};
            case "red": return new float[]{0.7f, 0.25f, 0.2f};
            case "brown": return new float[]{0.5f, 0.35f, 0.25f};
            case "grey": case "gray": return new float[]{0.6f, 0.6f, 0.6f};
            case "yellow": return new float[]{0.88f, 0.8f, 0.5f};
            case "beige": return new float[]{0.85f, 0.78f, 0.62f};
            case "black": return new float[]{0.2f, 0.2f, 0.2f};
            case "green": return new float[]{0.4f, 0.55f, 0.4f};
            case "blue": return new float[]{0.4f, 0.5f, 0.7f};
            case "orange": return new float[]{0.85f, 0.55f, 0.3f};
            default: return null;
        }
    }

    private static float measure(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Float.NaN;
        String value = raw.trim().toLowerCase(java.util.Locale.US);
        Matcher matcher = Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matcher(value);
        if (!matcher.find()) return Float.NaN;
        try {
            float n = Float.parseFloat(matcher.group());
            if (value.contains("feet") || value.contains("foot") || value.contains(" ft") || value.endsWith("ft") || value.contains("'")) n *= 0.3048f;
            return Float.isFinite(n) ? n : Float.NaN;
        } catch (Exception ignore) { return Float.NaN; }
    }

    /** Accept only actual numeric measurements; reject source codes such as H:4 or HBET:1-5. */
    private static float metricMeasure(String raw) {
        if (raw == null) return Float.NaN;
        String value = raw.trim().toLowerCase(java.util.Locale.US);
        if (!value.matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)\\s*(?:m|meter|meters|metre|metres|ft|feet|foot|')?$"))
            return Float.NaN;
        return measure(value);
    }

    private static float bHeight(JSONObject t, long id, String b, boolean tabarbour) {
        // Use explicit metric heights from source data first. OpenFreeMap's
        // render_height is a cartographic fallback (often a generic 5 m), not
        // a surveyed building height, so it must never override missing data.
        float h = metricMeasure(tg(t, "height"));
        if (!(h > 0f)) h = metricMeasure(tg(t, "building:height"));
        if (!(h > 0f)) h = metricMeasure(tg(t, "height:meters"));
        if (h > 0f) return Math.max(2.5f, h);
        float levels = measure(tg(t, "building:levels"));
        if (!(levels > 0f)) levels = measure(tg(t, "num_floors"));
        if (!(levels > 0f)) levels = measure(tg(t, "levels"));
        if (levels > 0f) {
            float roof = measure(tg(t, "roof:height"));
            if (!(roof > 0f)) roof = 0f;
            return Math.max(2.5f, levels * 3.2f + roof);
        }
        // OpenMapTiles render_height is a cartographic estimate, not a survey.
        // Use it only as a last-resort approximate visual height and label it ≈;
        // never let it replace explicit height or floor-count measurements.
        float renderHeight = measure(tg(t, "adam:render_height_m"));
        // Tabarbour's cartographic render heights frequently collapse multi-storey
        // apartment blocks to a 4-6 m extrusion. Keep that value as a lower bound,
        // not as the final height, when stronger floor/class evidence is available.
        float classFloorEstimate = 0f;
        if (tabarbour && !b.matches("roof|carport|garage|garages|shed|hut")) {
            // A low generic cartographic extrusion is not accepted as a one-storey
            // default for Tabarbour; use at least two estimated storeys if no
            // measured height/floor count exists, then refine by mapped building class.
            classFloorEstimate = 2f;
            if (b.matches("apartments|dormitory|residential")) classFloorEstimate = 5f;
            else if (b.matches("hotel|motel|hostel|hospital|clinic|commercial|office|retail|supermarket|mall|university")) classFloorEstimate = 4f;
            else if (b.matches("house|detached|semidetached_house|bungalow")) classFloorEstimate = 2f;
        }
        if (classFloorEstimate > 0f) {
            float classHeight = classFloorEstimate * 3.2f;
            if (renderHeight > 0f && renderHeight <= 250f) return Math.max(classHeight, Math.max(2.5f, renderHeight));
            return classHeight;
        }
        if (renderHeight > 0f && renderHeight <= 250f) return Math.max(2.5f, renderHeight);
        // Explicit class fallback when no measured height/floor count is available.
        if (b.equals("roof") || b.equals("carport") || b.equals("garage") || b.equals("garages")) return 3.5f;
        if (b.matches("house|detached|shed|hut|cabin|semidetached_house|bungalow")) return 6.5f;
        if (b.matches("apartments|commercial|office|hotel|retail|hospital|university|school")) return 12.8f;
        if (b.matches("industrial|warehouse|factory")) return 8.5f;
        return 7.5f;
    }

    private static float bMinHeight(JSONObject t, float totalHeight) {
        float value = measure(tg(t, "min_height"));
        if (!(value > 0f)) return 0f;
        return Math.max(0f, Math.min(value, totalHeight - 1f));
    }

    /** True polygon centroid, so footprints crossing a tile edge are assigned by their actual area. */
    private static float[] centroid(float[] xs, float[] zs) {
        double crossSum = 0d, cx = 0d, cz = 0d;
        for (int i = 0; i < xs.length; i++) {
            int j = (i + 1) % xs.length;
            double cross = (double) xs[i] * zs[j] - (double) xs[j] * zs[i];
            crossSum += cross;
            cx += (xs[i] + xs[j]) * cross;
            cz += (zs[i] + zs[j]) * cross;
        }
        if (Math.abs(crossSum) < 1e-8d) {
            cx = 0d; cz = 0d;
            for (int i = 0; i < xs.length; i++) { cx += xs[i]; cz += zs[i]; }
            return new float[]{(float) (cx / xs.length), (float) (cz / xs.length)};
        }
        return new float[]{(float) (cx / (3d * crossSum)), (float) (cz / (3d * crossSum))};
    }

    private static float[] buildingColor(JSONObject t, long id, boolean tabarbour, boolean manila) {
        String s = tg(t, "building:colour");
        if (s.isEmpty()) s = tg(t, "building:color");
        if (s.isEmpty()) s = tg(t, "facade:colour");
        if (s.isEmpty()) s = tg(t, "facade:color");
        if (s.isEmpty()) s = tg(t, "colour");
        float[] hc = hex(s);
        if (hc != null) return hc;
        String m = tg(t, "building:material");
        switch (m) {
            case "brick": return new float[]{0.62f, 0.35f, 0.27f};
            case "concrete": case "cement_block": return new float[]{0.68f, 0.68f, 0.66f};
            case "stone": case "sandstone": case "limestone": return new float[]{0.80f, 0.74f, 0.62f};
            case "wood": return new float[]{0.60f, 0.45f, 0.30f};
            case "plaster": case "stucco": case "render": return new float[]{0.90f, 0.86f, 0.76f};
            default: break;
        }
        // No per-building random colours: unknown facade colour stays neutral until
        // a verified matching facade image or an explicit mapped colour is available.
        if (manila) return new float[]{0.82f, 0.82f, 0.80f};
        return tabarbour ? new float[]{0.91f, 0.87f, 0.78f} : new float[]{0.78f, 0.73f, 0.65f};
    }

    private static void facadeQuad(GeoBuf b,
            float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz,
            float r, float g, float bl) {
        b.v(ax, ay, az, 0, 0, r, g, bl);
        b.v(bx, by, bz, 1, 0, r, g, bl);
        b.v(cx, cy, cz, 1, 1, r, g, bl); b.fixTri();
        b.v(ax, ay, az, 0, 0, r, g, bl);
        b.v(cx, cy, cz, 1, 1, r, g, bl);
        b.v(dx, dy, dz, 0, 1, r, g, bl); b.fixTri();
    }


    private static boolean isBalconyPart(JSONObject tags) {
        String part = tg(tags, "building:part").toLowerCase(java.util.Locale.US);
        return part.equals("balcony") || tg(tags, "adam:structure_type").equals("balcony");
    }

    /** Render mapped balcony parts as elevated slabs with a light perimeter rail. */
    private static void addBalconyPart(TileData td, JSONObject tags, float[] px, float[] pz) {
        int n = px.length;
        if (n < 3) return;
        float level = measure(tg(tags, "min_height"));
        if (!(level > 0f)) level = measure(tg(tags, "building:min_level")) * 3.2f;
        if (!(level > 0f)) level = measure(tg(tags, "height"));
        if (!(level >= 2.2f)) level = 3.2f; // conservative first-floor estimate when OSM omits elevation
        level = Math.max(2.2f, Math.min(80f, level));
        GeoBuf b = td.bufs[B_FACADE];
        int[] triangles = Poly.earClip(px, pz);
        if (triangles == null || triangles.length < 3) return;
        // Thin concrete/stone slab, following terrain elevation at each vertex.
        float thickness = 0.16f;
        for (int i = 0; i + 2 < triangles.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int v = triangles[i + k];
                float y = Terrain.h(px[v], pz[v]) + level;
                b.v(px[v], y, pz[v], 0, 0, 0.70f, 0.69f, 0.65f);
            }
            b.fixTri();
            for (int k = 2; k >= 0; k--) {
                int v = triangles[i + k];
                float y = Terrain.h(px[v], pz[v]) + level - thickness;
                b.v(px[v], y, pz[v], 0, 0, 0.48f, 0.47f, 0.44f);
            }
            b.fixTri();
        }
        // Rails are drawn only around the outside edge; no wall is dropped to street level.
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float dx = px[j] - px[i], dz = pz[j] - pz[i];
            float len = (float) Math.hypot(dx, dz);
            if (len < 0.5f) continue;
            float nx = -dz / len, nz = dx / len;
            float mx = (px[i] + px[j]) * 0.5f + nx * 0.35f;
            float mz = (pz[i] + pz[j]) * 0.5f + nz * 0.35f;
            if (Poly.contains(px, pz, mx, mz)) { nx = -nx; nz = -nz; }
            float y0a = Terrain.h(px[i], pz[i]) + level;
            float y0b = Terrain.h(px[j], pz[j]) + level;
            float y1a = y0a + 1.0f, y1b = y0b + 1.0f;
            float off = 0.045f;
            float r = 0.32f, g = 0.34f, bl = 0.35f;
            b.v(px[i]+nx*off,y0a,pz[i]+nz*off,0,0,r,g,bl);
            b.v(px[j]+nx*off,y0b,pz[j]+nz*off,1,0,r,g,bl);
            b.v(px[j]+nx*off,y1b,pz[j]+nz*off,1,1,r,g,bl); b.fixTri();
            b.v(px[i]+nx*off,y0a,pz[i]+nz*off,0,0,r,g,bl);
            b.v(px[j]+nx*off,y1b,pz[j]+nz*off,1,1,r,g,bl);
            b.v(px[i]+nx*off,y1a,pz[i]+nz*off,0,1,r,g,bl); b.fixTri();
        }
    }

    /** Resolve vertical clearance for a bridge deck without moving the road beneath it. */
    private static float bridgeDeckOffset(JSONObject tags, boolean bridge, boolean tunnel, boolean railOrFoot) {
        String raw = tg(tags, bridge ? "bridge:height" : "tunnel:height");
        float mapped = measure(raw);
        if (mapped > 0f) return Math.max(0.8f, Math.min(40f, mapped));
        float layer = measure(tg(tags, "layer"));
        if (Float.isFinite(layer) && layer != 0f) {
            float byLayer = layer * 3.2f;
            if (bridge && layer > 0f) return Math.max(byLayer, railOrFoot ? 5.0f : 4.8f);
            return byLayer;
        }
        if (bridge) return railOrFoot ? 5.0f : 4.8f;
        if (tunnel) return -2.8f;
        return 0f;
    }

    private static void addBuilding(TileData td, JSONObject tags, long id, String bld, float[] px, float[] pz, boolean tabarbour, boolean manila) {
        int n = px.length;
        float hMin = 1e9f, hMax = -1e9f;
        float[] ground = new float[n], roofY = new float[n];
        for (int i = 0; i < n; i++) {
            ground[i] = Terrain.h(px[i], pz[i]);
            hMin = Math.min(hMin, ground[i]);
            hMax = Math.max(hMax, ground[i]);
        }
        float h = bHeight(tags, id, bld, tabarbour);
        float minHeight = bMinHeight(tags, h);
        float partMinLevel = measure(tg(tags, "building:min_level"));
        if (!(minHeight > 0f) && partMinLevel > 0f) minHeight = Math.min(h - 0.5f, partMinLevel * 3.2f);
        float undergroundDepth = measure(tg(tags, "adam:underground:height"));
        if (!(undergroundDepth > 0f)) {
            float undergroundLevels = measure(tg(tags, "building:levels:underground"));
            undergroundDepth = undergroundLevels > 0f ? undergroundLevels * 3.0f : 0f;
        }
        undergroundDepth = Math.max(0f, Math.min(60f, undergroundDepth));
        float mappedRoofHeight = measure(tg(tags, "roof:height"));
        if (!(mappedRoofHeight > 0f)) mappedRoofHeight = Float.NaN;
        String rshape = tg(tags, "roof:shape");
        float roofRise = Float.isFinite(mappedRoofHeight) ? Math.max(0f, Math.min(20f, mappedRoofHeight))
                : (rshape.matches("gabled|hipped|pyramidal|skillion") ? 2.0f : 0f);
        float wallHeight = Math.max(1f, h - roofRise);
        // OSM height is the total height; roof:height is the roof portion, not an extra height.
        for (int i = 0; i < n; i++) roofY[i] = ground[i] + wallHeight;
        float yTop = hMax + wallHeight;
        String facadeMaterial = tg(tags, "building:material").toLowerCase(java.util.Locale.US);
        boolean mappedGlass = facadeMaterial.matches("glass|curtain_wall|glass_wall");
        boolean glass = manila ? mappedGlass : (h >= 40f || (bld.matches("office|commercial|hotel") && h >= 24f));
        GeoBuf buf = glass ? td.bufs[B_GLASS] : td.bufs[B_FACADE];
        float[] fc = buildingColor(tags, id, tabarbour, manila);
        float cr = fc[0], cg = fc[1], cb = fc[2];
        if (glass && !manila) { cr = 0.70f; cg = 0.78f; cb = 0.84f; }
        float rf = 1.0f;
        float rr = manila ? cr : 0.43f * rf, rg = manila ? cg : 0.43f * rf, rb = manila ? cb : 0.43f * rf;
        float[] rc = hex(tg(tags, "roof:colour"));
        if (rc == null) rc = hex(tg(tags, "roof:color"));
        String rmat = tg(tags, "roof:material");
        if (rc != null) { rr = rc[0]; rg = rc[1]; rb = rc[2]; }
        else if (rmat.matches("tiles|roof_tiles|clay")) { rr = 0.62f * rf; rg = 0.30f * rf; rb = 0.22f * rf; }
        else if (rmat.matches("metal|tin|zinc")) { rr = 0.55f * rf; rg = 0.58f * rf; rb = 0.60f * rf; }
        else if (rmat.matches("concrete")) { rr = 0.62f; rg = 0.62f; rb = 0.60f; }
        // Unmapped roof colours remain neutral; do not invent a tile colour.
        float vMax = (yTop - hMin - minHeight + undergroundDepth + 0.08f) / 13.6f, lo = 0.78f;
        // Select the same mapped entrance used by the door renderer, then remove
        // its ground-floor wall rectangle from the facade mesh itself.
        int cutEntranceEdge = 0;
        float cutEntranceLength = 0f, cutEntranceMid = 0.5f;
        boolean cutEntranceMapped = false;
        if (tabarbour) {
            for (int edge = 0; edge < n; edge++) {
                int next = (edge + 1) % n;
                float len = (float)Math.hypot(px[next]-px[edge], pz[next]-pz[edge]);
                if (len > cutEntranceLength) { cutEntranceLength=len; cutEntranceEdge=edge; }
            }
            JSONArray cutPoints = tags.optJSONArray("adam:entrances");
            if (cutPoints != null) {
                float bestDistance = 4.0f;
                for (int ei=0; ei<cutPoints.length(); ei++) {
                    JSONObject entrance=cutPoints.optJSONObject(ei);
                    JSONObject geometry=entrance==null?null:entrance.optJSONObject("geometry");
                    JSONArray coordinates=geometry==null?null:geometry.optJSONArray("coordinates");
                    if(coordinates==null||coordinates.length()<2) continue;
                    double lon=coordinates.optDouble(0,Double.NaN), lat=coordinates.optDouble(1,Double.NaN);
                    if(Double.isNaN(lon)||Double.isNaN(lat)) continue;
                    float ex=Geo.x(lon), ez=Geo.z(lat);
                    for(int edge=0;edge<n;edge++){
                        int next=(edge+1)%n;
                        float dx=px[next]-px[edge], dz=pz[next]-pz[edge], len2=dx*dx+dz*dz;
                        if(len2<0.01f) continue;
                        float t=Math.max(0f,Math.min(1f,((ex-px[edge])*dx+(ez-pz[edge])*dz)/len2));
                        float qx=px[edge]+dx*t, qz=pz[edge]+dz*t;
                        float distance=(float)Math.hypot(ex-qx,ez-qz);
                        if(distance<bestDistance){
                            bestDistance=distance; cutEntranceEdge=edge;
                            cutEntranceLength=(float)Math.sqrt(len2); cutEntranceMid=t; cutEntranceMapped=true;
                        }
                    }
                }
            }
        }
        float minx = 1e9f, maxx = -1e9f, minz = 1e9f, maxz = -1e9f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = px[i], az = pz[i], bx = px[j], bz = pz[j];
            minx = Math.min(minx, ax); maxx = Math.max(maxx, ax);
            minz = Math.min(minz, az); maxz = Math.max(maxz, az);
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 0.05f) continue;
            float u = Math.max(1, Math.round(len / 4f)) / 4f;
            float belowGroundDepth = minHeight >= 2.2f ? 0f : undergroundDepth;
            float yBotA = ground[i] + minHeight - belowGroundDepth - 0.08f;
            float yBotB = ground[j] + minHeight - belowGroundDepth - 0.08f;
            if (tabarbour && i == cutEntranceEdge && len >= 2.5f) {
                float openingWidth = 2.25f;
                float halfOpening = openingWidth / (2f * len);
                float t0 = Math.max(0.02f, cutEntranceMid-halfOpening);
                float t1 = Math.min(0.98f, cutEntranceMid+halfOpening);
                float x0=ax+(bx-ax)*t0, z0=az+(bz-az)*t0;
                float x1=ax+(bx-ax)*t1, z1=az+(bz-az)*t1;
                float bottom0=yBotA+(yBotB-yBotA)*t0, bottom1=yBotA+(yBotB-yBotA)*t1;
                float top0=roofY[i]+(roofY[j]-roofY[i])*t0;
                float top1=roofY[i]+(roofY[j]-roofY[i])*t1;
                float openingTop0=Math.min(bottom0+2.85f,top0-0.20f);
                float openingTop1=Math.min(bottom1+2.85f,top1-0.20f);
                // Left pier, right pier and lintel: the central ground-floor wall is genuinely absent.
                facadeQuad(buf,ax,yBotA,az,x0,bottom0,z0,x0,top0,z0,ax,roofY[i],az,
                        cr*lo,cg*lo,cb*lo);
                facadeQuad(buf,x1,bottom1,z1,bx,yBotB,bz,bx,roofY[j],bz,x1,top1,z1,
                        cr*lo,cg*lo,cb*lo);
                facadeQuad(buf,x0,openingTop0,z0,x1,openingTop1,z1,x1,top1,z1,x0,top0,z0,
                        cr,cg,cb);
            } else {
                buf.v(ax, yBotA, az, 0, vMax, cr * lo, cg * lo, cb * lo);
                buf.v(bx, yBotB, bz, u, vMax, cr * lo, cg * lo, cb * lo);
                buf.v(bx, roofY[j], bz, u, 0, cr, cg, cb);
                buf.fixTri();
                buf.v(ax, yBotA, az, 0, vMax, cr * lo, cg * lo, cb * lo);
                buf.v(bx, roofY[j], bz, u, 0, cr, cg, cb);
                buf.v(ax, roofY[i], az, 0, 0, cr, cg, cb);
                buf.fixTri();
            }
        }
        // Architectural plinth: preserve its mapped height as a distinct stone/concrete base band.
        float plinthHeight = measure(tg(tags, "building:plinth:height"));
        plinthHeight = Math.max(0f, Math.min(h * 0.35f, plinthHeight));
        // Floor count is taken from source. Height-based inference is only a fallback when absent.
        float floors = measure(tg(tags, "building:levels"));
        if (!(floors > 0f)) floors = Math.max(1f, Math.round(h / 3.2f));
        floors = Math.max(1f, Math.min(100f, floors));
        float floorStep = Math.max(2.2f, (wallHeight - minHeight) / floors);
        if (!manila) {
        // Non-Manila plinth and facade-floor-joint geometry.
        for (int edge = 0; edge < n; edge++) {
            int next = (edge + 1) % n;
            float ax = px[edge], az = pz[edge], bx = px[next], bz = pz[next];
            float dx = bx - ax, dz = bz - az, len = (float) Math.hypot(dx, dz);
            if (len < 1f) continue;
            float nx = dz / len, nz = -dx / len;
            if (Poly.contains(px, pz, (ax + bx) * 0.5f + nx, (az + bz) * 0.5f + nz)) { nx = -nx; nz = -nz; }
            // A separate, slightly projecting base course makes the plinth legible.
            if (plinthHeight > 0.15f) {
                float y0a = ground[edge] + minHeight, y0b = ground[next] + minHeight;
                float y1a = y0a + plinthHeight, y1b = y0b + plinthHeight;
                float off = 0.045f;
                float pr = 0.58f, pg = 0.55f, pb = 0.49f;
                buf.v(ax + nx*off,y0a,az+nz*off,0,0,pr,pg,pb); buf.v(bx+nx*off,y0b,bz+nz*off,1,0,pr,pg,pb); buf.v(bx+nx*off,y1b,bz+nz*off,1,1,pr,pg,pb); buf.fixTri();
                buf.v(ax+nx*off,y0a,az+nz*off,0,0,pr,pg,pb); buf.v(bx+nx*off,y1b,bz+nz*off,1,1,pr,pg,pb); buf.v(ax+nx*off,y1a,az+nz*off,0,1,pr,pg,pb); buf.fixTri();
            }
            // Fine horizontal facade joints at actual storey elevations (not a texture overlay).
            float band = Math.min(0.09f, Math.max(0.035f, h / 900f));
            for (int level = 1; level < (int) floors; level++) {
                float ya = ground[edge] + minHeight + floorStep * level;
                float yb = ground[next] + minHeight + floorStep * level;
                if (ya >= roofY[edge] - 0.15f || yb >= roofY[next] - 0.15f) continue;
                float off = 0.035f;
                float shade = 0.70f;
                buf.v(ax+nx*off,ya-band,az+nz*off,0,0,cr*shade,cg*shade,cb*shade);
                buf.v(bx+nx*off,yb-band,bz+nz*off,1,0,cr*shade,cg*shade,cb*shade);
                buf.v(bx+nx*off,yb,bz+nz*off,1,1,cr*shade,cg*shade,cb*shade); buf.fixTri();
                buf.v(ax+nx*off,ya-band,az+nz*off,0,0,cr*shade,cg*shade,cb*shade);
                buf.v(bx+nx*off,yb,bz+nz*off,1,1,cr*shade,cg*shade,cb*shade);
                buf.v(ax+nx*off,ya,az+nz*off,0,1,cr*shade,cg*shade,cb*shade); buf.fixTri();
            }
        }
        }
        // Individually modelled windows are retained only for the non-Manila city profiles.
        if (!manila) {
        int windowBudget = 240;
        for (int edge = 0; edge < n && windowBudget > 0; edge++) {
            int next = (edge + 1) % n;
            float ax = px[edge], az = pz[edge], bx = px[next], bz = pz[next];
            float dx = bx - ax, dz = bz - az, len = (float) Math.hypot(dx, dz);
            if (len < 2.8f) continue;
            float nx = dz / len, nz = -dx / len;
            if (Poly.contains(px, pz, (ax + bx) * 0.5f + nx, (az + bz) * 0.5f + nz)) { nx = -nx; nz = -nz; }
            int perFloor = Math.max(1, Math.min(16, (int) (len / 3.0f)));
            float pitch = len / (perFloor + 1f);
            float ww = Math.min(1.25f, pitch * 0.58f);
            float wh = Math.min(1.25f, Math.max(0.72f, floorStep * 0.48f));
            for (int level = 0; level < (int) floors && windowBudget > 0; level++) {
                float baseA = ground[edge] + minHeight + floorStep * level + Math.max(0.42f, floorStep * 0.18f);
                float baseB = ground[next] + minHeight + floorStep * level + Math.max(0.42f, floorStep * 0.18f);
                float topA = Math.min(baseA + wh, roofY[edge] - 0.18f);
                float topB = Math.min(baseB + wh, roofY[next] - 0.18f);
                if (topA - baseA < 0.48f || topB - baseB < 0.48f) continue;
                for (int wi = 0; wi < perFloor && windowBudget > 0; wi++) {
                    float t = (wi + 1f) / (perFloor + 1f);
                    float half = ww * 0.5f / len;
                    float t0 = Math.max(0.015f, t - half), t1 = Math.min(0.985f, t + half);
                    float x0 = ax + dx * t0 + nx * 0.075f, z0 = az + dz * t0 + nz * 0.075f;
                    float x1 = ax + dx * t1 + nx * 0.075f, z1 = az + dz * t1 + nz * 0.075f;
                    float y0 = baseA + (baseB - baseA) * t0, y1 = baseA + (baseB - baseA) * t1;
                    float yt0 = topA + (topB - topA) * t0, yt1 = topA + (topB - topA) * t1;
                    facadeQuad(td.bufs[B_GLASS], x0,y0,z0, x1,y1,z1, x1,yt1,z1, x0,yt0,z0, 0.20f,0.32f,0.39f);
                    float fr=0.76f, fg=0.75f, fb=0.70f, fw=0.055f;
                    facadeQuad(buf, x0,y0,z0, x1,y1,z1, x1,y1+fw,z1, x0,y0+fw,z0, fr,fg,fb);
                    facadeQuad(buf, x0,yt0-fw,z0, x1,yt1-fw,z1, x1,yt1,z1, x0,yt0,z0, fr,fg,fb);
                    facadeQuad(buf, x0,y0,z0, x0+dx*(t1-t0)*fw/Math.max(0.1f,ww),y1,z0+dz*(t1-t0)*fw/Math.max(0.1f,ww),
                            x0+dx*(t1-t0)*fw/Math.max(0.1f,ww),yt0,z0+dz*(t1-t0)*fw/Math.max(0.1f,ww), x0,yt0,z0, fr,fg,fb);
                    facadeQuad(buf, x1-dx*(t1-t0)*fw/Math.max(0.1f,ww),y1,z1-dz*(t1-t0)*fw/Math.max(0.1f,ww),
                            x1,y1,z1, x1,yt1,z1, x1-dx*(t1-t0)*fw/Math.max(0.1f,ww),yt1,z1-dz*(t1-t0)*fw/Math.max(0.1f,ww), fr,fg,fb);
                    windowBudget--;
                }
            }
        }
        }
        // Commercial ground floors use full-height glazed shopfronts instead of residential-size windows.
        boolean storefront = tabarbour && (!tg(tags, "shop").isEmpty()
                || bld.matches("retail|commercial|supermarket|mall|shop"));
        if (storefront) {
            int shopEdge = 0;
            float shopEdgeLength = 0f;
            for (int edge = 0; edge < n; edge++) {
                int next = (edge + 1) % n;
                float len = (float) Math.hypot(px[next]-px[edge], pz[next]-pz[edge]);
                if (len > shopEdgeLength) { shopEdgeLength=len; shopEdge=edge; }
            }
            if (shopEdgeLength >= 3.2f) {
                int next=(shopEdge+1)%n;
                float ax=px[shopEdge], az=pz[shopEdge], bx=px[next], bz=pz[next];
                float dx=bx-ax, dz=bz-az, len=shopEdgeLength;
                float nx=dz/len, nz=-dx/len;
                if (Poly.contains(px,pz,(ax+bx)*0.5f+nx,(az+bz)*0.5f+nz)) { nx=-nx; nz=-nz; }
                int bays=Math.max(1,Math.min(10,(int)(len/3.1f)));
                float pitch=len/bays;
                float y0=(ground[shopEdge]+ground[next])*0.5f+minHeight+0.18f;
                float y1=Math.min(y0+2.55f,Math.min(roofY[shopEdge],roofY[next])-0.22f);
                for(int bay=0;bay<bays;bay++){
                    // Leave the central shopfront bay open for the recessed entrance door.
                    if (bays > 1 && bay == bays/2) continue;
                    float t0=(bay+0.10f)/bays, t1=(bay+0.90f)/bays;
                    float x0=ax+dx*t0+nx*0.11f, z0=az+dz*t0+nz*0.11f;
                    float x1=ax+dx*t1+nx*0.11f, z1=az+dz*t1+nz*0.11f;
                    if(y1-y0<1.6f) continue;
                    facadeQuad(td.bufs[B_GLASS],x0,y0,z0,x1,y0,z1,x1,y1,z1,x0,y1,z0,
                            0.22f,0.39f,0.45f);
                    float fw=0.055f;
                    facadeQuad(buf,x0,y0,z0,x1,y0,z1,x1,y0+fw,z1,x0,y0+fw,z0,0.16f,0.18f,0.19f);
                    facadeQuad(buf,x0,y1-fw,z0,x1,y1-fw,z1,x1,y1,z1,x0,y1,z0,0.16f,0.18f,0.19f);
                    float mx=(x0+x1)*0.5f, mz=(z0+z1)*0.5f;
                    buf.box(mx,y0+(y1-y0)*0.5f,mz,0.045f,y1-y0,0.045f,0.18f,0.20f,0.21f);
                }
            }
        }

        // A ground-floor entrance is modelled on the longest street-facing facade.
        // Its position is a visual fallback because most source footprints do not map door coordinates.
        int doorEdge = 0;
        float doorEdgeLength = 0f;
        for (int edge = 0; edge < n; edge++) {
            int next = (edge + 1) % n;
            float len = (float) Math.hypot(px[next] - px[edge], pz[next] - pz[edge]);
            if (len > doorEdgeLength) { doorEdgeLength = len; doorEdge = edge; }
        }
        float entranceMid = 0.5f;
        boolean mappedEntrance = false;
        JSONArray mappedEntrances = tags.optJSONArray("adam:entrances");
        if (tabarbour && mappedEntrances != null) {
            float bestDistance = 4.0f;
            for (int ei = 0; ei < mappedEntrances.length(); ei++) {
                JSONObject entrance = mappedEntrances.optJSONObject(ei);
                JSONObject geometry = entrance == null ? null : entrance.optJSONObject("geometry");
                JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
                if (coordinates == null || coordinates.length() < 2) continue;
                double lon = coordinates.optDouble(0, Double.NaN);
                double lat = coordinates.optDouble(1, Double.NaN);
                if (Double.isNaN(lon) || Double.isNaN(lat)) continue;
                float ex = Geo.x(lon), ez = Geo.z(lat);
                for (int edge = 0; edge < n; edge++) {
                    int next = (edge + 1) % n;
                    float dx = px[next] - px[edge], dz = pz[next] - pz[edge];
                    float len2 = dx * dx + dz * dz;
                    if (len2 < 0.01f) continue;
                    float t = Math.max(0f, Math.min(1f,
                            ((ex - px[edge]) * dx + (ez - pz[edge]) * dz) / len2));
                    float qx = px[edge] + dx * t, qz = pz[edge] + dz * t;
                    float distance = (float) Math.hypot(ex - qx, ez - qz);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        doorEdge = edge;
                        doorEdgeLength = (float) Math.sqrt(len2);
                        entranceMid = t;
                        mappedEntrance = true;
                    }
                }
            }
        }
        if ((!manila || !tg(tags, "entrance").isEmpty() || !tg(tags, "door").isEmpty())
                && doorEdgeLength >= 2.2f && h >= 2.4f) {
            int next = (doorEdge + 1) % n;
            float ax = px[doorEdge], az = pz[doorEdge], bx = px[next], bz = pz[next];
            float dx = bx - ax, dz = bz - az;
            float nx = dz / doorEdgeLength, nz = -dx / doorEdgeLength;
            if (Poly.contains(px, pz, (ax + bx) * 0.5f + nx, (az + bz) * 0.5f + nz)) { nx = -nx; nz = -nz; }
            float doorWidth = measure(tg(tags, "door:width"));
            if (!(doorWidth >= 0.65f && doorWidth <= 2.2f)) doorWidth = 0.95f;
            float doorHeight = measure(tg(tags, "door:height"));
            if (!(doorHeight >= 1.7f && doorHeight <= 3.2f)) doorHeight = Math.min(2.15f, h - 0.25f);
            float mid = mappedEntrance ? entranceMid : 0.5f;
            float half = doorWidth / (2f * doorEdgeLength);
            float t0 = Math.max(0.02f, mid - half), t1 = Math.min(0.98f, mid + half);
            float yBase = (ground[doorEdge] + ground[next]) * 0.5f + minHeight;
            float doorPlaneOffset = tabarbour ? -0.30f : 0.085f;
            float x0 = ax + dx * t0 + nx * doorPlaneOffset, z0 = az + dz * t0 + nz * doorPlaneOffset;
            float x1 = ax + dx * t1 + nx * doorPlaneOffset, z1 = az + dz * doorPlaneOffset;
            // Recessed apartment entrance: dark inner wall, stone side returns and a door set back from the facade.
            if (tabarbour) {
                float recessWidth = Math.max(2.25f, doorWidth + 1.30f);
                float recessDepth = 0.42f;
                float rHalf = recessWidth / (2f * doorEdgeLength);
                float rt0 = Math.max(0.015f, mid-rHalf), rt1 = Math.min(0.985f, mid+rHalf);
                float fx0 = ax+dx*rt0+nx*0.10f, fz0=az+dz*rt0+nz*0.10f;
                float fx1 = ax+dx*rt1+nx*0.10f, fz1=az+dz*rt1+nz*0.10f;
                float ix0 = ax+dx*rt0-nx*recessDepth, iz0=az+dz*rt0-nz*recessDepth;
                float ix1 = ax+dx*rt1-nx*recessDepth, iz1=az+dz*rt1-nz*recessDepth;
                float top = yBase + Math.min(2.85f, Math.max(2.55f, doorHeight+0.35f));
                facadeQuad(buf, fx0,yBase,fz0, ix0,yBase,iz0, ix0,top,iz0, fx0,top,fz0, 0.56f,0.52f,0.45f);
                facadeQuad(buf, ix1,yBase,iz1, fx1,yBase,fz1, fx1,top,fz1, ix1,top,iz1, 0.56f,0.52f,0.45f);
                facadeQuad(buf, fx0,top,fz0, ix0,top,iz0, ix1,top,iz1, fx1,top,fz1, 0.68f,0.64f,0.57f);
                facadeQuad(buf, ix0,yBase,iz0, ix1,yBase,iz1, ix1,top,iz1, ix0,top,iz0, 0.16f,0.18f,0.19f);
                // Two open ground-floor terrace bays flank the central recessed entrance.
                float terraceDepth = 0.82f;
                for (int side=0; side<2; side++) {
                    float ta = side==0 ? 0.025f : rt1;
                    float tb = side==0 ? rt0 : 0.975f;
                    if ((tb-ta)*doorEdgeLength < 1.0f) continue;
                    float ax0=ax+dx*ta+nx*0.10f, az0=az+dz*ta+nz*0.10f;
                    float bx0=ax+dx*tb+nx*0.10f, bz0=az+dz*tb+nz*0.10f;
                    float ax1=ax0+nx*terraceDepth, az1=az0+nz*terraceDepth;
                    float bx1=bx0+nx*terraceDepth, bz1=bz0+nz*terraceDepth;
                    float slabY=yBase+0.08f;
                    facadeQuad(buf,ax0,slabY,az0,bx0,slabY,bz0,bx1,slabY,bz1,ax1,slabY,az1,
                            0.66f,0.61f,0.53f);
                    // Low open railings define each apartment terrace without closing the entrance.
                    facadeQuad(buf,ax1,slabY,az1,bx1,slabY,bz1,bx1,slabY+0.88f,bz1,ax1,slabY+0.88f,az1,
                            0.34f,0.36f,0.36f);
                    buf.box(ax1,slabY+0.44f,az1,0.08f,0.88f,0.08f,0.34f,0.36f,0.36f);
                    buf.box(bx1,slabY+0.44f,bz1,0.08f,0.88f,0.08f,0.34f,0.36f,0.36f);
                }
            }
            float frame = 0.09f;
            facadeQuad(buf, x0 - dx * frame / doorEdgeLength, yBase, z0 - dz * frame / doorEdgeLength,
                    x1 + dx * frame / doorEdgeLength, yBase, z1 + dz * frame / doorEdgeLength,
                    x1 + dx * frame / doorEdgeLength, yBase + doorHeight + frame, z1 + dz * frame / doorEdgeLength,
                    x0 - dx * frame / doorEdgeLength, yBase + doorHeight + frame, z0 - dz * frame / doorEdgeLength,
                    0.78f, 0.73f, 0.64f);
            float dr = tabarbour ? 0.12f : 0.30f, dg = tabarbour ? 0.15f : 0.20f, db = tabarbour ? 0.16f : 0.13f;
            float[] doorColor = hex(tg(tags, "door:colour"));
            if (doorColor == null) doorColor = hex(tg(tags, "door:color"));
            if (doorColor != null) { dr = doorColor[0]; dg = doorColor[1]; db = doorColor[2]; }
            facadeQuad(buf, x0, yBase + 0.04f, z0, x1, yBase + 0.04f, z1,
                    x1, yBase + doorHeight, z1, x0, yBase + doorHeight, z0, dr, dg, db);
            if (tabarbour && doorHeight > 1.85f) {
                float glassBottom = yBase + doorHeight * 0.38f;
                float inset = Math.min(0.12f, doorWidth * 0.12f);
                facadeQuad(td.bufs[B_GLASS], x0 + dx * inset / doorEdgeLength, glassBottom, z0 + dz * inset / doorEdgeLength,
                        x1 - dx * inset / doorEdgeLength, glassBottom, z1 - dz * inset / doorEdgeLength,
                        x1 - dx * inset / doorEdgeLength, yBase + doorHeight - 0.12f, z1 - dz * inset / doorEdgeLength,
                        x0 + dx * inset / doorEdgeLength, yBase + doorHeight - 0.12f, z0 + dz * inset / doorEdgeLength,
                        0.76f, 0.84f, 0.86f);
                buf.box((x0 + x1) * 0.5f, yBase + doorHeight * 0.68f, (z0 + z1) * 0.5f,
                        0.045f, doorHeight * 0.60f, 0.045f, 0.12f, 0.14f, 0.15f);
            }
            float handleX = ax + dx * (mid + half * 0.68f) + nx * (doorPlaneOffset + 0.02f);
            float handleZ = az + dz * (mid + half * 0.68f) + nz * (doorPlaneOffset + 0.02f);
            buf.box(handleX, yBase + doorHeight * 0.48f, handleZ, 0.055f, 0.11f, 0.055f, 0.78f, 0.69f, 0.45f);
        }
        // Basement storey joints are modelled below ground and become visible in cutaway mode.
        float basementFloors = measure(tg(tags, "building:levels:underground"));
        if (basementFloors > 0f && undergroundDepth > 0f) {
            int count = Math.max(1, Math.min(20, Math.round(basementFloors)));
            float basementStep = undergroundDepth / count;
            for (int edge = 0; edge < n; edge++) {
                int next = (edge + 1) % n;
                float ax = px[edge], az = pz[edge], bx = px[next], bz = pz[next];
                float dx = bx - ax, dz = bz - az, len = (float) Math.hypot(dx, dz);
                if (len < 1f) continue;
                float nx = dz / len, nz = -dx / len;
                if (Poly.contains(px, pz, (ax + bx) * 0.5f + nx, (az + bz) * 0.5f + nz)) { nx = -nx; nz = -nz; }
                for (int level = 1; level < count; level++) {
                    float ya = ground[edge] + minHeight - basementStep * level;
                    float yb = ground[next] + minHeight - basementStep * level;
                    float off = 0.055f, band = 0.10f;
                    float br = 0.34f, bg = 0.37f, bb = 0.39f;
                    buf.v(ax+nx*off,ya-band,az+nz*off,0,0,br,bg,bb);
                    buf.v(bx+nx*off,yb-band,bz+nz*off,1,0,br,bg,bb);
                    buf.v(bx+nx*off,yb,bz+nz*off,1,1,br,bg,bb); buf.fixTri();
                    buf.v(ax+nx*off,ya-band,az+nz*off,0,0,br,bg,bb);
                    buf.v(bx+nx*off,yb,bz+nz*off,1,1,br,bg,bb);
                    buf.v(ax+nx*off,ya,az+nz*off,0,1,br,bg,bb); buf.fixTri();
                }
            }
        }
        // Concrete basement slabs make underground storeys readable when terrain cutaway is enabled.
        if (basementFloors > 0f && undergroundDepth > 0f) {
            int count = Math.max(1, Math.min(20, Math.round(basementFloors)));
            int[] basementTriangles = Poly.earClip(px, pz);
            for (int level = 1; level <= count; level++) {
                float depth = undergroundDepth * level / count;
                for (int tri = 0; tri + 2 < basementTriangles.length; tri += 3) {
                    for (int k = 0; k < 3; k++) {
                        int vi = basementTriangles[tri + k];
                        float y = ground[vi] + minHeight - depth;
                        buf.v(px[vi], y, pz[vi], 0.01f, 0.01f, 0.43f, 0.45f, 0.46f);
                    }
                    buf.fixTri();
                }
            }
        }
        float parea = Math.abs(Poly.area(px, pz));
        boolean pyramid = (rshape.equals("pyramidal") || rshape.equals("hipped")
                || (!manila && rshape.isEmpty() && bld.matches("house|detached|semidetached_house|bungalow") && rnd(id, 10) < 0.6f))
                && n <= 8 && parea < 700f && h < 14f;
        if (pyramid) {
            float ax0 = 0, az0 = 0;
            for (int i = 0; i < n; i++) { ax0 += px[i]; az0 += pz[i]; }
            ax0 /= n; az0 /= n;
            float rh = roofRise > 0f ? roofRise : Math.min(3.2f, (float) Math.sqrt(parea) / 4f);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                buf.v(px[i], roofY[i], pz[i], 0.01f, 0.01f, rr, rg, rb);
                buf.v(px[j], roofY[j], pz[j], 0.01f, 0.01f, rr, rg, rb);
                float centerGround = 0f;
                for (int k = 0; k < n; k++) centerGround += ground[k];
                centerGround /= n;
                buf.v(ax0, centerGround + h + rh, az0, 0.01f, 0.01f, rr * 1.1f, rg * 1.1f, rb * 1.1f);
                buf.fixTri();
            }
        } else if (rshape.equals("gabled") || rshape.equals("skillion")) {
            // Fit the roof ridge/slope to the footprint's principal direction.
            double mx = 0d, mz = 0d;
            for (int i = 0; i < n; i++) { mx += px[i]; mz += pz[i]; }
            mx /= n; mz /= n;
            double xx = 0d, zz = 0d, xz = 0d;
            for (int i = 0; i < n; i++) {
                double dx = px[i] - mx, dz = pz[i] - mz;
                xx += dx * dx; zz += dz * dz; xz += dx * dz;
            }
            double angle = 0.5d * Math.atan2(2d * xz, xx - zz);
            double nx = -Math.sin(angle), nz = Math.cos(angle);
            float[] across = new float[n];
            float minAcross = Float.POSITIVE_INFINITY, maxAcross = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                across[i] = (float) (nx * (px[i] - mx) + nz * (pz[i] - mz));
                minAcross = Math.min(minAcross, across[i]); maxAcross = Math.max(maxAcross, across[i]);
            }
            float rise = roofRise > 0f ? roofRise : 2.0f;
            float span = Math.max(0.01f, maxAcross - minAcross);
            int[] tr = Poly.earClip(px, pz);
            for (int i = 0; i + 2 < tr.length; i += 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = tr[i + k];
                    float t = (across[vi] - minAcross) / span;
                    float roofFactor = rshape.equals("gabled") ? 1f - Math.abs(2f * t - 1f) : t;
                    buf.v(px[vi], roofY[vi] + rise * roofFactor, pz[vi], 0.01f, 0.01f, rr, rg, rb);
                }
                buf.fixTri();
            }
        } else {
            int[] tr = Poly.earClip(px, pz);
            for (int i = 0; i + 2 < tr.length; i += 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = tr[i + k];
                    buf.v(px[vi], roofY[vi], pz[vi], 0.01f, 0.01f, rr, rg, rb);
                }
                buf.fixTri();
            }
        }
        float mxc = 0, mzc = 0;
        for (int i = 0; i < n; i++) { mxc += px[i]; mzc += pz[i]; }
        // No random billboard panels: Manila signage must come from mapped names or verified photo evidence.
        Footprint f = new Footprint();
        f.xs = px; f.zs = pz; f.minx = minx; f.maxx = maxx; f.minz = minz; f.maxz = maxz;
        // Elevated building parts/overhangs are visible above the street but must not block ground traffic.
        if (minHeight < 2.2f) Collision.add(td.grid, f);
    }

    /** لوحات إعلانية مضيئة على واجهات المباني قرب تايمز سكوير. */
    private static void addSigns(TileData td, float[] px, float[] pz, float h, float baseY, long id) {
        GeoBuf sb = td.bufs[B_SIGN];
        int count = 0, n = px.length;
        for (int i = 0; i < n && count < 3; i++) {
            int j = (i + 1) % n;
            float ax = px[i], az = pz[i], bx = px[j], bz = pz[j];
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 12f) continue;
            float dx = (bx - ax) / len, dz = (bz - az) / len;
            float nx = dz, nz = -dx;
            if (Poly.contains(px, pz, (ax + bx) / 2f + nx, (az + bz) / 2f + nz)) { nx = -nx; nz = -nz; }
            float y0 = 6f + rnd(id, 20 + i) * 2f;
            float y1 = Math.min(h - 3f, y0 + 16f + rnd(id, 30 + i) * 14f);
            if (y1 - y0 < 6f) continue;
            y0 += baseY; y1 += baseY;
            float o = 0.35f, sh = len * 0.05f;
            float x0 = ax + dx * sh + nx * o, z0 = az + dz * sh + nz * o;
            float x1 = bx - dx * sh + nx * o, z1 = bz - dz * sh + nz * o;
            int cell = (int) (rnd(id, 40 + i) * 16) % 16;
            float u0 = (cell % 4) / 4f + 0.004f, u1 = u0 + 0.242f, v0 = (cell / 4) / 4f + 0.004f, v1 = v0 + 0.242f;
            sb.v(x0, y0, z0, u0, v1, 1, 1, 1); sb.v(x1, y0, z1, u1, v1, 1, 1, 1); sb.v(x1, y1, z1, u1, v0, 1, 1, 1); sb.fixTri();
            sb.v(x0, y0, z0, u0, v1, 1, 1, 1); sb.v(x1, y1, z1, u1, v0, 1, 1, 1); sb.v(x0, y1, z0, u0, v0, 1, 1, 1); sb.fixTri();
            count++;
        }
    }

    private static void addTree(GeoBuf b, float x, float y0, float z, float s, Random rng) {
        b.box(x, y0 + 1.3f * s, z, 0.28f * s, 2.6f * s, 0.28f * s, 0.35f, 0.24f, 0.16f);
        float cy = y0 + 3.7f * s, r = 2.0f * s;
        float gr = 0.2f + rng.nextFloat() * 0.15f, gg = 0.42f + rng.nextFloat() * 0.18f, gb = 0.16f + rng.nextFloat() * 0.08f;
        float ty = cy + 2.2f * s, by = cy - 1.4f * s;
        b.tri(x, ty, z, x + r, cy, z, x, cy, z + r, gr, gg, gb);
        b.tri(x, ty, z, x, cy, z + r, x - r, cy, z, gr, gg, gb);
        b.tri(x, ty, z, x - r, cy, z, x, cy, z - r, gr, gg, gb);
        b.tri(x, ty, z, x, cy, z - r, x + r, cy, z, gr, gg, gb);
        b.tri(x, by, z, x, cy, z + r, x + r, cy, z, gr * .8f, gg * .8f, gb * .8f);
        b.tri(x, by, z, x - r, cy, z, x, cy, z + r, gr * .8f, gg * .8f, gb * .8f);
        b.tri(x, by, z, x, cy, z - r, x - r, cy, z, gr * .8f, gg * .8f, gb * .8f);
        b.tri(x, by, z, x + r, cy, z, x, cy, z - r, gr * .8f, gg * .8f, gb * .8f);
    }

    /* ---------- الطرق والسواحل ---------- */
    private static boolean clipSeg(float[] s, float x0, float x1, float z0, float z1) {
        float dx = s[2] - s[0], dz = s[3] - s[1];
        float t0 = 0, t1 = 1;
        float[] p = {-dx, dx, -dz, dz};
        float[] q = {s[0] - x0, x1 - s[0], s[1] - z0, z1 - s[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return false; }
            else {
                float t = q[i] / p[i];
                if (p[i] < 0) { if (t > t1) return false; if (t > t0) t0 = t; }
                else { if (t < t0) return false; if (t < t1) t1 = t; }
            }
        }
        float ax = s[0] + t0 * dx, az = s[1] + t0 * dz, bx = s[0] + t1 * dx, bz = s[1] + t1 * dz;
        s[0] = ax; s[1] = az; s[2] = bx; s[3] = bz;
        return true;
    }

    private static ArrayList<ArrayList<float[]>> clipLine(float[] xs, float[] zs, float xMin, float xMax, float zMin, float zMax) {
        ArrayList<float[]> cur = new ArrayList<>();
        ArrayList<ArrayList<float[]>> pieces = new ArrayList<>();
        float[] seg = new float[4];
        for (int i = 0; i + 1 < xs.length; i++) {
            seg[0] = xs[i]; seg[1] = zs[i]; seg[2] = xs[i + 1]; seg[3] = zs[i + 1];
            if (!clipSeg(seg, xMin, xMax, zMin, zMax)) {
                if (cur.size() > 1) pieces.add(cur);
                cur = new ArrayList<>();
                continue;
            }
            if (cur.isEmpty()) {
                cur.add(new float[]{seg[0], seg[1]});
            } else {
                float[] last = cur.get(cur.size() - 1);
                if (Math.hypot(last[0] - seg[0], last[1] - seg[1]) > 0.05) {
                    if (cur.size() > 1) pieces.add(cur);
                    cur = new ArrayList<>();
                    cur.add(new float[]{seg[0], seg[1]});
                }
            }
            cur.add(new float[]{seg[2], seg[3]});
        }
        if (cur.size() > 1) pieces.add(cur);
        return pieces;
    }

    private static float[][] toArrays(ArrayList<float[]> pc) {
        ArrayList<float[]> pts = new ArrayList<>();
        for (float[] p : pc) {
            if (pts.isEmpty() || Math.hypot(p[0] - pts.get(pts.size() - 1)[0], p[1] - pts.get(pts.size() - 1)[1]) > 0.3) pts.add(p);
        }
        if (pts.size() < 2) return null;
        float[] px = new float[pts.size()], pz = new float[pts.size()];
        for (int i = 0; i < px.length; i++) { px[i] = pts.get(i)[0]; pz[i] = pts.get(i)[1]; }
        return new float[][]{px, pz};
    }

    /** يقسّم الخط إلى مقاطع قصيرة ليتبع التضاريس. */
    private static float[][] resample(float[] px, float[] pz, float maxLen) {
        ArrayList<float[]> o = new ArrayList<>();
        for (int i = 0; i + 1 < px.length; i++) {
            float len = (float) Math.hypot(px[i + 1] - px[i], pz[i + 1] - pz[i]);
            int k = Math.max(1, (int) Math.ceil(len / maxLen));
            for (int s = 0; s < k; s++) {
                float t = s / (float) k;
                o.add(new float[]{px[i] + (px[i + 1] - px[i]) * t, pz[i] + (pz[i + 1] - pz[i]) * t});
            }
        }
        o.add(new float[]{px[px.length - 1], pz[pz.length - 1]});
        float[] rx = new float[o.size()], rz = new float[o.size()];
        for (int i = 0; i < rx.length; i++) { rx[i] = o.get(i)[0]; rz[i] = o.get(i)[1]; }
        return new float[][]{rx, rz};
    }

    private static void addCoastSand(TileData td, float[] xs, float[] zs, float xMin, float xMax, float zMin, float zMax) {
        // الأرض على يسار اتجاه الخط في OSM؛ شريط الرمل يُزاح نحو اليابسة
        for (ArrayList<float[]> pc : clipLine(xs, zs, xMin, xMax, zMin, zMax)) {
            float[][] a = toArrays(pc);
            if (a == null) continue;
            float[][] r = resample(a[0], a[1], 8f);
            int n = r[0].length;
            float[] sx = new float[n], sz = new float[n];
            for (int i = 0; i < n; i++) {
                int p = Math.max(0, i - 1), q = Math.min(n - 1, i + 1);
                float dx = r[0][q] - r[0][p], dz = r[1][q] - r[1][p];
                float d = (float) Math.hypot(dx, dz);
                if (d < 1e-6f) d = 1;
                dx /= d; dz /= d;
                sx[i] = r[0][i] + dz * 7f; sz[i] = r[1][i] - dx * 7f; // يسار الاتجاه
            }
            ribbon(sx, sz, 14f, 0.14f, td.bufs[B_PLAIN], 1f, 12f, 0.86f, 0.78f, 0.60f);
        }
    }

    private static float sumLaneWidths(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0f;
        float total = 0f;
        String[] parts = raw.trim().split("[|;]");
        for (String part : parts) {
            float width = measure(part);
            if (width > 0.5f && width <= 12f) total += width;
        }
        return total;
    }

    private static void addRoad(TileData td, JSONObject tags, String hw, String name, HashSet<String> names, float[] xs, float[] zs,
                                float xMin, float xMax, float zMin, float zMax, boolean tabarbour) {
        // Resolve width independently for each mapped road segment. A road that
        // changes from one to two to four lanes therefore changes width at the
        // actual source segment boundary instead of receiving one city-wide width.
        float w = RW.getOrDefault(hw, 4.0f);
        float sourceWidth = measure(tg(tags, "width"));
        if (!(sourceWidth > 0.5f && sourceWidth <= 120f)) sourceWidth = measure(tg(tags, "width:carriageway"));
        if (!(sourceWidth > 0.5f && sourceWidth <= 120f)) sourceWidth = measure(tg(tags, "est_width"));
        if (!(sourceWidth > 0.5f && sourceWidth <= 120f)) {
            sourceWidth = sumLaneWidths(tg(tags, "width:lanes"));
            if (!(sourceWidth > 0.5f && sourceWidth <= 120f))
                sourceWidth = sumLaneWidths(tg(tags, "width:lanes:forward")) + sumLaneWidths(tg(tags, "width:lanes:backward"));
        }
        float laneWidth = hw.matches("motorway|motorway_link|trunk|trunk_link") ? 3.5f
                : hw.matches("primary|primary_link|secondary|secondary_link|tertiary|tertiary_link") ? 3.25f
                : hw.matches("footway|path|steps|cycleway|pedestrian") ? 1.8f : 3.0f;
        if (!(sourceWidth > 0.5f && sourceWidth <= 120f)) {
            float lanes = measure(tg(tags, "lanes"));
            if (!(lanes > 0f && lanes <= 12f)) {
                float forward = measure(tg(tags, "lanes:forward"));
                float backward = measure(tg(tags, "lanes:backward"));
                float bothWays = measure(tg(tags, "lanes:both_ways"));
                lanes = (forward > 0f && forward <= 12f ? forward : 0f)
                        + (backward > 0f && backward <= 12f ? backward : 0f)
                        + (bothWays > 0f && bothWays <= 2f ? bothWays : 0f);
            }
            if (lanes > 0f && lanes <= 12f) sourceWidth = lanes * laneWidth;
        }
        if (sourceWidth > 0.5f && sourceWidth <= 120f) w = sourceWidth;
        String surface = tg(tags, "surface").toLowerCase(java.util.Locale.US);
        boolean bridge = !tg(tags, "bridge").isEmpty() && !tg(tags, "bridge").equals("no");
        boolean tunnel = !tg(tags, "tunnel").isEmpty() && !tg(tags, "tunnel").equals("no");
        boolean veh = w >= 4.5f && !hw.equals("pedestrian");
        boolean labelOnly = tg(tags, "adam:label_only").equals("yes");
        for (ArrayList<float[]> pc : clipLine(xs, zs, xMin, xMax, zMin, zMax)) {
            float[][] a = toArrays(pc);
            if (a == null) continue;
            float[][] r = resample(a[0], a[1], 8f);
            float[] px = r[0], pz = r[1];
            if (labelOnly) {
                if (!name.isEmpty() && px.length >= 2) {
                    RoadPath labelPath = new RoadPath(px, pz, 1.0f, false);
                    float[] labelPoint = new float[4];
                    addDecal(td, labelPath, labelPath.total * 0.5f, name, labelPoint);
                }
                continue;
            }
            boolean footOrCycle = hw.matches("footway|path|steps|cycleway|pedestrian|bridleway");
            float deckOffset = bridgeDeckOffset(tags, bridge, tunnel, footOrCycle);
            float yOffset = 0.22f + deckOffset;
            // Asphalt is the default urban carriageway; mapped surface tags override it.
            float rr = bridge ? 0.92f : 1f, rg = bridge ? 0.93f : 1f, rb = bridge ? 0.95f : 1f;
            if (tunnel) { rr = 0.48f; rg = 0.49f; rb = 0.51f; }
            if (surface.matches("asphalt|chipseal|bituminous")) { rr = 1f; rg = 1f; rb = 1f; }
            else if (surface.matches("gravel|fine_gravel|ground|dirt|earth|unpaved|compacted")) { rr = 0.78f; rg = 0.70f; rb = 0.56f; }
            else if (surface.matches("concrete|paving_stones|sett|cobblestone|paved")) { rr = 0.82f; rg = 0.82f; rb = 0.78f; }
            else if (surface.matches("sand|mud")) { rr = 0.88f; rg = 0.77f; rb = 0.58f; }
            else if (surface.matches("grass|grass_paver")) { rr = 0.52f; rg = 0.64f; rb = 0.43f; }
            else if (surface.matches("wood|metal")) { rr = 0.62f; rg = 0.59f; rb = 0.54f; }
            if (w >= 6f || hw.equals("service")) {
                if (!bridge && !tunnel) ribbon(px, pz, w + 4.2f, 0.20f, td.bufs[B_SIDEWALK], (w + 4.2f) / 2f, 2f, 1f, 1f, 1f);
                GeoBuf roadBuf = (w >= 7f ? td.bufs[B_ROADW] : td.bufs[B_ROADN]);
                ribbonOffset(px, pz, w, yOffset, roadBuf, 1f, 12f, rr, rg, rb);
                if (bridge) addBridgeParapets(px, pz, w, yOffset, roadBuf, footOrCycle ? 1.15f : 0.85f);
                if (tabarbour && hw.matches("residential|living_street|unclassified|service")) {
                    long roadSeed = (((long) Math.round(px[0] * 10f)) << 32) ^ (long) Math.round(pz[0] * 10f);
                    addTabarbourSpeedBumps(roadBuf, px, pz, w, roadSeed);
                }
            } else {
                ribbonOffset(px, pz, w, yOffset, td.bufs[B_SIDEWALK], w / 2f, 2f, rr, rg, rb);
            }
            RoadPath rp = new RoadPath(px, pz, w, veh);
            rp.name = name;
            if (rp.total > 20f) td.paths.add(rp);
            // Label every mapped named street segment; label-only vector-tile
            // features are handled above and never create a duplicate road surface.
            if (!name.isEmpty() && rp.total >= 12f) {
                names.add(name);
                float[] o = new float[4];
                addDecal(td, rp, rp.total * 0.5f, name, o);
                for (float s = 125f; s < rp.total - 8f; s += 150f) addDecal(td, rp, s, name, o);
            }
        }
    }

    /** Side parapets make elevated road, rail and pedestrian bridge decks legible. */
    private static void addBridgeParapets(float[] px, float[] pz, float width, float yOffset,
                                          GeoBuf target, float railHeight) {
        if (px.length < 2 || width < 1f) return;
        float half = width * 0.5f + 0.06f;
        float r = 0.43f, g = 0.45f, b = 0.47f;
        for (int i = 0; i < px.length - 1; i++) {
            float dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i];
            float len = (float) Math.hypot(dx, dz);
            if (len < 0.1f) continue;
            float nx = -dz / len, nz = dx / len;
            for (int side : new int[]{-1, 1}) {
                float ox = nx * half * side, oz = nz * half * side;
                float x0 = px[i] + ox, z0 = pz[i] + oz;
                float x1 = px[i + 1] + ox, z1 = pz[i + 1] + oz;
                float y0 = Terrain.h(x0, z0) + yOffset;
                float y1 = Terrain.h(x1, z1) + yOffset;
                target.v(x0,y0,z0,0,0,r,g,b);
                target.v(x1,y1,z1,1,0,r,g,b);
                target.v(x1,y1+railHeight,z1,1,1,r,g,b); target.fixTri();
                target.v(x0,y0,z0,0,0,r,g,b);
                target.v(x1,y1+railHeight,z1,1,1,r,g,b);
                target.v(x0,y0+railHeight,z0,0,1,r,g,b); target.fixTri();
            }
        }
    }

    /** Raised, low-profile speed humps on Tabarbour residential/service streets only. */
    private static void addTabarbourSpeedBumps(GeoBuf b, float[] xs, float[] zs, float width, long id) {
        RoadPath path = new RoadPath(xs, zs, width, false);
        if (path.total < 48f || width < 3.2f) return;
        float spacing = 58f + rnd(id, 89) * 28f;
        float[] left = new float[4], right = new float[4];
        float[] offsets = {-0.55f, -0.22f, 0.22f, 0.55f};
        float[] rises = {0f, 0.075f, 0.075f, 0f};
        for (float distance = spacing * (0.35f + rnd(id, 90) * 0.25f);
             distance < path.total - 10f; distance += spacing) {
            for (int i = 0; i < offsets.length; i++) {
                path.posAt(distance + offsets[i], -width * 0.5f, left);
                path.posAt(distance + offsets[i], width * 0.5f, right);
                float yl = Terrain.h(left[0], left[1]) + 0.22f + rises[i];
                float yr = Terrain.h(right[0], right[1]) + 0.22f + rises[i];
                left[2] = yl; right[2] = yr;
            }
            for (int i = 0; i < offsets.length - 1; i++) {
                float v0 = distance / 12f + offsets[i] / 12f;
                float v1 = distance / 12f + offsets[i + 1] / 12f;
                b.v(left[0], left[2], left[1], 0f, v0, 0.96f, 0.96f, 0.94f);
                b.v(right[0], right[2], right[1], 1f, v0, 0.96f, 0.96f, 0.94f);
                b.v(left[0], left[2], left[1], 0f, v1, 0.96f, 0.96f, 0.94f);
                b.fixTri();
                b.v(right[0], right[2], right[1], 1f, v0, 0.96f, 0.96f, 0.94f);
                b.v(right[0], right[2], right[1], 1f, v1, 0.96f, 0.96f, 0.94f);
                b.v(left[0], left[2], left[1], 0f, v1, 0.96f, 0.96f, 0.94f);
                b.fixTri();
            }
        }
    }

    private static void addClippedRibbon(TileData td,float[] xs,float[] zs,float width,float yOffset,GeoBuf target,float r,float g,float b) {
        double west=td.ix*Geo.dLon,east=(td.ix+1)*Geo.dLon;
        double south=td.iz*Geo.D_LAT,north=(td.iz+1)*Geo.D_LAT;
        for(ArrayList<float[]> piece:clipLine(xs,zs,Geo.x(west),Geo.x(east),Geo.z(north),Geo.z(south))){
            float[][] a=toArrays(piece); if(a==null)continue;
            float[][] pc=resample(a[0],a[1],8f);
            ribbon(pc[0],pc[1],width,yOffset,target,1f,12f,r,g,b);
        }
    }

    private static boolean isWaterUtility(JSONObject tags) {
        String man=tg(tags,"man_made"), waterway=tg(tags,"waterway"), substance=tg(tags,"substance"), utility=tg(tags,"utility");
        return man.matches("pipeline|water_works|water_tower|wastewater_plant|pumping_station|reservoir")
                || waterway.matches("drain|ditch|sewer")
                || utility.matches("water|sewer|sanitary|stormwater")
                || substance.matches("water|sewage|wastewater");
    }

    private static void addWaterUtilityLine(TileData td, JSONObject tags, float[] xs, float[] zs) {
        double west=td.ix*Geo.dLon, east=(td.ix+1)*Geo.dLon;
        double south=td.iz*Geo.D_LAT, north=(td.iz+1)*Geo.D_LAT;
        ArrayList<ArrayList<float[]>> pieces=clipLine(xs,zs,Geo.x(west),Geo.x(east),Geo.z(north),Geo.z(south));
        String substance=tg(tags,"substance"), utility=tg(tags,"utility"), man=tg(tags,"man_made");
        boolean sewer=substance.matches("sewage|wastewater") || utility.matches("sewer|sanitary");
        float width=man.equals("pipeline")?1.15f:man.equals("pumping_station")?3f:1.7f;
        float r=sewer?0.38f:0.10f,g=sewer?0.40f:0.48f,b=sewer?0.43f:0.88f;
        for(ArrayList<float[]> piece:pieces){
            float[][] a=toArrays(piece); if(a==null)continue;
            float[][] pc=resample(a[0],a[1],8f);
            ribbon(pc[0],pc[1],width,0.18f,td.bufs[B_WATER],1f,12f,r,g,b);
        }
    }

    private static void addUtilityMarker(TileData td,float x,float z,JSONObject tags) {
        String man=tg(tags,"man_made");
        float r=0.10f,g=0.48f,b=0.88f;
        if(man.equals("wastewater_plant")){r=0.42f;g=0.44f;b=0.48f;}
        else if(man.equals("pumping_station")){r=0.92f;g=0.58f;b=0.12f;}
        td.bufs[B_SIGN].box(x,Terrain.h(x,z)+1.0f,z,1.4f,2.0f,1.4f,r,g,b);
    }

    private static void addTrafficSignal(TileData td,float x,float z) {
        float y=Terrain.h(x,z);
        GeoBuf b=td.bufs[B_SIGN];
        b.box(x,y+1.8f,z,0.10f,3.6f,0.10f,0.16f,0.17f,0.18f);
        b.box(x,y+3.05f,z,0.46f,0.82f,0.30f,0.10f,0.11f,0.12f);
        b.box(x,y+3.30f,z+0.17f,0.16f,0.16f,0.06f,0.90f,0.12f,0.10f);
        b.box(x,y+3.05f,z+0.17f,0.16f,0.16f,0.06f,0.95f,0.68f,0.08f);
        b.box(x,y+2.80f,z+0.17f,0.16f,0.16f,0.06f,0.10f,0.78f,0.24f);
    }

    /** تصنيف الأنشطة التجارية والخدمية من خصائص المكان أو المبنى. */
    private static int poiCategory(JSONObject tags) {
        String amenity=tg(tags,"amenity"), shop=tg(tags,"shop"), tourism=tg(tags,"tourism"), building=tg(tags,"building"), healthcare=tg(tags,"healthcare");
        if (amenity.matches("hospital|clinic|doctors|dentist|healthcare") || healthcare.matches("hospital|clinic|doctor|dentist")) return 1;
        if (tg(tags,"highway").equals("bus_stop") || tg(tags,"public_transport").matches("platform|stop_position") && tg(tags,"bus").equals("yes")) return 6;
        if (tg(tags,"railway").matches("station|halt|tram_stop|subway_entrance") || tg(tags,"public_transport").equals("station")) return 7;
        if (building.matches("hospital|clinic|health_centre")) return 1;
        if (amenity.matches("restaurant|fast_food|food_court")) return 2;
        if (amenity.matches("cafe|coffee_shop|bar")) return 3;
        if (tourism.matches("hotel|motel|hostel|guest_house")) return 4;
        if (building.matches("hotel|motel|hostel")) return 4;
        if (shop.matches("mall|supermarket|convenience|department_store|retail|general|clothes|bakery|butcher|electronics|chemist") || building.matches("retail|supermarket|mall|shop")) return 5;
        return 0;
    }

    private static String poiName(JSONObject tags) {
        String name=tg(tags,"name:ar");
        if (name.isEmpty()) name=tg(tags,"name");
        if (name.isEmpty()) name=tg(tags,"name:en");
        if (name.isEmpty()) name=tg(tags,"brand");
        if (name.isEmpty()) name=tg(tags,"addr:housename");
        if (name.isEmpty()) name=tg(tags,"addr:housenumber");
        return name;
    }

    /** علامة فئوية واضحة ولافتة اسمية فوق موقع النشاط، بارتفاع يتبع المبنى/التضاريس. */
    private static void addPoi(TileData td, float x, float z, String name, int category, float y) {
        float r=0.18f,g=0.62f,b=0.88f;
        if(category==2){r=0.94f;g=0.43f;b=0.16f;}
        else if(category==3){r=0.58f;g=0.34f;b=0.20f;}
        else if(category==4){r=0.55f;g=0.32f;b=0.76f;}
        else if(category==5){r=0.20f;g=0.68f;b=0.42f;}
        GeoBuf sign=td.bufs[B_SIGN];
        sign.box(x,y-1.0f,z,0.12f,2.0f,0.12f,0.25f,0.25f,0.27f);
        sign.box(x,y+0.20f,z,0.95f,0.48f,0.14f,r,g,b);
        if(name!=null&&!name.trim().isEmpty()){
            Decal d=new Decal(); d.text=name.trim(); d.x=x; d.z=z; d.dx=1f; d.dz=0f; d.y=y-0.55f; d.vertical=true;
            td.decals.add(d);
        }
    }

    /** لافتة رأسية على أطول واجهة للمبنى، مع اسم المنشأة أو رقمها وفئة النشاط. */
    private static void addFacadeLabel(TileData td, float[] px, float[] pz, String name, float minHeight, int category) {
        int best=0; float longest=0f;
        for(int i=0;i<px.length;i++){
            int j=(i+1)%px.length; float len=(float)Math.hypot(px[j]-px[i],pz[j]-pz[i]);
            if(len>longest){longest=len;best=i;}
        }
        if(longest<1.2f) return;
        int j=(best+1)%px.length;
        float dx=(px[j]-px[best])/longest, dz=(pz[j]-pz[best])/longest;
        float area=Poly.area(px,pz);
        float nx=area>0f?dz:-dz, nz=area>0f?-dx:dx;
        float x=(px[best]+px[j])*0.5f+nx*0.16f;
        float z=(pz[best]+pz[j])*0.5f+nz*0.16f;
        float y=Terrain.h(x,z)+minHeight+1.35f;
        float width=Math.min(3.6f,Math.max(1.8f,Math.min(longest-0.25f,3.6f)));
        float half=width*0.5f, height=1.15f;
        float lx=x-dx*half, lz=z-dz*half, rx=x+dx*half, rz=z+dz*half;
        float cr=0.34f,cg=0.28f,cb=0.22f;
        if(category==1){cr=0.12f;cg=0.38f;cb=0.72f;}
        else if(category==2){cr=0.78f;cg=0.25f;cb=0.10f;}
        else if(category==3){cr=0.43f;cg=0.25f;cb=0.14f;}
        else if(category==4){cr=0.42f;cg=0.20f;cb=0.62f;}
        else if(category==5){cr=0.12f;cg=0.48f;cb=0.28f;}
        GeoBuf sign=td.bufs[B_SIGN];
        sign.v(lx,y,lz,0,1,cr,cg,cb); sign.v(rx,y,rz,1,1,cr,cg,cb);
        sign.v(rx,y+height,rz,1,0,cr,cg,cb); sign.fixTri();
        sign.v(lx,y,lz,0,1,cr,cg,cb); sign.v(rx,y+height,rz,1,0,cr,cg,cb);
        sign.v(lx,y+height,lz,0,0,cr,cg,cb); sign.fixTri();
        if(name!=null&&!name.trim().isEmpty()){
            Decal d=new Decal(); d.text=name.trim(); d.x=x; d.z=z; d.dx=dx; d.dz=dz; d.y=y+0.08f; d.vertical=true;
            td.decals.add(d);
        }
    }

    private static void addDecal(TileData td, RoadPath rp, float s, String name, float[] o) {
        rp.posAt(s, rp.w / 4f, o);
        float dx = o[2], dz = o[3];
        if (dx < -1e-3f || (Math.abs(dx) <= 1e-3f && dz > 0)) { dx = -dx; dz = -dz; } // اتجاه مقروء من الأعلى
        Decal d = new Decal();
        d.text = name; d.x = o[0]; d.z = o[1]; d.dx = dx; d.dz = dz;
        td.decals.add(d);
    }

    private static void ribbon(float[] px, float[] pz, float w, float yOff, GeoBuf b, float uw, float vs,
                               float cr, float cg, float cb) {
        ribbonOffset(px, pz, w, yOff, b, uw, vs, cr, cg, cb);
    }

    private static void ribbonOffset(float[] px, float[] pz, float w, float yOff, GeoBuf b, float uw, float vs,
                               float cr, float cg, float cb) {
        int n = px.length;
        float[] lx = new float[n], lz = new float[n], rx = new float[n], rz = new float[n], vv = new float[n];
        float[] ly = new float[n], ry = new float[n];
        float cum = 0;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), c = Math.min(n - 1, i + 1);
            float dx = px[c] - px[a], dz = pz[c] - pz[a];
            float d = (float) Math.hypot(dx, dz);
            if (d < 1e-6f) d = 1;
            dx /= d; dz /= d;
            float nx = -dz, nz = dx;
            lx[i] = px[i] + nx * w / 2f; lz[i] = pz[i] + nz * w / 2f;
            rx[i] = px[i] - nx * w / 2f; rz[i] = pz[i] - nz * w / 2f;
            ly[i] = Terrain.h(lx[i], lz[i]) + yOff;
            ry[i] = Terrain.h(rx[i], rz[i]) + yOff;
            if (i > 0) cum += (float) Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            vv[i] = cum / vs;
        }
        for (int i = 0; i < n - 1; i++) {
            b.v(lx[i], ly[i], lz[i], 0, vv[i], cr, cg, cb);
            b.v(rx[i], ry[i], rz[i], uw, vv[i], cr, cg, cb);
            b.v(lx[i + 1], ly[i + 1], lz[i + 1], 0, vv[i + 1], cr, cg, cb);
            b.fixTri();
            b.v(rx[i], ry[i], rz[i], uw, vv[i], cr, cg, cb);
            b.v(rx[i + 1], ry[i + 1], rz[i + 1], uw, vv[i + 1], cr, cg, cb);
            b.v(lx[i + 1], ly[i + 1], lz[i + 1], 0, vv[i + 1], cr, cg, cb);
            b.fixTri();
        }
    }
}
