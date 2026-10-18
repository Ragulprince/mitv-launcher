# Install guide

Step by step, for someone who has never used adb. Takes about 20 minutes. Everything here is reversible
(see [Undo](#undo-everything)).

## Will it work on my TV?

Honest answer: **tested on one TV only** (Xiaomi MiTV-AXFR2, Android 14). It is plain Android, so parts of it
should work elsewhere, but not everything.

| Part | Other Android / Google TVs |
|---|---|
| The launcher app itself | Should run on any Android TV / Google TV with Android 10+ (`minSdk 29`). |
| Making it the home screen | **Device specific.** The stock launcher's package name differs per brand and you have to disable it (step 5). Not tested outside this Mi TV. |
| Debloat list in the README | **Xiaomi only.** Do not paste it on another brand. Find your own bloat (step 6). |
| Boot jump to the HDMI source | Uses the standard `TvInputManager`, so it should work wherever the TV lists HDMI inputs and has a Live TV app that handles passthrough. Unverified elsewhere. |
| HDMI 1 / HDMI 2 tiles | Match input ids containing `Hdmi1InputService` / `Hdmi2InputService` (Amlogic/droidlogic TVs). Other brands may name them differently: the tile shows "No HDMI N input found" until `jumpToPort` is adjusted. |
| Fire TV (Fire OS) | Not Android TV, different launcher setup. Not supported or tested. |
| Phones / tablets | Not meant for them. |

If your TV has no way to switch on **USB / network debugging** (some operator boxes lock it), stop here:
nothing below is possible on it.

## What you need

- A computer on the **same Wi-Fi / network** as the TV.
- `adb`: install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
  and check `adb version` works.
- To build the launcher: JDK 17 and the Android SDK (platform 35). Or get a prebuilt APK from someone you
  trust and skip step 3.
- Four to eight 16:9 wallpaper JPEGs (optional).

## 1. Turn on debugging on the TV

1. Settings -> Device Preferences -> About -> tap **Build** 7 times to enable Developer options.
2. Settings -> Device Preferences -> Developer options -> turn on **USB debugging** (and **Network debugging**
   / **ADB over network** if it exists).
3. Note the TV IP: Settings -> Device Preferences -> About -> Status.

## 2. Connect

```bash
adb connect <TV_IP>:5555
adb devices
```

Accept **Allow USB debugging?** on the TV and tick *Always allow*. `adb devices` must say `device`
(not `unauthorized`, not empty).

## 3. Build the launcher

```bash
git clone https://github.com/Ragulprince/mitv-launcher
cd mitv-launcher
```

Before building, edit `app/src/main/java/dev/tvlauncher/MainActivity.java`:

- **City and weather:** `CITY` and the `latitude=` / `longitude=` in `WX_URL` (look yours up on any map).
- **Dock apps:** the `TILES` array. Each entry is `{"Label", "package.name"}`; apps that aren't installed are
  skipped. Special kinds: `hdmi1`, `hdmi2`, `settings`, `apps`, `wall`.
  List your TV's packages with `adb shell pm list packages -3`.
- **Wallpapers:** copy your JPEGs into `app/src/main/assets/wall/` and list the file names in `WALLS`.
  Skip it and you get a plain dark background.

Then build:

```bash
echo "sdk.dir=/path/to/android/sdk" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## 4. Install and try it

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.tvlauncher/.MainActivity
```

It opens on top of your current home. Check the clock, weather, dock and focus movement with the remote.
**Stop here if anything looks broken.** The stock home still works at this point, nothing is changed.

## 5. Make it the home screen (optional)

Pressing Home still opens the stock launcher until you disable it.

1. Find what the TV's home is:

   ```bash
   adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```

   On the tested Mi TV: `com.google.android.apps.tv.launcherx` (Google TV home) and
   `com.google.android.tungsten.setupwraith` (setup wizard, ranks above third-party launchers).
   On Android TV (non Google TV) the stock one is usually `com.google.android.tvlauncher`. Brand launchers
   (Sony, TCL, Philips...) have their own names. **Ignore `dev.tvlauncher`.**

2. Run this with your package names in `PKGS`. It disables them, presses Home, and **reverts by itself** if
   the launcher didn't take over:

   ```bash
   adb shell '
   PKGS="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith"
   cmd role add-role-holder --user 0 android.app.role.HOME dev.tvlauncher
   for p in $PKGS; do pm disable-user --user 0 $p; done
   sleep 2; input keyevent KEYCODE_HOME; sleep 5
   if dumpsys window | grep mCurrentFocus | grep -q dev.tvlauncher; then echo OK; else
     for p in $PKGS; do pm enable --user 0 $p; done; echo reverted
   fi'
   ```

3. Press Home on the remote from another app. You should land on the launcher.

Do **not** disable system packages you can't name: `com.android.*`, `com.droidlogic.*`, the Live TV app
(it handles HDMI), OTA update services.

## 6. Remove bloat (optional)

Only remove things you recognise. The README section 2 has the Xiaomi list. For other brands, list candidates
and look each one up first:

```bash
adb shell pm list packages -s     # system apps
```

```bash
adb shell pm uninstall -k --user 0 <package>      # remove for you only; system copy stays
adb shell cmd package install-existing --user 0 <package>   # bring it back
```

## 7. HDMI auto-jump

Nothing to set up. After a cold boot the launcher jumps to the first **connected** HDMI input (retries for
about 15 s). Turn the set-top box on before powering the TV. Test: unplug the TV for 10 s, plug it back in.
To check the TV sees your inputs: `adb shell dumpsys tv_input`.

If HDMI 1 / 2 tiles say "No HDMI N input found", look at the input ids in that output and adjust the match in
`jumpToPort`.

## Undo everything

```bash
# stock home back (use the packages you disabled in step 5)
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
# restore anything removed in step 6
adb shell cmd package install-existing --user 0 <package>
# remove the launcher
adb uninstall dev.tvlauncher
```

Keep network debugging on until you are sure everything works. If you ever end up with no home screen and no
adb, unplug the TV and retry; the last resort is a factory reset, which clears all of this.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `adb devices` shows `unauthorized` | Accept the prompt on the TV; revoke USB debugging authorisations in Developer options and reconnect. |
| `failed to connect` | Same network? Network debugging on? Try rebooting the TV. |
| Home shows a blank screen | You disabled a package that ranks above the launcher, or not all of them. Re-run step 5 with the missing package, or undo. |
| Weather blank | TV has no internet, or `WX_URL` coordinates are wrong. |
| HDMI tile does nothing | See step 7. |
