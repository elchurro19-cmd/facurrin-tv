package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private final Recording auroraNode = Recording.create("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB;
    private BitmapShader glassShaderA;
    private final boolean withGlass;

    Scene() {
        this(true);
    }

    Scene(boolean withGlass) {
        this.withGlass = withGlass;
        try {
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL, flat look instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        auroraNode.setPosition(0, 0, lw, lh);
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        fallback.setShader(new LinearGradient(0, 0, 0, h, palette.color(1), palette.color(0), Shader.TileMode.CLAMP));
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (withGlass && (!imageFrost || !imageGlass)) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        if (liquid == null) return;
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        if (liquid != null) liquid.setFloatUniform("mixB", Math.max(0, Math.min(1, t)));
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        imageFrost = true;
        if (glass == null) return;
        BitmapShader s = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        glass.setInputShader("frost", s);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        offsetX = x;
        offsetY = y;
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null) {
            if (fallback.getShader() == null) applyPalette();
            c.drawRect(0, 0, w, h, fallback);
            return;
        }
        if (auroraDirty) {
            Canvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        auroraNode.draw(c);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            frost(c, r, radius, body, alpha);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }

    // Glass edition (from CleanYourTV Glass's FrostDrawable): with no shader (Android 12 and older), the blurred
    // background behind the panel (the same frost image the drawer uses), under a smoky tint that is lighter at the top
    // and deeper at the bottom (it reads as a slab with thickness), and a rim lit from the top left: brightest on the
    // edges that face the light, fainter on the far side. Shaders are made once and placed with a matrix: no
    // allocations per frame.
    // Over the blurred photo, a light veil: brighter at the top, a touch of shade at the foot. Without a photo, the
    // smoky tint carries the panel alone.
    private static final int[] FROST_TINT = {0x47FFFFFF, 0x1FFFFFFF, 0x0A000000, 0x29000000};
    private static final int[] FROST_SMOKE = {0x6A75757F, 0x5E4A4A54, 0x78303038, 0x8F222229};
    private static final float[] FROST_STOPS = {0, 0.42f, 0.85f, 1};
    private LinearGradient frostSmoke;
    private final RectF frostRim = new RectF();
    private final Paint frostImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix frostMatrix = new android.graphics.Matrix();
    private BitmapShader frostImageShader;
    private Bitmap frostShaderOf;
    private LinearGradient frostTint, frostLight;
    private float offsetX, offsetY;

    private void frost(Canvas c, RectF r, float radius, float body, float alpha) {
        int a = Math.round(255 * Math.min(1, alpha));
        if (frostTint == null) {
            frostTint = new LinearGradient(0, 0, 0, 1, FROST_TINT, FROST_STOPS, Shader.TileMode.CLAMP);
            frostSmoke = new LinearGradient(0, 0, 0, 1, FROST_SMOKE, FROST_STOPS, Shader.TileMode.CLAMP);
            frostLight = new LinearGradient(0, 0, 1, 1, new int[] {0xA6FFFFFF, 0x24FFFFFF, 0x4DFFFFFF},
                    new float[] {0, 0.62f, 1}, Shader.TileMode.CLAMP);
        }
        if (body > 0) {
            int ba = Math.round(a * Math.min(1, body));
            boolean photo = imageFrost && frostImage != null;
            LinearGradient tint = photo ? frostTint : frostSmoke;
            if (photo) {
                if (frostShaderOf != frostImage) {
                    frostShaderOf = frostImage;
                    frostImageShader = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    frostImagePaint.setShader(frostImageShader);
                }
                frostMatrix.setScale((float) w / Backdrop.FW, (float) h / Backdrop.FH);
                frostMatrix.postTranslate(-offsetX, -offsetY);
                frostImageShader.setLocalMatrix(frostMatrix);
                frostImagePaint.setAlpha(ba);
                c.drawRoundRect(r, radius, radius, frostImagePaint);
            }
            frostMatrix.setScale(1, r.height());
            frostMatrix.postTranslate(r.left, r.top);
            tint.setLocalMatrix(frostMatrix);
            flatFill.setShader(tint);
            flatFill.setAlpha(ba);
            c.drawRoundRect(r, radius, radius, flatFill);
            flatFill.setShader(null);
        }
        float sw = Math.max(1.5f, Math.min(r.width(), r.height()) * 0.012f);
        flatRim.setStrokeWidth(sw);
        frostMatrix.setScale(r.width(), r.height());
        frostMatrix.postTranslate(r.left, r.top);
        frostLight.setLocalMatrix(frostMatrix);
        flatRim.setShader(frostLight);
        flatRim.setAlpha(a);
        frostRim.set(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2);
        float rr = Math.max(0, radius - sw / 2);
        c.drawRoundRect(frostRim, rr, rr, flatRim);
        flatRim.setShader(null);
        flatRim.setAlpha(255);
    }
}
