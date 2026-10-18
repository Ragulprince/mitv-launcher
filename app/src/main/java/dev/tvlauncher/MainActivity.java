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
import android.graphics.Outline;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class MainActivity extends Activity {
    // label, kind: "hdmi1" | "hdmi2" | "apps" | "wall" | "settings" | package name. Reorder or edit freely.
    static final String[][] TILES = {
            {"HDMI 1", "hdmi1"}, {"HDMI 2", "hdmi2"}, {"Netflix", "com.netflix.ninja"}, {"Hotstar", "in.startv.hotstar"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom"}, {"YouTube", "com.google.android.youtube.tv"},
            {"Sony LIV", "com.sonyliv"}, {"Apple TV", "com.apple.atve.androidtv.appletv"},
            {"Play Store", "com.android.vending"}, {"Settings", "settings"}, {"All apps", "apps"},
            {"Wallpaper", "wall"}};
    // Files in assets/wall/. Drop more in and list them here.
    static final String[] WALLS = {"rb21.jpg", "max.jpg", "wet.jpg", "night.jpg"};
    static final long WALL_MS = 60 * 1000L;
    // ponytail: fixed coordinates for one city; add a setting if you ever move.
    static final String CITY = "Kallakurichi";
    static final String WX_URL = "https://api.open-meteo.com/v1/forecast?latitude=11.74&longitude=78.96"
            + "&current=temperature_2m,weather_code"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
            + "&timezone=auto&forecast_days=4";
    static final long WX_TTL = 30 * 60 * 1000L;
    static final int ACCENT = 0xFFE10600;

    float s;
    PackageManager pm;
    SharedPreferences prefs;
    TvInputManager tvm;
    final Handler h = new Handler();
    FrameLayout root, home;
    ImageView wall;
    int wallIdx;
    LinearLayout dock;
    TextView dockLabel, temp, desc, riseTv, setTv;
    WxIcon wxIcon;
    final WxIcon[] fIcon = new WxIcon[3];
    final TextView[] fDay = new TextView[3], fTemp = new TextView[3], fRain = new TextView[3];
    SunView sun;
    GridView grid;

    final Runnable rotate = new Runnable() {
        @Override public void run() {
            nextWall();
            h.postDelayed(this, WALL_MS);
        }
    };

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
        wallIdx = prefs.getInt("wall", 0) % WALLS.length;
        Bitmap first = loadWall(WALLS[wallIdx]);
        if (first != null) wall.setImageDrawable(new BitmapDrawable(getResources(), first));
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB8000000,
                0x61000000, 0x00000000, 0x00000000, 0xC7000000});
        sg.setColors(new int[]{0xB8000000, 0x61000000, 0x00000000, 0x00000000, 0xC7000000},
                new float[]{0f, .22f, .40f, .64f, 1f});
        scrim.setBackground(sg);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        home = new FrameLayout(this);
        home.setClipChildren(false);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        // clock + date
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        styleText(clock, 132, Color.WHITE, true);
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        styleText(date, 30, 0xC7FFFFFF, false);
        left.addView(clock);
        left.addView(date);
        place(left, Gravity.TOP | Gravity.START, 96, 52, 0);

        // widgets
        LinearLayout cards = new LinearLayout(this);
        cards.addView(weatherCard(), new LinearLayout.LayoutParams(px(500), -1));
        LinearLayout.LayoutParams sunLp = new LinearLayout.LayoutParams(px(250), -1);
        sunLp.leftMargin = px(24);
        cards.addView(sunCard(), sunLp);
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(px(360), -1);
        calLp.leftMargin = px(24);
        cards.addView(calendarCard(), calLp);
        place(cards, Gravity.TOP | Gravity.END, 0, 52, 96);

        // dock
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false);
        dock.setPadding(px(18), px(28), px(18), px(28));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0x850C0E12);
        dockBg.setCornerRadius(px(56));
        dockBg.setStroke(Math.max(1, px(1)), 0x29FFFFFF);
        dock.setBackground(dockBg);
        for (String[] t : TILES) {
            View v = tile(t[0], t[1]);
            if (v == null) continue; // app not installed
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(112), px(112));
            lp.setMargins(px(10), 0, px(10), 0);
            dock.addView(v, lp);
        }
        place(dock, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 850, 0);
        dockLabel = text("", 34, Color.WHITE, true);
        dockLabel.setLetterSpacing(0.08f);
        place(dockLabel, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 1030, 0);

        setContentView(root);
        dock.post(() -> {
            int n = dock.getChildCount();
            if (n > 0) dock.getChildAt(Math.min(1, n - 1)).requestFocus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        weather();
        bootJump();
        h.removeCallbacks(rotate);
        h.postDelayed(rotate, WALL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(rotate); // no wallpaper work while another app is on screen
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (grid != null) closeApps(); // Home pressed while All apps is open
    }

    @Override
    public void onBackPressed() {
        if (grid != null) closeApps(); // a launcher has nowhere to go back to
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
            try (InputStream in = getAssets().open("wall/" + name)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    void nextWall() {
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

    /** A specific port. ponytail: matches this TV's "Hdmi<N>InputService" ids; other boxes may name them differently. */
    boolean jumpToPort(int n) {
        try {
            for (TvInputInfo i : tvm.getTvInputList())
                if (i.getType() == TvInputInfo.TYPE_HDMI && i.getId().contains("Hdmi" + n + "InputService")) return jump(i);
        } catch (RuntimeException e) { /* TV service not ready */ }
        return false;
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

    View tile(String label, String kind) {
        boolean app = kind.indexOf('.') > 0;
        boolean hasBanner = app || kind.equals("settings");
        String pkg = kind.equals("settings") ? "com.android.tv.settings" : kind;
        Drawable banner = null;
        Drawable icon = null;
        try {
            if (hasBanner) banner = pm.getApplicationBanner(pkg);
            if (app) icon = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            if (app) return null;
        }
        FrameLayout f = new FrameLayout(this);
        View face;
        if (app) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            face = iv;
            f.setBackgroundColor(pkg.equals("com.android.vending") ? 0xFFF4F4F4 : 0xFF202024);
        } else {
            face = new Glyph(this, kind);
            f.setBackgroundColor(kind.startsWith("hdmi") ? 0xFF23232B : kind.equals("settings") ? 0xFF3A3D40
                    : kind.equals("apps") ? 0xFF2A2A30 : 0xFF2D2A40);
        }
        f.addView(face, new FrameLayout.LayoutParams(-1, -1));
        ImageView ban = null;
        if (banner != null) {
            ban = new ImageView(this);
            ban.setImageDrawable(banner);
            ban.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ban.setVisibility(View.GONE);
            f.addView(ban, new FrameLayout.LayoutParams(-1, -1));
        }
        f.setFocusable(true);
        f.setClickable(true);
        f.setClipToOutline(true);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(26)); }
        });
        GradientDrawable ring = new GradientDrawable();
        ring.setStroke(px(8), Color.WHITE);
        ring.setCornerRadius(px(26));
        final ImageView fban = ban;
        f.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f)
                    .translationY(focused ? -px(12) : 0).setDuration(120).start();
            v.setTranslationZ(focused ? px(12) : 0);
            v.setForeground(focused ? ring : null);
            if (focused) dockLabel.setText(label.toUpperCase());
            if (fban != null) { // the focused app opens into its banner
                fban.setVisibility(focused ? View.VISIBLE : View.GONE);
                face.setVisibility(focused ? View.GONE : View.VISIBLE);
                ValueAnimator a = ValueAnimator.ofInt(v.getLayoutParams().width, px(focused ? 200 : 112));
                a.setDuration(120);
                a.addUpdateListener(x -> {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.width = (int) x.getAnimatedValue();
                    v.setLayoutParams(lp);
                });
                a.start();
            }
        });
        f.setOnClickListener(v -> click(kind, pkg));
        return f;
    }

    void click(String kind, String pkg) {
        switch (kind) {
            case "hdmi1":
            case "hdmi2":
                if (!jumpToPort(kind.charAt(4) - '0')) toast("No " + kind.toUpperCase() + " input found");
                break;
            case "apps":
                showApps();
                break;
            case "wall":
                h.removeCallbacks(rotate);
                h.postDelayed(rotate, WALL_MS);
                nextWall();
                break;
            case "settings":
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                break;
            default:
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) toast("Can't open " + pkg); else startActivity(i);
        }
    }

    // ---- all apps ----

    void showApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(q, 0));
        Collections.sort(apps, (a, c) -> a.loadLabel(pm).toString().compareToIgnoreCase(c.loadLabel(pm).toString()));
        grid = new GridView(this);
        grid.setBackgroundColor(0xF2000000);
        grid.setNumColumns(6);
        grid.setPadding(px(96), px(96), px(96), px(60));
        grid.setHorizontalSpacing(px(24));
        grid.setVerticalSpacing(px(24));
        grid.setSelector(new ColorDrawable(0x33FFFFFF));
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
        int n = dock.getChildCount();
        if (n > 0) dock.getChildAt(Math.min(1, n - 1)).requestFocus();
    }

    // ---- widgets ----

    LinearLayout card(int padH, int padV) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(padH), px(padV), px(padH), px(padV));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x8C0E1016);
        g.setCornerRadius(px(28));
        g.setStroke(Math.max(1, px(1)), 0x29FFFFFF);
        c.setBackground(g);
        return c;
    }

    View weatherCard() {
        LinearLayout c = card(26, 22);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = new WxIcon(this);
        top.addView(wxIcon, new LinearLayout.LayoutParams(px(64), px(64)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(px(18), 0, 0, 0);
        temp = text("--", 56, Color.WHITE, true);
        desc = text(CITY, 22, 0xC7FFFFFF, false);
        tx.addView(temp);
        tx.addView(desc);
        top.addView(tx);
        c.addView(top);
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = px(18);
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(px(6), px(12), px(6), px(12));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(0x14FFFFFF);
            cg.setCornerRadius(px(18));
            cell.setBackground(cg);
            fDay[i] = text("", 20, 0xC7FFFFFF, false);
            fIcon[i] = new WxIcon(this);
            fTemp[i] = text("", 22, Color.WHITE, true);
            fRain[i] = text("", 18, 0xFF7CCFF2, false);
            cell.addView(fDay[i]);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(36), px(36));
            il.topMargin = px(4);
            il.bottomMargin = px(4);
            cell.addView(fIcon[i], il);
            cell.addView(fTemp[i]);
            cell.addView(fRain[i]);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.setMargins(px(6), 0, px(6), 0);
            row.addView(cell, cl);
        }
        c.addView(row, rowLp);
        return c;
    }

    View sunCard() {
        LinearLayout c = card(24, 22);
        c.addView(text("Sun", 22, 0xC7FFFFFF, false));
        sun = new SunView(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(200), px(78));
        sl.topMargin = px(14);
        c.addView(sun, sl);
        LinearLayout row = new LinearLayout(this);
        riseTv = text("--:--", 22, Color.WHITE, false);
        setTv = text("--:--", 22, Color.WHITE, false);
        setTv.setGravity(Gravity.END);
        LinearLayout a = new LinearLayout(this), b = new LinearLayout(this);
        a.setOrientation(LinearLayout.VERTICAL);
        b.setOrientation(LinearLayout.VERTICAL);
        a.addView(text("Sunrise", 17, 0x99FFFFFF, false));
        a.addView(riseTv);
        TextView sl2 = text("Sunset", 17, 0x99FFFFFF, false);
        sl2.setGravity(Gravity.END);
        b.addView(sl2);
        b.addView(setTv);
        row.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = px(14);
        c.addView(row, rl);
        return c;
    }

    View calendarCard() {
        LinearLayout c = card(24, 20);
        c.addView(new CalView(this, s), new LinearLayout.LayoutParams(px(312), px(270)));
        return c;
    }

    // ---- weather ----

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
            JSONObject j = new JSONObject(json);
            JSONObject cur = j.getJSONObject("current");
            int code = cur.getInt("weather_code");
            temp.setText(Math.round(cur.getDouble("temperature_2m")) + "°C");
            desc.setText(wmoText(code) + " · " + CITY);
            wxIcon.kind = iconKind(code);
            wxIcon.invalidate();
            JSONObject d = j.getJSONObject("daily");
            JSONArray days = d.getJSONArray("time"), hi = d.getJSONArray("temperature_2m_max"),
                    lo = d.getJSONArray("temperature_2m_min"), rain = d.getJSONArray("precipitation_probability_max"),
                    wc = d.getJSONArray("weather_code");
            for (int i = 0; i < 3 && i + 1 < days.length(); i++) {
                fDay[i].setText(LocalDate.parse(days.getString(i + 1)).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                fIcon[i].kind = iconKind(wc.getInt(i + 1));
                fIcon[i].invalidate();
                fTemp[i].setText(Math.round(hi.getDouble(i + 1)) + "° " + Math.round(lo.getDouble(i + 1)) + "°");
                fRain[i].setText(rain.getInt(i + 1) + "%");
            }
            String rise = d.getJSONArray("sunrise").getString(0), set = d.getJSONArray("sunset").getString(0);
            riseTv.setText(rise.substring(11, 16));
            setTv.setText(set.substring(11, 16));
            sun.rise = mins(rise);
            sun.set = mins(set);
            sun.invalidate();
        } catch (Exception e) { /* bad cache: leave the placeholders */ }
    }

    static int mins(String iso) { // "2026-10-18T06:02"
        return Integer.parseInt(iso.substring(11, 13)) * 60 + Integer.parseInt(iso.substring(14, 16));
    }

    static int iconKind(int c) { return c <= 1 ? 0 : c == 2 ? 1 : (c == 3 || c == 45 || c == 48) ? 2 : 3; }

    static String wmoText(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c < 60) return "Drizzle";
        if (c < 70) return "Rain";
        if (c < 80) return "Snow";
        if (c < 90) return "Showers";
        return "Thunderstorm";
    }

    /** 0 sun, 1 partly cloudy, 2 cloud, 3 rain. Drawn in a 48x48 box. */
    static class WxIcon extends View {
        int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        WxIcon(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float k = getWidth() / 48f;
            c.scale(k, k);
            if (kind <= 1) {
                p.setColor(0xFFFFC83D);
                if (kind == 0) c.drawCircle(24, 24, 10, p); else c.drawCircle(18, 17, 8, p);
            }
            if (kind >= 1) {
                float dy = kind == 3 ? -4 : 0;
                p.setColor(Color.WHITE);
                c.drawRoundRect(6, 22 + dy, 42, 38 + dy, 8, 8, p);
                c.drawCircle(20, 25 + dy, 9, p);
                c.drawCircle(30, 21 + dy, 11, p);
            }
            if (kind == 3) {
                p.setColor(0xFF4CC3F0);
                p.setStrokeWidth(3);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(16, 38, 13, 45, p);
                c.drawLine(25, 38, 22, 45, p);
                c.drawLine(34, 38, 31, 45, p);
            }
        }
    }

    /** Day arc with the sun placed by the current time. 200x78 design box. */
    static class SunView extends View {
        int rise = 6 * 60, set = 18 * 60;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        SunView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            c.scale(getWidth() / 200f, getHeight() / 78f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x59FFFFFF);
            c.drawArc(new RectF(10, 20, 190, 124), 180, 180, false, p);
            p.setStrokeWidth(2);
            p.setColor(0x4DFFFFFF);
            c.drawLine(0, 72, 200, 72, p);
            Calendar n = Calendar.getInstance();
            int now = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE);
            float f = Math.max(0f, Math.min(1f, (now - rise) / (float) Math.max(1, set - rise)));
            double th = Math.PI * (1 - f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(now >= rise && now <= set ? 0xFFFFC83D : 0x66FFC83D); // dim at night
            c.drawCircle((float) (100 + 90 * Math.cos(th)), (float) (72 - 52 * Math.sin(th)), 11, p);
        }
    }

    /** Month grid, Sunday first, today circled. */
    static class CalView extends View {
        final float s;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CalView(Context c, float s) { super(c); this.s = s; }
        void txt(Canvas c, String t, float cx, float cy, float size, int col, boolean bold) {
            p.setTextSize(size * s);
            p.setColor(col);
            p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2, p);
        }
        @Override protected void onDraw(Canvas c) {
            Calendar now = Calendar.getInstance();
            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int lead = first.get(Calendar.DAY_OF_WEEK) - 1;
            int total = now.getActualMaximum(Calendar.DAY_OF_MONTH), today = now.get(Calendar.DAY_OF_MONTH);
            p.setTextAlign(Paint.Align.LEFT);
            txt0(c, now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.ENGLISH) + " " + now.get(Calendar.YEAR));
            p.setTextAlign(Paint.Align.CENTER);
            float cw = getWidth() / 7f;
            String wd = "SMTWTFS";
            for (int i = 0; i < 7; i++) txt(c, wd.substring(i, i + 1), cw * (i + .5f), 64 * s, 17, 0x8CFFFFFF, false);
            for (int d = 1; d <= total; d++) {
                int idx = lead + d - 1;
                float cx = cw * (idx % 7 + .5f), cy = (98 + 34 * (idx / 7)) * s;
                if (d == today) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ACCENT);
                    c.drawCircle(cx, cy, 17 * s, p);
                }
                txt(c, String.valueOf(d), cx, cy, 20, d == today ? Color.WHITE : 0xE6FFFFFF, d == today);
            }
        }
        void txt0(Canvas c, String t) {
            p.setTextSize(24 * s);
            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(t, 0, 20 * s - (fm.ascent + fm.descent) / 2, p);
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
                c.scale(0.92f, 0.92f);
                c.translate(-26, -26);
                p.setStyle(Paint.Style.FILL);
                for (int x : new int[]{3, 29}) for (int y : new int[]{3, 29}) c.drawRoundRect(x, y, x + 20, y + 20, 5, 5, p);
            } else { // wallpaper
                c.scale(1.35f, 1.35f);
                c.translate(-20, -20);
                p.setStrokeWidth(3.2f);
                c.drawRoundRect(5, 7, 35, 33, 5, 5, p);
                c.drawCircle(14, 16, 3, p);
                Path q = new Path();
                q.moveTo(6, 29); q.rLineTo(9, -8); q.rLineTo(7, 6); q.rLineTo(5, -4); q.rLineTo(8, 7);
                c.drawPath(q, p);
            }
        }
    }

    // ---- view helpers ----

    void styleText(TextView v, float size, int color, boolean bold) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-condensed" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
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
        home.addView(v, lp);
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
