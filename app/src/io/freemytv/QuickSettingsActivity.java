package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Quick settings, as Google TV's gear opens them (that panel lives in Google's launcher, which is off in Free My TV
 * mode): a glass panel on the right with the time, tiles for Wi-Fi, Bluetooth, the screensaver now, picture and sound,
 * inputs and all settings, and the latest notifications under them. Opened by the home screen's gear and the remote's
 * gear key, over any app; the gear again (or Back) closes it.
 */
public class QuickSettingsActivity extends Activity {
    private PanelView panel;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        panel = new PanelView(this);
        setContentView(panel);
    }

    /** Already open (singleTask) and asked again: the gear works as a switch. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        panel.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return panel.key(e) || super.dispatchKeyEvent(e);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static final class Tile {
        final String title, symbol;
        String sub = "";
        final Runnable action;
        /** Instead of `action`: something done in place, with the panel left open. */
        Runnable stay;
        /** Done when OK is held (then `stay` waits for the release, so a hold doesn't also do it). */
        Runnable hold;
        /** Glass edition (option C1): a slider across the panel; ← → call `adjust`, `level` (0 to 1) fills it. */
        boolean wide;
        java.util.function.IntConsumer adjust;
        java.util.function.DoubleSupplier level;
        final android.graphics.RectF rect = new android.graphics.RectF();
        int row;
        /** Option B: a section heading drawn above it, a whole-width row, and a switch showing its state. */
        String section;
        boolean line;
        java.util.function.BooleanSupplier on;

        Tile(String title, String symbol, Runnable action) {
            this.title = title;
            this.symbol = symbol;
            this.action = action;
        }
    }

    private final class PanelView extends View {
        private final float k;
        private final List<Tile> tiles = new ArrayList<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint clock, date, title, sub;
        private final RectF r = new RectF(), panelRect = new RectF(), arc = new RectF();
        private final Path path = new Path();
        private Tile wifi, bluetooth;
        /** The tiles first (two columns), then the notifications. */
        private int focus;
        private float slide;   // 0 off screen, 1 in place

        PanelView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            clock = new TextPaint(Ui.text(Ui.stadium, 84 * k, 0xFFFFFFFF));   // the home clock's "Estadio" letter
            date = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            title = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF));
            sub = new TextPaint(Ui.text(Ui.regular, 19 * k, 0x99FFFFFF));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            buildTiles();
            arrangeTiles();
            loadGlass(c);
            ValueAnimator in = ValueAnimator.ofFloat(0, 1);
            in.setDuration(280);
            in.setInterpolator(new DecelerateInterpolator(2f));
            in.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            in.start();
        }

        private void buildTiles() {
            Context c = getContext();
            tiles.add(wifi = new Tile("Wi-Fi", "wifi", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
            tiles.add(bluetooth = new Tile("Bluetooth", "bluetooth", () -> open(bluetoothSettings())));
            // Glass edition (option C1, like the iPhone's): brightness and volume as wide sliders.
            final Tile bright = new Tile("Brillo del inicio", "sunny", null);
            bright.wide = true;
            bright.level = () -> 1 - Prefs.launcherDim(c) / 100.0;
            bright.adjust = d -> {   // 10 % to 100 %, a tenth a step: right brightens, left dims
                int now = Math.round(Prefs.launcherDim(c) / 10f) * 10;
                Prefs.setLauncherDim(c, Math.max(0, Math.min(90, now - d * 10)));
            };
            bright.stay = () -> { };
            tiles.add(bright);
            final android.media.AudioManager audio = c.getSystemService(android.media.AudioManager.class);
            if (audio != null) {
                final Tile volume = new Tile("Volumen", "speaker", null);
                volume.wide = true;
                volume.level = () -> audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        / (double) Math.max(1, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                volume.adjust = d -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        d > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
                volume.stay = () -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                tiles.add(volume);
            }
            Tile dream = new Tile("Protector", "bedtime", () -> slideOutThen(() -> {
                // Straight into the screensaver: the panel has slid away, so no closing animation of its own.
                try {
                    c.startActivity(screensaverNow().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("screensaver: " + e.getMessage());
                }
                finish();
                overridePendingTransition(0, 0);
            }));
            dream.sub = "Activar ahora";
            tiles.add(dream);
            // Glass edition: the screensaver in the dark (black, a faint clock); the song still shows when one plays.
            final Tile dark = new Tile("Protector oscuro", "clear_night", null);
            dark.sub = DreamView.dark(c) ? "Activado" : "Desactivado";
            dark.stay = () -> {
                boolean on = !DreamView.dark(c);
                DreamView.setDark(c, on);
                dark.sub = on ? "Activado" : "Desactivado";
                invalidate();
            };
            tiles.add(dark);
            Tile picture = new Tile("Imagen y sonido", "picture", () -> open(RemoteKeys.quickSettings(c)));
            picture.sub = "Brillo y modos";
            tiles.add(picture);
            if (installed("com.tcl.suspension")) {
                Tile inputs = new Tile("Entradas", "hdmi", () -> {
                    finish();
                    try {
                        c.startService(new Intent("com.tcl.quickpanel.show.window")
                                .setComponent(new ComponentName("com.tcl.suspension", "com.tcl.suspension.DismissService")));
                    } catch (RuntimeException ignored) {
                    }
                });
                inputs.sub = "HDMI y TV";
                tiles.add(inputs);
            }
            Tile all = new Tile("Ajustes", "settings", () -> open(new Intent(Settings.ACTION_SETTINGS)));
            all.sub = "Todos los ajustes";
            tiles.add(all);
            Tile home = new Tile("Inicio", "sparkles", () -> open(new Intent(c, SettingsActivity.class)));
            tiles.add(home);
            // The DLNA receiver lives in the remote's service: only where that service runs.
            String services = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services != null && services.contains(c.getPackageName() + "/")) {
                final Tile dlna = new Tile("Recibir videos", "play", null);
                dlna.sub = Dlna.enabled(c) ? "DLNA prendido" : "DLNA apagado";
                dlna.stay = () -> {
                    boolean on = !Dlna.enabled(c);
                    Dlna.setEnabled(c, on);
                    dlna.sub = on ? "DLNA prendido" : "DLNA apagado";
                    invalidate();
                };
                tiles.add(dlna);
            }
            // Over Home with a picture behind it: the next picture, seen changing behind the panel.
            // Held: the next kind of picture (landscapes, art, favourites).
            if (HomeActivity.shown && !Prefs.homeBackgroundShown(c).equals("black")) {
                final Tile bg = new Tile("Fondo", "sync", null);
                bg.sub = kindName(Prefs.homeBackgroundShown(c));   // the kind of picture (hold OK to change it)
                bg.stay = () -> Prefs.skipHomeBackground(c);
                // While a seasonal theme is on, the pictures are the theme's: no other kind to turn to.
                if (!Gallery.photos(Prefs.seasonalTheme(c))) bg.hold = () -> {
                    List<String> kinds = new ArrayList<>(java.util.Arrays.asList(Gallery.KINDS));
                    if (Gallery.favorites(c).isEmpty()) kinds.remove(Gallery.FAVORITES);
                    int at = kinds.indexOf(Prefs.homeBackground(c));
                    String next = kinds.get((at + 1) % kinds.size());
                    Prefs.setHomeBackground(c, next);
                    bg.sub = kindName(next);
                    invalidate();
                };
                tiles.add(bg);
            }
        }

        /**
         * Option B: sections with rows. Connections (Wi-Fi, Bluetooth with their switches), screen and sound (the
         * sliders, the dark screensaver, picture and sound), then the shortcuts in two columns.
         */
        private void arrangeTiles() {
            java.util.List<Tile> rows = new ArrayList<>(), shortcuts = new ArrayList<>();
            Tile dream = null, dark = null, picture = null, bright = null, volume = null;
            for (Tile t : tiles) {
                switch (t.title) {
                    case "Protector": dream = t; break;
                    case "Protector oscuro": dark = t; break;
                    case "Imagen y sonido": picture = t; break;
                    case "Brillo del inicio": bright = t; break;
                    case "Volumen": volume = t; break;
                    default: if (t != wifi && t != bluetooth) shortcuts.add(t);
                }
            }
            wifi.line = bluetooth.line = true;
            wifi.section = "CONEXIONES";
            wifi.on = () -> wifiState;
            bluetooth.on = () -> btState;
            rows.add(wifi);
            rows.add(bluetooth);
            Tile firstScreen = bright != null ? bright : volume;
            if (bright != null) rows.add(bright);
            if (volume != null) rows.add(volume);
            if (dark != null) {
                dark.line = true;
                final Context c = getContext();
                dark.on = () -> DreamView.dark(c);
                rows.add(dark);
            }
            if (picture != null) {
                picture.line = true;
                rows.add(picture);
            }
            if (firstScreen != null) firstScreen.section = "PANTALLA Y SONIDO";
            if (dream != null) shortcuts.add(0, dream);
            if (!shortcuts.isEmpty()) shortcuts.get(0).section = "ATAJOS";
            tiles.clear();
            tiles.addAll(rows);
            tiles.addAll(shortcuts);
        }

        /**
         * The system screensaver, started now: SystemUI's Somnambulator dreams with whatever screensaver is set (Free My
         * TV's), with no screen in between. Where it's missing, Free My TV's own preview.
         */
        private String kindName(String kind) {
            return Gallery.name(kind);
        }

        private String dimText(int percent) {
            return percent == 0 ? "Normal" : (100 - percent) + " %";
        }

        private Intent screensaverNow() {
            Intent dream = new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
            if (dream.resolveActivity(getContext().getPackageManager()) != null) return dream;
            return new Intent(getContext(), SettingsActivity.class).putExtra("screensaver", true);
        }

        /** Android TV has no BLUETOOTH_SETTINGS screen: its "Remote & accessories" page holds the switch and the devices. */
        private Intent bluetoothSettings() {
            Intent tv = new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES");
            if (tv.resolveActivity(getContext().getPackageManager()) != null) return tv;
            return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        }

        private boolean installed(String pkg) {
            try {
                getContext().getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private boolean wifiState, btState;

        void refresh() {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm == null || cm.getActiveNetwork() == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            boolean online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean ethernet = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            wifi.sub = online ? (ethernet ? "Cable conectado" : "Conectado") : "Sin conexión";
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            boolean btOn = false;
            try {
                btOn = bt != null && bt.isEnabled();
            } catch (SecurityException ignored) {
            }
            bluetooth.sub = btOn ? "Activado" : "Desactivado";
            wifiState = online;
            btState = btOn;
            invalidate();
        }

        /** The panel slides back out to the right (quickly), then `then` runs. */
        private void slideOutThen(Runnable then) {
            ValueAnimator out = ValueAnimator.ofFloat(slide, 0);
            out.setDuration(200);
            out.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
            out.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            out.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { then.run(); }
            });
            out.start();
        }

        private void open(Intent i) {
            finish();
            try {
                getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("quick settings: " + e.getMessage());
            }
        }

        private boolean closing, okPending, held;

        /** Back or the gear: the panel slides out the way it came (the scrim fades with it), then the screen goes. */
        void close() {
            if (closing) return;
            closing = true;
            slideOutThen(QuickSettingsActivity.this::finish);
        }

        boolean key(KeyEvent e) {
            if (closing) return true;   // on its way out
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                if (isOk(e.getKeyCode()) && okPending) {   // a tile with a hold: a short press acts on release
                    okPending = false;
                    if (!held) tiles.get(focus).stay.run();
                    held = false;
                }
                return e.getKeyCode() == KeyEvent.KEYCODE_BACK || isOk(e.getKeyCode());
            }
            int t = tiles.size();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    // By rows (a slider is a row of its own): to the nearest tile of the row above or below.
                    int row = tiles.get(focus).row + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    float cx = tiles.get(focus).rect.centerX(), best = Float.MAX_VALUE;
                    for (int i = 0; i < t; i++) {
                        Tile o = tiles.get(i);
                        if (o.row != row) continue;
                        float d = Math.abs(o.rect.centerX() - cx);
                        if (d < best) {
                            best = d;
                            focus = i;
                        }
                    }
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    Tile cur = tiles.get(focus);
                    if (cur.wide) {   // a slider: ← → move it
                        cur.adjust.accept(d);
                        break;
                    }
                    int next = focus + d;
                    if (next >= 0 && next < t && tiles.get(next).row == cur.row) focus = next;
                    break;
                }
                default:
                    if (!isOk(e.getKeyCode())) return false;
                    Tile tile = tiles.get(focus);
                    if (tile.hold != null) {
                        if (e.getRepeatCount() == 0) {
                            okPending = true;
                            held = false;
                        } else if ((e.isLongPress() || e.getRepeatCount() >= 8) && !held) {
                            held = true;
                            tile.hold.run();
                        }
                        return true;
                    }
                    if (e.getRepeatCount() > 0) return true;
                    if (tile.stay != null) tile.stay.run();
                    else tile.action.run();
                    return true;
            }
            invalidate();
            return true;
        }

        private boolean isOk(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pw = 600 * k;
            // The scrim over what's behind, darker towards the panel.
            fill.setShader(new LinearGradient(w - pw - 300 * k, 0, w - pw, 0, 0x00000000, Ui.alpha(0x99000000, slide), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            float x0 = w - pw * slide;
            panelRect.set(x0, 0, x0 + pw + 60 * k, h);
            glassPanel(c, panelRect, 44 * k, w, h);
            float x = x0 + 48 * k, right = x0 + pw - 48 * k;
            // Option B: no clock or weather (the home shows them); the sections from the top.
            drawTiles(c, x, right, 64 * k);
            // The home screen's brightness applies to the panel too (only over it: the home behind is already dimmed).
            int dim = Prefs.launcherDim(getContext());
            if (dim > 0) {
                fill.setColor(Ui.alpha(0xFF000000, dim / 100f));
                c.drawRoundRect(panelRect, 44 * k, 44 * k, fill);
            }
        }

        private HomeData.Weather weather;
        private boolean weatherRead;
        private TextPaint temp;

        /** Beside the clock: the weather now (symbol and temperature) and, under it, its name. */
        private void drawWeather(Canvas c, float right, float dateEnd) {
            if (!weatherRead) {
                weatherRead = true;
                if (Prefs.weather()) weather = HomeData.saved(getContext());
            }
            HomeData.Weather wx = weather;
            if (wx == null || Double.isNaN(wx.temp) || wx.code < 0) return;
            if (temp == null) temp = new TextPaint(Ui.text(Ui.stadium, 56 * k, 0xFFFFFFFF));
            temp.setTextAlign(Paint.Align.RIGHT);
            String t = HomeData.degrees(wx.temp);
            c.drawText(t, right, 132 * k, temp);
            float tw = temp.measureText(t);
            Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), right - tw - 34 * k, 114 * k, 30 * k, 0xEBFFFFFF);
            String name = HomeData.condition(wx.code, wx.isDay);
            if (right - date.measureText(name) < dateEnd) return;   // no room beside the date: the symbol says it
            date.setTextAlign(Paint.Align.RIGHT);
            c.drawText(name, right, 182 * k, date);
            date.setTextAlign(Paint.Align.LEFT);
        }

        private TextPaint section;

        /** Sections of whole-width rows and sliders, then the shortcuts two by two; returns where they end. */
        private float drawTiles(Canvas c, float x, float right, float top) {
            if (section == null) {
                section = new TextPaint(Ui.text(Ui.semibold, 18 * k, 0x99FFFFFF));
                section.setLetterSpacing(0.14f);
            }
            float gap = 12 * k, tw = (right - x - gap) / 2, lh = 74 * k, sh = 66 * k, th = 74 * k;
            float y = top;
            int row = 0, col = 0;
            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                if (t.section != null) {
                    if (col == 1) {
                        y += th + gap;
                        row++;
                        col = 0;
                    }
                    y += 34 * k;
                    c.drawText(t.section, x + 6 * k, y - 12 * k, section);
                }
                if (t.wide || t.line) {
                    if (col == 1) {
                        y += th + gap;
                        row++;
                        col = 0;
                    }
                    float hh = t.wide ? sh : lh;
                    t.rect.set(x, y, right, y + hh);
                    t.row = row++;
                    y += hh + gap;
                    continue;
                }
                t.rect.set(x + col * (tw + gap), y, x + col * (tw + gap) + tw, y + th);
                t.row = row;
                if (col == 1 || i == tiles.size() - 1) {
                    y += th + gap;
                    row++;
                    col = 0;
                } else {
                    col = 1;
                }
            }
            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                r.set(t.rect);
                boolean on = focus == i;
                if (t.wide) {
                    drawSlider(c, t, on);
                    continue;
                }
                box(c, r, 22 * k, on);
                int ink = on ? 0xFF1C1C1E : 0xEBFFFFFF;
                drawGlyph(c, t.symbol, r.left + 36 * k, r.centerY(), 13 * k, ink);
                title.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                sub.setColor(on ? 0x991C1C1E : 0x99FFFFFF);
                float tx = r.left + 70 * k, room = r.right - tx - (t.on != null ? 96 * k : 20 * k);
                boolean two = t.sub != null && !t.sub.isEmpty() && t.line;
                c.drawText(fit(t.title, title, room), tx, two ? r.centerY() - 4 * k : r.centerY() + Ui.halfCap(title), title);
                if (two) c.drawText(fit(t.sub, sub, room), tx, r.centerY() + 24 * k, sub);
                if (t.on != null) drawSwitch(c, r.right - 24 * k, r.centerY(), t.on.getAsBoolean(), on);
            }
            return y;
        }

        /** A switch with no colour: white with a dark knob when on, glass with a white knob when off. */
        private void drawSwitch(Canvas c, float right, float cy, boolean isOn, boolean focused) {
            float w = 62 * k, h = 34 * k;
            r.set(right - w, cy - h / 2, right, cy + h / 2);
            fill.setShader(null);
            int track = isOn ? (focused ? 0xFF1C1C1E : 0xFFFFFFFF) : (focused ? 0x33000000 : 0x40FFFFFF);
            fill.setColor(track);
            c.drawRoundRect(r, h / 2, h / 2, fill);
            float kr = h / 2 - 4 * k, kx = isOn ? r.right - h / 2 : r.left + h / 2;
            fill.setColor(isOn ? (focused ? 0xFFFFFFFF : 0xFF1C1C1E) : 0xFFFFFFFF);
            c.drawCircle(kx, cy, kr, fill);
        }

        /** A slider like the iPhone's: a glass bar filled white up to its level, its symbol and name over it. */
        private void drawSlider(Canvas c, Tile t, boolean on) {
            float rad = r.height() / 2;
            box(c, r, rad, false);
            float lv = (float) Math.max(0, Math.min(1, t.level.getAsDouble()));
            float fw = Math.max(r.height(), r.width() * lv);
            fill.setShader(null);
            fill.setColor(0xF2FFFFFF);
            c.save();
            c.clipRect(r.left, r.top, r.left + fw, r.bottom);
            c.drawRoundRect(r, rad, rad, fill);
            c.restore();
            // Dark over the white fill, white over the glass.
            drawGlyph(c, t.symbol, r.left + 42 * k, r.centerY(), 15 * k, fw > 60 * k ? 0xFF1C1C1E : 0xEBFFFFFF);
            title.setColor(fw > 76 * k + title.measureText(t.title) ? 0xFF1C1C1E : 0xFFFFFFFF);
            c.drawText(t.title, r.left + 76 * k, r.centerY() + Ui.halfCap(title), title);
            String pct = Math.round(lv * 100) + " %";
            sub.setColor(lv > 0.88f ? 0x991C1C1E : 0xCCFFFFFF);
            c.drawText(pct, r.right - 26 * k - sub.measureText(pct), r.centerY() + Ui.halfCap(sub), sub);
            if (on) {   // the focus: a white ring, and ← → move it
                rim.setShader(null);
                rim.setColor(0xFFFFFFFF);
                rim.setStrokeWidth(3.5f * k);
                c.drawRoundRect(r.left - 6 * k, r.top - 6 * k, r.right + 6 * k, r.bottom + 6 * k, rad + 6 * k, rad + 6 * k, rim);
            }
        }

        /** A tile: a pane of glass, lighter at the top, with a lit rim; focused, white (the tvOS focus), slightly larger. */
        private void box(Canvas c, RectF rr, float rad, boolean on) {
            if (on) rr.inset(-6 * k, -4 * k);
            fill.setShader(null);
            if (on) {
                fill.setColor(0xFFF5F5F7);
                c.drawRoundRect(rr, rad, rad, fill);
                return;
            }
            place(tileTint, rr, false);
            fill.setShader(tileTint);
            c.drawRoundRect(rr, rad, rad, fill);
            fill.setShader(null);
            litRim(c, rr, rad, 1.5f * k);
        }

        // MARK: Glass edition: the panel is frosted glass over the home screen's picture, like the home's panels.

        /** The home screen's picture (its cached copy), blurred: Backdrop.FW × FH, made off the UI thread. */
        private Bitmap blur;
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix glassMatrix = new android.graphics.Matrix();
        private final LinearGradient panelVeil = new LinearGradient(0, 0, 0, 1,
                new int[] {0x2EFFFFFF, 0x26000000, 0x59000000}, new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP);
        private final LinearGradient panelSmoke = new LinearGradient(0, 0, 0, 1, 0xE0303036, 0xF01C1C20, Shader.TileMode.CLAMP);
        private final LinearGradient tileTint = new LinearGradient(0, 0, 0, 1, 0x38FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
        private final LinearGradient rimLight = new LinearGradient(0, 0, 1, 1, new int[] {0xA6FFFFFF, 0x24FFFFFF, 0x4DFFFFFF},
                new float[] {0, 0.62f, 1}, Shader.TileMode.CLAMP);
        private final RectF rimRect = new RectF();

        private void loadGlass(Context c) {
            if (!HomeActivity.shown || !Gallery.photos(Prefs.homeBackgroundShown(c))) return;
            final java.io.File f = new java.io.File(c.getCacheDir(), "wallpaper.jpg");
            new Thread(() -> {
                try {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 8;
                    Bitmap mid = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                    if (mid == null) return;
                    Bitmap tiny = Bitmap.createScaledBitmap(mid, 40, 22, true);
                    Bitmap up = Bitmap.createScaledBitmap(tiny, Backdrop.FW, Backdrop.FH, true);
                    mid.recycle();
                    tiny.recycle();
                    post(() -> {
                        blur = up;
                        blurPaint.setShader(new android.graphics.BitmapShader(up, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                        invalidate();
                    });
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("quick settings glass: " + e.getMessage());
                }
            }).start();
        }

        private void glassPanel(Canvas c, RectF r, float rad, float w, float h) {
            if (blur != null) {
                glassMatrix.setScale(w / Backdrop.FW, h / Backdrop.FH);
                blurPaint.getShader().setLocalMatrix(glassMatrix);
                c.drawRoundRect(r, rad, rad, blurPaint);
            }
            LinearGradient tint = blur != null ? panelVeil : panelSmoke;
            place(tint, r, false);
            fill.setShader(tint);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            litRim(c, r, rad, 2 * k);
        }

        /** A unit gradient stretched over `r` (diagonal: corner to corner). */
        private void place(LinearGradient g, RectF r, boolean diagonal) {
            glassMatrix.setScale(diagonal ? r.width() : 1, r.height());
            glassMatrix.postTranslate(r.left, r.top);
            g.setLocalMatrix(glassMatrix);
        }

        /** The rim lit from the top left: brightest on the edges that face the light. */
        private void litRim(Canvas c, RectF r, float rad, float sw) {
            place(rimLight, r, true);
            rim.setShader(rimLight);
            rim.setStrokeWidth(sw);
            rimRect.set(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2);
            c.drawRoundRect(rimRect, rad - sw / 2, rad - sw / 2, rim);
            rim.setShader(null);
        }

        private String fit(String s, TextPaint p, float w) {
            return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString();
        }

        /** Wi-Fi and Bluetooth drawn here; the rest from the Material symbols. */
        private void drawGlyph(Canvas c, String which, float cx, float cy, float u, int color) {
            if (which.equals("wifi")) {
                glyph.setColor(color);
                glyph.setStrokeWidth(2.6f * k);
                for (int i = 1; i <= 3; i++) {
                    float rr = u * 0.55f * i;
                    arc.set(cx - rr, cy + u * 0.8f - rr, cx + rr, cy + u * 0.8f + rr);
                    c.drawArc(arc, -135, 90, false, glyph);
                }
                fill.setColor(color);
                c.drawCircle(cx, cy + u * 0.8f, 2.4f * k, fill);
            } else if (which.equals("bluetooth")) {
                glyph.setColor(color);
                glyph.setStrokeWidth(2.6f * k);
                path.reset();
                path.moveTo(cx - u * 0.55f, cy - u * 0.5f);
                path.lineTo(cx + u * 0.5f, cy + u * 0.45f);
                path.lineTo(cx, cy + u);
                path.lineTo(cx, cy - u);
                path.lineTo(cx + u * 0.5f, cy - u * 0.45f);
                path.lineTo(cx - u * 0.55f, cy + u * 0.5f);
                c.drawPath(path, glyph);
            } else {
                Symbols.draw(getContext(), c, which, cx, cy, u * 1.9f, color);
            }
        }
    }
}
