# Screenshots

Images in this directory are captures of the **running app**, taken from a
device or emulator with a CI-built APK. Nothing here is a mock-up: this project
does not publish a picture of a screen it cannot render.

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
   adb exec-out screencap -p > screenshots/01-catalogue-amoled.png

   # light
   adb shell "cmd uimode night no"
   adb exec-out screencap -p > screenshots/02-catalogue-light.png
   ```

3. Suggested set:

   | File | Screen | What it has to show |
   | --- | --- | --- |
   | `01-catalogue-amoled.png` | Catalogue | covers, VF/VOSTFR badges, pure-black background |
   | `02-catalogue-light.png` | Catalogue (light) | the same screen in the light theme |
   | `03-detail-episodes.png` | Series detail | episode list newest-first, film/OAV labels |
   | `04-player-quality.png` | Player | measured size, host, quality chips, downgrade note if it fired |
   | `05-downloads.png` | Downloads | progress, pause/resume, size on disk |
   | `06-offline-playback.png` | Player, airplane mode | « Lecture hors ligne » on a finished download |

   For `06-offline-playback.png`, enable airplane mode after the download
   finishes: the point of the screenshot is that playback works with no network
   and the app says why.

4. Keep the PNGs under ~500 KB each (`pngquant`, `optipng` or Android Studio's
   exporter) and reference them from the root `README.md`.

If a screen cannot be captured because it does not work yet, it is listed in the
root README's *Verification status* section instead of being illustrated with
something invented.
