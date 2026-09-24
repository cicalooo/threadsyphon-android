# ThreadSyphon Android

Native **Kotlin + Jetpack Compose (Material 3)** port (v1.0.10) of [ThreadSyphon](https://github.com/cicalooo/threadsyphon) — a 4chan thread watcher / media saver.

No accounts. No telemetry. HTTP is only used to check threads (`a.4cdn.org`), search catalogs, and download media you asked for (`i.4cdn.org`).

**Repo:** https://github.com/cicalooo/threadsyphon-android

---

## Requirements

- Android Studio Ladybug / Koala+ (or any IDE with AGP 8.7+)
- JDK 17
- Android SDK with **API 35** platform (compile/target); **minSdk 26**
- A device or emulator (ARM64 / x86_64)

You do **not** need this box’s environment — open the project on a machine with Android Studio.

---

## Clone → open → run

```bash
git clone https://github.com/cicalooo/threadsyphon-android.git
cd threadsyphon-android
```

1. Open the folder in **Android Studio** (File → Open).
2. Let Gradle sync finish (wrapper uses Gradle **8.9**).
3. Select a device / emulator.
4. Run the **app** configuration.

Command line (optional, with SDK installed):

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use applicationId `com.threadsyphon.android.debug`.

---

## Quick start

1. Launch **threadsyphon**.
2. Allow **notifications** when prompted (Android 13+).
3. Paste a URL such as `https://boards.4chan.org/g/thread/12345678` → **Add**.
4. The foreground service starts; a persistent notification shows **Pause all** / **Open**.
5. Open a thread for status, check-now, pause/start, open in browser.

Default save root (visible shared storage):

`/storage/emulated/0/threadsyphon/<board>/<thread>/`

(= Internal storage/threadsyphon). Requires all-files access on Android 11+ (Settings → Grant). Optional custom folder via SAF picker. App-private storage is available but not the default.

---

## Features

| Area | Behavior |
|---|---|
| **Watch** | Add URL, list + status, detail, multi-select pause/start/check/remove, start/pause all |
| **Downloads** | Media filters (all / images / video), filename modes (original / server / numbered), MD5 verify when API provides it, API/CDN courtesy gaps |
| **Find** | Catalog search with Windows-style query (`title:`, `body:`, `/tag/`, `OR`, `NOT` / `-`, `min_images:`, …) |
| **Rules** | Watchdogs that rescan catalogs while the **foreground service** runs and auto-add matches |
| **Share To** | `SEND` `text/plain` + `VIEW` `https` 4chan thread URLs → add & watch |
| **Wi‑Fi** | **Wi‑Fi only by default**; Settings toggle **Allow mobile data** |
| **Battery** | Settings explains + deep-links / requests ignore battery optimizations |
| **Progress** | Active list shows progress bar + `downloaded/total` while downloading |
| **Storage** | Shared `Internal storage/threadsyphon`, custom folder, in-app Open folder browser + Files chooser |
| **Extras** | Per-thread pause/resume, complete/404 notifications, auto-hide finished, thumbnails, watch-list JSON export/import, media filter ALL/images/video |

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Poll API + download media; Wi‑Fi gating |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Persistent watcher |
| `POST_NOTIFICATIONS` | Persistent + event notifications (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opt-in so OEM power managers don’t kill the watcher |
| `WAKE_LOCK` | Keep short work windows reliable while the FGS runs |

---

## Share To

From a browser or any app:

- **Share** text containing a 4chan thread URL → **Watch with ThreadSyphon**
- Or tap a `https://boards.4chan.org/.../thread/...` link and choose ThreadSyphon

---

## Wi‑Fi & battery

- **Default:** downloads and checks wait until Wi‑Fi (or Ethernet) is available.
- **Settings → Allow mobile data** opts into cellular for polls + media.
- **Settings → Ignore battery optimizations** and **App info → Battery → Unrestricted** are the primary keep-alive path. On some Samsung builds, sideloaded apps never appear in Never sleeping apps → Add (+); do not rely on that list — use Unrestricted + ignore-optimizations instead.

---

## Architecture (MVP)

```
UI (Compose)  →  WatchRepository (Room + DataStore)
                      ↓
              ThreadEngine + FourChanClient (OkHttp)
                      ↓
              WatchService (foreground) + Rule scout loop
```

- **API:** `https://a.4cdn.org/{board}/thread/{no}.json`, `.../catalog.json`
- **CDN:** `https://i.4cdn.org/{board}/{tim}{ext}`
- **UA:** `threadsyphon-android/1.0 (+mobile thread archiver; respectful polling)`

---

## Find / Rules query (subset of desktop)

| Syntax | Meaning |
|---|---|
| `general work` | both words in subject or OP body |
| `"daily driver"` | phrase |
| `/caig/` or `tag:caig` | subject tag |
| `title:nvidia` / `body:…` | field contains |
| `min_images:10` / `min_replies:50` | numeric floors |
| `-meta` / `NOT meta` | exclude |
| `a OR b` | either group |

---

## Project layout

```
app/src/main/java/com/threadsyphon/android/
  data/db/          Room entities + DAOs
  data/engine/      ThreadEngine, QueryParser, WatchRepository
  data/network/     OkHttp 4cdn client + rate limiters
  data/prefs/       DataStore settings
  service/          WatchService + notifications
  ui/               Compose screens (threads, find, rules, settings)
  util/             Wi‑Fi, battery, storage, MD5
```

Stack: AGP **8.7.3**, Kotlin **2.0.21**, Compose BOM **2024.10.01**, Room, DataStore, Navigation, OkHttp, Coroutines/Flow.  
`applicationId`: `com.threadsyphon.android`

---

## License / ethics

Use respectfully. Obey 4chan’s robots/API guidance and local law. This app is for archiving media from threads **you** choose to watch.

Desktop cousins: [threadsyphon (Windows)](https://github.com/cicalooo/threadsyphon), [threadsyphon-linux](https://github.com/cicalooo/threadsyphon-linux).

## Sideload release signing

Release APKs are signed for sideload only (not Play Store). Create a local
`keystore.properties` (gitignored) pointing at your release `.jks` — see
`app/build.gradle.kts`. Never commit the keystore or passwords.


## Foreground service

WatchService uses `specialUse` (not `dataSync`) so Android 15+ does not apply the 6-hour/24h dataSync timeout to always-on watching. `onTimeout` still stops cleanly if the OS ever times the service out.
