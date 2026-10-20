package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;

import org.xmlpull.v1.XmlPullParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a nest card is drawn from, in the order the reference launcher uses.
 *
 * <p>Measured off Dangbei 4.1.6 running on this TV, not guessed:
 *
 * <ol>
 *   <li><b>An app with a TV banner</b> (影视仓 ships {@code app_banner.png}): the banner itself is the card.
 *       Their tile and that PNG differ by a mean 5.9/255 per channel, while their tile and the app's icon
 *       differ by 26.</li>
 *   <li><b>An app with an adaptive icon</b> (网易云音乐): the icon's own background layer is the card and its
 *       foreground layer - a transparent, line-art logo - is drawn on it. That icon's background is
 *       {@code <gradient #FF4840 -> #FF0000>} and its foreground is white line art on transparency, which is
 *       exactly the "logo and colour are one piece" look. Both layers are public Android 8+ resources.</li>
 *   <li>Anything else: the icon's own solid ground, lifted off, painted as the card (a coloured square icon
 *       keeps its colour, its logo keeps its shape).</li>
 *   <li>A photo-like icon with no ground: the icon itself, filling the card.</li>
 * </ol>
 *
 * <p>Dangbei's own code cannot be read - its {@code classes.dex} is a packer stub (assets/.jgapp), so this is
 * reconstructed from shipped artwork and from what it draws on screen.
 */
final class SlotCard {
    /** Radius of a card, in dp: 15dp on a 150x84dp card, the same 10% ratio as the reference. */
    static final float RADIUS_DP = 15f;
    /** How tall a lifted logo is drawn on its card, as a fraction of the card's height. */
    static final float LOGO_SCALE = 0.52f;
    private static final int SAMPLE = 112;
    private static final int CACHE_MAX = 96;
    private static final int TOP_BASE = 0xFF2A2E36, BOTTOM_BASE = 0xFF14161B;

    /** What to paint: a card image or a flat card colour, plus an optional logo on top. */
    static final class Look {
        /** Painted across the card (centerCrop): a banner, an adaptive background, or the icon itself. */
        final Drawable art;
        /** Painted under the logo when it is not a full-bleed image; null for a flat card. */
        final Drawable fill;
        /** The line-art logo drawn on top, or null. */
        final Drawable logo;
        final float logoScale;

        Look(Drawable art, Drawable fill, Drawable logo, float logoScale) {
            this.art = art;
            this.fill = fill;
            this.logo = logo;
            this.logoScale = logoScale;
        }
    }

    private static final Map<String, Look> CACHE = new LinkedHashMap<String, Look>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Look> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private SlotCard() { }

    static Look look(String key, Drawable icon, Drawable banner, ApplicationInfo info,
                     PackageManager pm, float density) {
        Look cached = key == null ? null : CACHE.get(key);
        if (cached != null) return cached;
        Look look = build(icon, banner, info, pm, density);
        if (key != null) CACHE.put(key, look);
        return look;
    }

    private static Look build(Drawable icon, Drawable banner, ApplicationInfo info, PackageManager pm,
                              float density) {
        // 1. The app's own TV banner: the wide artwork the reference draws as-is.
        if (banner != null) return new Look(banner, null, null, 0);
        // 2. Adaptive icon layers: background is the card, foreground is the logo.
        Drawable[] layers = info == null ? null : adaptiveLayers(pm, info);
        if (layers != null) return new Look(layers[0], null, layers[1], LOGO_SCALE);
        if (icon == null) return new Look(null, neutral(density), null, 0);
        // 3. A plain coloured-square icon: lift its ground off and paint the card in that colour.
        Bitmap source = render(icon);
        if (source == null) return new Look(icon, null, null, 0);
        int ground = groundColor(source);
        if (ground != 0) {
            Bitmap lifted = lift(source, ground);
            source.recycle();
            if (lifted != null) {
                return new Look(null, ground(ground, density), new BitmapDrawable(null, lifted), LOGO_SCALE);
            }
        } else {
            source.recycle();
        }
        // 4. Nothing to lift: the icon fills the card.
        return new Look(icon, null, null, 0);
    }

    /**
     * The background and foreground layers of an app's adaptive icon, read from the app's own resources.
     * Works on every release from Android 8 up; {@code AdaptiveIconDrawable} only exposes the layers from
     * Android 10, and this TV is 9.
     */
    static Drawable[] adaptiveLayers(PackageManager pm, ApplicationInfo info) {
        if (info.icon == 0) return null;
        XmlResourceParser parser = null;
        try {
            Resources res = pm.getResourcesForApplication(info);
            parser = res.getXml(info.icon);
            int event;
            int backgroundId = 0, foregroundId = 0;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                String name = parser.getName();
                if ("adaptive-icon".equals(name)) continue;
                if ("background".equals(name)) {
                    backgroundId = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0);
                } else if ("foreground".equals(name)) {
                    foregroundId = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0);
                }
                if (backgroundId != 0 && foregroundId != 0) break;
            }
            if (backgroundId == 0 || foregroundId == 0) {
                android.util.Log.i("TvHomeSlot", "adaptiveLayers(" + info.packageName
                        + ") no layers: icon=" + Integer.toHexString(info.icon)
                        + " bg=" + Integer.toHexString(backgroundId) + " fg=" + Integer.toHexString(foregroundId));
                return null;
            }
            Drawable background = res.getDrawable(backgroundId, null);
            Drawable foreground = res.getDrawable(foregroundId, null);
            if (background == null || foreground == null) return null;
            Bitmap logo = cropToContent(render(foreground));
            if (logo == null) return new Drawable[]{background, foreground};
            return new Drawable[]{background, new BitmapDrawable(null, logo)};
        } catch (Exception | OutOfMemoryError e) {
            // Not an adaptive icon (a bitmap icon throws here), or a broken resource: fall through.
            android.util.Log.i("TvHomeSlot", "adaptiveLayers(" + info.packageName + ") failed: " + e);
            return null;
        } finally {
            if (parser != null) parser.close();
        }
    }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // ---- bitmaps ---------------------------------------------------------------------------------

    private static Bitmap render(Drawable drawable) {
        if (drawable == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable copy = drawable.getConstantState() != null
                    ? drawable.getConstantState().newDrawable().mutate() : drawable;
            copy.setBounds(0, 0, SAMPLE, SAMPLE);
            copy.draw(canvas);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The icon's own ground: the colour that covers most of the icon <em>and</em> most of its outer ring.
     * Both tests matter - a big logo in the middle covers a lot of the icon but never reaches the edge.
     */
    private static int groundColor(Bitmap bitmap) {
        if (bitmap == null) return 0;
        int[] pixels = new int[SAMPLE * SAMPLE];
        bitmap.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        int edge = Math.max(2, SAMPLE / 8);
        Map<Integer, Integer> all = new LinkedHashMap<>(), ring = new LinkedHashMap<>();
        int opaque = 0, ringOpaque = 0;
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int p = pixels[y * SAMPLE + x];
                if (Color.alpha(p) < 200) continue;
                int key = quantize(p);
                opaque++;
                count(all, key);
                if (x < edge || y < edge || x >= SAMPLE - edge || y >= SAMPLE - edge) {
                    ringOpaque++;
                    count(ring, key);
                }
            }
        }
        if (opaque == 0 || ringOpaque == 0) return 0;
        int bestKey = 0, best = 0;
        for (Map.Entry<Integer, Integer> e : all.entrySet()) {
            if (e.getValue() <= best || !ring.containsKey(e.getKey())) continue;
            best = e.getValue();
            bestKey = e.getKey();
        }
        if (bestKey == 0) return 0;
        Integer onRing = ring.get(bestKey);
        if (onRing == null || best * 3 < opaque || onRing * 2 < ringOpaque) return 0;
        return bestKey;
    }

    private static void count(Map<Integer, Integer> map, int key) {
        Integer seen = map.get(key);
        map.put(key, seen == null ? 1 : seen + 1);
    }

    private static int quantize(int color) {
        int r = Math.min(255, (Color.red(color) & 0xF0) | 0x08);
        int g = Math.min(255, (Color.green(color) & 0xF0) | 0x08);
        int b = Math.min(255, (Color.blue(color) & 0xF0) | 0x08);
        return Color.rgb(r, g, b);
    }

    /** The icon with its ground colour removed, cropped to what is left. */
    private static Bitmap lift(Bitmap source, int ground) {
        int[] pixels = new int[SAMPLE * SAMPLE];
        source.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            if (Color.alpha(p) == 0) continue;
            int distance = distance(p, ground);
            if (distance < 36) pixels[i] = 0;
            else if (distance < 96) {
                int alpha = Color.alpha(p) * (distance - 36) / 60;
                pixels[i] = (p & 0x00FFFFFF) | (alpha << 24);
            }
        }
        return crop(pixels);
    }

    /** The bitmap trimmed to its opaque pixels. */
    private static Bitmap cropToContent(Bitmap bitmap) {
        if (bitmap == null) return null;
        int[] pixels = new int[SAMPLE * SAMPLE];
        bitmap.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        Bitmap cropped = crop(pixels);
        bitmap.recycle();
        return cropped;
    }

    private static Bitmap crop(int[] pixels) {
        int left = SAMPLE, top = SAMPLE, right = -1, bottom = -1;
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                if (Color.alpha(pixels[y * SAMPLE + x]) < 24) continue;
                if (x < left) left = x;
                if (y < top) top = y;
                if (x > right) right = x;
                if (y > bottom) bottom = y;
            }
        }
        if (right <= left || bottom <= top) return null;
        Bitmap cropped = Bitmap.createBitmap(right - left + 1, bottom - top + 1, Bitmap.Config.ARGB_8888);
        int[] out = new int[cropped.getWidth() * cropped.getHeight()];
        for (int y = 0; y < cropped.getHeight(); y++) {
            System.arraycopy(pixels, (top + y) * SAMPLE + left, out, y * cropped.getWidth(), cropped.getWidth());
        }
        cropped.setPixels(out, 0, cropped.getWidth(), 0, 0, cropped.getWidth(), cropped.getHeight());
        return cropped;
    }

    private static int distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return (int) Math.sqrt((dr * dr + dg * dg + db * db) / 3.0);
    }

    // ---- cards -----------------------------------------------------------------------------------

    /** A flat card in the app's lifted ground colour, a touch lighter at the top. */
    private static Drawable ground(int color, float density) {
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(color, 1.10f), darken(color, 0.78f)});
        card.setCornerRadius(RADIUS_DP * density);
        card.setStroke(Math.round(density), 0x22FFFFFF);
        return card;
    }

    /** The calm card: charcoal tinted with the app's colour, for artwork that already stands on its own. */
    static Drawable neutral(float density) {
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{TOP_BASE, BOTTOM_BASE});
        card.setCornerRadius(RADIUS_DP * density);
        card.setStroke(Math.round(density), 0x22FFFFFF);
        return card;
    }

    /** Card corner radius in pixels for a given density. */
    static int radiusPx(float density) {
        return Math.round(RADIUS_DP * density);
    }

    private static int lighten(int color, float by) {
        return Color.rgb(Math.min(255, Math.round(Color.red(color) * by)),
                Math.min(255, Math.round(Color.green(color) * by)),
                Math.min(255, Math.round(Color.blue(color) * by)));
    }

    private static int darken(int color, float by) {
        return Color.rgb(Math.round(Color.red(color) * by),
                Math.round(Color.green(color) * by),
                Math.round(Color.blue(color) * by));
    }

    /** Unused today; kept so a future theme can pull the icon's accent colour for the ambient light. */
    static int accent(Drawable icon) {
        return AppAdapter.vividColor(icon);
    }

    /** Screen density helper used by callers that only have a Resources. */
    static float densityOf(Resources res) {
        return res.getDisplayMetrics().densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
    }
}
