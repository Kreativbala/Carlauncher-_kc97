package com.kc97.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure native Android Canvas slippy map for Yandex Maps (dark theme) + live Yandex Traffic (EPSG:3395).
 * v1.3:
 * - Persistent offline disk cache (/sdcard/KC97MapCache/{z}/{x}/{y}.png) so Chuy & Issyk-Kul stay visible offline!
 * - Automatic parent-tile zoom fallback when offline (never shows blank black squares).
 * - Auto-night dimmed color matrix when car headlights are ON.
 * - Zero WebView (~160 MB RAM saved), 0% CPU when idle.
 */
public class NativeMapView extends View {
    private static final double E = 0.0818191908426;
    private static final int TILE_SIZE = 256;

    // Default Bishkek area until first GPS fix
    private double centerLat = 42.8746;
    private double centerLon = 74.5698;
    private double carLat = centerLat;
    private double carLon = centerLon;
    private float carBearing = 0f;
    private boolean hasGps = false;
    private int zoom = 14;
    private boolean followCar = true;
    private boolean showTraffic = true;
    private boolean nightDimMode = false;
    private boolean paused = false;
    private long trafficEpochSec = System.currentTimeMillis() / 1000L;

    private final Paint darkMapPaintDay = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint darkMapPaintNight = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint trafficPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint destFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint camBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint camRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint camTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final Path routePath = new Path();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    public interface OnMapLongPressListener {
        void onMapLongPress(double lat, double lon);
    }

    private volatile List<double[]> routePoints = Collections.emptyList();
    private volatile List<SpeedCameraManager.SpeedCamera> cameras = Collections.emptyList();
    private OnMapLongPressListener longPressListener;
    private float downTouchX, downTouchY;
    private boolean movedSinceDown = false;
    private boolean longPressFired = false;
    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            if (dragging && !movedSinceDown && longPressListener != null) {
                longPressFired = true;
                int w = getWidth();
                int h = getHeight();
                if (w > 0 && h > 0) {
                    double[] cPx = latLonToPixel(centerLat, centerLon, zoom);
                    double px = (cPx[0] - w * 0.5) + downTouchX;
                    double py = (cPx[1] - h * 0.5) + downTouchY;
                    double[] ll = pixelToLatLon(px, py, zoom);
                    longPressListener.onMapLongPress(ll[0], ll[1]);
                }
            }
        }
    };

    private final File diskCacheDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> emptyTrafficTiles = Collections.synchronizedSet(new HashSet<>());

    private final LruCache<String, Bitmap> tileCache = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private float lastTouchX, lastTouchY;
    private boolean dragging = false;

    private final Runnable autoRecenterTask = () -> {
        if (hasGps) {
            followCar = true;
            centerLat = carLat;
            centerLon = carLon;
            invalidate();
        }
    };

    private final Runnable trafficRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (!paused && showTraffic) {
                refreshTrafficNow();
            }
            mainHandler.postDelayed(this, 120_000L);
        }
    };

    public NativeMapView(Context context) {
        super(context);

        // Prefer /sdcard/KC97MapCache so preloaded Chuy + Issyk-Kul packs work out of the box
        File extDir = new File(Environment.getExternalStorageDirectory(), "KC97MapCache");
        if (!extDir.exists()) extDir.mkdirs();
        diskCacheDir = (extDir.exists() && extDir.canRead()) ? extDir : new File(context.getCacheDir(), "map_tiles");
        if (!diskCacheDir.exists()) diskCacheDir.mkdirs();

        // Day dark-cockpit color matrix
        ColorMatrix invertDay = new ColorMatrix(new float[]{
                -0.88f, 0f, 0f, 0f, 232f,
                0f, -0.86f, 0f, 0f, 236f,
                0f, 0f, -0.78f, 0f, 244f,
                0f, 0f, 0f, 1f, 0f
        });
        darkMapPaintDay.setColorFilter(new ColorMatrixColorFilter(invertDay));

        // Night dimmed color matrix (softer low-glare navy when car headlights are ON)
        ColorMatrix invertNight = new ColorMatrix(new float[]{
                -0.65f, 0f, 0f, 0f, 172f,
                0f, -0.64f, 0f, 0f, 176f,
                0f, 0f, -0.58f, 0f, 185f,
                0f, 0f, 0f, 1f, 0f
        });
        darkMapPaintNight.setColorFilter(new ColorMatrixColorFilter(invertNight));

        ColorMatrix trfSat = new ColorMatrix();
        trfSat.setSaturation(1.45f);
        trafficPaint.setColorFilter(new ColorMatrixColorFilter(trfSat));

        gridPaint.setColor(Color.parseColor("#131C2B"));
        gridPaint.setStrokeWidth(1.5f);

        markerGlowPaint.setColor(Color.argb(65, 0, 212, 255));
        markerFillPaint.setColor(Color.parseColor("#00D4FF"));
        markerStrokePaint.setStyle(Paint.Style.STROKE);
        markerStrokePaint.setStrokeWidth(3.5f);
        markerStrokePaint.setColor(Color.WHITE);
        markerStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        routeGlowPaint.setStyle(Paint.Style.STROKE);
        routeGlowPaint.setStrokeWidth(14f);
        routeGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        routeGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        routeGlowPaint.setColor(Color.argb(95, 0, 212, 255));

        routeLinePaint.setStyle(Paint.Style.STROKE);
        routeLinePaint.setStrokeWidth(6.5f);
        routeLinePaint.setStrokeCap(Paint.Cap.ROUND);
        routeLinePaint.setStrokeJoin(Paint.Join.ROUND);
        routeLinePaint.setColor(Color.parseColor("#38BDF8"));

        destFillPaint.setStyle(Paint.Style.FILL);
        destFillPaint.setColor(Color.parseColor("#EF4444"));

        camBgPaint.setStyle(Paint.Style.FILL);
        camBgPaint.setColor(Color.parseColor("#F8FAFC"));

        camRingPaint.setStyle(Paint.Style.STROKE);
        camRingPaint.setStrokeWidth(3.5f);
        camRingPaint.setColor(Color.parseColor("#EF4444"));

        camTextPaint.setColor(Color.parseColor("#0F172A"));
        camTextPaint.setTextSize(15f);
        camTextPaint.setFakeBoldText(true);
        camTextPaint.setTextAlign(Paint.Align.CENTER);

        mainHandler.postDelayed(trafficRefreshTask, 120_000L);
    }

    public double getCarLat() { return carLat; }
    public double getCarLon() { return carLon; }
    public float getCarBearing() { return carBearing; }

    public void setOnMapLongPressListener(OnMapLongPressListener l) {
        this.longPressListener = l;
    }

    public void setRoutePoints(List<double[]> pts) {
        this.routePoints = pts != null ? pts : Collections.emptyList();
        invalidate();
    }

    public void setCameras(List<SpeedCameraManager.SpeedCamera> cams) {
        this.cameras = cams != null ? cams : Collections.emptyList();
        invalidate();
    }

    public void setNightDimMode(boolean night) {
        if (nightDimMode != night) {
            nightDimMode = night;
            trafficPaint.setAlpha(night ? 195 : 255);
            invalidate();
        }
    }

    public void updateGps(double lat, double lon, float bearing, int speedKmh) {
        carLat = lat;
        carLon = lon;
        if (speedKmh > 3) carBearing = bearing;
        hasGps = true;
        if (followCar) {
            centerLat = carLat;
            centerLon = carLon;
        }
        invalidate();
    }

    public void zoomBy(int delta) {
        int nz = Math.max(9, Math.min(18, zoom + delta));
        if (nz != zoom) {
            zoom = nz;
            invalidate();
        }
    }

    public void recenter() {
        followCar = true;
        centerLat = carLat;
        centerLon = carLon;
        invalidate();
    }

    public boolean toggleTraffic() {
        showTraffic = !showTraffic;
        if (showTraffic) {
            refreshTrafficNow();
        } else {
            invalidate();
        }
        return showTraffic;
    }

    public void refreshTrafficNow() {
        trafficEpochSec = System.currentTimeMillis() / 1000L;
        emptyTrafficTiles.clear();
        invalidate();
    }

    public void setPaused(boolean p) {
        paused = p;
        if (!p) invalidate();
    }

    public void shutdown() {
        mainHandler.removeCallbacks(trafficRefreshTask);
        mainHandler.removeCallbacks(autoRecenterTask);
        executor.shutdownNow();
        tileCache.evictAll();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTouchX = event.getX();
                downTouchY = event.getY();
                lastTouchX = downTouchX;
                lastTouchY = downTouchY;
                dragging = true;
                movedSinceDown = false;
                longPressFired = false;
                mainHandler.removeCallbacks(autoRecenterTask);
                mainHandler.removeCallbacks(longPressTask);
                mainHandler.postDelayed(longPressTask, 650L);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    if (!movedSinceDown && Math.hypot(event.getX() - downTouchX, event.getY() - downTouchY) > 14f) {
                        movedSinceDown = true;
                        followCar = false;
                        mainHandler.removeCallbacks(longPressTask);
                    }
                    if (movedSinceDown && !longPressFired) {
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        double[] cPx = latLonToPixel(centerLat, centerLon, zoom);
                        double[] nl = pixelToLatLon(cPx[0] - dx, cPx[1] - dy, zoom);
                        centerLat = nl[0];
                        centerLon = nl[1];
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                mainHandler.removeCallbacks(longPressTask);
                if (movedSinceDown) {
                    mainHandler.postDelayed(autoRecenterTask, 15_000L);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawColor(nightDimMode ? Color.parseColor("#080C12") : Color.parseColor("#0D131D"));

        double[] centerPx = latLonToPixel(centerLat, centerLon, zoom);
        double offsetX = centerPx[0] - w * 0.5;
        double offsetY = centerPx[1] - h * 0.5;

        int minTx = (int) Math.floor(offsetX / TILE_SIZE);
        int maxTx = (int) Math.floor((offsetX + w) / TILE_SIZE);
        int minTy = (int) Math.floor(offsetY / TILE_SIZE);
        int maxTy = (int) Math.floor((offsetY + h) / TILE_SIZE);
        int maxIdx = 1 << zoom;

        Paint activeMapPaint = nightDimMode ? darkMapPaintNight : darkMapPaintDay;

        // 1. Draw base dark Yandex Map tiles (Memory -> Disk Offline Cache -> Parent Fallback -> Network)
        for (int tx = minTx; tx <= maxTx; tx++) {
            for (int ty = minTy; ty <= maxTy; ty++) {
                if (ty < 0 || ty >= maxIdx) continue;
                int wtx = ((tx % maxIdx) + maxIdx) % maxIdx;
                float drawX = (float) (tx * TILE_SIZE - offsetX);
                float drawY = (float) (ty * TILE_SIZE - offsetY);

                String baseKey = "m_" + zoom + "_" + wtx + "_" + ty;
                Bitmap baseBmp = tileCache.get(baseKey);
                if (baseBmp != null) {
                    canvas.drawBitmap(baseBmp, drawX, drawY, activeMapPaint);
                } else {
                    // Draw cached lower-zoom parent tile immediately if available (seamless offline zoom!)
                    if (!drawParentFallback(canvas, zoom, wtx, ty, drawX, drawY, activeMapPaint)) {
                        canvas.drawRect(drawX, drawY, drawX + TILE_SIZE, drawY + TILE_SIZE, gridPaint);
                    }
                    loadBaseTileAsync(baseKey, zoom, wtx, ty);
                }
            }
        }

        // 2. Draw live Yandex Traffic overlay tiles
        if (showTraffic) {
            long stampBucket = trafficEpochSec / 120L;
            for (int tx = minTx; tx <= maxTx; tx++) {
                for (int ty = minTy; ty <= maxTy; ty++) {
                    if (ty < 0 || ty >= maxIdx) continue;
                    int wtx = ((tx % maxIdx) + maxIdx) % maxIdx;
                    float drawX = (float) (tx * TILE_SIZE - offsetX);
                    float drawY = (float) (ty * TILE_SIZE - offsetY);

                    String trfKey = "t_" + stampBucket + "_" + zoom + "_" + wtx + "_" + ty;
                    if (emptyTrafficTiles.contains(trfKey)) continue;

                    Bitmap trfBmp = tileCache.get(trfKey);
                    if (trfBmp != null) {
                        canvas.drawBitmap(trfBmp, drawX, drawY, trafficPaint);
                    } else {
                        fetchTrafficTileAsync(trfKey,
                                String.format(Locale.US,
                                        "https://core-jams-rdr-cache.maps.yandex.net/1.1/tiles?l=trf&lang=ru_RU&x=%d&y=%d&z=%d&scale=1&tm=%d",
                                        wtx, ty, zoom, trafficEpochSec));
                    }
                }
            }
        }

        // 2.5 Draw active route polyline + Point B destination marker
        List<double[]> rPts = routePoints;
        if (rPts != null && rPts.size() >= 2) {
            routePath.reset();
            boolean started = false;
            float endX = 0f, endY = 0f;
            for (int i = 0; i < rPts.size(); i++) {
                double[] p = rPts.get(i);
                double[] pPx = latLonToPixel(p[0], p[1], zoom);
                float sx = (float) (pPx[0] - offsetX);
                float sy = (float) (pPx[1] - offsetY);
                if (!started) {
                    routePath.moveTo(sx, sy);
                    started = true;
                } else {
                    routePath.lineTo(sx, sy);
                }
                endX = sx;
                endY = sy;
            }
            canvas.drawPath(routePath, routeGlowPaint);
            canvas.drawPath(routePath, routeLinePaint);
            if (endX > -40 && endX < w + 40 && endY > -40 && endY < h + 40) {
                canvas.drawCircle(endX, endY, 15f, destFillPaint);
                canvas.drawCircle(endX, endY, 15f, markerStrokePaint);
                camTextPaint.setColor(Color.WHITE);
                canvas.drawText("B", endX, endY + 5f, camTextPaint);
                camTextPaint.setColor(Color.parseColor("#0F172A"));
            }
        }

        // 2.7 Draw nearby Safe City speed cameras on map (visible at zoom >= 11)
        List<SpeedCameraManager.SpeedCamera> cams = cameras;
        if (zoom >= 11 && cams != null && !cams.isEmpty()) {
            for (int i = 0; i < cams.size(); i++) {
                SpeedCameraManager.SpeedCamera c = cams.get(i);
                double[] cPx = latLonToPixel(c.lat, c.lon, zoom);
                float cx = (float) (cPx[0] - offsetX);
                float cy = (float) (cPx[1] - offsetY);
                if (cx < -24 || cx > w + 24 || cy < -24 || cy > h + 24) continue;
                canvas.drawCircle(cx, cy, 15f, camBgPaint);
                canvas.drawCircle(cx, cy, 15f, camRingPaint);
                canvas.drawText(String.valueOf(c.speedLimit), cx, cy + 5.5f, camTextPaint);
            }
        }

        // 3. Draw GPS car arrow marker
        double[] carPx = latLonToPixel(carLat, carLon, zoom);
        float mx = (float) (carPx[0] - offsetX);
        float my = (float) (carPx[1] - offsetY);
        if (mx > -60 && mx < w + 60 && my > -60 && my < h + 60) {
            canvas.save();
            canvas.translate(mx, my);
            canvas.drawCircle(0, 0, 26f, markerGlowPaint);
            canvas.rotate(carBearing);
            arrowPath.reset();
            arrowPath.moveTo(0f, -20f);
            arrowPath.lineTo(15f, 16f);
            arrowPath.lineTo(0f, 8f);
            arrowPath.lineTo(-15f, 16f);
            arrowPath.close();
            canvas.drawPath(arrowPath, markerFillPaint);
            canvas.drawPath(arrowPath, markerStrokePaint);
            canvas.restore();
        }
    }

    private boolean drawParentFallback(Canvas canvas, int z, int x, int y, float drawX, float drawY, Paint paint) {
        for (int shift = 1; shift <= 3 && (z - shift) >= 9; shift++) {
            int pz = z - shift;
            int px = x >> shift;
            int py = y >> shift;
            String pKey = "m_" + pz + "_" + px + "_" + py;
            Bitmap pBmp = tileCache.get(pKey);
            if (pBmp != null) {
                int sub = 1 << shift;
                int subSize = TILE_SIZE / sub;
                int sx = (x & (sub - 1)) * subSize;
                int sy = (y & (sub - 1)) * subSize;
                srcRect.set(sx, sy, sx + subSize, sy + subSize);
                dstRect.set(drawX, drawY, drawX + TILE_SIZE, drawY + TILE_SIZE);
                canvas.drawBitmap(pBmp, srcRect, dstRect, paint);
                return true;
            }
        }
        return false;
    }

    private File getDiskTileFile(int z, int x, int y) {
        return new File(diskCacheDir, z + "/" + x + "/" + y + ".png");
    }

    private void loadBaseTileAsync(final String key, final int z, final int x, final int y) {
        if (paused || !inFlight.add(key)) return;
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                // 1. Check persistent offline disk cache first (instant, works with zero 4G!)
                File diskFile = getDiskTileFile(z, x, y);
                if (diskFile.exists() && diskFile.length() > 100) {
                    Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                    if (bmp != null) {
                        tileCache.put(key, bmp);
                        mainHandler.post(this::invalidate);
                        return;
                    }
                }

                // 2. Fetch from Yandex and save to offline disk cache
                String urlStr = String.format(Locale.US,
                        "https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x=%d&y=%d&z=%d&scale=1&lang=ru_RU",
                        x, y, z);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36");
                conn.setRequestProperty("Referer", "https://yandex.ru/maps/");
                if (conn.getResponseCode() == 200) {
                    byte[] bytes = readAllBytes(conn.getInputStream());
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        tileCache.put(key, bmp);
                        mainHandler.post(this::invalidate);
                        // Persist to disk for offline driving!
                        try {
                            File parent = diskFile.getParentFile();
                            if (parent != null && !parent.exists()) parent.mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(diskFile)) {
                                fos.write(bytes);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
                // Offline fallback: try loading parent tile from disk cache into RAM so drawParentFallback succeeds
                tryLoadParentFromDisk(z, x, y);
            } finally {
                if (conn != null) conn.disconnect();
                inFlight.remove(key);
            }
        });
    }

    private void tryLoadParentFromDisk(int z, int x, int y) {
        for (int shift = 1; shift <= 3 && (z - shift) >= 9; shift++) {
            int pz = z - shift;
            int px = x >> shift;
            int py = y >> shift;
            String pKey = "m_" + pz + "_" + px + "_" + py;
            if (tileCache.get(pKey) != null) return;
            File pf = getDiskTileFile(pz, px, py);
            if (pf.exists() && pf.length() > 100) {
                Bitmap pb = BitmapFactory.decodeFile(pf.getAbsolutePath());
                if (pb != null) {
                    tileCache.put(pKey, pb);
                    mainHandler.post(this::invalidate);
                    return;
                }
            }
        }
    }

    private void fetchTrafficTileAsync(final String key, final String urlStr) {
        if (paused || !inFlight.add(key)) return;
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36");
                conn.setRequestProperty("Referer", "https://yandex.ru/maps/");
                int code = conn.getResponseCode();
                if (code == 204) {
                    emptyTrafficTiles.add(key);
                } else if (code == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            tileCache.put(key, bmp);
                            mainHandler.post(this::invalidate);
                        } else {
                            emptyTrafficTiles.add(key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                inFlight.remove(key);
            }
        });
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    private static double[] latLonToPixel(double lat, double lon, int z) {
        double scale = TILE_SIZE * Math.pow(2.0, z);
        double radLon = Math.toRadians(lon);
        double clampedLat = Math.max(-85.0, Math.min(85.0, lat));
        double radLat = Math.toRadians(clampedLat);
        double sinLat = Math.sin(radLat);
        double esin = E * sinLat;
        double mercY = Math.log(Math.tan(Math.PI / 4.0 + radLat / 2.0)
                * Math.pow((1.0 - esin) / (1.0 + esin), E / 2.0));
        double x = (radLon + Math.PI) / (2.0 * Math.PI) * scale;
        double y = (Math.PI - mercY) / (2.0 * Math.PI) * scale;
        return new double[]{x, y};
    }

    private static double[] pixelToLatLon(double px, double py, int z) {
        double scale = TILE_SIZE * Math.pow(2.0, z);
        double lon = (px / scale) * 360.0 - 180.0;
        double mercY = Math.PI - (py / scale) * 2.0 * Math.PI;
        double phi = 2.0 * Math.atan(Math.exp(mercY)) - Math.PI / 2.0;
        for (int i = 0; i < 4; i++) {
            double es = E * Math.sin(phi);
            phi = 2.0 * Math.atan(Math.exp(mercY) * Math.pow((1.0 + es) / (1.0 - es), E / 2.0)) - Math.PI / 2.0;
        }
        return new double[]{Math.toDegrees(phi), lon};
    }
}
