# Smart Ride Assistant (v1.0 – Live Screen Analysis Edition)

Personal-use Android app for an inDrive driver. It reads the ride-offer screen **on the phone**, compares each offer with your
home → office commute using real road routing, and tells you by voice when a ride adds ≤ 2 km. **You always decide;
the app never taps, accepts, or bids.**

## Honest status — read this first

| Item | Status |
|---|---|
| Source code (Kotlin, Compose, MVVM) | Written, complete |
| Pure logic (OCR parser, route matcher, classifier, announce gate, messages, frame-diff, JSON parsers) | **Compiled with `kotlinc` and 35 unit tests pass** (synthetic data) |
| Android layer (MediaProjection service, Compose UI, ML Kit, TTS, DataStore, GPS) | **Written but NOT compiled or run.** The build sandbox had no Android SDK and no access to Google/Maven/Gradle servers |
| Installable APK | **Not built yet.** Build it with Android Studio or the included GitHub Actions workflow (below) |
| Real inDrive screenshots | **Not tested.** The screenshots mentioned in the brief were not attached, so the parser was tested only on synthetic OCR output using your example values |
| Performance targets (OCR < 1 s etc.) | **Not measured.** Must be benchmarked on the Oppo Reno 14 |

Expect to fix a few compile errors on first build (version pins, API details) and to tune the parser on real screenshots.

## Build the APK

**Option A – Android Studio (recommended)**
1. Install Android Studio (latest stable), open the `SmartRideAssistant` folder, let Gradle sync (needs internet once).
2. If prompted, accept the suggested Android Gradle Plugin / library upgrades, or keep the pinned versions in `gradle/libs.versions.toml`.
3. Run unit tests: `./gradlew testDebugUnitTest`.
4. Build: **Build ▸ Build APK(s)** or `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
   (The debug key is fine for personal use; the file is installable.)

**Option B – no PC build tools: GitHub Actions (free)**
Push this folder to a GitHub repository. The workflow `.github/workflows/build-apk.yml` runs the unit tests and builds the
APK; download it from the run's *Artifacts* (`SmartRideAssistant-debug-apk`).

## Install on the Oppo Reno 14
1. Copy `app-debug.apk` to the phone, open it, allow "install unknown apps" for your file manager when asked.
2. Open the app. Go to **Settings** and set your two locations (see below).
3. Press **Start Monitoring**, allow notifications and location, then accept Android's screen-capture dialog.
4. Open inDrive as usual. Stop any time from the app or the notification's **Stop** button.

### Setting the locations correctly
The app does **not** ship made-up coordinates. In Settings, tap **Find on map** for Home and Office; it queries OpenStreetMap
and shows what it found and the coordinates. Check them. A school or small colony may not exist in OpenStreetMap; then paste
exact coordinates (`lat, lon`, copied by long-pressing the spot in a maps app) or stand there and press **Use GPS here**.
Until the office is located, every ride is NEEDS REVIEW.

### Keep it alive on ColorOS
Menu names vary by ColorOS version: Battery ▸ App battery management ▸ Smart Ride Assistant ▸ allow background activity;
allow notifications; location "While using" or "Always"; lock the app in recents. If ColorOS or Android still ends capture
the app shows *Stopped* and you must start again; it never claims uninterrupted monitoring and never restarts capture by itself.
After a reboot nothing starts automatically (Android requires fresh consent each session).

## How it works
```
MediaProjection (foreground service) → sample 1 frame/s (3 s on non-ride screens) → tiny-grid change detection
   → ML Kit OCR (on-device) → ride-screen detection (fare + distance) → RideParser (cards / detail)
   → geocode pickup & destination (Nominatim, bounded to ±45 km around you)
   → plausibility check against distances printed on screen
   → OSRM road routes: current→office, and current→pickup→destination→office
   → ADDITIONAL = ride route − normal route → classify → history → voice alert (once per offer per cooldown)
```
**Classification** (limit = Settings, default 2 km): FAVOURABLE if additional ≤ limit and the drop-off is not behind you by
more than limit/2; MODERATE if additional ≤ 2×limit (or the drop-off is off-route); NOT SUITABLE beyond 2×limit;
NEEDS REVIEW whenever OCR, geocoding, GPS or routing is incomplete or uncertain (no guesses). The *route fit index* is a
simple heuristic (100 at no extra distance, 0 at 2× the limit), **not a probability**.

## Layout
`app/src/main/java/com/smartride/assistant/`: `capture/` (service, frame diff, shared state) · `ocr/` (ML Kit + `RideParser`) ·
`routing/` (provider interfaces, Nominatim, OSRM) · `match/` (`RouteMatcher`, `AnalysisCoordinator`) · `alert/` (TTS, gate, messages) ·
`data/` (models, DataStore settings/history, GPS) · `ui/` (Compose screens, ViewModel). Tests: `app/src/test/…`.

## Known limitations
- **Protected screens:** if inDrive marks its window secure, Android returns blank frames. The app does not and must not bypass that; nothing will be detected.
- **Terms of service:** inDrive's terms may restrict automated reading of its app. Check them yourself before use. This tool only reads what is on screen and never interacts.
- **Parser tuning:** card layout assumptions (fare per card, address order pickup→destination, region split at the largest vertical gap) need verification with real screenshots. Low confidence → NEEDS REVIEW with a manual-correction dialog.
- **Geocoding quality:** shop names and informal areas may be missing in OpenStreetMap → NEEDS REVIEW. The pickup is rejected if its map position contradicts the distance shown on screen.
- **Free services:** Nominatim (≤ 1 req/s, cached) and the OSRM demo server are for light use; point Settings ▸ Advanced to your own OSRM server for more. Needs internet while driving.
- **Urdu voice:** needs an Urdu TTS voice installed; otherwise the app falls back to Roman Urdu and tells you.
- **Orientation:** capture size is fixed at start; use portrait.
- Android 14+ lets you share a single app instead of the whole screen; either works.
- Android 14+ requires consent for every capture session; there is no "always on" mode.

## Roadmap if you want more
Calibrate the parser with 10–20 real screenshots · add instrumentation tests for the service lifecycle · benchmark OCR time/battery · optional offline routing.

Map data © OpenStreetMap contributors (ODbL).
