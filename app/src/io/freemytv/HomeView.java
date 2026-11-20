package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the football game
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and the week's football games); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 28, TILE_GAP = 34;   // compact shelf for a 65" set (was 40, 48)
    private static final float TILE_RADIUS = 17;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 6, VISIBLE_ROWS = 4;   // compact grid for a 65" set (was 5 × 3)
    private static final float GRID_W = 232, GRID_H = 130, GRID_GAP = 34, GRID_ROW = 196, GRID_TOP = 196, GRID_RADIUS = 17;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, WATCH = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    // Glass edition: CleanYourTV Glass's dock size (tiles up to 279 × 157 with five apps; more apps, smaller tiles),
    // keeping the same bottom edge (was 850, 84).
    private static final float L_PLATTER_TOP = 777, L_TILE_H = 157;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 700;
    // Glass edition clock block: the date centered over a big "stadium" clock, the weather loose under it (icon, big
    // temperature, condition, high and low) and the next four hours; the games under all that (L_QUOTES_BASE).
    private static final float G_LEFT = 72, G_DATE_BASE = 190, G_CLOCK_BASE = 476, G_TEMP_BASE = 566, G_HOUR_BASE = 636;
    private static final float RECENT_W = 196, RECENT_GAP = 24, RECENT_TOP = 368;   // compact (was 268)
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    /** "Seguir en…" (the recent apps, without TMDB picks) and the now-playing widget. */
    private static final int Z_RECENT = 6, Z_PLAYER = 7, Z_AVATAR = 8, Z_PLAYER_CTRL = 9, Z_FUTBOL = 10, Z_WEATHER = 11;
    /** Nothing focused (up from the top row, or Back on the shelf): any arrow brings the focus back where it was. */
    private static final int Z_IDLE = 12;
    private int idleFrom = Z_SHELF;
    /** The floating "Continuar viendo" widget at the centre right (↑ from the shelf enters it). */
    private static final int Z_WATCH = 13;
    /** Glass edition: the open player's progress bar (← → move the playhead). */
    private static final int Z_PLAYER_BAR = 14;
    /** Glass edition: the sleep timer's panel (row 0 its switch, row 1 the times). */
    private static final int Z_SLEEP = 15;
    /** Glass edition: the weather under the clock as a button (OK opens its panel where the clock is, W1). */
    private static final int Z_WXBTN = 16;
    private boolean wxOpen, wxWas;
    /** The drawer under the bar (D2): wide buttons; Entradas unfolds the inputs in place, the team its card. */
    private static final int TEAM_KIND = 30, WX_KIND = 31;
    /** The heart (saves the background on screen) and "Mis fondos" (the saved ones, to pick one). */
    private static final int HEART_KIND = 32, FAVS_KIND = 33, NEXTBG_KIND = 34;
    private static final int Z_FAVS = 17;
    private boolean heartOn, heartSub, favOn;
    private int favRow;   // in the card: 0 the switch (top right), 1 the pictures
    private final RectF favSwitch = new RectF();
    private TextPaint favSwitchText;
    private static final float FAVS_TOP = 808, FAVS_H = 250;   // under the heart's pill, ending above the screen's edge
    private int favCount, favIndex;
    private List<Gallery.Item> favs = new ArrayList<>();
    private final java.util.Map<String, Bitmap> favThumbs = new java.util.HashMap<>();
    private final RectF favsCard = new RectF();
    private TextPaint favHead, favEmpty;
    private final android.graphics.Path heartPath = new android.graphics.Path();
    private Paint heartStroke;
    private static final float CLOCK_LIFT = 56;   // the clock block, up from the bar
    private final RectF weekCard = new RectF();

    private final RectF dockStrip = new RectF();
    private TextPaint dockLabel, dockSub;

    private static boolean circle(HomeApps.Item it) {
        return it.kind == TEAM_KIND || it.kind == WX_KIND || it.kind == HEART_KIND || it.kind == NEXTBG_KIND;
    }
    private int drawerPanel;   // 0 the buttons, 1 the inputs, 2 the team's card
    private List<HomeApps.Item> drawerButtons = new ArrayList<>(), inputsList = new ArrayList<>();
    private TextPaint btnTitle, btnSub, wxHint;
    /** The drawer's weather card: page 0 the next hours, page 1 the next seven days. */
    private final Spring[] weatherFocus = {new Spring(0.26f, 0.85f, 0)};
    private int weatherPage;
    private final String[] dayText = new String[7], dayMaxText = new String[7], dayMinText = new String[7];
    /** The drawer's football card: focused, ←/→ turn its pages by hand (and it stops turning on its own for a while). */
    private final Spring[] futbolFocus = {new Spring(0.26f, 0.85f, 0)};
    private int cardPage;
    private long cardManualAt;
    /** The now-playing controls under the widget: previous, play/pause, next. */
    private final Spring[] ctrlFocus = new Spring[6];
    /** The controls shown: shuffle and repeat only when the playing app supports them. */
    private static final int C_SHUFFLE = 4, C_PREV = 0, C_PLAY = 1, C_NEXT = 2, C_REPEAT = 5, C_STOP = 6;
    private int[] ctrlKinds = {C_PREV, C_PLAY, C_NEXT};
    private final Spring[] ctrlShow = {new Spring(0.28f, 0.8f, 0)};
    private final RectF ctrlRect = new RectF();
    private int ctrlIndex = 1;
    /** The person's choice (OK on the widget): the full capsule or just the cover. */
    private boolean npCollapsed;
    /** The account circle at the top right: the person's photo (assets/profile.jpg) or a silhouette. */
    private final Spring[] avatarFocus = new Spring[1];
    /** Off: the account is in the TV's settings, reached from the quick settings panel. */
    private static final boolean SHOW_AVATAR = false;
    private final RectF avatarRect = new RectF();
    private Bitmap avatar;
    private boolean avatarLoaded;
    private static final int RECENT_MAX = 3;
    private List<HomeApps.Item> recent = new ArrayList<>();
    private final RectF[] recentRects = new RectF[RECENT_MAX];
    private final Spring[] recentFocus = new Spring[RECENT_MAX];
    private final Spring[] playerFocus = new Spring[1];
    private int recentIndex;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, watchLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or football (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    /** Glass edition: five round buttons (Buscar, Hablar, Apagar en, Wi-Fi, Ajustes) after the three tabs. */
    private static final int TOP_N = 4, TOP_LAST = 2 + TOP_N;
    /** The search button grows down into a pill with the microphone under it (focus on it: 1); micFocus: on the mic. */
    private final Spring[] searchPill = {new Spring(0.3f, 0.8f, 0)}, micFocus = {new Spring(0.3f, 0.85f, 0)};
    private boolean inMic;
    private final Spring[] topFocus = new Spring[3 + TOP_N];
    private final RectF[] topRects = new RectF[3 + TOP_N];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lMatch, lTip, lDate, lClock, lWeather, lClockMatch;
    private Paint gDateBold, gDate, gClock, gTemp, gCond, gCondDim, gHour, gHourTemp;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF161616, 0xFF000000, 0});   // HBO Max since 2025: white on black
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 0f;   // no wiggle: the lifted app just rises
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) holdAction(); };

    /** OK held: on the shelf the app lifts (to move it, ↓ for its options); in All Apps its options open. */
    private void holdAction() {
        if (sheetOpen) openGridMenu();
        else startReorder();
    }

    // MARK: - App options (Info, Remove, Uninstall)

    private static final String[] SHELF_ALL = {"Información", "Reemplazar", "Quitar de la barra", "Desinstalar"};
    private static final String[] GRID_ALL = {"Mover", "Agregar a la barra", "Información", "Desinstalar"};
    /** The options of the app whose menu is open: without Uninstall for the TV's own (system) apps. */
    private String[] shelfOptions = SHELF_ALL, gridOptions = GRID_ALL;

    private String[] optionsFor(String pkg, String[] all) {
        boolean system = true;
        try {
            int flags = getContext().getPackageManager().getApplicationInfo(pkg, 0).flags;
            system = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception ignored) {
        }
        if (!system) return all;
        return java.util.Arrays.copyOf(all, all.length - 1);   // Uninstall is always the last one
    }
    private boolean appMenu, gridMenu;
    private int menuIndex;
    private final RectF menuPill = new RectF();
    private Paint menuText;

    private void openGridMenu() {
        if (replacing != null) return;   // choosing a replacement: OK picks, there's no menu
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (gridIndex >= others.size() || others.get(gridIndex).pkg == null) return;
        gridOptions = optionsFor(others.get(gridIndex).pkg, GRID_ALL);
        gridMenu = true;
        okHeld = true;   // the release of the press that opened it opens nothing
        menuIndex = 0;
        gridDirty = true;
        click.open();
        wake();
    }

    /** Keys while the grid's options show. */
    private boolean gridMenuKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    menuIndex = Math.max(0, Math.min(gridOptions.length - 1, menuIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    gridDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = false;
                    String pkg = others.get(gridIndex).pkg;
                    gridMenu = false;
                    gridDirty = true;
                    appAction(gridOptions[menuIndex], pkg);
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    gridMenu = false;
                    gridDirty = true;
                    click.move();
                }
                return true;
            default:
                return false;
        }
    }

    // MARK: - Moving an app in All Apps (hold OK > Move): the arrows carry it anywhere, OK drops it, Back undoes

    private boolean gridMove;
    private List<HomeApps.Item> gridBefore;

    private void startGridMove() {
        gridMove = true;
        gridBefore = new ArrayList<>(others);
        gridDirty = true;
        showToast("← → ↑ ↓ mover · OK soltar · Atrás cancelar");
        click.open();
    }

    private boolean gridMoveKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            if (!down) return true;
            int to = Math.max(0, Math.min(others.size() - 1, gridIndex + dx + dy * COLUMNS));
            if (to == gridIndex) return true;
            List<HomeApps.Item> list = new ArrayList<>(others);
            list.add(to, list.remove(gridIndex));
            others = list;
            gridLabels = null;   // the names follow their cards
            gridIndex = to;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && e.getRepeatCount() == 0) {
                gridMove = false;
                List<String> order = new ArrayList<>();
                for (HomeApps.Item it : others) if (it.pkg != null) order.add(it.pkg);
                Prefs.setGridOrder(order);
                gridDirty = true;
                click.open();
            }
            okDown = okHeld = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                gridMove = false;
                others = gridBefore;   // as it was
                gridLabels = null;
                gridIndex = Math.min(gridIndex, others.size() - 1);
                retargetGrid(false);
                gridDirty = true;
                click.move();
            }
            return true;
        }
        return true;
    }

    // MARK: - Replacing a shelf app (hold OK > ↓ > Replace): All Apps opens to choose the one that takes its place

    /** The shelf app being replaced while All Apps is open to choose; null otherwise. */
    private String replacing;

    private void startReplace(String pkg) {
        List<String> onShelf = HomeApps.shelfPackages(getContext());
        List<HomeApps.Item> choices = new ArrayList<>();
        for (HomeApps.Item it : others) if (it.pkg != null && !onShelf.contains(it.pkg)) choices.add(it);   // no repeats
        if (choices.isEmpty()) {
            showToast("No hay otras apps para poner");
            return;
        }
        replacing = pkg;
        clearToasts();   // the moving notice would sit over the chooser's title
        setOthers(choices);
        openSheet();
    }

    /** OK on an app while choosing: it takes the replaced one's place on the shelf. */
    private void finishReplace(String chosen) {
        List<String> list = new ArrayList<>(HomeApps.shelfPackages(getContext()));
        int at = list.indexOf(replacing);
        if (at >= 0 && chosen != null && !list.contains(chosen)) {
            list.set(at, chosen);
            Prefs.setShelf(list);
            showToast("Listo, reemplazada");
        }
        replacing = null;
        closeSheet();
        reload();
    }

    /** What an option does; the shelf's own changes (order, removal) are saved first. */
    private void appAction(String option, String pkg) {
        Context c = getContext();
        try {
            switch (option) {
                case "Mover":
                    startGridMove();
                    break;
                case "Información":
                    c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case "Desinstalar":
                    c.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                            android.net.Uri.parse("package:" + pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case "Reemplazar":
                    startReplace(pkg);
                    break;
                case "Quitar de la barra": {
                    List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
                    list.remove(pkg);
                    Prefs.setShelf(list);
                    reload();
                    break;
                }
                default: {   // "Agregar a la barra"
                    List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
                    if (!list.contains(pkg) && list.size() >= HomeApps.MAX_SHELF) {
                        showToast("La barra ya tiene " + HomeApps.MAX_SHELF + " apps: quitá una primero");
                        break;
                    }
                    if (!list.contains(pkg)) list.add(pkg);
                    Prefs.setShelf(list);
                    reload();
                    showToast("Agregada a la barra");
                    break;
                }
            }
        } catch (RuntimeException ex) {
            AppLog.w("app option " + option + ": " + ex.getMessage());
        }
    }

    /** The options as glass pills under `anchor`, the chosen one white (the tvOS focus). */
    private void drawAppMenu(Canvas c, RectF anchor, String[] options) {
        if (menuText == null || menuText.getTextSize() != 22 * k) menuText = Ui.text(Ui.medium, 22 * k, 0xFFFFFFFF);
        float h = 52 * k, gap = 12 * k, pad = 26 * k, total = -gap;
        for (String o : options) total += menuText.measureText(o) + 2 * pad + gap;
        float x = Math.max(MARGIN * k, Math.min(CONTENT_R * k - total, anchor.centerX() - total / 2));
        float top = Math.min(h() - h - 16 * k, anchor.bottom + 22 * k);
        for (int i = 0; i < options.length; i++) {
            float w = menuText.measureText(options[i]) + 2 * pad;
            menuPill.set(x, top, x + w, top + h);
            boolean on = i == menuIndex;
            tmp.set(menuPill);
            tmp.offset(0, 6 * k);
            shadow.setColor(0x66000000);
            shadows.draw(c, tmp, h / 2, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(on ? 0xFFF5F5F7 : 0xE62C2C2E);
            c.drawRoundRect(menuPill, h / 2, h / 2, fill);
            menuText.setColor(on ? 0xFF1C1C1E : 0xF2FFFFFF);
            c.drawText(options[i], menuPill.left + pad, menuPill.centerY() + Ui.halfCap(menuText), menuText);
            x += w + gap;
        }
    }

    private float h() {
        return getHeight();
    }

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), futbolCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private HomeData.Weather textWeather;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText;
    private final String[] hourText = new String[6], hourTempText = new String[6];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private SceneView sceneSkyView, sceneLandView;

    /** The scene's two pictures, each drawn once: the sky (moved by its translation) and the mountains with the scrims. */
    private final class SceneView extends View {
        private final boolean isSky;
        private final RectF bounds = new RectF();

        SceneView(Context c, boolean isSky) {
            super(c);
            this.isSky = isSky;
        }

        @Override protected void onDraw(Canvas c) {
            Bitmap b = isSky ? sceneSky : sceneLand;
            if (b == null) return;
            bounds.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(b, null, bounds, scenePaint);
            if (b != null) return;   // the scrims are in the mountains' picture already
            if (sceneScrimLeft == null) {
                float sw = getWidth(), sh = getHeight();
                sceneScrimLeft = new LinearGradient(0, 0, sw * 0.6f, 0, new int[] {0x99000000, 0x4D000000, 0x00000000},
                        new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
                sceneScrimBottom = new LinearGradient(0, sh, 0, sh * 0.55f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP);
            }
            c.drawColor(0x40000000);
            scrimPaint.setShader(sceneScrimLeft);
            c.drawRect(bounds, scrimPaint);
            scrimPaint.setShader(sceneScrimBottom);
            c.drawRect(0, bounds.bottom * 0.55f, bounds.right, bounds.bottom, scrimPaint);
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }
    }

    private final Paint scrimPaint = new Paint(Paint.DITHER_FLAG);

    private boolean sceneOn() {
        return sceneSky != null && sceneLand != null;
    }

    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case WATCH: if (!picksOn()) drawWatch(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }

        /** The widget's layer fades by its own alpha: each draw takes it (no offscreen copy of a full-screen layer). */
        @Override public boolean hasOverlappingRendering() {
            return kind != WATCH;
        }
    }

    private final Runnable reloadSoon = this::reload;

    /** An app installed, removed, turned on or off while Home is on screen: the shelf and All Apps follow. */
    private final android.content.BroadcastReceiver appsChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            reloadSoon();
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updateWallpaper();
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        sceneSkyView = new SceneView(a, true);
        sceneLandView = new SceneView(a, false);
        sceneSkyView.setVisibility(GONE);
        sceneLandView.setVisibility(GONE);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // The "Continuar viendo" island: its own layer, so fading it in and out (on every move along the shelf) only
        // changes the layer's alpha, without drawing the glass, the clock or the island again on each frame.
        watchLayer = new Layer(a, WATCH);
        watchLayer.setVisibility(INVISIBLE);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        // (Measured again on a TCL with a Mali-G31, Android 11: with layers the drawer cost more GPU, 11-15 ms against
        // 8-14 ms without.)
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        dimLayer = new View(a);
        dimLayer.setBackgroundColor(0xFF000000);
        dimLayer.setAlpha(0);
        dimLayer.setVisibility(GONE);
        veilLayer = new View(a);
        veilLayer.setVisibility(GONE);
        for (View v : new View[] {sky, sceneSkyView, sceneLandView, veilLayer, heroLayer, watchLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, dimLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 3 + TOP_N; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < RECENT_MAX; i++) {
            recentFocus[i] = new Spring(0.32f, 0.78f, 0);
            recentRects[i] = new RectF();
        }
        playerFocus[0] = new Spring(0.26f, 0.85f, 0);
        avatarFocus[0] = new Spring(0.26f, 0.85f, 0);
        for (int i = 0; i < 6; i++) ctrlFocus[i] = new Spring(0.26f, 0.85f, 0);
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerLists(drawerList(a), HomeApps.allInputs(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    /** Over everything: the brightness picked in the quick panel (Prefs.launcherDim). */
    private View dimLayer;

    private final android.content.BroadcastReceiver dimReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            // Turned off: the background starts again from the first picture when it turns on.
            if (android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) Prefs.restartHomeBackground(context);
            applyDim();
            updateWallpaper();
        }
    };

    /**
     * The home screen's brightness: the system's own dimming (what darkens the screen behind a dialog), asked for by
     * an empty window over Home. The compositor lays a plain black over the screen; Home itself doesn't draw it, so it
     * costs no GPU time per frame (a black layer drawn by Home cost 4 to 7 ms on every frame of an animation). The
     * window's brightness was tried first: TCL keeps its backlight on its own picture settings and ignores it.
     */
    private View dimWindow;

    /**
     * An app or an input opens from Home: the system's dim, a layer of its own that the transition doesn't carry along
     * (the brightness jumped midway), gives way to Home's own black layer, just as dark, which fades out with Home. The
     * system's dim goes one frame later, once that layer is on screen (never a bright frame). Back on Home, applyDim
     * brings it back. Not for the quick panel: Home stays in view behind it.
     */
    private void dimInHome() {
        final View w = dimWindow;
        if (w == null) return;
        float dim = ((android.view.WindowManager.LayoutParams) w.getLayoutParams()).dimAmount;
        dimLayer.animate().cancel();
        dimLayer.setAlpha(dim);
        dimLayer.setVisibility(VISIBLE);
        dimWindow = null;
        dimLayer.postOnAnimation(() -> dimLayer.postOnAnimation(() -> {
            try {
                activity.getWindowManager().removeViewImmediate(w);
            } catch (RuntimeException ignored) {
            }
        }));
    }

    private void applyDim() {
        float dim = Prefs.launcherDim(getContext()) / 100f;
        // Coming back (still hidden, or fading in from black): the return fade ends at this dim and hands over then.
        if (dimFading || (getWindowVisibility() != VISIBLE && shownBefore)) return;
        dimLayer.animate().cancel();
        dimLayer.setAlpha(0);
        dimLayer.setVisibility(GONE);
        android.view.WindowManager wm = activity.getWindowManager();
        if (dim <= 0 || getWindowToken() == null) {
            if (dimWindow != null) {
                try {
                    wm.removeView(dimWindow);
                } catch (RuntimeException ignored) {
                }
                dimWindow = null;
            }
            return;
        }
        android.view.WindowManager.LayoutParams p = dimWindow != null
                ? (android.view.WindowManager.LayoutParams) dimWindow.getLayoutParams()
                : new android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                        android.graphics.PixelFormat.TRANSLUCENT);
        p.dimAmount = dim;
        p.token = getWindowToken();
        p.windowAnimations = 0;
        try {
            if (dimWindow == null) {
                dimWindow = new View(getContext());
                wm.addView(dimWindow, p);
            } else {
                wm.updateViewLayout(dimWindow, p);
            }
        } catch (RuntimeException e) {
            AppLog.w("home dim: " + e.getMessage());
            dimWindow = null;
        }
    }

    /**
     * Back from an app (the window was hidden; not after the quick panel, which leaves it shown): the screen comes up
     * from black in a quarter of a second. Only the black layer animates, so it costs next to nothing.
     */
    private boolean shownBefore, dimFading;

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE || dimLayer == null) return;
        if (!shownBefore) {   // the very first time (the home screen just started): no fade, it would only add waiting
            shownBefore = true;
            applyDim();
            return;
        }
        // From black down to the chosen brightness in Home's own layer, then the system's dim takes over unseen.
        final float to = Prefs.launcherDim(getContext()) / 100f;
        dimFading = true;
        dimLayer.animate().cancel();
        dimLayer.setVisibility(VISIBLE);
        dimLayer.setAlpha(1);
        dimLayer.animate().alpha(to).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    dimFading = false;
                    applyDim();
                }).start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        android.content.IntentFilter dim = new android.content.IntentFilter(Prefs.DIM_CHANGED);
        dim.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(dimReceiver, dim);
        applyDim();
    }

    @Override protected void onDetachedFromWindow() {
        if (dimWindow != null) {
            try {
                activity.getWindowManager().removeViewImmediate(dimWindow);
            } catch (RuntimeException ignored) {
            }
            dimWindow = null;
        }
        getContext().unregisterReceiver(dimReceiver);
        super.onDetachedFromWindow();
    }

    // MARK: - Wallpaper (Home Settings > Home background)

    /** A landscape or a painting behind everything (null: the plain background). A new one as often as chosen. */
    private Bitmap wallpaper;
    private String wallpaperKey = "";
    private boolean wallpaperLoading;
    private float wallpaperAlpha = 1;
    private Bitmap wallpaperBefore;
    /** A picture is chosen as the background (it may still be loading): the screen keeps the dark look meanwhile. */
    private boolean wallpaperWanted;

    // MARK: - Glass edition: the scene background (Val Gardena, its sky from the weather)

    /** Home background "scene": the same mountains always; behind them, a sky for the weather now, drifting slowly. */
    static final String SCENE = "scene";
    private Bitmap sceneSky, sceneLand;
    private String sceneKey = "";
    private boolean sceneLoading;
    private LinearGradient sceneScrimLeft, sceneScrimBottom;
    private final Paint scenePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private static final long SCENE_DRIFT_MS = 180_000;   // one slow sweep of the sky there and back
    /** About 15 frames a second while the scene shows, and only then: the sky moves a few pixels a second. */
    private final Runnable sceneTick = new Runnable() {
        @Override public void run() {
            if (!running || !sceneOn()) return;
            float room = Math.max(0, sceneSkyView.getWidth() - w);
            float t = (SystemClock.uptimeMillis() % SCENE_DRIFT_MS) / (float) SCENE_DRIFT_MS;   // 0 → 1
            float u = t < 0.5f ? t * 2 : 2 - t * 2, ease = u * u * (3 - 2 * u);
            sceneSkyView.setTranslationX(-room * ease);   // only moves it: nothing is drawn again
            handler.postDelayed(this, idleMode() ? 500 : 50);   // 20 a second: the sky moves under a pixel each
        }
    };

    /** Which sky goes with the weather: clear, sunset, cloudy, rain, storm or night. */
    private String sceneState() {
        HomeData.Weather wx = data.weather;
        int c = wx.code;
        boolean storm = c >= 95, rain = (c >= 51 && c <= 67) || (c >= 80 && c <= 82),
                cloudy = c == 3 || c == 45 || c == 48 || (c >= 71 && c <= 77) || c == 85 || c == 86;
        if (storm) return "storm";
        if (!wx.isDay) return "night";
        if (rain) return "rain";
        long now = System.currentTimeMillis();
        if (wx.sunset > 0 && Math.abs(wx.sunset - now) < 50 * 60_000L) return "sunset";
        return cloudy ? "cloudy" : "clear";
    }

    private void updateScene(Context ctx) {
        if (!wallpaperWanted) {
            wallpaperWanted = true;
            checkNight(false);
        }
        final String state = sceneState();
        if (state.equals(sceneKey) || sceneLoading) {
            if (sceneSky != null) restartScene();
            return;
        }
        sceneLoading = true;
        LOADER.execute(() -> {
            Bitmap skyB = null, landB = null, made = null;
            int[] frost = null;
            try {
                android.content.res.AssetManager am = ctx.getAssets();
                android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
                op.inPreferredConfig = Bitmap.Config.RGB_565;   // a sky needs no transparency: half the memory
                try (java.io.InputStream in = am.open("scene/sky_" + state + ".jpg")) {
                    skyB = android.graphics.BitmapFactory.decodeStream(in, null, op);
                }
                Bitmap raw;
                try (java.io.InputStream in = am.open("scene/land.webp")) {
                    raw = android.graphics.BitmapFactory.decodeStream(in);
                }
                // The mountains' light for this weather: duller with rain, dark and blue at night, warm at sunset.
                float br, sat, rr = 1, gg = 1, bb = 1;
                switch (state) {
                    case "sunset": br = 0.92f; sat = 1.05f; rr = 1.08f; gg = 0.98f; bb = 0.88f; break;
                    case "cloudy": br = 0.82f; sat = 0.7f; bb = 1.02f; break;
                    case "rain": br = 0.66f; sat = 0.55f; rr = 0.92f; gg = 0.97f; bb = 1.08f; break;
                    case "storm": br = 0.5f; sat = 0.45f; rr = 0.95f; gg = 0.98f; bb = 1.05f; break;
                    case "night": br = 0.22f; sat = 0.5f; rr = 0.85f; gg = 0.92f; bb = 1.25f; break;
                    default: br = 1; sat = 1;
                }
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(sat);
                android.graphics.ColorMatrix sc = new android.graphics.ColorMatrix();
                sc.setScale(br * rr, br * gg, br * bb, 1);
                cm.postConcat(sc);
                landB = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
                Paint lp = new Paint(Paint.FILTER_BITMAP_FLAG);
                lp.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                new Canvas(landB).drawBitmap(raw, null, new RectF(0, 0, Backdrop.W, Backdrop.H), lp);
                raw.recycle();
                Bitmap landScrimmed = landB.copy(Bitmap.Config.ARGB_8888, true);
                withWallpaperScrims(landScrimmed);   // the scrims once, here, not on every frame
                // A still copy (the sky at its start) for the glass's blur and the first frame.
                made = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
                Canvas mc = new Canvas(made);
                float s = Backdrop.H / (float) skyB.getHeight();
                mc.drawBitmap(skyB, null, new RectF(0, 0, skyB.getWidth() * s, Backdrop.H), lp.getColorFilter() == null ? null : new Paint(Paint.FILTER_BITMAP_FLAG));
                mc.drawBitmap(landB, 0, 0, null);
                withWallpaperScrims(made);
                frost = wallpaperFrost(made);
                made = Backdrop.gpu(made);   // GPU-only copies: nothing left in the app's own memory
                skyB = Backdrop.gpu(skyB);
                landB.recycle();
                landB = Backdrop.gpu(landScrimmed);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("scene: " + e.getMessage());
            }
            final Bitmap fs = skyB, fl = landB, fm = made;
            final int[] ff = frost;
            handler.post(() -> {
                sceneLoading = false;
                if (fs == null || fl == null || fm == null || !SCENE.equals(Prefs.homeBackgroundShown(ctx))) return;
                sceneSky = fs;
                sceneLand = fl;
                sceneKey = state;
                sceneScrimLeft = null;
                sceneSkyView.setVisibility(VISIBLE);
                sceneLandView.setVisibility(VISIBLE);
                requestLayout();
                sceneSkyView.invalidate();
                sceneLandView.invalidate();
                wallpaperBefore = null;
                wallpaper = fm;
                wallpaperKey = SCENE;
                wallpaperAlpha = 1;
                wallpaperFrostPx = ff;
                if (bgTo == null && bgFadeAt == 0) neutralGlass();
                checkNight(true);
                sky.invalidate();
                restartScene();
            });
        });
    }

    /** An app opened (or the background changed): the scene's pictures go; `keep` leaves the still copy to show. */
    void releaseScene(boolean keep) {
        handler.removeCallbacks(sceneTick);
        sceneSky = sceneLand = null;
        sceneKey = "";
        if (sceneSkyView != null) {
            sceneSkyView.setVisibility(GONE);
            sceneLandView.setVisibility(GONE);
        }
        if (!keep && SCENE.equals(wallpaperKey)) {
            wallpaper = null;
            wallpaperKey = "";
        }
        sky.invalidate();
    }

    /** No key for a minute: what only decorates (the sky's drift, the equalizer) slows right down. */
    private long lastKeyAt = SystemClock.uptimeMillis();

    private boolean idleMode() {
        return SystemClock.uptimeMillis() - lastKeyAt > 60_000;
    }

    private void restartScene() {
        handler.removeCallbacks(sceneTick);
        if (running && sceneSky != null) handler.post(sceneTick);
    }

    private void updateWallpaper() {
        final Context ctx = getContext().getApplicationContext();
        final String kind = Prefs.homeBackgroundShown(ctx);
        if (SCENE.equals(kind)) {
            updateScene(ctx);
            return;
        }
        if (sceneSky != null || sceneLand != null) releaseScene(false);   // the scene was on: it goes
        boolean wanted = Gallery.photos(kind);
        if (wanted != wallpaperWanted) {
            wallpaperWanted = wanted;
            checkNight(false);   // a picture is coming: the dark look at once, not after it loads
        }
        if (!wanted) {
            if (wallpaper != null) {
                wallpaper = null;
                wallpaperKey = "";
                checkNight(true);
                sky.invalidate();
            }
            return;
        }
        final long slot = Prefs.homeBackgroundStep(ctx);   // the list in its order, one step at a time
        final String key = kind + "|" + slot + "|" + Prefs.homeBackgroundSeed(ctx);   // a new start, a new key
        if (key.equals(wallpaperKey) || wallpaperLoading) return;
        wallpaperLoading = true;
        LOADER.execute(() -> {
            Bitmap b = null;
            Gallery.Item item = null;
            String full = key;
            try {   // the picture for this step (its url goes in the cache key: a new order, a new shuffle)
                List<Gallery.Item> items = Gallery.list(ctx, kind);
                if (!items.isEmpty()) {
                    item = Gallery.pick(ctx, items, slot);
                    full = key + "|" + item.url;
                    Gallery.setCurrent(ctx, item);
                }
            } catch (Exception e) {
                AppLog.w("wallpaper: " + e.getMessage());
            }
            // The finished background (cropped, darkened) is kept on disk: back on Home after the TV closed it (an
            // input, a big app), it's read in a moment instead of being made again from the original.
            java.io.File cache = new java.io.File(ctx.getCacheDir(), "wallpaper.jpg");
            java.io.File cacheKey = new java.io.File(ctx.getCacheDir(), "wallpaper.key");
            int[] frost = null;
            try {
                if (cache.exists() && full.equals(new String(java.nio.file.Files.readAllBytes(cacheKey.toPath()), "UTF-8"))) {
                    b = android.graphics.BitmapFactory.decodeFile(cache.getPath());
                    if (b != null) {
                        frost = wallpaperFrost(b);
                        b = Backdrop.gpu(b);
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                if (b == null && item != null) {
                    java.io.File f = Gallery.image(ctx, item);
                    Bitmap[] im = Backdrop.photo(f, Backdrop.W, Backdrop.H, null);
                    im[1].recycle();
                    Bitmap made = withWallpaperScrims(im[0]);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(cache)) {
                        made.compress(Bitmap.CompressFormat.JPEG, 92, out);
                        java.nio.file.Files.write(cacheKey.toPath(), full.getBytes("UTF-8"));
                    } catch (Exception e) {
                        AppLog.w("wallpaper cache: " + e.getMessage());
                    }
                    frost = wallpaperFrost(made);
                    b = Backdrop.gpu(made);
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("wallpaper: " + e.getMessage());
            }
            final Bitmap ready = b;
            final int[] readyFrost = frost;
            handler.post(() -> {
                wallpaperLoading = false;
                if (ready == null || !kind.equals(Prefs.homeBackgroundShown(ctx))) return;
                wallpaperBefore = wallpaper;   // a new picture fades in over the old one (not over black)
                wallpaper = ready;
                wallpaperKey = key;
                wallpaperFrostPx = readyFrost;
                if (bgTo == null && bgFadeAt == 0) neutralGlass();   // the glass now shows this picture, blurred
                checkNight(true);   // a photo always takes the night look: white text over a darkened picture
                // It fades in (a third of a second), rather than popping in.
                android.animation.ValueAnimator in = android.animation.ValueAnimator.ofFloat(0, 1);
                in.setDuration(wallpaperBefore != null ? 450 : 260);
                in.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        wallpaperBefore = null;
                        sky.invalidate();
                    }
                });
                in.addUpdateListener(a -> {
                    wallpaperAlpha = (float) a.getAnimatedValue();
                    sky.invalidate();
                });
                in.start();
            });
        });
    }

    /** The gallery picture, blurred for the glass: Backdrop.FW × FH, made on the loader thread. */
    private int[] wallpaperFrostPx;

    private static int[] wallpaperFrost(Bitmap src) {
        try {
            // Down in two steps (each one filtered) to a tiny picture, then up: a wide, smooth blur for almost nothing.
            Bitmap mid = Bitmap.createScaledBitmap(src, 240, 135, true);
            Bitmap tiny = Bitmap.createScaledBitmap(mid, 40, 22, true);
            Bitmap up = Bitmap.createScaledBitmap(tiny, Backdrop.FW, Backdrop.FH, true);
            int[] px = new int[Backdrop.FW * Backdrop.FH];
            up.getPixels(px, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
            mid.recycle();
            tiny.recycle();
            up.recycle();
            return px;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Darkened a little all over, more on the left (the clock) and at the bottom (the shelf): the text stays readable. */
    private static Bitmap withWallpaperScrims(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        Canvas c = new Canvas(b);
        c.drawColor(0x40000000);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.6f, 0, new int[] {0x99000000, 0x4D000000, 0x00000000},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h, 0, h * 0.55f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.55f, w, h, p);
        return b;
    }

    void resume() {
        if (running) return;
        running = true;
        idleFrom = Z_SHELF;   // back on Home: nothing focused (the first arrow or OK brings the shelf back)
        if (!sheetOpen && !drawerOpen) {
            zone = Z_IDLE;
            retarget(true);
        }
        lastKeyAt = SystemClock.uptimeMillis();   // back on Home: things move for a moment
        applyDim();
        updateWallpaper();
        restartTick();
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (nowPlaying == null) nowPlaying = new NowPlaying(getContext(), this::onNowPlaying);
        nowPlaying.start();
        // The island's data (the TV Provider, pictures) waits until Home is back on screen: reading it during the
        // return fought the animation for the TV's CPU. Nothing is focused on return, so nothing waits for it.
        handler.postDelayed(watchStart, WATCH_START_MS);
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        android.content.IntentFilter apps = new android.content.IntentFilter();
        apps.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        apps.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);   // turned on or off in the TV's settings
        apps.addDataScheme("package");
        getContext().registerReceiver(appsChanged, apps);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (nowPlaying != null) nowPlaying.stop();
        handler.removeCallbacks(npTick);
        handler.removeCallbacks(watchStart);
        WatchNext.get(getContext()).stop();
        MediaHistory.get(getContext()).setListener(null);
        handler.removeCallbacks(watchDebounce);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        try {
            getContext().unregisterReceiver(appsChanged);
        } catch (IllegalArgumentException ignored) {
        }
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_IDLE;   // nothing focused; the first arrow or OK brings back the first app
        idleFrom = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        // Glass edition: TV en vivo and the inputs stay in the drawer only; search and voice (the Google Assistant),
        // the sleep timer and the Wi-Fi took their place.
        lightButtons.add(HomeApps.action(HomeApps.QUICK_SETTINGS, "search", "Buscar", "search"));
        lightButtons.add(HomeApps.action(HomeApps.QUICK_SETTINGS, "sleep", "Apagar en\u2026", "bedtime"));
        lightButtons.add(HomeApps.action(HomeApps.QUICK_SETTINGS, "wifi", "Wi-Fi", "wifi"));
        lightButtons.add(HomeApps.action(HomeApps.QUICK_SETTINGS, "settings", Ui.tr("light|Settings"), "settings"));
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        if (SCENE.equals(Prefs.homeBackgroundShown(getContext()))) updateWallpaper();   // the weather may want another sky
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        // Glass edition: taller, for the bigger dock and the focused tile rising out of it (were 320 and 270).
        measure(platter, width, Math.round(380 * kk));
        measure(tiles, width, Math.round(340 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        Bitmap ss = sceneSky;   // the sky is wider than the screen: it slides
        sceneSkyView.layout(0, 0, ss == null ? width : Math.round(ss.getWidth() * height / (float) ss.getHeight()), height);
        sceneLandView.layout(0, 0, width, height);
        veilLayer.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        watchLayer.layout(0, 0, width, height);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(700 * kk));
        place(tiles, 0, Math.round(740 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        dimLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lMatch = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        gDateBold = Ui.text(Ui.semibold, 28 * k, L_INK);
        gDateBold.setLetterSpacing(0.16f);
        gDate = Ui.text(Ui.medium, 28 * k, L_INK);
        gDate.setLetterSpacing(0.16f);
        gClock = Ui.numbers(Ui.stadium, 276 * k, L_INK);
        gClock.setLetterSpacing(0.01f);
        gTemp = Ui.numbers(Ui.stadium, 62 * k, L_INK);
        gTemp.setLetterSpacing(0.02f);
        gCond = Ui.text(Ui.semibold, 28 * k, L_INK);
        gCondDim = Ui.numbers(Ui.medium, 22 * k, L_DIM);
        gHour = Ui.numbers(Ui.medium, 19 * k, L_DIM);
        gHourTemp = Ui.numbers(Ui.semibold, 22 * k, L_INK);
        for (Paint p : new Paint[] {gDateBold, gDate, gClock, gTemp, gCond, gCondDim, gHour, gHourTemp}) {
            p.setShadowLayer(16 * k, 0, 2 * k, 0x4D000000);
        }
        lClockMatch = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // The football line: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 21 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (step(allReveal, dt) && !iconDock()) {   // the All Apps square grows in (or out): the bar makes room
            layoutShelf();
            tilesMoving = true;
            platter.invalidate();
        }
        if (step(dockScroll, dt) && iconDock()) {   // the carousel slides: the icons move under the focus
            layoutShelf();
            tilesMoving = true;
            heroLayer.invalidate();
        }
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt) | step(playerFocus, dt) | step(avatarFocus, dt) | step(npExpand, dt) | step(ctrlFocus, dt) | step(ctrlShow, dt) | step(npOpen, dt) | step(npFull, dt) | step(barFocus, dt) | step(searchPill, dt) | step(micFocus, dt) | step(sleepOpen, dt) | step(sleepFocus, dt), cardsMoving = step(cardFocus, dt) | step(recentFocus, dt);
        if (topMoving) placeWatch();   // the island fades out while the player opens in its place
        boolean watchMoving = step(watchFocus, dt) | step(actFocus, dt);
        boolean shelfUpMoving = step(bubbleShow, dt);
        if (shelfUpMoving) heroLayer.invalidate();   // the clock shrinks as the app's shelf comes up
        boolean watchFading = step(watchShow, dt) | step(watchActive, dt) | shelfUpMoving;
        // The shelf's hint (passing over an app) and the content background (resting on it).
        hintShow[0].target = !iconDock() && !sheetOpen && zone == Z_SHELF && drawer.value < 0.02f && watchShown() ? 1 : 0;
        if (shelfUpMoving && iconDock()) {   // the wheel shrinks down while the bubble opens
            layoutShelf();
            tilesMoving = true;
        }
        boolean hintMoving = step(hintShow, dt), artMoving = step(artShow, dt);
        if (hintMoving) heroLayer.invalidate();
        if (artMoving) {
            sky.invalidate();
            heroLayer.invalidate();
        }
        watchFading |= hintMoving | artMoving;   // keeps the frames coming while they move
        if (watchSwap && watchShow[0].value < 0.03f) showWatch(watchWaiting);   // the old one faded: the new one comes in
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt) | step(futbolFocus, dt) | step(weatherFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (watchMoving || watchDirty) watchLayer.invalidate();
        if (watchFading || watchDirty || drawerMoving || sheetMoving || motionDirty) placeWatch();
        if (bgFading) sky.invalidate();
        if (drawerMoving || bgFading) updateVeil();
        if (bgFading) platter.invalidate();   // (not when the drawer stops: it faded away, so nothing jumps)
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = watchDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || watchMoving || watchFading
                || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home * (1 - smoothstep(0, 0.45f, dc)), lift);   // the glass fades instead of being redrawn
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /** The island's fade and rise, and the drawer's lift, as the layer's alpha and position (nothing redrawn). */
    private void placeWatch() {
        // Glass edition: the bubble shows only while the focus is up in it (↑ from the app), rising from the shelf.
        float show = Ui.clamp(watchShow[0].value) * Ui.clamp(bubbleShow[0].value), eased = show * show * (3 - 2 * show);
        float d = drawer.value, top = (1 - Ui.clamp(sheet.value)) * (1 - smoothstep(0, 0.32f, Ui.clamp(d)));
        float alpha = watch == null ? 0 : top * eased;
        alpha *= 1 - Ui.clamp(npOpen[0].value);
        show(watchLayer, alpha, -d * SHIFT * k + (1 - eased) * 26 * k);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /**
     * No recommendation's photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on
     * screen) or, with a gallery picture as the background, that picture blurred (Glass edition).
     */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(wallpaper != null && wallpaperFrostPx != null ? wallpaperFrostPx : neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (b != bgTo) return b.frost;
        // Glass edition: the panels show this frost, so at night it only goes part of the way to the darkened version
        // (all the way, the shelf and the buttons turned almost black).
        float e = night * NIGHT_FROST;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], e);
        return frostNight;
    }

    private static final float NIGHT_FROST = 0.4f;

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis()) || wallpaper != null || wallpaperWanted;
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    /**
     * The drawer's veil over the background (lighter by day, darker at night): a plain layer whose transparency
     * follows the drawer. The background itself stays as drawn (re-filtering a full-screen picture on every frame of
     * the drawer's slide made it stutter).
     */
    private View veilLayer;

    private void updateVeil() {
        float a = tone(0.32f, 0.42f) * Ui.clamp(drawer.value);
        veilLayer.setBackgroundColor(blend(0xFFFFFFFF, 0xFF000000, night));
        veilLayer.setAlpha(a);
        veilLayer.setVisibility(a > 0.002f ? VISIBLE : GONE);
    }

    private void nightChanged() {
        updateVeil();
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lMatch.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockMatch.setColor(tone(L_DIM, 0xFF98989D));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        {   // the bar ends in All Apps (the wheel's "Más apps"; on the cards, a rounded square)
            boolean has = false;
            for (HomeApps.Item it : list) has |= it.kind == HomeApps.ALL_APPS;
            if (!has) {
                list = new ArrayList<>(list);
                list.add(HomeApps.action(HomeApps.ALL_APPS, "all", iconDock() ? "Más apps" : "Todas las apps", "apps"));
            }
        }
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    // Glass edition, the "Íconos" shelf (Ajustes de inicio, Estilo de la barra): small square icons in a carousel, the
    // focused one always in the middle (the row slides under it), the far ones fading; no glass bar.
    private final Spring[] dockScroll = {new Spring(0.32f, 0.86f, 0)};
    private static final float DOCK_ICON = 150, DOCK_CY = 868, DOCK_R = 1500, DOCK_ANG = 8.6f;
    private boolean inFade;

    private boolean iconDock() {
        return Prefs.shelfStyle().equals("icons");
    }

    private float dockAlpha(int i) {
        float d = Math.abs(dockOff(i));
        return 1 - Ui.clamp((d - 2.3f) / 0.7f);
    }

    /** How far icon i is from the middle of the wheel, the short way round (the wheel has no ends). */
    private float dockOff(int i) {
        int n = Math.max(1, shelf.size());
        float d = (i - dockScroll[0].value) % n;
        if (d < -n / 2f) d += n;
        if (d >= n / 2f) d -= n;
        return d;
    }

    /** The wheel shrinks and drops while the bubble is open. */
    private float dockOpen() {
        return Ui.clamp(bubbleShow[0].value);
    }

    private void layoutShelf() {
        if (iconDock()) {
            int n = shelf.size();
            float o = dockOpen(), sc = 1 - 0.42f * o;
            float s = DOCK_ICON * k * sc, cy = (DOCK_CY + 112 * o) * k, cx = w / 2f, r = DOCK_R * k * sc;
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(dockOff(i) * DOCK_ANG);
                float x = cx + r * (float) Math.sin(a), y = cy + r * (1 - (float) Math.cos(a));
                tileRects[i].set(x - s / 2, y - s / 2, x + s / 2, y + s / 2);
            }
            platterRect.set(0, 0, 0, 0);
            return;
        }
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        int squares = 0;   // All Apps is a square: as wide as the cards are tall
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.ALL_APPS) squares++;
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1f, n - squares + squares * 9f / 16));
        float th = tw * 9f / 16;
        float rv = Ui.clamp(allReveal[0].value);
        float width = (n - squares) * tw + squares * (th + gap) * rv + Math.max(0, n - 1 - squares) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            boolean sq = shelf.get(i).kind == HomeApps.ALL_APPS;
            float iw = sq ? th * rv : tw;
            if (sq) x += gap * rv - gap;   // its gap grows with it
            tileRects[i].set(x, y, x + iw, y + th);
            x += iw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty() || iconDock()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    // MARK: - Football

    private static final int LIVE_RED = 0xFFFF453A;
    private long futbolAt = -1, futbolMinute = -1;
    private float futbolSizes = -1;
    private List<Futbol.Match> futbolShown = new ArrayList<>();
    private String[] futbolLine = new String[0], futbolShort = new String[0], futbolStatus = new String[0];
    private float[] wLineClock = new float[0], wLineCard = new float[0], wShort = new float[0];
    private float[] wStatusClock = new float[0], wStatusTab = new float[0];
    private final Snapshot futbolSnapshot = new Snapshot();

    /** Text and widths for the matches, made again only when the data, the minute or the text size change. */
    private void refreshFutbolText() {
        long minute = System.currentTimeMillis() / 60_000;
        float sizes = lClockMatch.getTextSize() + lMatch.getTextSize() * 7 + cardText.getTextSize() * 13;
        if (data.matchesAt == futbolAt && minute == futbolMinute && sizes == futbolSizes) return;
        futbolAt = data.matchesAt;
        futbolMinute = minute;
        futbolSizes = sizes;
        List<Futbol.Match> ms = data.matches;
        Futbol.Match mine = teamMatch(false);   // E2: your club's game, live or next (else its last)
        if (mine == null) mine = teamMatch(true);
        if (mine != null) ms = java.util.Collections.singletonList(mine);
        int n = ms.size();
        long now = System.currentTimeMillis();
        String[] line = new String[n], shrt = new String[n], status = new String[n];
        float[] wlc = new float[n], wlk = new float[n], ws = new float[n], wsc = new float[n], wst = new float[n];
        for (int i = 0; i < n; i++) {
            Futbol.Match m = ms.get(i);
            boolean scored = m.live() || m.done();
            line[i] = m.home.shortName + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.shortName;
            shrt[i] = m.home.abbr + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.abbr;
            status[i] = Futbol.status(m, now);
            wlc[i] = lClockMatch.measureText(line[i]);
            wlk[i] = cardText.measureText(line[i]);
            ws[i] = lMatch.measureText(shrt[i]);
            wsc[i] = lClockMatch.measureText(status[i]);
            wst[i] = lMatch.measureText(status[i]);
        }
        futbolShown = ms;
        futbolLine = line;
        futbolShort = shrt;
        futbolStatus = status;
        wLineClock = wlc;
        wLineCard = wlk;
        wShort = ws;
        wStatusClock = wsc;
        wStatusTab = wst;
    }

    private void drawCrest(Canvas c, Bitmap b, float x, float mid, float size, float alpha) {
        if (b == null) return;
        float h = size * b.getHeight() / Math.max(1f, b.getWidth());
        tmp.set(x, mid - h / 2, x + size, mid + h / 2);
        bitmapPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, null, tmp, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    /**
     * The games under the clock (or at the right of the tab row with picks on). Two or three being played at once show
     * together, one under the other, a little smaller; four or more, three at a time, taking turns. Otherwise one game
     * at a time, taking turns slowly. No dots.
     */
    private static final long ROTATE_MS = 12_000;
    private static final int LIVE_AT_ONCE = 3;
    private final Runnable rotateFutbol = () -> {
        heroDirty = true;
        wake();
    };

    private void drawFutbolRow(Canvas c) {
        refreshFutbolText();
        int n = futbolShown.size();
        if (n == 0) return;
        boolean clock = !picksOn();
        applyTone();
        Paint text = clock ? lClockMatch : lMatch;
        float fade = 1;
        for (int i = 3; i < 3 + TOP_N && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        long now = System.currentTimeMillis();
        int live = 0;
        while (live < n && (futbolShown.get(live).live() || futbolShown.get(live).kickedOff(now))) live++;   // the live ones come first
        boolean stack = clock && live >= 2;
        // Games being played always win: while any is live, the others (later, finished) wait their turn.
        int turns = stack ? (live + LIVE_AT_ONCE - 1) / LIVE_AT_ONCE : live > 0 ? live : n;
        int turn = (int) ((now / ROTATE_MS) % turns);
        if (turns > 1) {
            handler.removeCallbacks(rotateFutbol);
            handler.postDelayed(rotateFutbol, ROTATE_MS - now % ROTATE_MS + 20);
        }
        int textColor = text.getColor();
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + Ui.halfCap(text);
        if (!stack) {
            drawMatchLine(c, turn, text, textColor, clock, clock ? MARGIN * k : -1, base, fade, 1);
            text.setColor(textColor);
            return;
        }
        // Live together: smaller, one per line.
        float size = text.getTextSize();
        text.setTextSize(size * 0.82f);
        float step = Ui.cap(text) * 2.1f;   // three lines still end above the shelf (the layer is 790 tall)
        for (int j = 0; j < LIVE_AT_ONCE; j++) {
            int i = turn * LIVE_AT_ONCE + j;
            if (i >= live) break;
            drawMatchLine(c, i, text, textColor, true, MARGIN * k, base + j * step, fade, 0.82f);
        }
        text.setTextSize(size);
        text.setColor(textColor);
    }

    /** One game: crest, teams and score, crest, status. x < 0: ending at the content's right edge (the tab row). */
    private void drawMatchLine(Canvas c, int i, Paint text, int textColor, boolean clock, float x, float base, float fade,
            float scale) {
        float cap = Ui.cap(text), crest = cap * 1.9f, gap = 10 * k * scale;
        float mid = base - cap / 2;
        float wLine = clock ? wLineClock[i] * scale : wShort[i], wStatus = clock ? wStatusClock[i] * scale : wStatusTab[i];
        if (x < 0) x = CONTENT_R * k - (2 * crest + 4 * gap + wLine + wStatus);
        int dim = clock ? Ui.alpha(textColor, 0.75f * fade) : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        Futbol.Match m = futbolShown.get(i);
        drawCrest(c, m.home.crest, x, mid, crest, fade);
        x += crest + gap;
        text.setColor(Ui.alpha(textColor, fade));
        c.drawText(clock ? futbolLine[i] : futbolShort[i], x, base, text);
        x += wLine + gap;
        drawCrest(c, m.away.crest, x, mid, crest, fade);
        x += crest + 3 * gap;
        text.setColor(m.live() ? Ui.alpha(LIVE_RED, fade) : dim);
        c.drawText(futbolStatus[i], x, base, text);
    }

    /**
     * The drawer card: the week's games as a calendar, live ones first, under a heading per round ("Fecha 15 · Liga
     * Profesional", "Semifinal · vuelta · Libertadores"); four rows a page, turning every few seconds. A page that
     * starts mid-round repeats its heading.
     */
    private static final int CARD_ROWS = 4;
    private static final long PAGE_MS = 6000;
    private long upcomingAt = -1, upcomingMinute = -1;
    private float upcomingWidth = -1;
    /** A page is a list of rows; a row is a heading (match == null) or a game. */
    private static final class CardRow {
        Futbol.Match match;
        String text, status;
        float width;
    }
    private List<List<CardRow>> cardPages = new ArrayList<>();

    private void refreshUpcomingText(float width) {
        long minute = System.currentTimeMillis() / 60_000;
        if (data.matchesAt == upcomingAt && minute == upcomingMinute && width == upcomingWidth) return;
        upcomingAt = data.matchesAt;
        upcomingMinute = minute;
        upcomingWidth = width;
        long now = System.currentTimeMillis();
        float crest = Ui.cap(cardText) * 1.55f;
        List<List<CardRow>> pages = new ArrayList<>();
        List<CardRow> page = new ArrayList<>();
        String group = null;
        for (Futbol.Match m : data.upcoming) {
            String g = m.live() ? "EN VIVO" : m.round;
            boolean heading = !g.equals(group) || page.isEmpty();
            if (page.size() + (heading ? 2 : 1) > CARD_ROWS) {
                pages.add(page);
                page = new ArrayList<>();
                heading = true;
            }
            if (heading) {
                CardRow h = new CardRow();
                h.text = g;
                page.add(h);
            }
            CardRow r = new CardRow();
            r.match = m;
            r.status = Futbol.status(m, now);
            boolean scored = m.live() || m.done();
            float room = width - cardDimRight.measureText(r.status) - 2 * crest - 48 * k;
            r.text = m.home.shortName + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.shortName;
            if (cardText.measureText(r.text) > room) {
                r.text = m.home.abbr + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.abbr;
            }
            r.width = cardText.measureText(r.text);
            page.add(r);
            group = g;
        }
        if (!page.isEmpty()) pages.add(page);
        cardPages = pages;
    }

    private void drawFutbolCard(Canvas c, RectF r) {
        drawerCard(c, r);
        // Glass edition: only your club (Ajustes de inicio, Club favorito): its game now or next, and its last result.
        Futbol.Match next = teamMatch(false), last = teamMatch(true);
        if (next != null || last != null) {
            String tkey = data.matchesAt + "|" + System.currentTimeMillis() / 60_000 + "|" + Float.floatToIntBits(dark()) + "|team";
            if (Ui.LEGACY) futbolSnapshot.draw(c, r, tkey, cc -> drawTeamCard(cc, r, next, last));
            else drawTeamCard(c, r, next, last);
            drawCardFocus(c, r, Ui.clamp(futbolFocus[0].value), 0, 1);
            return;
        }
        refreshUpcomingText(r.width() - 68 * k);
        long now = System.currentTimeMillis();
        int pages = Math.max(1, cardPages.size());
        boolean manual = zone == Z_FUTBOL || now - cardManualAt < 20_000;
        if (!manual) cardPage = (int) ((now / PAGE_MS) % pages);   // by hand, it carries on from the page shown
        cardPage = Math.min(cardPage, pages - 1);
        int page = cardPage;
        if (pages > 1) postInvalidateDelayed(manual ? 20_000 : PAGE_MS - now % PAGE_MS + 20);
        String key = data.matchesAt + "|" + now / 60_000 + "|" + page + "|" + Float.floatToIntBits(dark()) + "|" + Ui.language;
        if (Ui.LEGACY) futbolSnapshot.draw(c, r, key, cc -> drawFutbolRows(cc, r, page, pages));
        else drawFutbolRows(c, r, page, pages);
        drawCardFocus(c, r, Ui.clamp(futbolFocus[0].value), page, pages);
    }

    private Paint cardRim;
    private TextPaint teamBig, teamSmall;

    /** Your club's game: being played or the next one (`done` false), or the last one played (`done` true). */
    private Futbol.Match teamMatch(boolean done) {
        long now = System.currentTimeMillis();
        Futbol.Match best = null;
        java.util.List<Futbol.Match> all = new java.util.ArrayList<>(data.matches);
        all.addAll(data.upcoming);
        all.addAll(data.highlights);   // the last game played is only here
        for (Futbol.Match m : all) {
            if (!m.club() || m.done() != done) continue;
            if (best == null) best = m;
            else if (!done && (m.live() || m.kickedOff(now)) && !(best.live() || best.kickedOff(now))) best = m;
            else if (!done && !(best.live() || best.kickedOff(now)) && m.start < best.start) best = m;
            else if (done && m.start > best.start) best = m;
        }
        return best;
    }

    private void drawTeamCard(Canvas c, RectF r, Futbol.Match next, Futbol.Match last) {
        if (teamBig == null || teamBig.getTextSize() != 34 * k) {
            teamBig = new TextPaint(Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF));
            teamSmall = new TextPaint(Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF));
        }
        float x = r.left + 34 * k, right = r.right - 34 * k;
        Futbol.Match m = next != null ? next : last;
        String club = Futbol.isClub(m.home.id) ? m.home.shortName : m.away.shortName;
        c.drawText("Tu equipo  ·  " + club, x, r.top + 54 * k, cardTitle);
        teamBig.setColor(cardText.getColor());
        teamSmall.setColor(cardDim.getColor());
        if (next != null) {
            // The game: both crests big, the names (and the score once it starts), when and which tournament.
            boolean scored = next.live() || next.done();
            String line = next.home.shortName + (scored ? "   " + Futbol.score(next) + "   " : "  vs  ") + next.away.shortName;
            float crest = 52 * k, base = r.top + 128 * k, mid = base - Ui.cap(teamBig) / 2;
            drawCrest(c, next.home.crest, x, mid, crest, 1);
            float tx = x + crest + 16 * k, lw = teamBig.measureText(line);
            c.drawText(TextUtils.ellipsize(line, teamBig, right - tx - crest - 16 * k, TextUtils.TruncateAt.END).toString(), tx, base, teamBig);
            drawCrest(c, next.away.crest, Math.min(tx + lw + 16 * k, right - crest), mid, crest, 1);
            String when = Futbol.status(next, System.currentTimeMillis());
            String round = next.round.isEmpty() ? next.league : next.round;
            cardDimRight.setColor(next.live() ? LIVE_RED : cardDim.getColor());
            Paint.Align was = cardDimRight.getTextAlign();
            cardDimRight.setTextAlign(Paint.Align.LEFT);
            c.drawText(when, x, base + 48 * k, cardDimRight);
            cardDimRight.setTextAlign(was);
            if (round != null && !round.isEmpty()) {
                float ww = cardDimRight.measureText(when) + 18 * k;
                c.drawText(TextUtils.ellipsize("·  " + round, teamSmall, right - x - ww, TextUtils.TruncateAt.END).toString(),
                        x + ww, base + 48 * k, teamSmall);
            }
        }
        if (last != null) {   // the last result, small, at the foot
            String res = "Último:  " + last.home.shortName + "  " + Futbol.score(last) + "  " + last.away.shortName;
            c.drawText(TextUtils.ellipsize(res, teamSmall, right - x, TextUtils.TruncateAt.END).toString(), x,
                    r.bottom - 34 * k, teamSmall);
        }
    }

    /**
     * One small dot per page under the list, the current one larger and brighter. Past seven pages, a window of seven
     * slides along with the page, its end dots smaller where more pages lie beyond (as on a phone's home screen).
     */
    private void drawPageDots(Canvas c, RectF r, int page, int pages, float alpha) {
        if (pages < 2) return;
        int n = Math.min(7, pages), first = Math.max(0, Math.min(pages - n, page - n / 2));
        float small = 4.5f * k, big = 7 * k, tiny = 3 * k, step = 12 * k, y = r.bottom - 18 * k;
        float x = r.centerX() - step * (n - 1) / 2f;
        fill.setShader(null);
        for (int i = 0; i < n; i++) {
            int p = first + i;
            boolean edge = (i == 0 && first > 0) || (i == n - 1 && first + n < pages);
            float size = p == page ? big : edge ? tiny : small;
            fill.setColor(Ui.alpha(p == page ? cardText.getColor() : Ui.alpha(cardDimRight.getColor(), 0.55f), alpha));
            c.drawCircle(x + i * step, y, size / 2, fill);
        }
    }

    private Paint roundHeading;

    private void drawFutbolRows(Canvas c, RectF r, int page, int pages) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText("Próximos partidos", x, r.top + 54 * k, cardTitle);
        if (cardPages.isEmpty()) {
            c.drawText(data.matchesAt == 0 ? "Buscando partidos…" : "No hay partidos esta semana", x, r.top + 110 * k, cardDim);
            return;
        }
        if (roundHeading == null || roundHeading.getTextSize() != 19 * k) {
            roundHeading = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
            roundHeading.setLetterSpacing(0.06f);
        }
        float cap = Ui.cap(cardText), crest = cap * 1.55f;
        int statusColor = cardDimRight.getColor();
        List<CardRow> rows = cardPages.get(Math.min(page, cardPages.size() - 1));
        for (int j = 0; j < rows.size(); j++) {
            CardRow row = rows.get(j);
            float base = r.top + (110 + 52 * j) * k;
            if (row.match == null) {
                roundHeading.setColor(statusColor);   // only the games' own "EN VIVO" is red
                c.drawText(row.text.toUpperCase(Ui.locale()), x, base - 6 * k, roundHeading);
                continue;
            }
            Futbol.Match m = row.match;
            float mid = base - cap / 2;
            drawCrest(c, m.home.crest, x, mid, crest, 1);
            float tx = x + crest + 12 * k;
            c.drawText(row.text, tx, base, cardText);
            drawCrest(c, m.away.crest, tx + row.width + 12 * k, mid, crest, 1);
            cardDimRight.setColor(m.live() ? LIVE_RED : statusColor);
            c.drawText(row.status, right, base, cardDimRight);
        }
        cardDimRight.setColor(statusColor);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (appMenu && reordering && liftIndex >= 0 && liftIndex < tileRects.length) drawAppMenu(c, tileRects[liftIndex], shelfOptions);
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        if (iconDock() && !inFade) {   // the carousel's far icons fade out
            float al = dockAlpha(i);
            if (al <= 0.01f) return;
            if (al < 0.99f) {
                inFade = true;
                RectF b = tileRects[i];
                int sv = c.saveLayerAlpha(b.left - 40 * k, b.top - 40 * k, b.right + 40 * k, b.bottom + 80 * k, Math.round(255 * al));
                drawShelfTile(c, i);
                c.restoreToCount(sv);
                inFade = false;
                return;
            }
        }
        HomeApps.Item it = shelf.get(i);
        if (iconDock() && (it.pkg != null || it.kind == HomeApps.ALL_APPS) && drawDockIcon(c, i, it)) return;
        if (!iconDock() && it.kind == HomeApps.ALL_APPS) {
            drawAllAppsSquare(c, i, it);
            return;
        }
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = (1 + FOCUS_GROW * f + LIFT_GROW * l);
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        // Glass edition: the focused tile also rises a little out of the dock (the spring gives it the bounce).
        float cx = base.centerX(), cy = base.centerY() - FOCUS_RISE * k * f;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        // Glass edition: no coloured glow around the focused app (the white rim and the rise mark it).
        if (SHELF_GLOW && fc > 0.01f && it.tile != null) drawAmbilight(c, it.tile, cell, r, fc);
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.tile != null) {
            // One copy, at the focused size: at rest the GPU shrinks it by a tenth, which looks the same. Two copies
            // (resting, focused) swapped resolution visibly as the focus arrived.
            if (iconDock()) cropImage(c, cardImage(it, bh * 16f / 9 * (1 + FOCUS_GROW), bh * (1 + FOCUS_GROW)), cell, r);
            else roundImage(c, cardImage(it, bw * (1 + FOCUS_GROW), bh * (1 + FOCUS_GROW)), cell, r);
        }
        // Glass edition: tvOS's white focus rim around the focused tile.
        if (fc > 0.01f && l <= 0.02f) {
            focusRing.setStrokeWidth(3.5f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * fc));
            float o = 1.5f * k;
            tmp.set(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o);
            c.drawRoundRect(tmp, r + o, r + o, focusRing);
        }
        if (iconDock() && fc > 0.01f) {   // the icons' name, under the focused one
            if (dockName == null) {
                dockName = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF));
                dockName.setTextAlign(Paint.Align.CENTER);
                dockName.setShadowLayer(8 * k, 0, 2 * k, 0x80000000);
            }
            dockName.setAlpha(Math.round(255 * fc * (1 - 2 * dockOpen())));
            c.drawText(it.label == null ? "" : it.label, cell.centerX(), cell.bottom + 40 * k, dockName);
        }
    }

    private TextPaint dockName;
    private final java.util.Map<String, Bitmap> appIcons = new java.util.HashMap<>();
    private final java.util.Set<String> iconAsked = new java.util.HashSet<>();

    /** The app's own launcher icon, square (made off the UI thread, once). */
    private Bitmap appIcon(String pkg) {
        Bitmap b = appIcons.get(pkg);
        if (b != null || !iconAsked.add(pkg)) return b;
        final Context ctx = getContext().getApplicationContext();
        LOADER.execute(() -> {
            try {
                android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
                Bitmap out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(out);
                d.setBounds(0, 0, 256, 256);
                d.draw(cc);
                handler.post(() -> {
                    appIcons.put(pkg, out);
                    tilesDirty = true;
                    wake();
                });
            } catch (Exception e) {
                AppLog.w("app icon " + pkg + ": " + e.getMessage());
            }
        });
        return null;
    }

    /** The "Íconos" shelf: the app's icon on a rounded tile, rising and growing with the focus, its name under it. */
    private boolean drawDockIcon(Canvas c, int i, HomeApps.Item it) {
        boolean more = it.kind == HomeApps.ALL_APPS;
        Bitmap icon = more ? null : appIcon(it.pkg);
        if (icon == null && !more) return false;
        float off = dockOff(i), f = tileFocus[i].value, fc = Ui.clamp(f), dist = Math.min(3, Math.abs(off));
        float s = (1 + 0.3f * f) * (1 - 0.12f * dist);
        RectF base = tileRects[i];
        float half = base.width() * s / 2, cx = base.centerX(), cy = base.centerY() - 22 * k * f * (1 - dockOpen()), rad = 24 * k * s * base.width() / (DOCK_ICON * k);
        int tilt = c.save();
        c.rotate(off * DOCK_ANG * 0.45f, cx, cy);
        cell.set(cx - half, cy - half, cx + half, cy + half);
        tmp.set(cell);
        tmp.offset(0, (8 + 12 * fc) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.3f + 0.25f * fc));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        if (more) {   // glass with a 3×3 grid of dots
            fill.setColor(0x47FFFFFF);
            c.drawRoundRect(cell, rad, rad, fill);
            focusRing.setStrokeWidth(2 * k);
            focusRing.setColor(0x8CFFFFFF);
            c.drawRoundRect(cell, rad, rad, focusRing);
            fill.setColor(0xFFFFFFFF);
            float q = cell.width(), d = q * 0.09f;
            for (int r = 0; r < 3; r++) for (int cc = 0; cc < 3; cc++) {
                float px = cell.left + q * (0.3f + 0.2f * cc), py = cell.top + q * (0.3f + 0.2f * r);
                tmp.set(px - d / 2, py - d / 2, px + d / 2, py + d / 2);
                c.drawRoundRect(tmp, d * 0.25f, d * 0.25f, fill);
            }
        } else {
            fill.setColor(0xFF1C1C1E);
            c.drawRoundRect(cell, rad, rad, fill);
            roundImage(c, icon, cell, rad);
        }
        if (fc > 0.01f) {
            focusRing.setStrokeWidth(3.5f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * fc));
            float o = 4 * k;
            c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, rad + o, rad + o, focusRing);
        }
        c.restoreToCount(tilt);
        if (fc > 0.01f && dockOpen() < 0.5f) {
            if (dockName == null) {
                dockName = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF));
                dockName.setTextAlign(Paint.Align.CENTER);
                dockName.setShadowLayer(8 * k, 0, 2 * k, 0x80000000);
            }
            dockName.setAlpha(Math.round(255 * fc));
            c.drawText(it.label == null ? "" : it.label, cx, cell.bottom + 40 * k, dockName);
        }
        return true;
    }

    private static final float FOCUS_RISE = 12;
    private static final boolean SHELF_GLOW = false;
    private final Paint focusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    { focusRing.setStyle(Paint.Style.STROKE); }

    private final android.util.LruCache<String, Bitmap> crispTiles = new android.util.LruCache<>(32);

    /**
     * An app's card at w x h: the sharp render when its banner allows one, else its card filtered down. The sharp one
     * is made in the background (opening each app's banner took most of a second on the first frame); until it's
     * ready the card shows filtered down, then it swaps in.
     */
    private Bitmap cardImage(HomeApps.Item it, float w, float h) {
        if (it.kind == HomeApps.APP && it.pkg != null) {
            int iw = Math.round(w), ih = Math.round(h);
            Bitmap sharp = HomeApps.sharpTileCached(it.pkg, iw, ih);
            if (sharp != null) return sharp;
            requestSharp(it.pkg, iw, ih);
        }
        return it.tile == null ? null : crispTile(it.tile, w, h);   // All Apps' cards are still loading
    }

    private final java.util.Set<String> sharpAsked = new java.util.HashSet<>();

    /** Memory is short while Home is hidden: the scaled cards and the background go; they're made again on return. */
    void trimImages() {
        if (running) return;
        crispTiles.evictAll();
        WatchNext.get(getContext()).trim();
        imageShaders.clear();
        // The shelf's sharp tiles stay (half a MB): made again on return, YouTube's and Prime Video's rebuilt logos
        // showed their stock banner first, and their lettering visibly changed size as the sharp one swapped in.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) keep.add(it.pkg);
        HomeApps.trimSharpExcept(keep);
        java.util.Iterator<String> asked = sharpAsked.iterator();
        while (asked.hasNext()) {
            String key = asked.next();
            int at = key.indexOf('@');
            if (at < 0 || !keep.contains(key.substring(0, at))) asked.remove();
        }
        Symbols.trim();
        wallpaper = null;    // kept on disk, ready: read again in a moment when Home comes back
        wallpaperKey = "";
    }

    private void requestSharp(final String pkg, final int w, final int h) {
        if (!sharpAsked.add(pkg + "@" + w + "x" + h)) return;   // asked once: made, or it has no sharp version
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            if (HomeApps.sharpTile(c, pkg, w, h) == null) return;
            handler.post(() -> {
                tilesDirty = gridDirty = true;
                wake();
            });
        });
    }
    private final Map<Bitmap, Integer> ambilightColors = new WeakHashMap<>();
    private Paint ambilight;

    /**
     * Ambilight: the focused app's colour glowing out from behind its tile, like the lights on the back of a TV. The
     * colour is the most vivid one in its banner (Netflix's red on its white card, Disney+'s teal).
     */
    private void drawAmbilight(Canvas c, Bitmap tile, RectF cell, float r, float f) {
        Integer color = ambilightColors.get(tile);
        if (color == null) {
            color = vivid(tile);
            ambilightColors.put(tile, color);
        }
        float blur = 38 * k;
        if (ambilight == null || ambilightBlur != blur) {
            ambilight = new Paint(Paint.ANTI_ALIAS_FLAG);
            ambilight.setMaskFilter(new android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL));
            ambilightBlur = blur;
        }
        ambilight.setColor(Ui.alpha(color, tone(0.3f, 0.45f) * f));   // a hint of light, not a halo
        tmp.set(cell);
        tmp.inset(-8 * k, -4 * k);
        tmp.offset(0, 6 * k);
        shadows.draw(c, tmp, r + 10 * k, blur, ambilight);
    }

    private float ambilightBlur;

    /** The banner's most saturated light, brightened enough to glow; a grey banner glows soft white. */
    private static int vivid(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
        int[] px = new int[32 * 18];
        s.getPixels(px, 0, 32, 0, 0, 32, 18);
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, w = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            android.graphics.Color.colorToHSV(p, hsv);
            double weight = Math.pow(hsv[1], 3) * hsv[2] + 1e-4;
            r += ((p >> 16) & 255) * weight;
            g += ((p >> 8) & 255) * weight;
            bl += (p & 255) * weight;
            w += weight;
        }
        int c = w == 0 ? 0xFFFFFFFF : android.graphics.Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w));
        android.graphics.Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.2f) return 0xFFE8ECF4;
        hsv[1] = Math.max(hsv[1], 0.75f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** `src` filtered down to w x h (halving first, no aliasing); kept, since cards redraw on every focus frame. */
    private Bitmap crispTile(Bitmap src, float w, float h) {
        int iw = Math.round(w), ih = Math.round(h);
        if (iw <= 0 || ih <= 0 || iw >= src.getWidth()) return src;
        String key = System.identityHashCode(src) + "@" + iw + "x" + ih;
        Bitmap b = crispTiles.get(key);
        if (b == null) {
            b = downscale(src, iw, ih);
            crispTiles.put(key, b);
        }
        return b;
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        // Between the two copies, while the focus grows or shrinks, they cross-fade: swapping at the midpoint made
        // thin lettering (YouTube's, Prime Video's) visibly jump.
        float t = smoothstep(0.3f, 0.7f, (s - 1) / FOCUS_GROW);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        if (t < 0.999f) drawLogoCopy(c, it, box, s, mode, true, 1 - t);
        if (t > 0.001f) drawLogoCopy(c, it, box, s, mode, false, t);
        bitmapPaint.setColorFilter(null);
        bitmapPaint.setAlpha(255);
    }

    private void drawLogoCopy(Canvas c, HomeApps.Item it, RectF box, float s, int mode, boolean rest, float alpha) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, tmp, bitmapPaint);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        HomeApps.Item browser = null;
        for (HomeApps.Item it : HomeApps.drawerShortcuts(c)) {
            if (it.kind == HomeApps.ALL_APPS) continue;   // at the end of the bar now
            if (it.kind == HomeApps.ANDROID_SETTINGS) continue;   // in the control center now (Más)
            if (it.kind == HomeApps.APP) browser = it;
            else list.add(it);
        }
        // (Entradas: in the control center now)
        if (browser != null) list.add(browser);
        list.add(HomeApps.action(HEART_KIND, "heart", "Guardar fondo", "sparkles"));
        list.add(HomeApps.action(NEXTBG_KIND, "nextbg", "Cambiar fondo", "sync"));   // joined to the heart: one capsule
        // Two circles at the end: the team (its crest only) and the weather (OK opens its panel).
        if (Prefs.futbol()) list.add(HomeApps.action(TEAM_KIND, "team", "Mi equipo", "tv"));
        if (Prefs.weather()) list.add(HomeApps.action(WX_KIND, "weather", "Clima", "partly_cloudy_day"));
        return list;
    }

    /** New lists from reload(): kept under the inputs while they're unfolded. */
    private void setDrawerLists(List<HomeApps.Item> buttons, List<HomeApps.Item> inputs) {
        inputsList = inputs;
        drawerButtons = buttons;
        setDrawerItems(drawerPanel == 1 ? new ArrayList<>(inputs) : buttons);
    }

    /** Entradas unfolds: the drawer's row becomes TV en vivo and the HDMI inputs; Back folds it again. */
    private void unfoldInputs(boolean open) {
        drawerPanel = open ? 1 : 0;
        setDrawerItems(open ? new ArrayList<>(inputsList) : drawerButtons);
        drawerIndex = 0;
        if (!open) {
            for (int i = 0; i < drawerItems.size(); i++) if (drawerItems.get(i).kind == HomeApps.INPUTS) drawerIndex = i;
        }
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        layoutDrawer();
        drawerDirty = true;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        if (drawerPanel != 1) {   // D2: wide buttons in a row, centred
            // P3: a dock of round icons, well apart, on a strip of glass; the focused one's name shows under it.
            int n = drawerItems.size();
            float gap = 64 * k, d = 116 * k;
            float x = (w - (n * d + Math.max(0, n - 1) * gap)) / 2, top = (ROW_TOP + 8) * k;
            for (int i = 0; i < n; i++) {
                if (i > 0 && drawerItems.get(i).kind == NEXTBG_KIND && drawerItems.get(i - 1).kind == HEART_KIND) x -= gap - 4 * k;
                drawerRects[i].set(x, top, x + d, top + d);
                x += d + gap;
            }
            if (n > 0) dockStrip.set(drawerRects[0].left - 46 * k, top - 24 * k, drawerRects[n - 1].right + 46 * k, top + d + 24 * k);
            futbolCard.set((w - 1040 * k) / 2, CARDS_TOP * k, (w + 1040 * k) / 2, (CARDS_TOP + CARDS_H) * k);
            // The weather opens under the buttons as two cards side by side: now and the next hours, the next days.
            float wl = MARGIN * k, wr = CONTENT_R * k, split = wl + (wr - wl) * 0.585f;
            weatherCard.set(wl, CARDS_TOP * k, split - 16 * k, (CARDS_TOP + CARDS_H) * k);
            weekCard.set(split + 16 * k, CARDS_TOP * k, wr, (CARDS_TOP + CARDS_H) * k);
            drawerDirty = true;
            return;
        }
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = (w - (n * tw + Math.max(0, n - 1) * gap)) / 2, top = ROW_TOP * k;   // centred
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.futbol();
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        futbolCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        refreshFavs(false);
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        heartSub = false;
        if (drawerPanel == 1) unfoldInputs(false);
        drawerPanel = 0;
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        drawerGlass(c, r, radius, lightTint, shadowColor, drop, 1);
    }

    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float alpha) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, (1 - dk) * alpha));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, (1 - dk) * alpha);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk * alpha);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerPanel == 1) c.drawText("Entradas  ·  Atrás vuelve", drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerPanel != 1) {   // D2: the wide buttons, and the team's card under them when it's open
            if (drawerPanel == 2 && Prefs.futbol()) drawTeamBig(c, futbolCard);
            if (drawerPanel == 5) drawFavsCard(c);
            if (drawerPanel == 3 && Prefs.weather()) {   // both at once: no pages to turn
                drawerCard(c, weatherCard);
                drawWeather(c, weatherCard);
                drawerCard(c, weekCard);
                drawWeek(c, weekCard);
            }
            if (!drawerItems.isEmpty()) drawerGlass(c, dockStrip, dockStrip.height() / 2, 0.45f, 0x10000000, 6 * k);
            for (int i = 1; i < drawerItems.size(); i++) {   // ♥ and ⟳: one capsule of glass
                if (drawerItems.get(i).kind != NEXTBG_KIND || drawerItems.get(i - 1).kind != HEART_KIND) continue;
                tmp.set(drawerRects[i - 1].left, drawerRects[i - 1].top, drawerRects[i].right, drawerRects[i].bottom);
                drawerGlass(c, tmp, tmp.height() / 2, 0.7f, 0x14000000, 6 * k);
            }
            for (int i = 0; i < drawerItems.size(); i++) drawDrawerButton(c, i);
            if (drawerPanel == 0 && zone == Z_DRAWER && drawerIndex < drawerItems.size()) {   // the name, under it
                HomeApps.Item it = drawerItems.get(drawerIndex);
                String[] tx = drawerText(it);
                if (dockLabel == null) {
                    dockLabel = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF));
                    dockLabel.setTextAlign(Paint.Align.CENTER);
                    dockSub = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xCCFFFFFF));
                    dockSub.setTextAlign(Paint.Align.CENTER);
                    for (Paint sp : new Paint[] {dockLabel, dockSub}) sp.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
                }
                RectF r = drawerRects[drawerIndex];
                float y = dockStrip.bottom + 44 * k + (it.kind == HEART_KIND ? 122 * k - 24 * k : 0);
                c.drawText(tx[0], r.centerX(), y, dockLabel);
                if (!tx[1].isEmpty()) c.drawText(tx[1], r.centerX(), y + 32 * k, dockSub);
            }
            return;
        }
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        // (the inputs unfolded: no cards under them; the weather lives under the clock now)
    }

    private final Bitmap[] crestFor = new Bitmap[2], crestSoft = new Bitmap[2];
    private TextPaint teamName, teamHuge, teamLabel, teamPill;
    private final android.graphics.Path teamClip = new android.graphics.Path();

    /** The cards' All Apps square: hidden (no room taken) until → past the last app reveals it. */
    private final Spring[] allReveal = {new Spring(0.3f, 0.86f, 0)};
    private boolean allArmed;

    private int allAppsAt() {
        if (iconDock() || shelf.isEmpty()) return -1;
        int last = shelf.size() - 1;
        return shelf.get(last).kind == HomeApps.ALL_APPS && last > 0 ? last : -1;
    }

    /** The cards' last tile: All Apps, a rounded square of glass with nine dots and its name; focused, white. */
    private void drawAllAppsSquare(Canvas c, int i, HomeApps.Item it) {
        if (tileRects[i].width() < 2 * k) return;   // hidden
        float f = Ui.clamp(tileFocus[i].value), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float cx = base.centerX(), cy = base.centerY() - 6 * k * f, half = base.width() * s / 2;
        cell.set(cx - half, cy - half, cx + half, cy + half);
        float r = TILE_RADIUS * k * s;
        tmp.set(cell);
        tmp.offset(0, (8 + 12 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.25f + 0.3f * f));
        shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(blend(0x47FFFFFF, WHITE, f));
        c.drawRoundRect(cell, r, r, fill);
        focusRing.setStrokeWidth(2 * k);
        focusRing.setColor(Ui.alpha(0x8CFFFFFF, 1 - f));
        c.drawRoundRect(cell, r, r, focusRing);
        int ink = blend(0xFFFFFFFF, INK, f);
        fill.setColor(ink);
        float q = cell.width(), d = q * 0.09f, gx = cell.left + q * 0.5f, gy = cell.top + q * 0.5f;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            float px = gx + (col - 1) * q * 0.16f, py = gy + (row - 1) * q * 0.16f;
            tmp.set(px - d / 2, py - d / 2, px + d / 2, py + d / 2);
            c.drawRoundRect(tmp, d * 0.25f, d * 0.25f, fill);
        }
        // (no label: the nine dots say it)
    }

    /** One box-blur pass over ARGB pixels (each channel, alpha too), along rows or columns. */
    private static void boxBlurAlpha(int[] src, int[] dst, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w, len = rows ? w : h, win = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int q = Math.max(0, Math.min(len - 1, i)), c = src[rows ? l * w + q : q * w + l];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[rows ? l * w + i : i * w + l] = (sa / win) << 24 | (sr / win) << 16 | (sg / win) << 8 | (sb / win);
                int out = Math.max(0, i - r), add = Math.min(len - 1, i + r + 1);
                int co = src[rows ? l * w + out : out * w + l], ca = src[rows ? l * w + add : add * w + l];
                sa += (ca >>> 24) - (co >>> 24); sr += ((ca >> 16) & 255) - ((co >> 16) & 255);
                sg += ((ca >> 8) & 255) - ((co >> 8) & 255); sb += (ca & 255) - (co & 255);
            }
        }
    }

    /** A crest blurred on a transparent square, drawn big as the card's soft background. */
    private Bitmap softCrest(int slot, Bitmap crest) {
        if (crest == null) return null;
        if (crestFor[slot] != crest) {
            crestFor[slot] = crest;
            try {
                Bitmap src = crest.getConfig() == Bitmap.Config.HARDWARE ? crest.copy(Bitmap.Config.ARGB_8888, false) : crest;
                int n = 128, in = 96, ih = Math.max(1, Math.round(in * (float) src.getHeight() / Math.max(1, src.getWidth())));
                Bitmap sq = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(sq);
                Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
                cc.drawBitmap(src, null, new RectF((n - in) / 2f, (n - ih) / 2f, (n + in) / 2f, (n + ih) / 2f), pp);
                int[] px = new int[n * n], tmpPx = new int[n * n];
                sq.getPixels(px, 0, n, 0, 0, n, n);
                for (int pass = 0; pass < 2; pass++) {   // soft, but still the crest
                    boxBlurAlpha(px, tmpPx, n, n, 2, true);
                    boxBlurAlpha(tmpPx, px, n, n, 2, false);
                }
                sq.setPixels(px, 0, n, 0, 0, n, n);
                crestSoft[slot] = sq;
            } catch (RuntimeException e) {
                crestSoft[slot] = null;
            }
        }
        return crestSoft[slot];
    }

    /**
     * The team's card (T1 + T2): both crests big and blurred behind, each on its side; on them the crests sharp with
     * the names, and in the middle the time (or, live, the score and the minute).
     */
    private void drawTeamBig(Canvas c, RectF r) {
        Futbol.Match m = teamMatch(false);
        if (m == null) m = teamMatch(true);
        if (m == null) {
            drawFutbolCard(c, r);
            return;
        }
        if (teamName == null) {
            teamName = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
            teamName.setTextAlign(Paint.Align.CENTER);
            teamHuge = new TextPaint(Ui.text(Ui.displaySemibold, 84 * k, 0xFFFFFFFF));
            teamHuge.setTextAlign(Paint.Align.CENTER);
            teamLabel = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xCCFFFFFF));
            teamLabel.setTextAlign(Paint.Align.CENTER);
            teamLabel.setLetterSpacing(0.2f);
            teamPill = new TextPaint(Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF));
            teamPill.setTextAlign(Paint.Align.CENTER);
            for (Paint sp : new Paint[] {teamName, teamHuge, teamLabel}) sp.setShadowLayer(12 * k, 0, 2 * k, 0x99000000);
        }
        float rad = 30 * k;
        tmp.set(r);
        tmp.offset(0, 14 * k);
        shadow.setColor(0x59000000);
        shadows.draw(c, tmp, rad, SHADOW_BLUR * 1.4f * k, shadow);
        c.save();
        teamClip.reset();
        teamClip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(teamClip);
        fill.setShader(null);
        fill.setColor(0xFF0D1726);
        c.drawRect(r, fill);
        // Behind: each crest huge and soft, on its side.
        float big = r.height() * 1.1f * 128 / 96;   // the crest inside about the card's height
        Bitmap[] soft = {softCrest(0, m.home.crest), softCrest(1, m.away.crest)};
        float[] at = {r.left + 210 * k, r.right - 210 * k};
        bitmapPaint.setFilterBitmap(true);
        for (int j = 0; j < 2; j++) {
            if (soft[j] == null) continue;
            float bh = big * soft[j].getHeight() / Math.max(1f, soft[j].getWidth());
            cell.set(at[j] - big / 2, r.centerY() - bh / 2, at[j] + big / 2, r.centerY() + bh / 2);
            bitmapPaint.setAlpha(190);
            c.drawBitmap(soft[j], null, cell, bitmapPaint);
        }
        bitmapPaint.setAlpha(255);
        fill.setColor(0x47000000);
        c.drawRect(r, fill);
        c.restore();
        focusRing.setStrokeWidth(2 * k);
        focusRing.setColor(0x66FFFFFF);
        c.drawRoundRect(r, rad, rad, focusRing);
        // On them: the crests sharp, the names under them.
        float cy = r.top + 140 * k, cs = 120 * k;
        drawCrest(c, m.home.crest, at[0] - cs / 2, cy, cs, 1);
        drawCrest(c, m.away.crest, at[1] - cs / 2, cy, cs, 1);
        c.drawText(m.home.shortName, at[0], r.top + 250 * k, teamName);
        c.drawText(m.away.shortName, at[1], r.top + 250 * k, teamName);
        // The middle: the round on top; the time, or the score (live: the minute in a red pill).
        float mx = r.centerX();
        String round = m.round == null || m.round.isEmpty() ? m.league : m.round;
        c.drawText(TextUtils.ellipsize(round.toUpperCase(Ui.locale()), teamLabel, 440 * k, TextUtils.TruncateAt.END).toString(),
                mx, r.top + 58 * k, teamLabel);
        long now = System.currentTimeMillis();
        String status = Futbol.status(m, now);
        if (m.live() || m.done()) {
            c.drawText(Futbol.score(m), mx, r.top + 172 * k, teamHuge);
            float pw = Math.max(110 * k, teamPill.measureText(status) + 40 * k), py = r.top + 200 * k;
            fill.setColor(m.live() ? LIVE_RED : 0x59FFFFFF);
            tmp.set(mx - pw / 2, py, mx + pw / 2, py + 44 * k);
            c.drawRoundRect(tmp, 22 * k, 22 * k, fill);
            c.drawText(status, mx, tmp.centerY() + Ui.halfCap(teamPill), teamPill);
        } else {
            teamHuge.setTextSize(84 * k);
            float tw = teamHuge.measureText(status);
            if (tw > 460 * k) teamHuge.setTextSize(84 * k * 460 * k / tw);   // a long one ("Mañana 21:30") shrinks
            c.drawText(status, mx, r.top + 172 * k, teamHuge);
            teamHuge.setTextSize(84 * k);
            Futbol.Match last = teamMatch(true);   // and the last one, under it
            if (last != null) {
                String ln = "ÚLTIMO  " + last.home.abbr + " " + Futbol.score(last) + " " + last.away.abbr;
                c.drawText(TextUtils.ellipsize(ln, teamLabel, 440 * k, TextUtils.TruncateAt.END).toString(), mx, r.top + 240 * k, teamLabel);
            } else c.drawText("vs", mx, r.top + 236 * k, teamLabel);
        }
    }

    /**
     * The heart: focused, it grows down into a pill (like the search with its microphone) with a second button, the
     * saved backgrounds. OK on the heart saves the background on screen; ↓ and OK opens "Mis fondos".
     */
    private void drawHeartButton(Canvas c, int i) {
        RectF base = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), open = Math.max(f, heartSub || drawerPanel == 5 ? 1 : 0);
        float d = base.height(), cx = base.centerX(), top = base.top, sub = 12 * k + d;
        cell.set(base.left, top, base.right, base.bottom + sub * open);
        float r = d / 2;
        if (open > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 10 * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.4f * open));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (open > 0.01f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);   // at rest the capsule is the glass
        boolean onHeart = zone == Z_DRAWER && drawerIndex == i && !heartSub;
        boolean onSub = zone == Z_DRAWER && drawerIndex == i && heartSub;
        float hy = base.centerY(), sy = base.centerY() + sub;
        if (onHeart) {
            fill.setShader(null);
            fill.setColor(WHITE);
            c.drawCircle(cx, hy, r - 4 * k, fill);
        }
        drawHeart(c, cx, hy + 2 * k, 21 * k, onHeart ? INK : tone(L_INK, 0xF2FFFFFF), heartOn);
        if (open > 0.01f) {
            if (onSub) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(WHITE, open));
                c.drawCircle(cx, sy, r - 4 * k, fill);
            }
            int ink = Ui.alpha(onSub ? INK : tone(L_INK, 0xF2FFFFFF), open);
            // A little framed picture: the saved backgrounds.
            float pw = 40 * k, ph = 30 * k;
            if (heartStroke == null) {
                heartStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                heartStroke.setStyle(Paint.Style.STROKE);
                heartStroke.setStrokeJoin(Paint.Join.ROUND);
            }
            heartStroke.setStrokeWidth(3.2f * k);
            heartStroke.setColor(ink);
            tmp.set(cx - pw / 2, sy - ph / 2, cx + pw / 2, sy + ph / 2);
            c.drawRoundRect(tmp, 5 * k, 5 * k, heartStroke);
            heartPath.reset();
            heartPath.moveTo(tmp.left + 5 * k, tmp.bottom - 5 * k);
            heartPath.lineTo(cx - 4 * k, sy - 2 * k);
            heartPath.lineTo(cx + 3 * k, sy + 5 * k);
            heartPath.lineTo(cx + 8 * k, sy);
            heartPath.lineTo(tmp.right - 5 * k, tmp.bottom - 5 * k);
            c.drawPath(heartPath, heartStroke);
        }
        if (onHeart || onSub) {
            focusRing.setStrokeWidth(4 * k);
            focusRing.setColor(0xF2FFFFFF);
            float o = 5 * k;
            c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, r + o, r + o, focusRing);
        }
    }

    /** "Usar galería de favoritos": Home shows the saved ones; off, what it showed before (Bing's, say). */
    private void setFavoritesOn(boolean on) {
        Context ctx = getContext().getApplicationContext();
        String now = Prefs.homeBackground(ctx);
        if (on && !now.equals(Gallery.FAVORITES)) {
            Prefs.setHomeBackgroundBefore(ctx, now);
            Prefs.setHomeBackground(ctx, Gallery.FAVORITES);
        } else if (!on && now.equals(Gallery.FAVORITES)) {
            Prefs.setHomeBackground(ctx, Prefs.homeBackgroundBefore(ctx));
        }
        favOn = on;
        updateWallpaper();
        drawerDirty = true;
    }

    /** A heart, `r` about half its width, filled or as an outline. */
    private void drawHeart(Canvas c, float cx, float cy, float r, int color, boolean filled) {
        heartPath.reset();
        heartPath.moveTo(cx, cy + r * 0.95f);
        heartPath.cubicTo(cx - r * 1.35f, cy + r * 0.05f, cx - r * 1.05f, cy - r * 1.05f, cx, cy - r * 0.45f);
        heartPath.cubicTo(cx + r * 1.05f, cy - r * 1.05f, cx + r * 1.35f, cy + r * 0.05f, cx, cy + r * 0.95f);
        heartPath.close();
        if (filled) {
            fill.setShader(null);
            fill.setColor(0xFFFF4D6D);
            c.drawPath(heartPath, fill);
            return;
        }
        if (heartStroke == null) {
            heartStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            heartStroke.setStyle(Paint.Style.STROKE);
            heartStroke.setStrokeJoin(Paint.Join.ROUND);
        }
        heartStroke.setStrokeWidth(3.2f * k);
        heartStroke.setColor(color);
        c.drawPath(heartPath, heartStroke);
    }

    /** What the heart and "Mis fondos" show: is the background on screen saved, and how many are (background thread). */
    private void refreshFavs(boolean thumbs) {
        final Context ctx = getContext().getApplicationContext();
        LOADER.execute(() -> {
            Gallery.Item cur = wallpaperWanted ? Gallery.current(ctx) : null;
            boolean on = cur != null && Gallery.isFavorite(ctx, cur.url);
            boolean using = Prefs.homeBackground(ctx).equals(Gallery.FAVORITES);
            List<Gallery.Item> list = new ArrayList<>();
            for (Gallery.Item it : Gallery.favorites(ctx)) if (!it.paused) list.add(it);
            handler.post(() -> {
                heartOn = on;
                favOn = using;
                favs = list;
                favCount = list.size();
                favIndex = Math.min(favIndex, Math.max(0, list.size() - 1));
                drawerDirty = true;
                wake();
            });
            if (!thumbs) return;
            for (Gallery.Item it : list) {   // small copies for the card, one by one
                if (favThumbs.containsKey(it.url)) continue;
                try {
                    java.io.File f = Gallery.image(ctx, it);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                    if (b == null) continue;
                    handler.post(() -> {
                        favThumbs.put(it.url, b);
                        drawerDirty = true;
                        wake();
                    });
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("fav thumb: " + e.getMessage());
                }
            }
        });
    }

    /** "Mis fondos": the saved backgrounds in a row under the buttons; OK puts the focused one on Home. */
    private void drawFavsCard(Canvas c) {
        favsCard.set(MARGIN * k, FAVS_TOP * k, CONTENT_R * k, (FAVS_TOP + FAVS_H) * k);
        drawerCard(c, favsCard);
        if (favHead == null) {
            favHead = new TextPaint(Ui.text(Ui.semibold, 21 * k, 0xD9FFFFFF));
            favHead.setLetterSpacing(0.16f);
            favEmpty = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF));
        }
        favHead.setColor(tone(L_DIM, 0xD9FFFFFF));
        favEmpty.setColor(drawerInk(0.85f));
        float x0 = favsCard.left + 36 * k, top = favsCard.top + 70 * k;
        c.drawText(zone == Z_FAVS && favRow == 1 ? "MIS FONDOS  ·  OK LO PONE DE FONDO" : "MIS FONDOS", x0, favsCard.top + 46 * k, favHead);
        // Top right: the switch, "Usar galería de favoritos".
        if (favSwitchText == null) favSwitchText = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF));
        boolean sf = zone == Z_FAVS && favRow == 0;
        float swW = 64 * k, swH = 34 * k, sR = favsCard.right - 32 * k, sCy = favsCard.top + 38 * k;
        String lbl = "Usar galería de favoritos";
        float lw = favSwitchText.measureText(lbl);
        favSwitch.set(sR - swW - 18 * k - lw - 22 * k, sCy - 26 * k, sR + 10 * k, sCy + 26 * k);
        if (sf) {
            fill.setShader(null);
            fill.setColor(WHITE);
            c.drawRoundRect(favSwitch, 26 * k, 26 * k, fill);
        }
        favSwitchText.setColor(sf ? INK : drawerInk(0.95f));
        c.drawText(lbl, favSwitch.left + 22 * k, sCy + Ui.halfCap(favSwitchText), favSwitchText);
        tmp.set(sR - swW, sCy - swH / 2, sR, sCy + swH / 2);
        fill.setShader(null);
        fill.setColor(favOn ? 0xFF34C759 : (sf ? 0x33000000 : 0x47FFFFFF));
        c.drawRoundRect(tmp, swH / 2, swH / 2, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(favOn ? tmp.right - swH / 2 : tmp.left + swH / 2, sCy, swH / 2 - 4 * k, fill);
        if (favs.isEmpty()) {
            c.drawText("Todavía no guardaste fondos: tocá el corazón para guardar el que estás viendo.", x0,
                    favsCard.centerY() + 20 * k, favEmpty);
            return;
        }
        float th = favsCard.bottom - 30 * k - top, tw = th * 16 / 9, gap = 22 * k;
        int perView = Math.max(1, (int) ((favsCard.width() - 72 * k + gap) / (tw + gap)));
        int first = zone == Z_FAVS && favRow == 1 ? Math.max(0, Math.min(favIndex - perView / 2, favs.size() - perView)) : 0;
        c.save();
        c.clipRect(favsCard.left + 20 * k, favsCard.top, favsCard.right - 20 * k, favsCard.bottom);
        for (int i = Math.max(0, first); i < favs.size() && i < first + perView + 1; i++) {
            float x = x0 + (i - first) * (tw + gap);
            boolean f = zone == Z_FAVS && favRow == 1 && i == favIndex;
            float g = f ? 8 * k : 0;
            cell.set(x - g, top - g, x + tw + g, top + th + g);
            Bitmap b = favThumbs.get(favs.get(i).url);
            if (b != null) cropImage(c, b, cell, 14 * k);
            else {
                fill.setShader(null);
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            }
            if (f) {
                focusRing.setStrokeWidth(4 * k);
                focusRing.setColor(0xF2FFFFFF);
                c.drawRoundRect(cell.left - 5 * k, cell.top - 5 * k, cell.right + 5 * k, cell.bottom + 5 * k, 19 * k, 19 * k, focusRing);
            }
        }
        c.restore();
    }

    /** A drawer button's name and its line (the apps' count, the next game, the weather now...). */
    private String[] drawerText(HomeApps.Item it) {
        String title = it.label, sub = "";
        if (it.kind == TEAM_KIND) {
            Futbol.Match m = teamMatch(false);
            if (m == null) m = teamMatch(true);
            if (m != null) {
                boolean home = Futbol.isClub(m.home.id);
                Futbol.Team me = home ? m.home : m.away, them = home ? m.away : m.home;
                title = me.shortName;
                long now = System.currentTimeMillis();
                sub = m.live() || m.done() ? Futbol.score(m) + " vs " + them.shortName + (m.live() ? "  ·  " + Futbol.status(m, now) : "")
                        : Futbol.status(m, now) + " vs " + them.shortName;
            }
        } else if (it.kind == WX_KIND) {
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp)) sub = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay);
        } else if (it.kind == HEART_KIND) {
            title = heartSub ? "Mis fondos" : heartOn ? "Fondo guardado" : "Guardar fondo";
            sub = heartSub ? (favCount == 1 ? "1 guardado" : favCount + " guardados") : "↓ Mis fondos";
        } else if (it.kind == NEXTBG_KIND) {
            title = "Cambiar fondo";
            sub = "Pasa a la próxima foto";
        } else if (it.kind == HomeApps.ALL_APPS) sub = others.size() + " apps";
        else if (it.kind == HomeApps.ANDROID_SETTINGS) sub = "Imagen, sonido, red";
        else if (it.kind == HomeApps.INPUTS) sub = "TV en vivo · HDMI";
        return new String[] {title, sub};
    }

    /** D2: a wide drawer button: its symbol (the team's crest) on the left, the name and a line under it. */
    private void drawDrawerButton(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        if (it.kind == HEART_KIND) {
            drawHeartButton(c, i);
            return;
        }
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.04f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = cell.height() / 2;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f && it.kind != NEXTBG_KIND) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f), dim = blend(tone(L_DIM, 0xB3FFFFFF), 0xB3141414, f);
        if (btnTitle == null) {
            btnTitle = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF));
            btnSub = new TextPaint(Ui.text(Ui.regular, 20 * k, 0xB3FFFFFF));
        }
        if (true) {   // P3: every button is round, its icon alone
            if (it.kind == HomeApps.APP && "globe".equals(it.symbol)) {   // the browser: a globe
                if (heartStroke == null) {
                    heartStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                    heartStroke.setStyle(Paint.Style.STROKE);
                    heartStroke.setStrokeJoin(Paint.Join.ROUND);
                }
                float gr = 22 * k * s;
                heartStroke.setStrokeWidth(3.2f * k);
                heartStroke.setColor(ink);
                c.drawCircle(cx, cy, gr, heartStroke);
                c.drawLine(cx - gr, cy, cx + gr, cy, heartStroke);
                tmp.set(cx - gr * 0.5f, cy - gr, cx + gr * 0.5f, cy + gr);
                c.drawOval(tmp, heartStroke);
                return;
            }
            if (it.kind == HEART_KIND) {   // filled: the background on screen is saved
                drawHeart(c, cx, cy + 2 * k * s, 21 * k * s, ink, heartOn);
                return;
            }
            if (it.kind == TEAM_KIND) {
                Futbol.Match m = teamMatch(false);
                if (m == null) m = teamMatch(true);
                Bitmap crest = m == null ? null : Futbol.isClub(m.home.id) ? m.home.crest : m.away.crest;
                if (crest != null) drawCrest(c, crest, cx - 32 * k * s, cy, 64 * k * s, 1);
                else Symbols.draw(getContext(), c, "tv", cx, cy, 40 * k * s, ink);
            } else Symbols.draw(getContext(), c, it.symbol, cx, cy, (it.kind == WX_KIND ? 46 : 40) * k * s, ink);
            return;
        }
        String title = it.label, sub = "";
        float ix = cell.left + 58 * k * s, tx = cell.left + 110 * k * s, room = cell.right - 24 * k - tx;
        if (it.kind == TEAM_KIND) {   // the club: its crest, its name, the game (live: the score and the minute)
            Futbol.Match m = teamMatch(false);
            if (m == null) m = teamMatch(true);
            if (m != null) {
                boolean home = Futbol.isClub(m.home.id);
                Futbol.Team me = home ? m.home : m.away, them = home ? m.away : m.home;
                title = me.shortName;
                long now = System.currentTimeMillis();
                sub = m.live() || m.done() ? Futbol.score(m) + " vs " + them.shortName + (m.live() ? "  ·  " + Futbol.status(m, now) : "")
                        : Futbol.status(m, now) + " vs " + them.shortName;
                if (me.crest != null) drawCrest(c, me.crest, ix - 26 * k * s, cy, 52 * k * s, 1);
                else Symbols.draw(getContext(), c, "tv", ix, cy, 34 * k * s, ink);
            } else Symbols.draw(getContext(), c, "tv", ix, cy, 34 * k * s, ink);
        } else {
            Symbols.draw(getContext(), c, it.symbol, ix, cy, 34 * k * s, ink);
            if (it.kind == HomeApps.ALL_APPS) sub = others.size() + " apps";
            else if (it.kind == HomeApps.ANDROID_SETTINGS) sub = "Imagen, sonido, red";
            else if (it.kind == HomeApps.INPUTS) sub = "TV en vivo · HDMI";
            else if (it.kind == FAVS_KIND) sub = favCount == 0 ? "Ninguno guardado" : favCount == 1 ? "1 guardado" : favCount + " guardados";
        }
        btnTitle.setColor(ink);
        btnTitle.setTextSize(27 * k * s);
        btnSub.setColor(dim);
        btnSub.setTextSize(20 * k * s);
        boolean two = !sub.isEmpty();
        c.drawText(TextUtils.ellipsize(title, btnTitle, room, TextUtils.TruncateAt.END).toString(), tx,
                two ? cy - 4 * k : cy + Ui.halfCap(btnTitle), btnTitle);
        if (two) c.drawText(TextUtils.ellipsize(sub, btnSub, room, TextUtils.TruncateAt.END).toString(), tx, cy + 28 * k * s, btnSub);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        int page = weatherPage;
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey() + "|" + page, cc -> {
            if (page == 0) drawWeather(cc, r);
            else drawWeek(cc, r);
        });
        else if (page == 0) drawWeather(c, r);
        else drawWeek(c, r);
        drawCardFocus(c, r, Ui.clamp(weatherFocus[0].value), page, 2);
    }

    /** The next seven days: day, symbol, high and low, in seven columns. */
    private void drawWeek(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle + " · 7 días", x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        int n = Math.min(7, wx.days.size());
        if (n == 0) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        float cw = (r.width() - 68 * k) / 7;
        for (int i = 0; i < n; i++) {
            float cx = x + cw * (i + 0.5f);
            c.drawText(dayText[i], cx, r.top + 104 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), cx, r.top + 152 * k, 32 * k, drawerInk(0.9f));
            c.drawText(dayMaxText[i], cx, r.top + 206 * k, hourTemp);
            c.drawText(dayMinText[i], cx, r.top + 246 * k, hourPaint);
        }
    }

    /** A focused drawer card: a white rim, and its page dots (dots only while it has focus). */
    /**
     * The drawer's cards: the arrows move between them; OK on one turns its pages with ← → (its page dots brighten), and
     * OK again or Back goes back to moving between the cards.
     */
    private boolean cardEngaged;

    private void drawCardFocus(Canvas c, RectF r, float f, int page, int pages) {
        if (f <= 0.01f) return;
        if (cardRim == null) {
            cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardRim.setStyle(Paint.Style.STROKE);
        }
        cardRim.setStrokeWidth(3 * k);
        cardRim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
        float h = 1.5f * k;
        c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, 30 * k - h, 30 * k - h, cardRim);
        // On focus too, softer, so it shows there are more pages; brighter once OK lets the arrows turn them.
        drawPageDots(c, r, page, pages, f * (cardEngaged ? 1 : 0.55f));
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            for (int i = 0; i < wx.days.size() && i < 7; i++) {
                String day = Ui.datePattern("EEE").format(wx.days.get(i)).replace(".", "");
                dayText[i] = i == 0 ? "Hoy" : Character.toUpperCase(day.charAt(0)) + day.substring(1);
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        drawerChanged |= aim(futbolFocus, !sheetOpen && zone == Z_FUTBOL ? 0 : -1, snap);
        drawerChanged |= aim(weatherFocus, !sheetOpen && zone == Z_WEATHER ? 0 : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(recentFocus, !sheetOpen && zone == Z_RECENT ? recentIndex : -1, snap);
        int aa = allAppsAt();
        if (aa >= 0 && shelfIndex == aa && !allArmed) shelfIndex = aa - 1;   // hidden: not a place to land on
        allReveal[0].target = aa >= 0 && zone == Z_SHELF && shelfIndex == aa && !sheetOpen ? 1 : 0;
        if (snap) allReveal[0].snap(allReveal[0].target);
        if (shelfIndex < shelf.size()) {   // the nearest turn of the wheel that has this app in the middle
            int n = shelf.size();
            dockScroll[0].target = shelfIndex + n * Math.round((dockScroll[0].target - shelfIndex) / n);
        }
        if (snap) dockScroll[0].snap(dockScroll[0].target);
        boolean wxNow = zone == Z_WXBTN;
        if (!wxNow) wxOpen = false;
        if (wxNow || wxWas) heroLayer.invalidate();
        wxWas = wxNow;
        boolean inBubble = !sheetOpen && zone == Z_WATCH;
        heroChanged |= aim(watchFocus, inBubble && watchRow == 0 ? watchIndex : -1, snap);
        heroChanged |= aim(actFocus, inBubble && watchRow == 1 ? actIndex : -1, snap);
        heroChanged |= aim(watchActive, inBubble ? 0 : -1, snap);
        heroChanged |= aim(bubbleShow, inBubble ? 0 : -1, snap);
        if (zone != Z_SHELF && zone != Z_WATCH) artShow[0].target = 0;   // the content background is the shelf's
        if (zone == Z_SHELF) heroChanged = true;   // the hint follows the focused app
        followWatch();
        statusChanged |= aim(playerFocus, !sheetOpen && zone == Z_PLAYER ? 0 : -1, snap);
        statusChanged |= aim(avatarFocus, !sheetOpen && zone == Z_AVATAR ? 0 : -1, snap);
        statusChanged |= aim(ctrlFocus, !sheetOpen && zone == Z_PLAYER_CTRL ? ctrlIndex : -1, snap);
        float show = !sheetOpen && !npCollapsed && (zone == Z_PLAYER || zone == Z_PLAYER_BAR || zone == Z_PLAYER_CTRL) ? 1 : 0;
        statusChanged |= aim(barFocus, !sheetOpen && zone == Z_PLAYER_BAR ? 0 : -1, snap);
        if (ctrlShow[0].target != show) {
            ctrlShow[0].target = show;
            statusChanged = true;
        }
        // The capsule drops into the whole player while the focus is on it, its bar or its buttons.
        boolean music = nowPlaying != null && nowPlaying.visible() && !npCollapsed;
        boolean open = !sheetOpen && music && (zone == Z_PLAYER || zone == Z_PLAYER_BAR || zone == Z_PLAYER_CTRL);
        boolean full = open;
        if (npOpen[0].target != (open ? 1 : 0) || npFull[0].target != (full ? 1 : 0)) {
            npOpen[0].target = open ? 1 : 0;
            npFull[0].target = full ? 1 : 0;
            statusChanged = heroChanged = true;
        }
        boolean inSleep = !sheetOpen && zone == Z_SLEEP;
        if (sleepOpen[0].target != (inSleep ? 1 : 0)) {
            sleepOpen[0].target = inSleep ? 1 : 0;
            heroChanged = true;
        }
        heroChanged |= aim(sleepFocus, inSleep ? (sleepRow == 0 ? 0 : 1 + sleepIdx) : -1, snap);
        boolean onSearch = !sheetOpen && zone == Z_TOP && topIndex == 3;
        if (!onSearch) inMic = false;
        statusChanged |= aim(searchPill, onSearch ? 0 : -1, snap);
        statusChanged |= aim(micFocus, onSearch && inMic ? 0 : -1, snap);
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        final List<HomeApps.Item> list = others;
        final Context ctx = getContext().getApplicationContext();
        final boolean choosing = replacing != null;
        // The installed apps are read again on their own thread (not behind the backgrounds and logos on LOADER):
        // an app just installed or turned on shows, and the grid never waits on other work to fill.
        new Thread(() -> {
            List<HomeApps.Item> fresh = list;
            if (!choosing) {
                try {
                    fresh = HomeApps.others(ctx);
                    AppLog.w("apps: " + fresh.size() + " in All Apps");
                } catch (RuntimeException e) {
                    AppLog.w("apps: couldn't list them: " + e);
                }
            }
            HomeApps.loadTiles(ctx, fresh);
            final List<HomeApps.Item> ready = fresh;
            handler.post(() -> {
                if (sheetOpen && ready != list && replacing == null && !gridMove && !gridMenu) setOthers(ready);
                gridDirty = true;
                wake();
            });
        }, "all-apps").start();
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        if (replacing != null) {   // left without choosing: All Apps gets back its whole list
            replacing = null;
            reload();
        }
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        // Once it has slid away, its cards go (they're loaded again next time it opens).
        handler.removeCallbacks(dropGrid);
        handler.postDelayed(dropGrid, 1500);
    }

    private final Runnable dropGrid = () -> {
        if (sheetOpen) return;
        final List<HomeApps.Item> list = others;
        final Context ctx = getContext().getApplicationContext();
        crispTiles.evictAll();
        imageShaders.clear();
        LOADER.execute(() -> HomeApps.dropTiles(ctx, list));
        tilesDirty = true;   // the shelf's copies are made again (from tiles still kept)
        wake();
    };

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private final RectF sheetGlass = new RectF();

    private void drawSheet(Canvas c) {
        // Glass edition: the whole sheet is a pane of the same frosted glass (the background blurred behind it), a
        // little darker so the app names read; it used to be a flat black veil.
        sheetGlass.set(-40 * k, -40 * k, w + 40 * k, h + 40 * k);   // its lit rim (about 1 % of its size) off screen
        scene.glass(c, sheetGlass, 0, SMOKE, 0.4f, 0, 0, 0.02f, 1);
        c.drawColor(0x4D000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(replacing != null ? "Elegí la app para el estante" : Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            // One copy at the focused size (see the shelf): no change of resolution as the focus moves.
            float lift = gridMove && i == gridIndex ? 0.16f : 0.08f;   // being moved: lifted a little higher
            drawCard(c, cardImage(others.get(i), GRID_W * k * 1.08f, GRID_H * k * 1.08f), gridCell, GRID_RADIUS * k, f, lift);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
        if (gridMenu && sheetOpen) drawAppMenu(c, focusedCell, gridOptions);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) {
            roundImage(c, image, cell, r);   // one copy at the focused size: no change of resolution with focus
        } else {
            fill.setShader(null);
            fill.setColor(0x26FFFFFF);   // still loading: a faint card where it will be
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    /** Any notice on screen or waiting goes (another screen takes its place). */
    private void clearToasts() {
        toasts.clear();
        if (toast != null) {
            toast = null;
            toastLayer.invalidate();
        }
    }

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k;
        float tw = toast.getWidth(), th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        c.translate(tmp.left + padX, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            try {
                final List<HomeApps.Item> s = HomeApps.shelf(c, true);
                final List<HomeApps.Item> st = statusList(c);
                final List<HomeApps.Item> d = drawerList(c), ins = HomeApps.allInputs(c);
                final List<HomeApps.Item> o = HomeApps.others(c);
                AppLog.w("apps: " + s.size() + " on the shelf, " + o.size() + " in All Apps");
                handler.post(() -> {
                    setShelf(s);
                    setStatusItems(st);
                    setDrawerLists(d, ins);
                    if (!sheetOpen) setOthers(o);
                });
            } catch (RuntimeException e) {
                AppLog.w("apps: couldn't list them: " + e);
            }
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean wasIdle = idleMode();
        lastKeyAt = SystemClock.uptimeMillis();
        if (wasIdle) {   // back from idle: everything moves at full speed again
            restartScene();
            restartTick();
        }
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (gridMove) return gridMoveKey(e, down);
        if (gridMenu) return gridMenuKey(e, down);
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canReorder() || (sheetOpen && gridIndex < others.size());
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        holdAction();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (zone == Z_WXBTN) {   // the weather panel closes; then back to the apps
            if (wxOpen) wxOpen = false;
            else zone = Z_SHELF;
            retarget(false);
            click.move();
            return;
        }
        if (!sheetOpen && (zone == Z_DRAWER || zone == Z_FUTBOL || zone == Z_WEATHER || zone == Z_FAVS) && drawerPanel != 0) {
            if (drawerPanel == 1) unfoldInputs(false);
            drawerPanel = 0;
            zone = Z_DRAWER;
            drawerDirty = true;
            retarget(false);
            click.move();
            return;
        }
        if (cardEngaged && (zone == Z_FUTBOL || zone == Z_WEATHER)) {
            cardEngaged = false;   // out of the card's pages, still on the card
            drawerDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS || zone == Z_FUTBOL || zone == Z_WEATHER) {
            closeDrawer();
            retarget(false);
        } else if (zone == Z_IDLE) {
            return;
        } else if (zone == Z_SLEEP) {
            zone = Z_TOP;   // the sleep panel folds back into its button
            topIndex = sleepButton();
            retarget(false);
        } else if (zone == Z_PLAYER_CTRL || zone == Z_PLAYER_BAR) {
            zone = Z_PLAYER;   // from its bar or its buttons back to the player itself
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            idleFrom = Z_SHELF;   // Back on the shelf: nothing focused
            zone = Z_IDLE;
            retarget(false);
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = iconDock() && shelf.size() > 1 ? (shelfIndex + dx + shelf.size()) % shelf.size()
                            : Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    allArmed = next == allAppsAt();   // only → from the last app gets there
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case Z_FUTBOL: {
                if (dy < 0) {
                    zone = Z_DRAWER;
                    cardEngaged = false;
                } else if (dx < 0 && !cardEngaged && Prefs.weather()) {
                    zone = Z_WEATHER;   // ← : the weather card (OK first to turn this one's pages)
                    drawerDirty = true;
                } else if (dx != 0 && cardEngaged) {
                    int pages = Math.max(1, cardPages.size()), next = Math.max(0, Math.min(pages - 1, cardPage + dx));
                    if (next == cardPage) return;
                    cardPage = next;
                    cardManualAt = System.currentTimeMillis();
                    drawerDirty = true;
                } else {
                    return;
                }
                break;
            }
            case Z_WEATHER: {
                if (dy < 0) {
                    zone = Z_DRAWER;
                    cardEngaged = false;
                } else if (dx > 0 && !cardEngaged) {
                    if (!Prefs.futbol()) return;
                    zone = Z_FUTBOL;   // → : the football card (OK first to turn this one's pages)
                } else if (dx != 0 && cardEngaged) {
                    int next = Math.max(0, Math.min(1, weatherPage + dx));
                    if (next == weatherPage) return;
                    weatherPage = next;
                } else {
                    return;
                }
                drawerDirty = true;
                break;
            }
            case Z_FAVS:
                if (favRow == 1 && dx != 0) {
                    int next = Math.max(0, Math.min(favs.size() - 1, favIndex + dx));
                    if (next == favIndex) return;
                    favIndex = next;
                } else if (dy < 0) {
                    if (favRow == 1) favRow = 0;   // up to the switch
                    else {
                        zone = Z_DRAWER;   // back to the heart's second button
                        heartSub = true;
                    }
                } else if (dy > 0 && favRow == 0 && !favs.isEmpty()) {
                    favRow = 1;
                } else {
                    return;
                }
                drawerDirty = true;
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                boolean onHeartBtn = drawerIndex < drawerItems.size() && drawerItems.get(drawerIndex).kind == HEART_KIND;
                if (dx != 0) {
                    heartSub = false;
                    if (drawerPanel == 2 || drawerPanel == 3 || drawerPanel == 5) drawerPanel = 0;   // its card goes with it
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0 && heartSub) {
                    heartSub = false;   // back up to the heart
                    drawerDirty = true;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (onHeartBtn && !heartSub) {
                    heartSub = true;   // down to "Mis fondos", under the heart
                    drawerDirty = true;
                } else if (onHeartBtn && drawerPanel == 5 && !talkBack()) {
                    zone = Z_FAVS;   // down into the card: the switch first
                    favRow = 0;
                    heartSub = false;
                    drawerDirty = true;
                } else if (false) {   // the team's card has nothing to move to
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (zone == Z_FAVS && favRow == 0) {   // the switch
            click.open();
            if (!favOn && favs.isEmpty()) {
                showToast("Guardá algún fondo con el corazón primero");
                return;
            }
            setFavoritesOn(!favOn);
            showToast(favOn ? "Usando tus fondos favoritos" : "Volviendo a los fondos de antes");
            return;
        }
        if (zone == Z_FAVS) {   // a saved background: on Home now (the switch turns on with it)
            if (favIndex >= favs.size()) return;
            click.open();
            if (!favOn) {
                Context bc = getContext().getApplicationContext();
                Prefs.setHomeBackgroundBefore(bc, Prefs.homeBackground(bc));
                favOn = true;
            }
            final String url = favs.get(favIndex).url;
            final Context fc = getContext().getApplicationContext();
            LOADER.execute(() -> {
                Gallery.showFavorite(fc, url);
                handler.post(() -> {
                    updateWallpaper();
                    heartOn = true;
                    drawerDirty = true;
                    showToast("Fondo cambiado");
                });
            });
            return;
        }
        if (zone == Z_WXBTN) {   // W1: the weather's panel opens (or closes)
            wxOpen = !wxOpen;
            click.open();
            retarget(false);
            return;
        }
        if (zone == Z_IDLE) {   // nothing focused: OK brings the focus back
            zone = idleFrom;
            retarget(false);
            return;
        }
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (!sheetOpen && (zone == Z_FUTBOL || zone == Z_WEATHER)) {
            cardEngaged = !cardEngaged;
            drawerDirty = true;
            click.open();
            return;
        }
        if (sheetOpen && replacing != null) {
            if (gridIndex < others.size()) finishReplace(others.get(gridIndex).pkg);
            click.open();
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            if (drawerPanel == 0 && it.kind == HomeApps.INPUTS) {   // Entradas unfolds in place
                click.open();
                unfoldInputs(true);
                retarget(false);
                return;
            }
            if (it.kind == NEXTBG_KIND) {   // the next background, right now
                click.open();
                Prefs.skipHomeBackground(getContext());
                updateWallpaper();
                handler.postDelayed(() -> refreshFavs(false), 1500);
                return;
            }
            if (it.kind == HEART_KIND && heartSub) {   // "Mis fondos" opens (or closes) under the buttons
                click.open();
                drawerPanel = drawerPanel == 5 ? 0 : 5;
                if (drawerPanel == 5) refreshFavs(true);
                drawerDirty = true;
                return;
            }
            if (it.kind == HEART_KIND) {   // saves (or un-saves) the background on screen
                click.open();
                final Context hc = getContext().getApplicationContext();
                LOADER.execute(() -> {
                    Gallery.Item cur = wallpaperWanted ? Gallery.current(hc) : null;
                    boolean nowOn = cur != null && Gallery.toggleFavorite(hc, cur);
                    handler.post(() -> {
                        showToast(cur == null ? "No hay un fondo para guardar" : nowOn ? "Fondo guardado en Mis fondos" : "Fondo quitado de Mis fondos");
                        refreshFavs(drawerPanel == 5);
                    });
                });
                return;
            }
            if (it.kind == FAVS_KIND) {   // the saved ones open (or close) under the buttons
                click.open();
                drawerPanel = drawerPanel == 5 ? 0 : 5;
                if (drawerPanel == 5) refreshFavs(true);
                drawerDirty = true;
                return;
            }
            if (it.kind == WX_KIND) {   // the weather's card opens (or closes) under the buttons
                click.open();
                drawerPanel = drawerPanel == 3 ? 0 : 3;
                weatherPage = 0;
                drawerDirty = true;
                return;
            }
            if (it.kind == TEAM_KIND) {   // the team's card opens (or closes) under the buttons
                click.open();
                drawerPanel = drawerPanel == 2 ? 0 : 2;
                drawerDirty = true;
                return;
            }
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
            if (topButton(topIndex == 3 && inMic ? "voice" : it.key)) return;
        } else if (zone == Z_AVATAR) {
            click.open();
            try {
                getContext().startActivity(RemoteKeys.accounts(getContext()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                getContext().startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        } else if (zone == Z_PLAYER) {
            // Glass edition: OK opens the whole player, on its play/pause (as moving down to its buttons does).
            npCollapsed = false;
            npExpand[0].target = 1;
            zone = Z_PLAYER_CTRL;
            ctrlIndex = indexOfKind(C_PLAY);
            retarget(false);
            statusDirty = heroDirty = true;
            click.open();
            return;
        } else if (zone == Z_SLEEP) {
            sleepOk();
            return;
        } else if (zone == Z_PLAYER_BAR) {
            if (nowPlaying != null) nowPlaying.toggle();   // OK on the bar: play or pause
            click.open();
            return;
        } else if (zone == Z_PLAYER_CTRL) {
            if (nowPlaying != null && ctrlIndex < ctrlKinds.length) {
                switch (ctrlKinds[ctrlIndex]) {
                    case C_SHUFFLE: nowPlaying.shuffle(); break;
                    case C_PREV: nowPlaying.previous(); break;
                    case C_PLAY: nowPlaying.toggle(); break;
                    case C_NEXT: nowPlaying.next(); break;
                    case C_STOP: nowPlaying.stopPlayback(); break;
                    default: nowPlaying.repeat(); break;
                }
            }
            click.open();
            return;
        } else if (zone == Z_WATCH && spotRow()) {
            click.open();
            android.content.Intent in;
            if (actIndex == 0) in = getContext().getPackageManager().getLeanbackLaunchIntentForPackage(SpotifyLists.PKG);
            else if (actIndex == 1) in = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("spotify:collection:playlists")).setPackage(SpotifyLists.PKG);
            // (plain spotify:collection plays Liked Songs on the TV app instead of showing the library)
            else in = SpotifyLists.open(actIndex - 2);
            try {
                if (in != null) getContext().startActivity(in.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("spotify row: " + e.getMessage());
            }
            return;
        } else if (zone == Z_WATCH && watchRow == 1 && panelSmallCount() > 0) {
            click.open();   // a playlist: straight into Spotify
            try {
                getContext().startActivity(SpotifyLists.open(Math.min(actIndex, SpotifyLists.IDS.length - 1)));
            } catch (RuntimeException e) {
                AppLog.w("spotify playlist: " + e.getMessage());
            }
            return;
        } else if (zone == Z_WATCH && watchRow == 1) {
            // The bubble's actions: open the app, move it on the shelf, its info.
            if (shelf.isEmpty() || shelfIndex >= shelf.size()) return;
            HomeApps.Item app = shelf.get(shelfIndex);
            if (actIndex == 1) {
                zone = Z_SHELF;
                retarget(false);
                startReorder();
                return;
            }
            if (actIndex == 2) {
                click.open();
                appAction("Información", app.pkg);
                return;
            }
            it = app;
            r = tileRects[shelfIndex];
        } else if (zone == Z_WATCH) {
            if (watch == null || watchIndex >= watch.items.size()) return;
            WatchNext.Entry e = watch.items.get(watchIndex);
            if (WatchNext.SPOTIFY.equals(e.pkg) && playSong(e)) return;
            String label = shelfLabel(e.pkg);
            it = e.uri != null ? HomeApps.app(e.pkg, label, e.uri) : HomeApps.app(e.pkg, label);
            r = watchRects[watchIndex];
        } else if (zone == Z_RECENT) {
            if (recentIndex >= recent.size()) return;
            it = recent.get(recentIndex);
            r = recentRects[recentIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        final HomeApps.Item item = it;
        final RectF from = new RectF(r);
        if (item.kind != HomeApps.QUICK_SETTINGS) {
            dimInHome();   // the panel leaves Home in view: nothing to hand over
            handler.removeCallbacks(sceneTick);   // the GPU and the CPU go to the app starting
            handler.removeCallbacks(npTick);
        }
        boolean opened = HomeApps.open(activity, item, this, Math.round(from.left), Math.round(from.top),
                Math.round(from.width()), Math.round(from.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast("← → mover · ↓ opciones · OK soltar · Atrás cancelar");
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!down) return true;
                if (appMenu) {
                    menuIndex = Math.max(0, Math.min(shelfOptions.length - 1, menuIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    tilesDirty = true;
                    click.move();
                } else {
                    shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && !appMenu) {
                    shelfOptions = optionsFor(shelf.get(shelfIndex).pkg, SHELF_ALL);
                    appMenu = true;   // ↓ while moving: the app's options
                    menuIndex = 0;
                    tilesDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && appMenu) {
                    appMenu = false;
                    tilesDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it
                // (or runs the chosen option).
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    String pkg = shelf.get(shelfIndex).pkg;
                    boolean menu = appMenu;
                    appMenu = false;
                    dropReorder();
                    if (menu) appAction(shelfOptions[menuIndex], pkg);
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (appMenu) {
                            appMenu = false;
                            tilesDirty = true;
                        } else {
                            cancelReorder(false);
                        }
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size() || shelf.get(j).kind == HomeApps.ALL_APPS) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) order.add(it.pkg);   // not the All Apps square
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        appMenu = false;
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and football (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.futbol()) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : futbolText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : futbolCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 football), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.futbol()) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The football card as TalkBack reads it: each game with its round and when. */
    private String futbolText() {
        StringBuilder s = new StringBuilder("Próximos partidos.");
        long now = System.currentTimeMillis();
        for (Futbol.Match m : data.upcoming) {
            s.append(' ').append(m.home.name).append(m.live() || m.done() ? " " + Futbol.score(m) + ", " : " contra ")
                    .append(m.away.name).append(", ").append(m.round).append(", ").append(Futbol.status(m, now)).append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        if (SHOW_AVATAR) {
            avatarRect.set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        for (int i = TOP_N - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        // "Seguir en…": three 16:9 cards in the empty right half, level with the date and the clock.
        float rw = RECENT_W * k, rh = rw * 9f / 16, rg = RECENT_GAP * k;
        float rx = CONTENT_R * k - RECENT_MAX * rw - (RECENT_MAX - 1) * rg;
        for (int i = 0; i < RECENT_MAX; i++) {
            recentRects[i].set(rx + i * (rw + rg), RECENT_TOP * k, rx + i * (rw + rg) + rw, RECENT_TOP * k + rh);
        }
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private Paint grain;

    /**
     * A fine grain (±1 level, random black and white specks at 2% at most) over the background: it breaks the steps of
     * the dark gradients and of the small blurred photo scaled up to the screen, which a big 4K panel makes visible as
     * bands. Too faint to see as noise at TV distance.
     */
    private void drawGrain(Canvas c) {
        if (grain == null) {
            int n = 128;
            int[] px = new int[n * n];
            java.util.Random r = new java.util.Random(20261022);
            for (int i = 0; i < px.length; i++) {
                int a = r.nextInt(6);
                px[i] = r.nextBoolean() ? (a << 24) | 0xFFFFFF : a << 24;
            }
            Bitmap tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            grain = new Paint();
            grain.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        c.drawRect(screen, grain);
    }

    private void drawLightSky(Canvas c) {
        if (sceneOn() && bgTo == null && bgFadeAt == 0) return;   // the scene's views cover the screen
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // The drawer's veil is its own layer above (updateVeil): the background is drawn as it is.
        android.graphics.ColorFilter v = null;
        if (bgTo == null || (fading && bgFrom == null)) {
            // Flat, not a gradient: a dark gradient has only ~17 grays to spread over 1080 lines, and a 65" 4K panel
            // shows the steps as bands even dithered. One tone can't band.
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFEDEDF0, 0xFFEDEDF0, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF000000, 0xFF000000, Shader.TileMode.CLAMP);   // OLED: black
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            fill.setDither(true);   // 17 grays over 1080 lines: undithered, a 65" panel shows them as bands
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
            fill.setDither(false);
            if (wallpaper != null) {
                imagePaint.setColorFilter(v);
                if (wallpaperBefore != null && wallpaperAlpha < 1) {
                    imagePaint.setAlpha(255);
                    c.drawBitmap(wallpaperBefore, null, screen, imagePaint);
                }
                imagePaint.setAlpha(Math.round(255 * wallpaperAlpha));
                c.drawBitmap(wallpaper, null, screen, imagePaint);
                imagePaint.setAlpha(255);
            }
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        if (bgTo != null || bgFrom != null) drawGrain(c);   // only a photo background has steps left to break up
        // Glass edition: resting on an app, its latest content washes over the background, blurred and darkened.
        float ae = Ui.clamp(artShow[0].value);
        if (ae > 0.01f && artBlur != null) {
            artPaint.setAlpha(Math.round(255 * ae));
            c.drawBitmap(artBlur, null, screen, artPaint);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.42f * ae));
            c.drawRect(screen, fill);
        }
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private TextPaint smallClock, smallTemp, smallDay;

    /**
     * While an app's shelf is up: a status line at the top left, level with the round buttons: the clock in the
     * home's letter and, beside it, the day over the weather.
     */
    private void drawSmallClock(Canvas c, float a) {
        if (smallClock == null || smallClock.getTextSize() != 64 * k) {
            smallClock = new TextPaint(Ui.numbers(Ui.stadium, 64 * k, 0xFFFFFFFF));
            smallTemp = new TextPaint(Ui.text(Ui.semibold, 21 * k, 0xE6FFFFFF));
            smallDay = new TextPaint(Ui.text(Ui.semibold, 18 * k, 0xB3FFFFFF));
            smallDay.setLetterSpacing(0.12f);
            for (Paint p : new Paint[] {smallClock, smallTemp, smallDay}) p.setShadowLayer(12 * k, 0, 2 * k, 0x80000000);
        }
        refreshGlassDate();
        RectF row = topRects[3];
        float mid = row.centerY() - (1 - a) * 12 * k, x = MARGIN * k;
        float base = mid + Ui.cap(smallClock) / 2;
        smallClock.setAlpha(Math.round(255 * a));
        c.drawText(clockText, x - Ui.leftBearing(smallClock, clockText), base, smallClock);
        float tx = x + smallClock.measureText(clockText) + 22 * k;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x59FFFFFF, a));
        c.drawRect(tx, mid - 26 * k, tx + 2 * k, mid + 26 * k, fill);
        tx += 20 * k;
        smallDay.setAlpha(Math.round(179 * a));
        String day = (gWeekday + (gDayMonth.isEmpty() ? "" : "  " + gDayMonth)).toUpperCase(Ui.locale());
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        c.drawText(day, tx, weather ? mid - 6 * k : mid + Ui.halfCap(smallDay), smallDay);
        if (weather) {
            refreshGlassWeather(wx);
            smallTemp.setAlpha(Math.round(230 * a));
            c.drawText(gTempText + "  " + gCondText, tx, mid + 24 * k, smallTemp);
        }
    }

    /** The weather under the clock, focused: a glass pill round it, a ring, and what OK does. */
    private void drawWxFocus(Canvas c) {
        tmp.set(52 * k, 488 * k, 440 * k, 594 * k);
        drawerGlass(c, tmp, tmp.height() / 2, 0.55f, 0x1A000000, 8 * k);
        focusRing.setStrokeWidth(4 * k);
        focusRing.setColor(0xF2FFFFFF);
        float o = 6 * k;
        c.drawRoundRect(tmp.left - o, tmp.top - o, tmp.right + o, tmp.bottom + o, tmp.height() / 2 + o, tmp.height() / 2 + o, focusRing);
        if (wxHint == null) {
            wxHint = new TextPaint(Ui.text(Ui.semibold, 26 * k, 0xE6FFFFFF));
            wxHint.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        }
        c.drawText("OK · ver el clima", tmp.right + 26 * k, tmp.centerY() + Ui.halfCap(wxHint), wxHint);
    }

    /** W1: the weather's panel where the clock was: now and the next hours on top, the next days under them. */
    private void drawWxPanel(Canvas c) {
        RectF r = new RectF(60 * k, 160 * k, 1060 * k, 760 * k);
        drawerGlass(c, r, 44 * k, 0.72f, 0x1A000000, 10 * k);
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        RectF top = new RectF(r.left, r.top, r.right, r.top + 300 * k), bottom = new RectF(r.left, r.bottom - 300 * k, r.right, r.bottom);
        drawWeather(c, top);
        drawWeek(c, bottom);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            // With an app's shelf up, the big clock, the weather and the games fade out and a small clock takes the
            // top left (option E).
            float up = Math.max(Ui.clamp(bubbleShow[0].value), wxOpen ? 1 : 0), keep = 1 - up;
            // Aligned with the bar of apps (Tarjetas): the block moves right to its first app's edge.
            float shift = iconDock() || platterRect.width() <= 0 ? 0 : Math.max(0, platterRect.left + SHELF_PAD * k - MARGIN * k);
            int moved = c.save();
            c.translate(shift, -CLOCK_LIFT * k);
            if (zone == Z_WXBTN && !wxOpen) drawWxFocus(c);
            if (keep > 0.99f) {
                drawClock(c);
                if (Prefs.futbol()) drawFutbolRow(c);
            } else if (keep > 0.01f) {
                int saved = c.saveLayerAlpha(0, 0, w, (L_PLATTER_TOP - 20) * k, Math.round(255 * keep));
                drawClock(c);
                if (Prefs.futbol()) drawFutbolRow(c);
                c.restoreToCount(saved);
            }
            c.restoreToCount(moved);
            if (up > 0.01f) drawSmallClock(c, up);
            if (wxOpen) {
                c.save();
                c.translate(shift, -CLOCK_LIFT * k);
                drawWxPanel(c);
                c.restore();
            }
            drawShelfHint(c);
            if (npWarmPending) {   // once, unseen: everything the player makes on its first open is made now
                npWarmPending = false;
                npWarmed = true;
                float v = npOpen[0].value, t = npOpen[0].target;
                npOpen[0].value = npOpen[0].target = 1;
                int sv = c.saveLayerAlpha(0, 0, w, h, 1);
                try {
                    drawNpCard(c);
                } catch (RuntimeException ex) {
                    AppLog.w("player warm-up: " + ex.getMessage());
                }
                c.restoreToCount(sv);
                npOpen[0].value = v;
                npOpen[0].target = t;
            }
            drawNpCard(c);
            drawSleepPanel(c);
            return;
        }
        String hello = Ui.tr("For You");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (Prefs.futbol()) drawFutbolRow(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    // Glass edition: the date's two halves ("MARTES", "17 DE NOVIEMBRE") and the weather's texts, made again only when
    // the date, the forecast, the settings or the language change.
    private String gDateKey = "", gWeekday = "", gDayMonth = "";
    private String gTempText = "", gCondText = "", gHiLoText = "";
    private String[] gHourText = new String[0], gHourTempText = new String[0], gHourSymbol = new String[0];

    private void refreshGlassDate() {
        if (dateText.equals(gDateKey)) return;
        gDateKey = dateText;
        java.util.Locale loc = Ui.locale();
        int comma = dateText.indexOf(',');
        gWeekday = (comma > 0 ? dateText.substring(0, comma) : dateText).trim().toUpperCase(loc);
        gDayMonth = comma > 0 ? dateText.substring(comma + 1).trim().toUpperCase(loc) : "";
    }

    private void refreshGlassWeather(HomeData.Weather wx) {
        if (data.weatherAt == weatherLineAt && Prefs.version == weatherLinePrefs && Ui.language.equals(weatherLineLanguage)) return;
        weatherLineAt = data.weatherAt;
        weatherLinePrefs = Prefs.version;
        weatherLineLanguage = Ui.language;
        gTempText = HomeData.degrees(wx.temp);
        gCondText = HomeData.condition(wx.code, wx.isDay);
        gHiLoText = Double.isNaN(wx.max) || Double.isNaN(wx.min) ? ""
                : "↑ " + HomeData.degrees(wx.max) + "     ↓ " + HomeData.degrees(wx.min);
        int n = Math.min(4, Math.min(wx.hours.size(), Math.min(wx.hourTemp.size(), wx.hourCode.size())));
        String[] at = new String[n], temp = new String[n], sym = new String[n];
        java.text.SimpleDateFormat hour = new java.text.SimpleDateFormat("H", Ui.locale());
        for (int i = 0; i < n; i++) {
            at[i] = hour.format(new java.util.Date(wx.hours.get(i))) + " h";
            temp[i] = HomeData.degrees(wx.hourTemp.get(i));
            boolean day = i < wx.hourDay.size() ? wx.hourDay.get(i) : wx.isDay;
            sym[i] = Symbols.weather(wx.hourCode.get(i), day);
        }
        gHourText = at;
        gHourTempText = temp;
        gHourSymbol = sym;
    }

    private void drawClock(Canvas c) {
        int ink = lClock.getColor(), dim = Ui.alpha(ink, 0.74f);
        float x = G_LEFT * k;
        // The date, centered over the clock: the weekday bold, a dot, the day and month lighter.
        refreshGlassDate();
        float cw = gClock.measureText(clockText);
        float ww = gDateBold.measureText(gWeekday), gap = 18 * k;
        float dw = ww + (gDayMonth.isEmpty() ? 0 : 2 * gap + gDate.measureText(gDayMonth));
        float dx = x + Math.max(0, (cw - dw) / 2), base = G_DATE_BASE * k;
        gDateBold.setColor(ink);
        c.drawText(gWeekday, dx, base, gDateBold);
        if (!gDayMonth.isEmpty()) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(ink, 0.6f));
            c.drawCircle(dx + ww + gap, base - Ui.halfCap(gDate), 4.5f * k, fill);
            gDate.setColor(Ui.alpha(ink, 0.85f));
            c.drawText(gDayMonth, dx + ww + 2 * gap, base, gDate);
        }
        gClock.setColor(ink);
        c.drawText(clockText, x - Ui.leftBearing(gClock, clockText), G_CLOCK_BASE * k, gClock);
        // Resting on an app, what you last saw or heard there takes the weather's place.
        float ae = Ui.clamp(artShow[0].value), wa = 1 - ae;
        // Up in the bubble, it shows the same: the title under the clock steps aside (the weather stays hidden).
        float te = ae * (1 - Ui.clamp(bubbleShow[0].value));
        if (te > 0.01f) drawArtText(c, te);
        HomeData.Weather wx = data.weather;
        if (wa <= 0.01f || !Prefs.weather() || Double.isNaN(wx.temp)) return;
        ink = Ui.alpha(ink, wa);
        dim = Ui.alpha(dim, wa);
        refreshGlassWeather(wx);
        // Now: the condition's symbol, the temperature big, and beside it the condition over the high and the low.
        base = G_TEMP_BASE * k;
        float size = 60 * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.cap(gTemp) / 2, size, ink);
        float tx = x + sw + 18 * k;
        gTemp.setColor(ink);
        c.drawText(gTempText, tx, base, gTemp);
        float lx = tx + gTemp.measureText(gTempText) + 24 * k;
        gCond.setColor(ink);
        c.drawText(gCondText, lx, base - Ui.cap(gTemp) + Ui.cap(gCond), gCond);
        if (!gHiLoText.isEmpty()) {
            gCondDim.setColor(dim);
            c.drawText(gHiLoText, lx, base, gCondDim);
        }
        // The next hours, each after a thin line: the hour, its symbol and its temperature.
        float hb = G_HOUR_BASE * k, col = 156 * k, isz = 26 * k;
        for (int i = 0; i < gHourText.length; i++) {
            float hx = x + i * col;
            fill.setShader(null);
            fill.setColor(Ui.alpha(ink, 0.25f));   // ink already carries the fade
            c.drawRect(hx, hb - 28 * k, hx + 2 * k, hb + 6 * k, fill);
            float px = hx + 14 * k;
            gHour.setColor(dim);
            c.drawText(gHourText[i], px, hb, gHour);
            px += gHour.measureText(gHourText[i]) + 8 * k;
            float hs = Symbols.width(getContext(), gHourSymbol[i], isz);
            Symbols.draw(getContext(), c, gHourSymbol[i], px + hs / 2, hb - Ui.halfCap(gHourTemp), isz, ink);
            px += hs + 6 * k;
            gHourTemp.setColor(ink);
            c.drawText(gHourTempText[i], px, hb, gHourTemp);
        }
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    // MARK: - "Seguir en…" (recent apps, right half, when there are no TMDB picks)

    private long recentMinute = -1;
    private Paint recentName, recentWhen;
    private String[] recentAgo = new String[0];

    /** Off: apps can't share what was being watched, and a row that only reopens apps added nothing. */
    private static final boolean RECENT_ROW = false;

    private boolean recentShown() {
        return RECENT_ROW && !picksOn() && !recent.isEmpty();
    }

    private void loadRecent() {
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            List<HomeApps.Item> list = HomeApps.recent(ctx, RECENT_MAX);
            handler.post(() -> {
                recent = list;
                recentIndex = Math.min(recentIndex, Math.max(0, list.size() - 1));
                recentMinute = -1;
                if (zone == Z_RECENT && list.isEmpty()) zone = Z_SHELF;
                heroDirty = true;
                retarget(true);
                wake();
            });
        }, "recent").start();
    }

    /** "hace 5 min", "hace 2 h", "ayer", "hace 3 días": made again once a minute. */
    private void refreshRecentAgo() {
        long minute = System.currentTimeMillis() / 60_000;
        if (minute == recentMinute && recentAgo.length == recent.size()) return;
        recentMinute = minute;
        String[] out = new String[recent.size()];
        for (int i = 0; i < out.length; i++) {
            long m = Math.max(0, (System.currentTimeMillis() - recent.get(i).lastUsed) / 60_000);
            out[i] = m < 1 ? "recién" : m < 60 ? "hace " + m + " min" : m < 24 * 60 ? "hace " + (m / 60) + " h"
                    : m < 48 * 60 ? "ayer" : "hace " + (m / (24 * 60)) + " días";
        }
        recentAgo = out;
    }

    private void drawRecent(Canvas c) {
        if (!recentShown()) return;
        refreshRecentAgo();
        String title = "Seguir en…";
        c.drawText(title, recentRects[0].left - Ui.leftBearing(lDate, title), L_DATE_BASE * k, lDate);
        for (int i = 0; i < recent.size() && i < RECENT_MAX; i++) {
            float f = recentFocus[i].value;
            RectF r = recentRects[i];
            drawCard(c, recent.get(i).tile, r, 14 * k, f, 0.08f);
            float lift = 5 * k * Ui.clamp(f);
            if (recentName == null || recentName.getTextSize() != 22 * k) {
                recentName = Ui.text(Ui.medium, 22 * k, L_INK);
                recentWhen = Ui.text(Ui.regular, 18 * k, L_DIM);
            }
            recentName.setColor(tone(L_INK, 0xF2FFFFFF));
            recentWhen.setColor(tone(L_DIM, 0x99FFFFFF));
            c.drawText(recent.get(i).label, r.left, r.bottom + 32 * k + lift, recentName);
            c.drawText(recentAgo[i], r.left, r.bottom + 56 * k + lift, recentWhen);
        }
    }

    // MARK: - "Continuar viendo" (centre right, for the focused app of the shelf)

    private static final long WATCH_DEBOUNCE_MS = 220;
    // Glass edition: a bubble that rises from the focused app when you press ↑ (passing over the apps only shows a hint,
    // and resting on one turns the background into what you last saw or heard there). Up to three cards (the latest
    // three to resume) and a row of actions: open the app, move it, its info.
    private static final float WATCH_PAD = 22, WATCH_HEADER = 36, WATCH_TOP = 50;
    private static final float WATCH_COL = 200, WATCH_COL_GAP = 16, WATCH_THUMB_H = 92;
    /** Below the cards: their text, then the actions row; the arrow under the bubble points at the app. */
    private static final float WATCH_BELOW = 124, WATCH_ARROW = 16;
    private final RectF[] actRects = {new RectF(), new RectF(), new RectF()};
    private final String[] actText = new String[3];
    private final Spring[] actFocus = {new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0),
            new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0)};
    /** 0 = the cards, 1 = the actions row. */
    private int watchRow, actIndex;
    private float watchArrowX;
    private TextPaint actPaint;
    private final Spring[] bubbleShow = {new Spring(0.3f, 0.8f, 0)};
    /** The bubble's own tint over the glass: dark and even, so the cards read the same on any picture. */
    private static final int BUBBLE_FILL = 0xB8202024;

    // The hint over the focused app ("↑ Más de Spotify") and, after a second's rest on it, the background turning into
    // the latest thing seen or heard there, with its title under the clock (where the weather was).
    private final Spring[] hintShow = {new Spring(0.3f, 0.85f, 0)};
    private final Spring[] artShow = {new Spring(0.2f, 0.95f, 0)};
    private static final long ART_REST_MS = 1000;
    /** Off: the person preferred the home's own picture to stay (the hint and the bubble remain). */
    private static final boolean CONTENT_BACKGROUND = false;
    private final Runnable artRest = this::restOnApp;
    private int artTries;
    private Bitmap artBlur;
    private String artKey = "", artLabel = "", artTitle = "", artSub = "", hintText = "", hintPkg = "";
    private float artProgress;
    private int artColor = 0xFFFFFFFF;
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private TextPaint artLabelP, artTitleP, artSubP, hintP;

    /** A second on the same app: its latest content takes the background (tried again shortly while it loads). */
    private void restOnApp() {
        if (!running || zone != Z_SHELF || watchPkg == null) return;
        WatchNext.Content c = watch;
        boolean ready = c != null && !watchSwap && watchPkg.equals(c.pkg) && !c.items.isEmpty();
        WatchNext.Entry e = ready ? c.items.get(0) : null;
        if (e == null || (e.bitmap == null && artTries < 4)) {
            if (++artTries < 8) handler.postDelayed(artRest, 300);
            return;
        }
        String key = c.pkg + "|" + e.title;
        if (!key.equals(artKey)) {
            artKey = key;
            artBlur = blurOf(e.bitmap);
            String h = c.header == null ? "" : c.header, app = shelfLabel(c.pkg).toUpperCase(Ui.locale());
            artLabel = h.startsWith("ESCUCHADO") ? "SEGUÍ ESCUCHANDO EN " + app
                    : h.startsWith("CONTINUAR") || h.startsWith("SEGUIR") || h.startsWith("VISTOS") ? "SEGUÍ VIENDO EN " + app
                    : h;
            if (artTitleP == null) {
                artLabelP = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xD9FFFFFF));
                artLabelP.setLetterSpacing(0.12f);
                artTitleP = new TextPaint(Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF));
                artSubP = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xD9FFFFFF));
                for (Paint p : new Paint[] {artLabelP, artTitleP, artSubP}) p.setShadowLayer(12 * k, 0, 2 * k, 0x80000000);
            }
            float room = 640 * k;
            artTitle = TextUtils.ellipsize(e.title, artTitleP, room, TextUtils.TruncateAt.END).toString();
            String sub = e.sub == null ? "" : e.sub;
            artSub = TextUtils.ellipsize(sub + (sub.isEmpty() ? "" : "  ·  ") + "↑ para elegir", artSubP, room,
                    TextUtils.TruncateAt.END).toString();
            artProgress = e.duration > 0 && e.position > 0 ? Ui.clamp(e.position / (float) e.duration) : 0;
            int brand = HomeApps.brandColor(c.pkg);
            artColor = brand != 0 ? brand : 0xFFFFFFFF;
        }
        artShow[0].target = 1;
        wake();
    }

    private static final int BLUR_W = 256, BLUR_H = 144, BLUR_R = 7;

    /**
     * The picture, centre-cropped to 16:9 and blurred for the full-screen background: scaled to 256×144 and given three
     * box-blur passes each way (close to a Gaussian, so no blocks or steps), then drawn filtered and dithered.
     */
    static Bitmap blurOf(Bitmap b) {
        if (b == null || b.getWidth() < 2 || b.getHeight() < 2) return null;
        try {
            Bitmap src = b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
            int w = src.getWidth(), h = src.getHeight(), cw = w, ch = Math.round(w * 9f / 16);
            if (ch > h) {
                ch = h;
                cw = Math.round(h * 16f / 9);
            }
            Bitmap crop = Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch);
            Bitmap small = Bitmap.createScaledBitmap(crop, BLUR_W, BLUR_H, true);
            int[] px = new int[BLUR_W * BLUR_H], tmp = new int[px.length];
            small.getPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(px, tmp, BLUR_W, BLUR_H, BLUR_R, true);
                boxBlur(tmp, px, BLUR_W, BLUR_H, BLUR_R, false);
            }
            Bitmap out = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** One box-blur pass (a running sum, edges clamped), along the rows or the columns, from `in` to `out`. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w, len = rows ? w : h, step = rows ? 1 : w, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = rows ? l * w : l;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(len - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[base + Math.min(len - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Over the focused app: "↑ Más de <app>", a small glass pill with a dot of the app's colour. */
    private void drawShelfHint(Canvas c) {
        float hv = Ui.clamp(hintShow[0].value);
        WatchNext.Content wc = watch;
        if (hv <= 0.01f || wc == null || shelfIndex >= tileRects.length || shelfIndex >= shelf.size()) return;
        if (hintP == null) hintP = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xF2FFFFFF));
        if (!wc.pkg.equals(hintPkg)) {
            hintPkg = wc.pkg;
            hintText = "↑   Más de " + shelfLabel(wc.pkg);
        }
        float eased = hv * hv * (3 - 2 * hv), dot = 10 * k;
        float pw = hintP.measureText(hintText) + 54 * k, ph = 42 * k;
        float cx = Math.max(MARGIN * k + pw / 2, Math.min(CONTENT_R * k - pw / 2, tileRects[shelfIndex].centerX()));
        float bottom = (L_PLATTER_TOP - 14) * k + (1 - eased) * 10 * k;
        tmp.set(cx - pw / 2, bottom - ph, cx + pw / 2, bottom);
        scene.glass(c, tmp, ph / 2, 0xFFFFFFFF, 0.6f, 0, 0, 0.02f, 1, eased);
        int brand = HomeApps.brandColor(wc.pkg);
        fill.setShader(null);
        fill.setColor(Ui.alpha(brand != 0 ? brand : 0xFFFFFFFF, eased));
        c.drawCircle(tmp.left + 20 * k, tmp.centerY(), dot / 2, fill);
        hintP.setAlpha(Math.round(242 * eased));
        c.drawText(hintText, tmp.left + 34 * k, tmp.centerY() + Ui.halfCap(hintP), hintP);
    }

    /** Under the clock, while the background shows it: what you last saw or heard there, and how far you got. */
    private void drawArtText(Canvas c, float ae) {
        if (artTitleP == null || artTitle.isEmpty()) return;
        float x = G_LEFT * k, base = (G_TEMP_BASE - 34) * k;
        artLabelP.setAlpha(Math.round(217 * ae));
        c.drawText(artLabel, x, base, artLabelP);
        artTitleP.setAlpha(Math.round(255 * ae));
        c.drawText(artTitle, x - Ui.leftBearing(artTitleP, artTitle), base + 58 * k, artTitleP);
        artSubP.setAlpha(Math.round(217 * ae));
        c.drawText(artSub, x, base + 98 * k, artSubP);
        if (artProgress > 0) {   // how far you got, in the app's colour
            float bw = 320 * k, bh = 6 * k, by = base + 124 * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x59FFFFFF, ae));
            tmp.set(x, by, x + bw, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            fill.setColor(Ui.alpha(artColor, ae));
            tmp.right = x + Math.max(bh, bw * artProgress);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
    }
    private WatchNext.Content watch, watchWaiting;
    private boolean watchSwap;
    /** The app it follows: the focused shelf app (kept while the focus is up in the widget or the top row). */
    private String watchPkg;
    private final Spring[] watchShow = {new Spring(0.3f, 0.92f, 0)};
    private final Spring[] watchFocus = {new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0)};
    /** Dimmed a little while the focus is elsewhere, so it never competes with the shelf. */
    private final Spring[] watchActive = {new Spring(0.3f, 0.9f, 0)};
    private final RectF watchCard = new RectF();
    private final RectF[] watchRects = {new RectF(), new RectF(), new RectF()};
    private int watchIndex;
    private final String[] watchTitles = new String[3], watchSubs = new String[3];
    /** How much of each card's title was seen (0: no bar), in the app's colour. */
    private final float[] watchProgress = new float[3];
    private int watchColor;
    /** How far the cards sit from the island's left padding (centred when fewer than three), and the heading's x. */
    private float watchShift, watchHeadX, watchGap;
    private TextPaint watchHead, watchTitle, watchSub;
    private Paint watchRim;
    private boolean watchDirty;
    private static final long WATCH_START_MS = 1200;
    private final Runnable watchStart = () -> {
        if (!running) return;
        MediaHistory.get(getContext()).start();
        MediaHistory.get(getContext()).setListener(this::refreshWatch);
        if (Prefs.watchNext()) WatchNext.get(getContext()).start(this::refreshWatch);
        else pickWatch();   // turned off in Home Settings: it goes
    };
    private final Runnable watchDebounce = this::pickWatch;
    private final Runnable watchImageReady = () -> {
        if (watch != null) loadWatchImages(watch);
        watchDirty = true;
        wake();
    };

    private boolean watchShown() {
        return !picksOn() && watch != null && !watchSwap && watchShow[0].target > 0.5f;
    }

    /** Called on every focus change: which app the widget should follow now, picked 220 ms later (no flicker). */
    private void followWatch() {
        String pkg;
        if (sheetOpen || zone == Z_DRAWER || zone == Z_FUTBOL || zone == Z_WEATHER || zone == Z_IDLE) {
            pkg = null;
        } else if (zone == Z_SHELF) {
            HomeApps.Item it = shelfIndex < shelf.size() ? shelf.get(shelfIndex) : null;
            pkg = it != null && it.kind == HomeApps.APP ? it.pkg : null;
        } else {
            return;   // up in the widget or the top row: it stays as it is
        }
        if (pkg == null ? watchPkg == null : pkg.equals(watchPkg)) return;
        watchPkg = pkg;
        // Another app: the content background goes, and comes back after a second's rest on this one.
        handler.removeCallbacks(artRest);
        artShow[0].target = 0;
        artTries = 0;
        if (CONTENT_BACKGROUND && pkg != null) handler.postDelayed(artRest, ART_REST_MS);
        handler.removeCallbacks(watchDebounce);
        if (pkg == null) pickWatch();
        else handler.postDelayed(watchDebounce, WATCH_DEBOUNCE_MS);
    }

    /** New data (the TV Provider changed, something was saved to the history, a picture arrived). */
    private void refreshWatch() {
        if (watchPkg != null) {
            handler.removeCallbacks(watchDebounce);
            pickWatch();
        }
        if (!Prefs.watchNext()) return;
        // Pictures fetched ahead for the other shelf apps: moving to one shows it at once.
        WatchNext wn = WatchNext.get(getContext());
        for (HomeApps.Item it : shelf) {
            if (it.kind != HomeApps.APP || it.pkg == null || it.pkg.equals(watchPkg)) continue;
            WatchNext.Content c = wn.content(it.pkg, it.label);
            if (c != null) loadWatchImages(c);
        }
    }

    private void pickWatch() {
        WatchNext.Content next = picksOn() || watchPkg == null || !Prefs.watchNext() ? null
                : WatchNext.get(getContext()).content(watchPkg, shelfLabel(watchPkg));
        if (next != null) loadWatchImages(next);
        WatchNext.Content now = watchSwap ? watchWaiting : watch;
        if (next != null && now != null && next.key().equals(now.key())) {   // the same: only its pictures may be new
            if (watchSwap) watchWaiting = next;
            else {
                watch = next;
                layoutWatch();
            }
            return;
        }
        if (next == null && now == null) return;
        if (watch == null || watchShow[0].value < 0.03f) {
            showWatch(next);
        } else {
            watchWaiting = next;   // the one on screen fades out first
            watchSwap = true;
            watchShow[0].target = 0;
        }
        watchDirty = true;
        wake();
    }

    private void showWatch(WatchNext.Content c) {
        watchSwap = false;
        watchWaiting = null;
        watch = c;
        watchIndex = 0;
        if (c == null) {
            watchShow[0].target = 0;
            if (zone == Z_WATCH) {
                zone = Z_SHELF;
                retarget(false);
            }
        } else {
            layoutWatch();
            watchShow[0].target = 1;
        }
        watchDirty = true;
        wake();
    }

    /** A song from the history: Spotify plays it (search and play); if it can't, its search opens on that song. */
    private boolean playSong(WatchNext.Entry e) {
        String q = (e.title + " " + e.sub).trim();
        if (q.isEmpty()) return false;
        Context ctx = getContext();
        android.content.Intent play = e.uri != null && e.uri.startsWith("spotify:track:")
                ? new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(e.uri)).setPackage(e.pkg)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                : new android.content.Intent("none");
        android.content.Intent search = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("spotify:search:" + android.net.Uri.encode(q))).setPackage(e.pkg)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        for (android.content.Intent in : new android.content.Intent[] {play, search}) {
            try {
                if (in.resolveActivity(ctx.getPackageManager()) == null) continue;
                click.open();
                ctx.startActivity(in);
                AppLog.w("song: " + in.getAction() + " " + q);
                return true;
            } catch (Exception ex) {
                AppLog.w("song " + in.getAction() + ": " + ex.getMessage());
            }
        }
        return false;
    }

    private String shelfLabel(String pkg) {
        for (HomeApps.Item it : shelf) if (pkg.equals(it.pkg)) return it.label;
        return pkg;
    }

    private void loadWatchImages(WatchNext.Content c) {
        int[] size = watchImageSize(c);
        WatchNext wn = WatchNext.get(getContext());
        for (WatchNext.Entry e : c.items) {
            if (e.bitmap == null) e.bitmap = wn.image(e, size[0], size[1], watchImageReady);
        }
    }

    // Glass edition, option E: up from an app turns the top of the screen into that app's own shelf, like Apple TV's
    // Top Shelf: a heading and up to three cards in a row, no box around them; the focused card opens wide (16:9) and
    // the others narrow, as it moves; the clock shrinks to the top left.
    private static final float TS_LEFT = 80, TS_HEAD = 300, TS_TOP = 330, TS_H = 320, TS_BIG = 570, TS_SMALL = 300,
            TS_GAP = 30;
    private final String[] watchTitlesBig = new String[3];
    private final android.graphics.Matrix cropMatrix = new android.graphics.Matrix();

    private int[] watchImageSize(WatchNext.Content c) {
        int t = Math.round(TS_H * k * 1.06f);
        return c.variant == WatchNext.ROW_WIDE ? new int[] {Math.round(TS_BIG * k * 1.06f), t} : new int[] {t, t};
    }

    /** The text cut to fit (narrow and wide), done once per content (never while drawing). */
    private void layoutWatch() {
        WatchNext.Content c = watch;
        if (c == null) return;
        if (watchHead == null || watchHead.getTextSize() != 22 * k) {
            watchHead = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xD9FFFFFF));
            watchHead.setLetterSpacing(0.12f);
            watchTitle = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
            watchSub = new TextPaint(Ui.text(Ui.medium, 21 * k, 0xC7FFFFFF));
            for (Paint p : new Paint[] {watchHead, watchTitle, watchSub}) p.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        }
        int brand = HomeApps.brandColor(c.pkg);
        watchColor = brand != 0 ? brand : 0xFFFFFFFF;
        int n = Math.min(3, c.items.size());
        boolean wide = c.variant == WatchNext.ROW_WIDE;
        float small = (wide ? TS_SMALL : TS_H * 0.78f) * k, big = (wide ? TS_BIG : TS_H) * k;
        watchHeadX = TS_LEFT * k;
        for (int i = 0; i < n; i++) {
            WatchNext.Entry e = c.items.get(i);
            boolean seen = e.duration > 0 && e.position > 0;
            watchProgress[i] = seen ? Ui.clamp(e.position / (float) e.duration) : 0;
            String sub = e.sub;
            if (sub.isEmpty() && seen) {   // a film: the time left instead of an episode
                long left = (e.duration - e.position) / 60_000;
                sub = left < 1 ? "Queda menos de 1 min" : left < 60 ? "Quedan " + left + " min" : "Quedan " + (left / 60) + " h " + (left % 60) + " min";
            }
            watchTitles[i] = TextUtils.ellipsize(e.title, watchTitle, small, TextUtils.TruncateAt.END).toString();
            watchTitlesBig[i] = TextUtils.ellipsize(e.title, watchTitle, big, TextUtils.TruncateAt.END).toString();
            watchSubs[i] = TextUtils.ellipsize(sub, watchSub, big, TextUtils.TruncateAt.END).toString();
        }
        placeWatchCards();
        watchDirty = true;
    }

    /** Each card's place for the focus as it is now: the focused one wide, the others narrow (they follow the spring). */
    private static final float PANEL_SCALE = 0.95f, PANEL_TOP = 212, PANEL_MIN_W = 1040;
    private TextPaint panelSmall, panelPill;
    private Paint cardFade;
    private android.graphics.Matrix cardFadeM;
    private final RectF panelRect = new RectF();

    /** The playlists under the cards (Spotify only, on the "Íconos" shelf). */
    private int panelSmallCount() {
        WatchNext.Content c = watch;
        return c != null && SpotifyLists.PKG.equals(c.pkg) ? 4 : 0;   // both styles
    }

    /**
     * The "Íconos" shelf's panel: a pane of glass round the cards, a row of Spotify's popular playlists under them, and
     * a pill under the pane with what's there ("Abrir Spotify · 5 playlists").
     */
    private void drawPanelGlass(Canvas c, WatchNext.Content wc) {
        int ns = panelSmallCount();
        if (ns > 0) SpotifyLists.load(watchImageReady);
        float pad = 34 * k, item = 64 * k, cardsBottom = watchCard.top;
        for (int i = 0; i < Math.min(3, wc.items.size()); i++) cardsBottom = Math.max(cardsBottom, watchRects[i].bottom);
        float smallTop = cardsBottom + 70 * k;
        float bottom = ns > 0 ? smallTop + item + 40 * k : cardsBottom + pad;
        panelRect.set(watchCard.left - pad, watchCard.top - 20 * k, Math.max(watchCard.right, watchCard.left + PANEL_MIN_W * k) + pad, bottom);
        tmp.set(panelRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.4f));
        shadows.draw(c, tmp, 30 * k, SHADOW_BLUR * 1.6f * k, shadow);
        scene.liquid(c, panelRect, 30 * k, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                tone(0.6f, 0.45f), 1);
        fill.setShader(null);
        fill.setColor(0x33000000);
        c.drawRoundRect(panelRect, 30 * k, 30 * k, fill);
        if (panelSmall == null) {
            panelSmall = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xD9FFFFFF));
            panelPill = new TextPaint(Ui.text(Ui.semibold, 19 * k, 0xF2FFFFFF));
            panelPill.setTextAlign(Paint.Align.CENTER);
        }
        if (ns > 0) {   // "Playlists populares", the covers in a row, their names under them
            watchHead.setAlpha(217);
            c.drawText("PLAYLISTS POPULARES", watchCard.left, smallTop - 2 * k, watchHead);
            float inner = Math.max(watchCard.right, watchCard.left + PANEL_MIN_W * k) - watchCard.left;
            float x = watchCard.left, step = inner / ns;
            for (int i = 0; i < ns; i++) {
                float f = Ui.clamp(actFocus[i].value);
                cell.set(x, smallTop + 14 * k, x + item, smallTop + 14 * k + item);
                Bitmap b = SpotifyLists.covers[i];
                if (b != null) roundImage(c, b, cell, 10 * k);
                else {
                    fill.setColor(0x24FFFFFF);
                    c.drawRoundRect(cell, 10 * k, 10 * k, fill);
                }
                if (f > 0.01f) {
                    focusRing.setStrokeWidth(3.5f * k);
                    focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
                    c.drawRoundRect(cell.left - 5 * k, cell.top - 5 * k, cell.right + 5 * k, cell.bottom + 5 * k, 14 * k, 14 * k, focusRing);
                }
                float tx = cell.right + 12 * k, tw = step - item - 24 * k;
                panelSmall.setAlpha(255);
                c.drawText(TextUtils.ellipsize(SpotifyLists.NAMES[i], panelSmall, tw, TextUtils.TruncateAt.END).toString(),
                        tx, cell.centerY() - 4 * k, panelSmall);
                panelSmall.setAlpha(165);
                c.drawText(TextUtils.ellipsize("Playlist · Spotify", panelSmall, tw, TextUtils.TruncateAt.END).toString(), tx, cell.centerY() + 22 * k, panelSmall);
                panelSmall.setAlpha(255);
                x += step;
            }
        }
        // The pill under the pane.
        String app = shelfLabel(wc.pkg);
        String what = ns > 0 ? "Abrir " + app + "  ·  " + ns + " playlists" : "Abrir " + app + "  ·  " + Math.min(3, wc.items.size()) + " para seguir";
        float pw = panelPill.measureText(what) + 48 * k, ph = 44 * k, pcx = panelRect.centerX(), pt = panelRect.bottom + 14 * k;
        tmp.set(pcx - pw / 2, pt, pcx + pw / 2, pt + ph);
        scene.glass(c, tmp, ph / 2, 0xFFFFFFFF, 0.6f, 0, 0, 0.02f, 1, 1);
        c.drawText(what, pcx, tmp.centerY() + Ui.halfCap(panelPill), panelPill);
    }

    private static final float BUB_W = 1000, BUB_H = 660, BUB_TOP = 186;
    private TextPaint bubTitle, bubSub, bubHead, bubBig, bubBigSub;
    private Object bubFor;
    private boolean bubWide;
    private final String[] bubTitles = new String[3], bubSubs = new String[3], bubBigTitles = new String[3];
    private final String[] bubLists = new String[4];

    /**
     * The bubble that opens with ↑ (option C). "Íconos": nearly square over the wheel, the focused one big on the left,
     * the list and the playlists beside it. "Tarjetas" (K1): the whole width over the cards, in three columns: the cover,
     * the list, the playlists in a 2×2.
     */
    private void drawBubble(Canvas c, WatchNext.Content wc) {
        int n = Math.min(3, wc.items.size()), ns = panelSmallCount();
        if (ns > 0) SpotifyLists.load(watchImageReady);
        boolean wide = !iconDock();
        float x, y, pw, ph, big, ct, lx, lw, lHead, rStep, th;
        if (wide) {
            // Where the cards were (option E), no pane, straight on the background; centred over the bar of apps.
            float content = (400 + 56 + 560 + (ns > 0 ? 60 + 240 + 140 : 0)) * k;
            x = (w - content) / 2 - 44 * k;
            y = 192 * k;   // the middle between the top row and the bar
            pw = w - 72 * k;
            ph = 520 * k;
            big = 400 * k;
            ct = y + 88 * k;
            lx = x + 44 * k + big + 56 * k;
            lw = 560 * k;
            lHead = y + 58 * k;
            rStep = 112 * k;
            th = 92 * k;
        } else {
            x = (w - BUB_W * k) / 2;
            y = BUB_TOP * k;
            pw = BUB_W * k;
            ph = BUB_H * k;
            big = 440 * k;
            ct = y + 92 * k;
            lx = x + 44 * k + big + 40 * k;
            lw = x + pw - 44 * k - lx;
            lHead = y + 120 * k;
            rStep = 96 * k;
            th = 76 * k;
        }
        if (bubTitle == null) {
            bubTitle = new TextPaint(Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF));
            bubSub = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xB3FFFFFF));
            bubHead = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xD9FFFFFF));
            bubHead.setLetterSpacing(0.18f);
            bubBig = new TextPaint(Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF));
            bubBig.setShadowLayer(10 * k, 0, 2 * k, 0x99000000);
            bubBigSub = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xD9FFFFFF));
            bubBigSub.setShadowLayer(8 * k, 0, 2 * k, 0x99000000);
            for (Paint sp : new Paint[] {bubTitle, bubSub, bubHead}) sp.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        }
        if (bubFor != wc || bubWide != wide) {   // the texts, cut to fit, once per content
            bubFor = wc;
            bubWide = wide;
            float tw = lw - th - 40 * k;
            for (int i = 0; i < n; i++) {
                WatchNext.Entry e = wc.items.get(i);
                String t = e.title == null ? "" : e.title, sub = e.sub == null ? "" : e.sub;
                bubTitles[i] = TextUtils.ellipsize(t, bubTitle, tw, TextUtils.TruncateAt.END).toString();
                bubSubs[i] = TextUtils.ellipsize(sub, bubSub, tw, TextUtils.TruncateAt.END).toString();
                bubBigTitles[i] = TextUtils.ellipsize(t, bubBig, big - 40 * k, TextUtils.TruncateAt.END).toString();
            }
            for (int i = 0; i < 4; i++) {
                String nm = i < SpotifyLists.NAMES.length ? SpotifyLists.NAMES[i] : "";
                bubLists[i] = wide ? TextUtils.ellipsize(nm, bubSub, 220 * k, TextUtils.TruncateAt.END).toString() : nm;
            }
        }
        panelRect.set(x, y, x + pw, y + ph);
        if (!wide) {
            tmp.set(panelRect);
            tmp.offset(0, 18 * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.4f));
            shadows.draw(c, tmp, 44 * k, SHADOW_BLUR * 1.6f * k, shadow);
            scene.liquid(c, panelRect, 44 * k, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1);
        }
        // The heading, with a dot of the app's colour.
        float hx = x + 44 * k, hb = y + 58 * k;
        if (watchColor != 0xFFFFFFFF) {
            fill.setShader(null);
            fill.setColor(watchColor);
            c.drawCircle(hx + 7 * k, hb - Ui.halfCap(bubHead), 7 * k, fill);
            hx += 26 * k;
        }
        c.drawText(wc.header.toUpperCase(Ui.locale()), hx, hb, bubHead);
        // The big cover: the one in focus (the first while the playlists have it).
        int bi = Math.max(0, Math.min(n - 1, watchIndex));
        WatchNext.Entry be = wc.items.get(bi);
        cell.set(x + 44 * k, ct, x + 44 * k + big, ct + big);
        float rad = 26 * k;
        tmp.set(cell);
        tmp.offset(0, 14 * k);
        shadow.setColor(0x66000000);
        shadows.draw(c, tmp, rad, SHADOW_BLUR * 1.4f * k, shadow);
        if (be.bitmap != null) cropImage(c, be.bitmap, cell, rad);
        else drawWatchImage(c, be, cell, rad, 1);
        if (cardFade == null) {
            cardFade = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardFade.setShader(new android.graphics.LinearGradient(0, 0, 0, 1, 0x00000000, 0xD0000000, Shader.TileMode.CLAMP));
            cardFadeM = new android.graphics.Matrix();
        }
        float ft = cell.top + cell.height() * 0.55f;
        cardFadeM.setScale(1, cell.bottom - ft);
        cardFadeM.postTranslate(0, ft);
        cardFade.getShader().setLocalMatrix(cardFadeM);
        c.save();
        c.clipRect(cell.left, ft, cell.right, cell.bottom);
        c.drawRoundRect(cell, rad, rad, cardFade);
        c.restore();
        String bt = bubBigTitles[bi] == null ? "" : bubBigTitles[bi];
        c.drawText(bt, cell.left + 22 * k, cell.bottom - 54 * k, bubBig);
        String bs = bubSubs[bi] == null ? "" : bubSubs[bi];
        c.drawText(bs, cell.left + 22 * k, cell.bottom - 22 * k, bubBigSub);
        if (watchRow == 0) {
            focusRing.setStrokeWidth(4 * k);
            focusRing.setColor(0xF2FFFFFF);
            float o = 5 * k;
            c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, rad + o, rad + o, focusRing);
        }
        // Under it: how far you got.
        if (watchProgress[bi] > 0 && !wide) {
            float by = cell.bottom + 30 * k, bh = 10 * k;
            fill.setShader(null);
            fill.setColor(0x4DFFFFFF);
            tmp.set(cell.left, by, cell.right, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.right = tmp.left + Math.max(bh, cell.width() * watchProgress[bi]);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
        // The list.
        c.drawText("MÁS PARA SEGUIR", lx, lHead, bubHead);
        for (int i = 0; i < n; i++) {
            float ry = lHead + 24 * k + i * rStep, f = Ui.clamp(watchFocus[i].value);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0x2EFFFFFF, f));
                tmp.set(lx - 12 * k, ry - 8 * k, lx + lw + 4 * k, ry + th + 8 * k);
                c.drawRoundRect(tmp, 16 * k, 16 * k, fill);
            }
            WatchNext.Entry e = wc.items.get(i);
            tmp.set(lx, ry, lx + th, ry + th);
            if (e.bitmap != null) cropImage(c, e.bitmap, tmp, 12 * k);
            else drawWatchImage(c, e, new RectF(tmp), 12 * k, 1);
            float tx = lx + th + 20 * k;
            c.drawText(bubTitles[i] == null ? "" : bubTitles[i], tx, ry + th * 0.45f, bubTitle);
            c.drawText(bubSubs[i] == null ? "" : bubSubs[i], tx, ry + th * 0.45f + 30 * k, bubSub);
        }
        // Spotify: its popular playlists. Wide: a 2×2 on the right, the names under them; square: a row under the list.
        if (ns > 0) {
            float px = wide ? lx + lw + 60 * k : lx, py = wide ? lHead : y + 470 * k;
            float item = (wide ? 140 : 88) * k, stepX = (wide ? 240 : 102) * k, stepY = 190 * k;
            c.drawText("PLAYLISTS POPULARES", px, py, bubHead);
            for (int i = 0; i < ns; i++) {
                float f = Ui.clamp(actFocus[i].value);
                float ix = px + (wide ? (i % 2) : i) * stepX, iy = py + 24 * k + (wide ? (i / 2) * stepY : 0);
                cell.set(ix, iy, ix + item, iy + item);
                Bitmap b = SpotifyLists.covers[i];
                if (b != null) roundImage(c, b, cell, 14 * k);
                else {
                    fill.setShader(null);
                    fill.setColor(0x24FFFFFF);
                    c.drawRoundRect(cell, 14 * k, 14 * k, fill);
                }
                if (f > 0.01f) {
                    focusRing.setStrokeWidth(4 * k);
                    focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
                    c.drawRoundRect(cell.left - 5 * k, cell.top - 5 * k, cell.right + 5 * k, cell.bottom + 5 * k, 19 * k, 19 * k, focusRing);
                }
                if (wide) c.drawText(bubLists[i], ix, cell.bottom + 30 * k, f > 0.5f ? bubTitle : bubSub);
            }
            if (!wide) {
                int at = watchRow == 1 ? Math.max(0, Math.min(ns - 1, actIndex)) : 0;
                c.drawText(watchRow == 1 ? bubLists[at] : "Top 50 Argentina · Viva Latino · …", lx, py + 24 * k + item + 40 * k,
                        watchRow == 1 ? bubTitle : bubSub);
            }
        }
    }

    private void placeWatchCards() {
        WatchNext.Content c = watch;
        if (c == null) return;
        boolean wide = c.variant == WatchNext.ROW_WIDE;
        float small = (wide ? TS_SMALL : TS_H * 0.78f) * k, big = (wide ? TS_BIG : TS_H) * k;
        int n = Math.min(3, c.items.size());
        // Nothing focused yet (the shelf coming up): the first card is the wide one.
        float any = 0;
        for (int i = 0; i < n; i++) any = Math.max(any, Ui.clamp(watchFocus[i].value));
        float sc = iconDock() ? PANEL_SCALE : 1;
        small *= sc;
        big *= sc;
        float total = 0;
        for (int i = 0; i < n; i++) {
            float f = Ui.clamp(watchFocus[i].value);
            if (i == 0) f = Math.max(f, 1 - any);
            total += small + (big - small) * f + (i > 0 ? TS_GAP * k : 0);
        }
        float x = iconDock() ? (w - Math.max(total, PANEL_MIN_W * k)) / 2 : (w - total) / 2;   // centred, like the bar
        float top = (iconDock() ? PANEL_TOP : TS_TOP) * k;
        float x0 = x;
        for (int i = 0; i < n; i++) {
            float f = Ui.clamp(watchFocus[i].value);
            if (i == 0) f = Math.max(f, 1 - any);
            float cw = small + (big - small) * f, ch = wide ? TS_H * sc * k : cw;
            watchRects[i].set(x, top, x + cw, top + ch);
            x += cw + TS_GAP * k;
        }
        watchHeadX = x0;
        watchCard.set(x0, top - 60 * k, x - TS_GAP * k, top + TS_H * sc * k + 80 * k);
    }

    /** The picture centre-cropped into `r` (never stretched), with rounded corners. */
    private void cropImage(Canvas c, Bitmap b, RectF r, float rad) {
        BitmapShader shader = imageShaders.get(b);
        if (shader == null) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(b, shader);
        }
        float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        cropMatrix.setScale(s, s);
        cropMatrix.postTranslate(r.centerX() - b.getWidth() * s / 2, r.centerY() - b.getHeight() * s / 2);
        shader.setLocalMatrix(cropMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(r, rad, rad, imageFill);
        imageFill.setShader(null);
    }

    private void drawWatch(Canvas c) {
        WatchNext.Content wc = watch;
        if (wc == null) return;
        if (iconDock()) {   // Íconos: the bubble C. Tarjetas: the cards in a row, the focused one wide (as before)
            drawBubble(c, wc);
            return;
        }
        if (spotRow()) {
            drawSpotifyRow(c, wc);
            return;
        }
        placeWatchCards();
        // The heading, with a dot of the app's colour.
        float hx = watchHeadX, hb = iconDock() ? (PANEL_TOP - 30) * k : TS_HEAD * k;
        if (watchColor != 0xFFFFFFFF) {
            fill.setShader(null);
            fill.setColor(watchColor);
            c.drawCircle(hx + 6 * k, hb - Ui.halfCap(watchHead), 6 * k, fill);
            hx += 24 * k;
        }
        c.drawText(wc.header, hx, hb, watchHead);
        boolean round = wc.variant == WatchNext.ROW_ROUND;
        for (int i = 0; i < Math.min(3, wc.items.size()); i++) {
            RectF r = watchRects[i];
            float f = Ui.clamp(watchFocus[i].value);
            cell.set(r);
            cell.offset(0, -6 * k * f);
            float rad = round ? cell.width() / 2 : 18 * k;
            tmp.set(cell);
            tmp.offset(0, (10 + 12 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.35f + 0.2f * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * 1.4f * k, shadow);
            WatchNext.Entry e = wc.items.get(i);
            if (e.bitmap != null) cropImage(c, e.bitmap, cell, rad);
            else drawWatchImage(c, e, cell, rad, 1);
            if (watchProgress[i] > 0) {   // how far you got, along the picture's foot, in the app's colour
                float in = 14 * k, bh = 6 * k, y = cell.bottom - in;
                fill.setShader(null);
                fill.setColor(0x8C000000);
                tmp.set(cell.left + in, y - bh, cell.right - in, y);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
                fill.setColor(watchColor);
                tmp.right = tmp.left + Math.max(bh, (cell.width() - 2 * in) * watchProgress[i]);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            }
            if (f > 0.01f) {
                if (watchRim == null) {
                    watchRim = new Paint(Paint.ANTI_ALIAS_FLAG);
                    watchRim.setStyle(Paint.Style.STROKE);
                }
                watchRim.setStrokeWidth(4 * k);
                watchRim.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
                float o = 2 * k;
                c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, rad + o, rad + o, watchRim);
            }
            if (iconDock()) {   // the title on the picture, over a dark fade at its foot
                if (cardFade == null) {
                    cardFade = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cardFade.setShader(new android.graphics.LinearGradient(0, 0, 0, 1, 0x00000000, 0xD0000000, Shader.TileMode.CLAMP));
                    cardFadeM = new android.graphics.Matrix();
                }
                float ft = cell.top + cell.height() * 0.45f;
                cardFadeM.setScale(1, cell.bottom - ft);
                cardFadeM.postTranslate(0, ft);
                cardFade.getShader().setLocalMatrix(cardFadeM);
                c.save();
                tmp.set(cell);
                c.clipRect(cell.left, ft, cell.right, cell.bottom);
                c.drawRoundRect(tmp, rad, rad, cardFade);
                c.restore();
                float pad = 16 * k, tb = cell.bottom - pad - (f > 0.01f ? 30 * k : 0);
                c.save();
                c.clipRect(cell);
                watchTitle.setAlpha(255);
                c.drawText(f > 0.5f ? watchTitlesBig[i] : watchTitles[i], cell.left + pad, tb, watchTitle);
                if (f > 0.01f) {
                    watchSub.setAlpha(Math.round(220 * f));
                    c.drawText(watchSubs[i], cell.left + pad, tb + 30 * k, watchSub);
                }
                c.restore();
                continue;
            }
            // Under it: the title (whole on the wide card), and its details only on the focused one.
            float base = r.bottom + 44 * k;
            watchTitle.setAlpha(Math.round(255 * (0.8f + 0.2f * f)));
            c.drawText(f > 0.5f ? watchTitlesBig[i] : watchTitles[i], r.left, base, watchTitle);
            if (f > 0.01f) {
                watchSub.setAlpha(Math.round(199 * f));
                c.drawText(watchSubs[i], r.left, base + 32 * k, watchSub);
            }
        }
    }

    private Paint spotArc;
    private android.graphics.Path spotPath;
    private static final int SPOT_ITEMS = 6;   // now playing, 4 playlists, the library
    private int spotVersion = -1;
    private String spotTitle = "", spotArtist = "";
    private final String[] spotNames = new String[4];

    /** Spotify with the cards style: its bubble is one row of its own. */
    private boolean spotRow() {
        WatchNext.Content c = watch;
        return !iconDock() && c != null && SpotifyLists.PKG.equals(c.pkg);
    }

    /**
     * Tarjetas, Spotify: what's playing now (big), Spotify's playlists (Top 50 Argentina…) and a way into the library,
     * in one row; the focused one grows.
     */
    private void drawSpotifyRow(Canvas c, WatchNext.Content wc) {
        SpotifyLists.load(watchImageReady);
        NowPlaying np = nowPlaying;
        boolean live = np != null && np.visible() && SpotifyLists.PKG.equals(np.pkg);
        WatchNext.Entry first = wc.items.isEmpty() ? null : wc.items.get(0);
        Bitmap art = live ? (np.artLarge != null ? np.artLarge : np.art) : first != null ? first.bitmap : null;
        int ver = live ? np.version : -2 - wc.items.size();
        float big = TS_H * k, small = 220 * k, gap = 40 * k;
        if (ver != spotVersion || spotNames[0] == null) {   // the texts, cut to fit, once per song
            spotVersion = ver;
            String t = live ? np.title : first != null ? first.title : "";
            String a = live ? np.artist : first != null ? first.sub : "";
            spotTitle = TextUtils.ellipsize(t == null ? "" : t, watchTitle, big, TextUtils.TruncateAt.END).toString();
            spotArtist = TextUtils.ellipsize(a == null ? "" : a, watchSub, big, TextUtils.TruncateAt.END).toString();
            for (int i = 0; i < 4; i++) spotNames[i] = TextUtils.ellipsize(i < SpotifyLists.NAMES.length
                    ? SpotifyLists.NAMES[i] : "", watchSub, small + 10 * k, TextUtils.TruncateAt.END).toString();
        }
        // Centred over the bar of apps. The heading, with Spotify's dot.
        float x0 = (w - (big + 60 * k + 4 * (small + gap) + small)) / 2;
        float hx = x0, hb = (TS_TOP - 52) * k;   // above the cover even when it rises with the focus
        fill.setShader(null);
        fill.setColor(0xFF1ED760);
        c.drawCircle(hx + 6 * k, hb - Ui.halfCap(watchHead), 6 * k, fill);
        watchHead.setAlpha(255);
        c.drawText(live ? "SONANDO AHORA" : "LO ÚLTIMO QUE SONÓ", hx + 24 * k, hb, watchHead);
        float x = x0, top = TS_TOP * k;
        // Now playing.
        spotTile(c, 0, x, top, big, art, 22 * k);
        if (live && np.duration > 0) {   // how far it is, along the cover's foot
            float in = 16 * k, bh = 6 * k, y = top + big - in, p = Ui.clamp(np.progress());
            fill.setShader(null);
            fill.setColor(0x8C000000);
            tmp.set(x + in, y - bh, x + big - in, y);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            fill.setColor(0xFF1ED760);
            tmp.right = tmp.left + Math.max(bh, (big - 2 * in) * p);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
        watchTitle.setAlpha(255);
        c.drawText(spotTitle, x, top + big + 44 * k, watchTitle);
        watchSub.setAlpha(199);
        c.drawText(spotArtist, x, top + big + 76 * k, watchSub);
        // The library first, then the playlists (heading over them), all along the top of the big cover.
        x += big + 60 * k;
        float sTop = top, under = top + small + 44 * k;
        spotTile(c, 1, x, sTop, small, null, 16 * k);
        float cx = x + small / 2, cy = sTop + small / 2 - 6 * k * Ui.clamp(actFocus[1].value);
        // The library's mark, like Spotify's: three books standing and one leaning.
        float u = small * 0.05f, bh = small * 0.36f, by = cy + bh / 2;
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        for (int j = 0; j < 3; j++) {
            float bx = cx - 4.2f * u + j * 2.1f * u;
            tmp.set(bx, by - bh, bx + 1.1f * u, by);
            c.drawRoundRect(tmp, u * 0.3f, u * 0.3f, fill);
        }
        c.save();
        c.rotate(-20, cx + 2.6f * u, by);
        tmp.set(cx + 2.6f * u, by - bh, cx + 3.7f * u, by);
        c.drawRoundRect(tmp, u * 0.3f, u * 0.3f, fill);
        c.restore();
        watchSub.setAlpha(Ui.clamp(actFocus[1].value) > 0.5f ? 255 : 199);
        c.drawText("Tu biblioteca", x, under, watchSub);
        x += small + gap;
        c.drawText("PLAYLISTS", x, hb, watchHead);
        for (int i = 0; i < 4; i++) {
            spotTile(c, 2 + i, x, sTop, small, SpotifyLists.covers[i], 16 * k);
            watchSub.setAlpha(Ui.clamp(actFocus[2 + i].value) > 0.5f ? 255 : 199);
            c.drawText(spotNames[i], x, under, watchSub);
            x += small + gap;
        }
        watchSub.setAlpha(199);
    }

    /** One square of the Spotify row: it rises and grows with the focus, with a white ring. */
    private void spotTile(Canvas c, int i, float x, float y, float s, Bitmap b, float rad) {
        float f = Ui.clamp(actFocus[i].value), g = s * 0.08f * f;
        cell.set(x - g / 2, y - g - 8 * k * f, x + s + g / 2, y + s - 8 * k * f);
        tmp.set(cell);
        tmp.offset(0, (10 + 10 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.35f + 0.2f * f));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * 1.3f * k, shadow);
        if (b != null) cropImage(c, b, cell, rad);
        else {
            fill.setShader(null);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(cell, rad, rad, fill);
            focusRing.setStrokeWidth(2 * k);
            focusRing.setColor(0x59FFFFFF);
            c.drawRoundRect(cell, rad, rad, focusRing);
        }
        if (f > 0.01f) {
            focusRing.setStrokeWidth(4 * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
            float o = 5 * k;
            c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, rad + o, rad + o, focusRing);
        }
    }

    /** Tarjetas, Spotify: its playlists in a 2×2 to the right of the cards. */
    private void drawCardPlaylists(Canvas c) {
        int ns = panelSmallCount();
        if (ns <= 0) return;
        SpotifyLists.load(watchImageReady);
        if (bubTitle == null) {
            bubTitle = new TextPaint(Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF));
            bubSub = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xB3FFFFFF));
            bubHead = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xD9FFFFFF));
            bubHead.setLetterSpacing(0.18f);
            bubBig = new TextPaint(Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF));
            bubBig.setShadowLayer(10 * k, 0, 2 * k, 0x99000000);
            bubBigSub = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xD9FFFFFF));
            bubBigSub.setShadowLayer(8 * k, 0, 2 * k, 0x99000000);
            for (Paint sp : new Paint[] {bubTitle, bubSub, bubHead}) sp.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        }
        if (cardLists[0] == null) {
            for (int i = 0; i < 4; i++) cardLists[i] = i < SpotifyLists.NAMES.length
                    ? TextUtils.ellipsize(SpotifyLists.NAMES[i], bubSub, 220 * k, TextUtils.TruncateAt.END).toString() : "";
        }
        float item = 140 * k, stepX = 240 * k, stepY = 196 * k, px = w - (TS_LEFT + 380) * k, py = TS_TOP * k;
        c.drawText("PLAYLISTS POPULARES", px, TS_HEAD * k, watchHead);
        for (int i = 0; i < ns; i++) {
            float f = watchRow == 1 ? Ui.clamp(actFocus[i].value) : 0;
            float ix = px + (i % 2) * stepX, iy = py + (i / 2) * stepY;
            cell.set(ix, iy, ix + item, iy + item);
            cell.offset(0, -6 * k * f);
            tmp.set(cell);
            tmp.offset(0, 10 * k);
            shadow.setColor(0x59000000);
            shadows.draw(c, tmp, 16 * k, SHADOW_BLUR * k, shadow);
            Bitmap b = SpotifyLists.covers[i];
            if (b != null) roundImage(c, b, cell, 16 * k);
            else {
                fill.setShader(null);
                fill.setColor(0x24FFFFFF);
                c.drawRoundRect(cell, 16 * k, 16 * k, fill);
            }
            if (f > 0.01f) {
                focusRing.setStrokeWidth(4 * k);
                focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
                c.drawRoundRect(cell.left - 5 * k, cell.top - 5 * k, cell.right + 5 * k, cell.bottom + 5 * k, 21 * k, 21 * k, focusRing);
            }
            c.drawText(cardLists[i], ix, iy + item + 30 * k, f > 0.5f ? bubTitle : bubSub);
        }
    }

    private final String[] cardLists = new String[4];

    private void drawWatchImage(Canvas c, WatchNext.Entry e, RectF r, float rad, float a) {
        if (e.bitmap != null) {
            int alpha = Math.round(255 * a);
            bitmapPaint.setAlpha(alpha);
            imageFill.setAlpha(alpha);
            roundImage(c, e.bitmap, r, rad);
            bitmapPaint.setAlpha(255);
            imageFill.setAlpha(255);
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(tone(0x14000000, 0x24FFFFFF), a));
            c.drawRoundRect(r, rad, rad, fill);
            // No picture (YouTube sometimes sends none): a play sign, so it reads as a video rather than a hole.
            Symbols.draw(getContext(), c, "play", r.centerX(), r.centerY(), Math.min(r.height() * 0.32f, 34 * k),
                    Ui.alpha(tone(L_DIM, 0xB3FFFFFF), a));
        }
    }

    // MARK: - Now playing (top right, left of the round buttons)

    private NowPlaying nowPlaying;
    private TextPaint npTitle, npArtist;
    private int npVersion = -1;
    private float npWidth = -1;
    private CharSequence npTitleText = "", npArtistText = "";
    private final RectF npRect = new RectF();
    /** 1 = the full capsule (playing), 0 = collapsed to a round cover (paused). */
    private final Spring[] npExpand = {new Spring(0.3f, 0.82f, 1)};
    private static final float[] EQ_SPEED = {5.3f, 7.1f, 4.4f}, EQ_PHASE = {0f, 1.7f, 0.9f};

    /**
     * A new song or play/pause: the buttons' layer is drawn again; while a song plays, ~12 times a second for the
     * equalizer (only that layer, and only while Home is on screen).
     */
    private static final long EQ_FRAME = 83;
    private final Runnable npTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            boolean eq = nowPlaying != null && nowPlaying.playing && nowPlaying.visible();
            if (nowPlaying != null) {
                statusDirty = true;
                // The open player: its wave travels with the equalizer while it plays; paused, only the progress
                // moves, once a second.
                long now = SystemClock.uptimeMillis();
                if (npOpen[0].value > 0.01f && (eq || now - npHeroAt >= 1000)) {
                    npHeroAt = now;
                    heroDirty = true;
                }
                wake();
            }
            if (eq) handler.postDelayed(this, idleMode() ? 1000 : EQ_FRAME);   // idle: the bars barely move
        }
    };

    private void restartTick() {
        handler.removeCallbacks(npTick);
        handler.post(npTick);
    }

    /**
     * The player's first open used to stutter: its shadow, its glass, its fonts and the cover's texture were all made
     * on that frame. Once music shows (and the remote rests), they're made unseen on a quiet frame instead.
     */
    private boolean npWarmed, npWarmPending;
    private int npArtReady = -1;
    private final Runnable npWarm = new Runnable() {
        @Override public void run() {
            NowPlaying np = nowPlaying;
            if (!running || npWarmed || np == null || !np.visible()) return;
            if (npOpen[0].value > 0.01f || SystemClock.uptimeMillis() - lastKeyAt < 1500) {
                handler.postDelayed(this, 1500);   // in use: a little later
                return;
            }
            npWarmPending = true;
            heroLayer.invalidate();
        }
    };

    private void onNowPlaying() {
        NowPlaying cur = nowPlaying;
        if (cur != null && cur.visible()) {
            if (!npWarmed) {
                handler.removeCallbacks(npWarm);
                handler.postDelayed(npWarm, 1200);
            }
            if (cur.version != npArtReady) {   // each new cover goes up to the GPU ahead, not when the player opens
                npArtReady = cur.version;
                Bitmap a = cur.artLarge != null ? cur.artLarge : cur.art;
                if (a != null) a.prepareToDraw();
            }
        }
        npExpand[0].target = npCollapsed ? 0 : 1;
        if ((zone == Z_PLAYER || zone == Z_PLAYER_BAR || zone == Z_PLAYER_CTRL) && (nowPlaying == null || !nowPlaying.visible())) {
            zone = Z_TOP;
            topIndex = 3;
            retarget(false);
        }
        restartTick();
    }

    // MARK: - Glass edition: the capsule drops into the player (focus on it, its bar or its buttons)

    /**
     * npOpen: 0 = the capsule, 1 = dropped into the player. npFull follows it (the card's size; it once had a compact
     * step in between): 1 = the whole player, the capsule's buttons inside it and the cover fading in from the right.
     * A light bounce as it arrives.
     */
    private final Spring[] npOpen = {new Spring(0.28f, 0.74f, 0)}, npFull = {new Spring(0.28f, 0.76f, 0)};
    private long npHeroAt;
    /** The bar's focus, and where ← → have moved the playhead to (-1: nowhere yet); sent once the arrows rest. */
    private final Spring[] barFocus = {new Spring(0.3f, 0.8f, 0)};
    private long scrubTo = -1;
    private static final long SCRUB_STEP = 5000, SCRUB_REST = 450;
    private final Runnable commitScrub = () -> {
        if (scrubTo >= 0 && nowPlaying != null) nowPlaying.seekTo(scrubTo);
        scrubTo = -1;
        heroDirty = true;
        wake();
    };

    private void scrub(int dx) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.canSeek()) return;
        long from = scrubTo >= 0 ? scrubTo : (long) (np.progress() * np.duration);
        scrubTo = Math.max(0, Math.min(np.duration - 1000, from + dx * SCRUB_STEP));
        handler.removeCallbacks(commitScrub);
        handler.postDelayed(commitScrub, SCRUB_REST);
        heroDirty = true;
        click.move();
        wake();
    }

    private static final float NPC_W1 = 560, NPC_H1 = 160, NPC_W2 = 560, NPC_H2 = 330, NPC_PAD = 22, NPC_COVER = 108;
    /** The cover as the card's picture, and its colour on the progress (made once per song). */
    private final android.graphics.Matrix npcGlowMatrix = new android.graphics.Matrix();
    private int npcGlowVersion = -1, npcAccent = 0xFFFFFFFF;
    private float npcArtAt = Float.NaN, npcFadeAt = Float.NaN;
    private int npcFadeVersion = -1;
    private final Paint npcArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private android.graphics.LinearGradient npcScrim, npcFoot;
    private final RectF npCard = new RectF(), npMorph = new RectF(), npFullRect = new RectF();
    private TextPaint npcLabel, npcTitle, npcArtist, npcTime;
    private int npcVersion = -1;
    private boolean npcWide;
    private CharSequence npcTitleText = "", npcArtistText = "";
    private String npcLabelText = "";

    private final Path npShape = new Path();
    private final RectF npShapeRect = new RectF();
    private Paint npRim;

    /** The L: the capsule's row on top (left of the round buttons), the body under the buttons too, rounded corners. */
    private void lShape(Path p, float l, float t, float nx, float ny, float r, float b, float rad) {
        float rr = Math.min(rad, Math.min((nx - l) / 2, (b - t) / 2));
        p.reset();
        p.moveTo(l + rr, t);
        if (r - nx < 2 * rr || b - ny < 2 * rr) {   // still just the capsule (or a plain card): a rounded rect
            float right = Math.max(nx, r);
            npShapeRect.set(l, t, right, b);
            p.addRoundRect(npShapeRect, rr, rr, Path.Direction.CW);
            return;
        }
        float ri = Math.min(rr, Math.min((r - nx) / 2, (ny - t) / 2));   // the notch's inner corner
        p.lineTo(nx - rr, t);
        p.quadTo(nx, t, nx, t + rr);
        p.lineTo(nx, ny - ri);
        p.quadTo(nx, ny, nx + ri, ny);
        p.lineTo(r - rr, ny);
        p.quadTo(r, ny, r, ny + rr);
        p.lineTo(r, b - rr);
        p.quadTo(r, b, r - rr, b);
        p.lineTo(l + rr, b);
        p.quadTo(l, b, l, b - rr);
        p.lineTo(l, t + rr);
        p.quadTo(l, t, l + rr, t);
        p.close();
    }

    /**
     * Option L1: the capsule opens into an L that wraps the round buttons: its own row grows down, and under the
     * buttons too, to the right edge. The cover and the song top left (where the capsule was), the bar and the
     * controls under the buttons. It stays above the clock's foot.
     */
    private LinearGradient npRimShader;
    private float npRimAt;

    private void drawNpCard(Canvas c) {
        float e = npOpen[0].value;
        NowPlaying np = nowPlaying;
        if (e <= 0.01f || np == null || !np.visible()) return;
        RectF first = topRects[3];
        float capL = first.left - 22 * k - 440 * k;
        float L = capL - 8 * k, T = first.top - 8 * k, NX = first.left - 12 * k, NY = first.bottom + 16 * k;
        float R = CONTENT_R * k + 10 * k, B = T + 420 * k;
        float ce = Ui.clamp(e), grow = e;   // the spring overshoots a little
        float r = lerp(NX, R, ce), b = lerp(first.bottom + 8 * k, B, grow);
        npCard.set(L, T, R, B);
        npMorph.set(L, T, r, b);
        lShape(npShape, L, T, NX, NY, r, b, 32 * k);
        float ge = Ui.clamp(e * 3);
        // The shadow, under the body.
        tmp.set(L + 14 * k, NY, R - 14 * k, B);   // its final size: one blurred shadow, made once (not one per frame)
        tmp.offset(0, 22 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.45f * ge * ce));
        shadows.draw(c, tmp, 32 * k, SHADOW_BLUR * 1.8f * k, shadow);
        // The same glass as the capsule and the round buttons (clipped to the L), a light shade so the text reads, and
        // their rim lit from the top left along the L's edge.
        c.save();
        c.clipPath(npShape);
        scene.liquid(c, npMorph, 32 * k, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                tone(0.6f, 0.45f), ge);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x26000000, ge));
        c.drawRect(npMorph, fill);
        c.restore();
        if (npRim == null) {
            npRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            npRim.setStyle(Paint.Style.STROKE);
        }
        npRim.setStrokeWidth(Math.max(1.5f, 2.2f * k));
        if (npRimShader == null || npRimAt != L + T + R + B) {
            npRimAt = L + T + R + B;
            npRimShader = new LinearGradient(L, T, R, B, new int[] {0x99FFFFFF, 0x24FFFFFF, 0x47FFFFFF},
                    new float[] {0, 0.62f, 1}, Shader.TileMode.CLAMP);
        }
        npRim.setShader(npRimShader);
        npRim.setAlpha(Math.round(255 * ge));
        c.drawPath(npShape, npRim);
        npRim.setShader(null);
        // With the focus on the player itself (not its bar or its buttons), the rim turns white.
        float pf = Ui.clamp(playerFocus[0].value) * ge;
        if (pf > 0.01f) {
            focusRing.setStrokeWidth(3.5f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * pf));
            c.drawPath(npShape, focusRing);
        }
        if (np.version != npcGlowVersion) {
            npcGlowVersion = np.version;
            npcAccent = blend(coverColor(np.art), 0xFFFFFFFF, 0.35f);
        }
        float te = Ui.clamp((e - 0.5f) / 0.5f);   // the content fades in once it's mostly open
        if (te <= 0.01f) return;
        if (npcTitle == null || npcTitle.getTextSize() != 36 * k) {
            npcLabel = new TextPaint(Ui.text(Ui.medium, 19 * k, 0xD9FFFFFF));
            npcTitle = new TextPaint(Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF));
            npcArtist = new TextPaint(Ui.text(Ui.regular, 25 * k, 0xCCFFFFFF));
            npcTime = new TextPaint(Ui.numbers(Ui.medium, 19 * k, 0xCCFFFFFF));
            npcVersion = -1;
        }
        c.save();
        c.clipPath(npShape);
        // Option LB: the cover big on the left, the L's whole height, fading into the glass towards the right (made
        // once per song); the song, the bar and the controls beside it, under the round buttons.
        float hh = B - T;
        Bitmap art = np.artLarge != null ? np.artLarge : np.art;
        if (np.version != npcFadeVersion || npcFadeAt != L + T + hh) {
            npcFadeVersion = np.version;
            npcFadeAt = L + T + hh;
            npcArtPaint.setShader(null);
            if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                float sc = Math.max(hh / art.getWidth(), hh / art.getHeight());
                m.setScale(sc, sc);
                m.postTranslate(L + hh / 2 - art.getWidth() * sc / 2, T + hh / 2 - art.getHeight() * sc / 2);
                BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sh.setLocalMatrix(m);
                android.graphics.LinearGradient fade = new android.graphics.LinearGradient(L + hh * 0.42f, 0, L + hh, 0,
                        0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
                npcArtPaint.setShader(new android.graphics.ComposeShader(sh, fade, android.graphics.PorterDuff.Mode.DST_IN));
            }
        }
        if (npcArtPaint.getShader() != null) {
            npcArtPaint.setAlpha(Math.round(255 * te));
            c.drawRect(L, T, L + hh, B, npcArtPaint);
        } else {
            fill.setColor(Ui.alpha(0x24FFFFFF, te));
            c.drawRect(L, T, L + hh * 0.7f, B, fill);
        }
        float tx = L + hh * 0.86f, tw = R - 34 * k - tx;
        if (np.version != npcVersion) {
            npcVersion = np.version;
            npcTitleText = TextUtils.ellipsize(np.title, npcTitle, tw, TextUtils.TruncateAt.END);
            String app = np.pkg == null ? "" : shelfLabel(np.pkg);
            String who = np.artist + (app.isEmpty() || app.equals(np.pkg) ? "" : "  ·  " + app);
            npcArtistText = TextUtils.ellipsize(who, npcArtist, tw, TextUtils.TruncateAt.END);
            npcLabelText = "";
            for (Paint sp : new Paint[] {npcTitle, npcArtist, npcTime}) sp.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        }
        npcTitle.setColor(Ui.alpha(0xFFFFFFFF, te));
        c.drawText(npcTitleText, 0, npcTitleText.length(), tx, NY + 58 * k, npcTitle);
        npcArtist.setColor(Ui.alpha(0xCCFFFFFF, te));
        c.drawText(npcArtistText, 0, npcArtistText.length(), tx, NY + 98 * k, npcArtist);
        // The progress, across the body beside the cover: the song's colour with its hill of sound, the knob, times.
        float p = scrubTo >= 0 && np.duration > 0 ? Ui.clamp(scrubTo / (float) np.duration) : Ui.clamp(np.progress());
        float by = NY + 150 * k, bh = 12 * k;
        float a0 = tx, a1 = R - 34 * k, head = a0 + (a1 - a0) * p;
        fill.setColor(Ui.alpha(0x47FFFFFF, te));
        tmp.set(a0, by - bh / 2, a1, by + bh / 2);
        c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        if (head - a0 > bh) {
            float t = SystemClock.uptimeMillis() / 1000f, span = head - a0, amp = (np.playing ? 22 : 8) * k;
            glyphPath.reset();
            glyphPath.moveTo(a0, by);
            for (float wx = a0; ; wx += 4 * k) {
                float xx = Math.min(wx, head), u = (xx - a0) / span;
                float hill = (float) Math.sin(Math.PI * u);
                float roll = np.playing ? 0.6f + 0.4f * (float) Math.sin(u * 7 - t * 2.2f) : 1;
                glyphPath.lineTo(xx, by - bh / 2 - amp * hill * hill * roll);
                if (xx >= head) break;
            }
            glyphPath.lineTo(head, by);
            glyphPath.close();
            fill.setColor(Ui.alpha(npcAccent, 0.45f * te));
            c.drawPath(glyphPath, fill);
            fill.setColor(Ui.alpha(npcAccent, te));
            tmp.set(a0, by - bh / 2, head, by + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
        float bf = Ui.clamp(barFocus[0].value), knob = 14 * k * (1 + 0.45f * bf);
        fill.setColor(Ui.alpha(0xFFFAF6F2, te));
        c.drawCircle(head, by, knob, fill);
        if (bf > 0.01f) {
            focusRing.setStrokeWidth(3.5f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * bf * te));
            c.drawCircle(head, by, knob + 7 * k, focusRing);
        }
        String done = time(np.duration * p), total = time(np.duration);
        npcTime.setColor(Ui.alpha(0xCCFFFFFF, te));
        c.drawText(done, a0, by + 38 * k, npcTime);
        c.drawText(total, a1 - npcTime.measureText(total), by + 38 * k, npcTime);
        // The controls under it (option B): previous, play/pause and next in a glass pill, shuffle and repeat beside.
        updateCtrlKinds(np);
        int n = ctrlKinds.length;
        float step = 70 * k, side = 92 * k, cy = B - 58 * k, mid = (a0 + a1) / 2;
        float ph = 64 * k, pw = 3 * step + 64 * k;   // four in the pill: previous, play, next, stop
        tmp.set(mid - pw / 2, cy - ph / 2, mid + pw / 2, cy + ph / 2);
        fill.setColor(Ui.alpha(0x26FFFFFF, te));
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
        if (watchRim == null) {
            watchRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            watchRim.setStyle(Paint.Style.STROKE);
        }
        watchRim.setStrokeWidth(1.5f * k);
        watchRim.setColor(Ui.alpha(0x66FFFFFF, te));
        c.drawRoundRect(tmp, ph / 2, ph / 2, watchRim);
        for (int i = 0; i < n; i++) {
            int kind = ctrlKinds[i];
            float cx;
            switch (kind) {
                case C_SHUFFLE: cx = mid - pw / 2 - side / 2 - 6 * k; break;
                case C_PREV: cx = mid - 1.5f * step; break;
                case C_NEXT: cx = mid + 0.5f * step; break;
                case C_STOP: cx = mid + 1.5f * step; break;
                case C_REPEAT: cx = mid + pw / 2 + side / 2 + 6 * k; break;
                default: cx = mid - 0.5f * step;   // play
            }
            float cf = Ui.clamp(ctrlFocus[i].value), rr = (kind == C_PLAY ? 27 : 24) * k * (1 + 0.1f * cf);
            if (cf > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, cf * te));
                c.drawCircle(cx, cy, rr, fill);
            }
            fill.setColor(Ui.alpha(blend(0xF2FFFFFF, 0xFF141414, cf), te));
            boolean active = (kind == C_SHUFFLE && np.shuffleOn) || (kind == C_REPEAT && np.repeatMode != 0);
            if (active && cf < 0.5f) fill.setColor(Ui.alpha(npcAccent, te));
            float u = (kind == C_PLAY ? 15 : kind == C_SHUFFLE || kind == C_REPEAT ? 11 : 12) * k;
            drawControlGlyph(c, kind == C_PLAY ? (np.playing ? 3 : 1) : kind, cx, cy, u);
        }
        c.restore();
    }

    /** The cover's average colour, brightened so it glows (white without a cover). */
    static int coverColor(Bitmap art) {
        if (art == null) return 0xFFFFFFFF;
        try {
            Bitmap one = Bitmap.createScaledBitmap(art.getConfig() == Bitmap.Config.HARDWARE
                    ? art.copy(Bitmap.Config.ARGB_8888, false) : art, 1, 1, true);
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(one.getPixel(0, 0), hsv);
            hsv[1] = Math.max(hsv[1], 0.45f);
            hsv[2] = Math.max(hsv[2], 0.8f);
            return android.graphics.Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static String time(float ms) {
        long s = Math.max(0, Math.round(ms / 1000));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Glass edition: the capsule fades out while the player opens beside the clock (it grows out of it). */
    private void drawNowPlaying(Canvas c) {
        float hide = 1 - Ui.clamp(npOpen[0].value * 1.8f);
        if (hide <= 0.01f) return;
        if (hide >= 0.99f) {
            drawNowPlayingCapsule(c);
            return;
        }
        int saved = c.saveLayerAlpha(0, 0, w, 240 * k, Math.round(255 * hide));
        drawNowPlayingCapsule(c);
        c.restoreToCount(saved);
    }

    /** Cover, title and artist, in a glass capsule like the buttons beside it. */
    private void drawNowPlayingCapsule(Canvas c) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible()) return;
        RectF first = topRects[3];
        float h = first.height(), right = first.left - 22 * k, rad = h / 2;
        // Paused, it collapses to a circle the size of the buttons; playing again, it opens back up.
        float e = Ui.clamp(npExpand[0].value), full = 440 * k;
        npRect.set(right - (h + (full - h) * e), first.top, right, first.bottom);
        RectF r = npRect;
        tmp.set(r);
        tmp.offset(0, 6 * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f)));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, r, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1);
        // Focused: white, like the round buttons beside it (OK then plays or pauses).
        float pf = Ui.clamp(playerFocus[0].value);
        if (pf > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, pf));
            c.drawRoundRect(r, rad, rad, fill);
        }
        // Inset enough that the cover's corners clear the capsule's round end.
        float pad = 10 * k, as = h - 2 * pad, ax = r.left + pad + 3 * k * e, ar = as * (0.5f - 0.22f * e);
        cell.set(ax, r.top + pad, ax + as, r.top + pad + as);
        if (np.art != null) {
            roundImage(c, np.art, cell, ar);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0x14000000, 0x24FFFFFF));
            c.drawRoundRect(cell, ar, ar, fill);
            Symbols.draw(getContext(), c, "play", cell.centerX(), cell.centerY(), 22 * k, tone(L_DIM, 0xB3FFFFFF));
        }
        // The equalizer: moving while it plays, still at its lowest when paused.
        float eqW = 22 * k;
        // Text and equalizer fade in once the capsule is mostly open, clipped to it while it moves.
        float te = Ui.clamp((e - 0.4f) / 0.6f);
        if (te <= 0.01f) return;
        float tx = cell.right + 16 * k, tr = r.right - rad * 0.75f - (eqW > 0 ? eqW + 14 * k : 0);
        float avail = full - (pad + 3 * k + as + 16 * k) - (rad * 0.75f + eqW + 14 * k);   // the open width: stable text
        c.save();
        c.clipRect(r);
        if (npTitle == null || npTitle.getTextSize() != 24 * k) {
            npTitle = new TextPaint(Ui.text(Ui.displaySemibold, 24 * k, 0xFFFFFFFF));
            npTitle.setLetterSpacing(-0.01f);
            npArtist = new TextPaint(Ui.text(Ui.regular, 19 * k, 0xFFFFFFFF));
            npArtist.setLetterSpacing(0.01f);
            npVersion = -1;
        }
        if (np.version != npVersion || avail != npWidth) {
            npVersion = np.version;
            npWidth = avail;
            npTitleText = android.text.TextUtils.ellipsize(np.title, npTitle, avail, android.text.TextUtils.TruncateAt.END);
            npArtistText = android.text.TextUtils.ellipsize(np.artist, npArtist, avail, android.text.TextUtils.TruncateAt.END);
        }
        npTitle.setColor(Ui.alpha(blend(tone(L_INK, 0xFAFFFFFF), L_INK, pf), te));
        npArtist.setColor(Ui.alpha(blend(tone(L_DIM, 0x99FFFFFF), L_DIM, pf), te));
        // Two lines centred as a block on the capsule.
        float t1 = Ui.cap(npTitle), t2 = Ui.cap(npArtist), lineGap = 11 * k, block = t1 + lineGap + t2;
        float base1 = r.centerY() - block / 2 + t1, base2 = base1 + lineGap + t2;
        c.drawText(npTitleText, 0, npTitleText.length(), tx, base1, npTitle);
        c.drawText(npArtistText, 0, npArtistText.length(), tx, base2, npArtist);
        if (eqW > 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(blend(tone(L_INK, 0xE6FFFFFF), L_INK, pf), te));
            float bw = 4 * k, gap = (eqW - 3 * bw) / 2, x = r.right - rad * 0.75f - eqW, bottom = r.centerY() + 9 * k;
            float t = SystemClock.uptimeMillis() / 1000f;
            for (int b = 0; b < 3; b++) {
                // Each bar bounces at its own pace (an animation: measuring the sound cost the TV too much); paused,
                // three still dots.
                float bh = !np.playing ? bw : (8 + 11 * Math.abs((float) Math.sin(t * EQ_SPEED[b] + EQ_PHASE[b]))) * k;
                tmp.set(x + b * (bw + gap), bottom - bh, x + b * (bw + gap) + bw, bottom);
                c.drawRoundRect(tmp, bw / 2, bw / 2, fill);
            }
        }
        c.restore();
    }

    /**
     * Previous, play/pause and next: three glass circles floating under the widget, like the round buttons, that rise
     * into place while the widget (or one of them) has focus. Focused: white with dark ink.
     */
    private void drawPlayerControls(Canvas c) {
        float show = Ui.clamp(ctrlShow[0].value) * (1 - Ui.clamp(npOpen[0].value * 2.5f));   // open: they're in the player
        NowPlaying np = nowPlaying;
        if (show <= 0.01f || np == null || !np.visible()) return;
        updateCtrlKinds(np);
        int n = ctrlKinds.length;
        // Anchored where the open capsule's centre is (it shrinks to the right as it collapses: following it dragged the
        // buttons sideways); they rise into it as they shrink and fade, the outer ones a little ahead of the middle one.
        float dia = 58 * k, gap = 16 * k, cx = npRect.right - 440 * k / 2, base = npRect.bottom + 22 * k + dia / 2;
        for (int i = 0; i < n; i++) {
            float side = Math.abs(i - (n - 1) / 2f) / Math.max(1, (n - 1) / 2f);   // 0 middle, 1 the ends
            float own = Ui.clamp((show - 0.25f * side) / (1 - 0.25f * side));
            if (own <= 0.01f) continue;
            float eased = own * own * (3 - 2 * own);
            float f = Ui.clamp(ctrlFocus[i].value), s = (0.6f + 0.4f * eased) * (1 + 0.1f * f), rad = dia / 2 * s;
            float x = cx + (i - (n - 1) / 2f) * (dia + gap);
            float cy = base - (1 - eased) * 34 * k;
            float showWas = show;
            show = eased * eased;   // the fade runs ahead of the motion: gone before it reaches the capsule
            cell.set(x - rad, cy - rad, x + rad, cy + rad);
            tmp.set(cell);
            tmp.offset(0, (5 + 7 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, (tone(0.1f, 0.3f) + tone(0.12f, 0.18f) * f) * show));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 10 * k, 7 * k,
                    tone(0.6f, 0.45f), show * (1 - f));
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f * show));
                c.drawCircle(x, cy, rad, fill);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f), show));
            int kind = ctrlKinds[i];
            boolean active = (kind == C_SHUFFLE && np.shuffleOn) || (kind == C_REPEAT && np.repeatMode != 0);
            if (active && f < 0.5f) fill.setColor(Ui.alpha(0xFF30D158, show));   // on: green, like the apps do
            drawControlGlyph(c, kind == C_PLAY ? (np.playing ? 3 : 1) : kind, x, cy, rad * 0.36f);
            show = showWas;
        }
    }

    private void updateCtrlKinds(NowPlaying np) {
        // Glass edition: previous, play, next and stop (to stop listening), shuffle and repeat on the sides.
        int n = 4 + (np.canShuffle() ? 1 : 0) + (np.canRepeat() ? 1 : 0);
        if (n == ctrlKinds.length) return;
        int[] k2 = new int[n];
        int i = 0;
        if (np.canShuffle()) k2[i++] = C_SHUFFLE;
        k2[i++] = C_PREV;
        k2[i++] = C_PLAY;
        k2[i++] = C_NEXT;
        k2[i++] = C_STOP;
        if (np.canRepeat()) k2[i] = C_REPEAT;
        ctrlKinds = k2;
        ctrlIndex = Math.min(ctrlIndex, n - 1);
    }

    private int indexOfKind(int kind) {
        for (int i = 0; i < ctrlKinds.length; i++) if (ctrlKinds[i] == kind) return i;
        return 0;
    }

    private Paint ctrlStroke;

    /**
     * 0 previous, 1 play, 2 next, 3 pause (filled), 4 shuffle, 5 repeat (stroked): centred on (x, y), `u` about half
     * their height, in `fill`'s colour.
     */
    private void drawControlGlyph(Canvas c, int which, float x, float y, float u) {
        glyphPath.reset();
        if (which == C_STOP) {   // stop: a rounded square
            tmp.set(x - u * 0.72f, y - u * 0.72f, x + u * 0.72f, y + u * 0.72f);
            c.drawRoundRect(tmp, u * 0.18f, u * 0.18f, fill);
            return;
        }
        if (which == C_SHUFFLE || which == C_REPEAT) {
            if (ctrlStroke == null) {
                ctrlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                ctrlStroke.setStyle(Paint.Style.STROKE);
                ctrlStroke.setStrokeCap(Paint.Cap.ROUND);
                ctrlStroke.setStrokeJoin(Paint.Join.ROUND);
            }
            ctrlStroke.setStrokeWidth(u * 0.24f);
            ctrlStroke.setColor(fill.getColor());
            if (which == C_SHUFFLE) {
                // Two crossing arrows.
                glyphPath.moveTo(x - u, y - u * 0.6f);
                glyphPath.cubicTo(x - u * 0.2f, y - u * 0.6f, x + u * 0.1f, y + u * 0.6f, x + u, y + u * 0.6f);
                glyphPath.moveTo(x - u, y + u * 0.6f);
                glyphPath.cubicTo(x - u * 0.2f, y + u * 0.6f, x + u * 0.1f, y - u * 0.6f, x + u, y - u * 0.6f);
                glyphPath.moveTo(x + u * 0.65f, y - u * 0.95f);
                glyphPath.lineTo(x + u, y - u * 0.6f);
                glyphPath.lineTo(x + u * 0.65f, y - u * 0.25f);
                glyphPath.moveTo(x + u * 0.65f, y + u * 0.25f);
                glyphPath.lineTo(x + u, y + u * 0.6f);
                glyphPath.lineTo(x + u * 0.65f, y + u * 0.95f);
            } else {
                // A loop with an arrow head.
                tmp.set(x - u, y - u * 0.6f, x + u, y + u * 0.6f);
                glyphPath.addRoundRect(tmp, u * 0.5f, u * 0.5f, Path.Direction.CW);
                glyphPath.moveTo(x + u * 0.05f, y - u * 0.95f);
                glyphPath.lineTo(x + u * 0.4f, y - u * 0.6f);
                glyphPath.lineTo(x + u * 0.05f, y - u * 0.25f);
            }
            c.drawPath(glyphPath, ctrlStroke);
            return;
        }
        switch (which) {
            case 1:
                glyphPath.moveTo(x - u * 0.7f, y - u);
                glyphPath.lineTo(x + u * 0.95f, y);
                glyphPath.lineTo(x - u * 0.7f, y + u);
                glyphPath.close();
                break;
            case 3:
                tmp.set(x - u * 0.8f, y - u, x - u * 0.22f, y + u);
                c.drawRoundRect(tmp, u * 0.15f, u * 0.15f, fill);
                tmp.set(x + u * 0.22f, y - u, x + u * 0.8f, y + u);
                c.drawRoundRect(tmp, u * 0.15f, u * 0.15f, fill);
                return;
            default: {
                float dir = which == 0 ? -1 : 1;   // previous points left, next points right
                glyphPath.moveTo(x - dir * u * 0.75f, y - u * 0.85f);
                glyphPath.lineTo(x + dir * u * 0.55f, y);
                glyphPath.lineTo(x - dir * u * 0.75f, y + u * 0.85f);
                glyphPath.close();
                float bx = x + dir * u * 0.62f;
                tmp.set(Math.min(bx, bx + dir * u * 0.28f), y - u * 0.85f, Math.max(bx, bx + dir * u * 0.28f), y + u * 0.85f);
                c.drawRoundRect(tmp, u * 0.1f, u * 0.1f, fill);
                break;
            }
        }
        c.drawPath(glyphPath, fill);
    }

    private final Path glyphPath = new Path();

    /** The account circle: glass like the buttons, the photo inside; focused, a white ring and the label. */
    private void drawAvatar(Canvas c) {
        if (!SHOW_AVATAR) return;
        if (!avatarLoaded) {
            avatarLoaded = true;
            avatar = loadAvatar();
        }
        RectF r = avatarRect;
        float f = Ui.clamp(avatarFocus[0].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
        cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1);
        float in = 4 * k * s;
        tmp.set(cell.left + in, cell.top + in, cell.right - in, cell.bottom - in);
        if (avatar != null) {
            roundImage(c, crispTile(avatar, tmp.width(), tmp.height()), tmp, tmp.width() / 2);
        } else {
            // A person: head and shoulders, in the buttons' ink.
            fill.setShader(null);
            fill.setColor(tone(L_INK, 0xEBFFFFFF));
            float cx = cell.centerX(), u = rad / 3.4f;
            c.drawCircle(cx, cell.centerY() - u * 0.55f, u * 0.82f, fill);
            tmp.set(cx - u * 1.55f, cell.centerY() + u * 0.55f, cx + u * 1.55f, cell.centerY() + u * 3.4f);
            c.drawArc(tmp, 180, 180, true, fill);
        }
        if (f > 0.01f) {
            rimPaint().setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cell.centerX(), cell.centerY(), rad - 1.5f * k, rimPaint());
            String label = "Tu cuenta";
            float half = lTip.measureText(label) / 2;
            lTip.setAlpha(Math.round(255 * f));
            c.drawText(label, Math.min(r.centerX(), CONTENT_R * k - half), r.bottom + (40 + 4 * f) * k, lTip);
        }
    }

    private Paint avatarRim;

    private Paint rimPaint() {
        if (avatarRim == null) {
            avatarRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            avatarRim.setStyle(Paint.Style.STROKE);
        }
        avatarRim.setStrokeWidth(3 * k);
        return avatarRim;
    }

    /** assets/profile.jpg, centre-cropped to a square: the person's own photo, since apps can't see the Google one. */
    private Bitmap loadAvatar() {
        try (java.io.InputStream in = getContext().getAssets().open("profile.jpg")) {
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b == null) return null;
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            return side > 256 ? Bitmap.createScaledBitmap(sq, 256, 256, true) : sq;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The magnifier, which grows down into a vertical pill of glass with the microphone under it while it has the
     * focus (one button for typing and for talking to the Assistant). False: not grown at all, drawn as the others.
     */
    private final RectF pillRect = new RectF();

    private boolean drawSearchPill(Canvas c, RectF r) {
        float e = Ui.clamp(searchPill[0].value), m = Ui.clamp(micFocus[0].value);
        if (e <= 0.01f) return false;
        float f = Ui.clamp(topFocus[3].value), s = 1 + 0.08f * e, rad = r.width() / 2 * s;
        float ext = (L_BUTTON + L_BUTTON_GAP) * k * e;
        float cx = r.centerX(), top = r.centerY() - rad, bottom = r.centerY() + rad + ext;
        tmp.set(cx - rad, top, cx + rad, bottom);
        RectF pill = pillRect;
        pill.set(tmp);
        tmp.offset(0, 10 * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.16f, 0.36f)));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, pill, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                tone(0.6f, 0.45f), 1);
        float cy1 = r.centerY(), cy2 = r.centerY() + ext, in = 5 * k;
        // The white focus sits on the magnifier or, one step down, on the microphone.
        float onSearch = f * (1 - m);
        fill.setShader(null);
        if (onSearch > 0.01f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, onSearch));
            c.drawCircle(cx, cy1, rad - in, fill);
        }
        if (m > 0.01f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, m));
            c.drawCircle(cx, cy2, rad - in, fill);
        }
        int light = tone(L_INK, 0xEBFFFFFF);
        Symbols.draw(getContext(), c, "search", cx, cy1, 27 * k * s, blend(light, L_INK, onSearch));
        Symbols.draw(getContext(), c, "mic", cx, cy2, 27 * k * s, Ui.alpha(blend(light, L_INK, m), Math.min(1, e * 1.5f)));
        // Its name on the left of the focused circle (under it is the edge of the buttons' layer).
        String label = m > 0.5f ? "Hablar con el Asistente" : "Buscar  ·  ↓ para hablar";
        lTip.setAlpha(Math.round(255 * f));
        Paint.Align was = lTip.getTextAlign();
        lTip.setTextAlign(Paint.Align.RIGHT);
        c.drawText(label, cx - rad - 18 * k, cy2 + Ui.halfCap(lTip), lTip);   // under the music capsule, not on it
        lTip.setTextAlign(was);
        return true;
    }

    // MARK: Glass edition: the sleep timer's panel (option T1), dropping from its button like the player

    private final Spring[] sleepOpen = {new Spring(0.28f, 0.76f, 0)};
    private final Spring[] sleepFocus = new Spring[8];
    {
        for (int i = 0; i < sleepFocus.length; i++) sleepFocus[i] = new Spring(0.3f, 0.85f, 0);
    }
    private int sleepRow = 1, sleepIdx, sleepSetIdx = -1;
    private static final int[] SLEEP_MIN = {15, 30, 45, 60, 120};
    private static final String[] SLEEP_TEXT = {"15 min", "30 min", "45 min", "1 h", "2 h"};
    private final RectF sleepCard = new RectF(), sleepMorph = new RectF();
    private TextPaint sleepTitle, sleepPill, sleepFoot;
    private final RectF[] sleepPills = new RectF[6];
    {
        for (int i = 0; i < sleepPills.length; i++) sleepPills[i] = new RectF();
    }

    /** The times on offer; "Fin del tema" too while a song with a length plays (what's left of it). */
    private int[] sleepChoices() {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible() || np.duration <= 0) return SLEEP_MIN;
        int[] out = java.util.Arrays.copyOf(SLEEP_MIN, SLEEP_MIN.length + 1);
        out[SLEEP_MIN.length] = -1;
        return out;
    }

    private int songMinutesLeft() {
        NowPlaying np = nowPlaying;
        if (np == null || np.duration <= 0) return 0;
        long left = (long) (np.duration * (1 - np.progress()));
        return (int) Math.max(1, (left + 59_999) / 60_000);
    }

    private int sleepIndexOf(int minutes) {
        for (int i = 0; i < SLEEP_MIN.length; i++) if (SLEEP_MIN[i] >= minutes) return i;
        return SLEEP_MIN.length - 1;
    }

    private int sleepButton() {
        for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).key.equals("sleep")) return 3 + i;
        return 3;
    }

    /** OK in the panel: the switch turns it on (with the marked time) or off; a time sets it and turns it on. */
    private void sleepOk() {
        Context ctx = getContext();
        int[] ch = sleepChoices();
        int pick = ch[Math.min(sleepIdx, ch.length - 1)];
        int minutes = pick < 0 ? songMinutesLeft() : pick;
        if (sleepRow == 0) minutes = SleepTimer.minutesLeft(ctx) > 0 ? 0 : minutes;
        SleepTimer.set(ctx, minutes);
        sleepSetIdx = minutes == 0 ? -1 : sleepIdx;
        refreshTopState();
        statusDirty = heroDirty = true;
        click.open();
        wake();
    }

    private void drawSleepPanel(Canvas c) {
        float e = sleepOpen[0].value;
        if (e <= 0.01f) return;
        RectF btn = topRects[sleepButton()];
        float right = CONTENT_R * k, top = topRects[3].bottom + 24 * k, w = 640 * k, h = 252 * k;
        sleepCard.set(right - w, top, right, top + h);
        sleepMorph.set(lerp(btn.left, sleepCard.left, e), lerp(btn.top, sleepCard.top, e),
                lerp(btn.right, sleepCard.right, e), lerp(btn.bottom, sleepCard.bottom, e));
        float rad = lerp(btn.height() / 2, 30 * k, Ui.clamp(e)), ge = Ui.clamp(e * 3);
        tmp.set(sleepMorph);
        tmp.offset(0, 18 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.45f * ge));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * 1.6f * k, shadow);
        drawerGlass(c, sleepMorph, rad, 0.72f, 0x1A000000, 8 * k, ge);
        fill.setShader(null);
        fill.setColor(Ui.alpha(BUBBLE_FILL, ge));
        c.drawRoundRect(sleepMorph, rad, rad, fill);
        float te = Ui.clamp((e - 0.5f) / 0.5f);
        if (te <= 0.01f) return;
        if (sleepTitle == null || sleepTitle.getTextSize() != 28 * k) {
            sleepTitle = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
            sleepPill = new TextPaint(Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF));
            sleepPill.setTextAlign(Paint.Align.CENTER);
            sleepFoot = new TextPaint(Ui.text(Ui.medium, 20 * k, 0xCCFFFFFF));
        }
        c.save();
        c.clipRect(sleepMorph);
        float pad = 28 * k, L = sleepCard.left + pad, R = sleepCard.right - pad, T = sleepCard.top;
        int left = SleepTimer.minutesLeft(getContext());
        boolean on = left > 0;
        // The switch: white when on (the knob dark), glass when off (the knob white). No colour.
        float rowY = T + 52 * k;
        sleepTitle.setAlpha(Math.round(255 * te));
        c.drawText("Apagar la tele", L, rowY + Ui.halfCap(sleepTitle), sleepTitle);
        float sw = 76 * k, sh = 40 * k, sx = R - sw;
        tmp.set(sx, rowY - sh / 2, R, rowY + sh / 2);
        fill.setColor(Ui.alpha(on ? 0xFFFFFFFF : 0x38FFFFFF, te));
        c.drawRoundRect(tmp, sh / 2, sh / 2, fill);
        float kr = sh / 2 - 4 * k, kx = on ? R - sh / 2 : sx + sh / 2;
        fill.setColor(Ui.alpha(on ? 0xFF1C1C1E : 0xFFFFFFFF, te));
        c.drawCircle(kx, rowY, kr, fill);
        float f0 = Ui.clamp(sleepFocus[0].value);
        if (f0 > 0.01f) {
            focusRing.setStrokeWidth(3.5f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f0 * te));
            tmp.inset(-7 * k, -7 * k);
            c.drawRoundRect(tmp, sh / 2 + 7 * k, sh / 2 + 7 * k, focusRing);
        }
        // The times, as pills: the one set now white, the focused one ringed.
        int[] ch = sleepChoices();
        float py = T + 128 * k, ph = 48 * k, gap = 8 * k, x = L;
        for (int i = 0; i < ch.length; i++) {
            String t = ch[i] < 0 ? "Fin del tema" : SLEEP_TEXT[i];
            float pw = sleepPill.measureText(t) + 28 * k;
            sleepPills[i].set(x, py - ph / 2, x + pw, py + ph / 2);
            x += pw + gap;
        }
        float shift = Math.max(0, x - gap - R);   // too wide: the row slides so the focused one shows
        for (int i = 0; i < ch.length; i++) {
            RectF r = sleepPills[i];
            r.offset(-shift * (sleepIdx / (float) Math.max(1, ch.length - 1)), 0);
            boolean set = on && i == sleepSetIdx;
            float f = Ui.clamp(sleepFocus[1 + i].value);
            fill.setColor(Ui.alpha(set ? 0xFFFFFFFF : 0x29FFFFFF, te));
            c.drawRoundRect(r, ph / 2, ph / 2, fill);
            sleepPill.setColor(Ui.alpha(set ? 0xFF141414 : 0xFFFFFFFF, te));
            c.drawText(ch[i] < 0 ? "Fin del tema" : SLEEP_TEXT[i], r.centerX(), r.centerY() + Ui.halfCap(sleepPill), sleepPill);
            if (f > 0.01f) {
                focusRing.setStrokeWidth(3.5f * k);
                focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f * te));
                tmp.set(r);
                tmp.inset(-6 * k, -6 * k);
                c.drawRoundRect(tmp, ph / 2 + 6 * k, ph / 2 + 6 * k, focusRing);
            }
        }
        // What it will do.
        String foot;
        if (on) {
            String at = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(System.currentTimeMillis() + left * 60_000L));
            foot = "Se apaga a las " + at + "  ·  quedan " + left + " min";
        } else {
            foot = "Desactivado  ·  elegí un tiempo";
        }
        sleepFoot.setAlpha(Math.round(204 * te));
        c.drawText(foot, L, T + 210 * k, sleepFoot);
        c.restore();
    }

    private boolean wifiOn = true;
    private int sleepLeft;
    private TextPaint badge;
    private String wifiText = "Wi-Fi";

    private TextPaint badgePaint() {
        if (badge == null || badge.getTextSize() != 15 * k) {
            badge = new TextPaint(Ui.text(Ui.semibold, 15 * k, 0xFF141414));
            badge.setTextAlign(Paint.Align.CENTER);
        }
        return badge;
    }

    /** The round button's name under it, with its state (Wi-Fi connected or not, the timer's minutes). */
    private String buttonLabel(HomeApps.Item b) {
        switch (b.key) {
            case "wifi": return wifiText;
            case "sleep": return sleepLeft > 0 ? "Se apaga en " + sleepLeft + " min · OK cambia" : "Apagar en… · OK: 30 min";
            case "voice": return "Hablar con el Asistente";
            default: return b.label;
        }
    }

    /** The Wi-Fi's state and the timer's minutes for their buttons (checked as the buttons are drawn; cheap). */
    private void refreshTopState() {
        boolean on = false, eth = false;
        try {
            android.net.ConnectivityManager cm = getContext().getSystemService(android.net.ConnectivityManager.class);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
            on = nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            eth = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (RuntimeException ignored) {
        }
        wifiOn = on;
        wifiText = !on ? "Sin conexión" : eth ? "Cable conectado" : "Wi-Fi conectado";
        sleepLeft = SleepTimer.minutesLeft(getContext());
    }

    /** OK on one of the new round buttons; false for the ones HomeApps opens (Ajustes). */
    private boolean topButton(String key) {
        Context ctx = getContext();
        android.content.Intent i;
        switch (key) {
            case "search":   // the Google Assistant, typing
                i = new android.content.Intent("android.search.action.GLOBAL_SEARCH").putExtra("search_type", 2);
                break;
            case "voice":    // the Google Assistant, listening
                i = new android.content.Intent(android.content.Intent.ACTION_ASSIST).putExtra("search_type", 1)
                        .putExtra("android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD", false);
                break;
            case "wifi":
                i = new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                break;
            case "sleep": {   // its panel drops down from the button
                zone = Z_SLEEP;
                sleepRow = 1;
                sleepIdx = Math.max(0, sleepIndexOf(SleepTimer.minutesLeft(ctx)));
                sleepSetIdx = SleepTimer.minutesLeft(ctx) > 0 ? sleepIdx : -1;   // the running time shows white
                retarget(false);
                click.open();
                wake();
                return true;
            }
            default:
                return false;
        }
        click.open();
        try {
            ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {   // no search screen: the Assistant's own
                ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_ASSIST)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e2) {
                AppLog.w("top button " + key + ": " + e2.getMessage());
            }
        }
        return true;
    }

    private void drawLightButtons(Canvas c) {
        refreshTopState();
        drawAvatar(c);
        drawNowPlaying(c);
        drawPlayerControls(c);
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            HomeApps.Item b = lightButtons.get(i);
            if (b.key.equals("search") && drawSearchPill(c, r)) continue;
            String symbol = b.key.equals("wifi") ? (wifiOn ? "wifi" : "wifi_off") : b.symbol;
            int ink = blend(tone(L_INK, 0xEBFFFFFF), L_INK, f);
            if (b.key.equals("wifi") && !wifiOn) ink = blend(0xFFFF6961, L_INK, f);   // no connection: red
            Symbols.draw(getContext(), c, symbol, cell.centerX(), cell.centerY(), 27 * k * s, ink);
            if (b.key.equals("sleep") && sleepLeft > 0 && f < 0.99f) {   // the timer running: its minutes on a dot
                String m = String.valueOf(sleepLeft);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 1 - f));
                float bx = cell.right - 6 * k, by = cell.top + 6 * k, br = 15 * k;
                c.drawCircle(bx, by, br, fill);
                badgePaint().setAlpha(Math.round(255 * (1 - f)));
                c.drawText(m, bx, by + Ui.halfCap(badgePaint()), badgePaint());
            }
            if (f > 0.01f) {
                String label = buttonLabel(b);
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private String liveTvText = "";
    private long liveTvMinute = -1;

    /** "  ·  Racing vs Boca · 21:30" while a game is on or starts within 12 hours (made once a minute). */
    private String liveTvMatch() {
        long now = System.currentTimeMillis(), minute = now / 60_000;
        if (minute == liveTvMinute) return liveTvText;
        liveTvMinute = minute;
        liveTvText = "";
        if (Prefs.futbol()) {
            List<Futbol.Match> shown = Futbol.shownNow(data.matches, now);
            if (!shown.isEmpty()) {
                Futbol.Match m = shown.get(0);
                if (m.live() || m.kickedOff(now) || (!m.done() && m.start - now < 12 * 3_600_000L)) {
                    liveTvText = "  ·  " + Futbol.oneLine(m);
                }
            }
        }
        return liveTvText;
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (iconDock() && !watchShown()) {   // the panel may still be on its way: bring it now
                    handler.removeCallbacks(watchDebounce);
                    pickWatch();
                    if (watchSwap) showWatch(watchWaiting);
                    if (watch != null) watchShow[0].snap(1);
                }
                if (iconDock() && !watchShown()) return true;   // nothing to open: ↑ stays on the wheel
                if (watchShown()) {
                    zone = Z_WATCH;   // the bubble rises from the app, on its first card
                    watchIndex = 0;
                    watchRow = spotRow() ? 1 : 0;
                    actIndex = 0;
                    layoutWatch();
                    placeWatch();
                } else if (cards.isEmpty() && recentShown()) {
                    zone = Z_RECENT;
                    recentIndex = nearest(recentRects, recent.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                } else if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_WATCH: {
                // Moves as it looks. Íconos: the list, the playlists in a row under it. Tarjetas: the list, the
                // playlists in a 2×2 to its right.
                int n = Math.min(3, watch.items.size()), ns = panelSmallCount();
                boolean wide = !iconDock();
                if (spotRow()) {   // Spotify on the cards: now playing, the playlists, the library, in one row
                    if (dx != 0) {
                        int nx = actIndex + dx;
                        if (nx < 0 || nx >= SPOT_ITEMS) return true;
                        actIndex = nx;
                    } else if (dy < 0) {
                        zone = Z_TOP;
                        topIndex = 3;
                    } else zone = Z_SHELF;
                    break;
                }
                if (watchRow == 0 && wide) {   // the cards in a row
                    if (dx != 0) {
                        int nx = watchIndex + dx;
                        if (nx >= 0 && nx < n) watchIndex = nx;
                        else if (dx > 0 && ns > 0) {
                            watchRow = 1;
                            actIndex = 0;
                        } else return true;
                    } else if (dy < 0) {
                        zone = Z_TOP;
                        topIndex = 3;
                    } else zone = Z_SHELF;
                } else if (watchRow == 0) {
                    if (dy != 0) {
                        int nx = watchIndex + dy;
                        if (nx >= 0 && nx < n) watchIndex = nx;
                        else if (dy < 0) {
                            zone = Z_TOP;
                            topIndex = 3;
                        } else if (!wide && ns > 0) {
                            watchRow = 1;
                            actIndex = 0;
                        } else zone = Z_SHELF;
                    } else if (dx > 0 && ns > 0) {   // → to the playlists, on the nearest one
                        watchRow = 1;
                        actIndex = wide ? Math.min(ns - 1, watchIndex >= 2 ? 2 : 0) : 0;
                    } else return true;
                } else if (wide) {
                    int col = actIndex % 2, row = actIndex / 2;
                    if (dx != 0) {
                        int nc = col + dx;
                        if (nc < 0) {   // ← from the left column: back to the last card
                            watchRow = 0;
                            watchIndex = n - 1;
                        } else if (nc > 1 || row * 2 + nc >= ns) return true;
                        else actIndex = row * 2 + nc;
                    } else {
                        int nr = row + dy;
                        if (nr < 0) {
                            zone = Z_TOP;
                            topIndex = 3;
                        } else if (nr * 2 + col >= ns) zone = Z_SHELF;
                        else actIndex = nr * 2 + col;
                    }
                } else {
                    if (dx != 0) {
                        int nx = actIndex + dx;
                        if (nx < 0) {   // ← from the first: back to the list
                            watchRow = 0;
                            watchIndex = n - 1;
                        } else if (nx >= ns) return true;
                        else actIndex = nx;
                    } else if (dy < 0) {
                        watchRow = 0;
                        watchIndex = n - 1;
                    } else zone = Z_SHELF;
                }
                break;
            }
            case Z_RECENT:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(recent.size() - 1, recentIndex + dx));
                    if (next == recentIndex) return true;
                    recentIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = 3;
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, recentRects[recentIndex].centerX());
                }
                break;
            case Z_WXBTN:
                if (wxOpen) {
                    if (dx < 0) wxOpen = false;   // ← closes the panel
                    else return true;
                } else if (dx > 0) {
                    if (nowPlaying != null && nowPlaying.visible()) zone = Z_PLAYER;
                    else {
                        zone = Z_TOP;
                        topIndex = 3;
                    }
                } else if (dy > 0) zone = Z_SHELF;
                else return true;
                break;
            case Z_PLAYER:
                if (dx > 0) {
                    zone = Z_TOP;
                    topIndex = 3;
                } else if (dx < 0 && picksOn()) {
                    zone = Z_TOP;
                    topIndex = 2;
                    selectTab(2);
                } else if (dx < 0 && Prefs.weather()) {
                    zone = Z_WXBTN;   // left of the music: the weather under the clock
                } else if (dy > 0) {
                    if (npCollapsed) {
                        zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                    } else if (nowPlaying != null && nowPlaying.canSeek()) {
                        zone = Z_PLAYER_BAR;   // down onto its progress bar, then its buttons
                    } else {
                        zone = Z_PLAYER_CTRL;   // down into the controls, on play/pause
                        ctrlIndex = indexOfKind(C_PLAY);
                    }
                } else if (dy < 0) {
                    idleFrom = Z_PLAYER;
                    zone = Z_IDLE;
                } else {
                    return true;
                }
                break;
            case Z_SLEEP: {
                int n = sleepChoices().length;
                if (sleepRow == 1 && dx != 0) {
                    int next = Math.max(0, Math.min(n - 1, sleepIdx + dx));
                    if (next == sleepIdx) return true;
                    sleepIdx = next;
                } else if (dx != 0) {
                    return true;
                } else if (dy < 0) {
                    if (sleepRow == 1) sleepRow = 0;
                    else {
                        zone = Z_TOP;
                        topIndex = sleepButton();
                    }
                } else if (sleepRow == 0) {
                    sleepRow = 1;
                } else {
                    zone = Z_SHELF;
                }
                break;
            }
            case Z_PLAYER_BAR:
                if (dx != 0) {
                    scrub(dx);   // the playhead moves; the focus stays on the bar
                    return true;
                } else if (dy < 0) {
                    zone = Z_PLAYER;
                } else {
                    zone = Z_PLAYER_CTRL;
                    ctrlIndex = indexOfKind(C_PLAY);
                }
                break;
            case Z_PLAYER_CTRL:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(ctrlKinds.length - 1, ctrlIndex + dx));
                    if (next == ctrlIndex) return true;
                    ctrlIndex = next;
                } else if (dy < 0) {
                    zone = nowPlaying != null && nowPlaying.canSeek() ? Z_PLAYER_BAR : Z_PLAYER;
                } else {
                    zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                }
                break;
            case Z_AVATAR:
                if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = TOP_LAST;
                } else if (dy > 0) {
                    zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                } else {
                    return true;
                }
                break;
            case Z_TOP:
                // The search pill: down to the microphone under the magnifier, up back to it.
                if (topIndex == 3 && dy > 0 && !inMic) {
                    inMic = true;
                    break;
                }
                if (topIndex == 3 && dy < 0 && inMic) {
                    inMic = false;
                    break;
                }
                if (dx != 0) inMic = false;
                if (dx > 0 && topIndex == TOP_LAST) {
                    if (!SHOW_AVATAR) return true;
                    zone = Z_AVATAR;
                    break;
                }
                // Left from the first round button: the now-playing widget, when it shows.
                if (dx < 0 && topIndex == 3 && nowPlaying != null && nowPlaying.visible()) {
                    zone = Z_PLAYER;
                    break;
                }
                if (dx < 0 && topIndex == 3 && !picksOn() && Prefs.weather()) {
                    zone = Z_WXBTN;   // no music: left of the first button is the weather
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : 3, Math.min(TOP_LAST, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty() && watchShown()) {
                        zone = Z_WATCH;
                        watchIndex = 0;
                        watchRow = spotRow() ? 1 : 0;
                        actIndex = 0;
                    } else if (cards.isEmpty() && recentShown()) {
                        zone = Z_RECENT;
                        recentIndex = nearest(recentRects, recent.size(), topRects[topIndex].centerX());
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (dy < 0) {
                    idleFrom = Z_TOP;
                    zone = Z_IDLE;
                } else {
                    return true;
                }
                break;
            case Z_IDLE:
                if (dy < 0 && idleFrom == Z_SHELF) {   // nothing focused, ↑: straight to the buttons at the top
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = idleFrom;   // otherwise the first arrow only brings the focus back
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
