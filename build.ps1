$ErrorActionPreference = "Stop"

$proj = $PSScriptRoot
$build = Join-Path $proj "build"
New-Item -ItemType Directory -Force -Path "$build\res", "$build\gen", "$build\classes", "$build\dex" | Out-Null

# Locate JDK (11/17/21+) via JAVA_HOME, PATH, or common install directories
$jdkCandidates = @()
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $jdkCandidates += $env:JAVA_HOME }
$javacInPath = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($javacInPath) { $jdkCandidates += (Split-Path (Split-Path $javacInPath.Source)) }
$jdkCandidates += @(
    Get-ChildItem "C:\Program Files\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    Get-ChildItem "C:\Program Files\Java\jdk*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
)
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path "$_\bin\javac.exe") } | Select-Object -First 1
if (-not $jdk) { throw "JDK not found! Set JAVA_HOME or install OpenJDK 17+." }

$javac = Join-Path $jdk "bin\javac.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path

# Locate Android SDK (platform 34 + build-tools 34.0.0)
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:USERPROFILE\AppData\Local\Android\Sdk"
) | Where-Object { $_ -and (Test-Path "$_\platforms\android-34\android.jar") }
$sdk = $sdkCandidates | Select-Object -First 1
if (-not $sdk) {
    $foundJar = Get-ChildItem "$env:USERPROFILE\AppData" -Filter "android.jar" -Recurse -Depth 5 -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "android-34" } | Select-Object -First 1
    if ($foundJar) { $sdk = Split-Path (Split-Path $foundJar.DirectoryName) }
}
if (-not $sdk) { throw "Android SDK (platforms/android-34 + build-tools/34.0.0) not found! Set ANDROID_HOME." }

$androidJar = "$sdk\platforms\android-34\android.jar"
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$aapt2 = "$bt\aapt2.exe"
$d8 = "$bt\d8.bat"
$zipalign = "$bt\zipalign.exe"
$apksigner = "$bt\apksigner.bat"

Write-Host "Using JDK: $jdk"
Write-Host "Using SDK: $sdk (build-tools: $(Split-Path $bt -Leaf))"

# 1. Compile resources
& $aapt2 compile --dir "$proj\res" -o "$build\res\res.zip"

# 2. Link resources + manifest (+ optional assets) -> unsigned APK + R.java
$linkArgs = @(
    "link",
    "-o", "$build\app-unaligned.apk",
    "-I", $androidJar,
    "--manifest", "$proj\AndroidManifest.xml",
    "--java", "$build\gen"
)
if (Test-Path "$proj\assets") {
    $linkArgs += @("-A", "$proj\assets")
}
$linkArgs += "$build\res\res.zip"
& $aapt2 @linkArgs

# 3. Compile Java sources (Java 8 bytecode for Android 8.0 API 26+)
$sources = Get-ChildItem "$proj\src", "$build\gen" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
& $javac --release 8 -encoding UTF-8 -classpath $androidJar -d "$build\classes" $sources

# 4. Dex classes with d8 (min-api 26)
$classFiles = Get-ChildItem "$build\classes" -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName
& $d8 --min-api 26 --lib $androidJar --output "$build\dex" $classFiles

# 5. Add classes.dex to APK
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\app-unaligned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

# 6. Zipalign
$alignedApk = "$build\app-aligned.apk"
& $zipalign -f 4 "$build\app-unaligned.apk" $alignedApk

# 7. Generate local debug keystore if missing & sign APK
$ks = Join-Path $proj "debug.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -v -keystore $ks -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=KC97Cockpit,O=Custom,C=KG"
}
$finalApk = Join-Path $proj "KC97-Cockpit-Launcher.apk"
& $apksigner sign --ks $ks --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out $finalApk $alignedApk
Remove-Item -Force "$finalApk.idsig" -ErrorAction SilentlyContinue
& $apksigner verify --print-certs $finalApk

Write-Host "BUILD SUCCESS: $finalApk ($((Get-Item $finalApk).Length) bytes)"
