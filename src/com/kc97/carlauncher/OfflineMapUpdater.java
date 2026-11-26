package com.kc97.carlauncher;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device autonomous offline map pack updater for Chuy Oblast + Boom Gorge + Issyk-Kul Oblast!
 * Downloads only missing tiles into /sdcard/KC97MapCache so no PC is ever required to refresh/expand offline maps.
 */
public class OfflineMapUpdater {
    private static final double E = 0.0818191908426;

    public interface ProgressListener {
        void onProgress(int done, int total, int downloadedNew);
        void onFinished(boolean cancelled, int downloadedNew, int totalCached);
    }

    private final File cacheDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    public OfflineMapUpdater(Context ctx) {
        File ext = new File(Environment.getExternalStorageDirectory(), "KC97MapCache");
        if (!ext.exists()) ext.mkdirs();
        cacheDir = (ext.exists() && ext.canWrite()) ? ext : new File(ctx.getCacheDir(), "map_tiles");
    }

    public boolean isRunning() { return running.get(); }

    public void cancel() { cancelFlag.set(true); }

    public int countCachedTiles() {
        return countFiles(cacheDir);
    }

    private int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.getName().endsWith(".png") ? 1 : 0;
        File[] list = f.listFiles();
        if (list == null) return 0;
        int sum = 0;
        for (File c : list) sum += countFiles(c);
        return sum;
    }

    public void startSyncChuyBoomIssykKul(final ProgressListener listener) {
        if (!running.compareAndSet(false, true)) return;
        cancelFlag.set(false);

        new Thread(() -> {
            Set<String> keys = new HashSet<>();
            // 1. Full Chuy + Boom + Issyk-Kul overview (z=10..12)
            addBbox(keys, 42.05, 43.15, 73.25, 78.65, new int[]{10, 11, 12});
            // 2. Detailed z=13: Chuy Valley + Boom Gorge + North & South Issyk-Kul ring
            addBbox(keys, 42.65, 43.05, 73.70, 76.25, new int[]{13});
            addBbox(keys, 42.38, 42.85, 76.10, 78.55, new int[]{13});
            addBbox(keys, 42.08, 42.45, 76.10, 78.50, new int[]{13});
            // 3. High-detail z=14: Kara-Balta -> Belovodskoe -> Sokuluk -> Bishkek -> Tokmok -> Boom Gorge -> Balykchy -> Cholpon-Ata -> Karakol
            addBbox(keys, 42.77, 42.90, 73.82, 74.40, new int[]{14}); // Kara-Balta / Belovodskoe / Sokuluk
            addBbox(keys, 42.78, 42.96, 74.45, 74.88, new int[]{14}); // Bishkek / Kant
            addBbox(keys, 42.80, 42.88, 75.24, 75.36, new int[]{14}); // Tokmok
            addBbox(keys, 42.45, 42.80, 75.72, 76.24, new int[]{14}); // Full Boom Gorge + Balykchy high detail!
            addBbox(keys, 42.61, 42.70, 77.00, 77.25, new int[]{14}); // Cholpon-Ata / Bosteri
            addBbox(keys, 42.45, 42.53, 78.34, 78.44, new int[]{14}); // Karakol

            List<int[]> missing = new ArrayList<>();
            int alreadyCached = 0;
            for (String k : keys) {
                String[] p = k.split("_");
                int z = Integer.parseInt(p[0]);
                int x = Integer.parseInt(p[1]);
                int y = Integer.parseInt(p[2]);
                File f = new File(cacheDir, z + "/" + x + "/" + y + ".png");
                if (f.exists() && f.length() > 100) {
                    alreadyCached++;
                } else {
                    missing.add(new int[]{z, x, y});
                }
            }

            final int totalMissing = missing.size();
            if (totalMissing == 0) {
                final int cachedNow = alreadyCached;
                running.set(false);
                mainHandler.post(() -> listener.onFinished(false, 0, cachedNow));
                return;
            }

            AtomicInteger done = new AtomicInteger(0);
            AtomicInteger okNew = new AtomicInteger(0);
            ExecutorService pool = Executors.newFixedThreadPool(6);
            for (final int[] tile : missing) {
                pool.execute(() -> {
                    if (cancelFlag.get()) return;
                    if (downloadTile(tile[0], tile[1], tile[2])) {
                        okNew.incrementAndGet();
                    }
                    int d = done.incrementAndGet();
                    if (d % 15 == 0 || d == totalMissing) {
                        int n = okNew.get();
                        mainHandler.post(() -> listener.onProgress(d, totalMissing, n));
                    }
                });
            }
            pool.shutdown();
            try {
                while (!pool.isTerminated()) {
                    if (cancelFlag.get()) {
                        pool.shutdownNow();
                        break;
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException ignored) {}

            int finalNew = okNew.get();
            int finalTotal = alreadyCached + finalNew;
            boolean wasCancelled = cancelFlag.get();
            running.set(false);
            mainHandler.post(() -> listener.onFinished(wasCancelled, finalNew, finalTotal));
        }, "OfflineMap-Sync").start();
    }

    private boolean downloadTile(int z, int x, int y) {
        HttpURLConnection conn = null;
        try {
            String urlStr = String.format(Locale.US,
                    "https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x=%d&y=%d&z=%d&scale=1&lang=ru_RU",
                    x, y, z);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://yandex.ru/maps/");
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                is.close();
                byte[] bytes = baos.toByteArray();
                if (bytes.length > 100) {
                    File out = new File(cacheDir, z + "/" + x + "/" + y + ".png");
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(bytes);
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private static void addBbox(Set<String> out, double latMin, double latMax, double lonMin, double lonMax, int[] zooms) {
        for (int z : zooms) {
            int[] t1 = latLonToTile(latMax, lonMin, z);
            int[] t2 = latLonToTile(latMin, lonMax, z);
            int xMin = Math.min(t1[0], t2[0]);
            int xMax = Math.max(t1[0], t2[0]);
            int yMin = Math.min(t1[1], t2[1]);
            int yMax = Math.max(t1[1], t2[1]);
            for (int x = xMin; x <= xMax; x++) {
                for (int y = yMin; y <= yMax; y++) {
                    out.add(z + "_" + x + "_" + y);
                }
            }
        }
    }

    private static int[] latLonToTile(double lat, double lon, int z) {
        int n = 1 << z;
        double radLon = Math.toRadians(lon);
        double radLat = Math.toRadians(Math.max(-85.0, Math.min(85.0, lat)));
        double esin = E * Math.sin(radLat);
        double mercY = Math.log(Math.tan(Math.PI / 4.0 + radLat / 2.0) * Math.pow((1.0 - esin) / (1.0 + esin), E / 2.0));
        int x = (int) ((radLon + Math.PI) / (2.0 * Math.PI) * n);
        int y = (int) ((Math.PI - mercY) / (2.0 * Math.PI) * n);
        return new int[]{x, y};
    }
}
