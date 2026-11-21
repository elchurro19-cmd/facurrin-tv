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
    private boolean welcomeStart;

    /** The city's box: a text field over the panel's own row, in its colours and letter. */
    private android.widget.EditText cityField;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        welcomeStart = getIntent() != null && getIntent().getBooleanExtra("welcome", false);
        panel = new PanelView(this);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(panel);
        cityField = new android.widget.EditText(this);
        cityField.setVisibility(View.GONE);
        cityField.setBackground(null);
        cityField.setSingleLine(true);
        cityField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        cityField.setTextColor(0xFF1C1C1E);
        cityField.setHintTextColor(0x801C1C1E);
        cityField.setTypeface(Ui.medium);
        cityField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * getResources().getDisplayMetrics().heightPixels / 1080f);
        cityField.setHint("Escribí tu ciudad y apretá Buscar");
        root.addView(cityField, new android.widget.FrameLayout.LayoutParams(1, 1));
        setContentView(root);
    }

    /** Already open (singleTask) and asked again: the gear works as a switch. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("welcome", false)) {
            panel.startWelcome();
            return;
        }
        if (panel.welcomeMode) return;   // the welcome isn't closed by the gear
        panel.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel.editing) {   // typing the city: the keys are the field's (Back leaves it)
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && panel.downFromField()) return true;
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) panel.stopEditing();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        return panel.key(e) || super.dispatchKeyEvent(e);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** Who made it: shown at the foot of the welcome and in Info. */
    static final String CREDIT = "Hecho por Facurrin";

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
        /** U2: a value shown on the right ("‹ Oscuro ›"), stepped by ← → (and OK). */
        java.util.function.Supplier<String> value;
        java.util.function.IntConsumer cycle;
        /** OK opens a list to pick from (the value shows with a chevron). */
        boolean opens;

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
            buildWelcome();
            if (welcomeStart) startWelcome();
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

        /** U2: one panel for everything: Rápido, Entradas, Inicio, Fondos and Más, each a section on the icon rail. */
        private final List<String> sectNames = new ArrayList<>(), sectIcons = new ArrayList<>();
        private final List<List<Tile>> sectTiles = new ArrayList<>();
        private int sect, side;   // side 0: the rail, 1: the section's rows (focus is the row)
        /** A list shown in place of the section's rows (the city's results, the shelf's apps): Back leaves it. */
        private List<Tile> subList;
        private String subTitle = "";
        private int subFrom;

        private List<Tile> rows() {
            return subList != null ? subList : welcomeMode ? stepRows.get(step) : sectTiles.get(sect);
        }

        private void openSub(String title, List<Tile> list) {
            subFrom = focus;
            subTitle = title;
            subList = list;
            focus = 0;
            side = 1;
            invalidate();
        }

        private void closeSub() {
            subList = null;
            focus = subFrom;
            invalidate();
        }

        private Tile curTile() {
            List<Tile> l = rows();
            return l.get(Math.max(0, Math.min(l.size() - 1, focus)));
        }

        private List<Tile> section(String name, String icon) {
            sectNames.add(name);
            sectIcons.add(icon);
            List<Tile> l = new ArrayList<>();
            sectTiles.add(l);
            return l;
        }

        private Tile toggle(String title, String symbol, java.util.function.BooleanSupplier get, Runnable flip) {
            Tile t = new Tile(title, symbol, null);
            t.on = get;
            t.stay = () -> {
                flip.run();
                changed();
            };
            return t;
        }

        private Tile cycle(String title, String symbol, java.util.function.Supplier<String> value, java.util.function.IntConsumer step) {
            Tile t = new Tile(title, symbol, null);
            t.value = value;
            t.cycle = d -> {
                step.accept(d);
                changed();
            };
            t.stay = () -> t.cycle.accept(1);
            return t;
        }

        /** A row whose value is picked from a list shown in the panel (the current one ticked); OK picks. */
        private Tile picker(String title, String symbol, String[] names, java.util.function.IntSupplier current,
                java.util.function.IntConsumer pick) {
            final Tile t = new Tile(title, symbol, null);
            t.opens = true;
            t.value = () -> names[Math.max(0, Math.min(names.length - 1, current.getAsInt()))];
            t.stay = () -> {
                List<Tile> list = new ArrayList<>();
                for (int i = 0; i < names.length; i++) {
                    final int idx = i;
                    Tile o = new Tile(names[i], symbol, null);
                    o.value = () -> current.getAsInt() == idx ? "✓" : "";
                    o.stay = () -> {
                        pick.accept(idx);
                        changed();
                        closeSub();
                    };
                    list.add(o);
                }
                openSub(title, list);
                focus = Math.max(0, current.getAsInt());
            };
            return t;
        }

        /** A link into the settings' longer screens (pickers, the shelf, the remote's buttons). */
        private Tile link(String title, String symbol, String sub, String what) {
            Tile t = new Tile(title, symbol, () -> open(new Intent(getContext(), SettingsActivity.class).putExtra("open", what)));
            t.sub = sub;
            return t;
        }

        private void changed() {
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }

        private int at(String[] list, String v) {
            int i = java.util.Arrays.asList(list).indexOf(v);
            return Math.max(0, i);
        }

        private void buildTiles() {
            final Context c = getContext();
            // Rápido: what's used every day.
            List<Tile> quick = section("Rápido", "wifi");
            quick.add(wifi = new Tile("Wi-Fi", "wifi", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
            wifi.on = () -> wifiState;
            quick.add(bluetooth = new Tile("Bluetooth", "bluetooth", () -> open(bluetoothSettings())));
            bluetooth.on = () -> btState;
            final Tile bright = new Tile("Brillo del inicio", "sunny", null);
            bright.wide = true;
            bright.level = () -> 1 - Prefs.launcherDim(c) / 100.0;
            bright.adjust = d -> {   // 10 % to 100 %, a tenth a step
                int now = Math.round(Prefs.launcherDim(c) / 10f) * 10;
                Prefs.setLauncherDim(c, Math.max(0, Math.min(90, now - d * 10)));
            };
            bright.stay = () -> { };
            quick.add(bright);
            final android.media.AudioManager audio = null;   // no volume bar: the remote's buttons do it
            if (audio != null) {
                final Tile volume = new Tile("Volumen", "speaker", null);
                volume.wide = true;
                volume.level = () -> audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        / (double) Math.max(1, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                volume.adjust = d -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        d > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
                volume.stay = () -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                quick.add(volume);
            }
            Tile dream = new Tile("Protector", "bedtime", () -> slideOutThen(() -> {
                try {
                    c.startActivity(screensaverNow().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("screensaver: " + e.getMessage());
                }
                finish();
                overridePendingTransition(0, 0);
            }));
            dream.sub = "Activar ahora";
            quick.add(dream);
            quick.add(toggle("Protector oscuro", "clear_night", () -> DreamView.dark(c), () -> DreamView.setDark(c, !DreamView.dark(c))));

            // Entradas: TV en vivo and the HDMI inputs, straight from here.
            List<Tile> ins = section("Entradas", "hdmi");
            for (final HomeApps.Item it : HomeApps.allInputs(c)) {
                Tile t = new Tile(it.label, it.kind == HomeApps.LIVE_TV ? "live_tv" : "hdmi", () -> {
                    finish();
                    if (!HomeApps.open(QuickSettingsActivity.this, it, null, 0, 0, 0, 0)) AppLog.w("couldn't open " + it.label);
                });
                t.sub = it.kind == HomeApps.LIVE_TV ? "Canales de aire" : it.connected ? "Conectado" : "Sin señal";
                ins.add(t);
            }
            if (ins.isEmpty()) {
                Tile none = new Tile("No hay entradas", "hdmi", null);
                none.stay = () -> { };
                ins.add(none);
            }

            // Inicio: the home screen's own settings (the longer ones open their screen).
            final String[] units = {"auto", "C", "F"}, unitNames = {"Automática", "°C", "°F"};
            List<Tile> home = section("Inicio", "home");
            home.add(tempTile = cycle("Temperatura", "sunny", () -> unitNames[at(units, Prefs.temperature())],
                    d -> Prefs.setTemperature(units[(at(units, Prefs.temperature()) + d + 3) % 3])));
            home.add(cycle("Estilo de la barra", "apps", () -> Prefs.shelfStyle().equals("icons") ? "Íconos" : "Tarjetas",
                    d -> Prefs.setShelfStyle(Prefs.shelfStyle().equals("icons") ? "cards" : "icons")));
            home.add(weatherTile = toggle("Clima", "partly_cloudy_day", Prefs::weather, () -> Prefs.setWeather(!Prefs.weather())));
            home.add(futbolTile = toggle("Fútbol", "ball", Prefs::futbol, () -> Prefs.setFutbol(!Prefs.futbol())));
            home.add(toggle("Continuar viendo", "play", Prefs::watchNext, () -> Prefs.setWatchNext(!Prefs.watchNext())));
            home.add(toggle("Sonidos al navegar", "speaker", Prefs::sounds, () -> Prefs.setSounds(!Prefs.sounds())));
            final String[] langs = SettingsActivity.SettingsView.languageChoices();
            final String[] langNames = new String[langs.length];
            for (int i = 0; i < langs.length; i++) langNames[i] = langs[i].equals("auto") ? "Automático" : Ui.languageName(langs[i]);
            home.add(picker("Idioma", "settings", langNames, () -> at(langs, Prefs.language()), i -> Prefs.setLanguage(langs[i])));
            final Tile city = new Tile("Ciudad", "home", null);
            city.sub = Prefs.city().isEmpty() ? "Elegí tu ciudad" : Prefs.city();
            city.stay = () -> askCity(city);
            home.add(city);
            cityTile = city;
            final String[][] clubs = SettingsActivity.SettingsView.CLUBS, nations = SettingsActivity.SettingsView.NATIONS;
            final String[] clubNames = new String[clubs.length], nationNames = new String[nations.length];
            for (int i = 0; i < clubs.length; i++) clubNames[i] = clubs[i][1];
            for (int i = 0; i < nations.length; i++) nationNames[i] = nations[i][1];
            final Tile clubT = new Tile("Mis equipos", "ball", null);
            clubT.opens = true;
            clubT.value = () -> {
                List<String[]> l = Prefs.clubs();
                String first = l.get(0)[1].isEmpty() ? clubNames[SettingsActivity.SettingsView.indexOf(clubs, l.get(0)[0])] : l.get(0)[1];
                return l.size() == 1 ? first : first + " y " + (l.size() - 1) + " más";
            };
            clubT.stay = this::openTeams;
            home.add(clubT);
            clubTile = clubT;
            final Tile nationT = new Tile("Selección", "ball", null);
            nationT.opens = true;
            nationT.value = () -> !Prefs.nationName().isEmpty() ? Prefs.nationName() : nationNames[SettingsActivity.SettingsView.indexOf(nations, Prefs.nation())];
            nationT.stay = () -> askLive("Selección", "Escribí el país (aparecen mientras escribís)", true);
            home.add(nationT);
            nationTile = nationT;

            // Fondos: what's behind the home screen and the screensaver.
            final String[] bk = Gallery.bingKinds();
            final String[] kinds = new String[bk.length + 2], savers = new String[bk.length + 3];
            kinds[0] = "black";
            System.arraycopy(bk, 0, kinds, 1, bk.length);
            kinds[kinds.length - 1] = Gallery.FAVORITES;
            savers[0] = "home";   // the home's own pictures (the default)
            System.arraycopy(bk, 0, savers, 1, bk.length);
            savers[bk.length + 1] = Gallery.FAVORITES;
            savers[bk.length + 2] = "clock";
            final String[] kindNames = new String[kinds.length], saverNames = new String[savers.length];
            for (int i = 0; i < kinds.length; i++) kindNames[i] = kinds[i].equals("black") ? "Negro" : Gallery.name(kinds[i]);
            for (int i = 0; i < savers.length; i++) saverNames[i] = savers[i].equals("clock") ? "Reloj"
                    : savers[i].equals("home") ? "El fondo del inicio" : Gallery.name(savers[i]);
            final int[] minutes = {60, 360, 720, 1440, 10080};
            final String[] minuteNames = {"1 hora", "6 horas", "12 horas", "1 día", "1 semana"};
            List<Tile> bg = section("Fondos", "sync");
            bg.add(homeBgTile = picker("Fondo del inicio", "sync", kindNames, () -> at(kinds, Prefs.homeBackground(c)),
                    i -> Prefs.setHomeBackground(c, kinds[i])));
            bg.add(cycle("Cambiar cada", "sync", () -> {
                int m = Prefs.homeBackgroundMinutes(c);
                for (int i = 0; i < minutes.length; i++) if (minutes[i] == m) return minuteNames[i];
                return minuteNames[1];
            }, d -> {
                int m = Prefs.homeBackgroundMinutes(c), i = 1;
                for (int j = 0; j < minutes.length; j++) if (minutes[j] == m) i = j;
                Prefs.setHomeBackgroundMinutes(c, minutes[(i + d + minutes.length) % minutes.length]);
            }));
            bg.add(saverTile = picker("Protector de pantalla", "bedtime", saverNames, () -> at(savers, Prefs.screensaver(c)),
                    i -> Prefs.setScreensaver(c, savers[i])));
            bg.add(toggle("Usar galería de favoritos", "sparkles", () -> Prefs.homeBackground(c).equals(Gallery.FAVORITES), () -> {
                String now = Prefs.homeBackground(c);
                if (now.equals(Gallery.FAVORITES)) Prefs.setHomeBackground(c, Prefs.homeBackgroundBefore(c));
                else if (!Gallery.favorites(c).isEmpty()) {
                    Prefs.setHomeBackgroundBefore(c, now);
                    Prefs.setHomeBackground(c, Gallery.FAVORITES);
                }
            }));
            bg.add(toggle("Fotos al azar", "sync", () -> Prefs.homeBackgroundRandom(c), () -> Prefs.setHomeBackgroundRandom(c, !Prefs.homeBackgroundRandom(c))));


            // No "Más": the TV's settings and DLNA go to Rápido, the shelf, the mode and the remote's buttons to Inicio.
            List<Tile> more = home;
            Tile tv = new Tile("Ajustes de la TV", "tv_settings", () -> open(new Intent(Settings.ACTION_SETTINGS)));
            tv.sub = "Imagen, sonido, red, apps";
            quick.add(tv);
            if (RemoteKeys.supported()) more.add(link("Botones del control", "gamepad", "Qué abre cada botón", "keys"));
            final Tile mode = new Tile("Pantalla de inicio", "home", null);
            final long[] armed = {0};
            final boolean freeNow = HomeMode.FREE.equals(HomeMode.state(c).optString("mode"));   // read once, not every frame
            mode.value = () -> freeNow ? "Facurrin TV" : "Original";
            mode.stay = () -> {   // a big change: OK twice (the first only asks)
                boolean free = HomeMode.FREE.equals(HomeMode.state(c).optString("mode"));
                long now = android.os.SystemClock.uptimeMillis();
                if (now - armed[0] > 4000) {
                    armed[0] = now;
                    mode.sub = "OK otra vez para pasar a " + (free ? "Original" : "Facurrin TV");
                } else {
                    armed[0] = 0;
                    HomeMode.request(c, free ? HomeMode.ORIGINAL : HomeMode.FREE);
                    mode.sub = "Cambiando…";
                }
                invalidate();
            };
            more.add(mode);
            List<Tile> info = section("Info", "sparkles");
            Tile made = new Tile(Ui.NAME, "sparkles", null);
            made.sub = "Hecho por Facurrin · basado en Free My TV (código abierto)";
            made.stay = () -> { };
            info.add(made);
            Tile ver = new Tile("Versión", "settings", null);
            String vn = "";
            try {
                vn = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            final String vName = vn == null ? "" : vn;
            ver.value = () -> vName;
            ver.stay = () -> { };
            info.add(ver);
            Tile give = new Tile("Apoyá a Facurrin", "sparkles", null);
            give.sub = "PayPal o transferencia, para seguir mejorándola";
            give.opens = true;
            give.stay = () -> {
                List<Tile> one = new ArrayList<>();
                one.add(donateTile);
                openSub("Apoyá a Facurrin", one);
            };
            info.add(give);
            Tile again = new Tile("Ver la bienvenida otra vez", "home", null);
            again.sub = "Ciudad, equipo y fondo, paso a paso";
            again.stay = this::startWelcome;
            info.add(again);
            String services = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services != null && services.contains(c.getPackageName() + "/")) {
                quick.add(toggle("Recibir videos (DLNA)", "play", () -> Dlna.enabled(c), () -> Dlna.setEnabled(c, !Dlna.enabled(c))));
            }
        }

        /** A box in the panel to type in (the TV's keyboard comes up); `then` gets the text. City, club, team. */
        boolean editing;
        private Tile editTile;
        private java.util.function.Consumer<String> editThen;

        private boolean liveMode;

        private void askCity(final Tile cityTile) {
            liveMode = false;
            editTile = cityTile;
            askText("Ciudad", "Escribí tu ciudad y apretá Buscar", text -> searchCity(text, cityTile));
        }

        private void askText(String title, String hint, java.util.function.Consumer<String> then) {
            editThen = then;
            List<Tile> box = new ArrayList<>();
            Tile field = new Tile("", "search", null);
            field.stay = () -> { };
            box.add(field);
            openSub(title, box);
            editing = true;
            float x0 = getWidth() - 740 * k, cx = x0 + 186 * k, cr = x0 + 740 * k - 34 * k, rowTop = 136 * k;
            if (welcomeMode) {
                cx = cardLeft() + CARD_PAD * k;
                cr = cardLeft() + (CARD_W - CARD_PAD) * k;
                rowTop = (CARD_TOP + ROWS_AT) * k;
            }
            float fh = 76 * k;   // as tall as the row's inside: the letters are never cut
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    Math.round(cr - cx - 100 * k), Math.round(fh));
            lp.leftMargin = Math.round(cx + 74 * k);
            lp.topMargin = Math.round(rowTop + (84 * k - fh) / 2);
            cityField.setLayoutParams(lp);
            cityField.setPadding(0, 0, 0, 0);
            cityField.setIncludeFontPadding(false);
            cityField.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            cityField.setHint(hint);
            cityField.setText("");
            cityField.setVisibility(View.VISIBLE);
            cityField.setOnEditorActionListener((v, act, ev) -> {
                String text = cityField.getText().toString();
                if (liveMode) {   // a team: "Buscar" just searches now; the matches are already under the box
                    removeCallbacks(liveRun);
                    liveSearch(text);
                    return true;
                }
                stopEditing();
                if (editThen != null) editThen.accept(text);
                return true;
            });
            cityField.requestFocus();
            cityField.post(() -> {
                android.view.inputmethod.InputMethodManager im = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.showSoftInput(cityField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            invalidate();
        }

        void stopEditing() {
            if (!editing) return;
            editing = false;
            android.view.inputmethod.InputMethodManager im = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(cityField.getWindowToken(), 0);
            cityField.setVisibility(View.GONE);
            closeSub();
        }

        /** Typing a team: the box on top, the matches under it as the letters come (ESPN), ↓ goes down to them. */
        private boolean liveNation;
        private final Runnable liveRun = () -> liveSearch(cityField.getText().toString());
        private android.text.TextWatcher liveWatch;

        private void askLive(String title, String hint, boolean nation) {
            liveNation = nation;
            askText(title, hint, text -> liveSearch(text));
            liveMode = true;
            if (liveWatch == null) {
                liveWatch = new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(android.text.Editable s) {
                        if (!editing || editThen == null) return;
                        removeCallbacks(liveRun);
                        postDelayed(liveRun, 350);
                    }
                };
                cityField.addTextChangedListener(liveWatch);
            }
        }

        /** The matches for what's typed: the Liga's clubs (or South America's teams) by their name at once, then ESPN's. */
        private void liveSearch(final String text) {
            final String q2 = text.trim();
            if (subList == null || q2.isEmpty()) return;
            final boolean nation = liveNation;
            final List<String[]> local = new ArrayList<>();
            String[][] mine = nation ? SettingsActivity.SettingsView.NATIONS : SettingsActivity.SettingsView.CLUBS;
            String low = q2.toLowerCase(java.util.Locale.ROOT);
            for (String[] t : mine) if (t[1].toLowerCase(java.util.Locale.ROOT).contains(low)) local.add(new String[] {t[0], t[1], ""});
            showMatches(local, nation);
            if (q2.length() < 3) return;
            new Thread(() -> {
                final List<String[]> found = new ArrayList<>(local);
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                            "https://site.web.api.espn.com/apis/common/v3/search?type=team&limit=20&query="
                                    + java.net.URLEncoder.encode(q2, "UTF-8")).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream(), "UTF-8"))) {
                        for (String line; (line = rd.readLine()) != null; ) sb.append(line);
                    }
                    org.json.JSONArray items = new org.json.JSONObject(sb.toString()).optJSONArray("items");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        org.json.JSONObject o = items.optJSONObject(i);
                        if (o == null || !"soccer".equals(o.optString("sport"))) continue;
                        boolean dup = false;
                        for (String[] f : found) dup |= f[0].equals(o.optString("id"));
                        if (!dup) found.add(new String[] {o.optString("id"), o.optString("displayName"), o.optString("league")});
                    }
                } catch (Exception e) {
                    AppLog.w("team search: " + e.getMessage());
                }
                post(() -> {
                    if (q2.equals(cityField.getText().toString().trim()) || !editing) showMatches(found, nation);
                });
            }).start();
        }

        /** "Mis equipos": each club followed (OK takes it off, with a second OK), and a row to add one. */
        private void openTeams() {
            final String[][] liga = SettingsActivity.SettingsView.CLUBS;
            List<Tile> list = new ArrayList<>();
            final List<String[]> mine = Prefs.clubs();
            for (final String[] t : mine) {
                String name = t[1].isEmpty() ? liga[SettingsActivity.SettingsView.indexOf(liga, t[0])][1] : t[1];
                final Tile row = new Tile(name, "ball", null);
                final long[] armed = {0};
                row.sub = mine.size() > 1 ? "OK para quitarlo" : "Tu único equipo";
                row.stay = () -> {
                    if (mine.size() <= 1) return;   // at least one
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - armed[0] > 3000) {
                        armed[0] = now;
                        row.sub = "OK otra vez para quitarlo";
                        invalidate();
                        return;
                    }
                    List<String[]> left = new ArrayList<>();
                    for (String[] o : Prefs.clubs()) if (!o[0].equals(t[0])) left.add(o);
                    Prefs.setClubs(left);
                    changed();
                    closeSub();
                    openTeams();
                };
                list.add(row);
            }
            Tile add = new Tile("+ Agregar equipo", "ball", null);
            add.sub = "Buscalo escribiendo";
            add.stay = () -> {
                closeSub();
                askLive("Agregar equipo", "Escribí el club (aparecen mientras escribís)", false);
            };
            list.add(add);
            openSub("Mis equipos", list);
        }

        /** The box stays first; the matches follow it. */
        private void showMatches(List<String[]> found, final boolean nation) {
            if (subList == null || subList.isEmpty()) return;
            Tile box = subList.get(0);
            List<Tile> list = new ArrayList<>();
            list.add(box);
            for (final String[] tm : found) {
                Tile t = new Tile(tm[1], "ball", null);
                t.sub = tm[2].isEmpty() ? "" : tm[2].toUpperCase(java.util.Locale.ROOT);
                t.stay = () -> {
                    if (nation) {
                        Prefs.setNation(tm[0]);
                        Prefs.setNationName(tm[1]);
                    } else {   // one more club in "Mis equipos"
                        List<String[]> l = Prefs.clubs();
                        boolean have = false;
                        for (String[] o : l) have |= o[0].equals(tm[0]);
                        if (!have) l.add(new String[] {tm[0], tm[1]});
                        Prefs.setClubs(l);
                    }
                    changed();
                    leaveField();
                    closeSub();
                };
                list.add(t);
            }
            subList = list;
            invalidate();
        }

        /** Down from the box to the matches: the keyboard goes, what was typed stays written in the box. */
        boolean downFromField() {
            if (!editing || subList == null || subList.size() < 2) return false;
            leaveField();
            focus = 1;
            invalidate();
            return true;
        }

        private void leaveField() {
            if (!editing) return;
            editing = false;
            if (subList != null && !subList.isEmpty()) {
                Tile box = subList.get(0);
                String typed = cityField.getText().toString();
                subList.set(0, new Tile(typed.isEmpty() ? " " : typed, "search", null));
                subList.get(0).stay = () -> { };
            }
            android.view.inputmethod.InputMethodManager im = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(cityField.getWindowToken(), 0);
            cityField.setVisibility(View.GONE);
        }

        /** Any football team by name (ESPN's search): the matches listed; OK makes it the club (or the team). */
        private void searchTeam(final String name, final boolean nation) {
            if (name.trim().isEmpty()) return;
            new Thread(() -> {
                final List<String[]> found = new ArrayList<>();   // {id, name, league}
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                            "https://site.web.api.espn.com/apis/common/v3/search?type=team&limit=20&query="
                                    + java.net.URLEncoder.encode(name.trim(), "UTF-8")).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream(), "UTF-8"))) {
                        for (String line; (line = rd.readLine()) != null; ) sb.append(line);
                    }
                    org.json.JSONArray items = new org.json.JSONObject(sb.toString()).optJSONArray("items");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        org.json.JSONObject o = items.optJSONObject(i);
                        if (o == null || !"soccer".equals(o.optString("sport"))) continue;
                        found.add(new String[] {o.optString("id"), o.optString("displayName"), o.optString("league")});
                    }
                } catch (Exception e) {
                    AppLog.w("team search: " + e.getMessage());
                }
                post(() -> {
                    List<Tile> list = new ArrayList<>();
                    for (final String[] tm : found) {
                        Tile t = new Tile(tm[1], "ball", null);
                        t.sub = tm[2].isEmpty() ? "" : tm[2].toUpperCase(java.util.Locale.ROOT);
                        t.stay = () -> {
                            if (nation) {
                                Prefs.setNation(tm[0]);
                                Prefs.setNationName(tm[1]);
                            } else {
                                Prefs.setClub(tm[0]);
                                Prefs.setClubName(tm[1]);
                            }
                            changed();
                            closeSub();
                        };
                        list.add(t);
                    }
                    if (list.isEmpty()) {
                        Tile none = new Tile("No encontré ese equipo", "ball", null);
                        none.stay = this::closeSub;
                        list.add(none);
                    }
                    openSub(nation ? "Elegí la selección" : "Elegí tu club", list);
                });
            }).start();
        }

        private void searchCity(final String name, final Tile cityTile) {
            if (name.trim().isEmpty()) return;
            cityTile.sub = "Buscando…";
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                try {
                    found = HomeData.findCity(name);
                } catch (Exception e) {
                    found = new ArrayList<>();
                    AppLog.w("city search: " + e.getMessage());
                }
                final List<String[]> result = found;
                post(() -> {
                    cityTile.sub = result.isEmpty() ? "No encontré esa ciudad" : Prefs.city();
                    if (result.isEmpty()) {
                        invalidate();
                        return;
                    }
                    List<Tile> list = new ArrayList<>();
                    for (final String[] ct : result) {
                        Tile t = new Tile(ct[0], "home", null);
                        t.stay = () -> {
                            Prefs.setCity(ct[1], ct[2], ct[3]);
                            HomeData.get(getContext()).settingsChanged();
                            cityTile.sub = Prefs.city();
                            closeSub();
                        };
                        list.add(t);
                    }
                    openSub("Elegí tu ciudad", list);
                });
            }).start();
        }

        /** The shelf: every app; OK puts it on the bar (or takes it off), ← → moves it earlier or later. */
        private void openShelf() {
            final Context c = getContext();
            final List<String> order = new ArrayList<>();
            PackageManager pm = c.getPackageManager();
            for (String pkg : HomeApps.shelfPackages(c)) if (HomeApps.launchIntent(pm, pkg) != null) order.add(pkg);
            List<Tile> list = new ArrayList<>();
            for (final String[] app : HomeApps.shelfCandidates(c)) {
                final Tile t = new Tile(app[1], "apps", null);
                t.on = () -> order.contains(app[0]);
                t.stay = () -> {
                    if (order.contains(app[0])) order.remove(app[0]);
                    else if (order.size() < HomeApps.MAX_SHELF) order.add(app[0]);
                    Prefs.setShelf(order);
                    invalidate();
                };
                t.cycle = d -> {   // ← → : one place earlier or later on the bar
                    int at = order.indexOf(app[0]), to = at + d;
                    if (at < 0 || to < 0 || to >= order.size()) return;
                    order.remove(at);
                    order.add(to, app[0]);
                    Prefs.setShelf(order);
                    invalidate();
                };
                t.value = () -> {
                    int at = order.indexOf(app[0]);
                    return at < 0 ? "" : "Lugar " + (at + 1);
                };
                list.add(t);
            }
            openSub("Estante", list);
        }

        private void arrangeTiles() {
            tiles.clear();
            for (List<Tile> l : sectTiles) tiles.addAll(l);
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
            if (welcomeMode) return keyWelcome(e);
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                if (isOk(e.getKeyCode()) && okPending) {   // a row with a hold: a short press acts on release
                    okPending = false;
                    if (!held) curTile().stay.run();
                    held = false;
                }
                return e.getKeyCode() == KeyEvent.KEYCODE_BACK || isOk(e.getKeyCode());
            }
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    if (subList != null) closeSub();   // back to the section
                    else if (side == 1) side = 0;      // back to the rail
                    else close();
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                    if (side == 0) sect = Math.max(0, Math.min(sectNames.size() - 1, sect + d));
                    else focus = Math.max(0, Math.min(rows().size() - 1, focus + d));
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (side == 0) {
                        if (d > 0) {
                            side = 1;
                            focus = 0;
                        }
                        break;
                    }
                    Tile cur = curTile();
                    if (cur.wide) cur.adjust.accept(d);        // a slider moves
                    else if (cur.cycle != null) cur.cycle.accept(d);   // a value steps
                    else if (d < 0 && subList != null) closeSub();
                    else if (d < 0) side = 0;                   // ← back to the rail
                    break;
                }
                default:
                    if (!isOk(e.getKeyCode())) return false;
                    if (e.getRepeatCount() > 0) return true;
                    if (side == 0) {
                        side = 1;
                        focus = 0;
                        break;
                    }
                    Tile tile = curTile();
                    if (tile.stay != null) tile.stay.run();
                    else if (tile.action != null) tile.action.run();
                    break;
            }
            invalidate();
            return true;
        }

        private boolean isOk(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (welcomeMode) {
                drawWelcome(c);
                return;
            }
            float w = getWidth(), h = getHeight(), pw = 740 * k;
            // The scrim over what's behind, darker towards the panel.
            fill.setShader(new LinearGradient(w - pw - 300 * k, 0, w - pw, 0, 0x00000000, Ui.alpha(0x99000000, slide), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            float x0 = w - pw * slide;
            panelRect.set(x0, 0, x0 + pw + 60 * k, h);
            glassPanel(c, panelRect, 44 * k, w, h);
            drawColumns(c, x0, pw);
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

        private TextPaint colTitle, railName, valuePaint;

        /** U2: a rail of icons on the left (the sections), the open section's rows on the right. */
        private void drawColumns(Canvas c, float x0, float pw) {
            if (colTitle == null) {
                colTitle = new TextPaint(Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF));
                railName = new TextPaint(Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF));
                railName.setTextAlign(Paint.Align.CENTER);
                valuePaint = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xCCFFFFFF));
                valuePaint.setTextAlign(Paint.Align.RIGHT);
            }
            float h = getHeight();
            float rl = x0 + 20 * k, rw = 124 * k, ih = 150 * k, ig = 26 * k;
            int ns = sectNames.size();
            float rt = Math.max(54 * k, (h - (ns * ih + Math.max(0, ns - 1) * ig)) / 2);   // centred in the height
            for (int i = 0; i < ns; i++) {
                r.set(rl, rt + i * (ih + ig), rl + rw, rt + i * (ih + ig) + ih);
                boolean f = side == 0 && i == sect;
                fill.setShader(null);
                if (f) {
                    fill.setColor(0xFFF5F5F7);
                    c.drawRoundRect(r, 26 * k, 26 * k, fill);
                } else if (i == sect) {
                    fill.setColor(0x33FFFFFF);
                    c.drawRoundRect(r, 26 * k, 26 * k, fill);
                }
                int ink = f ? 0xFF1C1C1E : 0xF2FFFFFF;
                drawGlyph(c, sectIcons.get(i), r.centerX(), r.top + 58 * k, 19 * k, ink);
                railName.setColor(ink);
                c.drawText(sectNames.get(i), r.centerX(), r.top + 122 * k, railName);
            }
            fill.setColor(0x2EFFFFFF);
            c.drawRect(x0 + 160 * k, 50 * k, x0 + 162 * k, h - 50 * k, fill);
            // The section: its name, then its rows (scrolled so the focused one shows).
            float cx = x0 + 186 * k, cr = x0 + pw - 34 * k;
            if (sectNames.isEmpty()) return;
            c.drawText(subList != null ? subTitle : sectNames.get(sect), cx + 6 * k, 100 * k, colTitle);
            if (subList != null && !subList.isEmpty() && subList.get(0) == donateTile) {
                drawDonate(c, cx, cr);
                return;
            }
            List<Tile> l = rows();
            float top = 136 * k, bottom = h - 34 * k, y = top, scroll = 0;
            for (int i = 0; i < l.size(); i++) {
                float hh = (l.get(i).wide ? 70 : 84) * k;
                if (side == 1 && i == focus && y + hh > bottom) scroll = y + hh - bottom;
                y += hh + 12 * k;
            }
            c.save();
            c.clipRect(cx - 12 * k, top - 10 * k, cr + 12 * k, bottom + 8 * k);
            y = top - scroll;
            for (int i = 0; i < l.size(); i++) {
                Tile t = l.get(i);
                float hh = (t.wide ? 70 : 84) * k;
                t.rect.set(cx, y, cr, y + hh);
                drawRow(c, t, side == 1 && focus == i);
                y += hh + 12 * k;
            }
            c.restore();
        }

        /** "Apoyá a Facurrin": a few words, PayPal's QR (scanned with the phone) and the alias for a transfer. */
        private final Tile donateTile = new Tile("", "sparkles", null);
        {
            donateTile.stay = () -> { };
        }
        private Bitmap donateQr;
        private boolean donateQrRead;
        private TextPaint donateText, donateBig;
        private final Paint qrPaint = new Paint();   // no filtering: the modules stay sharp squares

        private void drawDonate(Canvas c, float cx, float cr) {
            if (!donateQrRead) {
                donateQrRead = true;
                try (java.io.InputStream in = getContext().getAssets().open("donate/paypal.png")) {
                    donateQr = android.graphics.BitmapFactory.decodeStream(in);
                } catch (Exception e) {
                    AppLog.w("donate qr: " + e.getMessage());
                }
                donateText = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xD9FFFFFF));
                donateBig = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
            }
            float x = cx + 6 * k, y = 160 * k;
            c.drawText("Si te gusta, podés ayudarme a seguir", x, y, donateText);
            c.drawText("mejorándola (y a pagar las herramientas).", x, y + 34 * k, donateText);
            // PayPal's QR on a white square, big enough to scan from the couch.
            float s = 300 * k, qx = cx + (cr - cx - s) / 2, qy = y + 72 * k;
            r.set(qx - 22 * k, qy - 22 * k, qx + s + 22 * k, qy + s + 22 * k);
            fill.setShader(null);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, 24 * k, 24 * k, fill);
            if (donateQr != null) {
                r.set(qx, qy, qx + s, qy + s);
                c.drawBitmap(donateQr, null, r, qrPaint);
            }
            float ty = qy + s + 70 * k;
            donateBig.setTextAlign(Paint.Align.CENTER);
            donateText.setTextAlign(Paint.Align.CENTER);
            float mid = (cx + cr) / 2;
            c.drawText("PayPal · paypal.me/facurrin", mid, ty, donateBig);
            c.drawText("Escaneá el código con el celular", mid, ty + 36 * k, donateText);
            // From Argentina: a transfer to the alias, any amount, no fees.
            r.set(cx, ty + 72 * k, cr, ty + 72 * k + 112 * k);
            box(c, r, 22 * k, false);
            c.drawText("Desde Argentina, transferí a", mid, r.top + 44 * k, donateText);
            c.drawText("Alias: facurrin  (Brubank)", mid, r.top + 86 * k, donateBig);
            donateBig.setTextAlign(Paint.Align.LEFT);
            donateText.setTextAlign(Paint.Align.LEFT);
        }

        /** One row at its `rect`: glass (white when focused), its symbol, title and line, and its value or switch. */
        private void drawRow(Canvas c, Tile t, boolean on) {
            if (valuePaint == null) {
                colTitle = new TextPaint(Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF));
                railName = new TextPaint(Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF));
                railName.setTextAlign(Paint.Align.CENTER);
                valuePaint = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xCCFFFFFF));
                valuePaint.setTextAlign(Paint.Align.RIGHT);
            }
            r.set(t.rect);
            if (t.wide) {
                drawSlider(c, t, on);
                return;
            }
            box(c, r, 22 * k, on);
            int ink = on ? 0xFF1C1C1E : 0xEBFFFFFF;
            drawGlyph(c, t.symbol, r.left + 38 * k, r.centerY(), 13 * k, ink);
            title.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
            sub.setColor(on ? 0x991C1C1E : 0x99FFFFFF);
            float tx = r.left + 76 * k;
            String vv = t.value != null ? t.value.get() : "";
            String val = vv.isEmpty() ? "" : t.cycle != null && t.on == null ? "‹  " + vv + "  ›" : t.opens ? vv + "   ›" : vv;
            valuePaint.setColor(on ? 0xB31C1C1E : 0xCCFFFFFF);
            float rightRoom = t.on != null ? (val.isEmpty() ? 100 * k : 200 * k) : !val.isEmpty() ? valuePaint.measureText(val) + 40 * k
                    : t.action != null ? 50 * k : 24 * k;
            float room = r.right - tx - rightRoom;
            boolean two = t.sub != null && !t.sub.isEmpty();
            c.drawText(fit(t.title, title, room), tx, two ? r.centerY() - 4 * k : r.centerY() + Ui.halfCap(title), title);
            if (two) c.drawText(fit(t.sub, sub, room), tx, r.centerY() + 24 * k, sub);
            if (t.on != null) {
                drawSwitch(c, r.right - 26 * k, r.centerY(), t.on.getAsBoolean(), on);
                if (!val.isEmpty()) c.drawText(val, r.right - 110 * k, r.centerY() + Ui.halfCap(valuePaint), valuePaint);
            } else if (!val.isEmpty()) c.drawText(val, r.right - 26 * k, r.centerY() + Ui.halfCap(valuePaint), valuePaint);
            else if (t.action != null) c.drawText("›", r.right - 26 * k, r.centerY() + Ui.halfCap(valuePaint), valuePaint);
        }

        // MARK: The welcome (B1): one step at a time on a glass card in the middle, over the home's picture blurred.

        boolean welcomeMode;
        private int step, btn = 1;
        private final List<List<Tile>> stepRows = new ArrayList<>();
        private Tile tempTile, weatherTile, futbolTile, cityTile, clubTile, nationTile, homeBgTile, saverTile;
        private static final float CARD_W = 900, CARD_TOP = 120, CARD_H = 820, CARD_PAD = 60, ROWS_AT = 300;
        private final String[] stepTitle = {"Hola, bienvenido", "¿Dónde estás?", "Tu equipo", "El fondo", "¡Listo!"};
        private final String[] stepSub = {
                "Facurrin TV: un inicio limpio y rápido para tu tele.\nConfigurémoslo en un minuto; cada paso se puede saltear.",
                "Para el clima y la hora a la que el inicio se pone oscuro.",
                "Sus partidos y resultados en el inicio y en el protector.",
                "Fotos del día detrás del inicio y en el protector de pantalla.",
                "Ya podés usar tu tele.\nTodo esto se cambia después en el centro de control."};
        private TextPaint wTitle, wSub, wStep, wBtn, wCredit;
        private LinearGradient welcomeBg;

        private void buildWelcome() {
            for (int i = 0; i < stepTitle.length; i++) stepRows.add(new ArrayList<>());
            stepRows.get(1).add(cityTile);
            stepRows.get(1).add(tempTile);
            stepRows.get(1).add(weatherTile);
            stepRows.get(2).add(clubTile);
            stepRows.get(2).add(nationTile);
            stepRows.get(2).add(futbolTile);
            stepRows.get(3).add(homeBgTile);
            stepRows.get(3).add(saverTile);
        }

        void startWelcome() {
            if (editing) stopEditing();
            welcomeMode = true;
            subList = null;
            goStep(0);
        }

        private void goStep(int s) {
            if (editing) stopEditing();
            subList = null;
            step = Math.max(0, Math.min(stepTitle.length - 1, s));
            focus = 0;
            onButtons = stepRows.get(step).isEmpty();
            btn = 1;
            invalidate();
        }

        private boolean onButtons;

        private void finishWelcome() {
            Prefs.setWelcomed(true);
            changed();
            welcomeMode = false;
            if (welcomeStart) {
                finish();
                return;
            }
            side = 0;
            focus = 0;
            invalidate();
        }

        private float cardLeft() {
            return (getWidth() - CARD_W * k) / 2;
        }

        boolean keyWelcome(KeyEvent e) {
            int code = e.getKeyCode();
            if (e.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_BACK || isOk(code);
            List<Tile> l = rows();
            boolean last = step == stepTitle.length - 1;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (subList != null) closeSub();
                    else if (step > 0) goStep(step - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (onButtons && !l.isEmpty() && subList == null) {
                        onButtons = false;
                        focus = l.size() - 1;
                    } else if (!onButtons) focus = Math.max(0, focus - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (onButtons) break;
                    if (focus < l.size() - 1) focus++;
                    else if (subList == null) onButtons = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (onButtons) {
                        btn = Math.max(step == 0 ? 1 : 0, Math.min(1, btn + d));
                        break;
                    }
                    if (l.isEmpty()) break;
                    Tile cur = curTile();
                    if (cur.cycle != null) cur.cycle.accept(d);
                    else if (d < 0 && subList != null) closeSub();
                    break;
                }
                default:
                    if (!isOk(code)) return false;
                    if (e.getRepeatCount() > 0) return true;
                    if (onButtons) {
                        if (btn == 0) goStep(step - 1);
                        else if (last) finishWelcome();
                        else goStep(step + 1);
                        break;
                    }
                    if (l.isEmpty()) break;
                    Tile tile = curTile();
                    if (tile.stay != null) tile.stay.run();
                    else if (tile.action != null) tile.action.run();
                    break;
            }
            invalidate();
            return true;
        }

        private void drawWelcome(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (wTitle == null) {
                wTitle = new TextPaint(Ui.text(Ui.displaySemibold, 54 * k, 0xFFFFFFFF));
                wTitle.setTextAlign(Paint.Align.CENTER);
                wSub = new TextPaint(Ui.text(Ui.regular, 25 * k, 0xC7FFFFFF));
                wSub.setTextAlign(Paint.Align.CENTER);
                wStep = new TextPaint(Ui.text(Ui.semibold, 19 * k, 0x99FFFFFF));
                wStep.setTextAlign(Paint.Align.CENTER);
                wStep.setLetterSpacing(0.14f);
                wBtn = new TextPaint(Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF));
                wBtn.setTextAlign(Paint.Align.CENTER);
                wCredit = new TextPaint(Ui.text(Ui.medium, 21 * k, 0x99FFFFFF));
                wCredit.setTextAlign(Paint.Align.CENTER);
                welcomeBg = new LinearGradient(0, 0, 0, 1, new int[] {0xFF1D2A40, 0xFF3A2F3F, 0xFF0E0E14}, null, Shader.TileMode.CLAMP);
            }
            // Behind: the home's picture blurred and darkened (or a dusk gradient before there is one).
            fill.setShader(null);
            if (blur != null) {
                glassMatrix.setScale(w / Backdrop.FW, h / Backdrop.FH);
                blurPaint.getShader().setLocalMatrix(glassMatrix);
                c.drawRect(0, 0, w, h, blurPaint);
                fill.setColor(0x66000000);
                c.drawRect(0, 0, w, h, fill);
            } else {
                panelRect.set(0, 0, w, h);
                place(welcomeBg, panelRect, false);
                fill.setShader(welcomeBg);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
            }
            float cl = cardLeft(), ct = CARD_TOP * k, cw = CARD_W * k, cb = ct + CARD_H * k, mid = cl + cw / 2;
            panelRect.set(cl, ct, cl + cw, cb);
            glassPanel(c, panelRect, 44 * k, w, h);
            // The dots: where you are.
            int n = stepTitle.length;
            float dx = 30 * k, d0 = mid - (n - 1) * dx / 2;
            for (int i = 0; i < n; i++) {
                fill.setColor(i == step ? 0xFFFFFFFF : 0x66FFFFFF);
                if (i == step) {
                    r.set(d0 + i * dx - 16 * k, ct + 60 * k - 6 * k, d0 + i * dx + 16 * k, ct + 60 * k + 6 * k);
                    c.drawRoundRect(r, 6 * k, 6 * k, fill);
                } else c.drawCircle(d0 + i * dx, ct + 60 * k, 6 * k, fill);
            }
            boolean sub2 = subList != null;
            c.drawText(sub2 ? "ELEGÍ UNA OPCIÓN" : "PASO " + (step + 1) + " DE " + n, mid, ct + 122 * k, wStep);
            c.drawText(sub2 ? subTitle : stepTitle[step], mid, ct + 190 * k, wTitle);
            String[] lines = (sub2 ? "" : stepSub[step]).split("\n");
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], mid, ct + 240 * k + i * 36 * k, wSub);
            // The rows (scrolled so the focused one shows).
            List<Tile> l = rows();
            float top = ct + ROWS_AT * k, bottom = cb - 140 * k, x0 = cl + CARD_PAD * k, x1 = cl + cw - CARD_PAD * k;
            if (step == 0 && !sub2) top = ct + 340 * k;
            float y = top, scroll = 0;
            for (int i = 0; i < l.size(); i++) {
                float hh = 84 * k;
                if (!onButtons && i == focus && y + hh > bottom) scroll = y + hh - bottom;
                y += hh + 12 * k;
            }
            c.save();
            c.clipRect(x0 - 14 * k, top - 10 * k, x1 + 14 * k, bottom + 8 * k);
            y = top - scroll;
            for (int i = 0; i < l.size(); i++) {
                Tile t = l.get(i);
                t.rect.set(x0, y, x1, y + 84 * k);
                drawRow(c, t, !onButtons && focus == i);
                y += 96 * k;
            }
            c.restore();
            // The buttons: ← Atrás and Siguiente → (the first step only goes on, the last one starts).
            if (!sub2) {
                float bh = 72 * k, by = cb - 60 * k - bh, gap = 24 * k, bw = (x1 - x0 - gap) / 2;
                String next = step == 0 ? "Empezar  →" : step == n - 1 ? "Empezar a usar la tele" : "Siguiente  →";
                if (step == 0) {
                    r.set(x0 + bw / 2 + gap / 2, by, x1 - bw / 2 - gap / 2, by + bh);
                    drawButton(c, next, onButtons && btn == 1);
                } else {
                    r.set(x0, by, x0 + bw, by + bh);
                    drawButton(c, "←  Atrás", onButtons && btn == 0);
                    r.set(x1 - bw, by, x1, by + bh);
                    drawButton(c, next, onButtons && btn == 1);
                }
            }
            c.drawText(CREDIT, w / 2, h - 52 * k, wCredit);
        }

        private void drawButton(Canvas c, String label, boolean on) {
            float rad = r.height() / 2;
            box(c, r, rad, on);
            wBtn.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
            c.drawText(label, r.centerX(), r.centerY() + Ui.halfCap(wBtn), wBtn);
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
                    // A real blur at 192 × 108 (box blur, three passes): smooth when stretched, no blocks.
                    Bitmap small = Bitmap.createScaledBitmap(mid, Backdrop.FW, Backdrop.FH, true);
                    mid.recycle();
                    int fw = Backdrop.FW, fh = Backdrop.FH;
                    int[] px = new int[fw * fh], tp = new int[fw * fh];
                    small.getPixels(px, 0, fw, 0, 0, fw, fh);
                    for (int pass = 0; pass < 3; pass++) {
                        blurLine(px, tp, fw, fh, 6, true);
                        blurLine(tp, px, fw, fh, 6, false);
                    }
                    Bitmap up = Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888);
                    small.recycle();
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

        /** One box-blur pass along the rows or the columns (opaque pixels). */
        private void blurLine(int[] in, int[] out, int w, int h, int r, boolean rows) {
            int lines = rows ? h : w, len = rows ? w : h, div = 2 * r + 1;
            for (int l = 0; l < lines; l++) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    int q2 = Math.max(0, Math.min(len - 1, i)), p = in[rows ? l * w + q2 : q2 * w + l];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
                }
                for (int i = 0; i < len; i++) {
                    out[rows ? l * w + i : i * w + l] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                    int ai = Math.min(len - 1, i + r + 1), si = Math.max(0, i - r);
                    int ad = in[rows ? l * w + ai : ai * w + l], sd = in[rows ? l * w + si : si * w + l];
                    sr += ((ad >> 16) & 255) - ((sd >> 16) & 255);
                    sg += ((ad >> 8) & 255) - ((sd >> 8) & 255);
                    sb += (ad & 255) - (sd & 255);
                }
            }
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
            } else if (which.equals("ball")) {   // a football: a ring, a pentagon in the middle, its seams
                glyph.setColor(color);
                glyph.setStrokeWidth(2.4f * k);
                c.drawCircle(cx, cy, u, glyph);
                path.reset();
                for (int i = 0; i < 5; i++) {
                    double a = Math.toRadians(-90 + 72 * i);
                    float px = cx + (float) Math.cos(a) * u * 0.4f, py = cy + (float) Math.sin(a) * u * 0.4f;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                    c.drawLine(px, py, cx + (float) Math.cos(a) * u, cy + (float) Math.sin(a) * u, glyph);
                }
                path.close();
                fill.setColor(color);
                c.drawPath(path, fill);
            } else {
                Symbols.draw(getContext(), c, which, cx, cy, u * 1.9f, color);
            }
        }
    }
}
