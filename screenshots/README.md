# Screenshots

Images in this directory are captures of the **running app**, taken from the
`Device smoke test` workflow (API 34 x86_64 emulator, debug APK built from the
same commit). Nothing here is a mock-up: this project does not publish a picture
of a screen it cannot render.

## What is currently here

| File | Shows | Notes |
| --- | --- | --- |
| ![](03-settings.png) `03-settings.png` | Settings, AMOLED theme | Captured by the smoke test after it verified the tab was reached (by text unique to that screen) and that the app's own window was on screen. The version string in the frame names the build it came from. |
| `smoke-report.txt` | The run's own record | Emulator API/ABI, package version, `am start -W` timing, resumed activity, per-screen visible text, per-capture verification lines, ANR lines. |

No catalogue capture is kept at the moment, and that is deliberate: earlier runs
produced files named `01-app-amoled.png` whose frames were the **launcher** (the
system ANR dialog had been dismissed with BACK, which also left the app) and then
the app behind the emulator's own "not responding" dialog. Both were mislabelled
claims, so they were deleted rather than renamed or annotated. The current script
writes a capture only when the app's window is on screen, suffixes
`-with-system-dialog` when the dialog is in front, and fails the run when it
cannot produce a single verified capture.

Not captured, and deliberately not implied: playback, the quality selector with
measured sizes, a download in progress, and offline playback. Each needs network
that reaches the sources, which the CI runner does not have. Capture them on a
real connection (procedure below) and update *Verification status* in the root
README in the same change.

## How they are produced

1. Get an installable build — either from a CI run (the `debug-apk` artifact of
   the `CI` workflow) or by building locally:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Capture the screens worth showing, with the app in the theme you want to
   document (`AMOLED` and light are both in Settings):

   ```bash
   # AMOLED / dark
   adb shell "cmd uimode night yes"
   adb exec-out screencap -p > screenshots/01-app-amoled.png

   # light
   adb shell "cmd uimode night no"
   adb exec-out screencap -p > screenshots/04-catalogue-light.png
   ```

3. The remaining shots, for a machine that can reach the sources:

   | File | Screen | What it has to show |
   | --- | --- | --- |
   | `04-catalogue-light.png` | Catalogue (light) | the same screen in the light theme |
   | `05-detail-episodes.png` | Series detail | episode list newest-first, film/OAV labels |
   | `06-player-quality.png` | Player | measured size, host, quality chips, downgrade note if it fired |
   | `07-downloads.png` | Downloads | progress, pause/resume, size on disk |
   | `08-offline-playback.png` | Player, airplane mode | « Lecture hors ligne » on a finished download |

   Name a file for the state it really shows: if the catalogue is reporting an
   unreachable source when you capture it, that is worth keeping — but not under a
   name that implies a grid of covers.

   For `08-offline-playback.png`, enable airplane mode after the download
   finishes: the point of the screenshot is that playback works with no network
   and the app says why.

4. Keep the PNGs under ~500 KB each (`pngquant`, `optipng` or Android Studio's
   exporter) and reference them from the root `README.md`.

If a screen cannot be captured because it does not work yet, it is listed in the
root README's *Verification status* section instead of being illustrated with
something invented.
