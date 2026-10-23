package dev.tvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

/** My Favourites + the mood-card deck. No image files: every poster is drawn from the title, a colour and the app's logo. */
class Browse {
    // My Favourites, left to right, 7 to a row. Names come from assets/titles.tsv (tools/build_titles.py).
    static final String[] FAVS = {"Breaking Bad", "Better Call Saul", "Money Heist", "Dark", "The Boys", "Panchayat",
            "Drive to Survive", "Master", "Bigil", "Mersal", "Sarkar", "Viswasam", "Veeram", "Thunivu"};
    // Moods in deck order: name | colour | titles. "Anything at all" is added last and holds everything.
    static final String[][] MOODS = {
            {"Thalapathy mass", "F2B84B", "Master,Bigil,Mersal,Sarkar,Varisu,Theri,Thuppakki,Leo,Beast,GOAT"},
            {"Thala mass", "E5484D", "Viswasam,Veeram,Vivegam,Yennai Arindhaal,Thunivu"},
            {"Edge of your seat", "D9603B", "Money Heist,Dark,Lupin,Narcos,Squid Game,Sacred Games,Fallout,Reacher"},
            {"Capes and chaos", "9B6BFF", "The Boys,Invincible,Loki,Wednesday,The Umbrella Academy,Avengers: Endgame,Black Panther,Deadpool & Wolverine,Thor: Ragnarok"},
            {"Gotham after dark", "2F9E8F", "Breaking Bad,Better Call Saul,Ozark,Peaky Blinders,Mindhunter"},
            {"Mind games", "4C8DFF", "Dark,Black Mirror,Mindhunter,Lupin,Andor"},
            {"Desi originals", "3FB27F", "Panchayat,Mirzapur,The Family Man,Paatal Lok,Sacred Games"},
            {"Epic worlds", "C28A3C", "Game of Thrones,Shogun,The Mandalorian,Andor,The Witcher,Stranger Things"}};

    /** One title: its app, the app's own deep link, and a real poster from assets/posters (or a drawn one if that is missing). */
    static class Title {
        final String name, app, link, file;
        final android.content.res.AssetManager assets;
        Drawable logo;
        Bitmap bmp;
        boolean tried;
        int color;
        Title(android.content.res.AssetManager am, String[] r) { assets = am; name = r[0]; app = r[1]; link = r[2]; file = r[3]; }

        /** Decoded on first use, so only the posters you look at cost memory. */
        Bitmap poster() {
            if (!tried) {
                tried = true;
                try (java.io.InputStream in = assets.open("posters/" + file)) { bmp = BitmapFactory.decodeStream(in); } catch (Exception e) { /* drawn poster instead */ }
                if (bmp != null) {
                    float[] hsv = new float[3];
                    Color.colorToHSV(Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0), hsv);
                    hsv[1] = Math.max(hsv[1], .55f); hsv[2] = Math.max(hsv[2], .85f); // the poster's main colour, made glow-worthy
                    color = Color.HSVToColor(hsv);
                }
            }
            return bmp;
        }

        int color() { poster(); return color != 0 ? color : (color = Color.HSVToColor(new float[]{(name.hashCode() & 0x7fffffff) % 360, .6f, .85f})); }
    }

    static class Mood {
        final String name;
        final int color;
        final List<Title> titles = new ArrayList<>();
        Mood(String name, int color) { this.name = name; this.color = color; }
    }

    /** Titles from assets/titles.tsv whose app is installed (the rest are skipped). */
    static List<Title> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Title> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getAssets().open("titles.tsv")))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                try {
                    pm.getApplicationInfo(f[1], 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                Title t = new Title(c.getAssets(), f);
                String logo = f[1].equals("com.netflix.ninja") ? "logo_netflix" : f[1].contains("amazon") ? "logo_prime" : "logo_hotstar";
                int res = c.getResources().getIdentifier(logo, "drawable", c.getPackageName());
                t.logo = res == 0 ? null : c.getDrawable(res).mutate();
                out.add(t);
            }
        } catch (java.io.IOException e) { /* no titles file: the sections stay empty */ }
        return out;
    }

    static Title find(List<Title> all, String name) {
        for (Title t : all) if (t.name.equals(name)) return t;
        return null;
    }

    static List<Mood> moods(List<Title> all) {
        List<Mood> out = new ArrayList<>();
        for (String[] m : MOODS) {
            Mood mood = new Mood(m[0], 0xFF000000 | Integer.parseInt(m[1], 16));
            for (String n : m[2].split(",")) { Title t = find(all, n); if (t != null) mood.titles.add(t); }
            if (!mood.titles.isEmpty()) out.add(mood);
        }
        Mood any = new Mood("Anything at all", 0xFFB8C0CC);
        any.titles.addAll(all);
        if (!all.isEmpty()) out.add(any);
        return out;
    }

    static void open(Activity a, Title t) {
        PackageManager pm = a.getPackageManager();
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t.link)).setPackage(t.app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (RuntimeException e) { /* app doesn't take the link: fall through to just opening it */ }
        Intent i = pm.getLeanbackLaunchIntentForPackage(t.app);
        if (i == null) i = pm.getLaunchIntentForPackage(t.app);
        if (i != null) a.startActivity(i);
    }

    static int mix(int a, int b, float k) {
        return Color.rgb((int) (Color.red(a) * (1 - k) + Color.red(b) * k),
                (int) (Color.green(a) * (1 - k) + Color.green(b) * k),
                (int) (Color.blue(a) * (1 - k) + Color.blue(b) * k));
    }

    /** A poster at the origin, w x h: colour wash, faint initial, app logo top left, title bottom left. */
    static void poster(Canvas c, Title t, float w, float h, Typeface tf) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = w * .07f;
        Bitmap b = t.poster();
        if (b != null) { // the real poster, cropped to fit, with a dark foot so the app badge and edges sit well
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            float k = Math.max(w / b.getWidth(), h / b.getHeight());
            float sw = w / k, sh = h / k;
            c.drawBitmap(b, new android.graphics.Rect((int) ((b.getWidth() - sw) / 2), (int) ((b.getHeight() - sh) / 2),
                    (int) ((b.getWidth() + sw) / 2), (int) ((b.getHeight() + sh) / 2)), new android.graphics.RectF(0, 0, w, h), p);
            p.setShader(new LinearGradient(0, h * .72f, 0, h, 0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * .72f, w, h, p);
            p.setShader(null);
            c.restore();
            if (t.logo != null) {
                int m = (int) (w * .07f), n = (int) (w * .15f);
                t.logo.setBounds((int) w - m - n, (int) h - m - n, (int) w - m, (int) h - m);
                t.logo.draw(c);
            }
            return;
        }
        int col = t.color();
        p.setShader(new LinearGradient(0, 0, w * .3f, h, col, mix(col, Color.BLACK, .8f), Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, r, r, p);
        p.setShader(null);
        p.setTypeface(tf);
        p.setColor(0x26FFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * .62f);
        c.drawText(t.name.substring(0, 1), w / 2, h * .62f, p);
        if (t.logo != null) {
            int m = (int) (w * .09f), n = (int) (w * .2f);
            t.logo.setBounds(m, m, m + n, m + n);
            t.logo.draw(c);
        }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(tf);
        tp.setTextSize(w * .125f);
        StaticLayout sl = StaticLayout.Builder.obtain(t.name, 0, t.name.length(), tp, (int) (w * .82f))
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        c.save();
        c.translate(w * .09f, h - w * .09f - sl.getHeight());
        sl.draw(c);
        c.restore();
    }

    /** A poster that is black and white until you move to it (sat 0 -> 1). */
    static class Poster extends View {
        Title t;
        final Typeface tf;
        final Paint fp = new Paint();
        float sat;
        Poster(Context c, Title t, Typeface tf) { super(c); this.t = t; this.tf = tf; }
        void setSat(float s) { sat = s; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            boolean grey = sat < .999f;
            if (grey) {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(sat);
                fp.setColorFilter(new ColorMatrixColorFilter(m));
                c.saveLayer(0, 0, getWidth(), getHeight(), fp);
            }
            poster(c, t, getWidth(), getHeight(), tf);
            if (grey) c.restore();
        }
    }

    /** The big card: the mood colour fading to near-black, its first three posters fanned out, name and count. */
    static class MoodCard extends View {
        Mood m;
        final Typeface tf;
        MoodCard(Context c, Typeface tf) { super(c); this.tf = tf; }
        void set(Mood m) { this.m = m; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            if (m == null) return;
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, m.color, 0xFF0A0A0C, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, h, w * .06f, w * .06f, p);
            p.setShader(null);
            float pw = w * .36f, ph = pw * 1.5f;
            int[] order = {0, 2, 1}; // sides first, the middle one on top
            float[] cx = {.25f, .5f, .75f}, rot = {-9f, 0f, 9f}, cy = {.37f, .34f, .37f};
            for (int k : order) {
                if (k >= m.titles.size()) continue;
                c.save();
                c.translate(w * cx[k], h * cy[k]);
                c.rotate(rot[k]);
                c.translate(-pw / 2, -ph / 2);
                poster(c, m.titles.get(k), pw, ph, tf);
                c.restore();
            }
            p.setTypeface(tf);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.WHITE);
            p.setTextSize(w * .085f);
            c.drawText(m.name, w / 2, h * .8f, p);
            p.setColor(0xB3FFFFFF);
            p.setTextSize(w * .05f);
            c.drawText(m.titles.size() + (m.titles.size() == 1 ? " title" : " titles"), w / 2, h * .8f + w * .08f, p);
        }
    }

    /**
     * Left = nope: the card flies off and the next mood comes up. Right or OK = yes: posters spin like a reel, slow
     * down, land on a random title, count down from 3 and open it. In the countdown OK opens now, Right spins again,
     * Left or Back cancels.
     */
    static class Deck extends FrameLayout {
        static final int CARD = 0, REEL = 1, COUNT = 2;
        final Activity act;
        final List<Mood> moods;
        final IntConsumer onColor;
        final Handler h = new Handler();
        final Random rnd = new Random();
        final MoodCard top, back1, back2;
        final Poster reel;
        final TextView msg;
        final View bar, track;
        final float s;
        int idx, mode;
        Title pick;

        Deck(Activity a, List<Mood> moods, Typeface tf, float s, IntConsumer onColor) {
            super(a);
            act = a; this.moods = moods; this.s = s; this.onColor = onColor;
            setFocusable(true);
            setClipChildren(false);
            int cw = Math.round(460 * s), ch = Math.round(620 * s);
            LayoutParams lp = new LayoutParams(cw, ch, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = Math.round(230 * s);
            back2 = new MoodCard(a, tf);
            back1 = new MoodCard(a, tf);
            top = new MoodCard(a, tf);
            for (MoodCard m : new MoodCard[]{back2, back1, top}) addView(m, new LayoutParams(lp));
            back1.setScaleX(.92f); back1.setScaleY(.92f); back1.setTranslationY(-28 * s); back1.setAlpha(.7f);
            back2.setScaleX(.84f); back2.setScaleY(.84f); back2.setTranslationY(-56 * s); back2.setAlpha(.45f);

            reel = new Poster(a, moods.get(moods.size() - 1).titles.get(0), tf);
            LayoutParams rp = new LayoutParams(Math.round(290 * s), Math.round(435 * s), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            rp.topMargin = Math.round(290 * s);
            reel.setSat(1f);
            reel.setVisibility(INVISIBLE);
            addView(reel, rp);
            msg = new TextView(a);
            msg.setTypeface(tf);
            msg.setTextColor(Color.WHITE);
            msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40 * s);
            msg.setGravity(Gravity.CENTER);
            msg.setVisibility(INVISIBLE);
            LayoutParams mp = new LayoutParams(-1, -2, Gravity.TOP);
            mp.topMargin = Math.round(755 * s);
            addView(msg, mp);
            track = new View(a);
            track.setBackgroundColor(0x33FFFFFF);
            track.setVisibility(INVISIBLE);
            LayoutParams tp = new LayoutParams(Math.round(460 * s), Math.round(8 * s), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            tp.topMargin = Math.round(905 * s);
            addView(track, tp);
            bar = new View(a);
            bar.setVisibility(INVISIBLE);
            addView(bar, new LayoutParams(tp));
            bind();
        }

        boolean busy() { return mode != CARD; }

        void bind() {
            top.set(moods.get(idx));
            back1.set(moods.get((idx + 1) % moods.size()));
            back2.set(moods.get((idx + 2) % moods.size()));
            onColor.accept(moods.get(idx).color);
        }

        @Override protected void onFocusChanged(boolean gained, int dir, android.graphics.Rect r) {
            super.onFocusChanged(gained, dir, r);
            if (gained) onColor.accept(moods.get(idx).color);
        }

        @Override public boolean onKeyDown(int key, KeyEvent e) {
            boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT, ok = key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER, left = key == KeyEvent.KEYCODE_DPAD_LEFT;
            if (mode == CARD) {
                if (left) { nope(); return true; }
                if (right || ok) { spin(); return true; }
                return super.onKeyDown(key, e); // up and down leave the deck
            }
            if (mode == COUNT && ok) { go(); return true; }
            if (mode == COUNT && right) { spin(); return true; }
            if (left) { cancel(); return true; }
            return key != KeyEvent.KEYCODE_BACK; // keys do nothing while the reel runs (Back is handled by the activity)
        }

        void nope() {
            top.animate().translationX(-520 * s).rotation(-14).alpha(0f).setDuration(260).withEndAction(() -> {
                idx = (idx + 1) % moods.size();
                bind();
                top.setTranslationX(0); top.setRotation(0);
                top.animate().alpha(1f).setDuration(200).start();
            }).start();
        }

        void cards(float a) { for (View v : new View[]{top, back1, back2}) v.animate().alpha(a * (v == top ? 1f : v == back1 ? .7f : .45f)).setDuration(200).start(); }

        void spin() {
            h.removeCallbacksAndMessages(null);
            mode = REEL;
            Mood m = moods.get(idx);
            pick = m.titles.get(rnd.nextInt(m.titles.size()));
            cards(0f);
            for (View v : new View[]{msg, track, bar}) v.setVisibility(INVISIBLE);
            reel.setVisibility(VISIBLE);
            int n = 18;
            int at = 0;
            for (int k = 0; k < n; k++) {
                final int step = k;
                at += 45 + 5 * k + k * k / 4; // fast at first, then slowing down
                final int dur = Math.max(40, 45 + 5 * k + k * k / 4 - 10);
                h.postDelayed(() -> {
                    Title t = step == n - 1 ? pick : m.titles.get(rnd.nextInt(m.titles.size()));
                    reel.t = t;
                    reel.setTranslationY(40 * s);
                    reel.invalidate();
                    reel.animate().translationY(0).setDuration(dur).start();
                    onColor.accept(t.color());
                    if (step == n - 1) countdown();
                }, at);
            }
        }

        void countdown() {
            mode = COUNT;
            msg.setVisibility(VISIBLE);
            track.setVisibility(VISIBLE);
            bar.setVisibility(VISIBLE);
            bar.setBackgroundColor(moods.get(idx).color);
            bar.setPivotX(0);
            bar.setScaleX(0f);
            bar.animate().scaleX(1f).setDuration(3000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
            for (int k = 3; k >= 1; k--) {
                final int n = k;
                h.postDelayed(() -> msg.setText(pick.name + "\nOpening in " + n + "…"), (3 - k) * 1000L);
            }
            h.postDelayed(this::go, 3000);
        }

        void go() {
            h.removeCallbacksAndMessages(null);
            Browse.open(act, pick);
            cancel();
        }

        void cancel() {
            h.removeCallbacksAndMessages(null);
            bar.animate().cancel();
            mode = CARD;
            for (View v : new View[]{reel, msg, track, bar}) v.setVisibility(INVISIBLE);
            cards(1f);
            onColor.accept(moods.get(idx).color);
        }
    }
}
