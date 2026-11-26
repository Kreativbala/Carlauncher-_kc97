package com.kc97.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Silent background telemetry black-box logger for KC97 Cockpit v2.0.
 * - Writes 1 clean CSV row/sec while ignition/ECU is active or car is moving into /sdcard/KC97Logs/.
 * - Zero UI footprint; auto-cleans logs older than 30 days.
 * - 1-tap export to /sdcard/Download/KC97Logs/ + Android Share sheet (Telegram / WhatsApp / Mail / USB).
 */
public class TripLogger {
    private static final String TAG = "KC97_LOG";
    private static final String HEADER =
            "timestamp,lat,lon,gps_kmh,ecu_kmh,rpm,coolant_c,voltage_v,inst_lph,inst_l100,avg_l100,total_km,total_l,headlamp,handbrake,cpu_c\n";

    private final File logDir;
    private final SimpleDateFormat fileFmt = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US);
    private final SimpleDateFormat rowFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private File currentFile;
    private BufferedWriter writer;
    private long lastWriteMs = 0L;
    private long lastFlushMs = 0L;
    private int rowsWritten = 0;

    public TripLogger(Context ctx) {
        File ext = new File(Environment.getExternalStorageDirectory(), "KC97Logs");
        if (!ext.exists()) ext.mkdirs();
        logDir = (ext.exists() && ext.canWrite()) ? ext : new File(ctx.getFilesDir(), "KC97Logs");
        if (!logDir.exists()) logDir.mkdirs();
        cleanupOldLogsAsync();
    }

    public synchronized void recordSample(double lat, double lon, int gpsKmh,
                                          ObdBridgeManager.ObdData obd,
                                          boolean headlamp, boolean handbrake, int cpuTempC) {
        // Record only while ECU is active OR car is moving (don't waste disk when parked/off)
        boolean active = (obd != null && obd.connected && obd.ecuReady) || gpsKmh >= 5;
        if (!active) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastWriteMs < 1000L) return;
        lastWriteMs = now;

        try {
            if (writer == null) {
                currentFile = new File(logDir, "trip_" + fileFmt.format(new Date()) + ".csv");
                boolean needHeader = !currentFile.exists() || currentFile.length() == 0;
                writer = new BufferedWriter(new FileWriter(currentFile, true), 4096);
                if (needHeader) writer.write(HEADER);
            }

            int ecuSpd = obd != null ? obd.ecuSpeedKmh : 0;
            int rpm = obd != null ? obd.rpm : 0;
            int cool = (obd != null && obd.coolantTempC > -100) ? obd.coolantTempC : -999;
            float volt = obd != null ? obd.voltage : 0f;
            float lph = obd != null ? obd.instantLph : 0f;
            float l100 = obd != null ? obd.instantL100 : 0f;
            double avg = obd != null ? obd.avgL100 : 0.0;
            double km = obd != null ? obd.totalKm : 0.0;
            double lit = obd != null ? obd.totalLiters : 0.0;

            String line = String.format(Locale.US,
                    "%s,%.5f,%.5f,%d,%d,%d,%d,%.1f,%.2f,%.1f,%.1f,%.2f,%.3f,%d,%d,%d\n",
                    rowFmt.format(new Date()), lat, lon, gpsKmh, ecuSpd, rpm, cool, volt,
                    lph, l100, avg, km, lit, headlamp ? 1 : 0, handbrake ? 1 : 0, cpuTempC);
            writer.write(line);
            rowsWritten++;

            if (now - lastFlushMs >= 10_000L) {
                writer.flush();
                lastFlushMs = now;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Log write failed: " + e.getMessage());
        }
    }

    public synchronized void flush() {
        try { if (writer != null) writer.flush(); } catch (Throwable ignored) {}
    }

    public synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (Throwable ignored) {}
    }

    public int getLogFileCount() {
        File[] files = listLogsSorted();
        return files == null ? 0 : files.length;
    }

    public File getLatestLogFile() {
        flush();
        File[] files = listLogsSorted();
        if (files == null || files.length == 0) return null;
        return files[0];
    }

    private File[] listLogsSorted() {
        File[] files = logDir.listFiles((d, name) -> name.endsWith(".csv"));
        if (files != null && files.length > 1) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        }
        return files;
    }

    /**
     * Copies all trip logs to /sdcard/Download/KC97Logs and opens Android Share sheet for the latest CSV.
     */
    public String exportAndShareLatest(Activity activity) {
        flush();
        File[] files = listLogsSorted();
        if (files == null || files.length == 0) {
            return "Логов поездок пока нет (запись начнётся автоматически при включении зажигания)";
        }

        // Copy to /sdcard/Download/KC97Logs for easy USB/file-manager pickup
        File dlDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KC97Logs");
        if (!dlDir.exists()) dlDir.mkdirs();
        int copied = 0;
        for (File f : files) {
            if (copyFile(f, new File(dlDir, f.getName()))) copied++;
        }

        // Open standard Android Share chooser for the latest trip CSV
        File latestExport = new File(dlDir, files[0].getName());
        File toShare = latestExport.exists() ? latestExport : files[0];
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_SUBJECT, "KC97 Trip Log: " + toShare.getName());
            share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(toShare));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, "Экспорт лога поездки"));
        } catch (Throwable ignored) {}

        return "Экспортировано " + copied + " лог(ов) в Download/KC97Logs (" + toShare.getName() + ")";
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void cleanupOldLogsAsync() {
        new Thread(() -> {
            long cutoff = System.currentTimeMillis() - (30L * 24L * 3600L * 1000L);
            File[] files = logDir.listFiles((d, name) -> name.endsWith(".csv"));
            if (files == null) return;
            for (File f : files) {
                if (f.lastModified() < cutoff) f.delete();
            }
        }, "Log-Cleanup").start();
    }
}
