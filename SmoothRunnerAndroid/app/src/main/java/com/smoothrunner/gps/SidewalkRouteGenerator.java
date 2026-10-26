package com.smoothrunner.gps;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * SidewalkRouteGenerator:
 * Quét trực tiếp mạng lưới vỉa hè & lối đi bộ quanh phòng (KTX Khu A ĐHQG TP.HCM hoặc vị trí GPS thực)
 * từ OpenStreetMap API v0.6 chính thức (https://api.openstreetmap.org/api/0.6/map.json):
 * 1. Tạo đường dẫn (Lead-In Path) đi bộ từ đúng toạ độ TRONG PHÒNG ra tới mép vỉa hè gần nhất.
 * 2. Cắt bỏ toàn bộ ngõ cụt (dead-end pruning) và cầu thang (steps).
 * 3. Tại mỗi ngã ba / ngã tư vỉa hè, tự động RANDOM chọn nhánh vỉa hè tiếp theo quanh khu vực ở,
 *    dịch chuyển quỹ đạo vào đúng lề vỉa hè theo bề rộng đường thực tế và bo tròn góc cua bằng Spline.
 */
public class SidewalkRouteGenerator {

    public static class LatLng {
        public final double lat;
        public final double lng;
        public final String streetName;
        public final double sidewalkOffsetMeters;
        public final double outerSideSign;

        public LatLng(double lat, double lng) {
            this(lat, lng, "Vỉa hè nội khu", 3.6, 1.0);
        }

        public LatLng(double lat, double lng, String streetName, double sidewalkOffsetMeters) {
            this(lat, lng, streetName, sidewalkOffsetMeters, 1.0);
        }

        public LatLng(double lat, double lng, String streetName, double sidewalkOffsetMeters, double outerSideSign) {
            this.lat = lat;
            this.lng = lng;
            this.streetName = streetName != null ? streetName : "Vỉa hè nội khu";
            this.sidewalkOffsetMeters = sidewalkOffsetMeters;
            this.outerSideSign = outerSideSign;
        }
    }

    public static class GeneratedPlan {
        public final int totalIntersectionNodes;
        public final List<LatLng> leadInPoints;
        public final List<LatLng> sidewalkPoints;
        public final String routeStyleSummary;

        public GeneratedPlan(int totalIntersectionNodes, List<LatLng> leadInPoints, List<LatLng> sidewalkPoints) {
            this(totalIntersectionNodes, leadInPoints, sidewalkPoints, "Đa chế độ Làng Đại Học (Khu A ↔ Khu B)");
        }

        public GeneratedPlan(
                int totalIntersectionNodes,
                List<LatLng> leadInPoints,
                List<LatLng> sidewalkPoints,
                String routeStyleSummary) {
            this.totalIntersectionNodes = totalIntersectionNodes;
            this.leadInPoints = leadInPoints;
            this.sidewalkPoints = sidewalkPoints;
            this.routeStyleSummary = routeStyleSummary;
        }
    }

    private static final double EARTH_RADIUS = 6378137.0;
    private static final Random RANDOM = new Random();
    private static volatile String cachedBboxUrl = null;
    private static volatile String cachedOsmRawJson = null;

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS * c;
    }

    public static float bearingDegrees(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brng + 360.0) % 360.0);
    }

    public static LatLng offsetByMeters(
            double lat, double lng, double distanceMeters, double bearingDeg, String streetName, double offsetMeta) {
        if (Math.abs(distanceMeters) < 0.005) {
            return new LatLng(lat, lng, streetName, offsetMeta);
        }
        double brngRad = Math.toRadians(bearingDeg);
        double latRad = Math.toRadians(lat);
        double lngRad = Math.toRadians(lng);
        double angDist = distanceMeters / EARTH_RADIUS;

        double newLat = Math.asin(Math.sin(latRad) * Math.cos(angDist)
                + Math.cos(latRad) * Math.sin(angDist) * Math.cos(brngRad));
        double newLng = lngRad + Math.atan2(
                Math.sin(brngRad) * Math.sin(angDist) * Math.cos(latRad),
                Math.cos(angDist) - Math.sin(latRad) * Math.sin(newLat));

        return new LatLng(Math.toDegrees(newLat), Math.toDegrees(newLng), streetName, offsetMeta);
    }

    public static LatLng offsetByMeters(double lat, double lng, double distanceMeters, double bearingDeg) {
        return offsetByMeters(lat, lng, distanceMeters, bearingDeg, "Vỉa hè", 3.6);
    }

    public static double estimateSidewalkOffsetFromTags(JSONObject tags) {
        if (tags == null) return 3.6;
        String hw = tags.optString("highway", "");
        String fw = tags.optString("footway", "");

        if ("footway".equals(hw) || "pedestrian".equals(hw)
                || "path".equals(hw) || "corridor".equals(hw) || "sidewalk".equals(fw)) {
            return 0.0;
        }

        String widthStr = tags.optString("width", "").replaceAll("[^0-9.]", "");
        if (!widthStr.isEmpty()) {
            try {
                double w = Double.parseDouble(widthStr);
                if (w >= 2.0 && w <= 40.0) return (w / 2.0) + 1.0;
            } catch (Exception ignored) {
            }
        }

        String lanesStr = tags.optString("lanes", "").replaceAll("[^0-9.]", "");
        if (!lanesStr.isEmpty()) {
            try {
                double lanes = Double.parseDouble(lanesStr);
                if (lanes >= 1.0 && lanes <= 8.0) return Math.max(4.6, (lanes * 3.5 / 2.0) + 1.4);
            } catch (Exception ignored) {
            }
        }

        switch (hw) {
            case "primary":
            case "primary_link":
                return 6.8;
            case "secondary":
            case "secondary_link":
                return 5.6;
            case "tertiary":
            case "tertiary_link":
                return 4.8;
            case "residential":
            case "unclassified":
                return 4.2;
            case "living_street":
            case "service":
                return 2.4;
            default:
                return 4.2;
        }
    }

    public static synchronized void preloadBundledMapIfNeeded(Context context) {
        if (cachedOsmRawJson != null && !cachedOsmRawJson.isEmpty() && "LANG_DAI_HOC".equals(cachedBboxUrl)) {
            return;
        }
        String loaded = loadBundledLangDaiHocJson(context);
        if (loaded != null && !loaded.isEmpty()) {
            cachedBboxUrl = "LANG_DAI_HOC";
            cachedOsmRawJson = loaded;
        }
    }

    private static String loadBundledLangDaiHocJson(Context context) {
        InputStream rawStream = null;
        try {
            if (context != null) {
                rawStream = context.getAssets().open("osm_lang_dai_hoc.json.gz");
            } else {
                ClassLoader cl = SidewalkRouteGenerator.class.getClassLoader();
                if (cl != null) {
                    rawStream = cl.getResourceAsStream("assets/osm_lang_dai_hoc.json.gz");
                }
                if (rawStream == null) {
                    rawStream = SidewalkRouteGenerator.class.getResourceAsStream("/assets/osm_lang_dai_hoc.json.gz");
                }
            }
            if (rawStream != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new GZIPInputStream(rawStream), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        } finally {
            if (rawStream != null) {
                try {
                    rawStream.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static GeneratedPlan generateFromRoomToRandomSidewalks(
            double roomLat, double roomLng, double radiusMeters, int numRandomSegments) {
        return generateFromRoomToRandomSidewalks(roomLat, roomLng, radiusMeters, numRandomSegments, true);
    }

    public static GeneratedPlan generateFromRoomToRandomSidewalks(
            double roomLat, double roomLng, double radiusMeters, int numRandomSegments, boolean startingFromDormRoom) {
        Map<Long, LatLng> nodes = new HashMap<>();
        Map<Long, Set<Long>> adj = new HashMap<>();
        Map<String, String> edgeNames = new HashMap<>();
        Map<String, Double> edgeOffsets = new HashMap<>();
        Map<String, Double> edgeOuterSideSigns = new HashMap<>();
        Set<String> medianEdges = new HashSet<>();

        try {
            boolean inLangDaiHoc = distanceMeters(roomLat, roomLng, 10.881915, 106.809219) < 2800.0;
            String bboxUrl;
            if (inLangDaiHoc) {
                // Bao phủ trọn vẹn Làng Đại Học ĐHQG từ KTX Khu A (106.8092) sang tận KTX Khu B (106.7800)
                bboxUrl = "https://api.openstreetmap.org/api/0.6/map.json?bbox=106.779500,10.871500,106.814500,10.890500";
            } else {
                double r = Math.max(400.0, Math.min(radiusMeters, 1500.0));
                double dLat = Math.toDegrees(r / EARTH_RADIUS);
                double dLng = dLat / Math.max(0.2, Math.cos(Math.toRadians(roomLat)));
                bboxUrl = String.format(Locale.US,
                        "https://api.openstreetmap.org/api/0.6/map.json?bbox=%.6f,%.6f,%.6f,%.6f",
                        roomLng - dLng, roomLat - dLat, roomLng + dLng, roomLat + dLat);
            }

            String rawJson = null;
            if (inLangDaiHoc) {
                if (cachedOsmRawJson != null && !cachedOsmRawJson.isEmpty() && "LANG_DAI_HOC".equals(cachedBboxUrl)) {
                    rawJson = cachedOsmRawJson;
                } else {
                    rawJson = loadBundledLangDaiHocJson(null);
                    if (rawJson != null && !rawJson.isEmpty()) {
                        cachedBboxUrl = "LANG_DAI_HOC";
                        cachedOsmRawJson = rawJson;
                    }
                }
            }

            if (rawJson == null || rawJson.isEmpty()) {
                if (bboxUrl.equals(cachedBboxUrl) && cachedOsmRawJson != null && !cachedOsmRawJson.isEmpty()) {
                    rawJson = cachedOsmRawJson;
                } else {
                    URL url = new URL(bboxUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "SmoothRunnerAndroidCLI/1.6.4");
                    conn.setRequestProperty("Accept-Encoding", "gzip");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(22000);

                    if (conn.getResponseCode() == 200) {
                        InputStream inStream = conn.getInputStream();
                        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                            inStream = new GZIPInputStream(inStream);
                        }
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(inStream, StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        rawJson = sb.toString();
                        cachedBboxUrl = bboxUrl;
                        cachedOsmRawJson = rawJson;
                    }
                }
            }

            if (rawJson != null) {
                JSONObject json = new JSONObject(rawJson);
                JSONArray elements = json.optJSONArray("elements");
                if (elements != null) {
                    Set<Long> allowedMainGates = new HashSet<>();
                    // Mở Cổng chính KTX Khu A (10.878319, 106.806151) và nút 4809043211 nằm trên trục chính Đ. Thánh Gióng
                    allowedMainGates.add(2938914930L);
                    allowedMainGates.add(2938914917L);
                    allowedMainGates.add(4809043211L);

                    Set<Long> blockedNodes = new HashSet<>();
                    // Chặn toàn bộ cổng phụ KTX Khu A bị khóa (cổng phụ AH2 ra Tạ Quang Bửu, cổng phụ ra Nguyễn Bỉnh Khiêm, cổng phụ ra Thánh Gióng, cổng phụ Bách Khoa)
                    long[] closedKtxGates = new long[]{
                            11350152381L, 4809030811L, 4809030812L, 4809060669L,
                            11350152380L, 12336254240L, 12336254241L, 12518012309L, 4766291200L,
                            4809043209L, 5753048834L
                    };
                    for (long gid : closedKtxGates) {
                        blockedNodes.add(gid);
                    }

                    Set<Long> internalKtxWayIds = new HashSet<>();
                    long[] ktxWays = new long[]{
                            488606128L, 488606129L, 490582680L, 290326862L, 290326861L,
                            290326863L, 290326866L, 290326859L, 290326860L
                    };
                    for (long wid : ktxWays) {
                        internalKtxWayIds.add(wid);
                    }
                    Set<Long> allowedInternalWayIds = new HashSet<>(internalKtxWayIds);
                    // Cho phép trục đường bộ Văn Cao (137036061) nối thẳng Tôn Thất Tùng / Hải Thượng Lãn Ông xuống Lương Định Của (KTX Khu B)
                    allowedInternalWayIds.add(137036061L);

                    Set<Long> protectedInternalNodes = new HashSet<>();
                    Map<Long, double[]> rawNodes = new HashMap<>();
                    Map<Long, Set<String>> nodeStreetNames = new HashMap<>();

                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject el = elements.getJSONObject(i);
                        String type = el.optString("type");
                        if ("node".equals(type) && el.has("lat") && el.has("lon")) {
                            long nid = el.getLong("id");
                            rawNodes.put(nid, new double[]{el.getDouble("lat"), el.getDouble("lon")});
                            JSONObject ntags = el.optJSONObject("tags");
                            if (ntags != null && !allowedMainGates.contains(nid)) {
                                String acc = ntags.optString("access", "");
                                if ("no".equals(acc) || "private".equals(acc)) {
                                    blockedNodes.add(nid);
                                }
                            }
                        } else if ("way".equals(type)) {
                            JSONObject wtags = el.optJSONObject("tags");
                            if (wtags != null && !wtags.optString("highway", "").isEmpty()) {
                                String wName = wtags.optString("name", "").trim();
                                if (!wName.isEmpty()) {
                                    JSONArray nds = el.optJSONArray("nodes");
                                    if (nds != null) {
                                        for (int j = 0; j < nds.length(); j++) {
                                            long nid = nds.getLong(j);
                                            nodeStreetNames.computeIfAbsent(nid, k -> new HashSet<>()).add(wName);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject el = elements.getJSONObject(i);
                        if (!"way".equals(el.optString("type"))) continue;
                        long wayId = el.optLong("id", -1L);
                        JSONObject tags = el.optJSONObject("tags");
                        if (tags == null) continue;
                        String hw = tags.optString("highway", "");
                        if (hw.isEmpty() || "steps".equals(hw) || "motorway".equals(hw) || "trunk".equals(hw)
                                || "footway".equals(hw) || "path".equals(hw) || "track".equals(hw)
                                || "pedestrian".equals(hw) || "cycleway".equals(hw) || "corridor".equals(hw)) {
                            continue;
                        }
                        String rawName = tags.optString("name", "").trim();
                        String lowerName = rawName.toLowerCase(Locale.US);
                        // Loại bỏ hầm chui, cầu vượt, đường ống nước thô và quốc lộ/xa lộ xe tải
                        if (rawName.contains("Ống Nước") || rawName.contains("Hầm")
                                || rawName.contains("Cầu vượt") || rawName.contains("Quốc lộ")
                                || lowerName.contains("xa lộ hà nội")) {
                            continue;
                        }
                        if (wayId != 137036061L && ("private".equals(tags.optString("access"))
                                || "no".equals(tags.optString("access"))
                                || "no".equals(tags.optString("foot")))) {
                            continue;
                        }

                        JSONArray nds = el.optJSONArray("nodes");
                        if (nds == null || nds.length() < 2) continue;

                        double wayLength = 0.0;
                        for (int j = 0; j < nds.length() - 1; j++) {
                            double[] c1 = rawNodes.get(nds.getLong(j));
                            double[] c2 = rawNodes.get(nds.getLong(j + 1));
                            if (c1 != null && c2 != null) {
                                wayLength += distanceMeters(c1[0], c1[1], c2[0], c2[1]);
                            }
                        }

                        long u0 = nds.getLong(0);
                        long v0 = nds.getLong(nds.length() - 1);
                        Set<String> nu = nodeStreetNames.get(u0);
                        Set<String> nv = nodeStreetNames.get(v0);
                        String junction = tags.optString("junction", "").trim();

                        boolean isPureMedianOpening = rawName.isEmpty()
                                && !"roundabout".equals(junction)
                                && wayLength <= 65.0
                                && nu != null && nv != null
                                && nu.size() == 1 && nu.equals(nv);
                        boolean isIntersectionConnector = rawName.isEmpty()
                                && wayLength <= 65.0
                                && nu != null && !nu.isEmpty()
                                && nv != null && !nv.isEmpty()
                                && !isPureMedianOpening;
                        boolean isMajorOrLinkOrRoundabout = hw.endsWith("_link")
                                || "roundabout".equals(junction)
                                || "primary".equals(hw)
                                || "secondary".equals(hw)
                                || "tertiary".equals(hw);

                        if (rawName.isEmpty()
                                && !allowedInternalWayIds.contains(wayId)
                                && !isMajorOrLinkOrRoundabout
                                && !isIntersectionConnector
                                && !isPureMedianOpening) {
                            continue;
                        }

                        double offset = internalKtxWayIds.contains(wayId)
                                ? 2.2
                                : estimateSidewalkOffsetFromTags(tags);
                        String onewayTag = tags.optString("oneway", "").trim().toLowerCase(Locale.US);
                        boolean isForwardOneway = "yes".equals(onewayTag)
                                || "true".equals(onewayTag)
                                || "1".equals(onewayTag)
                                || "roundabout".equals(junction);
                        boolean isReverseOneway = "-1".equals(onewayTag) || "reverse".equals(onewayTag);

                        String stName;
                        if (isPureMedianOpening) {
                            String medSt = nu.iterator().next();
                            stName = "Điểm quay đầu giữa đường (" + medSt + ")";
                        } else if (!rawName.isEmpty()) {
                            stName = rawName.startsWith("Vỉa hè") ? rawName : ("Vỉa hè " + rawName);
                        } else if (internalKtxWayIds.contains(wayId)) {
                            stName = "Đường nội khu KTX Khu A";
                        } else {
                            stName = "Vỉa hè Giao lộ Làng Đại Học";
                        }

                        for (int j = 0; j < nds.length(); j++) {
                            long nid = nds.getLong(j);
                            double[] coord = rawNodes.get(nid);
                            if (coord != null && !blockedNodes.contains(nid)) {
                                String nodeLabel = stName;
                                if (nid == 2938914930L || nid == 2938914917L) {
                                    nodeLabel = "Cổng chính KTX Khu A ➔ Tạ Quang Bửu";
                                }
                                if (!nodes.containsKey(nid) || !rawName.isEmpty() || nid == 2938914930L || nid == 2938914917L) {
                                    nodes.put(nid, new LatLng(coord[0], coord[1], nodeLabel, offset));
                                }
                                if (internalKtxWayIds.contains(wayId)) {
                                    protectedInternalNodes.add(nid);
                                }
                            }
                        }

                        for (int j = 0; j < nds.length() - 1; j++) {
                            long u = nds.getLong(j);
                            long v = nds.getLong(j + 1);
                            if (u == v || blockedNodes.contains(u) || blockedNodes.contains(v)) continue;
                            if (!nodes.containsKey(u) || !nodes.containsKey(v)) continue;
                            adj.computeIfAbsent(u, k -> new HashSet<>()).add(v);
                            adj.computeIfAbsent(v, k -> new HashSet<>()).add(u);
                            String edgeLabel = (u == 2938914930L || v == 2938914930L || u == 2938914917L || v == 2938914917L)
                                    ? "Cổng chính KTX Khu A ➔ Tạ Quang Bửu"
                                    : stName;
                            String k1 = u + "_" + v;
                            String k2 = v + "_" + u;
                            edgeNames.put(k1, edgeLabel);
                            edgeNames.put(k2, edgeLabel);
                            edgeOffsets.put(k1, offset);
                            edgeOffsets.put(k2, offset);
                            // Trên đường đôi 1 chiều (oneway=yes):
                            // - Đi xuôi chiều (u -> v): vỉa hè bên ngoài nằm bên PHẢI (+1.0), con lươn giữa lộ nằm bên TRÁI.
                            // - Đi ngược chiều (v -> u): vỉa hè bên ngoài nằm bên TRÁI (-1.0), tránh bị đẩy vào con lươn giữa lộ!
                            if (isForwardOneway) {
                                edgeOuterSideSigns.put(k1, 1.0);
                                edgeOuterSideSigns.put(k2, -1.0);
                            } else if (isReverseOneway) {
                                edgeOuterSideSigns.put(k1, -1.0);
                                edgeOuterSideSigns.put(k2, 1.0);
                            } else {
                                edgeOuterSideSigns.put(k1, 1.0);
                                edgeOuterSideSigns.put(k2, 1.0);
                            }
                            if (isPureMedianOpening) {
                                medianEdges.add(k1);
                                medianEdges.add(k2);
                            }
                        }
                    }

                    boolean pruned = true;
                    while (pruned) {
                        pruned = false;
                        List<Long> dead = new ArrayList<>();
                        for (Map.Entry<Long, Set<Long>> entry : adj.entrySet()) {
                            if (entry.getValue().size() < 2 && !protectedInternalNodes.contains(entry.getKey())) {
                                dead.add(entry.getKey());
                            }
                        }
                        if (!dead.isEmpty()) {
                            pruned = true;
                            for (Long d : dead) {
                                adj.remove(d);
                            }
                            for (Set<Long> nbSet : adj.values()) {
                                nbSet.removeAll(dead);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (adj.size() < 4) {
            buildFallbackGrid(roomLat, roomLng, nodes, adj, edgeNames, edgeOffsets);
        }

        List<Long> sortedIds = new ArrayList<>(adj.keySet());
        final boolean isNearAh2 = startingFromDormRoom && distanceMeters(roomLat, roomLng, 10.881915, 106.809219) < 220.0;
        Collections.sort(sortedIds, Comparator.comparingDouble(id -> {
            LatLng pt = nodes.get(id);
            double d = distanceMeters(roomLat, roomLng, pt.lat, pt.lng);
            // Khi xuất phát từ cụm AH1/AH2, bắt buộc chọn điểm xuất phát nằm trên Đường nội khu KTX Khu A,
            // tuyệt đối không nhảy tắt qua cổng khóa ra thẳng Vỉa hè Tạ Quang Bửu hoặc Thánh Gióng!
            if (isNearAh2 && pt.streetName != null && !pt.streetName.contains("KTX Khu A")) {
                d += 500.0;
            } else if (!startingFromDormRoom && pt.streetName != null && pt.streetName.contains("KTX Khu A")) {
                // Nếu đang Pause ở vỉa hè ngoài đường lớn (ví dụ Vỉa hè Tạ Quang Bửu) và bấm mở phiên mới,
                // giữ nguyên trên vỉa hè đường lớn chứ không nhảy ngược qua tường rào vào lại KTX Khu A!
                d += 400.0;
            }
            return d;
        }));

        long entryId = sortedIds.get(0);
        List<Long> walkIds = new ArrayList<>();
        walkIds.add(entryId);
        long curr = entryId;
        long prev = -1;
        List<Long> recentQueue = new ArrayList<>();
        recentQueue.add(entryId);

        // Nếu xuất phát bên trong KTX Khu A (khu AH1/AH2/AG/A), đi dọc đường trục chính nội khu
        // xuống thẳng Cổng chính KTX Khu A (10.878319, 106.806151 - quãng đường ~660m) rồi mới ra đường lớn!
        if (startingFromDormRoom
                && distanceMeters(roomLat, roomLng, 10.8805, 106.8085) < 550.0
                && distanceMeters(roomLat, roomLng, 10.878319, 106.806151) > 180.0) {
            long gateTargetId = findNearestNodeId(10.878319, 106.806151, nodes, adj);
            if (gateTargetId != -1 && gateTargetId != entryId) {
                List<Long> toGatePath = findShortestPath(entryId, gateTargetId, nodes, adj, medianEdges, true, false);
                if (toGatePath.size() >= 2) {
                    for (int i = 1; i < toGatePath.size(); i++) {
                        long stepId = toGatePath.get(i);
                        walkIds.add(stepId);
                        recentQueue.add(stepId);
                        prev = curr;
                        curr = stepId;
                    }
                }
            }
        }

        // Quay số ngẫu nhiên chọn phong cách chạy bộ cho phiên này:
        // - Mode 0 (32%): Chạy xa KTX Khu A -> KTX Khu B (Hành lang Quảng Trường Sáng Tạo - Nguyễn Du - Mạc Đĩnh Chi)
        // - Mode 1 (28%): Chạy xa KTX Khu A -> KTX Khu B (Hành lang Tạ Quang Bửu - Thánh Gióng - Hải Thượng Lãn Ông - Khu B)
        // - Mode 2 (22%): Pha trộn Đi hết đường + Ngẫu nhiên Quay đầu giữa chừng (Vòng rộng Làng Đại Học)
        // - Mode 3 (18%): Đi hết đường & Khám phá xa ngẫu nhiên (Khu A - Hồ Đá - Trung tâm ĐHQG - Khu B)
        int modeRoll = RANDOM.nextInt(100);
        String routeStyleSummary;
        List<Long> macroWaypoints = new ArrayList<>();
        boolean injectMidWayTurnback = false;

        if (modeRoll < 32 && adj.size() > 100) {
            injectMidWayTurnback = RANDOM.nextInt(100) < 50;
            routeStyleSummary = injectMidWayTurnback
                    ? "Chạy xa KTX Khu A ➔ KTX Khu B (Quảng Trường Sáng Tạo - Nguyễn Du) + Có quay đầu giữa chừng"
                    : "Chạy xa KTX Khu A ➔ KTX Khu B (Đi hết đường qua Quảng Trường Sáng Tạo - Nguyễn Du - Mạc Đĩnh Chi)";
            macroWaypoints.add(findNearestNodeId(10.8765, 106.8032, nodes, adj)); // Quảng Trường Sáng Tạo
            macroWaypoints.add(findNearestNodeId(10.8791, 106.7992, nodes, adj)); // Marie Curie / William Shakespeare
            macroWaypoints.add(findNearestNodeId(10.8805, 106.7860, nodes, adj)); // Nguyễn Du
            macroWaypoints.add(findNearestNodeId(10.8826, 106.7833, nodes, adj)); // Mạc Đĩnh Chi (Cổng KTX Khu B)
            macroWaypoints.add(findNearestNodeId(10.8875, 106.7820, nodes, adj)); // Đường Vành Đai KTX Khu B
        } else if (modeRoll < 60 && adj.size() > 100) {
            injectMidWayTurnback = RANDOM.nextInt(100) < 50;
            routeStyleSummary = injectMidWayTurnback
                    ? "Chạy xa KTX Khu A ➔ KTX Khu B (Thánh Gióng - Hải Thượng Lãn Ông) + Có quay đầu giữa chừng"
                    : "Chạy xa KTX Khu A ➔ KTX Khu B (Đi hết đường qua Thánh Gióng - Hải Thượng Lãn Ông - Tôn Thất Tùng)";
            macroWaypoints.add(findNearestNodeId(10.8844, 106.8063, nodes, adj)); // Đ. Thánh Gióng / Thomas Edison
            macroWaypoints.add(findNearestNodeId(10.8873, 106.7993, nodes, adj)); // Lê Quý Đôn / Hải Thượng Lãn Ông
            macroWaypoints.add(findNearestNodeId(10.8845, 106.7884, nodes, adj)); // Hải Thượng Lãn Ông / Tôn Thất Tùng
            macroWaypoints.add(findNearestNodeId(10.8826, 106.7833, nodes, adj)); // Mạc Đĩnh Chi / Lương Định Của (Khu B)
            macroWaypoints.add(findNearestNodeId(10.8875, 106.7820, nodes, adj)); // Đường Vành Đai KTX Khu B
        } else if (modeRoll < 82) {
            routeStyleSummary = "Pha trộn Tự nhiên (Random Đi hết đường + Quay đầu giữa chừng khắp Làng Đại Học)";
        } else {
            injectMidWayTurnback = RANDOM.nextInt(100) < 40;
            routeStyleSummary = "Khám phá xa Đa hướng (KTX Khu A ↔ Trung tâm ĐHQG ↔ KTX Khu B)";
            double[][] landmarkPool = new double[][]{
                    {10.8844, 106.8063}, // Thánh Gióng / Thomas Edison
                    {10.8765, 106.8032}, // Quảng Trường Sáng Tạo
                    {10.8811, 106.7966}, // William Shakespeare / Hồ Đá
                    {10.8863, 106.7946}, // Hải Thượng Lãn Ông
                    {10.8805, 106.7860}, // Nguyễn Du (Gần Khu B)
                    {10.8832, 106.7841}, // Mạc Đĩnh Chi / Lương Định Của (Khu B)
                    {10.8875, 106.7820}  // Đường Vành Đai KTX Khu B
            };
            List<double[]> picked = new ArrayList<>();
            Collections.addAll(picked, landmarkPool);
            Collections.shuffle(picked, RANDOM);
            for (int k = 0; k < Math.min(3, picked.size()); k++) {
                macroWaypoints.add(findNearestNodeId(picked.get(k)[0], picked.get(k)[1], nodes, adj));
            }
        }

        // Thực thi chuỗi Waypoint (nếu ở chế độ chạy xa sang KTX Khu B hoặc khám phá đa hướng)
        int turnbackLegIndex = injectMidWayTurnback ? RANDOM.nextInt(Math.max(1, macroWaypoints.size() - 1)) : -1;
        for (int wIdx = 0; wIdx < macroWaypoints.size(); wIdx++) {
            long wp = macroWaypoints.get(wIdx);
            if (wp == -1 || wp == curr) continue;
            List<Long> seg = findShortestPath(curr, wp, nodes, adj, medianEdges, false, true);
            if (seg.size() >= 2) {
                if (wIdx == turnbackLegIndex && seg.size() >= 7) {
                    int mid = Math.min(seg.size() - 2, Math.max(3, seg.size() / 2));
                    for (int i = 1; i <= mid; i++) {
                        long stepId = seg.get(i);
                        walkIds.add(stepId);
                        recentQueue.add(stepId);
                        prev = curr;
                        curr = stepId;
                    }
                    // Quay đầu giữa chừng (lùi lại 2-4 nút về giao lộ gần nhất hoặc băng qua điểm mở con lươn)
                    int backSteps = Math.min(3, mid - 1);
                    for (int b = 1; b <= backSteps; b++) {
                        long backId = seg.get(mid - b);
                        walkIds.add(backId);
                        recentQueue.add(backId);
                        prev = curr;
                        curr = backId;
                    }
                    List<Long> rem = findShortestPath(curr, wp, nodes, adj, medianEdges, true, true);
                    for (int i = 1; i < rem.size(); i++) {
                        long stepId = rem.get(i);
                        walkIds.add(stepId);
                        recentQueue.add(stepId);
                        prev = curr;
                        curr = stepId;
                    }
                } else {
                    for (int i = 1; i < seg.size(); i++) {
                        long stepId = seg.get(i);
                        walkIds.add(stepId);
                        recentQueue.add(stepId);
                        prev = curr;
                        curr = stepId;
                    }
                }
            }
        }

        // Tiếp tục bước đi ngẫu nhiên kết hợp Đi hết đường + Ngẫu nhiên Quay đầu giữa chừng cho đủ >= 145 đoạn (~6.5 - 8.5 km)
        int totalTargetSegments = Math.max(numRandomSegments, 145);
        double distOnCurrentStreet = 0.0;
        double targetTurnbackDist = 170.0 + RANDOM.nextDouble() * 210.0;
        boolean currentStreetWantsTurnback = (RANDOM.nextInt(100) < (modeRoll >= 60 && modeRoll < 82 ? 42 : 22));
        String activeStreetName = "";
        int segmentsSinceLastTurnback = 10;

        for (int s = 0; s < totalTargetSegments; s++) {
            Set<Long> nbSet = adj.get(curr);
            if (nbSet == null || nbSet.isEmpty()) break;

            LatLng currPt = nodes.get(curr);
            LatLng prevPt = (prev != -1) ? nodes.get(prev) : null;
            float inBrng = (prevPt != null && currPt != null)
                    ? bearingDegrees(prevPt.lat, prevPt.lng, currPt.lat, currPt.lng)
                    : -1.0f;

            // Kiểm tra nếu đã đi được một đoạn 170m-380m trên đường hiện tại và trúng xác suất Quay đầu giữa chừng!
            if (currentStreetWantsTurnback
                    && distOnCurrentStreet >= targetTurnbackDist
                    && segmentsSinceLastTurnback >= 8
                    && walkIds.size() >= 6) {
                // Ưu tiên 1: Nếu ngay tại nút hiện tại có điểm mở con lươn (medianEdges), băng qua con lươn để quay đầu!
                long medianNb = -1;
                for (Long nb : nbSet) {
                    if (medianEdges.contains(curr + "_" + nb) && nb != prev) {
                        medianNb = nb;
                        break;
                    }
                }
                if (medianNb != -1) {
                    walkIds.add(medianNb);
                    recentQueue.add(medianNb);
                    prev = curr;
                    curr = medianNb;
                    distOnCurrentStreet = 0.0;
                    currentStreetWantsTurnback = false;
                    segmentsSinceLastTurnback = 0;
                    continue;
                }
                // Ưu tiên 2: Quay đầu ngay giữa đường trên vỉa hè (lùi về giao lộ có >= 3 nhánh cách đó 2..4 bước rồi rẽ hướng mới!)
                int backCount = 0;
                int startIdx = walkIds.size() - 2;
                for (int bIdx = startIdx; bIdx >= Math.max(1, startIdx - 3); bIdx--) {
                    long backNode = walkIds.get(bIdx);
                    if (backNode == 2938914930L || backNode == 2938914917L) break;
                    walkIds.add(backNode);
                    recentQueue.add(backNode);
                    prev = curr;
                    curr = backNode;
                    backCount++;
                    Set<Long> backNbs = adj.get(backNode);
                    if (backNbs != null && backNbs.size() >= 3) {
                        break;
                    }
                }
                if (backCount > 0) {
                    distOnCurrentStreet = 0.0;
                    currentStreetWantsTurnback = false;
                    segmentsSinceLastTurnback = 0;
                    continue;
                }
            }

            LatLng macroFromPt = prevPt;
            if (currPt != null && walkIds.size() >= 2) {
                for (int bIdx = walkIds.size() - 2; bIdx >= 0; bIdx--) {
                    LatLng candPt = nodes.get(walkIds.get(bIdx));
                    if (candPt != null && distanceMeters(candPt.lat, candPt.lng, currPt.lat, currPt.lng) >= 40.0) {
                        macroFromPt = candPt;
                        break;
                    }
                }
            }
            float macroInBrng = (macroFromPt != null && currPt != null)
                    ? bearingDegrees(macroFromPt.lat, macroFromPt.lng, currPt.lat, currPt.lng)
                    : inBrng;

            List<Long> sameStreetStraightCandidates = new ArrayList<>();
            List<Long> smoothForwardCandidates = new ArrayList<>();
            List<Long> nonBacktrackCandidates = new ArrayList<>();
            double maxAllowedRoam = Math.max(radiusMeters * 1.45, 3600.0);

            for (Long nb : nbSet) {
                if (nb == prev) continue;
                // Sau khi đã ra khỏi Cổng chính KTX Khu A ra đường lớn, không rẽ ngược lại vào trong cổng
                if ((nb == 2938914930L || nb == 2938914917L) && nbSet.size() > 1) continue;
                // Không tự ý rẽ vào điểm mở con lươn trừ khi đang chủ động thực hiện quay đầu giữa chừng
                if (medianEdges.contains(curr + "_" + nb) && !currentStreetWantsTurnback) continue;

                LatLng pt = nodes.get(nb);
                if (pt == null) continue;
                if (distanceMeters(roomLat, roomLng, pt.lat, pt.lng) > maxAllowedRoam) continue;

                nonBacktrackCandidates.add(nb);
                if (inBrng >= 0.0f && currPt != null) {
                    float outBrng = bearingDegrees(currPt.lat, currPt.lng, pt.lat, pt.lng);
                    double turnDiff = Math.abs(outBrng - inBrng);
                    if (turnDiff > 180.0) turnDiff = 360.0 - turnDiff;
                    double macroTurnDiff = Math.abs(outBrng - macroInBrng);
                    if (macroTurnDiff > 180.0) macroTurnDiff = 360.0 - macroTurnDiff;

                    String candEdgeName = edgeNames.getOrDefault(curr + "_" + nb, "");
                    if (turnDiff <= 38.0 && !activeStreetName.isEmpty() && activeStreetName.equals(candEdgeName)) {
                        sameStreetStraightCandidates.add(nb);
                    }
                    if (turnDiff <= 115.0 && macroTurnDiff <= 125.0) {
                        smoothForwardCandidates.add(nb);
                    }
                } else {
                    smoothForwardCandidates.add(nb);
                }
            }

            Set<Long> recentVisited = new HashSet<>(
                    recentQueue.subList(Math.max(0, recentQueue.size() - 45), recentQueue.size()));

            List<Long> unvisitedSameStreet = new ArrayList<>();
            for (Long nb : sameStreetStraightCandidates) {
                if (!recentVisited.contains(nb)) {
                    unvisitedSameStreet.add(nb);
                }
            }
            List<Long> unvisitedSmooth = new ArrayList<>();
            for (Long nb : smoothForwardCandidates) {
                if (!recentVisited.contains(nb)) {
                    unvisitedSmooth.add(nb);
                }
            }
            List<Long> unvisitedAny = new ArrayList<>();
            for (Long nb : nonBacktrackCandidates) {
                if (!recentVisited.contains(nb)) {
                    unvisitedAny.add(nb);
                }
            }

            // 75% cơ hội tiếp tục đi thẳng hết con đường hiện tại trước khi rẽ ở ngã tư!
            List<Long> pool;
            if (!unvisitedSameStreet.isEmpty() && RANDOM.nextInt(100) < 75) {
                pool = unvisitedSameStreet;
            } else if (!unvisitedSmooth.isEmpty()) {
                pool = unvisitedSmooth;
            } else if (!unvisitedAny.isEmpty()) {
                pool = unvisitedAny;
            } else if (!smoothForwardCandidates.isEmpty()) {
                pool = smoothForwardCandidates;
            } else if (!nonBacktrackCandidates.isEmpty()) {
                pool = nonBacktrackCandidates;
            } else {
                pool = new ArrayList<>(nbSet);
            }

            long next = pool.get(RANDOM.nextInt(pool.size()));
            LatLng nextPt = nodes.get(next);
            String stepStreet = edgeNames.getOrDefault(curr + "_" + next, "");
            double stepDist = (currPt != null && nextPt != null)
                    ? distanceMeters(currPt.lat, currPt.lng, nextPt.lat, nextPt.lng)
                    : 0.0;

            if (stepStreet.equals(activeStreetName)) {
                distOnCurrentStreet += stepDist;
            } else {
                activeStreetName = stepStreet;
                distOnCurrentStreet = stepDist;
                targetTurnbackDist = 160.0 + RANDOM.nextDouble() * 220.0;
                int turnbackChance = (modeRoll >= 60 && modeRoll < 82) ? 40 : 20;
                currentStreetWantsTurnback = (RANDOM.nextInt(100) < turnbackChance);
            }
            segmentsSinceLastTurnback++;

            walkIds.add(next);
            recentQueue.add(next);
            prev = curr;
            curr = next;
        }

        // Gộp/bỏ các nút giao quá sát nhau (< 14m, ví dụ cụm vạch qua đường)
        List<LatLng> cleanVertices = new ArrayList<>();
        for (int i = 0; i < walkIds.size(); i++) {
            long idCurr = walkIds.get(i);
            LatLng pCurr = nodes.get(idCurr);
            if (pCurr == null) continue;

            String stName = pCurr.streetName;
            double edgeOff = pCurr.sidewalkOffsetMeters;
            double sideSign = 1.0;
            if (i < walkIds.size() - 1) {
                String key = idCurr + "_" + walkIds.get(i + 1);
                if (edgeNames.containsKey(key)) stName = edgeNames.get(key);
                if (edgeOffsets.containsKey(key)) edgeOff = edgeOffsets.get(key);
                if (edgeOuterSideSigns.containsKey(key)) sideSign = edgeOuterSideSigns.get(key);
            } else if (i > 0) {
                String prevKey = walkIds.get(i - 1) + "_" + idCurr;
                if (edgeOffsets.containsKey(prevKey)) edgeOff = edgeOffsets.get(prevKey);
                if (edgeOuterSideSigns.containsKey(prevKey)) sideSign = edgeOuterSideSigns.get(prevKey);
            }

            if (!cleanVertices.isEmpty()) {
                LatLng last = cleanVertices.get(cleanVertices.size() - 1);
                double d = distanceMeters(last.lat, last.lng, pCurr.lat, pCurr.lng);
                if (d < 14.0 && i < walkIds.size() - 1) {
                    continue;
                }
            }
            cleanVertices.add(new LatLng(pCurr.lat, pCurr.lng, stName, edgeOff, sideSign));
        }

        // Chỉ loại bỏ gai nhiễu cực ngắn (< 20m cả hai cạnh), GIỮ NGUYÊN các đỉnh quay đầu giữa chừng thực sự!
        List<LatLng> nonLoopVertices = new ArrayList<>();
        for (int i = 0; i < cleanVertices.size(); i++) {
            LatLng cand = cleanVertices.get(i);
            if (nonLoopVertices.size() >= 2) {
                LatLng pA = nonLoopVertices.get(nonLoopVertices.size() - 2);
                LatLng pB = nonLoopVertices.get(nonLoopVertices.size() - 1);
                double dAB = distanceMeters(pA.lat, pA.lng, pB.lat, pB.lng);
                double dBC = distanceMeters(pB.lat, pB.lng, cand.lat, cand.lng);
                float b1 = bearingDegrees(pA.lat, pA.lng, pB.lat, pB.lng);
                float b2 = bearingDegrees(pB.lat, pB.lng, cand.lat, cand.lng);
                double turn = Math.abs(b2 - b1);
                if (turn > 180.0) turn = 360.0 - turn;
                if (turn > 135.0 && dAB < 20.0 && dBC < 20.0) {
                    nonLoopVertices.remove(nonLoopVertices.size() - 1);
                }
            }
            nonLoopVertices.add(cand);
        }

        // Dịch toàn bộ quỹ đạo từ tim đường nhựa ra sát VỈA HÈ BÊN NGOÀI (4.2m - 5.2m trên đường lớn, 2.2m trong KTX)
        // theo đúng hướng outerSideSign để TUYỆT ĐỐI KHÔNG bao giờ chạy giữa lòng đường hay dải phân cách giữa lộ!
        List<LatLng> rawShiftedCorners = new ArrayList<>();
        for (int i = 0; i < nonLoopVertices.size(); i++) {
            LatLng pCurr = nonLoopVertices.get(i);
            double safeOffset = Math.max(2.0, Math.min(5.2, pCurr.sidewalkOffsetMeters));
            if (i > 0 && i < nonLoopVertices.size() - 1) {
                LatLng pPrev = nonLoopVertices.get(i - 1);
                LatLng pNext = nonLoopVertices.get(i + 1);
                float bIn = bearingDegrees(pPrev.lat, pPrev.lng, pCurr.lat, pCurr.lng);
                float bOut = bearingDegrees(pCurr.lat, pCurr.lng, pNext.lat, pNext.lng);
                double normIn = (bIn + 90.0 * pPrev.outerSideSign + 360.0) % 360.0;
                double normOut = (bOut + 90.0 * pCurr.outerSideSign + 360.0) % 360.0;
                double turn = Math.abs(bOut - bIn);
                if (turn > 180.0) turn = 360.0 - turn;
                if (turn > 138.0) {
                    String turnLabel = pCurr.streetName.startsWith("Quay đầu")
                            ? pCurr.streetName
                            : ("Quay đầu giữa chừng — " + pCurr.streetName);
                    rawShiftedCorners.add(offsetByMeters(pCurr.lat, pCurr.lng, safeOffset, normIn, turnLabel, safeOffset));
                    rawShiftedCorners.add(offsetByMeters(pCurr.lat, pCurr.lng, 2.2, bIn, turnLabel, safeOffset));
                    rawShiftedCorners.add(offsetByMeters(pCurr.lat, pCurr.lng, safeOffset, normOut, turnLabel, safeOffset));
                    continue;
                }
                double nx = Math.cos(Math.toRadians(normIn)) + Math.cos(Math.toRadians(normOut));
                double ny = Math.sin(Math.toRadians(normIn)) + Math.sin(Math.toRadians(normOut));
                double outerNormal = (Math.hypot(nx, ny) > 0.1)
                        ? ((Math.toDegrees(Math.atan2(ny, nx)) + 360.0) % 360.0)
                        : normOut;
                rawShiftedCorners.add(offsetByMeters(pCurr.lat, pCurr.lng, safeOffset, outerNormal, pCurr.streetName, safeOffset));
            } else {
                float brng;
                double sign = pCurr.outerSideSign;
                if (i < nonLoopVertices.size() - 1) {
                    LatLng pNext = nonLoopVertices.get(i + 1);
                    brng = bearingDegrees(pCurr.lat, pCurr.lng, pNext.lat, pNext.lng);
                } else if (i > 0) {
                    LatLng pPrev = nonLoopVertices.get(i - 1);
                    brng = bearingDegrees(pPrev.lat, pPrev.lng, pCurr.lat, pCurr.lng);
                    sign = pPrev.outerSideSign;
                } else {
                    brng = 0.0f;
                }
                double outerNormal = (brng + 90.0 * sign + 360.0) % 360.0;
                rawShiftedCorners.add(offsetByMeters(pCurr.lat, pCurr.lng, safeOffset, outerNormal, pCurr.streetName, safeOffset));
            }
        }

        // Làm sạch sau khi dịch ra vỉa hè: loại bỏ các điểm quá gần (< 9m) hoặc góc nhọn cực ngắn ở giao lộ
        List<LatLng> shiftedCorners = new ArrayList<>();
        for (int i = 0; i < rawShiftedCorners.size(); i++) {
            LatLng pt = rawShiftedCorners.get(i);
            if (!shiftedCorners.isEmpty() && i < rawShiftedCorners.size() - 1) {
                LatLng last = shiftedCorners.get(shiftedCorners.size() - 1);
                if (distanceMeters(last.lat, last.lng, pt.lat, pt.lng) < 9.0) {
                    continue;
                }
            }
            if (shiftedCorners.size() >= 2) {
                LatLng pA = shiftedCorners.get(shiftedCorners.size() - 2);
                LatLng pB = shiftedCorners.get(shiftedCorners.size() - 1);
                double dAB = distanceMeters(pA.lat, pA.lng, pB.lat, pB.lng);
                double dBC = distanceMeters(pB.lat, pB.lng, pt.lat, pt.lng);
                float b1 = bearingDegrees(pA.lat, pA.lng, pB.lat, pB.lng);
                float b2 = bearingDegrees(pB.lat, pB.lng, pt.lat, pt.lng);
                double turn = Math.abs(b2 - b1);
                if (turn > 180.0) turn = 360.0 - turn;
                if (turn > 130.0 && (dAB < 16.0 || dBC < 16.0)) {
                    shiftedCorners.remove(shiftedCorners.size() - 1);
                }
            }
            shiftedCorners.add(pt);
        }

        List<LatLng> sidewalkPoints = subdivideAndSmoothSpline(shiftedCorners, 1.8);

        LatLng firstSw = sidewalkPoints.get(0);
        LatLng secondSw = sidewalkPoints.get(Math.min(1, sidewalkPoints.size() - 1));
        float swBrng = bearingDegrees(firstSw.lat, firstSw.lng, secondSw.lat, secondSw.lng);
        double distRoomToSw = distanceMeters(roomLat, roomLng, firstSw.lat, firstSw.lng);

        List<LatLng> rawLeadIn = new ArrayList<>();
        rawLeadIn.add(new LatLng(roomLat, roomLng, "Trong phòng KTX ➔ Lối ra", 0.0));
        if (distRoomToSw > 5.0) {
            rawLeadIn.add(offsetByMeters(
                    firstSw.lat,
                    firstSw.lng,
                    Math.min(7.5, distRoomToSw * 0.35),
                    (swBrng + 180.0) % 360.0,
                    "Cổng toà nhà ➔ Vỉa hè",
                    0.0));
        }
        rawLeadIn.add(new LatLng(firstSw.lat, firstSw.lng, firstSw.streetName, 0.0));
        List<LatLng> leadInPoints = subdivideLinear(rawLeadIn, 1.4);

        return new GeneratedPlan(adj.size(), leadInPoints, sidewalkPoints, routeStyleSummary);
    }

    private static long findNearestNodeId(
            double targetLat,
            double targetLng,
            Map<Long, LatLng> nodes,
            Map<Long, Set<Long>> adj) {
        long bestId = -1;
        double bestDist = Double.MAX_VALUE;
        for (Long id : adj.keySet()) {
            LatLng pt = nodes.get(id);
            if (pt != null) {
                double d = distanceMeters(targetLat, targetLng, pt.lat, pt.lng);
                if (d < bestDist) {
                    bestDist = d;
                    bestId = id;
                }
            }
        }
        return bestId;
    }

    private static void buildFallbackGrid(
            double roomLat,
            double roomLng,
            Map<Long, LatLng> nodes,
            Map<Long, Set<Long>> adj,
            Map<String, String> edgeNames,
            Map<String, Double> edgeOffsets) {
        nodes.clear();
        adj.clear();
        double stepM = 90.0;
        long id = 1;
        long[][] grid = new long[3][3];
        for (int r = -1; r <= 1; r++) {
            for (int c = -1; c <= 1; c++) {
                LatLng pNorth = offsetByMeters(roomLat, roomLng, r * stepM + 22.0, 0.0);
                LatLng pt = offsetByMeters(pNorth.lat, pNorth.lng, c * stepM + 22.0, 90.0, "Lối đi bộ nội khu KTX Khu A", 0.0);
                grid[r + 1][c + 1] = id;
                nodes.put(id, pt);
                adj.put(id, new HashSet<>());
                id++;
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                long u = grid[r][c];
                if (r + 1 < 3) {
                    long v = grid[r + 1][c];
                    adj.get(u).add(v);
                    adj.get(v).add(u);
                    edgeNames.put(u + "_" + v, "Lối đi bộ nội khu KTX Khu A");
                    edgeNames.put(v + "_" + u, "Lối đi bộ nội khu KTX Khu A");
                    edgeOffsets.put(u + "_" + v, 0.0);
                    edgeOffsets.put(v + "_" + u, 0.0);
                }
                if (c + 1 < 3) {
                    long v = grid[r][c + 1];
                    adj.get(u).add(v);
                    adj.get(v).add(u);
                    edgeNames.put(u + "_" + v, "Vỉa hè Quảng Trường Sáng Tạo");
                    edgeNames.put(v + "_" + u, "Vỉa hè Quảng Trường Sáng Tạo");
                    edgeOffsets.put(u + "_" + v, 3.6);
                    edgeOffsets.put(v + "_" + u, 3.6);
                }
            }
        }
    }

    private static List<Long> findShortestPath(
            long startId,
            long targetId,
            Map<Long, LatLng> nodes,
            Map<Long, Set<Long>> adj,
            Set<String> medianEdges,
            boolean allowMedianCrossings,
            boolean preventGateReentry) {
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        dist.put(startId, 0.0);

        while (visited.size() < adj.size()) {
            long u = -1;
            double bestD = Double.MAX_VALUE;
            for (Map.Entry<Long, Double> e : dist.entrySet()) {
                if (!visited.contains(e.getKey()) && e.getValue() < bestD) {
                    bestD = e.getValue();
                    u = e.getKey();
                }
            }
            if (u == -1 || u == targetId) break;
            visited.add(u);

            LatLng pu = nodes.get(u);
            Set<Long> nbs = adj.get(u);
            if (pu == null || nbs == null) continue;

            for (Long v : nbs) {
                if (visited.contains(v)) continue;
                if (!allowMedianCrossings && medianEdges != null && medianEdges.contains(u + "_" + v)) {
                    continue;
                }
                if (preventGateReentry && v != targetId && (v == 2938914930L || v == 2938914917L)) {
                    continue;
                }
                LatLng pv = nodes.get(v);
                if (pv == null) continue;
                double w = distanceMeters(pu.lat, pu.lng, pv.lat, pv.lng);
                double cand = bestD + w;
                if (cand < dist.getOrDefault(v, Double.MAX_VALUE)) {
                    dist.put(v, cand);
                    parent.put(v, u);
                }
            }
        }

        List<Long> path = new ArrayList<>();
        if (!parent.containsKey(targetId)) return path;
        long cur = targetId;
        while (cur != startId && parent.containsKey(cur)) {
            path.add(cur);
            cur = parent.get(cur);
        }
        path.add(startId);
        Collections.reverse(path);
        return path;
    }

    public static List<LatLng> subdivideLinear(List<LatLng> pts, double spacingMeters) {
        if (pts == null || pts.size() < 2) return pts;
        List<LatLng> dense = new ArrayList<>();
        for (int i = 0; i < pts.size() - 1; i++) {
            LatLng a = pts.get(i);
            LatLng b = pts.get(i + 1);
            double d = distanceMeters(a.lat, a.lng, b.lat, b.lng);
            int steps = Math.max(1, (int) Math.ceil(d / spacingMeters));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                dense.add(new LatLng(
                        a.lat + t * (b.lat - a.lat),
                        a.lng + t * (b.lng - a.lng),
                        a.streetName,
                        a.sidewalkOffsetMeters));
            }
        }
        dense.add(pts.get(pts.size() - 1));
        return dense;
    }

    public static List<LatLng> subdivideAndSmoothSpline(List<LatLng> points, double spacingMeters) {
        if (points == null || points.size() < 3) return subdivideLinear(points, spacingMeters);

        List<LatLng> current = new ArrayList<>(points);
        for (int iter = 0; iter < 3; iter++) {
            List<LatLng> next = new ArrayList<>();
            next.add(current.get(0));
            for (int i = 0; i < current.size() - 1; i++) {
                LatLng p0 = current.get(i);
                LatLng p1 = current.get(i + 1);
                double segLen = distanceMeters(p0.lat, p0.lng, p1.lat, p1.lng);
                if (segLen < 0.4) continue;
                double ratio = (segLen > 14.0) ? Math.min(0.28, 5.5 / segLen) : 0.25;
                next.add(new LatLng(
                        p0.lat + ratio * (p1.lat - p0.lat),
                        p0.lng + ratio * (p1.lng - p0.lng),
                        p0.streetName,
                        p0.sidewalkOffsetMeters));
                next.add(new LatLng(
                        p0.lat + (1.0 - ratio) * (p1.lat - p0.lat),
                        p0.lng + (1.0 - ratio) * (p1.lng - p0.lng),
                        p1.streetName,
                        p1.sidewalkOffsetMeters));
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return subdivideLinear(current, spacingMeters);
    }
}
