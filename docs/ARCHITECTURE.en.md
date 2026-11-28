**🇬🇧 English** | **[🇷🇺 Русский](ARCHITECTURE.md)**

# Technical Architecture — KC97 Cockpit Launcher (v1.9)

## 1. Target Platform & Design Philosophy

Designed specifically for aftermarket Chinese Android head units (tested on **KTE KC97 / X20G_CJ01_KTE**, MediaTek MT6797 Helio X20, 4 GB RAM, real **Android 8.0 Oreo API 26**, `2000×1200` display).

Core design principle — **zero external dependencies and zero WebViews**:
- Entire UI is constructed programmatically using native Android `View` and `Canvas` APIs.
- Avoiding AndroidX, Gradle bloat, and Chromium WebViews keeps the signed APK at **~58 KB** and active RAM footprint around **28–36 MB** (vs. 300–1000 MB for stock/third-party launchers).

---

## 2. Source Code Modules

| Class | Responsibility |
|---|---|
| `MainActivity` | Responsive dashboard layout (64%/36% height split: 33% telemetry + 67% map; 55% dock + 45% media), system & GPIO sensor polling, GPS+OBD speed fusion, auto-night brightness control, OBD settings dialog. |
| `NativeMapView` | Multi-threaded raster tile renderer for Yandex Maps (EPSG:3395 elliptical Mercator) + live traffic overlay (`jgo`). Two-tier cache: in-memory `LruCache` + persistent disk cache at `/sdcard/KC97MapCache/{z}/{x}/{y}.png`. Automatic parent-tile fallback when offline; night dimming via `ColorMatrix`. |
| `ObdBridgeManager` | Background ELM327 v1.5 poller over Unix domain socket `/dev/socket/goc_spp` + local TCP bridge on `127.0.0.1:35000`. Implements Speed-Density fuel consumption and persistent `SharedPreferences` accumulation. |
| `CarStatusView` | Vector top-down car graphic with dynamic headlight beams (`Headlamp`), brake/taillight glow when stopped or on handbrake, and white reverse lights (`backcar_satus`). |
| `CarMediaListenerService` | `NotificationListenerService` + `MediaSessionManager` tracking `ru.yandex.music` and `com.spotify.music` metadata, album art, and media transport actions. |
| `MediaIconButton` | Custom Canvas-rendered `PREV`, `PLAY`, `PAUSE`, `NEXT` controls (avoids Android 8 color emoji rendering glitches on Unicode symbols like `⏸`). |
| `AppDrawerDialog` | Lightweight 6-column dark app drawer loaded asynchronously. |

---

## 3. ELM327 OBD-II over `/dev/socket/goc_spp` (SD-968)

On KTE head units using the external **SD-968** UART Bluetooth module (`/dev/ttyMT3`, daemon `/ksys/bin/sdsdk968`), Android's standard `com.android.bluetooth` service is stripped, but SPP access is exposed through a world-accessible Unix socket:
- Socket path: `/dev/socket/goc_spp` (`srwxrwxrwx`, usable without root).
- **Critical `sdsdk968` binary detail (`sub_97c0` @ `0x0097e8`):** when `spp_handle == 0` after reboot/sleep, the daemon checks `cmp r1, #0xc` (`len == 12`)! You MUST write **strictly 12 ASCII bytes of the MAC address WITHOUT `\r\n`** (e.g., `"001DA50821EB"`) and wait `~4.5 s` for `bt_spp_connect` (`0x18b9c`) to open the RFCOMM channel. Appending `\r\n` (14 bytes) skips `bt_spp_connect`, while sending `AT#SP` to `/dev/goc_serial` triggers Apple iAP and forces `KteBTService` to kill/restart `sdsdk968`.
- Auto-MAC discovery: `ObdBridgeManager` automatically parses `/data/goc/bt_conf.ini` (`[PAIRSTLIST]`) to discover the paired `OBDII` adapter address.
- Once connected, the socket behaves as a raw transparent serial link to ELM327.

### Reliable Honda ECU Reconnect Cycle (ISO 9141-2)
1. Adapter setup: `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATSP0`.
2. Battery voltage query `ATRV` (works even in `ACC` / ignition OFF).
3. ECU handshake (`handshakeEcu`):
   - `ATPC` (close stale protocol session) → `ATSP0` → `0100` with a full `12 s` deadline.
   - Crucial Android 8 `LocalSocket` detail: read timeouts throw plain `IOException("Try again")` (`EAGAIN`) instead of `SocketTimeoutException`; `sendCmd` catches this and continues waiting up to the full deadline so Honda's 5-baud `BUS INIT: ...` (~4.5–5 s) never aborts prematurely.
4. On ECU loss (`NO DATA` / `UNABLE` / `ERROR` when engine stops), stats are persisted immediately, the badge turns yellow (`connecting = true`), and clean protocol handshakes resume automatically on next ignition ON.

### Transparent TCP Bridge (`127.0.0.1:35000`)
- Thread `OBD-TcpBridge` listens on `127.0.0.1:35000`.
- When an external scanner (such as Car Scanner ELM OBD2) connects, the launcher saves fuel stats, releases `goc_spp`, pipes bytes bidirectionally (`TCP ↔ goc_spp`), and automatically resumes telemetry polling once the external client disconnects.

---

## 4. Speed-Density Fuel Consumption Model

For ECUs without PID `0110` (MAF) or `015E` (Fuel Rate) — verified on **Honda Stream RN3 (K20A, `0100 = 4100BE3EA801`)** — fuel rate is computed via the ideal-gas Speed-Density equation using `010B` (MAP, kPa), `010C` (RPM), `010F` (IAT, °C), and `010D` (Vehicle Speed, km/h):

$$\text{MAF}_{\text{est}}\text{ (g/s)} = \frac{\text{RPM} \cdot \text{MAP}_{\text{kPa}}}{T_{\text{IAT (K)}}} \cdot V_{\text{eng (L)}} \cdot \text{VE} \cdot 0.02904$$

$$\text{Fuel Rate (L/h)} = \text{MAF}_{\text{est}} \cdot \frac{3600}{14.7 \cdot 745} \approx \text{MAF}_{\text{est}} \cdot 0.3287$$

- Constant `0.02904` derives directly from the 4-stroke ideal gas law: $\frac{1000 \cdot 28.97}{8.3145 \cdot 120000} \approx 0.029036$.
- Default volumetric efficiency: `DEFAULT_VE = 0.78` (calibrated for naturally aspirated Honda K20A 2.0L).
- **DFCO (Deceleration Fuel Cut-Off):** when `RPM > 1500`, `MAP ≤ 20 kPa`, and `Speed > 20 km/h`, instant fuel rate clamps to `0.0 L/h`.
- **Persistence:** `total_km` and `total_liters` are flushed to `SharedPreferences` (`kc97_obd_stats`) every 15 seconds, on ECU disconnect, and in `onPause()` / `onDestroy()`.

---

## 5. Coolant Thresholds & Anti-Drift Speed Filter

### Coolant Temperature (`0105`, °C = byte - 40)
- `< 50°C` — 🔵 Blue (`#38BDF8` / `#0D2842`)
- `50 .. 98°C` — 🟢 Green (`#4ADE80` / `#0F2920`)
- `99 .. 106°C` — 🟡 Yellow (`#FDE047` / `#3B2A0A`)
- `≥ 107°C` — 🔴 Red (`#FCA5A5` / `#4C0D19`)

### Anti-Drift Speedometer Fusion
- **When ECU is live (`obdEcuReady == true`):**
  - If `RPM < 150` and `ecuSpeed == 0` → strictly `0 km/h`.
  - If `ecuSpeed == 0` → GPS jitter `< 10 km/h` is suppressed (`0 km/h`).
  - If `ecuSpeed > 0` → uses accurate GPS speed (`accuracy < 30 m`), falling back to `ecuSpeed`.
- **When ignition is OFF / no ECU:**
  - Leaving `0 km/h` requires either a single clean fix `≥ 10 km/h` (`accuracy < 25 m`) or **2 consecutive fixes** `≥ 6 km/h` (`≥ 9 km/h` if `accuracy > 25 m`).
  - Returns to `0 km/h` when speed drops `< 3 km/h`.

---

## 6. KTE / KC97 Hardware Interfaces

| Signal | System Source | Values |
|---|---|---|
| Headlights / Illumination | `Settings.System` → `"Headlamp"` | `0` off, `1` on (triggers auto-night dimming: window brightness `0.28` + darkened map) |
| Physical Handbrake Wire | `/sys/devices/virtual/kte/kte_hand_brake/hand_brake_status` | `0` released, `1` engaged |
| Reverse Gear | `Settings.System` → `"backcar_satus"` | `0` off, `1` active |
| CPU Thermal Sensor | `/sys/class/thermal/thermal_zone1/temp` | millidegrees °C |
| Bluetooth Audio (`KteBluetooth`) | Broadcasts `com.kte.bluetooth_*` | `playPause`, `next`, `previous`, `playName`, `playStatus`, `btState`, `getName` |
