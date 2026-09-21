# Screenshots

Images in this directory are captures of the **running app**, taken from the
`Device smoke test` workflow (API 34 x86_64 emulator, debug APK built from the
same commit). Nothing here is a mock-up: this project does not publish a picture
of a screen it cannot render.

## What is currently here

| File | Shows | Notes |
| --- | --- | --- |
| `01-app-amoled.png` | Catalogue screen, AMOLED theme | The CI runner cannot resolve `voir-anime.to`, so this screen legitimately shows its **error message** (`Requête échouée: … Unable to resolve host`). That is the app reporting the real cause instead of an empty catalogue. |
| `01-app-amoled-with-system-dialog.png` | The same screen with the emulator's ANR dialog over it | Present when the emulator raised its own "Process system isn't responding" dialog (its `com.android.phone` / `media.module` processes do that on software-rendered runners) and dismissing it failed. The app UI is still visible behind it. The filename says so rather than hiding it. |
| `02-notification-extras-launch.png` | The app after a launch carrying the episode watcher's extras | Same network limitation. |
| `03-settings.png` | Settings | Present only when the run verified the tab was reached, by a string that exists nowhere else in the app. |
| `smoke-report.txt` | The run's own record | Emulator API/ABI, package version, `am start -W` timing, resumed activity, per-screen visible text, ANR lines. Read it before trusting an image: the report says which state each capture came from. |

A file that is missing from this directory was not produced by the last run —
the smoke test captures only when the UI is up and no system dialog is covering
it, and it deletes a screenshot whose screen it could not verify. Missing here
therefore means "not captured", never "captured elsewhere". The report records
which case applied.

Not captured, and deliberately not implied by the images above: playback, the
quality selector with measured sizes, a download in progress, and offline
playback. Each needs network that reaches the sources, which the CI runner does
not have. Capture them on a real connection (procedure below) and the README's
*Verification status* section should be updated in the same change.

The smoke test reads the app's frame count from `dumpsys gfxinfo` before capturing
(a resumed activity can still be showing the splash screen — the first version of
this test captured exactly that and called it a catalogue). Where a metric is
unreadable on the image, the report says "unreadable" instead of implying it was
checked.

The captures are committed by the workflow rather than uploaded as artifacts,
because artifact downloads and raw job logs are unreachable from the
environments this project is developed in; a committed file is readable
everywhere. The smoke test refuses to save a screenshot while a system dialog is
on screen — an image showing the emulator's own "Process system isn't responding"
dialog under an app-screen filename would be a claim the file cannot support.

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
