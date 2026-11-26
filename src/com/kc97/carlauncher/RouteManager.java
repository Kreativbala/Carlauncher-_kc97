package com.kc97.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight routing & geocoding manager for KC97 Cockpit v2.0.
 * - Online: OSRM public driving router + Nominatim address search (bounded to Chuy & Issyk-Kul).
 * - Offline / Boom Gorge resilient:
 *   1) Persists active OSRM route polyline on disk (/sdcard/KC97MapCache/active_route.txt) so losing 4G
 *      or restarting in Boom Gorge keeps the full route intact.
 *   2) Built-in offline highway corridor (Kara-Balta -> Belovodskoe -> Bishkek -> Tokmok -> Kemin -> Boom -> Balykchy -> Cholpon-Ata -> Karakol)
 *      so you can build a route to major Chuy/Issyk-Kul destinations even with ZERO internet!
 */
public class RouteManager {
    private static final String TAG = "KC97_ROUTE";
    private static final String PREFS = "kc97_routes";

    public static class Place {
        public final String name;
        public final double lat;
        public final double lon;

        public Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static class ActiveRoute {
        public String destName;
        public double destLat;
        public double destLon;
        public double totalKm;
        public int etaMinutes;
        public boolean offlineFallback;
        public List<double[]> points = Collections.emptyList(); // [lat, lon]
    }

    public interface RouteCallback {
        void onRouteReady(ActiveRoute route, String message);
    }

    public interface SearchCallback {
        void onResults(List<Place> results, String error);
    }

    // Built-in offline Chuy & Issyk-Kul destinations (always available with 0 internet!)
    public static final Place[] BUILTIN_PLACES = new Place[]{
            new Place("Бишкек (Центр / Ала-Тоо)", 42.8764, 74.6037),
            new Place("Беловодское", 42.8295, 74.1086),
            new Place("Кара-Балта", 42.8142, 73.8481),
            new Place("Сокулук", 42.8568, 74.2908),
            new Place("Токмок", 42.8419, 75.3015),
            new Place("Боом / Балыкчы", 42.4602, 76.1871),
            new Place("Чолпон-Ата (Иссык-Куль)", 42.6494, 77.0819),
            new Place("Каракол (Иссык-Куль)", 42.4907, 78.3936)
    };

    // Main M-41 / A-365 Chuy -> Boom Gorge -> Issyk-Kul highway spine for offline corridor routing!
    private static final double[][] HIGHWAY_SPINE = new double[][]{
            {42.8142, 73.8481}, // Kara-Balta
            {42.8295, 74.1086}, // Belovodskoe
            {42.8568, 74.2908}, // Sokuluk
            {42.8764, 74.6037}, // Bishkek
            {42.8911, 74.8508}, // Kant
            {42.8419, 75.3015}, // Tokmok
            {42.7858, 75.6917}, // Kemin
            {42.6815, 75.8750}, // Boom Gorge North
            {42.5630, 75.8935}, // Boom Gorge Mid
            {42.4820, 76.0210}, // Boom Gorge South
            {42.4602, 76.1871}, // Balykchy
            {42.5562, 76.6635}, // Tamchy
            {42.6494, 77.0819}, // Cholpon-Ata
            {42.7240, 77.5230}, // Ananyevo
            {42.6950, 78.0010}, // Tyup
            {42.4907, 78.3936}  // Karakol
    };

    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File routeFile;
    private volatile ActiveRoute activeRoute;

    public RouteManager(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File ext = new File(Environment.getExternalStorageDirectory(), "KC97MapCache");
        if (!ext.exists()) ext.mkdirs();
        File dir = (ext.exists() && ext.canWrite()) ? ext : ctx.getFilesDir();
        this.routeFile = new File(dir, "active_route.txt");
        loadPersistedRoute();
    }

    public ActiveRoute getActiveRoute() {
        return activeRoute;
    }

    public void saveFavorite(String key, double lat, double lon) {
        prefs.edit()
                .putString(key + "_lat", String.valueOf(lat))
                .putString(key + "_lon", String.valueOf(lon))
                .apply();
    }

    public Place getFavorite(String key, String label) {
        String sLat = prefs.getString(key + "_lat", null);
        String sLon = prefs.getString(key + "_lon", null);
        if (sLat == null || sLon == null) return null;
        try {
            return new Place(label, Double.parseDouble(sLat), Double.parseDouble(sLon));
        } catch (Throwable e) {
            return null;
        }
    }

    public void clearRoute() {
        activeRoute = null;
        try { if (routeFile.exists()) routeFile.delete(); } catch (Throwable ignored) {}
    }

    /**
     * Recomputes remaining distance & ETA along the active route as the car moves (100% offline!).
     */
    public void updateProgress(double carLat, double carLon) {
        ActiveRoute r = activeRoute;
        if (r == null || r.points.isEmpty()) return;
        // Find nearest point on polyline
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < r.points.size(); i++) {
            double[] p = r.points.get(i);
            double d = SpeedCameraManager.haversineMeters(carLat, carLon, p[0], p[1]);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        double remM = bestDist;
        for (int i = bestIdx; i < r.points.size() - 1; i++) {
            double[] a = r.points.get(i);
            double[] b = r.points.get(i + 1);
            remM += SpeedCameraManager.haversineMeters(a[0], a[1], b[0], b[1]);
        }
        r.totalKm = remM / 1000.0;
        r.etaMinutes = Math.max(1, (int) Math.round((r.totalKm / 55.0) * 60.0));
        if (remM < 80.0) {
            // Arrived at destination!
            clearRoute();
        }
    }

    public void searchAddressAsync(final String query, final SearchCallback cb) {
        new Thread(() -> {
            List<Place> out = new ArrayList<>();
            // 1. Always check offline built-in Chuy/Issyk-Kul places first!
            String ql = query.trim().toLowerCase(Locale.ROOT);
            for (Place p : BUILTIN_PLACES) {
                if (p.name.toLowerCase(Locale.ROOT).contains(ql)) {
                    out.add(p);
                }
            }
            // 2. Query Nominatim (bounded to Chuy + Issyk-Kul, KG)
            HttpURLConnection conn = null;
            try {
                String urlStr = "https://nominatim.openstreetmap.org/search?format=json&limit=6&accept-language=ru"
                        + "&countrycodes=kg&viewbox=73.2,43.2,78.7,42.0&q="
                        + URLEncoder.encode(query.trim(), "UTF-8");
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(7000);
                conn.setRequestProperty("User-Agent", "KC97CockpitLauncher/2.0");
                if (conn.getResponseCode() == 200) {
                    String json = readStream(conn);
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String disp = o.optString("display_name", "");
                        // Shorten long Nominatim label to first 2 segments
                        String[] segs = disp.split(",");
                        String shortName = segs.length >= 2 ? (segs[0].trim() + ", " + segs[1].trim()) : disp;
                        double lat = Double.parseDouble(o.getString("lat"));
                        double lon = Double.parseDouble(o.getString("lon"));
                        out.add(new Place(shortName, lat, lon));
                    }
                }
                mainHandler.post(() -> cb.onResults(out, out.isEmpty() ? "Ничего не найдено" : null));
            } catch (Throwable e) {
                mainHandler.post(() -> cb.onResults(out, out.isEmpty() ? "Нет интернета (доступны офлайн-города)" : null));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "Route-Search").start();
    }

    public void buildRouteAsync(final double fromLat, final double fromLon,
                                final double toLat, final double toLon,
                                final String destName, final RouteCallback cb) {
        new Thread(() -> {
            ActiveRoute route = tryOsrmRoute(fromLat, fromLon, toLat, toLon, destName);
            String msg;
            if (route != null) {
                msg = String.format(Locale.US, "Маршрут: %.1f км (~%d мин)", route.totalKm, route.etaMinutes);
            } else {
                // Offline fallback (e.g., inside Boom Gorge without 4G!)
                route = buildOfflineCorridorRoute(fromLat, fromLon, toLat, toLon, destName);
                msg = String.format(Locale.US, "Офлайн-маршрут: %.1f км (~%d мин)", route.totalKm, route.etaMinutes);
            }
            activeRoute = route;
            persistRoute(route);
            final ActiveRoute finalR = route;
            final String finalMsg = msg;
            mainHandler.post(() -> cb.onRouteReady(finalR, finalMsg));
        }, "Route-Builder").start();
    }

    private ActiveRoute tryOsrmRoute(double fromLat, double fromLon, double toLat, double toLon, String destName) {
        HttpURLConnection conn = null;
        try {
            String urlStr = String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson",
                    fromLon, fromLat, toLon, toLat);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "KC97CockpitLauncher/2.0");
            if (conn.getResponseCode() != 200) return null;

            JSONObject root = new JSONObject(readStream(conn));
            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) return null;

            JSONObject r0 = routes.getJSONObject(0);
            double distM = r0.optDouble("distance", 0);
            double durS = r0.optDouble("duration", 0);
            JSONArray coords = r0.getJSONObject("geometry").getJSONArray("coordinates");

            List<double[]> pts = new ArrayList<>(coords.length());
            // Decimate very dense routes slightly so Canvas stays ultra-fast
            int step = Math.max(1, coords.length() / 400);
            for (int i = 0; i < coords.length(); i += step) {
                JSONArray c = coords.getJSONArray(i);
                pts.add(new double[]{c.getDouble(1), c.getDouble(0)});
            }
            JSONArray last = coords.getJSONArray(coords.length() - 1);
            pts.add(new double[]{last.getDouble(1), last.getDouble(0)});

            ActiveRoute ar = new ActiveRoute();
            ar.destName = destName;
            ar.destLat = toLat;
            ar.destLon = toLon;
            ar.totalKm = distM / 1000.0;
            ar.etaMinutes = Math.max(1, (int) Math.round(durS / 60.0));
            ar.offlineFallback = false;
            ar.points = pts;
            return ar;
        } catch (Throwable e) {
            Log.w(TAG, "OSRM unavailable, using offline corridor: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Offline highway spine routing for Chuy -> Boom Gorge -> Issyk-Kul when cellular data is unavailable.
     */
    private ActiveRoute buildOfflineCorridorRoute(double fromLat, double fromLon, double toLat, double toLon, String destName) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{fromLat, fromLon});

        int startIdx = nearestSpineIndex(fromLat, fromLon);
        int endIdx = nearestSpineIndex(toLat, toLon);
        double directDist = SpeedCameraManager.haversineMeters(fromLat, fromLon, toLat, toLon);

        // Use highway spine waypoints for inter-city trips (> 12 km)
        if (directDist > 12000.0 && startIdx != endIdx) {
            int dir = startIdx < endIdx ? 1 : -1;
            for (int i = startIdx; i != endIdx + dir; i += dir) {
                pts.add(new double[]{HIGHWAY_SPINE[i][0], HIGHWAY_SPINE[i][1]});
            }
        }
        pts.add(new double[]{toLat, toLon});

        double distM = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            distM += SpeedCameraManager.haversineMeters(pts.get(i)[0], pts.get(i)[1], pts.get(i + 1)[0], pts.get(i + 1)[1]);
        }

        ActiveRoute ar = new ActiveRoute();
        ar.destName = destName;
        ar.destLat = toLat;
        ar.destLon = toLon;
        ar.totalKm = (distM * 1.12) / 1000.0; // road-winding estimate
        ar.etaMinutes = Math.max(1, (int) Math.round((ar.totalKm / 60.0) * 60.0));
        ar.offlineFallback = true;
        ar.points = pts;
        return ar;
    }

    private int nearestSpineIndex(double lat, double lon) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < HIGHWAY_SPINE.length; i++) {
            double d = SpeedCameraManager.haversineMeters(lat, lon, HIGHWAY_SPINE[i][0], HIGHWAY_SPINE[i][1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void persistRoute(ActiveRoute r) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(r.destName.replace("\n", " ")).append('\n');
            sb.append(String.format(Locale.US, "%.5f,%.5f,%.2f,%d,%d\n",
                    r.destLat, r.destLon, r.totalKm, r.etaMinutes, r.offlineFallback ? 1 : 0));
            for (double[] p : r.points) {
                sb.append(String.format(Locale.US, "%.5f,%.5f\n", p[0], p[1]));
            }
            try (FileOutputStream fos = new FileOutputStream(routeFile)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private void loadPersistedRoute() {
        if (!routeFile.exists() || routeFile.length() < 15) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(routeFile), StandardCharsets.UTF_8))) {
            String name = br.readLine();
            String meta = br.readLine();
            if (name == null || meta == null) return;
            String[] mp = meta.split(",");
            ActiveRoute ar = new ActiveRoute();
            ar.destName = name.trim();
            ar.destLat = Double.parseDouble(mp[0]);
            ar.destLon = Double.parseDouble(mp[1]);
            ar.totalKm = Double.parseDouble(mp[2]);
            ar.etaMinutes = Integer.parseInt(mp[3]);
            ar.offlineFallback = mp.length > 4 && "1".equals(mp[4].trim());
            List<double[]> pts = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] xy = line.split(",");
                if (xy.length >= 2) {
                    pts.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                }
            }
            if (pts.size() >= 2) {
                ar.points = pts;
                activeRoute = ar;
            }
        } catch (Throwable ignored) {}
    }

    private static String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
