param(
    [Parameter(Mandatory=$true)]
    [string]$DeviceIp,
    [int]$Port = 5555,
    [switch]$PushOfflineMaps
)
$ErrorActionPreference = "Stop"

# Locate adb from PATH or Android SDK
$adbCmd = Get-Command adb.exe -ErrorAction SilentlyContinue
$adb = if ($adbCmd) {
    $adbCmd.Source
} elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    "$env:ANDROID_HOME\platform-tools\adb.exe"
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") {
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
} else {
    throw "adb.exe not found! Add Android platform-tools to PATH or set ANDROID_HOME."
}

$apk = Join-Path $PSScriptRoot "KC97-Cockpit-Launcher.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk. Run .\build.ps1 first." }
$target = "${DeviceIp}:${Port}"

& $adb connect $target
& $adb -s $target install -r $apk
& $adb -s $target shell "pm grant com.kc97.carlauncher android.permission.ACCESS_FINE_LOCATION"
& $adb -s $target shell "pm grant com.kc97.carlauncher android.permission.ACCESS_COARSE_LOCATION"
& $adb -s $target shell "pm grant com.kc97.carlauncher android.permission.READ_EXTERNAL_STORAGE"
& $adb -s $target shell "pm grant com.kc97.carlauncher android.permission.WRITE_EXTERNAL_STORAGE"
& $adb -s $target shell "su 0 cmd package compile -m speed-profile -f com.kc97.carlauncher"
& $adb -s $target shell "su 0 cmd package set-home-activity com.kc97.carlauncher/.MainActivity"
& $adb -s $target shell "su 0 pm disable com.kc97.carlauncher/.CarMediaListenerService && su 0 pm enable com.kc97.carlauncher/.CarMediaListenerService"

if ($PushOfflineMaps) {
    $tar = Join-Path $PSScriptRoot "chuy_issyk_kul_tiles.tar"
    if (Test-Path $tar) {
        Write-Host "Pushing offline map pack..."
        & $adb -s $target push $tar /sdcard/offline_tiles.tar
        & $adb -s $target shell "mkdir -p /sdcard/KC97MapCache && tar -xf /sdcard/offline_tiles.tar -C /sdcard/KC97MapCache && rm -f /sdcard/offline_tiles.tar"
    } else {
        Write-Warning "Offline map archive not found. Run 'python fetch_offline_maps.py' first."
    }
}

& $adb -s $target shell "am start -n com.kc97.carlauncher/.MainActivity"
Write-Host "Installed & launched KC97 Cockpit v1.9 on $target!"
