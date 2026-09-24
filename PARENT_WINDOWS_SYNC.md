# Parent: signed v1.0.10 APK on Windows

Executor Shell had **no machineId parameter** this turn (all commands ran on the Linux box).
Signed assembleRelease + APK upload must run with machineId `70bb9179-4335-4fcc-bad9-a454c964a3d7`.

- Feature commit: (filled after push)
- Release (notes only until signed APK uploaded): https://github.com/cicalooo/threadsyphon-android/releases/tag/v1.0.10
- Do not publish the unsigned box APK over signed installs.
- Box verified compile: `app-release-unsigned.apk` (no keystore on box).

```powershell
cd "C:\Users\cyka\Documents\Code\threadsyphon-android"
$lp = "local.properties"; if (Test-Path $lp) { Copy-Item $lp "$env:TEMP\ts-lp.bak" -Force }
git fetch origin; git checkout main; git pull --ff-only origin main
if (Test-Path "$env:TEMP\ts-lp.bak") { Copy-Item "$env:TEMP\ts-lp.bak" $lp -Force }
$env:JAVA_HOME = 'C:\Users\cyka\.jdks\jbr-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleRelease --no-daemon
New-Item -ItemType Directory -Force -Path 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks' | Out-Null
Copy-Item 'app\build\outputs\apk\release\app-release.apk' 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks\threadsyphon-android-1.0.10-release.apk' -Force
gh release upload v1.0.10 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks\threadsyphon-android-1.0.10-release.apk' --clobber --repo cicalooo/threadsyphon-android
```

Or: `.\scripts\build-release-1.0.10.ps1` then the `gh release upload` line above.
