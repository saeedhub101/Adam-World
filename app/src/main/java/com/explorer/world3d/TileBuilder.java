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
    private static final int[] PALETTE = {0xe8dfcf, 0xd9c9a8, 0xcfc7b8, 0xece6d8, 0xc2a27a, 0xd8b98e, 0xc9a77e, 0xb58b62, 0xc4876b, 0xaeb8c2, 0x96918a, 0x77736d, 0x514e4a, 0x393b3d};

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
        // Collect named shop POI nodes before building extrusion so real storefront
        // names can be attached to the matching footprint instead of blank/fake signs.
        ArrayList<JSONObject> mappedShopNodes = new ArrayList<>();
        ArrayList<JSONObject> mappedAddressNodes = new ArrayList<>();
        for (int si = 0; si < els.length(); si++) {
            JSONObject candidate = els.optJSONObject(si);
            if (candidate == null || !candidate.optString("type", "").equals("node")) continue;
            JSONObject candidateTags = candidate.optJSONObject("tags");
            if (candidateTags == null) continue;
            if (!candidateTags.optString("addr:housenumber", "").trim().isEmpty())
                mappedAddressNodes.add(candidate);
            if (candidateTags.optString("shop", "").isEmpty()) continue;
            String shopName = candidateTags.optString("name:ar", "");
            if (shopName.isEmpty()) shopName = candidateTags.optString("name", "");
            if (shopName.isEmpty()) shopName = candidateTags.optString("brand", "");
            if (shopName.isEmpty()) shopName = candidateTags.optString("operator", "");
            if (!shopName.trim().isEmpty()) mappedShopNodes.add(candidate);
        }
        Hg hg = new Hg(xMin, xMax, zMin, zMax, 5f);

        ArrayList<float[]> trees = new ArrayList<>(), lamps = new ArrayList<>(), crossings = new ArrayList<>();
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
                else if (tg(tags, "highway").equals("crossing") || tg(tags, "crossing").matches("zebra|marked|uncontrolled|traffic_signals"))
                    crossings.add(new float[]{x, z});
                else if (poiCategory(tags) > 0) addPoi(td, x, z, poiName(tags), poiCategory(tags), Terrain.h(x, z) + 2.8f);
                else if (!tg(tags, "addr:housenumber").isEmpty())
                    addPoi(td, x, z, "رقم " + tg(tags, "addr:housenumber"), 10, Terrain.h(x, z) + 2.8f);
                else if (isWaterUtility(tags)) addUtilityMarker(td, x, z, tags);
                else if (tg(tags,"railway").matches("station|halt|tram_stop|subway_entrance") || tg(tags,"public_transport").matches("station|platform|stop_position")) addPoi(td,x,z,poiName(tags),7,Terrain.h(x,z)+2.8f);
                else if (tg(tags,"highway").equals("bus_stop")) addPoi(td,x,z,poiName(tags),6,Terrain.h(x,z)+2.8f);
                continue;
            }
            if (type.equals("relation")) {
                if (tg(tags, "route").matches("bus|trolleybus")) {
                    JSONArray members = el.optJSONArray("members");
                    String routeText = (tg(tags, "name") + " " + tg(tags, "network") + " " + tg(tags, "ref") + " " + tg(tags, "operator")).toLowerCase(java.util.Locale.US);
                    boolean brt = routeText.contains("brt") || routeText.contains("الباص السريع") || routeText.contains("rapid bus");
                    float[] colour = hex(tg(tags, "colour")); float rr = brt ? 0.60f : 0.12f, rg = brt ? 0.08f : 0.42f, rb = brt ? 0.68f : 0.88f;
                    if (colour != null) { rr = colour[0]; rg = colour[1]; rb = colour[2]; }
                    if (members != null) for (int mi = 0; mi < members.length(); mi++) {
                        JSONObject member = members.optJSONObject(mi); JSONArray mg = member == null ? null : member.optJSONArray("geometry"); if (mg == null || mg.length() < 2) continue;
                        float[] mx = new float[mg.length()], mz = new float[mg.length()];
                        for (int q = 0; q < mg.length(); q++) { JSONObject point = mg.optJSONObject(q); if (point == null) { mx = null; break; } mx[q] = Geo.x(point.optDouble("lon", Double.NaN)); mz[q] = Geo.z(point.optDouble("lat", Double.NaN)); }
                        if (mx != null) addClippedRibbon(td, mx, mz, brt ? 3.8f : 2.2f, brt ? 0.36f : 0.30f, td.bufs[B_ROADN], rr, rg, rb);
                    }
                }
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
            String amenity = tg(tags, "amenity");
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
                addBuilding(td, tags, id, bld, px, pz, tabarbour, manila, mappedShopNodes);
                int poi = poiCategory(tags);
                String buildingLabel = poiName(tags);
                String houseNumber = tg(tags, "addr:housenumber");
                if (houseNumber.isEmpty()) houseNumber = mappedHouseNumber(mappedAddressNodes, px, pz);
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
                if (poi == 1) buildingLabel = "مستشفى · " + buildingLabel;
                else if (poi == 8) buildingLabel = "مدرسة · " + buildingLabel;
                else if (poi == 9) buildingLabel = "صيدلية · " + buildingLabel;
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
            } else if (amenity.equals("parking") || tg(tags, "parking").matches("surface|multi-storey")) {
                float[][] lot = clean(xs, zs);
                if (lot != null && overlaps(lot, xMin, xMax, zMin, zMax)) {
                    fillCells(td.bufs[B_SIDEWALK], lot[0], lot[1], xMin, xMax, zMin, zMax, 2.5f, hg, Float.NaN, 0.20f, 0.66f, 0.67f, 0.65f, id);
                    for (int pi = 0; pi < lot[0].length; pi++) { int pj = (pi + 1) % lot[0].length; ribbonOffset(new float[]{lot[0][pi], lot[0][pj]}, new float[]{lot[1][pi], lot[1][pj]}, 0.22f, 0.30f, td.bufs[B_SIDEWALK], 1f, 2f, 0.48f, 0.49f, 0.47f); }
                    addParkingLotBays(td, lot[0], lot[1]);
                }
            } else if (hw.equals("traffic_island") || tg(tags, "area:highway").equals("traffic_island")) {
                float[][] island = clean(xs, zs);
                if (island != null && overlaps(island, xMin, xMax, zMin, zMax))
                    addTrafficIsland(td, island[0], island[1]);
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
            } else if (tg(tags,"route").matches("bus|trolleybus")
                    || tg(tags,"public_transport_route").matches("bus|trolleybus")) {
                String routeNetwork = tg(tags, "route_network");
                String routeName = tg(tags, "route_name");
                String routeRef = tg(tags, "route_ref");
                boolean brt = routeNetwork.toLowerCase(java.util.Locale.US).contains("brt")
                        || routeName.toLowerCase(java.util.Locale.US).contains("brt")
                        || routeName.contains("الباص السريع");
                float[] routeColour = hex(tg(tags, "route_colour"));
                float rr = brt ? 0.60f : 0.12f;
                float rg = brt ? 0.08f : 0.42f;
                float rb = brt ? 0.68f : 0.88f;
                if (routeColour != null) { rr = routeColour[0]; rg = routeColour[1]; rb = routeColour[2]; }
                addClippedRibbon(td, xs, zs, brt ? 3.6f : 2.1f, brt ? 0.34f : 0.29f,
                        td.bufs[B_ROADN], rr, rg, rb);
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

        // Paint zebra crossings from mapped crossing nodes, aligned to the nearest real road.
        for (float[] crossing : crossings) {
            RoadPath nearest = null;
            float nearestS = 0f, nearestDistance = Float.POSITIVE_INFINITY;
            float[] point = new float[4];
            for (RoadPath candidate : td.paths) {
                if (!candidate.veh || candidate.w < 4f) continue;
                for (float distance = 0f; distance <= candidate.total; distance += 2f) {
                    candidate.posAt(distance, 0f, point);
                    float d = (float)Math.hypot(point[0] - crossing[0], point[1] - crossing[1]);
                    if (d < nearestDistance) { nearestDistance = d; nearest = candidate; nearestS = distance; }
                }
            }
            if (nearest == null || nearestDistance > Math.max(5f, nearest.w * 0.5f + 2f)) continue;
            float half = Math.max(1.7f, Math.min(3.2f, nearest.w * 0.42f));
            for (float offset = -3.2f; offset <= 3.2f; offset += 0.88f) {
                float distance = Math.max(0f, Math.min(nearest.total, nearestS + offset));
                float[] a = new float[4], b = new float[4];
                nearest.posAt(distance, -half, a);
                nearest.posAt(distance, half, b);
                drawMarkingQuad(td, a[0], a[1], b[0], b[1], 0.30f,
                        0.46f, 0.94f, 0.94f, 0.91f);
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
        if (tabarbour) {
            // Stable per-building Jordanian stone palette: limestone, cream,
            // yellow-beige, ochre, brown and restrained grey/charcoal variants.
            // The ID-based choice avoids identical city-wide facades without flicker.
            int colour = PALETTE[Math.floorMod((int)(id ^ (id >>> 32)), PALETTE.length)];
            return new float[]{((colour >> 16) & 255) / 255f,
                    ((colour >> 8) & 255) / 255f, (colour & 255) / 255f};
        }
        return new float[]{0.78f, 0.73f, 0.65f};
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

    private static void addBuilding(TileData td, JSONObject tags, long id, String bld, float[] px, float[] pz, boolean tabarbour, boolean manila, ArrayList<JSONObject> mappedShopNodes) {
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
        float undergroundParkingFloors = measure(tg(tags, "building:levels:underground"));
        String undergroundParkingKind = tg(tags, "parking").toLowerCase(java.util.Locale.US);
        String undergroundParkingCondition = tg(tags, "parking:condition").toLowerCase(java.util.Locale.US);
        boolean explicitUndergroundParking = undergroundParkingKind.equals("underground")
                || undergroundParkingCondition.equals("underground") || undergroundParkingFloors > 0f;
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
                float bestDistance = 1.75f;
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
            if (tabarbour && cutEntranceMapped && i == cutEntranceEdge && len >= 2.5f) {
                float openingWidth = explicitUndergroundParking ? 3.35f : 2.25f;
                float halfOpening = openingWidth / (2f * len);
                float t0 = Math.max(0.02f, cutEntranceMid-halfOpening);
                float t1 = Math.min(0.98f, cutEntranceMid+halfOpening);
                float x0=ax+(bx-ax)*t0, z0=az+(bz-az)*t0;
                float x1=ax+(bx-ax)*t1, z1=az+(bz-az)*t1;
                float bottom0=yBotA+(yBotB-yBotA)*t0, bottom1=yBotA+(yBotB-yBotA)*t1;
                float top0=roofY[i]+(roofY[j]-roofY[i])*t0;
                float top1=roofY[i]+(roofY[j]-roofY[i])*t1;
                float openingClearHeight = explicitUndergroundParking ? 2.75f : 2.85f;
                float openingTop0=Math.min(bottom0+openingClearHeight,top0-0.20f);
                float openingTop1=Math.min(bottom1+openingClearHeight,top1-0.20f);
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
                || bld.matches("retail|commercial|supermarket|mall|shop")
                || hasNamedShopNode(mappedShopNodes, px, pz));
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
                HashSet<Long> assignedShopNodes = new HashSet<>();
                float ax=px[shopEdge], az=pz[shopEdge], bx=px[next], bz=pz[next];
                float dx=bx-ax, dz=bz-az, len=shopEdgeLength;
                float nx=dz/len, nz=-dx/len;
                if (Poly.contains(px,pz,(ax+bx)*0.5f+nx,(az+bz)*0.5f+nz)) { nx=-nx; nz=-nz; }
                int bays=Math.max(1,Math.min(10,(int)(len/3.1f)));
                float pitch=len/bays;
                float y0=(ground[shopEdge]+ground[next])*0.5f+minHeight+0.18f;
                float y1=Math.min(y0+2.55f,Math.min(roofY[shopEdge],roofY[next])-0.22f);
                for(int bay=0;bay<bays;bay++){
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
                    // Every commercial unit gets its own glazed entry door and a sign fascia.
                    float doorW=Math.min(0.92f,(t1-t0)*len*0.34f);
                    float doorT=t1-doorW/len-0.10f/len;
                    float q0=Math.max(t0+0.035f,doorT), q1=Math.min(t1-0.025f,q0+doorW/len);
                    float qx0=ax+dx*q0+nx*0.13f, qz0=az+dz*q0+nz*0.13f;
                    float qx1=ax+dx*q1+nx*0.13f, qz1=az+dz*q1+nz*0.13f;
                    float doorTop=Math.min(y0+2.25f,y1-0.12f);
                    if(doorTop-y0>1.65f){
                        facadeQuad(td.bufs[B_GLASS],qx0,y0+0.04f,qz0,qx1,y0+0.04f,qz1,
                                qx1,doorTop,qz1,qx0,doorTop,qz0,0.18f,0.30f,0.34f);
                        facadeQuad(buf,qx0,y0+0.04f,qz0,qx1,y0+0.04f,qz1,
                                qx1,y0+0.12f,qz1,qx0,y0+0.12f,qz0,0.16f,0.18f,0.19f);
                        facadeQuad(buf,qx0,doorTop-0.06f,qz0,qx1,doorTop-0.06f,qz1,
                                qx1,doorTop,qz1,qx0,doorTop,qz0,0.16f,0.18f,0.19f);
                    }
                    float signBottom=y1+0.12f, signTop=Math.min(y1+0.52f,roofY[shopEdge]+minHeight-0.12f);
                    if(signTop-signBottom>0.12f){
                        facadeQuad(buf,x0,signBottom,z0,x1,signBottom,z1,x1,signTop,z1,x0,signTop,z0,
                                0.16f,0.17f,0.18f);
                        // Match a mapped shop node to this bay by its projection on
                        // the actual footprint facade. Only real mapped names become text.
                        float bayCenter=(bay+0.5f)/bays, bestScore=Float.POSITIVE_INFINITY;
                        String mappedName="";
                        long mappedId=Long.MIN_VALUE;
                        for(JSONObject shopNode:mappedShopNodes){
                            long nodeId=shopNode.optLong("id",Long.MIN_VALUE);
                            if(assignedShopNodes.contains(nodeId)) continue;
                            float sx=Geo.x(shopNode.optDouble("lon",Double.NaN));
                            float sz=Geo.z(shopNode.optDouble("lat",Double.NaN));
                            if(!Float.isFinite(sx)||!Float.isFinite(sz)) continue;
                            float projection=((sx-ax)*dx+(sz-az)*dz)/(len*len);
                            projection=Math.max(0f,Math.min(1f,projection));
                            float pxn=ax+dx*projection,pzn=az+dz*projection;
                            float distance=(float)Math.hypot(sx-pxn,sz-pzn);
                            float score=Math.abs(projection-bayCenter)*len+distance*0.65f;
                            if(distance<=12f && score<bestScore){
                                JSONObject st=shopNode.optJSONObject("tags");
                                String candidate=st==null?"":st.optString("name:ar","");
                                if(candidate.isEmpty()&&st!=null) candidate=st.optString("name","");
                                if(candidate.isEmpty()&&st!=null) candidate=st.optString("brand","");
                                if(candidate.isEmpty()&&st!=null) candidate=st.optString("operator","");
                                if(!candidate.trim().isEmpty()){
                                    bestScore=score; mappedName=candidate.trim(); mappedId=nodeId;
                                }
                            }
                        }
                        // A footprint-level business name is valid only when the
                        // facade has one unit; never copy one business name to 3 shops.
                        if(mappedName.isEmpty() && bays==1){
                            mappedName=tg(tags,"name:ar");
                            if(mappedName.isEmpty()) mappedName=tg(tags,"name");
                            if(mappedName.isEmpty()) mappedName=tg(tags,"brand");
                            if(mappedName.isEmpty()) mappedName=tg(tags,"operator");
                        }
                        if(!mappedName.isEmpty()){
                            Decal shopDecal=new Decal();
                            shopDecal.text=mappedName; shopDecal.x=(x0+x1)*0.5f;
                            shopDecal.z=(z0+z1)*0.5f; shopDecal.dx=dx; shopDecal.dz=dz;
                            shopDecal.y=signBottom+0.07f; shopDecal.vertical=true;
                            td.decals.add(shopDecal);
                            if(mappedId!=Long.MIN_VALUE) assignedShopNodes.add(mappedId);
                        }
                    }
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
            // Keep door placement consistent with the facade-opening matcher above.
            // Entrance points commonly sit just outside the footprint boundary; a
            // 1.0 m threshold could cut the wall opening but reject the actual door.
            float bestDistance = 1.75f;
            for (int ei = 0; ei < mappedEntrances.length(); ei++) {
                JSONObject entrance = mappedEntrances.optJSONObject(ei);
                JSONObject properties = entrance == null ? null : entrance.optJSONObject("properties");
                JSONObject entranceTags = properties == null ? null : properties.optJSONObject("tags");
                String entranceKind = entranceTags == null ? (properties == null ? "" : properties.optString("entrance", "")) : entranceTags.optString("entrance", "");
                String entranceBuilding = entranceTags == null ? "" : entranceTags.optString("building", "");
                if (entranceKind.matches("garage|service") || entranceBuilding.equals("garages")) continue;
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
        // Build a real dog-leg driveway only where OSM marks a garage entrance.
        // The lane drops below grade, bends laterally, then reaches the mapped access side.
        if (tabarbour && mappedEntrances != null) {
            for (int ei=0; ei<mappedEntrances.length(); ei++) {
                JSONObject entry=mappedEntrances.optJSONObject(ei);
                JSONObject ep=entry==null?null:entry.optJSONObject("properties");
                JSONObject et=ep==null?null:ep.optJSONObject("tags");
                String entryKind=et==null?(ep==null?"":ep.optString("entrance","")):et.optString("entrance","");
                String accessKind=et==null?"":et.optString("building","");
                if(!entryKind.equals("garage") && !accessKind.equals("garages")) continue;
                JSONObject eg=entry.optJSONObject("geometry");
                JSONArray ec=eg==null?null:eg.optJSONArray("coordinates");
                if(ec==null||ec.length()<2) continue;
                float gx=Geo.x(ec.optDouble(0,Double.NaN)), gz=Geo.z(ec.optDouble(1,Double.NaN));
                int edgeBest=-1; float tBest=0f, dBest=Float.POSITIVE_INFINITY;
                for(int edge=0;edge<n;edge++){
                    int next=(edge+1)%n; float ex=px[next]-px[edge], ez=pz[next]-pz[edge], l2=ex*ex+ez*ez;
                    if(l2<0.01f) continue;
                    float t=Math.max(0f,Math.min(1f,((gx-px[edge])*ex+(gz-pz[edge])*ez)/l2));
                    float d=(float)Math.hypot(gx-(px[edge]+ex*t),gz-(pz[edge]+ez*t));
                    if(d<dBest){dBest=d;edgeBest=edge;tBest=t;}
                }
                if(edgeBest<0||dBest>1.75f) continue;
                int next=(edgeBest+1)%n; float ex=px[next]-px[edgeBest], ez=pz[next]-pz[edgeBest];
                float edgeLength=(float)Math.hypot(ex,ez); if(edgeLength<2f) continue;
                float nx=ez/edgeLength,nz=-ex/edgeLength;
                if(Poly.contains(px,pz,gx+nx,gz+nz)){nx=-nx;nz=-nz;}
                float tx=ex/edgeLength,tz=ez/edgeLength;
                float half=1.55f;
                // Four stations create a broad, drivable dog-leg instead of a straight trench.
                float[] rx={gx-nx*0.35f,gx+nx*4.0f+tx*0.2f,gx+nx*7.0f+tx*3.8f,gx+nx*12.0f+tx*3.8f};
                float[] rz={gz-nz*0.35f,gz+nz*4.0f+tz*0.2f,gz+nz*7.0f+tz*3.8f,gz+nz*12.0f+tz*3.8f};
                float baseY=(ground[edgeBest]+(ground[next]-ground[edgeBest])*tBest);
                float[] ry={baseY-2.65f,baseY-1.9f,baseY-0.85f,Terrain.h(rx[3],rz[3])+0.10f};
                for(int seg=0;seg<3;seg++){
                    float dx=rx[seg+1]-rx[seg], dz=rz[seg+1]-rz[seg];
                    float len=(float)Math.hypot(dx,dz); if(len<0.1f) continue;
                    float sx=-dz/len*half, sz=dx/len*half;
                    float ax=rx[seg]+sx, az=rz[seg]+sz, bx=rx[seg+1]+sx, bz=rz[seg+1]+sz;
                    float cx=rx[seg+1]-sx, cz=rz[seg+1]-sz, dx2=rx[seg]-sx, dz2=rz[seg]-sz;
                    facadeQuad(td.bufs[B_ROADN],ax,ry[seg],az,bx,ry[seg+1],bz,cx,ry[seg+1],cz,dx2,ry[seg],dz2,0.31f,0.32f,0.32f);
                    float wall=0.55f;
                    facadeQuad(buf,ax,ry[seg],az,ax,ry[seg]+wall,az,bx,ry[seg+1]+wall,bz,bx,ry[seg+1],bz,0.48f,0.47f,0.43f);
                    facadeQuad(buf,cx,ry[seg+1],cz,cx,ry[seg+1]+wall,cz,dx2,ry[seg]+wall,dz2,dx2,ry[seg],dz2,0.48f,0.47f,0.43f);
                }
                break;
            }
        }
        // A mapped garage entrance is preferred. If the building itself explicitly
        // declares underground parking, provide a conservative side-ramp fallback;
        // never infer basement parking for ordinary residential buildings.
        if (tabarbour && !storefront) {
            float undergroundFloors = undergroundParkingFloors;
            boolean hasVerifiedBasementParking = explicitUndergroundParking;
            boolean hasMappedGarage = false;
            if (mappedEntrances != null) {
                for (int ei = 0; ei < mappedEntrances.length(); ei++) {
                    JSONObject entry = mappedEntrances.optJSONObject(ei);
                    JSONObject ep = entry == null ? null : entry.optJSONObject("properties");
                    JSONObject et = ep == null ? null : ep.optJSONObject("tags");
                    String ek = et == null ? (ep == null ? "" : ep.optString("entrance", ""))
                            : et.optString("entrance", "");
                    String bk = et == null ? "" : et.optString("building", "");
                    if (ek.equals("garage") || bk.equals("garages")) { hasMappedGarage = true; break; }
                }
            }
            if (hasVerifiedBasementParking && !hasMappedGarage && doorEdgeLength >= 4.0f) {
                int edge = doorEdge;
                int next = (edge + 1) % n;
                float ex = px[next] - px[edge], ez = pz[next] - pz[edge];
                float edgeLength = (float) Math.hypot(ex, ez);
                if (edgeLength >= 4.0f) {
                    float nx = ez / edgeLength, nz = -ex / edgeLength;
                    float mx = (px[edge] + px[next]) * 0.5f;
                    float mz = (pz[edge] + pz[next]) * 0.5f;
                    if (Poly.contains(px, pz, mx + nx, mz + nz)) { nx = -nx; nz = -nz; }
                    float tx = ex / edgeLength, tz = ez / edgeLength;
                    float depth = undergroundFloors > 0f
                            ? Math.max(2.65f, Math.min(6.0f, undergroundFloors * 2.8f)) : 2.65f;
                    float[] rx = {mx - nx * 0.35f, mx + nx * 3.5f,
                            mx + nx * 6.5f + tx * 3.0f, mx + nx * 10.0f + tx * 3.0f};
                    float[] rz = {mz - nz * 0.35f, mz + nz * 3.5f,
                            mz + nz * 6.5f + tz * 3.0f, mz + nz * 10.0f + tz * 3.0f};
                    float baseY = (ground[edge] + ground[next]) * 0.5f;
                    float[] ry = {baseY - depth, baseY - depth * 0.70f,
                            baseY - depth * 0.32f, Terrain.h(rx[3], rz[3]) + 0.10f};
                    float half = 1.55f;
                    for (int seg = 0; seg < 3; seg++) {
                        float dx = rx[seg + 1] - rx[seg], dz = rz[seg + 1] - rz[seg];
                        float len = (float) Math.hypot(dx, dz);
                        if (len < 0.1f) continue;
                        float sx = -dz / len * half, sz = dx / len * half;
                        float ax = rx[seg] + sx, az = rz[seg] + sz;
                        float bx = rx[seg + 1] + sx, bz = rz[seg + 1] + sz;
                        float cx = rx[seg + 1] - sx, cz = rz[seg + 1] - sz;
                        float dx2 = rx[seg] - sx, dz2 = rz[seg] - sz;
                        facadeQuad(td.bufs[B_ROADN], ax, ry[seg], az, bx, ry[seg + 1], bz,
                                cx, ry[seg + 1], cz, dx2, ry[seg], dz2, 0.31f, 0.32f, 0.32f);
                        float wall = 0.55f;
                        facadeQuad(buf, ax, ry[seg], az, ax, ry[seg] + wall, az,
                                bx, ry[seg + 1] + wall, bz, bx, ry[seg + 1], bz,
                                0.48f, 0.47f, 0.43f);
                        facadeQuad(buf, cx, ry[seg + 1], cz, cx, ry[seg + 1] + wall, cz,
                                dx2, ry[seg] + wall, dz2, dx2, ry[seg], dz2,
                                0.48f, 0.47f, 0.43f);
                    }
                }
            }
        }
        if ((!manila || !tg(tags, "entrance").isEmpty() || !tg(tags, "door").isEmpty())
                && !(tabarbour && storefront) && (!tabarbour || mappedEntrance) && doorEdgeLength >= 2.2f && h >= 2.4f) {
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
            float x1 = ax + dx * t1 + nx * doorPlaneOffset, z1 = az + dz * t1 + nz * doorPlaneOffset;
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
        // Tabarbour/Jordan flat roofs: raised perimeter parapet and varied, separate rooftop equipment.
        // Equipment is deterministic per building and scaled to the actual footprint area.
        if (tabarbour && !pyramid && !rshape.equals("gabled") && !rshape.equals("skillion")) {
            float parapet = Math.max(0.95f, Math.min(1.25f, measure(tg(tags, "roof:parapet:height")) > 0f
                    ? measure(tg(tags, "roof:parapet:height")) : 1.08f));
            for (int edge=0; edge<n; edge++) {
                int next=(edge+1)%n;
                float ax=px[edge], az=pz[edge], bx=px[next], bz=pz[next];
                float len=(float)Math.hypot(bx-ax,bz-az);
                if(len<0.7f) continue;
                float nx=(bz-az)/len, nz=-(bx-ax)/len;
                if(Poly.contains(px,pz,(ax+bx)*0.5f+nx,(az+bz)*0.5f+nz)){nx=-nx;nz=-nz;}
                float off=0.06f, y0=(roofY[edge]+roofY[next])*0.5f;
                facadeQuad(buf,ax+nx*off,y0,az+nz*off,bx+nx*off,y0,bz+nz*off,
                        bx+nx*off,y0+parapet,bz+nz*off,ax+nx*off,y0+parapet,az+nz*off,
                        cr*0.88f,cg*0.88f,cb*0.88f);
            }
            float area=Math.abs(Poly.area(px,pz));
            float roofBase=0f;
            for(int k=0;k<n;k++) roofBase+=roofY[k];
            roofBase/=n;
            float cx=0f,cz=0f;
            for(int k=0;k<n;k++){cx+=px[k];cz+=pz[k];}
            cx/=n;cz/=n;
            float usable=Math.min(1f,Math.max(0f,(area-55f)/260f));
            int tanks=area>85f ? 1+(rnd(id,71)>0.62f?1:0)+(area>260f&&rnd(id,72)>0.45f?1:0) : 0;
            for(int k=0;k<tanks;k++){
                float ox=(rnd(id,80+k)-0.5f)*Math.min(5f,(maxx-minx)*0.32f);
                float oz=(rnd(id,90+k)-0.5f)*Math.min(5f,(maxz-minz)*0.32f);
                float tx=cx+ox,tz=cz+oz;
                if(!Poly.contains(px,pz,tx,tz)) continue;
                float radius=0.48f+rnd(id,100+k)*0.30f;
                buf.box(tx,roofBase+0.82f,tz,radius,1.64f,radius,0.78f,0.80f,0.79f);
                buf.box(tx,roofBase+1.68f,tz,radius*0.92f,0.08f,radius*0.92f,0.62f,0.65f,0.66f);
            }
            // Satellite dishes and antenna masts: present on many, but deliberately not all, roofs.
            int dishes=area>45f ? 1+(rnd(id,111)>0.48f?1:0)+(area>220f&&rnd(id,112)>0.55f?1:0) : 0;
            for(int k=0;k<dishes;k++){
                float ox=(rnd(id,120+k)-0.5f)*Math.min(6f,(maxx-minx)*0.38f);
                float oz=(rnd(id,130+k)-0.5f)*Math.min(6f,(maxz-minz)*0.38f);
                float dx=cx+ox,dz=cz+oz;
                if(!Poly.contains(px,pz,dx,dz)) continue;
                buf.box(dx,roofBase+0.72f,dz,0.045f,0.72f,0.045f,0.48f,0.49f,0.50f);
                float tilt=(rnd(id,140+k)-0.5f)*0.55f;
                facadeQuad(td.bufs[B_GLASS],dx-0.48f,roofBase+0.78f,dz+tilt,
                        dx+0.48f,roofBase+0.78f,dz-tilt,
                        dx+0.48f,roofBase+1.30f,dz-tilt-0.18f,
                        dx-0.48f,roofBase+1.30f,dz+tilt-0.18f,0.62f,0.65f,0.67f);
            }
            // Occasional AC condensers, solar hot-water collectors and PV panels.
            if(area>95f && rnd(id,151)>0.38f){
                float ax=cx+(rnd(id,152)-0.5f)*Math.min(4f,(maxx-minx)*0.25f);
                float az=cz+(rnd(id,153)-0.5f)*Math.min(4f,(maxz-minz)*0.25f);
                if(Poly.contains(px,pz,ax,az)) buf.box(ax,roofBase+0.42f,az,0.48f,0.78f,0.34f,0.72f,0.74f,0.72f);
            }
            if(area>125f && rnd(id,161)>0.52f){
                float sx=cx+(rnd(id,162)-0.5f)*Math.min(3f,(maxx-minx)*0.20f);
                float sz=cz+(rnd(id,163)-0.5f)*Math.min(3f,(maxz-minz)*0.20f);
                if(Poly.contains(px,pz,sx,sz)){
                    facadeQuad(td.bufs[B_GLASS],sx-1.0f,roofBase+0.22f,sz-0.55f,
                            sx+1.0f,roofBase+0.22f,sz-0.55f,sx+1.0f,roofBase+0.92f,sz+0.55f,
                            sx-1.0f,roofBase+0.92f,sz+0.55f,0.12f,0.18f,0.22f);
                }
            }
            if(area>170f && rnd(id,171)>0.66f){
                float sx=cx-1.4f,sz=cz+1.2f;
                if(Poly.contains(px,pz,sx,sz)) buf.box(sx,roofBase+1.15f,sz,0.62f,0.34f,0.62f,0.76f,0.79f,0.78f);
            }
            // Sparse solar hot-water collectors, distinct from photovoltaic panels.
            if(area>110f && rnd(id,181)>0.58f){
                float hx=cx+(rnd(id,182)-0.5f)*Math.min(3.5f,(maxx-minx)*0.22f);
                float hz=cz+(rnd(id,183)-0.5f)*Math.min(3.5f,(maxz-minz)*0.22f);
                if(Poly.contains(px,pz,hx,hz)){
                    buf.box(hx,roofBase+1.02f,hz-0.58f,0.78f,0.22f,0.27f,0.79f,0.82f,0.83f);
                    facadeQuad(td.bufs[B_GLASS],hx-0.88f,roofBase+0.28f,hz-0.48f,
                            hx+0.88f,roofBase+0.28f,hz-0.48f,hx+0.88f,roofBase+0.78f,hz+0.48f,
                            hx-0.88f,roofBase+0.78f,hz+0.48f,0.16f,0.23f,0.27f);
                }
            }
        }
        // No random billboard panels: Manila signage must come from mapped names or verified photo evidence.
        Footprint f = new Footprint();
        f.xs = px; f.zs = pz; f.minx = minx; f.maxx = maxx; f.minz = minz; f.maxz = maxz;
        f.obstacleHeight = Math.max(2.2f, h);
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
        boolean mappedBusway = hw.equals("busway") || tg(tags, "busway").matches("lane|track|yes");
        boolean veh = (w >= 4.5f || mappedBusway) && !hw.equals("pedestrian");
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
            if (w >= 6f || hw.equals("service") || mappedBusway) {
                GeoBuf roadBuf = (w >= 7f ? td.bufs[B_ROADW] : td.bufs[B_ROADN]);
                ribbonOffset(px, pz, w, yOffset, roadBuf, 1f, 12f, rr, rg, rb);
                if (bridge) addBridgeParapets(px, pz, w, yOffset, roadBuf, footOrCycle ? 1.15f : 0.85f);
                if (tabarbour && hw.matches("residential|living_street|unclassified|service")) {
                    long roadSeed = (((long) Math.round(px[0] * 10f)) << 32) ^ (long) Math.round(pz[0] * 10f);
                    addTabarbourSpeedBumps(roadBuf, px, pz, w, roadSeed);
                }
            } else {
                GeoBuf narrowRoad = veh ? td.bufs[B_ROADN] : td.bufs[B_SIDEWALK];
                ribbonOffset(px, pz, w, yOffset, narrowRoad, w / 2f, 2f, rr, rg, rb);
            }
            RoadPath rp = new RoadPath(px, pz, w, veh);
            rp.name = name;
            if (!bridge && !tunnel && veh && !footOrCycle)
                addRoadSidewalks(td, rp, tags, hw, yOffset);
            if (tg(tags, "junction").equals("roundabout")) addRoundaboutIsland(td, px, pz, w);
            if (veh && w >= 5.0f) addRoadMarkings(td, rp, tags, hw, yOffset, w);
            String medianKind = (tg(tags, "median") + " " + tg(tags, "divider") + " " + tg(tags, "separator") + " " + tg(tags, "dual_carriageway")).toLowerCase(java.util.Locale.US);
            if (veh && !tg(tags, "oneway").matches("yes|1|true") && (medianKind.matches(".*(yes|grass|concrete|kerb|barrier|median).*") || !tg(tags, "median:width").isEmpty())) addRoadMedian(td, rp, tags, yOffset);
            String busLaneTags = (tg(tags, "bus:lanes") + " " + tg(tags, "psv:lanes") + " " + tg(tags, "busway")).toLowerCase(java.util.Locale.US);
            if (mappedBusway || busLaneTags.matches(".*(designated|permissive|yes|lane|track).*")) addBusLaneMarking(td, rp, yOffset, mappedBusway);
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

    /**
     * Builds two independent, terrain-conforming sidewalks beside the carriageway.
     * Widths and presence are source-driven when tagged; urban defaults are used
     * only when the source omits sidewalk metadata. The complete sidewalk polygon
     * is registered as a vehicle obstacle, not merely painted on the road.
     */
    /**
     * Build traversable sidewalks with a shallow beveled transition from asphalt.
     * The pavement remains visible and continuous, but it is never registered as
     * a collision obstacle. A low ramp replaces the former vertical kerb wall so
     * pedestrians and ordinary road vehicles can cross the street/sidewalk edge.
     */
    private static void addRoadSidewalks(TileData td, RoadPath path, JSONObject tags,
                                         String highway, float roadYOffset) {
        if (path == null || path.total < 2f) return;
        String both = tg(tags, "sidewalk").toLowerCase(java.util.Locale.US);
        boolean urban = highway.matches("primary|secondary|tertiary|unclassified|residential|living_street|service|road");
        for (int side : new int[]{-1, 1}) {
            String sideName = side < 0 ? "right" : "left";
            String sideTag = tg(tags, "sidewalk:" + sideName).toLowerCase(java.util.Locale.US);
            String presence = sideTag.isEmpty() ? both : sideTag;
            if (presence.matches("no|none|separate|0")) continue;
            if (sideTag.isEmpty() && (both.equals("left") && side < 0 || both.equals("right") && side > 0)) continue;
            if (presence.isEmpty() && !urban) continue;
            float width = measure(tg(tags, "sidewalk:" + sideName + ":width"));
            if (!(width >= 0.7f && width <= 12f)) width = measure(tg(tags, "sidewalk:width"));
            if (!(width >= 0.7f && width <= 12f)) width = presence.equals("yes") || presence.equals("both") ? 1.8f : 1.5f;
            float inner = path.w * 0.5f;
            float centerOffset = side * (inner + width * 0.5f);
            float junctionClearance = Math.min(7f, path.total * 0.22f);
            float sidewalkStart = junctionClearance, sidewalkEnd = path.total - junctionClearance;
            if (sidewalkEnd - sidewalkStart < 5f) continue;
            int count = Math.max(2, (int)Math.ceil((sidewalkEnd - sidewalkStart) / 5f) + 1);
            float[] cx = new float[count], cz = new float[count];
            float[] p = new float[4];
            for (int i = 0; i < count; i++) {
                float distance = sidewalkStart + (sidewalkEnd - sidewalkStart) * i / (count - 1f);
                path.posAt(distance, centerOffset, p);
                cx[i] = p[0]; cz[i] = p[1];
            }

            String kerbType = (tg(tags, "kerb") + " " + tg(tags, "curb")).toLowerCase(java.util.Locale.US);
            float kerbRise = measure(tg(tags, "kerb:height"));
            if (!(kerbRise >= 0.02f && kerbRise <= 0.12f)) kerbRise = 0.08f;
            if (kerbType.matches(".*(flush|lowered|rolled).*")) kerbRise = Math.min(0.035f, kerbRise);
            final float pavementY = roadYOffset + kerbRise;

            // Flat sidewalk deck, raised only a few centimetres above the road.
            ribbonOffset(cx, cz, width, pavementY, td.bufs[B_SIDEWALK],
                    1f, 8f, 0.78f, 0.77f, 0.72f);

            // A 65 cm bevel gives both tyres and the character a continuous ramp.
            // It replaces the old vertical concrete face at the carriageway edge.
            float rampWidth = 0.65f;
            for (int i = 0; i + 1 < count; i++) {
                float s0 = sidewalkStart + (sidewalkEnd - sidewalkStart) * i / (count - 1f);
                float s1 = sidewalkStart + (sidewalkEnd - sidewalkStart) * (i + 1) / (count - 1f);
                float[] a = new float[4], b = new float[4], c = new float[4], d = new float[4];
                path.posAt(s0, side * inner, a);
                path.posAt(s1, side * inner, b);
                path.posAt(s1, side * (inner + rampWidth), c);
                path.posAt(s0, side * (inner + rampWidth), d);
                float baseA = Terrain.h(a[0], a[1]) + roadYOffset + 0.012f;
                float baseB = Terrain.h(b[0], b[1]) + roadYOffset + 0.012f;
                float topC = Terrain.h(c[0], c[1]) + pavementY;
                float topD = Terrain.h(d[0], d[1]) + pavementY;
                facadeQuad(td.bufs[B_SIDEWALK],
                        a[0], baseA, a[1], b[0], baseB, b[1],
                        c[0], topC, c[1], d[0], topD, d[1],
                        0.58f, 0.59f, 0.56f);
                // Keep a low-step footprint for physics/suspension. Movement uses
                // step clearance, so even a vertical curb of this height is passable.
                Footprint curbStep = new Footprint();
                curbStep.xs = new float[]{a[0], b[0], c[0], d[0]};
                curbStep.zs = new float[]{a[1], b[1], c[1], d[1]};
                curbStep.minx = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0]));
                curbStep.maxx = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0]));
                curbStep.minz = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]));
                curbStep.maxz = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]));
                curbStep.obstacleHeight = kerbRise;
                Collision.add(td.grid, curbStep);
            }
        }
    }

    /** Build a concrete inner island and raised curb inside a mapped closed roundabout way. */
    private static void addRoundaboutIsland(TileData td, float[] xs, float[] zs, float roadWidth) {
        if (xs == null || zs == null || xs.length < 5) return;
        int count = xs.length;
        if (Math.hypot(xs[0] - xs[count - 1], zs[0] - zs[count - 1]) > 1.5f) return;
        count--;
        if (count < 4) return;
        float[] ringX = new float[count], ringZ = new float[count];
        System.arraycopy(xs, 0, ringX, 0, count);
        System.arraycopy(zs, 0, ringZ, 0, count);
        float[] center = centroid(ringX, ringZ);
        float radius = 0f;
        for (int i = 0; i < count; i++)
            radius += (float) Math.hypot(ringX[i] - center[0], ringZ[i] - center[1]);
        radius /= count;
        if (radius < roadWidth * 0.7f + 1.5f) return;
        float scale = Math.max(0.18f, Math.min(0.82f, 1f - (roadWidth * 0.5f + 0.85f) / radius));
        float[] islandX = new float[count], islandZ = new float[count];
        for (int i = 0; i < count; i++) {
            islandX[i] = center[0] + (ringX[i] - center[0]) * scale;
            islandZ[i] = center[1] + (ringZ[i] - center[1]) * scale;
        }
        addTrafficIsland(td, islandX, islandZ);
        ribbonOffset(islandX, islandZ, 0.52f, 0.40f, td.bufs[B_SIDEWALK],
                1f, 2f, 0.62f, 0.62f, 0.59f);
    }

    /** Filled concrete traffic island with a visible raised perimeter curb. */
    private static void addTrafficIsland(TileData td, float[] xs, float[] zs) {
        if (xs == null || zs == null || xs.length < 3) return;
        int[] triangles = Poly.earClip(xs, zs);
        Footprint islandObstacle = new Footprint();
        islandObstacle.xs = xs; islandObstacle.zs = zs;
        islandObstacle.minx = islandObstacle.maxx = xs[0];
        islandObstacle.minz = islandObstacle.maxz = zs[0];
        for (int i = 1; i < xs.length; i++) {
            islandObstacle.minx = Math.min(islandObstacle.minx, xs[i]); islandObstacle.maxx = Math.max(islandObstacle.maxx, xs[i]);
            islandObstacle.minz = Math.min(islandObstacle.minz, zs[i]); islandObstacle.maxz = Math.max(islandObstacle.maxz, zs[i]);
        }
        islandObstacle.obstacleHeight = 0.30f; // Raised island: step-over height, not a blocking wall.
        Collision.add(td.grid, islandObstacle);
        GeoBuf fill = td.bufs[B_SIDEWALK];
        for (int i = 0; i + 2 < triangles.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int v = triangles[i + k];
                float x = xs[v], z = zs[v];
                fill.v(x, Terrain.h(x, z) + 0.30f, z, 0, 0, 0.73f, 0.72f, 0.67f);
            }
            fill.fixTri();
        }
        for (int i = 0; i < xs.length; i++) {
            int j = (i + 1) % xs.length;
            float[] ex = {xs[i], xs[j]}, ez = {zs[i], zs[j]};
            ribbonOffset(ex, ez, 0.42f, 0.28f, td.bufs[B_SIDEWALK],
                    1f, 2f, 0.55f, 0.55f, 0.52f);
        }
    }

    private static void addRoadMedian(TileData td, RoadPath path, JSONObject tags, float roadYOffset) {
        float w = measure(tg(tags, "median:width")); if (!(w >= 0.6f && w <= 20f)) w = 1.4f;
        String kind = (tg(tags, "median") + " " + tg(tags, "divider") + " " + tg(tags, "separator")).toLowerCase(java.util.Locale.US);
        boolean grass = kind.contains("grass") || kind.contains("green"); float r = grass ? 0.38f : 0.66f, g = grass ? 0.52f : 0.66f, b = grass ? 0.29f : 0.63f;
        float[] a = new float[4], c = new float[4];
        for (float s = 0; s < path.total - 1; s += 7f) { float e = Math.min(path.total, s + 7f); path.posAt(s, 0, a); path.posAt(e, 0, c); drawMarkingQuad(td,a[0],a[1],c[0],c[1],roadYOffset+0.16f,w,r,g,b);
            for (int side : new int[]{-1,1}) { path.posAt(s,side*w*0.5f,a); path.posAt(e,side*w*0.5f,c); drawMarkingQuad(td,a[0],a[1],c[0],c[1],roadYOffset+0.24f,0.18f,0.48f,0.49f,0.47f); }
        }
    }
    private static void addBusLaneMarking(TileData td, RoadPath path, float roadYOffset, boolean fullBusway) {
        if(path==null||path.total<6f)return; float off=fullBusway?0f:path.w*0.5f-1.65f;
        for(float s=3f;s<path.total-2f;s+=12f){float[] a=new float[4],b=new float[4];path.posAt(s,off,a);path.posAt(Math.min(path.total,s+5f),off,b);drawMarkingQuad(td,a[0],a[1],b[0],b[1],roadYOffset+0.06f,0.42f,0.08f,0.48f,0.72f);}
    }
    private static void addParkingLotBays(TileData td, float[] px, float[] pz) {
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(int i=0;i<px.length;i++){minX=Math.min(minX,px[i]);maxX=Math.max(maxX,px[i]);minZ=Math.min(minZ,pz[i]);maxZ=Math.max(maxZ,pz[i]);}
        for(float z=minZ+1.5f;z<maxZ-1f;z+=5.2f) for(float x=minX+1f;x<maxX-1f;x+=2.7f) {
            float x1=Math.min(maxX-0.5f,x+2.5f),z1=Math.min(maxZ,z+4.5f),mid=(x+x1)*0.5f;
            if(Poly.contains(px,pz,mid,z)&&Poly.contains(px,pz,mid,z1)) {
                drawMarkingQuad(td,x,z,x1,z,0.25f,0.10f,0.94f,0.94f,0.90f);
                drawMarkingQuad(td,x,z,x,z1,0.25f,0.10f,0.94f,0.94f,0.90f);
                drawMarkingQuad(td,x1,z,x1,z1,0.25f,0.10f,0.94f,0.94f,0.90f);
            }
        }
    }
    /** Draw source-driven lane dividers and mapped curbside parking-bay markings. */
    private static void addRoadMarkings(TileData td, RoadPath path, JSONObject tags,
                                        String highway, float roadYOffset, float width) {
        if (path == null || path.total < 8f) return;
        float lanesValue = measure(tg(tags, "lanes"));
        if (!(lanesValue > 0f && lanesValue <= 12f)) {
            float forward = measure(tg(tags, "lanes:forward"));
            float backward = measure(tg(tags, "lanes:backward"));
            lanesValue = (forward > 0f && forward <= 12f ? forward : 0f)
                    + (backward > 0f && backward <= 12f ? backward : 0f);
        }
        int lanes = lanesValue > 0f ? Math.max(1, Math.min(12, Math.round(lanesValue)))
                : Math.max(1, Math.min(8, Math.round(width / 3.2f)));
        String oneWayTag = tg(tags, "oneway").toLowerCase(java.util.Locale.US);
        boolean oneWay = oneWayTag.matches("yes|1|true");
        if (lanes >= 2) {
            if (oneWay) {
                for (int lane = 1; lane < lanes; lane++) {
                    float offset = -width * 0.5f + width * lane / lanes;
                    drawDashedLaneLine(td, path, offset, roadYOffset, 2.8f, 6.5f,
                            0.92f, 0.92f, 0.88f);
                }
            } else {
                drawDashedLaneLine(td, path, 0f, roadYOffset, 3.0f, 6.0f,
                        0.96f, 0.82f, 0.28f);
                int eachSide = lanes / 2;
                if (eachSide > 1) {
                    for (int lane = 1; lane < eachSide; lane++) {
                        float offset = width * lane / lanes;
                        drawDashedLaneLine(td, path, -offset, roadYOffset, 2.8f, 6.5f,
                                0.92f, 0.92f, 0.88f);
                        drawDashedLaneLine(td, path, offset, roadYOffset, 2.8f, 6.5f,
                                0.92f, 0.92f, 0.88f);
                    }
                }
            }
        }
        float forwardLanes = measure(tg(tags, "lanes:forward"));
        float backwardLanes = measure(tg(tags, "lanes:backward"));
        if (oneWay && lanes >= 1 && width >= 5.5f) {
            float laneOffset = -width * 0.5f + width / (2f * lanes);
            for (float distance = 24f; distance < path.total - 8f; distance += 52f)
                drawRoadArrow(td, path, distance, laneOffset, roadYOffset, false);
        } else if (forwardLanes > 0f && backwardLanes > 0f
                && forwardLanes + backwardLanes <= 12f && width >= 6f) {
            float fw = width * forwardLanes / (forwardLanes + backwardLanes);
            float bw = width - fw;
            for (int lane = 0; lane < Math.min(4, Math.round(forwardLanes)); lane++) {
                float offset = -width * 0.5f + fw * (lane + 0.5f) / forwardLanes;
                for (float distance = 28f + lane * 5f; distance < path.total - 8f; distance += 58f)
                    drawRoadArrow(td, path, distance, offset, roadYOffset, false);
            }
            for (int lane = 0; lane < Math.min(4, Math.round(backwardLanes)); lane++) {
                float offset = bw * (lane + 0.5f) / backwardLanes;
                for (float distance = 28f + lane * 5f; distance < path.total - 8f; distance += 58f)
                    drawRoadArrow(td, path, distance, offset, roadYOffset, true);
            }
        } else if (!oneWay && lanes >= 2 && width >= 5.5f) {
            int eachSide = Math.max(1, lanes / 2);
            for (int lane = 0; lane < Math.min(4, eachSide); lane++) {
                float offset = -width * 0.5f + width * (lane + 0.5f) / lanes;
                for (float distance = 28f + lane * 5f; distance < path.total - 8f; distance += 58f)
                    drawRoadArrow(td, path, distance, offset, roadYOffset, false);
            }
            for (int lane = 0; lane < Math.min(4, eachSide); lane++) {
                float offset = width * (lane + 0.5f) / lanes;
                for (float distance = 28f + lane * 5f; distance < path.total - 8f; distance += 58f)
                    drawRoadArrow(td, path, distance, offset, roadYOffset, true);
            }
        }
        String leftParking = tg(tags, "parking:lane:left").toLowerCase(java.util.Locale.US);
        String rightParking = tg(tags, "parking:lane:right").toLowerCase(java.util.Locale.US);
        String bothParking = tg(tags, "parking:lane:both").toLowerCase(java.util.Locale.US);
        if (bothParking.matches("parallel|diagonal|perpendicular|marked|yes")) {
            leftParking = bothParking;
            rightParking = bothParking;
        }
        if (leftParking.matches("parallel|diagonal|perpendicular|marked|yes"))
            drawParkingBayLines(td, path, 1f, width, roadYOffset);
        if (rightParking.matches("parallel|diagonal|perpendicular|marked|yes"))
            drawParkingBayLines(td, path, -1f, width, roadYOffset);
    }

    private static void drawDashedLaneLine(TileData td, RoadPath path, float offset,
                                           float roadYOffset, float dash, float gap,
                                           float r, float g, float b) {
        for (float s = 2f; s < path.total - 1f; s += dash + gap) {
            drawMarkingSegment(td, path, s, Math.min(path.total, s + dash), offset,
                    roadYOffset, 0.11f, r, g, b);
        }
    }

    private static void drawParkingBayLines(TileData td, RoadPath path, float side,
                                            float roadWidth, float roadYOffset) {
        float inner = side * (roadWidth * 0.5f + 0.18f);
        float outer = side * (roadWidth * 0.5f + 2.45f);
        for (float s = 5f; s < path.total - 2f; s += 6.2f) {
            float[] a = new float[4], b = new float[4];
            path.posAt(s, inner, a);
            path.posAt(s, outer, b);
            drawMarkingQuad(td, a[0], a[1], b[0], b[1], roadYOffset,
                    0.10f, 0.88f, 0.88f, 0.84f);
        }
    }

    private static void drawRoadArrow(TileData td, RoadPath path, float distance,
                                      float offset, float roadYOffset, boolean reverse) {
        float[] p = new float[4];
        path.posAt(distance, offset, p);
        float dx = reverse ? -p[2] : p[2];
        float dz = reverse ? -p[3] : p[3];
        float nx = -dz, nz = dx;
        float bx = p[0] - dx * 1.15f, bz = p[1] - dz * 1.15f;
        float tipX = p[0] + dx * 1.25f, tipZ = p[1] + dz * 1.25f;
        float wing = 0.72f;
        drawMarkingQuad(td, bx, bz, tipX, tipZ, roadYOffset, 0.13f, 0.94f, 0.94f, 0.90f);
        drawMarkingQuad(td, tipX, tipZ,
                p[0] - dx * 0.20f + nx * wing, p[1] - dz * 0.20f + nz * wing,
                roadYOffset, 0.13f, 0.94f, 0.94f, 0.90f);
        drawMarkingQuad(td, tipX, tipZ,
                p[0] - dx * 0.20f - nx * wing, p[1] - dz * 0.20f - nz * wing,
                roadYOffset, 0.13f, 0.94f, 0.94f, 0.90f);
    }

    private static void drawMarkingSegment(TileData td, RoadPath path, float start, float end,
                                           float offset, float roadYOffset, float thickness,
                                           float r, float g, float b) {
        float[] a = new float[4], c = new float[4];
        path.posAt(start, offset, a);
        path.posAt(end, offset, c);
        drawMarkingQuad(td, a[0], a[1], c[0], c[1], roadYOffset,
                thickness, r, g, b);
    }

    private static void drawMarkingQuad(TileData td, float x0, float z0, float x1, float z1,
                                        float roadYOffset, float thickness,
                                        float r, float g, float b) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.hypot(dx, dz);
        if (len < 0.04f) return;
        float nx = -dz / len * thickness * 0.5f;
        float nz = dx / len * thickness * 0.5f;
        float ax = x0 + nx, az = z0 + nz, bx = x1 + nx, bz = z1 + nz;
        float cx = x1 - nx, cz = z1 - nz, dx2 = x0 - nx, dz2 = z0 - nz;
        GeoBuf mark = td.bufs[B_SIGN];
        float ya = Terrain.h(ax, az) + roadYOffset + 0.045f;
        float yb = Terrain.h(bx, bz) + roadYOffset + 0.045f;
        float yc = Terrain.h(cx, cz) + roadYOffset + 0.045f;
        float yd = Terrain.h(dx2, dz2) + roadYOffset + 0.045f;
        mark.v(ax, ya, az, 0, 0, r, g, b); mark.v(bx, yb, bz, 1, 0, r, g, b);
        mark.v(cx, yc, cz, 1, 1, r, g, b); mark.fixTri();
        mark.v(ax, ya, az, 0, 0, r, g, b); mark.v(cx, yc, cz, 1, 1, r, g, b);
        mark.v(dx2, yd, dz2, 0, 1, r, g, b); mark.fixTri();
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
        if (amenity.equals("pharmacy") || healthcare.equals("pharmacy") || shop.equals("chemist")) return 9;
        if (amenity.matches("school|kindergarten|college|university") || building.matches("school|kindergarten|college|university")) return 8;
        if (tg(tags,"highway").equals("bus_stop") || tg(tags,"public_transport").matches("platform|stop_position") && tg(tags,"bus").equals("yes")) return 6;
        if (tg(tags,"railway").matches("station|halt|tram_stop|subway_entrance") || tg(tags,"public_transport").equals("station")) return 7;
        if (building.matches("hospital|clinic|health_centre")) return 1;
        if (amenity.matches("restaurant|fast_food|food_court")) return 2;
        if (amenity.matches("cafe|coffee_shop|bar")) return 3;
        if (tourism.matches("hotel|motel|hostel|guest_house")) return 4;
        if (building.matches("hotel|motel|hostel")) return 4;
        if (shop.matches("mall|supermarket|convenience|department_store|retail|general|clothes|bakery|butcher|electronics") || building.matches("retail|supermarket|mall|shop")) return 5;
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
        if(category==1){r=0.78f;g=0.16f;b=0.18f;}
        else if(category==8){r=0.16f;g=0.34f;b=0.78f;}
        else if(category==9){r=0.12f;g=0.62f;b=0.30f;}
        else if(category==2){r=0.94f;g=0.43f;b=0.16f;}
        else if(category==3){r=0.58f;g=0.34f;b=0.20f;}
        else if(category==4){r=0.55f;g=0.32f;b=0.76f;}
        else if(category==5){r=0.20f;g=0.68f;b=0.42f;}
        GeoBuf sign=td.bufs[B_SIGN];
        sign.box(x,y-1.0f,z,0.12f,2.0f,0.12f,0.25f,0.25f,0.27f);
        sign.box(x,y+0.20f,z,0.95f,0.48f,0.14f,r,g,b);
        if(category==1 || category==9){
            sign.box(x,y+0.20f,z+0.09f,0.14f,0.34f,0.035f,1f,1f,1f);
            sign.box(x,y+0.20f,z+0.09f,0.34f,0.14f,0.035f,1f,1f,1f);
        } else if(category==8){
            for(int wx=-1;wx<=1;wx++) sign.box(x+wx*0.23f,y+0.20f,z+0.09f,0.10f,0.16f,0.035f,0.94f,0.94f,0.90f);
        }
        if(name!=null&&!name.trim().isEmpty()){
            Decal d=new Decal(); d.text=name.trim(); d.x=x; d.z=z; d.dx=1f; d.dz=0f; d.y=y-0.55f; d.vertical=true;
            td.decals.add(d);
        }
    }

    /** Find a real OSM address point strictly inside its matching building footprint. */
    private static String mappedHouseNumber(ArrayList<JSONObject> nodes, float[] px, float[] pz) {
        if (nodes == null || nodes.isEmpty()) return "";
        for (JSONObject node : nodes) {
            double lat = node.optDouble("lat", Double.NaN), lon = node.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
            float x = Geo.x(lon), z = Geo.z(lat);
            if (!Poly.contains(px, pz, x, z)) continue;
            JSONObject tags = node.optJSONObject("tags");
            if (tags == null) continue;
            String number = tags.optString("addr:housenumber", "").trim();
            if (!number.isEmpty()) return number;
        }
        return "";
    }

    /** لافتة رأسية على أطول واجهة للمبنى، مع اسم المنشأة أو رقمها وفئة النشاط. */
    private static boolean hasNamedShopNode(ArrayList<JSONObject> nodes, float[] px, float[] pz) {
        if (nodes == null || nodes.isEmpty()) return false;
        for (JSONObject node : nodes) {
            float x=Geo.x(node.optDouble("lon",Double.NaN));
            float z=Geo.z(node.optDouble("lat",Double.NaN));
            if (Float.isFinite(x) && Float.isFinite(z) && Poly.contains(px,pz,x,z)) return true;
        }
        return false;
    }

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
        else if(category==8){cr=0.12f;cg=0.32f;cb=0.72f;}
        else if(category==9){cr=0.10f;cg=0.58f;cb=0.28f;}
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
