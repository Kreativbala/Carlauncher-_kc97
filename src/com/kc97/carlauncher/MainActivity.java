package com.kc97.carlauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener, CarMediaListenerService.MediaUpdateListener {
    private static final String TAG_BT = "KC97_BT";
    private static final String PREFS_NAME = "kc97_cockpit";
    private static final String KEY_MEDIA_TAB = "media_tab_v11";
    private static final String TAB_YANDEX = CarMediaListenerService.PKG_YANDEX;
    private static final String TAB_SPOTIFY = CarMediaListenerService.PKG_SPOTIFY;
    private static final String TAB_BLUETOOTH = "kte_bt_music";
    private static final int REQ_PERMS = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioManager audioManager;

    // Car telemetry views
    private TextView tvVoltage;
    private TextView tvVolume;
    private TextView tvObdBadge;
    private TextView tvTemp;
    private TextView tvSpeed;
    private TextView tvCoolantBadge;
    private TextView tvFuelInstant;
    private TextView tvFuelAvg;
    private TextView tvFuelDist;
    private TextView tvGpsMeta;
    private CarStatusView carStatusView;
    private TextView pillHeadlamp;
    private TextView pillHandbrake;
    private boolean previewHeadlights = false;
    private boolean nightDimActive = false;
    private boolean activityResumed = false;
    private boolean userManuallyOpenedDvr = false;

    // Live Blue ELM327 v1.5 OBD-II Fuel Computer + TCP bridge (127.0.0.1:35000)
    private ObdBridgeManager obdManager;
    private ObdBridgeManager.ObdData latestObdData;
    private boolean obdEcuReady = false;
    private int obdRpm = 0;
    private int obdEcuSpeedKmh = 0;
    private boolean lastHeadlampOn = false;
    private boolean lastHandbrakeOn = false;
    private int lastCpuTempC = 0;

    // v2.0 Modules: Safe City Speed Cameras, Point B Routing, On-Device Offline Maps, Silent Trip Logger
    private SpeedCameraManager speedCameraManager;
    private RouteManager routeManager;
    private OfflineMapUpdater offlineMapUpdater;
    private TripLogger tripLogger;

    // GPS Trip Computer state
    private double tripDistanceMeters = 0.0;
    private long tripStartElapsedMs = 0L;
    private int tripMaxSpeedKmh = 0;
    private int satUsedCount = 0;
    private int altitudeMeters = -9999;
    private Location lastTripLocation = null;
    private int gpsMoveStreak = 0;

    // Pure native Canvas Map (with offline disk cache + auto-night + cameras + route!)
    private NativeMapView mapView;
    private TextView tvTrafficPill;
    private TextView tvCameraPill;
    private TextView tvCameraBanner;
    private TextView tvRouteBanner;
    private TextView tvGpsPill;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private int currentSpeedKmh = 0;

    // Dock Bluetooth live badge
    private TextView btBadgeText;

    // 3-tab media widget: 1. Я.Музыка (default), 2. Spotify, 3. BT Музыка
    private String selectedMediaTab = TAB_YANDEX;
    private TextView tabYandex;
    private TextView tabSpotify;
    private TextView tabBluetooth;
    private ImageView ivCover;
    private TextView tvTrackTitle;
    private TextView tvTrackArtist;
    private MediaIconButton btnPrev;
    private MediaIconButton btnPlayPause;
    private MediaIconButton btnNext;
    private TextView tvPermHint;

    // Bluetooth music live state (from KteBluetooth broadcasts)
    private String btTrackName = "";
    private String btArtistName = "";
    private String btDeviceName = "";
    private boolean btPlaying = false;
    private boolean btConnected = false;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable periodicUpdater = new Runnable() {
        @Override
        public void run() {
            updateClockTempVol();
            updateCarSensors();
            updateTripUi();
            handler.postDelayed(this, 5000);
        }
    };

    private final ContentObserver sysSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateCarSensors();
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateVolumeBadge();
        }
    };

    private final BroadcastReceiver btMusicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String act = intent.getAction();
            Bundle ex = intent.getExtras();

            // Log BT extras so we can inspect wife's phone BT metadata in logcat -s KC97_BT
            if (ex != null) {
                StringBuilder sb = new StringBuilder(act).append(" -> ");
                for (String k : ex.keySet()) {
                    sb.append(k).append("=").append(ex.get(k)).append("; ");
                }
                Log.i(TAG_BT, sb.toString());
            } else {
                Log.i(TAG_BT, act + " (no extras)");
            }

            if ("com.kte.bluetooth_playName".equals(act) && ex != null) {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    if (v instanceof String && !((String) v).trim().isEmpty()) {
                        String kl = k.toLowerCase(Locale.US);
                        String val = ((String) v).trim();
                        if (kl.contains("artist") || kl.contains("singer")) {
                            btArtistName = val;
                        } else {
                            btTrackName = val;
                        }
                    }
                }
            } else if ("com.kte.bluetooth_playStatus".equals(act) || "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED".equals(act)) {
                if (ex != null) {
                    for (String k : ex.keySet()) {
                        Object v = ex.get(k);
                        if (v instanceof Boolean) {
                            btPlaying = (Boolean) v;
                        } else if (v instanceof Integer) {
                            int iv = (Integer) v;
                            btPlaying = (iv == 1 || iv == 10);
                        }
                    }
                }
            } else if ("com.kte.bluetooth_btState".equals(act) || "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(act)) {
                if (ex != null) {
                    for (String k : ex.keySet()) {
                        Object v = ex.get(k);
                        if (v instanceof Boolean) btConnected = (Boolean) v;
                        else if (v instanceof Integer) btConnected = ((Integer) v) > 0;
                        else if (v instanceof String && !((String) v).trim().isEmpty()) btDeviceName = ((String) v).trim();
                    }
                }
            } else if ("com.kte.bluetooth_getName".equals(act) && ex != null) {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    if (v instanceof String && !((String) v).trim().isEmpty()) {
                        btDeviceName = ((String) v).trim();
                    }
                }
            }
            if (TAB_BLUETOOTH.equals(selectedMediaTab)) {
                refreshMediaUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Allow sharing exported CSV trip logs via file:// URI on Android 8 without FileProvider overhead
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        selectedMediaTab = prefs.getString(KEY_MEDIA_TAB, TAB_YANDEX);
        tripStartElapsedMs = SystemClock.elapsedRealtime();

        speedCameraManager = new SpeedCameraManager(this);
        routeManager = new RouteManager(this);
        offlineMapUpdater = new OfflineMapUpdater(this);
        tripLogger = new TripLogger(this);

        setContentView(buildRootLayout());
        requestPermissionsIfNeeded();
        applyBootSwappinessAsync();

        if (mapView != null) {
            mapView.setCameras(speedCameraManager.getCameras());
            RouteManager.ActiveRoute savedRoute = routeManager.getActiveRoute();
            if (savedRoute != null) {
                mapView.setRoutePoints(savedRoute.points);
            }
            updateRouteBanner();
            mapView.setOnMapLongPressListener(this::showLongPressPointBDialog);
        }

        String savedMac = prefs.getString("obd_mac", ObdBridgeManager.DEFAULT_OBD_MAC);
        obdManager = new ObdBridgeManager(this, savedMac, this::onObdUpdated);
        obdManager.start();

        // Auto-return cockpit to front after DVR ("Стражи безопасности") autostart finishes launching on boot
        scheduleBootDvrAutoMinimize();
    }

    private void scheduleBootDvrAutoMinimize() {
        // Only during first 90 seconds after head unit cold boot (never interferes with manual DVR opening!)
        if (SystemClock.elapsedRealtime() > 95_000L) return;
        int[] delaysMs = {6500, 13000, 21000};
        for (int delay : delaysMs) {
            handler.postDelayed(() -> {
                if (!activityResumed && !userManuallyOpenedDvr && SystemClock.elapsedRealtime() < 110_000L) {
                    try {
                        Intent home = new Intent(MainActivity.this, MainActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(home);
                    } catch (Throwable ignored) {}
                }
            }, delay);
        }
    }

    /**
     * Automatically applies vm.swappiness=25 on head unit startup (using built-in root su 0),
     * so smooth app switching persists across every car restart without needing Tasker/init.d.
     */
    private void applyBootSwappinessAsync() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", "echo 25 > /proc/sys/vm/swappiness"});
                p.waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    private View buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#090C12"));
        int outerPad = dp(14);
        root.setPadding(outerPad, dp(10), outerPad, outerPad);

        // ===== TOP ROW (64%): Left Car Telemetry (33%) + Right Pure Native Map (67%) =====
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams topRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 64f);
        topRowLp.bottomMargin = dp(12);

        View carCard = buildCarCard();
        LinearLayout.LayoutParams carLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 33f);
        carLp.rightMargin = dp(12);
        topRow.addView(carCard, carLp);

        View mapCard = buildMapCard();
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 67f);
        topRow.addView(mapCard, mapLp);

        root.addView(topRow, topRowLp);

        // ===== BOTTOM ROW (36%): Left Hotkey Dock (55%) + Right 3-Tab Media Widget (45%) =====
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 36f);

        View dockCard = buildDockCard();
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 55f);
        dockLp.rightMargin = dp(12);
        bottomRow.addView(dockCard, dockLp);

        View mediaCard = buildMediaCard();
        LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 45f);
        bottomRow.addView(mediaCard, mediaLp);

        root.addView(bottomRow, bottomRowLp);
        return root;
    }

    private View buildCarCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeCardDrawable("#131924", "#243146", 20));
        int p = dp(13);
        card.setPadding(p, dp(10), p, dp(10));

        // Top strip: AKB Voltage + Volume  <spacer>  OBD status dot + CPU Temp (no duplicate clock!)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvVoltage = new TextView(this);
        tvVoltage.setText("⚡ —");
        tvVoltage.setTextColor(Color.parseColor("#64748B"));
        tvVoltage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvVoltage.getPaint().setFakeBoldText(true);
        tvVoltage.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvVoltage.setBackground(makeCardDrawable("#151D2B", "#26344B", 10));
        tvVoltage.setOnClickListener(v -> launchPackageOrToast("com.ovz.carscanner", "Car Scanner"));
        header.addView(tvVoltage);

        tvVolume = new TextView(this);
        tvVolume.setText("🔊 --");
        tvVolume.setTextColor(Color.parseColor("#93C5FD"));
        tvVolume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvVolume.getPaint().setFakeBoldText(true);
        tvVolume.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvVolume.setBackground(makeCardDrawable("#101E30", "#1E3A5F", 10));
        tvVolume.setOnClickListener(v -> launchPackageOrToast("com.kte.eqselect", "Эквалайзер"));
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        volLp.leftMargin = dp(6);
        header.addView(tvVolume, volLp);

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Colored OBD status badge: Green = OK, Yellow = Connecting, Red = No contact
        tvObdBadge = new TextView(this);
        tvObdBadge.setText("● OBD");
        tvObdBadge.setTextColor(Color.parseColor("#FBBF24"));
        tvObdBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvObdBadge.getPaint().setFakeBoldText(true);
        tvObdBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvObdBadge.setBackground(makeCardDrawable("#36260B", "#D97706", 10));
        tvObdBadge.setOnClickListener(v -> showFuelSettingsDialog());
        LinearLayout.LayoutParams obdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        obdLp.rightMargin = dp(6);
        header.addView(tvObdBadge, obdLp);

        tvTemp = new TextView(this);
        tvTemp.setText("CPU --°C");
        tvTemp.setTextColor(Color.parseColor("#34D399"));
        tvTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTemp.getPaint().setFakeBoldText(true);
        tvTemp.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvTemp.setBackground(makeCardDrawable("#0F2420", "#1E4D40", 10));
        tvTemp.setOnClickListener(v -> launchPackageOrToast("com.example.electricfan", "Вентилятор"));
        header.addView(tvTemp);
        card.addView(header);

        // Speedometer readout
        LinearLayout speedBox = new LinearLayout(this);
        speedBox.setOrientation(LinearLayout.HORIZONTAL);
        speedBox.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        tvSpeed = new TextView(this);
        tvSpeed.setText("0");
        tvSpeed.setTextColor(Color.parseColor("#F8FAFC"));
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        tvSpeed.getPaint().setFakeBoldText(true);
        tvSpeed.setIncludeFontPadding(false);
        speedBox.addView(tvSpeed);

        TextView unit = new TextView(this);
        unit.setText(" км/ч");
        unit.setTextColor(Color.parseColor("#64748B"));
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        unit.setPadding(dp(4), 0, 0, dp(5));
        speedBox.addView(unit);
        card.addView(speedBox);

        // Enlarged OBD Coolant & Fuel Computer panel (+2sp bigger for effortless reading!)
        LinearLayout fuelBox = new LinearLayout(this);
        fuelBox.setOrientation(LinearLayout.VERTICAL);
        fuelBox.setBackground(makeCardDrawable("#0E1521", "#1E2D45", 14));
        fuelBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        fuelBox.setOnClickListener(v -> showFuelSettingsDialog());

        // Row 1: Color-coded Coolant Badge (Blue <50 | Green 50-98 | Yellow 99-106 | Red >=107) + Instant Fuel
        LinearLayout fuelRow1 = new LinearLayout(this);
        fuelRow1.setOrientation(LinearLayout.HORIZONTAL);
        fuelRow1.setGravity(Gravity.CENTER);

        tvCoolantBadge = new TextView(this);
        tvCoolantBadge.setText("ОЖ —°C");
        tvCoolantBadge.setTextColor(Color.parseColor("#94A3B8"));
        tvCoolantBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvCoolantBadge.getPaint().setFakeBoldText(true);
        tvCoolantBadge.setSingleLine(true);
        tvCoolantBadge.setPadding(dp(9), dp(3), dp(9), dp(3));
        tvCoolantBadge.setBackground(makeCardDrawable("#151D2B", "#26344B", 9));
        LinearLayout.LayoutParams coolLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        coolLp.rightMargin = dp(8);
        fuelRow1.addView(tvCoolantBadge, coolLp);

        tvFuelInstant = new TextView(this);
        tvFuelInstant.setText("Тек: —");
        tvFuelInstant.setTextColor(Color.parseColor("#E2E8F0"));
        tvFuelInstant.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvFuelInstant.getPaint().setFakeBoldText(true);
        tvFuelInstant.setSingleLine(true);
        fuelRow1.addView(tvFuelInstant);
        fuelBox.addView(fuelRow1);

        // Row 2: Large Average Fuel + Distance/Liters
        LinearLayout fuelRow2 = new LinearLayout(this);
        fuelRow2.setOrientation(LinearLayout.HORIZONTAL);
        fuelRow2.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(5);

        tvFuelAvg = new TextView(this);
        tvFuelAvg.setText("Ср: — л/100");
        tvFuelAvg.setTextColor(Color.parseColor("#7DD3FC"));
        tvFuelAvg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvFuelAvg.getPaint().setFakeBoldText(true);
        tvFuelAvg.setSingleLine(true);
        LinearLayout.LayoutParams avgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        avgLp.rightMargin = dp(6);
        fuelRow2.addView(tvFuelAvg, avgLp);

        tvFuelDist = new TextView(this);
        tvFuelDist.setText("0.0 км (0.0 л)");
        tvFuelDist.setTextColor(Color.parseColor("#94A3B8"));
        tvFuelDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvFuelDist.getPaint().setFakeBoldText(true);
        tvFuelDist.setSingleLine(true);
        fuelRow2.addView(tvFuelDist);
        fuelBox.addView(fuelRow2, row2Lp);

        LinearLayout.LayoutParams fuelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fuelLp.topMargin = dp(4);
        fuelLp.bottomMargin = dp(3);
        card.addView(fuelBox, fuelLp);

        // GPS satellites & altitude compact line
        tvGpsMeta = new TextView(this);
        tvGpsMeta.setText("🛰 0 спутн.  •  высота — м");
        tvGpsMeta.setTextColor(Color.parseColor("#64748B"));
        tvGpsMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvGpsMeta.setGravity(Gravity.CENTER);
        tvGpsMeta.setSingleLine(true);
        card.addView(tvGpsMeta);

        // Car Graphic Canvas
        carStatusView = new CarStatusView(this);
        carStatusView.setOnLongClickListener(v -> {
            previewHeadlights = !previewHeadlights;
            updateCarSensors();
            Toast.makeText(this, previewHeadlights ? "Ночной режим / Фары: тест ВКЛ" : "Фары: авто-датчик", Toast.LENGTH_SHORT).show();
            return true;
        });
        card.addView(carStatusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom indicators row: Headlights (also auto-night!) + Real Handbrake / Parking
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setPadding(0, dp(4), 0, 0);

        pillHeadlamp = makeStatusPill("Фары: ВЫКЛ", "#1A2232", "#94A3B8");
        pillHeadlamp.setOnClickListener(v -> {
            previewHeadlights = !previewHeadlights;
            updateCarSensors();
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p1.rightMargin = dp(8);
        statusRow.addView(pillHeadlamp, p1);

        pillHandbrake = makeStatusPill("P Паркинг", "#132033", "#93C5FD");
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusRow.addView(pillHandbrake, p2);

        card.addView(statusRow);
        return card;
    }

    private View buildMapCard() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(makeCardDrawable("#0D131D", "#243146", 20));
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });

        mapView = new NativeMapView(this);
        card.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top-left row: Live Traffic toggle + Safe City Cameras & Offline Maps Hub
        LinearLayout topLeftRow = new LinearLayout(this);
        topLeftRow.setOrientation(LinearLayout.HORIZONTAL);

        tvTrafficPill = makeOverlayPill("● Пробки ВКЛ", "#D60D131D", "#22C55E", "#EAF2FF");
        tvTrafficPill.setOnClickListener(v -> {
            boolean on = mapView.toggleTraffic();
            tvTrafficPill.setText(on ? "● Пробки ВКЛ" : "○ Пробки ВЫКЛ");
            tvTrafficPill.setTextColor(Color.parseColor(on ? "#4ADE80" : "#94A3B8"));
        });
        LinearLayout.LayoutParams trfBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trfBtnLp.rightMargin = dp(8);
        topLeftRow.addView(tvTrafficPill, trfBtnLp);

        int camCnt = speedCameraManager != null ? speedCameraManager.getCameraCount() : 0;
        tvCameraPill = makeOverlayPill("📷 Камеры (" + camCnt + ")", "#D60D131D", "#38BDF8", "#E0F2FE");
        tvCameraPill.setOnClickListener(v -> showCamerasAndMapsDialog());
        topLeftRow.addView(tvCameraPill);

        FrameLayout.LayoutParams topLeftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        topLeftLp.leftMargin = dp(12);
        topLeftLp.topMargin = dp(12);
        card.addView(topLeftRow, topLeftLp);

        // Top-center HUD Camera Alert Banner (hidden until within 420m of a camera!)
        tvCameraBanner = makeOverlayPill("📷 Камера 60 • 300 м", "#E63B1219", "#EF4444", "#FFFFFF");
        tvCameraBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvCameraBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams camBanLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        camBanLp.topMargin = dp(56);
        card.addView(tvCameraBanner, camBanLp);

        tvGpsPill = makeOverlayPill("● Поиск GPS…", "#D60D131D", "#F59E0B", "#FDE68A");
        FrameLayout.LayoutParams gpsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        gpsLp.rightMargin = dp(12);
        gpsLp.topMargin = dp(12);
        card.addView(tvGpsPill, gpsLp);

        // Bottom-left stack: Active Route Banner + [🏁 Маршрут] + [⛶ Навигатор]
        LinearLayout bottomLeftCol = new LinearLayout(this);
        bottomLeftCol.setOrientation(LinearLayout.VERTICAL);

        tvRouteBanner = makeOverlayPill("", "#E60B2A38", "#38BDF8", "#E0F9FF");
        tvRouteBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvRouteBanner.setVisibility(View.GONE);
        tvRouteBanner.setOnClickListener(v -> showActiveRouteActionsDialog());
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbLp.bottomMargin = dp(8);
        bottomLeftCol.addView(tvRouteBanner, rbLp);

        LinearLayout navBtnRow = new LinearLayout(this);
        navBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnRoute = makeOverlayPill("🏁 Маршрут", "#DD112842", "#38BDF8", "#E0F9FF");
        btnRoute.setOnClickListener(v -> showRoutePickerDialog());
        LinearLayout.LayoutParams rBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rBtnLp.rightMargin = dp(8);
        navBtnRow.addView(btnRoute, rBtnLp);

        TextView btnNav = makeOverlayPill("⛶ Я.Навигатор", "#CC0B2A38", "#00D4FF", "#E0F9FF");
        btnNav.setOnClickListener(v -> openPreferredNavigator());
        navBtnRow.addView(btnNav);

        bottomLeftCol.addView(navBtnRow);

        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        navLp.leftMargin = dp(12);
        navLp.bottomMargin = dp(12);
        card.addView(bottomLeftCol, navLp);

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setOrientation(LinearLayout.VERTICAL);
        TextView bPlus = makeMapCtrlBtn("+");
        bPlus.setOnClickListener(v -> mapView.zoomBy(1));
        TextView bMinus = makeMapCtrlBtn("−");
        bMinus.setOnClickListener(v -> mapView.zoomBy(-1));
        TextView bLoc = makeMapCtrlBtn("◎");
        bLoc.setOnClickListener(v -> mapView.recenter());

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(44), dp(44));
        clp.bottomMargin = dp(8);
        ctrls.addView(bPlus, clp);
        ctrls.addView(bMinus, clp);
        ctrls.addView(bLoc, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams ctrlBoxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        ctrlBoxLp.rightMargin = dp(12);
        ctrlBoxLp.bottomMargin = dp(12);
        card.addView(ctrls, ctrlBoxLp);

        View borderOverlay = new View(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(20));
        border.setStroke(dp(1), Color.parseColor("#243146"));
        borderOverlay.setBackground(border);
        borderOverlay.setClickable(false);
        card.addView(borderOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return card;
    }

    private View buildDockCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(makeCardDrawable("#131924", "#243146", 20));
        int p = dp(10);
        card.setPadding(p, p, p, p);

        card.addView(makeDockTile("⊞", "Меню", "#3B1D5E", "#C084FC", v -> new AppDrawerDialog(this).show(), null));
        card.addView(makeDockTile("➤", "Навигатор", "#0F3D3E", "#2DD4BF", v -> openPreferredNavigator(), null));
        card.addView(makeDockTile("◉", "Стражи", "#4A2511", "#FB923C",
                v -> {
                    userManuallyOpenedDvr = true;
                    launchPackageOrToast("com.fvision.camera", "Стражи безопасности");
                },
                v -> {
                    launchPackageOrToast("com.kte.radio", "Радио");
                    return true;
                }));

        LinearLayout btTile = makeDockTile("ᛒ", "Bluetooth", "#103266", "#60A5FA", v -> launchPackageOrToast("com.kte.bluetooth", "Bluetooth"), null);
        btBadgeText = (TextView) btTile.getChildAt(1);
        card.addView(btTile);

        card.addView(makeDockTile("ılı", "Звук / EQ", "#133A3A", "#38BDF8", v -> launchPackageOrToast("com.kte.eqselect", "Эквалайзер"), null));
        card.addView(makeDockTile("▶", "YouTube", "#4A1219", "#FF3B4E",
                v -> launchPackageOrToast("com.google.android.youtube", "YouTube"),
                v -> {
                    launchPackageOrToast("com.zjinnova.zlink", "ZLink");
                    return true;
                }));

        return card;
    }

    private LinearLayout makeDockTile(String glyph, String title, String iconBgHex, String accentHex,
                                      View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(8), dp(5), dp(8));
        tile.setBackground(makeCardDrawable("#182130", "#26344B", 16));
        tile.setOnClickListener(onClick);
        if (onLongClick != null) tile.setOnLongClickListener(onLongClick);

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextColor(Color.parseColor(accentHex));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        icon.getPaint().setFakeBoldText(true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(makeCardDrawable(iconBgHex, accentHex, 15));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(54), dp(54));
        ilp.bottomMargin = dp(8);
        tile.addView(icon, ilp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.parseColor("#E2E8F0"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.getPaint().setFakeBoldText(true);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(label);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tlp.leftMargin = dp(4);
        tlp.rightMargin = dp(4);
        tile.setLayoutParams(tlp);
        return tile;
    }

    private View buildMediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeCardDrawable("#131924", "#243146", 20));
        int p = dp(12);
        card.setPadding(p, dp(10), p, dp(10));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(makeCardDrawable("#0B1018", "#1E293B", 12));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));

        tabYandex = makeMediaTab("● Я.Музыка", v -> selectMediaTab(TAB_YANDEX));
        tabSpotify = makeMediaTab("● Spotify", v -> selectMediaTab(TAB_SPOTIFY));
        tabBluetooth = makeMediaTab("● BT Музыка", v -> selectMediaTab(TAB_BLUETOOTH));

        tabs.addView(tabYandex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabSpotify, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabBluetooth, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.bottomMargin = dp(10);
        card.addView(tabs, tabsLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        ivCover = new ImageView(this);
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCover.setBackground(makeCardDrawable("#1A2333", "#2A3A52", 14));
        ivCover.setClipToOutline(true);
        ivCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        ivCover.setOnClickListener(v -> openSelectedMediaApp());
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(86), dp(86));
        coverLp.rightMargin = dp(14);
        body.addView(ivCover, coverLp);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);

        tvTrackTitle = new TextView(this);
        tvTrackTitle.setText("Яндекс Музыка");
        tvTrackTitle.setTextColor(Color.parseColor("#F8FAFC"));
        tvTrackTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTrackTitle.getPaint().setFakeBoldText(true);
        tvTrackTitle.setSingleLine(true);
        tvTrackTitle.setEllipsize(TextUtils.TruncateAt.END);
        rightCol.addView(tvTrackTitle);

        tvTrackArtist = new TextView(this);
        tvTrackArtist.setText("Нажмите ▶ для запуска");
        tvTrackArtist.setTextColor(Color.parseColor("#94A3B8"));
        tvTrackArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTrackArtist.setSingleLine(true);
        tvTrackArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.bottomMargin = dp(10);
        rightCol.addView(tvTrackArtist, artistLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        btnPrev = new MediaIconButton(this, MediaIconButton.ICON_PREV, "#1B2536", "#2D3D56", "#E2E8F0");
        btnPrev.setOnClickListener(v -> onMediaPrev());
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        prevLp.rightMargin = dp(8);
        controls.addView(btnPrev, prevLp);

        btnPlayPause = new MediaIconButton(this, MediaIconButton.ICON_PLAY, "#FFCC00", "#FFCC00", "#090C12");
        btnPlayPause.setOnClickListener(v -> onMediaPlayPause());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(48), 1.35f);
        playLp.rightMargin = dp(8);
        controls.addView(btnPlayPause, playLp);

        btnNext = new MediaIconButton(this, MediaIconButton.ICON_NEXT, "#1B2536", "#2D3D56", "#E2E8F0");
        btnNext.setOnClickListener(v -> onMediaNext());
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        controls.addView(btnNext, nextLp);

        rightCol.addView(controls);

        tvPermHint = new TextView(this);
        tvPermHint.setText("Нажмите, чтобы разрешить чтение треков");
        tvPermHint.setTextColor(Color.parseColor("#F59E0B"));
        tvPermHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPermHint.setPadding(0, dp(4), 0, 0);
        tvPermHint.setVisibility(View.GONE);
        tvPermHint.setOnClickListener(v -> {
            try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (Throwable ignored) {}
        });
        rightCol.addView(tvPermHint);

        body.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshMediaUi();
        return card;
    }

    private TextView makeMediaTab(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.getPaint().setFakeBoldText(true);
        tv.setSingleLine(true);
        tv.setPadding(dp(4), dp(6), dp(4), dp(6));
        tv.setOnClickListener(l);
        return tv;
    }

    private void selectMediaTab(String tab) {
        selectedMediaTab = tab;
        prefs.edit().putString(KEY_MEDIA_TAB, tab).apply();
        if (TAB_BLUETOOTH.equals(tab)) queryKteBtState();
        refreshMediaUi();
    }

    private void openSelectedMediaApp() {
        if (TAB_BLUETOOTH.equals(selectedMediaTab)) launchPackageOrToast("com.kte.bluetooth", "Bluetooth");
        else if (TAB_SPOTIFY.equals(selectedMediaTab)) launchPackageOrToast(TAB_SPOTIFY, "Spotify");
        else launchPackageOrToast(TAB_YANDEX, "Яндекс Музыка");
    }

    private void onMediaPlayPause() {
        if (TAB_BLUETOOTH.equals(selectedMediaTab)) {
            // Direct SD-968 hardware AVRCP control via /dev/goc_serial (AT#VB unmute + AT#MA play/pause)
            new Thread(() -> {
                ObdBridgeManager.sendGocSerialCmd("AT#VB");
                ObdBridgeManager.sendGocSerialCmd("AT#MA");
            }, "BT-PlayPause").start();
            sendBroadcast(new Intent("com.kte.bluetooth_playPause"));
            btPlaying = !btPlaying;
            refreshMediaUi();
        } else {
            CarMediaListenerService.togglePlayPause(this, selectedMediaTab);
        }
    }

    private void onMediaNext() {
        if (TAB_BLUETOOTH.equals(selectedMediaTab)) {
            new Thread(() -> ObdBridgeManager.sendGocSerialCmd("AT#MD"), "BT-Next").start();
            sendBroadcast(new Intent("com.kte.bluetooth_next"));
        } else {
            CarMediaListenerService.skipNext(this, selectedMediaTab);
        }
    }

    private void onMediaPrev() {
        if (TAB_BLUETOOTH.equals(selectedMediaTab)) {
            new Thread(() -> ObdBridgeManager.sendGocSerialCmd("AT#ME"), "BT-Prev").start();
            sendBroadcast(new Intent("com.kte.bluetooth_previous"));
        } else {
            CarMediaListenerService.skipPrevious(this, selectedMediaTab);
        }
    }

    private void queryKteBtState() {
        try {
            sendBroadcast(new Intent("com.kte.bluetooth_getBtState"));
            sendBroadcast(new Intent("com.kte.bluetooth_getName"));
        } catch (Throwable ignored) {}
    }

    private void refreshMediaUi() {
        boolean isYandex = TAB_YANDEX.equals(selectedMediaTab);
        boolean isSpotify = TAB_SPOTIFY.equals(selectedMediaTab);
        boolean isBt = TAB_BLUETOOTH.equals(selectedMediaTab);

        styleTab(tabYandex, isYandex, "#3D310B", "#FFCC00", "#FDE047");
        styleTab(tabSpotify, isSpotify, "#133A24", "#1DB954", "#4ADE80");
        styleTab(tabBluetooth, isBt, "#103266", "#3B82F6", "#60A5FA");

        String accentBg = isYandex ? "#FFCC00" : (isSpotify ? "#1DB954" : "#3B82F6");
        String accentFg = isBt ? "#FFFFFF" : "#090C12";
        btnPlayPause.setColors(accentBg, accentBg, accentFg);

        if (isBt) {
            tvPermHint.setVisibility(View.GONE);
            setAppIconCover("com.kte.bluetooth");
            String title = !btTrackName.isEmpty() ? btTrackName : "Bluetooth Музыка";
            String sub = !btArtistName.isEmpty() ? btArtistName
                    : (!btDeviceName.isEmpty() ? ("Телефон: " + btDeviceName)
                    : (btConnected ? "Телефон подключён" : "Нажмите на иконку для подключения"));
            tvTrackTitle.setText(title);
            tvTrackArtist.setText(sub);
            btnPlayPause.setIconType(btPlaying ? MediaIconButton.ICON_PAUSE : MediaIconButton.ICON_PLAY);
            return;
        }

        CarMediaListenerService.TrackInfo info = CarMediaListenerService.getTrackInfo(selectedMediaTab);
        tvPermHint.setVisibility(CarMediaListenerService.isConnected() ? View.GONE : View.VISIBLE);

        if (info.hasSession && info.title != null && !info.title.isEmpty()) {
            tvTrackTitle.setText(info.title);
            tvTrackArtist.setText(info.artist != null && !info.artist.isEmpty()
                    ? info.artist : (isSpotify ? "Spotify" : "Яндекс Музыка"));
            btnPlayPause.setIconType(info.playing ? MediaIconButton.ICON_PAUSE : MediaIconButton.ICON_PLAY);
            if (info.artwork != null) {
                ivCover.setPadding(0, 0, 0, 0);
                ivCover.setImageBitmap(info.artwork);
            } else {
                setAppIconCover(selectedMediaTab);
            }
        } else {
            tvTrackTitle.setText(isSpotify ? "Spotify" : "Яндекс Музыка");
            tvTrackArtist.setText("Нажмите ▶ или на иконку");
            btnPlayPause.setIconType(MediaIconButton.ICON_PLAY);
            setAppIconCover(selectedMediaTab);
        }
    }

    private void styleTab(TextView tv, boolean active, String bg, String border, String fg) {
        if (active) {
            tv.setBackground(makeCardDrawable(bg, border, 10));
            tv.setTextColor(Color.parseColor(fg));
        } else {
            tv.setBackground(null);
            tv.setTextColor(Color.parseColor("#64748B"));
        }
    }

    private void setAppIconCover(String pkg) {
        try {
            ivCover.setPadding(dp(12), dp(12), dp(12), dp(12));
            ivCover.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
        } catch (Throwable ignored) {
            ivCover.setImageResource(R.drawable.ic_launcher);
        }
    }

    private void updateVolumeBadge() {
        if (audioManager == null || tvVolume == null) return;
        try {
            int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            tvVolume.setText(vol == 0 ? "🔇 0" : ("🔊 " + vol));
        } catch (Throwable ignored) {}
    }

    private void updateClockTempVol() {
        updateVolumeBadge();

        int tempC = readCpuTempC();
        if (tempC > 0) {
            lastCpuTempC = tempC;
            tvTemp.setText("CPU " + tempC + "°C");
            if (tempC >= 75) {
                tvTemp.setTextColor(Color.parseColor("#FB7185"));
                tvTemp.setBackground(makeCardDrawable("#3B1219", "#E11D48", 10));
            } else if (tempC >= 65) {
                tvTemp.setTextColor(Color.parseColor("#FBBF24"));
                tvTemp.setBackground(makeCardDrawable("#36260B", "#D97706", 10));
            } else {
                tvTemp.setTextColor(Color.parseColor("#34D399"));
                tvTemp.setBackground(makeCardDrawable("#0F2420", "#1E4D40", 10));
            }
        }
    }

    private void updateTripUi() {
        String altStr = altitudeMeters > -1000 ? (altitudeMeters + " м") : "— м";
        tvGpsMeta.setText(String.format(Locale.US, "🛰 %d спутн.  •  высота %s  •  макс %d",
                satUsedCount, altStr, tripMaxSpeedKmh));
    }

    private int readCpuTempC() {
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone1/temp"))) {
            String line = br.readLine();
            if (line != null) {
                int raw = Integer.parseInt(line.trim());
                return raw > 1000 ? raw / 1000 : raw;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private int readHandbrakeGpio() {
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/devices/virtual/kte/kte_hand_brake/hand_brake_status"))) {
            String line = br.readLine();
            if (line != null) return Integer.parseInt(line.trim());
        } catch (Throwable ignored) {}
        return 0;
    }

    private void applyAutoNightMode(boolean headlightsOn) {
        if (nightDimActive == headlightsOn) return;
        nightDimActive = headlightsOn;
        if (mapView != null) mapView.setNightDimMode(headlightsOn);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            // Softly dim window when headlights are ON (0.28), restore system brightness when OFF (-1f)
            lp.screenBrightness = headlightsOn ? 0.28f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private void updateCarSensors() {
        int lamp = 0, rev = 0, btStat = 0;
        int brake = readHandbrakeGpio();
        try {
            lamp = Settings.System.getInt(getContentResolver(), "Headlamp", 0);
            rev = Settings.System.getInt(getContentResolver(), "backcar_satus", 0);
            btStat = Settings.System.getInt(getContentResolver(), "kte_bt_status", 0);
        } catch (Throwable ignored) {}

        boolean headlights = (lamp != 0) || previewHeadlights;
        boolean handbrake = (brake != 0);
        boolean reverse = (rev != 0);
        lastHeadlampOn = headlights;
        lastHandbrakeOn = handbrake;
        if (btStat == 1) btConnected = true;

        carStatusView.updateState(headlights, handbrake, reverse, currentSpeedKmh);
        applyAutoNightMode(headlights);

        if (headlights) {
            pillHeadlamp.setText("🌙 Фары: ВКЛ");
            pillHeadlamp.setTextColor(Color.parseColor("#67E8F9"));
            pillHeadlamp.setBackground(makeCardDrawable("#0E3242", "#00D4FF", 12));
        } else {
            pillHeadlamp.setText("Фары: ВЫКЛ");
            pillHeadlamp.setTextColor(Color.parseColor("#94A3B8"));
            pillHeadlamp.setBackground(makeCardDrawable("#1A2232", "#2A364C", 12));
        }

        if (handbrake) {
            pillHandbrake.setText("(P) Ручник");
            pillHandbrake.setTextColor(Color.parseColor("#FDA4AF"));
            pillHandbrake.setBackground(makeCardDrawable("#3B1219", "#E11D48", 12));
        } else if (reverse) {
            pillHandbrake.setText("R Задний ход");
            pillHandbrake.setTextColor(Color.parseColor("#FDE047"));
            pillHandbrake.setBackground(makeCardDrawable("#3B2E0A", "#EAB308", 12));
        } else if (currentSpeedKmh == 0) {
            pillHandbrake.setText("P Паркинг");
            pillHandbrake.setTextColor(Color.parseColor("#93C5FD"));
            pillHandbrake.setBackground(makeCardDrawable("#132033", "#2B4A73", 12));
        } else {
            pillHandbrake.setText("D В движении");
            pillHandbrake.setTextColor(Color.parseColor("#6EE7B7"));
            pillHandbrake.setBackground(makeCardDrawable("#0F2922", "#10B981", 12));
        }

        if (btBadgeText != null) {
            btBadgeText.setText(btStat == 1 ? "Bluetooth ●" : "Bluetooth");
        }
    }

    private void openPreferredNavigator() {
        RouteManager.ActiveRoute r = routeManager != null ? routeManager.getActiveRoute() : null;
        if (r != null) {
            try {
                Uri uri = Uri.parse(String.format(Locale.US,
                        "yandexnavi://build_route_on_map?lat_to=%.5f&lon_to=%.5f", r.destLat, r.destLon));
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage("ru.yandex.yandexnavi");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {}
        }
        if (!launchPackageSilent("ru.yandex.yandexnavi")) {
            launchPackageOrToast("com.google.android.apps.maps", "Навигатор");
        }
    }

    private boolean launchPackageSilent(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void launchPackageOrToast(String pkg, String name) {
        if (!launchPackageSilent(pkg)) {
            Toast.makeText(this, name + " не установлен", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermissionsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_PERMS);
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (locationManager == null) locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this);
            }
            if (gnssCallback == null) {
                gnssCallback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        int used = 0;
                        int total = status.getSatelliteCount();
                        for (int i = 0; i < total; i++) {
                            if (status.usedInFix(i)) used++;
                        }
                        satUsedCount = used > 0 ? used : total;
                        handler.post(() -> updateTripUi());
                    }
                };
            }
            locationManager.registerGnssStatusCallback(gnssCallback);

            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) onLocationChanged(loc);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        int rawGpsSpd = location.hasSpeed() ? Math.round(location.getSpeed() * 3.6f) : 0;
        float acc = location.hasAccuracy() ? location.getAccuracy() : 99f;

        int spd;
        if (obdEcuReady) {
            // ECU wheel speed sensor is live: zero parked/red-light GPS drift!
            if (obdRpm < 150 && obdEcuSpeedKmh == 0) {
                spd = 0;
                gpsMoveStreak = 0;
            } else if (obdEcuSpeedKmh == 0) {
                spd = (rawGpsSpd >= 10 && acc < 20f) ? rawGpsSpd : 0;
                if (spd == 0) gpsMoveStreak = 0;
            } else {
                spd = (rawGpsSpd >= 3 && acc < 30f) ? rawGpsSpd : obdEcuSpeedKmh;
                gpsMoveStreak = 2;
            }
        } else {
            // Ignition OFF / OBD not locked: strict GPS stationary deadband + hysteresis
            if (currentSpeedKmh == 0) {
                int startThresh = acc > 25f ? 9 : 6;
                if (rawGpsSpd >= 10 && acc < 25f) {
                    gpsMoveStreak = 2;
                    spd = rawGpsSpd;
                } else if (rawGpsSpd >= startThresh && acc < 30f) {
                    gpsMoveStreak++;
                    spd = (gpsMoveStreak >= 2) ? rawGpsSpd : 0;
                } else {
                    gpsMoveStreak = 0;
                    spd = 0;
                }
            } else {
                if (rawGpsSpd < 3) {
                    gpsMoveStreak = 0;
                    spd = 0;
                } else {
                    spd = rawGpsSpd;
                }
            }
        }

        currentSpeedKmh = spd;
        if (spd > tripMaxSpeedKmh) tripMaxSpeedKmh = spd;

        if (location.hasAltitude()) {
            altitudeMeters = (int) Math.round(location.getAltitude());
        }

        // Accumulate trip distance accurately (ignore GPS jitter when parked)
        if (lastTripLocation != null && spd >= 5 && acc < 35f) {
            float d = lastTripLocation.distanceTo(location);
            if (d > 3f && d < 500f) {
                tripDistanceMeters += d;
            }
        }
        if (acc < 40f) {
            lastTripLocation = location;
        }

        tvSpeed.setText(String.valueOf(spd));
        updateTripUi();
        updateCarSensors();

        if (tvGpsPill != null) {
            tvGpsPill.setText("● GPS OK");
            tvGpsPill.setTextColor(Color.parseColor("#4ADE80"));
        }
        if (mapView != null) {
            mapView.updateGps(location.getLatitude(), location.getLongitude(), location.getBearing(), spd);
        }
        if (routeManager != null) {
            routeManager.updateProgress(location.getLatitude(), location.getLongitude());
            updateRouteBanner();
        }
        if (speedCameraManager != null) {
            float brg = location.hasBearing() ? location.getBearing() : (mapView != null ? mapView.getCarBearing() : 0f);
            SpeedCameraManager.CameraAlert alert = speedCameraManager.evaluate(
                    location.getLatitude(), location.getLongitude(), brg, spd);
            updateCameraBanner(alert);
        }
        if (tripLogger != null) {
            tripLogger.recordSample(location.getLatitude(), location.getLongitude(), spd,
                    latestObdData, lastHeadlampOn, lastHandbrakeOn, lastCpuTempC);
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onMediaUpdated() { refreshMediaUi(); }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (obdManager != null) obdManager.onActivityResumed();
        CarMediaListenerService.setUiListener(this);
        if (!CarMediaListenerService.isConnected()) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, CarMediaListenerService.class));
            } catch (Throwable ignored) {}
        }

        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction("com.kte.bluetooth_playName");
        btFilter.addAction("com.kte.bluetooth_playStatus");
        btFilter.addAction("com.kte.bluetooth_btState");
        btFilter.addAction("com.kte.bluetooth_getName");
        btFilter.addAction("com.kte.bluetooth_connectAddress");
        btFilter.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        btFilter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        registerReceiver(btMusicReceiver, btFilter);

        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        queryKteBtState();

        refreshMediaUi();
        updateClockTempVol();
        updateTripUi();
        updateCarSensors();
        handler.removeCallbacks(periodicUpdater);
        handler.postDelayed(periodicUpdater, 5000);
        try {
            getContentResolver().registerContentObserver(Settings.System.getUriFor("Headlamp"), false, sysSettingsObserver);
            getContentResolver().registerContentObserver(Settings.System.getUriFor("backcar_satus"), false, sysSettingsObserver);
        } catch (Throwable ignored) {}
        startLocationUpdates();
        if (mapView != null) mapView.setPaused(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        if (obdManager != null) obdManager.persistStatsNow();
        if (tripLogger != null) tripLogger.flush();
        CarMediaListenerService.setUiListener(null);
        try { unregisterReceiver(btMusicReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(volumeReceiver); } catch (Throwable ignored) {}
        handler.removeCallbacks(periodicUpdater);
        try { getContentResolver().unregisterContentObserver(sysSettingsObserver); } catch (Throwable ignored) {}
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Throwable ignored) {}
            if (gnssCallback != null) {
                try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Throwable ignored) {}
            }
        }
        if (mapView != null) mapView.setPaused(true);
    }

    private void showFuelSettingsDialog() {
        if (obdManager == null) return;
        int logCount = tripLogger != null ? tripLogger.getLogFileCount() : 0;
        final String[] items = {
                "📤 Экспорт логов поездки (CSV, файлов: " + logCount + ")",
                "🔌 Переподключить OBD (ELM327)",
                "🔄 Сбросить статистику расхода (км и литры)",
                "🔧 Открыть Car Scanner (127.0.0.1:35000)",
                "Объём двигателя: 1.5 л",
                "Объём двигателя: 1.6 л",
                "Объём двигателя: 1.8 л",
                "Объём двигателя: 2.0 л (Honda K20A)",
                "Объём двигателя: 2.4 л",
                "Объём двигателя: 2.5 л",
                "Объём двигателя: 3.0 л",
                "Объём двигателя: 3.5 л"
        };
        final float[] vols = {1.5f, 1.6f, 1.8f, 2.0f, 2.4f, 2.5f, 3.0f, 3.5f};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(String.format(Locale.US, "OBD и Расход (объём %.1f л)", obdManager.getEngineVolL()))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        if (tripLogger != null) {
                            String res = tripLogger.exportAndShareLatest(this);
                            Toast.makeText(this, res, Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        obdManager.forceReconnect();
                        Toast.makeText(this, "Переподключение к ELM327…", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        obdManager.resetStatistics();
                        Toast.makeText(this, "Статистика расхода и пробега сброшена", Toast.LENGTH_SHORT).show();
                    } else if (which == 3) {
                        launchPackageOrToast("com.ovz.carscanner", "Car Scanner");
                    } else {
                        int vIdx = which - 4;
                        if (vIdx >= 0 && vIdx < vols.length) {
                            obdManager.setEngineVolL(vols[vIdx]);
                            Toast.makeText(this, String.format(Locale.US, "Установлен объём %.1f л", vols[vIdx]), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void updateCoolantBadge(int tempC, boolean valid) {
        if (tvCoolantBadge == null) return;
        if (!valid || tempC <= -100) {
            tvCoolantBadge.setText("ОЖ —°C");
            tvCoolantBadge.setTextColor(Color.parseColor("#94A3B8"));
            tvCoolantBadge.setBackground(makeCardDrawable("#151D2B", "#26344B", 9));
            return;
        }
        tvCoolantBadge.setText("ОЖ " + tempC + "°C");
        if (tempC >= 107) {
            // Critical overheating (>= 107°C) -> RED
            tvCoolantBadge.setTextColor(Color.parseColor("#FCA5A5"));
            tvCoolantBadge.setBackground(makeCardDrawable("#4C0D19", "#EF4444", 9));
        } else if (tempC >= 99) {
            // High but not critical (99..106°C) -> YELLOW
            tvCoolantBadge.setTextColor(Color.parseColor("#FDE047"));
            tvCoolantBadge.setBackground(makeCardDrawable("#3B2A0A", "#EAB308", 9));
        } else if (tempC >= 50) {
            // Warmed up, good to drive (50..98°C) -> GREEN
            tvCoolantBadge.setTextColor(Color.parseColor("#4ADE80"));
            tvCoolantBadge.setBackground(makeCardDrawable("#0F2920", "#22C55E", 9));
        } else {
            // Cold engine (< 50°C), wait / drive gently -> BLUE
            tvCoolantBadge.setTextColor(Color.parseColor("#38BDF8"));
            tvCoolantBadge.setBackground(makeCardDrawable("#0D2842", "#0284C7", 9));
        }
    }

    private void onObdUpdated(ObdBridgeManager.ObdData d) {
        if (tvVoltage == null || tvObdBadge == null || tvCoolantBadge == null
                || tvFuelInstant == null || tvFuelAvg == null || tvFuelDist == null) return;

        latestObdData = d;
        obdEcuReady = d.connected && d.ecuReady && !d.bridgeBusy;
        obdRpm = d.rpm;
        obdEcuSpeedKmh = d.ecuSpeedKmh;
        if (tripLogger != null && mapView != null) {
            tripLogger.recordSample(mapView.getCarLat(), mapView.getCarLon(), currentSpeedKmh,
                    d, lastHeadlampOn, lastHandbrakeOn, lastCpuTempC);
        }
        if (obdEcuReady && obdEcuSpeedKmh == 0 && obdRpm < 150 && currentSpeedKmh != 0) {
            currentSpeedKmh = 0;
            gpsMoveStreak = 0;
            tvSpeed.setText("0");
            updateCarSensors();
        }

        // Always display persistent saved average consumption & distance
        String avgStr = d.avgL100 > 0.05 ? String.format(Locale.US, "%.1f", d.avgL100) : "—";
        tvFuelAvg.setText("Ср: " + avgStr + " л/100");
        tvFuelDist.setText(String.format(Locale.US, "%.1f км (%.1f л)", d.totalKm, d.totalLiters));

        if (d.bridgeBusy) {
            tvObdBadge.setText("● OBD");
            tvObdBadge.setTextColor(Color.parseColor("#38BDF8"));
            tvObdBadge.setBackground(makeCardDrawable("#0E3242", "#00D4FF", 10));
            tvVoltage.setText("⚡ CarScan");
            tvVoltage.setTextColor(Color.parseColor("#38BDF8"));
            tvVoltage.setBackground(makeCardDrawable("#0E3242", "#00D4FF", 10));
            updateCoolantBadge(-999, false);
            tvFuelInstant.setText("Car Scanner");
            tvFuelInstant.setTextColor(Color.parseColor("#38BDF8"));
            return;
        }

        // Update colored OBD indicator next to CPU: Green = ECU connected, Yellow = Connecting, Red = No contact
        if (d.connected && d.ecuReady) {
            tvObdBadge.setText("● OBD");
            tvObdBadge.setTextColor(Color.parseColor("#4ADE80"));
            tvObdBadge.setBackground(makeCardDrawable("#0F2920", "#22C55E", 10));
        } else if (d.connecting || d.connected) {
            tvObdBadge.setText("● OBD");
            tvObdBadge.setTextColor(Color.parseColor("#FBBF24"));
            tvObdBadge.setBackground(makeCardDrawable("#36260B", "#EAB308", 10));
        } else {
            tvObdBadge.setText("● OBD");
            tvObdBadge.setTextColor(Color.parseColor("#FB7185"));
            tvObdBadge.setBackground(makeCardDrawable("#3B1219", "#E11D48", 10));
        }

        if (d.connected && d.voltage > 5.0f) {
            tvVoltage.setText(String.format(Locale.US, "⚡ %.1fV", d.voltage));
            if (d.voltage < 11.8f) {
                tvVoltage.setTextColor(Color.parseColor("#FB7185"));
                tvVoltage.setBackground(makeCardDrawable("#3B1219", "#E11D48", 10));
            } else if (d.voltage > 14.8f) {
                tvVoltage.setTextColor(Color.parseColor("#FBBF24"));
                tvVoltage.setBackground(makeCardDrawable("#36260B", "#D97706", 10));
            } else {
                tvVoltage.setTextColor(Color.parseColor("#38BDF8"));
                tvVoltage.setBackground(makeCardDrawable("#0F1D2E", "#00D4FF", 10));
            }

            if (d.ecuReady) {
                updateCoolantBadge(d.coolantTempC, true);
                String instStr;
                if (d.rpm < 150) {
                    instStr = "0.0 л/ч";
                } else if (d.ecuSpeedKmh >= 5) {
                    instStr = String.format(Locale.US, "%.1f л/100", d.instantL100);
                } else {
                    instStr = String.format(Locale.US, "%.1f л/ч", d.instantLph);
                }
                tvFuelInstant.setText("Тек: " + instStr);
                tvFuelInstant.setTextColor(Color.parseColor("#F8FAFC"));
            } else {
                updateCoolantBadge(-999, false);
                tvFuelInstant.setText("Тек: —");
                tvFuelInstant.setTextColor(Color.parseColor("#94A3B8"));
            }
        } else {
            tvVoltage.setText("⚡ —");
            tvVoltage.setTextColor(Color.parseColor("#64748B"));
            tvVoltage.setBackground(makeCardDrawable("#151D2B", "#26344B", 10));
            updateCoolantBadge(-999, false);
            tvFuelInstant.setText("Тек: —");
            tvFuelInstant.setTextColor(Color.parseColor("#64748B"));
        }
    }

    // ==================== v2.0: Point B Routing, Safe City Cameras & Offline Map Sync ====================

    private void showRoutePickerDialog() {
        if (routeManager == null || mapView == null) return;
        RouteManager.Place home = routeManager.getFavorite("home", "🏠 Дом");
        RouteManager.Place work = routeManager.getFavorite("work", "💼 Работа");

        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        labels.add("🔎 Поиск адреса (Чуй / Бишкек / Иссык-Куль)");
        labels.add(home != null ? "🏠 Поехать Домой" : "🏠 Сохранить текущее место как «Дом»");
        labels.add(work != null ? "💼 Поехать на Работу" : "💼 Сохранить текущее место как «Работа»");
        if (routeManager.getActiveRoute() != null) {
            labels.add("❌ Сбросить текущий маршрут");
        }
        for (RouteManager.Place p : RouteManager.BUILTIN_PLACES) {
            labels.add("📍 " + p.name + " (офлайн)");
        }
        labels.add("📌 Совет: удерживайте палец на карте для точки Б");

        final boolean hasActive = routeManager.getActiveRoute() != null;
        final int builtinOffset = hasActive ? 4 : 3;

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🏁 Выбрать точку Б (Маршрут)")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        showAddressSearchDialog();
                    } else if (which == 1) {
                        if (home != null) {
                            startRouteTo(home.lat, home.lon, "Дом");
                        } else {
                            routeManager.saveFavorite("home", mapView.getCarLat(), mapView.getCarLon());
                            Toast.makeText(this, "Текущая точка сохранена как «Дом»", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 2) {
                        if (work != null) {
                            startRouteTo(work.lat, work.lon, "Работа");
                        } else {
                            routeManager.saveFavorite("work", mapView.getCarLat(), mapView.getCarLon());
                            Toast.makeText(this, "Текущая точка сохранена как «Работа»", Toast.LENGTH_SHORT).show();
                        }
                    } else if (hasActive && which == 3) {
                        routeManager.clearRoute();
                        mapView.setRoutePoints(null);
                        updateRouteBanner();
                        Toast.makeText(this, "Маршрут сброшен", Toast.LENGTH_SHORT).show();
                    } else {
                        int idx = which - builtinOffset;
                        if (idx >= 0 && idx < RouteManager.BUILTIN_PLACES.length) {
                            RouteManager.Place p = RouteManager.BUILTIN_PLACES[idx];
                            startRouteTo(p.lat, p.lon, p.name);
                        }
                    }
                })
                .setNeutralButton("Обновить Дом/Работу", (d, w) -> showSaveFavoritesDialog())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showSaveFavoritesDialog() {
        if (routeManager == null || mapView == null) return;
        String[] opts = {"🏠 Сохранить текущую позицию как «Дом»", "💼 Сохранить текущую позицию как «Работа»"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Сохранить избранное")
                .setItems(opts, (d, w) -> {
                    routeManager.saveFavorite(w == 0 ? "home" : "work", mapView.getCarLat(), mapView.getCarLon());
                    Toast.makeText(this, w == 0 ? "Точка «Дом» обновлена" : "Точка «Работа» обновлена", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showAddressSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("Например: ЦУМ Бишкек, Ошский рынок, Тамчы…");
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#94A3B8"));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🔎 Поиск точки Б")
                .setView(input)
                .setPositiveButton("Найти", (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) return;
                    Toast.makeText(this, "Поиск «" + q + "»…", Toast.LENGTH_SHORT).show();
                    routeManager.searchAddressAsync(q, (results, err) -> {
                        if (results == null || results.isEmpty()) {
                            Toast.makeText(this, err != null ? err : "Адрес не найден", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] names = new String[results.size()];
                        for (int i = 0; i < results.size(); i++) names[i] = results.get(i).name;
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("Выберите адрес")
                                .setItems(names, (d2, which) -> {
                                    RouteManager.Place p = results.get(which);
                                    startRouteTo(p.lat, p.lon, p.name);
                                })
                                .setNegativeButton("Отмена", null)
                                .show();
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showLongPressPointBDialog(double lat, double lon) {
        String coords = String.format(Locale.US, "%.4f, %.4f", lat, lon);
        String[] actions = {
                "🏁 Проложить маршрут сюда (на мини-карте)",
                "🧭 Поехать сюда в Яндекс Навигаторе",
                "📷 Добавить камеру 60 км/ч в эту точку"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Точка на карте (" + coords + ")")
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        startRouteTo(lat, lon, "Точка Б");
                    } else if (which == 1) {
                        startRouteTo(lat, lon, "Точка Б");
                        openPreferredNavigator();
                    } else {
                        if (speedCameraManager.addUserCamera(lat, lon, 60, 0, 0)) {
                            refreshCamerasOnMap();
                            Toast.makeText(this, "Камера 60 км/ч добавлена!", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startRouteTo(double toLat, double toLon, String destName) {
        if (routeManager == null || mapView == null) return;
        Toast.makeText(this, "Строим маршрут → " + destName + "…", Toast.LENGTH_SHORT).show();
        routeManager.buildRouteAsync(mapView.getCarLat(), mapView.getCarLon(), toLat, toLon, destName, (route, msg) -> {
            if (route != null) {
                mapView.setRoutePoints(route.points);
                updateRouteBanner();
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateRouteBanner() {
        if (tvRouteBanner == null || routeManager == null) return;
        RouteManager.ActiveRoute r = routeManager.getActiveRoute();
        if (r == null) {
            tvRouteBanner.setVisibility(View.GONE);
            if (mapView != null) mapView.setRoutePoints(null);
        } else {
            String tag = r.offlineFallback ? " (офлайн)" : "";
            tvRouteBanner.setText(String.format(Locale.US, "🏁 %s • %.1f км • ~%d мин%s  ✕",
                    r.destName, r.totalKm, r.etaMinutes, tag));
            tvRouteBanner.setVisibility(View.VISIBLE);
        }
    }

    private void showActiveRouteActionsDialog() {
        RouteManager.ActiveRoute r = routeManager != null ? routeManager.getActiveRoute() : null;
        if (r == null) return;
        String[] opts = {"🧭 Передать маршрут в Яндекс Навигатор", "❌ Завершить / сбросить маршрут"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Маршрут: " + r.destName)
                .setItems(opts, (d, w) -> {
                    if (w == 0) {
                        openPreferredNavigator();
                    } else {
                        routeManager.clearRoute();
                        mapView.setRoutePoints(null);
                        updateRouteBanner();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showCamerasAndMapsDialog() {
        if (speedCameraManager == null || offlineMapUpdater == null) return;
        int margin = speedCameraManager.getOverspeedMargin();
        String audioStr = speedCameraManager.isAudioEnabled()
                ? (speedCameraManager.isVoiceEnabled() ? "Голос + сигнал ВКЛ" : "Только сигнал ВКЛ")
                : "Звук ВЫКЛ";
        String[] items = {
                "🔄 Обновить базу камер с GitHub (сейчас: " + speedCameraManager.getCameraCount() + ")",
                "📷 + Добавить камеру здесь (текущий GPS)",
                "⚡ Порог предупреждения: +" + margin + " км/ч (60 → " + (60 + margin) + ")",
                "🔊 Оповещение: " + audioStr,
                "🔔 Проверить звук / голос предупреждения",
                "🗺 Докачать офлайн-карту (Чуй + Боом + Иссык-Куль)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📷 Камеры и Офлайн-карты")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Toast.makeText(this, "Загрузка базы камер с GitHub…", Toast.LENGTH_SHORT).show();
                        speedCameraManager.updateFromGitHubAsync((ok, msg, total) -> {
                            refreshCamerasOnMap();
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        });
                    } else if (which == 1) {
                        showAddCameraHereDialog();
                    } else if (which == 2) {
                        showMarginPickerDialog();
                    } else if (which == 3) {
                        if (!speedCameraManager.isAudioEnabled()) {
                            speedCameraManager.setAudioEnabled(true);
                            speedCameraManager.setVoiceEnabled(true);
                            Toast.makeText(this, "Включено: голос + сигнал", Toast.LENGTH_SHORT).show();
                        } else if (speedCameraManager.isVoiceEnabled()) {
                            speedCameraManager.setVoiceEnabled(false);
                            Toast.makeText(this, "Включено: только короткий сигнал", Toast.LENGTH_SHORT).show();
                        } else {
                            speedCameraManager.setAudioEnabled(false);
                            Toast.makeText(this, "Звук камер выключен (только экран)", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 4) {
                        speedCameraManager.testAlertNow();
                    } else if (which == 5) {
                        startOfflineMapSyncDialog();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showAddCameraHereDialog() {
        if (mapView == null || speedCameraManager == null) return;
        final double lat = mapView.getCarLat();
        final double lon = mapView.getCarLon();
        final int brg = Math.round(mapView.getCarBearing());
        String[] opts = {
                "Камера 40 км/ч (в обе стороны)",
                "Камера 60 км/ч (по моему ходу, " + brg + "°)",
                "Камера 60 км/ч (в обе стороны)",
                "Камера 90 км/ч (по моему ходу, " + brg + "°)",
                "Камера 90 км/ч (в обе стороны)"
        };
        int[][] cfg = {{40, 0}, {60, 1}, {60, 0}, {90, 1}, {90, 0}};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Добавить камеру в текущую точку")
                .setItems(opts, (d, w) -> {
                    if (speedCameraManager.addUserCamera(lat, lon, cfg[w][0], cfg[w][1], brg)) {
                        refreshCamerasOnMap();
                        Toast.makeText(this, "Камера " + cfg[w][0] + " км/ч сохранена!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showMarginPickerDialog() {
        final int[] margins = {0, 5, 8, 9, 10};
        String[] labels = {
                "+0 км/ч (строго по знаку: 60 → 60)",
                "+5 км/ч (60 → 65 км/ч)",
                "+8 км/ч (60 → 68 км/ч)",
                "+9 км/ч (рекомендуется для КР: 60 → 69, штраф с 71)",
                "+10 км/ч (60 → 70 км/ч)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Порог звукового предупреждения")
                .setItems(labels, (d, w) -> {
                    speedCameraManager.setOverspeedMargin(margins[w]);
                    Toast.makeText(this, "Порог установлен: +" + margins[w] + " км/ч", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startOfflineMapSyncDialog() {
        if (offlineMapUpdater == null) return;
        if (offlineMapUpdater.isRunning()) {
            Toast.makeText(this, "Загрузка офлайн-карт уже идёт…", Toast.LENGTH_SHORT).show();
            return;
        }
        int cachedNow = offlineMapUpdater.countCachedTiles();
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🗺 Офлайн-карта: Чуй + Боом + Иссык-Куль")
                .setMessage("Сейчас в памяти: " + cachedNow + " тайлов.\n\nДокачать недостающие участки (включая детальное Боомское ущелье z=14) по Wi-Fi/4G?")
                .setPositiveButton("Докачать", (d, w) -> {
                    Toast.makeText(this, "Проверяю и докачиваю тайлы Чуй + Боом + Иссык-Куль…", Toast.LENGTH_SHORT).show();
                    offlineMapUpdater.startSyncChuyBoomIssykKul(new OfflineMapUpdater.ProgressListener() {
                        @Override
                        public void onProgress(int done, int total, int downloadedNew) {
                            if (tvCameraPill != null) {
                                tvCameraPill.setText("🗺 " + done + "/" + total);
                            }
                        }

                        @Override
                        public void onFinished(boolean cancelled, int downloadedNew, int totalCached) {
                            refreshCamerasOnMap();
                            String msg = downloadedNew == 0
                                    ? ("Офлайн-карта уже полная (" + totalCached + " тайлов)!")
                                    : ("Докачано +" + downloadedNew + " тайлов! Всего: " + totalCached);
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refreshCamerasOnMap() {
        if (speedCameraManager == null) return;
        if (mapView != null) mapView.setCameras(speedCameraManager.getCameras());
        if (tvCameraPill != null) {
            tvCameraPill.setText("📷 Камеры (" + speedCameraManager.getCameraCount() + ")");
        }
    }

    private void updateCameraBanner(SpeedCameraManager.CameraAlert alert) {
        if (tvCameraBanner == null) return;
        if (alert == null) {
            tvCameraBanner.setVisibility(View.GONE);
            return;
        }
        if (alert.overspeed) {
            tvCameraBanner.setText(String.format(Locale.US, "⚠ СНИЗЬТЕ СКОРОСТЬ! Камера %d • %d м",
                    alert.camera.speedLimit, alert.distanceMeters));
            tvCameraBanner.setTextColor(Color.WHITE);
            tvCameraBanner.setBackground(makeCardDrawable("#EBDC2626", "#FCA5A5", 14));
        } else {
            tvCameraBanner.setText(String.format(Locale.US, "📷 Камера %d • %d м",
                    alert.camera.speedLimit, alert.distanceMeters));
            tvCameraBanner.setTextColor(Color.parseColor("#FDE68A"));
            tvCameraBanner.setBackground(makeCardDrawable("#E01E293B", "#F59E0B", 14));
        }
        tvCameraBanner.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (obdManager != null) obdManager.stop();
        if (tripLogger != null) tripLogger.close();
        if (speedCameraManager != null) speedCameraManager.shutdown();
        if (offlineMapUpdater != null) offlineMapUpdater.cancel();
        if (mapView != null) mapView.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}

    private TextView makeOverlayPill(String text, String bgHex, String borderHex, String textHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        tv.setBackground(makeCardDrawable(bgHex, borderHex, 12));
        return tv;
    }

    private TextView makeMapCtrlBtn(String glyph) {
        TextView tv = new TextView(this);
        tv.setText(glyph);
        tv.setTextColor(Color.parseColor("#F1F5F9"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(makeCardDrawable("#DC0D131D", "#334155", 12));
        return tv;
    }

    private TextView makeStatusPill(String text, String bgHex, String textHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setBackground(makeCardDrawable(bgHex, "#2A364C", 12));
        return tv;
    }

    private GradientDrawable makeCardDrawable(String fillHex, String strokeHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), Color.parseColor(strokeHex));
        return gd;
    }

    private int dp(int val) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics()));
    }
}
