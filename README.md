# Mi TV launcher

A small, ad-free replacement home screen for a Xiaomi Mi TV (Google TV / Android 14), plus the notes
on how the TV was cleaned up over ADB to get there.

What you end up with:

- A custom launcher (plain Java, **no dependencies, ~4 MB APK with wallpapers**, about 64 MB RAM).
- Clock, weather with a 3-day forecast, sunrise/sunset and a month calendar.
- An Apple-TV-style floating **dock**: the focused app opens into its real banner.
- Separate **HDMI 1 / HDMI 2** tiles, and an **auto-jump to your set-top box on cold boot**.
- Wallpapers that crossfade every minute (only while the launcher is on screen).
- Xiaomi/Google bloat removed, the Google TV home disabled.

Tested on: **Xiaomi MiTV-AXFR2**, Android 14 (API 34), 1.5 GB RAM, 1280x720 UI. Other TVs are untested.

**Want to set this up on your own TV? Follow [INSTALL.md](INSTALL.md)** (step-by-step, compatibility notes, undo).

> Everything below is reversible. Nothing is deleted from the system partition. A factory reset undoes all of it.

---

## 1. Prerequisites

- Developer options on the TV, and **USB debugging / network debugging ON**.
- `adb` on your computer (Android platform-tools).
- JDK 17 and the Android SDK (platform 35, build-tools) only if you want to build the launcher.

```bash
adb connect <TV_IP>:5555     # accept the "Allow debugging" prompt on the TV, tick "Always allow"
adb devices                  # should say "device", not "unauthorized"
```

Find the TV IP in Settings -> Device Preferences -> About -> Status.

> An Airtel Xstream box was tried first: its USB-debugging toggle is locked by the firmware, so none of this
> was possible there. Check the toggle can actually be switched on before planning anything.

## 2. Remove the bloat

`pm uninstall -k --user 0` removes an app **for user 0 only**; the system copy stays, so it is recoverable.

```bash
for p in \
  com.miui.tv.analytics com.xiaomi.statistic \
  com.mitv.tvhome.atv com.mitv.tvhome.mitvplus com.mitv.tvhome.michannel com.mitv.tvhome.oemtab \
  com.xiaomi.mimusic2 com.mitv.gallery com.mitv.videoplayer \
  com.xiaomi.mitv.mediaexplorer com.xiaomi.mitv.smartshare \
  com.google.android.feedback com.android.tv.feedbackconsent \
  com.google.android.play.games com.google.android.youtube.tvmusic com.google.android.marvin.talkback
do adb shell pm uninstall -k --user 0 $p; done
```

Restore any of them:

```bash
adb shell cmd package install-existing --user 0 <package>
```

**Deliberately left alone** (removing these risks broken remote keys, picture settings, updates or boot):
`com.xiaomi.mitv.tvmanager`, `mitv.service`, `com.mitv.milinkservice`, `com.mitv.overseaservice`,
`com.mitv.bspfactoryservice`, `com.android.mitv.globalkeyoverlay`, `com.droidlogic.*`,
`com.mitv.livetv` (it handles HDMI inputs), `com.xiaomi.mitv.updateservice` (OTA), Play Store and Play services.

## 3. The launcher

Single activity, programmatic views, no XML layouts, no libraries.

| File | What it does |
|---|---|
| `MainActivity.java` | Everything: dock, widgets, weather, wallpaper rotation, HDMI, All-apps overlay |
| `AndroidManifest.xml` | `HOME` + `LEANBACK_LAUNCHER` intent filters, `INTERNET`, `QUERY_ALL_PACKAGES` |
| `assets/wall/*.jpg` | Wallpapers (not in this repo, see below) |

How it works:

- **Layout** is designed on a 1920x1080 grid and scaled by `screenWidth / 1920`, so it fits any TV density.
- **Tile art** comes from the installed apps at runtime (`getApplicationIcon` for the square icon,
  `getApplicationBanner` for the banner shown when focused). No logos are bundled. Apps that aren't
  installed are skipped.
- **Dock tiles** are defined in the `TILES` array at the top of `MainActivity`. Reorder, add or remove there.
  A kind is a package name, or `hdmi1`, `hdmi2`, `settings`, `apps`, `wall`.
- **Weather** is one call to [Open-Meteo](https://open-meteo.com) (free, no key), cached for 30 minutes,
  fetched only when the launcher is visible. City is set by `CITY` and the coordinates in `WX_URL`.
- **Wallpaper** is decoded from `assets/wall/` at screen width (a 4K JPEG costs about 9 MB instead of 33 MB)
  and crossfaded every `WALL_MS` (60 s). The rotation stops when another app is in front.

### Build and install

```bash
echo "sdk.dir=/path/to/android/sdk" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Uses Android Gradle plugin 8.12.0 with Gradle 9.3.1, `compileSdk 35`, `minSdk 29`.

### Wallpapers

The four photos used here are third-party images, so they are **not** committed (`.gitignore`). Put your
own 16:9 JPEGs in `app/src/main/assets/wall/` and list them in `WALLS` in `MainActivity.java`.
With none present the app still runs; you just get the plain dark background.

## 4. Making it the home screen

Installing it is not enough on Google TV. Findings from this TV:

- Setting the home role (`cmd role add-role-holder ... android.app.role.HOME`) was **not** enough: Home still
  went to Google TV's own launcher (`com.google.android.apps.tv.launcherx`).
- Disabling only `launcherx` also failed: the home candidates are ranked and the Google TV **setup wizard**
  (`com.google.android.tungsten.setupwraith`, `RecoveryActivity`) outranks a third-party launcher, so Home
  landed on nothing.
- Disabling **both** worked.

```bash
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.tvlauncher
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Safe version: apply it and check that Home actually lands on the launcher, undoing everything if not:

```bash
adb shell '
cmd role add-role-holder --user 0 android.app.role.HOME dev.tvlauncher
pm disable-user --user 0 com.google.android.apps.tv.launcherx
pm disable-user --user 0 com.google.android.tungsten.setupwraith
sleep 2; input keyevent KEYCODE_HOME; sleep 5
if dumpsys window | grep mCurrentFocus | grep -q dev.tvlauncher; then echo OK; else
  pm enable --user 0 com.google.android.apps.tv.launcherx
  pm enable --user 0 com.google.android.tungsten.setupwraith
  echo reverted
fi'
```

### Undo (go back to the stock home)

```bash
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
```

Keep network debugging on so you can always do this. If you ever lose adb access with no home screen,
unplug the TV, retry, and as a last resort factory reset (this clears all the disabled/removed state).

## 5. HDMI auto-jump

On the stock firmware it was the Google TV home that jumped back to your last HDMI source after a cold boot
(power unplugged), so replacing it loses that unless the launcher does it itself. This was verified by
re-enabling `launcherx` and cold-booting: the jump came back; with it disabled, it didn't. The Mi "Start to
play when a source is connected" setting (`tv_auto_play_input=1`) is on but does nothing without it.

How the launcher does it:

- Inputs come from `TvInputManager`; HDMI ones have `TYPE_HDMI`. On this TV the ids look like
  `com.droidlogic.tvinput/.services.Hdmi2InputService/HW6`.
- It launches `content://android.media.tv/passthrough/<url-encoded input id>` with `ACTION_VIEW`.
  Live TV (`com.mitv.livetv`) handles it. You can test from adb:

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "content://android.media.tv/passthrough/com.droidlogic.tvinput%2F.services.Hdmi2InputService%2FHW6"
```

- Input state: `0` connected, `1` connected-standby, `2` disconnected (`dumpsys tv_input`).
- **Boot jump:** once per cold boot, detected by `Settings.Global.BOOT_COUNT` changing. It picks the first
  *connected* HDMI input, retries for about 15 s while the TV services come up, and does nothing if no
  device is connected. It never fires on the first run after installing.
- The HDMI 1 / HDMI 2 tiles match `Hdmi<N>InputService` in the input id. Other TVs may name them
  differently; adjust `jumpToPort`.

> Status when this was written: the Home key, both HDMI tiles' jump and the app itself were tested on the TV.
> The unplug-and-replug boot jump with the launcher as home was **not yet confirmed**.

## 6. Gotchas worth remembering

- `mCurrentFocus=null` in `dumpsys window` right after pressing Home can be transient: wait a few seconds.
- `cmd package resolve-activity` ignores the preferred-home setting; judge by which window gets focus.
- Pressing Home while already on the launcher does nothing visible, so test from another screen.
- `adb install -r` of the home app is safe: the role holder survives. Re-check Home afterwards anyway.
- "Unplug the TV" is a cold boot; the standby button is not. They behave differently for HDMI resume.
- Boot took about 50 s versus about 60 s on the stock setup.

## 7. Layout of the repo

```
README.md  INSTALL.md
app/src/main/java/dev/tvlauncher/MainActivity.java
app/src/main/AndroidManifest.xml
app/src/main/assets/wall/            (put your wallpapers here)
build.gradle  settings.gradle  gradlew  gradle/
```
