package dev.tvlauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public class MainActivity extends Activity {
    // label, kind (package name), glow colour, taglines (| separated; one per visit, rotating),
    // and for apps "logo:size": res/drawable/logo_<logo>.xml (white vector) in a size x size box (1920 grid); tune size so thin and solid logos weigh the same.
    // Reorder, edit, or rewrite the taglines freely.
    static final String[][] TILES = {
            {"Netflix", "com.netflix.ninja", "E50914", "Just one more episode|Binge responsibly", "netflix:135"},
            {"Hotstar", "in.startv.hotstar", "1F80E0", "Match day starts here|Live, loud, yours", "hotstar:140"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "00A8E1", "Free delivery, endless stories", "prime:230"},
            {"YouTube", "com.google.android.youtube.tv", "FF2D2D", "Down the rabbit hole|Watch something new", "youtube:150"},
            {"Apple TV", "com.apple.atve.androidtv.appletv", "B8B8C0", "Stories worth the wait", "apple:140"}};
    // Files in assets/wall/. Drop more in and list them here.
    static final String[] WALLS = {"mx1.jpg", "mx2.jpg", "mx3.jpg", "mx4.jpg"}; // more than one and they crossfade every WALL_MS
    // shown in black and white; set to false for colour
    static final boolean MONO = false;
    static final long WALL_MS = 10 * 1000L;
    static final long IDLE_MS = 3 * 60 * 1000L; // home screen fades away after this long without a key
    // ponytail: fixed coordinates for one city; add a setting if you ever move.
    static final String WX_URL = "https://api.open-meteo.com/v1/forecast?latitude=11.74&longitude=78.96"
            + "&current=temperature_2m,weather_code&timezone=auto";
    static final long WX_TTL = 30 * 60 * 1000L;

    float s;
    PackageManager pm;
    SharedPreferences prefs;
    TvInputManager tvm;
    final Handler h = new Handler();
    FrameLayout root, home, pager; // pager: three screens stacked (dock, My Favourites, mood deck), scrolled by focus
    Browse.Deck deck;
    int page, dockColor, favColor, deckColor;
    TextView favTitle;
    ImageView wall;
    View scrim, glow, dim;
    GradientDrawable glowBg;
    int glowColor, visit;
    boolean asleep;
    int wallIdx;
    LinearLayout dock;
    TextView tagline, temp, curLabel;
    WxIcon wxIcon;
    View moreApps;
    GridView grid;

    final Runnable rotate = new Runnable() {
        @Override public void run() {
            nextWall();
            h.postDelayed(this, WALL_MS);
        }
    };
    final Runnable sleep = this::sleepHome;

    int px(float d) { return Math.round(d * s); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        pm = getPackageManager();
        prefs = getSharedPreferences("p", 0);
        tvm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070A);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (MONO) { ColorMatrix m = new ColorMatrix(); m.setSaturation(0); wall.setColorFilter(new ColorMatrixColorFilter(m)); }
        wallIdx = prefs.getInt("wall", 0) % WALLS.length;
        Bitmap first = loadWall(WALLS[wallIdx]);
        if (first != null) wall.setImageDrawable(new BitmapDrawable(getResources(), first));
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        scrim = new View(this); // dark at the top for the clock, darker at the bottom so the dock sits on black
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB8000000,
                0x61000000, 0x00000000, 0x66000000, 0xF2000000});
        sg.setColors(new int[]{0xB8000000, 0x61000000, 0x00000000, 0x66000000, 0xF2000000},
                new float[]{0f, .18f, .36f, .62f, 1f});
        scrim.setBackground(sg);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        dim = new View(this); // black veil, fades in as focus moves down the page
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        home = new FrameLayout(this);
        home.setClipChildren(false);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        pager = new FrameLayout(this);
        pager.setClipChildren(false);
        home.addView(pager, new FrameLayout.LayoutParams(-1, 3 * getResources().getDisplayMetrics().heightPixels));

        // time top left, weather beside it (no date, no place name)
        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        styleText(clock, 96, Color.WHITE, false);
        clock.setTypeface(outfit(200));
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        wxIcon = new WxIcon(this);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(px(48), px(48));
        wl.leftMargin = px(44);
        left.addView(wxIcon, wl);
        temp = text("", 40, Color.WHITE, false);
        temp.setPadding(px(12), 0, 0, 0);
        left.addView(temp);
        place(left, Gravity.TOP | Gravity.START, 96, 56, 0);

        // top right: bare icons, focusable, nothing behind them
        LinearLayout icons = new LinearLayout(this);
        icons.setGravity(Gravity.CENTER_VERTICAL);
        String[][] top = {{"wifi", "wifi"}, {"hdmi", "inputs"}, {"settings", "settings"}};
        for (String[] t : top) {
            View g = new Glyph(this, t[0]);
            grow(g, 1.3f, null);
            g.setOnClickListener(v -> click(t[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(60), px(60));
            lp.leftMargin = px(36);
            icons.addView(g, lp);
        }
        place(icons, Gravity.TOP | Gravity.END, 0, 76, 96);

        // soft glow behind the dock in the focused app's colour (a round gradient stretched wide)
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(px(500));
        glow.setBackground(glowBg);
        glow.setScaleX(1.9f);
        setGlow(0);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(px(1000), px(1000), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gp.topMargin = px(330);
        home.addView(glow, 0, gp); // behind the pager

        tagline = text("", 64, 0xF2FFFFFF, false);
        tagline.setTypeface(outfit(300));
        place(tagline, Gravity.TOP | Gravity.START, 100, 640, 0);

        // dock: equal cells across the screen, logos floating on the wallpaper; the last cell opens all apps
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false);
        List<String[]> cells = new ArrayList<>(Arrays.asList(TILES));
        cells.add(new String[]{"All apps", "apps", "B8B8C0", "Everything else on this TV"});
        for (String[] t : cells) {
            View v = tile(t);
            if (v == null) continue; // app not installed
            dock.addView(v, new LinearLayout.LayoutParams(px(287), px(190)));
            moreApps = v;
        }
        place(dock, Gravity.TOP | Gravity.START, 100, 770, 0);

        buildBrowse();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            int p = n != null && n.getTag() instanceof Integer ? (Integer) n.getTag() : 0;
            dim.animate().alpha(p > 0 ? .5f : 0f).setDuration(300).start();
            if (p == page) return;
            page = p;
            pager.animate().translationY(-p * getResources().getDisplayMetrics().heightPixels).setDuration(350).start();
            glow.animate().translationY(p == 0 ? 0 : -px(290)).setDuration(350).start();
            setGlow(p == 0 ? dockColor : p == 1 ? favColor : deckColor);
        });
        setContentView(root);
        focusDock();
    }

    /** My Favourites (page 2) and the mood deck (page 3), built from the titles whose app is installed. */
    void buildBrowse() {
        List<Browse.Title> all = Browse.installed(this);
        if (all.isEmpty()) return;
        Typeface tf = outfit(700);
        int H = 1080;
        place(text("My Favourites", 34, 0xCCFFFFFF, false), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, H + 60, 0);
        favTitle = text("", 46, Color.WHITE, false);
        favTitle.setTypeface(outfit(300));
        place(favTitle, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, H + 130, 0);
        LinearLayout rows = new LinearLayout(this); // 7 posters to a row
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        LinearLayout row = null;
        int count = 0;
        for (String n : Browse.FAVS) {
            Browse.Title t = Browse.find(all, n);
            if (t == null) continue;
            Browse.Poster v = new Browse.Poster(this, t, tf);
            v.setTag(1);
            v.setAlpha(.8f);
            v.setFocusable(true);
            v.setClickable(true);
            v.setOnFocusChangeListener((x, f) -> {
                ValueAnimator a = ValueAnimator.ofFloat(v.sat, f ? 1f : 0f); // black and white until you move to it
                a.setDuration(200);
                a.addUpdateListener(u -> v.setSat((float) u.getAnimatedValue()));
                a.start();
                v.animate().scaleX(f ? 1.18f : 1f).scaleY(f ? 1.18f : 1f).alpha(f ? 1f : .8f).setDuration(200).start();
                if (f) {
                    favTitle.setText(t.name);
                    favColor = t.color();
                    if (page == 1) setGlow(favColor);
                }
            });
            v.setOnClickListener(x -> Browse.open(this, t));
            if (count++ % 7 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                rows.addView(row, new LinearLayout.LayoutParams(-2, -2));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(190), px(285));
            lp.setMargins(px(15), px(count > 7 ? 32 : 0), px(15), 0);
            row.addView(v, lp);
        }
        place(rows, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, H + 250, 0);

        place(text("Don't have time to browse?", 34, 0xCCFFFFFF, false), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 2 * H + 100, 0);
        List<Browse.Mood> moods = Browse.moods(all);
        deckColor = moods.get(0).color;
        deck = new Browse.Deck(this, moods, tf, s, c -> { deckColor = c; if (page == 2) setGlow(c); });
        deck.setTag(2);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels);
        dp.topMargin = 2 * getResources().getDisplayMetrics().heightPixels;
        pager.addView(deck, dp);
        place(text("◀  Skip          Pick for me  ▶", 28, 0x99FFFFFF, false), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 2 * H + 960, 0);
    }

    void focusDock() {
        dock.post(() -> { if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visit++;
        weather();
        bootJump();
        h.removeCallbacks(rotate);
        h.postDelayed(rotate, WALL_MS);
        wakeHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(rotate); // no background work while another app is on screen
        h.removeCallbacks(sleep);
        if (deck != null) deck.cancel(); // a half-spun reel shouldn't open a title later
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (grid != null) closeApps(); // Home pressed while All apps is open
    }

    @Override
    public void onBackPressed() {
        if (deck != null && deck.busy()) { deck.cancel(); return; }
        if (grid != null) closeApps(); // a launcher has nowhere to go back to
    }

    // ---- screensaver: after IDLE_MS the home layer fades away and only the wallpaper stays ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (asleep) { // the key that wakes it does nothing else
            if (e.getAction() == KeyEvent.ACTION_UP) wakeHome();
            return true;
        }
        h.removeCallbacks(sleep);
        h.postDelayed(sleep, IDLE_MS);
        return super.dispatchKeyEvent(e);
    }

    void sleepHome() {
        asleep = true;
        for (View v : new View[]{home, scrim, grid}) if (v != null) v.animate().alpha(0f).setDuration(1200).start();
    }

    void wakeHome() {
        asleep = false;
        for (View v : new View[]{home, scrim, grid}) if (v != null) v.animate().alpha(1f).setDuration(300).start();
        h.removeCallbacks(sleep);
        h.postDelayed(sleep, IDLE_MS);
    }

    // ---- wallpaper ----

    /** Decodes from assets at about screen width, so a 4K photo costs ~9 MB, not 33 MB. */
    Bitmap loadWall(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open("wall/" + name)) { BitmapFactory.decodeStream(in, null, o); }
            int target = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            o.inSampleSize = Math.max(1, Integer.highestOneBit(o.outWidth / target));
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888; // full colour, no banding in the dark areas
            try (InputStream in = getAssets().open("wall/" + name)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    void nextWall() {
        if (WALLS.length < 2) return;
        wallIdx = (wallIdx + 1) % WALLS.length;
        prefs.edit().putInt("wall", wallIdx).apply();
        final String name = WALLS[wallIdx];
        new Thread(() -> {
            Bitmap b = loadWall(name);
            if (b != null) runOnUiThread(() -> crossfade(b));
        }).start();
    }

    void crossfade(Bitmap b) {
        BitmapDrawable nd = new BitmapDrawable(getResources(), b);
        Drawable old = wall.getDrawable();
        if (old == null) { wall.setImageDrawable(nd); return; }
        TransitionDrawable td = new TransitionDrawable(new Drawable[]{old, nd});
        wall.setImageDrawable(td);
        td.startTransition(900);
        // drop the old bitmap once the fade is done
        h.postDelayed(() -> { if (wall.getDrawable() == td) wall.setImageDrawable(nd); }, 1000);
    }

    // ---- HDMI ----

    /** First connected HDMI input; with any=true falls back to the first HDMI input at all. */
    TvInputInfo hdmiInput(boolean any) {
        TvInputInfo first = null;
        try {
            for (TvInputInfo i : tvm.getTvInputList()) {
                if (i.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (first == null) first = i;
                if (tvm.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) return i;
            }
        } catch (RuntimeException e) { /* TV service not ready yet */ }
        return any ? first : null;
    }

    boolean jumpToHdmi(boolean any) {
        return jump(hdmiInput(any));
    }

    boolean jump(TvInputInfo i) {
        if (i == null) return false;
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("content://android.media.tv/passthrough/" + Uri.encode(i.getId()))));
        return true;
    }

    /** Once per cold boot: jump to the HDMI source if a device is connected. Not on the first run after install. */
    void bootJump() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        boolean first = !prefs.contains("boot");
        if (boot == prefs.getInt("boot", -2)) return;
        prefs.edit().putInt("boot", boot).apply();
        if (!first) tryJump(0);
    }

    // ponytail: gives up after ~15s; add a listener on input state if boxes boot slower than that.
    void tryJump(int n) {
        h.postDelayed(() -> { if (!jumpToHdmi(false) && n < 5) tryJump(n + 1); }, 2500);
    }


    // ---- dock ----

    /** Focus = grow slightly with a short ease. The only focus state anywhere. */
    void grow(View v, float k, Runnable onFocus) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, focused) -> {
            x.animate().scaleX(focused ? k : 1f).scaleY(focused ? k : 1f).setDuration(160).start();
            if (focused && onFocus != null) onFocus.run();
        });
    }

    View tile(String[] t) {
        String label = t[0], kind = t[1];
        View face;
        int size = 110; // logo box in the 1920 grid
        if (kind.indexOf('.') > 0) {
            ImageView iv = new ImageView(this);
            try {
                pm.getApplicationInfo(kind, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
            String[] lg = t[4].split(":"); // name:size
            iv.setImageResource(getResources().getIdentifier("logo_" + lg[0], "drawable", getPackageName()));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            size = Integer.parseInt(lg[1]);
            face = iv;
        } else {
            face = new Glyph(this, kind);
        }
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(px(size), px(size), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        fp.topMargin = px((150 - size) / 2); // logos centre in the top 150, the name sits under that
        f.addView(face, fp);
        TextView name = text(label, 26, 0xF2FFFFFF, false); // the app's name, under the focused logo
        name.setTypeface(outfit(500));
        name.setAlpha(0f);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        np.topMargin = px(150);
        f.addView(name, np);
        String[] lines = t[3].split("\\|");
        grow(f, 1.18f, () -> {
            if (curLabel != null && curLabel != name) curLabel.animate().alpha(0f).setDuration(120).start();
            curLabel = name;
            name.animate().alpha(1f).setDuration(220).start();
            tagline.setText(lines[visit % lines.length]);
            tagline.setAlpha(0f);
            tagline.animate().alpha(1f).setDuration(220).start();
            setGlow(dockColor = 0xFF000000 | Integer.parseInt(t[2], 16));
        });
        f.setOnClickListener(v -> click(kind));
        return f;
    }

    void setGlow(int target) {
        ValueAnimator a = ValueAnimator.ofArgb(glowColor, target);
        a.setDuration(250);
        a.addUpdateListener(x -> {
            glowColor = (int) x.getAnimatedValue();
            int c = glowColor & 0xFFFFFF;
            glowBg.setColors(new int[]{0x55000000 | c, c});
        });
        a.start();
    }

    void click(String kind) {
        switch (kind) {
            case "inputs":
                if (!jumpToHdmi(true)) toast("No HDMI input found");
                break;
            case "apps":
                showApps();
                break;
            case "wifi":
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "settings":
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                break;
            default:
                Intent i = pm.getLeanbackLaunchIntentForPackage(kind);
                if (i == null) i = pm.getLaunchIntentForPackage(kind);
                if (i == null) toast("Can't open " + kind); else startActivity(i);
        }
    }

    // ---- more apps ----

    void showApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(q, 0));
        List<String> mine = new ArrayList<>(); // dock apps are already on the home row
        for (String[] t : TILES) mine.add(t[1]);
        for (int i = apps.size() - 1; i >= 0; i--) if (mine.contains(apps.get(i).activityInfo.packageName)) apps.remove(i);
        Collections.sort(apps, (a, c) -> a.loadLabel(pm).toString().compareToIgnoreCase(c.loadLabel(pm).toString()));
        grid = new GridView(this);
        grid.setBackgroundColor(0xF2000000);
        grid.setNumColumns(6);
        grid.setPadding(px(96), px(96), px(96), px(60));
        grid.setHorizontalSpacing(px(24));
        grid.setVerticalSpacing(px(24));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                ResolveInfo ri = apps.get(i);
                LinearLayout c = new LinearLayout(MainActivity.this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(px(8), px(10), px(8), px(10));
                ImageView iv = new ImageView(MainActivity.this);
                Drawable d = ri.activityInfo.loadBanner(pm);
                iv.setImageDrawable(d != null ? d : ri.loadIcon(pm));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                c.addView(iv, new LinearLayout.LayoutParams(px(200), px(112)));
                TextView t = text(ri.loadLabel(pm).toString(), 24, Color.WHITE, false);
                t.setSingleLine();
                c.addView(t);
                return c;
            }
        });
        final View[] sel = {null};
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int i, long id) {
                if (sel[0] != null) sel[0].animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                sel[0] = v;
                if (v != null) v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).start();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        grid.setOnItemClickListener((AdapterView<?> a, View v, int i, long id) -> {
            ResolveInfo ri = apps.get(i);
            startActivity(Intent.makeMainActivity(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        home.setVisibility(View.INVISIBLE); // invisible views can't take focus
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        grid.requestFocus();
    }

    void closeApps() {
        root.removeView(grid);
        grid = null;
        home.setVisibility(View.VISIBLE);
        moreApps.requestFocus();
    }

    // ---- weather: just an icon and the temperature, fetched only while the home screen is showing ----

    void weather() {
        String cached = prefs.getString("wxJson", null);
        if (cached != null) showWx(cached);
        if (cached != null && System.currentTimeMillis() - prefs.getLong("wxTs", 0) < WX_TTL) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(WX_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                String body = new Scanner(c.getInputStream()).useDelimiter("\\A").next();
                new JSONObject(body).getJSONObject("current"); // reject garbage before caching
                prefs.edit().putString("wxJson", body).putLong("wxTs", System.currentTimeMillis()).apply();
                runOnUiThread(() -> showWx(body));
            } catch (Exception e) { /* offline: keep the cached value */ }
        }).start();
    }

    void showWx(String json) {
        try {
            JSONObject cur = new JSONObject(json).getJSONObject("current");
            temp.setText(Math.round(cur.getDouble("temperature_2m")) + "°C");
            wxIcon.kind = iconKind(cur.getInt("weather_code"));
            wxIcon.invalidate();
        } catch (Exception e) { /* bad cache: leave it blank */ }
    }

    static int iconKind(int c) { return c <= 1 ? 0 : c == 2 ? 1 : (c == 3 || c == 45 || c == 48) ? 2 : 3; }

    /** 0 sun, 1 partly cloudy, 2 cloud, 3 rain. Drawn in a 48x48 box. */
    static class WxIcon extends View {
        int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        WxIcon(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            c.scale(getWidth() / 48f, getWidth() / 48f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.WHITE);
            Path cloud = null;
            if (kind >= 1) { // outline of the union of a box and two circles
                float dy = kind == 3 ? -4 : 0;
                cloud = new Path();
                cloud.addRoundRect(6, 22 + dy, 42, 38 + dy, 8, 8, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(20, 25 + dy, 9, Path.Direction.CW);
                cloud.op(b, Path.Op.UNION);
                b.reset();
                b.addCircle(30, 21 + dy, 11, Path.Direction.CW);
                cloud.op(b, Path.Op.UNION);
            }
            if (kind <= 1) { // sun: circle, plus rays when it is on its own
                Path sun = new Path();
                float cx = kind == 0 ? 24 : 17, cy = kind == 0 ? 24 : 16, r = kind == 0 ? 8 : 7;
                sun.addCircle(cx, cy, r, Path.Direction.CW);
                if (kind == 0) for (int a = 0; a < 8; a++) {
                    double t = a * Math.PI / 4;
                    sun.moveTo(cx + (float) Math.cos(t) * 12.5f, cy + (float) Math.sin(t) * 12.5f);
                    sun.lineTo(cx + (float) Math.cos(t) * 17, cy + (float) Math.sin(t) * 17);
                }
                if (cloud != null) sun.op(cloud, Path.Op.DIFFERENCE); // the cloud hides what is behind it
                c.drawPath(sun, p);
            }
            if (cloud != null) c.drawPath(cloud, p);
            if (kind == 3) {
                c.drawLine(16, 38, 13, 45, p);
                c.drawLine(25, 38, 22, 45, p);
                c.drawLine(34, 38, 31, 45, p);
            }
        }
    }

    /** Dock glyphs for tiles that aren't apps. Drawn in a 112 design box. */
    static class Glyph extends View {
        final String kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context c, String kind) { super(c); this.kind = kind; }
        @Override protected void onDraw(Canvas c) {
            float k = getHeight() / 112f;
            c.translate(getWidth() / 2f, getHeight() / 2f);
            c.scale(k, k);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            if (kind.startsWith("hdmi")) {
                c.translate(-30, -30);
                p.setStrokeWidth(5);
                Path q = new Path();
                q.moveTo(6, 8); q.lineTo(62, 8); q.lineTo(62, 26); q.lineTo(54, 38); q.lineTo(14, 38); q.lineTo(6, 26); q.close();
                c.drawPath(q, p);
                for (int x = 20; x <= 50; x += 10) c.drawLine(x, 18, x, 26, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(26);
                p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                c.drawText(kind.substring(4), 34, 68, p); // port number under the plug
            } else if (kind.equals("settings")) {
                c.scale(1.35f, 1.35f);
                c.translate(-20, -20);
                p.setStrokeWidth(3.6f);
                c.drawCircle(20, 20, 7, p);
                float[][] sp = {{20, 4, 20, 9}, {20, 31, 20, 36}, {4, 20, 9, 20}, {31, 20, 36, 20},
                        {8.7f, 8.7f, 12.2f, 12.2f}, {27.8f, 27.8f, 31.3f, 31.3f}, {8.7f, 31.3f, 12.2f, 27.8f}, {27.8f, 12.2f, 31.3f, 8.7f}};
                for (float[] l : sp) c.drawLine(l[0], l[1], l[2], l[3], p);
            } else if (kind.equals("apps")) {
                p.setStyle(Paint.Style.FILL);
                for (int x : new int[]{-34, 4}) for (int y : new int[]{-34, 4}) c.drawRoundRect(x, y, x + 30, y + 30, 7, 7, p);
            } else { // wifi: three arcs and a dot
                p.setStrokeWidth(7);
                for (int r : new int[]{20, 38, 56}) c.drawArc(new RectF(-r, 32 - r, r, 32 + r), 225, 90, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(0, 32, 6, p);
            }
        }
    }


    // ---- view helpers ----

    final java.util.HashMap<Integer, Typeface> faces = new java.util.HashMap<>();

    /** Outfit (bundled in assets/fonts) at a weight from 100 to 900. */
    Typeface outfit(int wght) {
        Typeface t = faces.get(wght);
        if (t == null) {
            t = new Typeface.Builder(getAssets(), "fonts/Outfit.ttf").setFontVariationSettings("'wght' " + wght).build();
            if (t == null) t = Typeface.DEFAULT;
            faces.put(wght, t);
        }
        return t;
    }

    void styleText(TextView v, float size, int color, boolean bold) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        v.setTextColor(color);
        v.setTypeface(outfit(bold ? 600 : 400));
    }

    TextView text(String t, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        styleText(v, size, color, bold);
        return v;
    }

    /** Add to the home layer with design-pixel margins (left/top/right). */
    void place(View v, int gravity, int l, int t, int r) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        lp.setMargins(px(l), px(t), px(r), 0);
        pager.addView(v, lp);
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
