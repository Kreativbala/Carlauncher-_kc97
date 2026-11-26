# Changelog / История изменений

## [2.0] — 2026-11-26 (Current Stable / Текущая стабильная)
- **EN:** Added **Point B Routing** right on the native map (`🏁 Маршрут`: address search, Home/Work favorites, offline Chuy & Issyk-Kul cities, map long-press, OSRM polyline + offline Boom Gorge highway corridor fallback + persistent route across restarts + 1-tap Yandex Navigator handoff).
- **EN:** Added **Safe City Speed Cameras** (`📷 Камеры`: 135 bundled KG cameras including M-41 Belovodskoe→Bishkek 60 km/h, direction-aware 420m lookahead, configurable `+9 km/h` warning margin, Russian TTS + chime with AudioFocus music ducking, 1-tap GitHub DB sync, 1-tap `+ Camera here`, custom `speedcam_custom.txt` support).
- **EN:** Added **On-Device Offline Map Sync** (`OfflineMapUpdater`) to top up Chuy + high-detail Boom Gorge (`z=14`) + Issyk-Kul tiles directly over Wi-Fi without a PC.
- **EN:** Added **Silent Background Trip Telemetry Logger** (`/sdcard/KC97Logs/*.csv`, 30-day retention) with 1-tap CSV export & Android Share in the OBD menu.
- **RU:** Добавлен **выбор точки «Б» и прокладка маршрута** прямо на главной карте (`🏁 Маршрут`: поиск адреса, Дом/Работа, офлайн-города Чуя и Иссык-Куля, долгое нажатие на карту, сохранение маршрута и офлайн-коридор в Боомском ущелье + передача точки в Я.Навигатор).
- **RU:** Интегрированы **камеры «Безопасного города»** (`📷 Камеры`: 135 камер по КР + новая камера `42.84782, 74.16440` 60 км/ч в сторону Бишкека, учёт направления за 420 м, порог `+9 км/ч` → предупреждает с 69 км/ч, русский голос + сигнал с приглушением музыки, обновление с GitHub в 1 тап, кнопка «+ Камера здесь»).
- **RU:** Добавлена **автономная докачка офлайн-карт Чуй + Боом + Иссык-Куль** прямо на магнитоле по Wi-Fi без ПК.
- **RU:** Добавлен **фоновый «чёрный ящик» логов поездки** (`/sdcard/KC97Logs/*.csv`) с экспортом по кнопке в меню OBD.

## [1.9] — 2026-11-26
- **EN:** Enlarged coolant & fuel consumption typography (+2sp: coolant `17sp`, instant `18sp`, average `20sp`, trip distance/liters `15sp`) for effortless glance readability while driving.
- **EN:** Replaced the unused Radio dock button with direct **Dashcam / «Стражи» (`com.fvision.camera`)** launch (long-press still opens Radio `com.kte.radio`).
- **RU:** Увеличен шрифт блока ОЖ и расхода топлива на +2 пункта (`ОЖ` 17sp, `Тек` 18sp, `Ср` 20sp, пробег/литры 15sp) для удобного чтения на ходу.
- **RU:** Кнопка «Радио» в доке заменена на быстрый запуск регистратора **«Стражи» (`com.fvision.camera`)**; долгое нажатие открывает Радио.

## [1.8] — 2026-11-26
- **EN:** Eliminated parked GPS speed jitter (3–5 km/h) and `P Паркинг` ↔ `D В движении` flickering via ECU wheel speed (`010D`) + RPM (`010C`) fusion and stationary GPS hysteresis (6–10 km/h when ignition is OFF).
- **RU:** Устранён дрейф спидометра на стоянке (3–5 км/ч) и переключения `P Паркинг` ↔ `D В движении` за счёт слияния с датчиком скорости ЭБУ (`010D`) и оборотами (`010C`), а также гистерезиса GPS при выключенном зажигании.

## [1.7] — 2026-11-26
- **EN:** Fixed yellow OBD stall after ignition OFF→ON cycle with clean protocol reset (`ATPC` → `ATSP0` → `0100`), full 12s wait for Honda ISO 9141-2 `BUS INIT`, and proper Android `LocalSocket` timeout handling.
- **EN:** Added dedicated 4-color Coolant (`ОЖ`) badge: Blue `<50°C`, Green `50–98°C`, Yellow `99–106°C`, Red `≥107°C`, plus enlarged fuel text and manual OBD reconnect option.
- **RU:** Исправлено зависание OBD в жёлтом статусе после перезапуска зажигания (чистый сброс `ATPC` → `ATSP0` → `0100`, полное ожидание `BUS INIT` Honda ISO 9141-2).
- **RU:** Выделена цветная плашка ОЖ (синий `<50°C`, зелёный `50–98°C`, жёлтый `99–106°C`, красный `≥107°C`), укрупнён шрифт расхода, добавлена кнопка переподключения OBD.

## [1.6] — 2026-11-26
- **EN:** Calibrated Speed-Density fuel model for **Honda Stream RN3 (K20A 2.0L i-VTEC, `VE = 0.78`)**; removed duplicate clock; added color-coded `● OBD` badge next to CPU temp.
- **RU:** Калибровка модели расхода под **Honda Stream RN3 (K20A 2.0 л, `VE = 0.78`)**; убраны дублирующие часы; добавлен цветной значок `● OBD` рядом с `CPU °C`.

## [1.5] — 2026-11-26
- **EN:** Built-in non-root blue ELM327 v1.5 bridge via `/dev/socket/goc_spp` (SD-968 chip), local TCP bridge (`127.0.0.1:35000`) for Car Scanner, and persistent Speed-Density fuel computer (`MAP + RPM + IAT + Speed`).
- **RU:** Встроенный мост ELM327 v1.5 без root через `/dev/socket/goc_spp` (SD-968), TCP-мост `127.0.0.1:35000` для Car Scanner и накопительный бортовой компьютер расхода топлива.

## [1.3] — 2026-11-26
- **EN:** Replaced WebView with pure native Canvas map renderer (`NativeMapView`, ~30 MB total RAM), persistent offline tile cache (`/sdcard/KC97MapCache`), regional preloader (`fetch_offline_maps.py`), and headlight auto-night mode.
- **RU:** Полный отказ от WebView — переход на нативный Canvas-движок карт (~30 МБ ОЗУ), офлайн-кеш `/sdcard/KC97MapCache`, загрузчик регионов и авто-ночной режим по фарам.

## [1.0 – 1.2] — 2026-11-26
- **EN:** Initial dark automotive launcher for KTE/KC97 Android 8.0 head units, live Yandex Traffic, 3-tab media player (`Yandex Music` / `Spotify` / `BT Music`), vector media controls, and hardware GPIO handbrake reading.
- **RU:** Первые версии тёмного автолаунчера для KTE/KC97 (Android 8.0), живые пробки Яндекс.Карт, 3-вкладочный плеер, векторные кнопки и чтение ручника из GPIO.
