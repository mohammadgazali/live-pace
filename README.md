# Live Pace

A floating circular bubble that shows your pace over the last 10 seconds, on top of Runna or any other app.

- **Drag** it anywhere on the screen
- **Tap** it to pause or resume alerts (for warm-ups, recoveries and walk breaks)
- **Hold** it for 2 seconds to close it
- **Colours:** green = in range · red = too slow · blue = too fast · dark = no target, alerts paused, or no GPS yet
- **Vibration** (optional): 2 long buzzes = too slow, 3 short = too fast. Fires only after you've been off-target for 5 seconds, and at most once every 20 seconds.
- **Voice** (optional): speaks your pace every 30 or 60 seconds and briefly lowers your podcast while it talks.

---

## Step 1 — Turn this folder into an APK (pick ONE way)

### Way A: Android Studio on your laptop (easiest, ~3 GB download once)
1. Install Android Studio from developer.android.com/studio
2. Open it → **Open** → choose this `LivePace` folder. Wait for "Gradle sync" to finish (first time takes 5–10 min).
3. Menu: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
4. When it says "APK(s) generated", click **locate**. The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Way B: GitHub builds it for you (nothing to install)
1. Create a new repository on github.com (private is fine) and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. A "Build APK" run starts on its own (or press **Run workflow**).
3. When it turns green (~4 min), open the run and download **LivePace-apk** at the bottom. Unzip it to get `app-debug.apk`.

## Step 2 — Install on your phone
1. Send `app-debug.apk` to your phone (WhatsApp to yourself, Google Drive, or USB cable).
2. Tap it. Android will ask to allow installs from that app (Files / Drive / WhatsApp) → allow.
3. If Play Protect warns "unknown app", tap **Install anyway**. That warning appears for any app not from the Play Store.

## Step 3 — First run
1. Open **Live Pace**, enter your target range (for example `5:20` to `5:40`), choose vibration/voice, tap **Start bubble**.
2. Allow **Precise location**, choose **While using the app**.
3. It opens "Display over other apps" → find Live Pace → turn it on → go back → tap **Start bubble** again.
4. Open Runna. The bubble shows `GPS wait` until it has a fix, then your pace.

### Important on Xiaomi / Redmi / Realme / Vivo / Oppo / OnePlus / Samsung
These phones sometimes kill background apps mid-run. Once, go to
**Settings → Apps → Live Pace → Battery** and set it to **Unrestricted / No restrictions**.
On Xiaomi also turn on **Autostart** for Live Pace.

---

## Good to know
- It can't read Runna's targets (Runna has no way for other apps to read them). Set the range yourself before the run. It works best for easy, tempo and long runs with one target. For intervals, tap the bubble to pause alerts during recoveries, or open the app and press Start again with a new range. Pressing Start while it's running just updates the settings.
- The pace is a 10-second rolling average of the GPS chip's own speed reading. That's steadier than raw second-by-second pace but reacts about 5 seconds after you change speed.
- Under bridges, flyovers and tall buildings GPS gets worse for everyone. The bubble shows `GPS wait` when the signal drops instead of showing a wrong number.

## Changing things later
- Smoothing window, accuracy filter: `app/src/main/java/com/gazali/livepace/PaceCalculator.kt` (`WINDOW_MS`)
- Buzz timing, colours, bubble size: `PaceService.kt` (constants at the top, `dp(84f)` for size)

Easiest way: open this folder in Claude Code and ask for the change, then rebuild.
