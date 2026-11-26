package com.kc97.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline + 1-tap GitHub-updatable "Safe City" Speed Camera manager for Kyrgyzstan (Chuy + Bishkek + Issyk-Kul).
 * Supports standard SpeedCamOnLine / Navitel CSV format:
 *   IDX,X(lon),Y(lat),TYPE,SPEED,DIRTYPE,DIRECTION
 * Features:
 * - Direction-aware 420m lookahead (ignores opposite-lane cameras).
 * - Configurable alert margin (default +9 km/h -> warns at 69 km/h for a 60 km/h zone, before KG 71 km/h fine!).
 * - Russian Android TTS voice warning with automatic crisp ToneGenerator fallback + AudioFocus music ducking.
 * - 1-tap GitHub sync + 1-tap "+ Camera" user additions + custom SpeedCam.txt drop-in support.
 */
public class SpeedCameraManager {
    private static final String TAG = "KC97_CAM";
    private static final String PREFS = "kc97_speedcam";
    private static final String KEY_MARGIN = "overspeed_margin";
    private static final String KEY_AUDIO = "audio_enabled";
    private static final String KEY_VOICE = "voice_enabled";
    public static final String GITHUB_RAW_URL =
            "https://raw.githubusercontent.com/Kreativbala/Carlauncher-_kc97/main/cameras/speedcam_kg.txt";

    public static class SpeedCamera {
        public final int id;
        public final double lat;
        public final double lon;
        public final int type;
        public final int speedLimit;
        public final int dirType;   // 0=all, 1=one-way (direction), 2=two-way (direction & +180)
        public final int direction; // 0..359 deg

        public SpeedCamera(int id, double lat, double lon, int type, int speedLimit, int dirType, int direction) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.type = type;
            this.speedLimit = speedLimit;
            this.dirType = dirType;
            this.direction = direction;
        }
    }

    public static class CameraAlert {
        public final SpeedCamera camera;
        public final int distanceMeters;
        public final boolean overspeed;

        public CameraAlert(SpeedCamera camera, int distanceMeters, boolean overspeed) {
            this.camera = camera;
            this.distanceMeters = distanceMeters;
            this.overspeed = overspeed;
        }
    }

    public interface UpdateCallback {
        void onComplete(boolean success, String message, int totalCount);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File cacheDir;
    private final File mainDbFile;
    private final File userDbFile;
    private final File customDbFile;

    private volatile List<SpeedCamera> cameras = Collections.emptyList();
    private final Map<Integer, Long> lastSpokenMs = new HashMap<>();

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile int overspeedMargin;
    private volatile boolean audioEnabled;
    private volatile boolean voiceEnabled;

    public SpeedCameraManager(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        this.overspeedMargin = prefs.getInt(KEY_MARGIN, 9); // KG default: 60 -> warn at 69 km/h (fine at 71)
        this.audioEnabled = prefs.getBoolean(KEY_AUDIO, true);
        this.voiceEnabled = prefs.getBoolean(KEY_VOICE, true);

        File ext = new File(Environment.getExternalStorageDirectory(), "KC97MapCache");
        if (!ext.exists()) ext.mkdirs();
        cacheDir = (ext.exists() && ext.canRead()) ? ext : ctx.getFilesDir();
        mainDbFile = new File(cacheDir, "speedcam_kg.txt");
        userDbFile = new File(cacheDir, "user_cameras.txt");
        customDbFile = new File(cacheDir, "speedcam_custom.txt");

        reloadCameras();
        initTts();
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    int res = tts.setLanguage(new Locale("ru", "RU"));
                    ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED);
                    if (ttsReady) {
                        tts.setSpeechRate(1.08f);
                    }
                    Log.i(TAG, "TTS initialized, Russian ready=" + ttsReady);
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "TTS unavailable, will use crisp tone fallback");
            ttsReady = false;
        }
    }

    public int getOverspeedMargin() { return overspeedMargin; }
    public boolean isAudioEnabled() { return audioEnabled; }
    public boolean isVoiceEnabled() { return voiceEnabled; }
    public int getCameraCount() { return cameras.size(); }
    public List<SpeedCamera> getCameras() { return cameras; }

    public void setOverspeedMargin(int margin) {
        overspeedMargin = Math.max(0, Math.min(20, margin));
        prefs.edit().putInt(KEY_MARGIN, overspeedMargin).apply();
    }

    public void setAudioEnabled(boolean on) {
        audioEnabled = on;
        prefs.edit().putBoolean(KEY_AUDIO, on).apply();
    }

    public void setVoiceEnabled(boolean on) {
        voiceEnabled = on;
        prefs.edit().putBoolean(KEY_VOICE, on).apply();
    }

    public void reloadCameras() {
        List<SpeedCamera> loaded = new ArrayList<>();
        // 1. Load main database (prefer updated SD file, fallback to bundled APK asset)
        if (mainDbFile.exists() && mainDbFile.length() > 40) {
            parseFileInto(mainDbFile, loaded, 0);
        } else {
            try (InputStream is = ctx.getAssets().open("speedcam_kg.txt")) {
                parseStreamInto(is, loaded, 0);
            } catch (Throwable e) {
                Log.w(TAG, "Asset speedcam_kg.txt missing: " + e.getMessage());
            }
        }
        // 2. Merge optional custom SpeedCamOnLine export (/sdcard/KC97MapCache/speedcam_custom.txt)
        if (customDbFile.exists() && customDbFile.length() > 20) {
            parseFileInto(customDbFile, loaded, 100000);
        }
        // 3. Merge user-added in-car cameras (/sdcard/KC97MapCache/user_cameras.txt)
        if (userDbFile.exists() && userDbFile.length() > 10) {
            parseFileInto(userDbFile, loaded, 200000);
        }
        cameras = Collections.unmodifiableList(loaded);
        Log.i(TAG, "Loaded total speed cameras: " + cameras.size());
    }

    private void parseFileInto(File file, List<SpeedCamera> out, int idOffset) {
        try (InputStream is = new FileInputStream(file)) {
            parseStreamInto(is, out, idOffset);
        } catch (Throwable e) {
            Log.w(TAG, "Failed reading " + file.getName() + ": " + e.getMessage());
        }
    }

    private void parseStreamInto(InputStream is, List<SpeedCamera> out, int idOffset) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        int seq = 1;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("IDX") || line.startsWith("X,")) continue;
            String[] parts = line.split(",");
            try {
                int id, type, speed, dirType, dir;
                double lon, lat;
                if (parts.length >= 7) {
                    // Navitel / SpeedCamOnLine: IDX,X(lon),Y(lat),TYPE,SPEED,DIRTYPE,DIRECTION
                    id = idOffset + (int) Double.parseDouble(parts[0].trim());
                    lon = Double.parseDouble(parts[1].trim());
                    lat = Double.parseDouble(parts[2].trim());
                    type = Integer.parseInt(parts[3].trim());
                    speed = Integer.parseInt(parts[4].trim());
                    dirType = Integer.parseInt(parts[5].trim());
                    dir = Integer.parseInt(parts[6].trim()) % 360;
                } else if (parts.length >= 6) {
                    // iGO format: X(lon),Y(lat),TYPE,SPEED,DIRTYPE,DIRECTION
                    id = idOffset + seq;
                    lon = Double.parseDouble(parts[0].trim());
                    lat = Double.parseDouble(parts[1].trim());
                    type = Integer.parseInt(parts[2].trim());
                    speed = Integer.parseInt(parts[3].trim());
                    dirType = Integer.parseInt(parts[4].trim());
                    dir = Integer.parseInt(parts[5].trim()) % 360;
                } else {
                    continue;
                }
                if (lat < -85 || lat > 85 || lon < -180 || lon > 180) continue;
                if (speed <= 0 || speed > 150) speed = 60;
                out.add(new SpeedCamera(id, lat, lon, type, speed, dirType, dir));
                seq++;
            } catch (Throwable ignored) {}
        }
    }

    public boolean addUserCamera(double lat, double lon, int speedLimit, int dirType, int directionDeg) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs();
            int nextId = 1 + (int) (System.currentTimeMillis() % 90000);
            String row = String.format(Locale.US, "%d,%.5f,%.5f,1,%d,%d,%d\n",
                    nextId, lon, lat, speedLimit, dirType, ((directionDeg % 360) + 360) % 360);
            try (FileWriter fw = new FileWriter(userDbFile, true)) {
                fw.write(row);
            }
            reloadCameras();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "addUserCamera failed: " + e.getMessage());
            return false;
        }
    }

    public void updateFromGitHubAsync(final UpdateCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(GITHUB_RAW_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "KC97Cockpit/2.0");
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[4096];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = is.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                is.close();
                String content = sb.toString();
                if (!content.contains(",") || content.length() < 40) {
                    throw new Exception("Пустой или повреждённый файл камер");
                }
                if (!cacheDir.exists()) cacheDir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(mainDbFile)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                }
                reloadCameras();
                int count = cameras.size();
                mainHandler.post(() -> cb.onComplete(true, "База обновлена (" + count + " камер)", count));
            } catch (Throwable e) {
                mainHandler.post(() -> cb.onComplete(false, "Ошибка: " + e.getMessage(), cameras.size()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "SpeedCam-Updater").start();
    }

    /**
     * Checks nearest relevant camera ahead within 420m and fires audio/voice warning if overspeeding.
     */
    public CameraAlert evaluate(double carLat, double carLon, float carBearing, int speedKmh) {
        List<SpeedCamera> list = cameras;
        if (list.isEmpty()) return null;

        SpeedCamera bestCam = null;
        double bestDist = 425.0;
        boolean moving = speedKmh >= 10;

        for (int i = 0; i < list.size(); i++) {
            SpeedCamera c = list.get(i);
            // Fast bounding-box pre-filter (~550m)
            if (Math.abs(c.lat - carLat) > 0.005 || Math.abs(c.lon - carLon) > 0.007) continue;

            double dist = haversineMeters(carLat, carLon, c.lat, c.lon);
            if (dist > 420.0 || dist >= bestDist) continue;

            if (moving) {
                // Ensure camera is AHEAD of the car (within +-55 deg of heading), or right beside (< 45m)
                double brgToCam = bearingDeg(carLat, carLon, c.lat, c.lon);
                if (dist > 45.0 && angleDiff(carBearing, brgToCam) > 55.0) continue;

                // Respect camera direction so opposite-lane cameras don't trigger!
                if (c.dirType == 1) {
                    if (angleDiff(carBearing, c.direction) > 55.0) continue;
                } else if (c.dirType == 2) {
                    double opp = (c.direction + 180.0) % 360.0;
                    if (angleDiff(carBearing, c.direction) > 55.0 && angleDiff(carBearing, opp) > 55.0) continue;
                }
            }
            bestDist = dist;
            bestCam = c;
        }

        if (bestCam == null) return null;

        boolean overspeed = speedKmh >= (bestCam.speedLimit + overspeedMargin);
        if (overspeed && audioEnabled) {
            long now = SystemClock.elapsedRealtime();
            Long prev = lastSpokenMs.get(bestCam.id);
            if (prev == null || (now - prev) > 25_000L) {
                lastSpokenMs.put(bestCam.id, now);
                triggerAudioAlert(bestCam.speedLimit);
            }
        }
        return new CameraAlert(bestCam, (int) Math.round(bestDist), overspeed);
    }

    public void testAlertNow() {
        triggerAudioAlert(60);
    }

    private void triggerAudioAlert(final int speedLimit) {
        new Thread(() -> {
            AudioFocusRequest focusReq = null;
            try {
                if (audioManager != null) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build())
                                .build();
                        audioManager.requestAudioFocus(focusReq);
                    } else {
                        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                    }
                }

                // Short attention chime first
                try {
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 88);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 260);
                    Thread.sleep(320);
                    tg.release();
                } catch (Throwable ignored) {}

                // Spoken Russian warning if TTS is ready and voice enabled
                if (voiceEnabled && ttsReady && tts != null) {
                    String phrase = "Снизьте скорость! Камера, " + speedLimit;
                    tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "cam_" + speedLimit);
                    Thread.sleep(2400);
                } else {
                    Thread.sleep(250);
                }
            } catch (Throwable ignored) {
            } finally {
                if (audioManager != null) {
                    if (Build.VERSION.SDK_INT >= 26 && focusReq != null) {
                        audioManager.abandonAudioFocusRequest(focusReq);
                    } else {
                        audioManager.abandonAudioFocus(null);
                    }
                }
            }
        }, "SpeedCam-Audio").start();
    }

    public void shutdown() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {}
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    public static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }
}
