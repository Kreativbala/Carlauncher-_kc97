import math
import os
import tarfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

E = 0.0818191908426
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "offline_tiles")
TAR_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chuy_issyk_kul_tiles.tar")

def lat_lon_to_tile(lat, lon, z):
    n = 1 << z
    rad_lon = math.radians(lon)
    rad_lat = math.radians(max(-85.0, min(85.0, lat)))
    esin = E * math.sin(rad_lat)
    merc_y = math.log(math.tan(math.pi / 4.0 + rad_lat / 2.0) * (((1.0 - esin) / (1.0 + esin)) ** (E / 2.0)))
    x = int((rad_lon + math.pi) / (2.0 * math.pi) * n)
    y = int((math.pi - merc_y) / (2.0 * math.pi) * n)
    return x, y

def collect_bbox(tiles_set, lat_min, lat_max, lon_min, lon_max, zooms):
    for z in zooms:
        x1, y1 = lat_lon_to_tile(lat_max, lon_min, z)
        x2, y2 = lat_lon_to_tile(lat_min, lon_max, z)
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                tiles_set.add((z, x, y))

tiles = set()
# 1. Full Chuy Oblast + Issyk-Kul Oblast overview (z=10, 11, 12)
collect_bbox(tiles, 42.05, 43.15, 73.25, 78.65, [10, 11, 12])

# 2. Detailed z=13: Chuy Valley + Boom Gorge + Balykchy + North & South Issyk-Kul ring + Karakol
collect_bbox(tiles, 42.65, 43.05, 73.70, 76.25, [13])  # Kara-Balta -> Belovodskoe -> Bishkek -> Tokmok -> Kemin -> Boom -> Balykchy
collect_bbox(tiles, 42.38, 42.85, 76.10, 78.55, [13])  # North shore Issyk-Kul (Balykchy -> Tamchy -> Cholpon-Ata -> Bosteri -> Karakol)
collect_bbox(tiles, 42.08, 42.45, 76.10, 78.50, [13])  # South shore Issyk-Kul (Balykchy -> Bokonbaevo -> Barskoon -> Kyzyl-Suu -> Karakol)

# 3. Street-level z=14: Key cities & Belovodskoe!
collect_bbox(tiles, 42.77, 42.90, 73.82, 74.40, [14])  # Kara-Balta -> Belovodskoe -> Sokuluk
collect_bbox(tiles, 42.78, 42.96, 74.45, 74.88, [14])  # Bishkek + Kant
collect_bbox(tiles, 42.80, 42.88, 75.24, 75.36, [14])  # Tokmok
collect_bbox(tiles, 42.43, 42.50, 76.14, 76.24, [14])  # Balykchy
collect_bbox(tiles, 42.61, 42.70, 77.00, 77.25, [14])  # Cholpon-Ata + Bosteri
collect_bbox(tiles, 42.45, 42.53, 78.34, 78.44, [14])  # Karakol

print(f"Total tiles queued for Chuy + Issyk-Kul: {len(tiles)}")

def download_one(item):
    z, x, y = item
    out_path = os.path.join(OUT_DIR, str(z), str(x), f"{y}.png")
    if os.path.exists(out_path) and os.path.getsize(out_path) > 100:
        return True
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    url = f"https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru_RU"
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36",
        "Referer": "https://yandex.ru/maps/"
    })
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            data = r.read()
            if len(data) > 100:
                with open(out_path, "wb") as f:
                    f.write(data)
                return True
    except Exception:
        return False
    return False

ok = 0
fail = 0
with ThreadPoolExecutor(max_workers=16) as ex:
    futs = [ex.submit(download_one, t) for t in tiles]
    for fut in as_completed(futs):
        if fut.result():
            ok += 1
        else:
            fail += 1

print(f"Downloaded: {ok}, Failed: {fail}")

with tarfile.open(TAR_PATH, "w") as tf:
    for z, x, y in sorted(tiles):
        p = os.path.join(OUT_DIR, str(z), str(x), f"{y}.png")
        if os.path.exists(p):
            tf.add(p, arcname=f"{z}/{x}/{y}.png")

size_mb = os.path.getsize(TAR_PATH) / (1024 * 1024)
print(f"PACK READY: {TAR_PATH} ({size_mb:.2f} MB)")
