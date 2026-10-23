# Home-screen customisation, 2026-10-23: full session memory

Read this first next time. It records what was built in one long session, why, how to rebuild it, and what is left.
The repo `README.md` / `INSTALL.md` cover the earlier work (debloat, becoming the home app, HDMI boot jump).

## State at the end of the day

- A custom launcher (plain Java, no libraries, ~4 MB APK) running on a **Xiaomi MiTV-AXFR2**, Android 14, 1280x720 UI.
- **USB / network debugging was turned OFF afterwards**, so nothing can be installed until it is switched back on
  (Developer options -> USB debugging; accept the "Allow USB debugging?" prompt, tick "Always allow").
- Projectivy Launcher (`com.spocky.projengmenu`) was uninstalled on request (reinstall from Play Store if wanted).
- Everything below is **built, installed and screenshot-checked on the TV**, except where marked "not tested".

## What the user asked for, in order

1. Follow the guide PDF **"Your TV, Your Way"** (a reel guide: CleanYourTV by Gathin23 + prompts) on their TV.
   Section 1-2 (debloat, base launcher) were already done. Built section 3 (clean-up) and 5 (home screen).
   Section 6 (My Favourites, needs a Photoshop PSD of cut-outs) was impossible, there were no PNG/PSD assets, so it
   was **re-imagined as a poster wall**. Section 7 (mood cards) was built with posters instead of art assets.
2. White logos in the dock like the guide's screenshot (no boxes, no wordmarks).
3. Black-and-white Doctor Doom wallpaper like the guide, later replaced by the user's own four F1 wallpapers
   (`Desktop\maxx`), rotating.
4. Remove HDMI from the dock (use the top-right Inputs icon), wallpaper every 10 s.
5. Real thumbnails on everything; add Vijay and Ajith films (Prime / Hotstar), Better Call Saul, more Prime and Hotstar picks.
6. Enlarge the dock like the guide's "UI breakdown" image (6 equal cells, big logos, left tagline, name under focus).
7. Uninstall Projectivy Launcher. Then: save all this as a memory file, push to GitHub.

## Final design (1920x1080 design grid, scaled by `screenWidth / 1920`)

Three screens stacked vertically in `pager`; focus moves scroll them (`OnGlobalFocusChangeListener` reads a tag:
none = dock, 1 = favourites, 2 = deck).

**Screen 1: home**
- Clock top-left: Outfit weight 200, 96 px, 24 h, no date. Beside it a white *outline* weather icon + temperature
  in C (Open-Meteo, Kallakurichi coordinates, cached 30 min, fetched only while the launcher is showing, no place name).
- Top-right bare icons: Wi-Fi (opens Wi-Fi settings), Inputs (jumps to the first *connected* HDMI input), Settings.
  Focus = grow 1.3x.
- Dock: 6 equal cells of 287 x 190, starting 100 px from the left, y = 770. Cells 1-5 = Netflix, Hotstar, Prime, YouTube,
  Apple TV (white vector logos, each in its own square box so visual weight matches); cell 6 = All apps (opens the grid).
  Focus = grow 1.18x, the app name fades in under the logo, a **tagline** (Outfit 300, 64 px, left-aligned at y = 640)
  fades in above, and a soft radial **glow** in the app's brand colour sits behind the dock.
  Taglines rotate per visit (`visit % lines.length`); they are placeholders, edit `TILES`.
- Wallpaper: four photos crossfade every **10 s** (`WALL_MS`), only while the launcher is visible. A top and bottom
  scrim keeps the clock and dock readable. The black `dim` veil fades in when you are on screens 2-3.
- Screensaver: after 3 min without a key the whole home layer fades out leaving only the wallpaper; any key wakes it
  (the waking key is swallowed).
- All apps grid: sorted list of every other leanback app (dock apps hidden), focus = grow, Back/Home closes it.

**Screen 2: My Favourites** (`Browse.FAVS`, 2 rows x 7)
- Real posters, black and white until focused; focused = colour (saturation animation), grows 1.18x, name above,
  glow in the poster's average colour, app badge bottom-right. OK opens the title in its app.

**Screen 3: "Don't have time to browse?"** (`Browse.Deck`, moods in `Browse.MOODS`)
- Big mood card (mood colour fading to near black, first three posters fanned, name, "N titles"), two cards peeking behind.
- Left = skip (card flies off with a tilt). Right/OK = poster reel that starts fast and slows, lands on a random title,
  "Opening in 3..." with a progress bar in the mood colour, then opens it. During the countdown OK = open now,
  Right = spin again, Left/Back = cancel.
- Moods: Thalapathy mass, Thala mass, Edge of your seat, Capes and chaos, Gotham after dark, Mind games, Desi
  originals, Epic worlds, Anything at all (holds every title).

## Where things live

| Thing | File |
|---|---|
| Home, dock, pager, favourites, grid, weather, screensaver, wallpaper | `app/src/main/java/dev/tvlauncher/MainActivity.java` |
| Titles data, poster drawing, mood card, deck/reel/countdown, deep-link opening | `app/src/main/java/dev/tvlauncher/Browse.java` |
| White vector logos (netflix, hotstar, prime, youtube, apple) | `app/src/main/res/drawable/logo_*.xml` |
| Outfit variable font (SIL OFL) | `app/src/main/assets/fonts/Outfit.ttf` |
| Title list (name, app package, deep link, poster file) | `app/src/main/assets/titles.tsv` (generated) |
| Generator for the list + posters | `tools/build_titles.py` |
| Wallpaper rotate helper | `tools/RotateWallpapers.java` |
| Posters, wallpapers | `app/src/main/assets/posters/`, `assets/wall/`: **git-ignored** (third-party images) |

Posters and wallpapers are not in git. After a fresh clone: run `python3 tools/build_titles.py` for the posters
(needs internet), and put your own landscape JPEGs in `assets/wall/` and list them in `WALLS`.

## Data pipeline and deep links (what was learned)

- **Posters + availability: JustWatch's public GraphQL** (`https://apis.justwatch.com/graphql`, no key). Query
  `popularTitles(country: IN, filter:{searchQuery, objectTypes})`; `content.posterUrl` has `{profile}` / `{format}` placeholders
  (used `s276`, `jpg` at `https://images.justwatch.com`); `offers(country: IN, platform: WEB)` gives `standardWebURL`, which is the
  app deep link. Package short names: `nfx` Netflix, `prv` Prime Video, `jhs` JioHotstar. Schema introspection is disabled; guess field names.
- Tried and rejected for posters: Wikipedia (series give logos/landscape cards), TVmaze (ok but no films), iTunes search (empty), TMDB (needs a key).
- **Deep link formats that resolve on this TV** (`adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -d <url> <pkg>`):
  - Netflix `https://www.netflix.com/title/<id>` -> `com.netflix.ninja`
  - Prime Video `https://app.primevideo.com/detail?gti=amzn1.dv.gti.<uuid>` -> `com.amazon.amazonvideo.livingroom`
    (`www.primevideo.com/...` and `watch.amazon.in/...` do **not** resolve)
  - Hotstar `https://www.hotstar.com/in/<id>` -> `in.startv.hotstar`
  - Always launch with `setPackage`, fall back to opening the app if it throws.
- **Wikidata** has useful ID properties: P1874 Netflix ID, P14462 Prime Video GTI, P11049 JioHotstar ID, P8055/P14440 Prime IDs.
  It confirmed most Netflix ids and caught a wrong one (Sacred Games is 80115328).
- Netflix IDs from memory were mostly right but not all: **always take links from the generator, not from memory.**
- Not on Netflix/Prime/Hotstar in India at build time, so left out: Sherlock, Kaththi (Zee5), Vedalam, Mankatha, Valimai, Billa.
- Verified on the TV: Prime link opened Master's page (it is the **Malayalam-dubbed** listing JustWatch returns); Hotstar link
  opened the app but showed a login (JioHotstar is not signed in on this TV).

## Wallpapers

- User's four images were **portrait phone wallpapers shown sideways**; rotating each 90 degrees **counter-clockwise** makes them
  upright (checked on a contact sheet). Done at full resolution with the JDK only (no ImageMagick/PIL here): `tools/RotateWallpapers.java`.
- Doctor Doom (Wallhaven `n67m3l`) was used for a while with a black-and-white `ColorMatrix` filter; `MONO` switches that.
  The old four photos and Doom are parked outside the repo in `~/tvlauncher-unused-walls/`.

## Environment and commands

- WSL2 Ubuntu. `adb` is **not on PATH**: `/home/prince/android/sdk/platform-tools/adb`. JDK: `/home/prince/android/jdk17`.
- TV was at `192.168.1.12:5555` on 2026-10-23 (DHCP, may change). `adb connect <ip>:5555`, accept the prompt on the TV.
- Build + install + restart:

```bash
export JAVA_HOME=/home/prince/android/jdk17 ANDROID_HOME=/home/prince/android/sdk
./gradlew assembleDebug -q --offline
A=/home/prince/android/sdk/platform-tools/adb
$A install -r app/build/outputs/apk/debug/app-debug.apk
$A shell am force-stop dev.tvlauncher; $A shell am start -n dev.tvlauncher/.MainActivity
$A exec-out screencap -p > shot.png        # Netflix/Prime/Hotstar playback is blocked from capture
```

- Use `force-stop` + `start` to see a new build: `am start` alone just brings the running instance forward.
- Edit prefs for testing (debug build): `adb shell run-as dev.tvlauncher sh -c 'sed -i ... shared_prefs/p.xml'` (e.g. wallpaper index `wall`).
- Reading the guide PDF here was done by parsing its content streams with Python (no poppler); the text is in its `ToUnicode` maps.

## Gotchas hit (so they are not hit again)

- Pixel-extracting white logos from the apps' own icons (luminance cut-off) looked ragged; **vector logos** (Simple Icons paths +
  two hand-drawn: the sparkle and the Amazon smile) look clean. Vector drawables have a square viewport, so size each logo as a *square* box.
- The glow view must be added to `home` **before** `pager` (index 0) or it tints the logos.
- A view's focus listener fires before the global focus listener: keep per-page colours in fields (`dockColor`, `favColor`, `deckColor`).
- Children outside a parent's bounds draw only if every ancestor has `clipChildren = false`; the app-name label was invisible until it sat
  inside the cell.
- `popularTitles` search needs an exact normalised title match + year within 1 or it grabs the wrong show ("Master", "Leo", "Dark").
- Rotated phone wallpapers: look at both rotations before choosing.

## Open items / ideas

- **Prime Master** opens the Malayalam-dub entry; prefer the Tamil listing if JustWatch returns several.
- Hotstar needs signing in on the TV; Prime titles need a Prime membership.
- Not tested: the 3 min screensaver, tapping Wi-Fi/Inputs/Settings icons, Back from All apps, the full countdown screen after the poster change,
  Home returning to the last-used item.
- The Inputs icon jumps to the first *connected* HDMI input, with HDMI removed from the dock there is no way to pick HDMI 1 vs 2:
  a small chooser would fix that (`jumpToPort` was deleted; it is in git history).
- Sony LIV was dropped from the dock (no clean symbol); a white SVG would bring it back.
- `README.md` still describes the older dock (HDMI tiles, 60 s wallpaper, widgets). This file is the newer truth.
- Not done from the guide: the optional PS5 item, per-title "scenes", character touches (section 6 follow-ups).
- Logos are trademarks of their owners and used only to label apps on a private TV; posters/wallpapers are personal-use and kept out of git.

## Credits

Guide and base idea: CleanYourTV by Gathin23 (MIT) and the "Your TV, Your Way" guide. Weather: Open-Meteo. Typeface: Outfit (SIL OFL).
Logo paths: Simple Icons (CC0). Availability and posters: JustWatch. IDs: Wikidata.
