# Parent: signed v1.0.3 APK on Windows

Executor Shell had **no machineId parameter** this turn (args stripped; all commands ran on the Linux box).
Signed assembleRelease + APK upload must run with machineId `70bb9179-4335-4fcc-bad9-a454c964a3d7`.

- Commit: `2b990b9380eb21563b8e0bfd519d0adf9f0935b6`
- Release (notes only): https://github.com/cicalooo/threadsyphon-android/releases/tag/v1.0.3
- Do not publish the unsigned box APK over signed 1.0.2 installs.

```powershell
cd "C:\Users\cyka\Documents\Code\threadsyphon-android"
$lp = "local.properties"; if (Test-Path $lp) { Copy-Item $lp "$env:TEMP\ts-lp.bak" -Force }
git fetch origin; git checkout main; git pull --ff-only origin main
if (Test-Path "$env:TEMP\ts-lp.bak") { Copy-Item "$env:TEMP\ts-lp.bak" $lp -Force }
$env:JAVA_HOME = 'C:\Users\cyka\.jdks\jbr-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleRelease --no-daemon
New-Item -ItemType Directory -Force -Path 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks' | Out-Null
Copy-Item 'app\build\outputs\apk\release\app-release.apk' 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks\threadsyphon-android-1.0.3-release.apk' -Force
gh release upload v1.0.3 'C:\Users\cyka\Documents\Code\threadsyphon-android-apks\threadsyphon-android-1.0.3-release.apk' --clobber --repo cicalooo/threadsyphon-android
```
