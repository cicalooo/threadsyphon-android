$ErrorActionPreference = 'Stop'
$proj = 'C:\Users\cyka\Documents\Code\threadsyphon-android'
$apks = 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks'
$jdk = 'C:\Users\cyka\.jdks\jbr-21.0.11'
Set-Location $proj
$lp = Join-Path $proj 'local.properties'
if (Test-Path $lp) { Copy-Item $lp "$env:TEMP\ts-lp.bak" -Force }
git fetch origin
git checkout main
git pull --ff-only origin main
if (Test-Path "$env:TEMP\ts-lp.bak") { Copy-Item "$env:TEMP\ts-lp.bak" $lp -Force }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
.\gradlew.bat :app:assembleRelease --no-daemon
New-Item -ItemType Directory -Force -Path $apks | Out-Null
$built = Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "Missing $built — is keystore.properties present?" }
Copy-Item $built (Join-Path $apks 'threadsyphon-android-1.0.2-release.apk') -Force
Write-Host "APK: $apks\threadsyphon-android-1.0.2-release.apk"
gh release upload v1.0.2 (Join-Path $apks 'threadsyphon-android-1.0.2-release.apk') --clobber --repo cicalooo/threadsyphon-android
