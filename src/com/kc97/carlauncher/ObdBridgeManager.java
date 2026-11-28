package com.kc97.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct SD-968 Bluetooth SPP OBD-II Fuel Computer & Telemetry + TCP Wi-Fi ELM327 bridge (127.0.0.1:35000).
 *
 * v1.5:
 * - Supports Speed-Density fuel consumption via MAP (010B) + RPM (010C) + IAT (010F) + Speed (010D),
 *   exact match for this car's ECU (verified via 0100=BE3EA801).
 * - Calculates instant fuel rate (L/h when idling, L/100km when moving).
 * - Persistently accumulates long-term distance (km) & fuel burned (L) in SharedPreferences across car restarts,
 *   so 100 km / 200 km+ average consumption is always saved until manual reset!
 */
public class ObdBridgeManager {
    private static final String TAG = "KC97_OBD";
    private static final String PREFS = "kc97_obd_stats";
    private static final String KEY_TOTAL_KM = "total_km";
    private static final String KEY_TOTAL_LITERS = "total_liters";
    private static final String KEY_ENGINE_VOL = "engine_vol_l";

    public static final String DEFAULT_OBD_MAC = "001DA50821EB";
    public static final int TCP_BRIDGE_PORT = 35000;
    private static final double DEFAULT_VE = 0.78; // Calibrated for Honda K20A 2.0L i-VTEC (RN3)

    public static class ObdData {
        public boolean connected = false;
        public boolean connecting = true;
        public boolean bridgeBusy = false;
        public boolean ecuReady = false;
        public float voltage = 0f;
        public int coolantTempC = -999;
        public int rpm = 0;
        public int ecuSpeedKmh = 0;
        public float instantLph = 0f;       // L/h
        public float instantL100 = 0f;      // L/100km (valid when ecuSpeedKmh >= 5)
        public double totalKm = 0.0;        // Persistent across restarts!
        public double totalLiters = 0.0;    // Persistent across restarts!
        public double avgL100 = 0.0;        // Persistent average L/100km
        public float engineVolL = 2.0f;
    }

    public interface ObdListener {
        void onObdUpdated(ObdData data);
    }

    private static final Pattern VOLT_PATTERN = Pattern.compile("(\\d{1,2}\\.\\d)");

    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String obdMac;
    private volatile ObdListener listener;
    private volatile boolean running = false;
    private volatile boolean externalClientActive = false;
    private volatile float engineVolL;

    private final ObdData latest = new ObdData();
    private ServerSocket tcpServer;
    private LocalSocket activeSppSocket;
    private final Object sppLock = new Object();
    private final Object statsLock = new Object();

    private long lastSampleMs = 0L;
    private long lastPersistMs = 0L;
    private int lastIatC = 25;

    public ObdBridgeManager(Context ctx, String mac, ObdListener listener) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.obdMac = (mac != null && mac.length() == 12) ? mac.toUpperCase(Locale.US) : DEFAULT_OBD_MAC;
        this.listener = listener;

        synchronized (statsLock) {
            latest.totalKm = Double.longBitsToDouble(prefs.getLong(KEY_TOTAL_KM, Double.doubleToRawLongBits(0.0)));
            latest.totalLiters = Double.longBitsToDouble(prefs.getLong(KEY_TOTAL_LITERS, Double.doubleToRawLongBits(0.0)));
            engineVolL = prefs.getFloat(KEY_ENGINE_VOL, 2.0f);
            latest.engineVolL = engineVolL;
            recomputeAvgLocked();
        }
    }

    public float getEngineVolL() {
        return engineVolL;
    }

    public void setEngineVolL(float liters) {
        engineVolL = Math.max(0.8f, Math.min(6.0f, liters));
        synchronized (statsLock) {
            latest.engineVolL = engineVolL;
        }
        prefs.edit().putFloat(KEY_ENGINE_VOL, engineVolL).apply();
        notifyListener();
    }

    public void resetStatistics() {
        synchronized (statsLock) {
            latest.totalKm = 0.0;
            latest.totalLiters = 0.0;
            latest.avgL100 = 0.0;
        }
        persistStatsNow();
        notifyListener();
    }

    public void persistStatsNow() {
        double km, lit;
        synchronized (statsLock) {
            km = latest.totalKm;
            lit = latest.totalLiters;
        }
        prefs.edit()
                .putLong(KEY_TOTAL_KM, Double.doubleToRawLongBits(km))
                .putLong(KEY_TOTAL_LITERS, Double.doubleToRawLongBits(lit))
                .apply();
        lastPersistMs = SystemClock.elapsedRealtime();
    }

    public void start() {
        if (running) return;
        running = true;
        startedElapsedMs = SystemClock.elapsedRealtime();
        notifyListener();
        new Thread(this::tcpServerLoop, "OBD-TcpBridge").start();
        new Thread(this::pollLoop, "OBD-Poller").start();
    }

    public void stop() {
        running = false;
        persistStatsNow();
        closeActiveSpp();
        try { if (tcpServer != null) tcpServer.close(); } catch (Throwable ignored) {}
    }

    private long startedElapsedMs = 0L;

    public void forceReconnect() {
        Log.i(TAG, "Manual/forced OBD reconnect requested");
        refreshObdMacFromBtConf();
        closeActiveSpp();
    }

    public void onActivityResumed() {
        refreshObdMacFromBtConf();
    }

    /**
     * Called on ACC wake (ACTION_SCREEN_ON): closes any stale sleeping SPP socket so pollLoop immediately reconnects.
     */
    public void onAccWake() {
        if (!externalClientActive) {
            Log.i(TAG, "ACC wake detected -> refreshing OBD SPP session");
            refreshObdMacFromBtConf();
            latest.ecuReady = false;
            latest.connecting = true;
            notifyListener();
            closeActiveSpp();
        }
    }

    /**
     * Auto-discovers paired OBD-II adapter MAC from world-readable /data/goc/bt_conf.ini
     * (written by sdsdk968 when any OBDII / ELM327 / Vgate / V-LINK adapter is paired),
     * so even re-pairing or swapping OBD adapters works automatically without manual config!
     */
    private void refreshObdMacFromBtConf() {
        java.io.BufferedReader br = null;
        try {
            java.io.File f = new java.io.File("/data/goc/bt_conf.ini");
            if (!f.canRead()) return;
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            java.util.Map<String, String> addrs = new java.util.HashMap<>();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.startsWith("addr") && v.length() == 12) {
                    addrs.put(k.substring(4), v.toUpperCase(Locale.US));
                } else if (k.startsWith("name") && !v.isEmpty()) {
                    names.put(k.substring(4), v);
                }
            }
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                String nm = e.getValue().toUpperCase(Locale.US);
                if (nm.contains("OBD") || nm.contains("ELM") || nm.contains("V-LINK")
                        || nm.contains("VLINK") || nm.contains("VGATE") || nm.contains("KONNWEI")) {
                    String foundMac = addrs.get(e.getKey());
                    if (foundMac != null && foundMac.length() == 12 && !foundMac.equals(obdMac)) {
                        Log.i(TAG, "Auto-detected paired OBDII MAC from bt_conf.ini: " + foundMac + " (" + e.getValue() + ")");
                        obdMac = foundMac;
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Reads the paired phone display name from /data/goc/bt_conf.ini ([LASTDEV] -> nameN)
     * so BT Music tab shows the paired phone name immediately after reboot.
     */
    public static String readLastPhoneNameFromBtConf() {
        java.io.BufferedReader br = null;
        try {
            java.io.File f = new java.io.File("/data/goc/bt_conf.ini");
            if (!f.canRead()) return "";
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            java.util.Map<String, String> addrs = new java.util.HashMap<>();
            java.util.Map<String, String> names = new java.util.HashMap<>();
            String lastAddr = "";
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("addr".equals(k) && v.length() == 12) {
                    lastAddr = v.toUpperCase(Locale.US);
                } else if (k.startsWith("addr") && v.length() == 12) {
                    addrs.put(k.substring(4), v.toUpperCase(Locale.US));
                } else if (k.startsWith("name") && !v.isEmpty()) {
                    names.put(k.substring(4), v);
                }
            }
            for (java.util.Map.Entry<String, String> e : addrs.entrySet()) {
                String mac = e.getValue();
                String nm = names.get(e.getKey());
                if (nm == null || nm.isEmpty()) continue;
                if (!lastAddr.isEmpty() && lastAddr.equals(mac) && !nm.toUpperCase(Locale.US).contains("OBD")) {
                    return nm;
                }
            }
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                String nm = e.getValue();
                if (!nm.toUpperCase(Locale.US).contains("OBD") && !nm.toUpperCase(Locale.US).contains("ELM")) {
                    return nm;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    /**
     * Writes a raw Goodocom/SD-968 AT# command to /dev/goc_serial (used ONLY for explicit AVRCP music keys).
     */
    public static boolean sendGocSerialCmd(String atCmd) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream("/dev/goc_serial");
            fos.write((atCmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
            fos.flush();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "goc_serial cmd failed (" + atCmd + "): " + e.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Exact reverse-engineered sdsdk968 /dev/socket/goc_spp protocol (sub_97c0 @ 0x0097e8):
     * - sdsdk968 checks `cmp r1, #0xc` (exact 12-byte MAC length, WITHOUT \r\n!).
     * - Sending 14 bytes (MAC + \r\n) failed `r1 == 12`, skipping `bt_spp_connect` (0x18b9c) after reboot!
     * - Sending AT#SP on /dev/goc_serial triggered Apple iAP and reset HFP / killed sdsdk968!
     * - Sending EXACT 12 ASCII bytes of MAC triggers native RFCOMM SPP connect cleanly with zero HFP impact!
     */
    private LocalSocket openSppLink() throws Exception {
        refreshObdMacFromBtConf();
        String targetMac = obdMac;
        LocalSocket sock = new LocalSocket();
        sock.connect(new LocalSocketAddress("/dev/socket/goc_spp", LocalSocketAddress.Namespace.FILESYSTEM));
        sock.setSoTimeout(1800);
        OutputStream out = sock.getOutputStream();
        // MUST be exact 12 ASCII bytes (no \r, no \n) so sdsdk968 sub_97c0 matches `cmp r1, #0xc`!
        out.write(targetMac.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Log.i(TAG, "Sent exact 12-byte OBD SPP connect to /dev/socket/goc_spp: " + targetMac);
        // Wait 4.5s so sdsdk968 completes RFCOMM SPP paging (`spp_handle > 0`) before first ELM327 AT command
        Thread.sleep(4500);
        return sock;
    }

    private void closeActiveSpp() {
        synchronized (sppLock) {
            if (activeSppSocket != null) {
                try { activeSppSocket.close(); } catch (Throwable ignored) {}
                activeSppSocket = null;
            }
        }
    }

    private void tcpServerLoop() {
        try {
            tcpServer = new ServerSocket(TCP_BRIDGE_PORT, 2, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "TCP OBD Bridge listening on 127.0.0.1:" + TCP_BRIDGE_PORT);
            while (running) {
                Socket client = tcpServer.accept();
                Log.i(TAG, "External OBD client connected (Car Scanner)");
                externalClientActive = true;
                latest.bridgeBusy = true;
                persistStatsNow();
                notifyListener();

                closeActiveSpp();
                Thread.sleep(300);

                LocalSocket spp = null;
                try {
                    spp = openSppLink();
                    spp.setSoTimeout(0);
                    client.setTcpNoDelay(true);

                    final InputStream sppIn = spp.getInputStream();
                    final OutputStream sppOut = spp.getOutputStream();
                    final InputStream cliIn = client.getInputStream();
                    final OutputStream cliOut = client.getOutputStream();

                    Thread t1 = new Thread(() -> pipeStream(sppIn, cliOut), "OBD-SppToTcp");
                    t1.start();
                    pipeStream(cliIn, sppOut);
                    try { spp.close(); } catch (Throwable ignored) {}
                    t1.join(1000);
                } catch (Throwable e) {
                    Log.w(TAG, "Bridge session error: " + e.getMessage());
                } finally {
                    try { if (spp != null) spp.close(); } catch (Throwable ignored) {}
                    try { client.close(); } catch (Throwable ignored) {}
                    externalClientActive = false;
                    latest.bridgeBusy = false;
                    lastSampleMs = 0L;
                    notifyListener();
                    Log.i(TAG, "External OBD client disconnected; resuming fuel computer");
                }
            }
        } catch (Throwable e) {
            if (running) Log.w(TAG, "TCP bridge stopped: " + e.getMessage());
        }
    }

    private static void pipeStream(InputStream in, OutputStream out) {
        byte[] buf = new byte[512];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {}
    }

    private void pollLoop() {
        while (running) {
            if (externalClientActive) {
                sleepQuiet(1000);
                continue;
            }
            LocalSocket spp = null;
            try {
                latest.connecting = true;
                notifyListener();
                spp = openSppLink();
                synchronized (sppLock) {
                    if (!running || externalClientActive) {
                        spp.close();
                        continue;
                    }
                    activeSppSocket = spp;
                }
                InputStream in = spp.getInputStream();
                OutputStream out = spp.getOutputStream();

                String id = "";
                for (int tries = 0; tries < 4 && running && !externalClientActive; tries++) {
                    id = sendCmd(in, out, tries == 1 ? "ATZ" : "ATI", 3200);
                    if (id.toUpperCase(Locale.US).contains("ELM") || id.contains(">")) break;
                    sleepQuiet(1800);
                }
                if (!id.toUpperCase(Locale.US).contains("ELM") && !id.contains(">")) {
                    throw new Exception("No ELM327 response: " + id);
                }

                sendCmd(in, out, "ATE0", 1500);
                sendCmd(in, out, "ATL0", 1500);
                sendCmd(in, out, "ATS0", 1500);
                sendCmd(in, out, "ATSP0", 1500);

                latest.connected = true;
                latest.connecting = true; // waiting for ECU lock -> yellow
                readVoltage(in, out);
                notifyListener();

                // Initial ECU handshake (Honda ISO 9141-2 5-baud BUS INIT takes ~4-6s)
                boolean locked = handshakeEcu(in, out);
                latest.ecuReady = locked;
                latest.connecting = !locked;
                if (locked) {
                    readSlowPids(in, out);
                }
                lastSampleMs = SystemClock.elapsedRealtime();
                notifyListener();

                int cycle = 0;
                int ecuRetryFailCount = 0;
                while (running && !externalClientActive) {
                    // 1. Battery voltage every 6 cycles (~6 sec)
                    if (cycle % 6 == 0) {
                        if (!readVoltage(in, out)) throw new Exception("Lost ELM327 link");
                    }

                    // 2. If ECU is not locked yet (ignition was OFF, then turned ON), re-handshake cleanly!
                    if (!latest.ecuReady) {
                        if (cycle % 4 == 0) {
                            boolean ok = handshakeEcu(in, out);
                            if (ok) {
                                ecuRetryFailCount = 0;
                                latest.ecuReady = true;
                                latest.connecting = false;
                                readSlowPids(in, out);
                                lastSampleMs = SystemClock.elapsedRealtime();
                            } else {
                                ecuRetryFailCount++;
                                latest.connecting = true;
                                // Periodically reset ELM327 if stuck after many failed ignition-off polls
                                if (ecuRetryFailCount >= 12) {
                                    throw new Exception("Refresh ELM327 session");
                                }
                            }
                        }
                        notifyListener();
                        cycle++;
                        sleepQuiet(700);
                        continue;
                    }

                    // 3. Coolant Temp (0105) & Intake Air Temp (010F) every 5 cycles (~5 sec)
                    if (cycle % 5 == 0) {
                        if (!readSlowPids(in, out)) {
                            latest.ecuReady = false;
                            latest.connecting = true;
                            latest.rpm = 0;
                            latest.instantLph = 0f;
                            latest.instantL100 = 0f;
                            persistStatsNow();
                            notifyListener();
                            cycle++;
                            sleepQuiet(700);
                            continue;
                        }
                    }

                    // 4. Fast fuel & speed loop (~1 Hz): RPM (010C) + MAP (010B) + Speed (010D)
                    if (latest.ecuReady && !externalClientActive) {
                        String rpmResp = sendCmd(in, out, "010C", 2000);
                        if (isEcuLost(rpmResp)) {
                            Log.i(TAG, "ECU connection lost on 010C: " + rpmResp);
                            latest.ecuReady = false;
                            latest.connecting = true;
                            latest.rpm = 0;
                            latest.instantLph = 0f;
                            latest.instantL100 = 0f;
                            persistStatsNow();
                            notifyListener();
                            cycle++;
                            sleepQuiet(700);
                            continue;
                        }
                        int rpm = parseRpm(rpmResp);
                        int mapKpa = parseSingleByte(sendCmd(in, out, "010B", 2000), "410B");
                        int spdKmh = parseSingleByte(sendCmd(in, out, "010D", 2000), "410D");

                        long nowMs = SystemClock.elapsedRealtime();
                        double dtSec = lastSampleMs > 0 ? (nowMs - lastSampleMs) / 1000.0 : 0.0;
                        lastSampleMs = nowMs;
                        if (dtSec < 0.05 || dtSec > 6.0) dtSec = 0.0;

                        if (rpm >= 0) latest.rpm = rpm;
                        if (spdKmh >= 0) latest.ecuSpeedKmh = spdKmh;

                        // Speed-Density Fuel Rate calculation (g/s -> L/h)
                        double lph = 0.0;
                        if (rpm > 150 && mapKpa > 0) {
                            // Deceleration fuel cut-off detection (coasting in gear)
                            if (rpm > 1500 && mapKpa <= 20 && spdKmh > 20) {
                                lph = 0.0;
                            } else {
                                double iatK = Math.max(230.0, lastIatC + 273.15);
                                double mafEstGs = ((double) rpm * (double) mapKpa / iatK) * engineVolL * DEFAULT_VE * 0.02904;
                                lph = mafEstGs * 0.3287; // 3600 / (14.7 * 745)
                            }
                        }
                        latest.instantLph = (float) lph;
                        latest.instantL100 = (spdKmh >= 5 && lph > 0.0)
                                ? (float) Math.min(99.9, (lph / spdKmh) * 100.0)
                                : 0f;

                        // Accumulate persistent long-term stats whenever engine is running
                        if (dtSec > 0.0 && rpm > 150) {
                            double dLiters = lph * (dtSec / 3600.0);
                            double dKm = (spdKmh > 0 ? spdKmh : 0) * (dtSec / 3600.0);
                            synchronized (statsLock) {
                                latest.totalLiters += dLiters;
                                latest.totalKm += dKm;
                                recomputeAvgLocked();
                            }
                            if (nowMs - lastPersistMs >= 15_000L) {
                                persistStatsNow();
                            }
                        }
                    }

                    notifyListener();
                    cycle++;
                    sleepQuiet(500);
                }
            } catch (Throwable e) {
                Log.d(TAG, "OBD poll retry: " + e.getMessage());
                latest.connected = false;
                latest.connecting = false;
                latest.ecuReady = false;
                latest.rpm = 0;
                latest.instantLph = 0f;
                latest.instantL100 = 0f;
                lastSampleMs = 0L;
                persistStatsNow();
                notifyListener();
            } finally {
                closeActiveSpp();
            }
            sleepQuiet(10_000);
        }
    }

    private void recomputeAvgLocked() {
        if (latest.totalKm >= 0.2) {
            latest.avgL100 = Math.min(99.9, (latest.totalLiters / latest.totalKm) * 100.0);
        } else {
            latest.avgL100 = 0.0;
        }
    }

    private boolean readVoltage(InputStream in, OutputStream out) {
        try {
            String vResp = sendCmd(in, out, "ATRV", 2500);
            Matcher m = VOLT_PATTERN.matcher(vResp);
            if (m.find()) {
                latest.voltage = Float.parseFloat(m.group(1));
                return true;
            }
            return vResp.contains(">");
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Cleanly closes stale ISO-9141 session (ATPC), resets auto-protocol (ATSP0),
     * and waits full deadline (up to 12s) for Honda 5-baud BUS INIT + 4100 response.
     */
    private boolean handshakeEcu(InputStream in, OutputStream out) throws Exception {
        sendCmd(in, out, "ATPC", 1500);
        sendCmd(in, out, "ATSP0", 1500);
        String r0100 = sendCmd(in, out, "0100", 12000);
        String clean = r0100.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        boolean ok = clean.contains("4100");
        Log.i(TAG, "ECU handshake 0100 -> " + r0100 + " (ok=" + ok + ")");
        return ok;
    }

    private boolean readSlowPids(InputStream in, OutputStream out) throws Exception {
        String coolResp = sendCmd(in, out, "0105", 2500);
        int cool = parseSingleByte(coolResp, "4105");
        if (cool >= 0) {
            latest.coolantTempC = cool - 40;
            int iat = parseSingleByte(sendCmd(in, out, "010F", 2000), "410F");
            if (iat >= 0) lastIatC = iat - 40;
            return true;
        }
        if (isEcuLost(coolResp)) {
            Log.i(TAG, "ECU lost on 0105: " + coolResp);
            return false;
        }
        return true;
    }

    private static boolean isEcuLost(String resp) {
        if (resp == null || resp.isEmpty()) return true;
        String u = resp.toUpperCase(Locale.US);
        return u.contains("UNABLE") || u.contains("NO DATA") || u.contains("ERROR") || u.contains("STOPPED");
    }

    private String sendCmd(InputStream in, OutputStream out, String cmd, int timeoutMs) throws Exception {
        // Drain any stale leftover bytes from previous timeout so stream never desyncs
        try {
            int avail = in.available();
            if (avail > 0) {
                byte[] trash = new byte[Math.min(avail, 512)];
                in.read(trash);
            }
        } catch (Throwable ignored) {}

        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && running && !externalClientActive) {
            try {
                int n = in.read(buf);
                if (n < 0) throw new Exception("SPP EOF");
                if (n == 0) continue;
                sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                if (sb.indexOf(">") >= 0) break;
            } catch (java.io.IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.US) : "";
                boolean isTimeout = (e instanceof java.net.SocketTimeoutException)
                        || (e instanceof java.io.InterruptedIOException)
                        || msg.contains("try again")
                        || msg.contains("timed out")
                        || msg.contains("timeout")
                        || msg.contains("eagain");
                if (!isTimeout) throw e;
                // Keep waiting until full deadline! Crucial for Honda ISO-9141 "BUS INIT: ..." (~5s)
                if (sb.indexOf(">") >= 0) break;
            }
        }
        return sb.toString().replace("\r", " ").replace("\n", " ").trim();
    }

    private static int parseSingleByte(String resp, String prefixHex) {
        String clean = resp.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        int idx = clean.indexOf(prefixHex);
        if (idx >= 0 && clean.length() >= idx + prefixHex.length() + 2) {
            try {
                return Integer.parseInt(clean.substring(idx + prefixHex.length(), idx + prefixHex.length() + 2), 16);
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static int parseRpm(String resp) {
        String clean = resp.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        int idx = clean.indexOf("410C");
        if (idx >= 0 && clean.length() >= idx + 8) {
            try {
                int a = Integer.parseInt(clean.substring(idx + 4, idx + 6), 16);
                int b = Integer.parseInt(clean.substring(idx + 6, idx + 8), 16);
                return ((a * 256) + b) / 4;
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private void notifyListener() {
        ObdListener l = listener;
        if (l == null) return;
        final ObdData snap = new ObdData();
        synchronized (statsLock) {
            snap.connected = latest.connected;
            snap.connecting = latest.connecting;
            snap.bridgeBusy = latest.bridgeBusy;
            snap.ecuReady = latest.ecuReady;
            snap.voltage = latest.voltage;
            snap.coolantTempC = latest.coolantTempC;
            snap.rpm = latest.rpm;
            snap.ecuSpeedKmh = latest.ecuSpeedKmh;
            snap.instantLph = latest.instantLph;
            snap.instantL100 = latest.instantL100;
            snap.totalKm = latest.totalKm;
            snap.totalLiters = latest.totalLiters;
            snap.avgL100 = latest.avgL100;
            snap.engineVolL = latest.engineVolL;
        }
        mainHandler.post(() -> l.onObdUpdated(snap));
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
