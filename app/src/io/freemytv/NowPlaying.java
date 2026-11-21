package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * What is playing now (Spotify, YouTube Music or any app with a media session): title, artist, cover and position,
 * for the widget at the top right of the home screen. Needs NowPlayingListener's notification access; without it,
 * nothing shows. Runs on the UI thread: Android delivers the callbacks there, and they are rare.
 */
final class NowPlaying implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener { void onNowPlaying(); }

    /** Video apps: their videos aren't songs, so the widget (music) leaves them out. */
    private static final java.util.Set<String> VIDEO_APPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow", "com.tcl.tv", "com.google.android.videos",
            "com.apple.atve.androidtv.appletv", "com.crunchyroll.crunchyroid", "tv.pluto.android"));

    /** Also this TV's own video apps (LocalApps). */
    private static boolean isVideo(String pkg) {
        return VIDEO_APPS.contains(pkg) || LocalApps.video(pkg);
    }

    /** A paused song stays on screen this long, then the widget goes. */
    private static final long PAUSED_SHOWN = 20 * 60_000L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap raw;   // the cover as the app sent it (art and artLarge are copies)
    private long songAt;
    private final Runnable reread = this::read;
    private int[] startSig;   // the first picture of this song
    private long startSigAt;
    private boolean startReplaced;
    /** Pictures learnt to be the app's logo (sent in place of a cover between songs); kept in a file. */
    private static final java.util.List<int[]> logos = new java.util.ArrayList<>();
    private static boolean logosRead;

    /** An 8×8 thumbnail's colours: enough to tell one picture from another. */
    private static int[] signature(Bitmap b) {
        try {
            Bitmap src = b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
            Bitmap t = Bitmap.createScaledBitmap(src, 8, 8, true);
            int[] px = new int[64];
            t.getPixels(px, 0, 8, 0, 0, 8, 8);
            return px;
        } catch (RuntimeException e) {
            return new int[64];
        }
    }

    private static boolean similar(int[] x, int[] y) {
        long d = 0;
        for (int i = 0; i < 64; i++) {
            d += Math.abs(((x[i] >> 16) & 255) - ((y[i] >> 16) & 255)) + Math.abs(((x[i] >> 8) & 255) - ((y[i] >> 8) & 255))
                    + Math.abs((x[i] & 255) - (y[i] & 255));
        }
        return d / 192 < 10;   // under 10 levels apart on average
    }

    private boolean isLogo(Bitmap b) {
        readLogos();
        if (logos.isEmpty()) return false;
        int[] sg = signature(b);
        for (int[] l : logos) if (similar(sg, l)) return true;
        return false;
    }

    private void rememberLogo(int[] sg) {
        readLogos();
        for (int[] l : logos) if (similar(sg, l)) return;
        logos.add(sg);
        StringBuilder out = new StringBuilder();
        for (int[] l : logos) {
            for (int v : l) out.append(v).append(',');
            out.append('\n');
        }
        try {
            java.nio.file.Files.write(new java.io.File(context.getFilesDir(), "cover-logos2.txt").toPath(), out.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private void readLogos() {
        if (logosRead) return;
        logosRead = true;
        try {
            java.io.File old = new java.io.File(context.getFilesDir(), "cover-logos.txt");
            if (old.exists()) old.delete();   // learnt by the old rule, which took repeated album covers for logos
            java.io.File f = new java.io.File(context.getFilesDir(), "cover-logos2.txt");
            if (!f.exists()) return;
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
                String[] parts = line.split(",");
                if (parts.length < 64) continue;
                int[] sg = new int[64];
                for (int i = 0; i < 64; i++) sg[i] = Integer.parseInt(parts[i].trim());
                logos.add(sg);
            }
        } catch (Exception ignored) {
        }
    }
    private MediaSessionManager msm;
    private MediaController controller;

    String title = "", artist = "", pkg = "";
    Bitmap art, artLarge;
    long duration, position, positionAt;   // positionAt: SystemClock.elapsedRealtime() of `position`
    float speed;
    boolean playing;
    long pausedAt;
    /** The session's PlaybackState actions: what this app lets others do (shuffle and repeat are optional). */
    long actions;
    boolean shuffleOn;
    int repeatMode;   // 0 off, 1 one, 2 all (as MediaSessionCompat)
    /** Bumped on every change: the screen re-measures its text only then. */
    int version;   // moves when the song or its cover changes (not on play, pause or position)
    int artVersion;   // moves only when the cover changes

    NowPlaying(Context c, Listener l) {
        context = c;
        listener = l;
    }

    void start() {
        try {
            msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(context, NowPlayingListener.class);
            msm.addOnActiveSessionsChangedListener(this, me, main);
            onActiveSessionsChanged(msm.getActiveSessions(me));
        } catch (SecurityException | NullPointerException e) {
            AppLog.w("now playing: no notification access (" + e.getMessage() + ")");
            msm = null;
        }
    }

    void stop() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
        msm = null;
        watch(null);
    }

    /**
     * Shuffle and repeat: MediaSessionCompat's ACTION_SET_SHUFFLE_MODE (1 << 21) and ACTION_SET_REPEAT_MODE (1 << 18),
     * sent as its custom actions. Spotify takes both while a playlist or album plays (not a single song); the buttons
     * show only while the app offers them.
     */
    boolean canShuffle() { return (actions & (1L << 21)) != 0; }
    boolean canRepeat() { return (actions & (1L << 18)) != 0; }

    void shuffle() {
        shuffleOn = !shuffleOn;
        custom("SET_SHUFFLE_MODE", "ARGUMENT_SHUFFLE_MODE", shuffleOn ? 1 : 0);
    }

    void repeat() {
        repeatMode = repeatMode == 0 ? 2 : repeatMode == 2 ? 1 : 0;   // off -> all -> one -> off
        custom("SET_REPEAT_MODE", "ARGUMENT_REPEAT_MODE", repeatMode);
    }

    private void custom(String action, String argument, int value) {
        MediaController c = controller;
        if (c == null) return;
        android.os.Bundle b = new android.os.Bundle();
        b.putInt("android.support.v4.media.session.action." + argument, value);
        try {
            c.getTransportControls().sendCustomAction("android.support.v4.media.session.action." + action, b);
        } catch (RuntimeException ignored) {
        }
        listener.onNowPlaying();
    }

    /** Whether the app lets others move its playhead (Glass edition: the player's bar). */
    boolean canSeek() {
        return controller != null && (actions & PlaybackState.ACTION_SEEK_TO) != 0 && duration > 0;
    }

    /** Moves the playhead to `ms`; shown at once (the app's own update follows). */
    void seekTo(long ms) {
        MediaController c = controller;
        if (c == null) return;
        try {
            c.getTransportControls().seekTo(ms);
            position = ms;
            positionAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {
            AppLog.w("now playing seek: " + e.getMessage());
        }
    }

    void previous() {
        MediaController c = controller;
        if (c != null) try { c.getTransportControls().skipToPrevious(); } catch (RuntimeException ignored) {}
    }

    void next() {
        MediaController c = controller;
        if (c != null) try { c.getTransportControls().skipToNext(); } catch (RuntimeException ignored) {}
    }

    /** Play/pause: pause what plays, play what's paused. */
    /** Asks the app playing now (when it's `pkg`) to find and play a song; false when there's no such session. */
    boolean playFromSearch(String pkg, String query) {
        MediaController c = controller;
        if (c == null || !pkg.equals(c.getPackageName())) return false;
        try {
            c.getTransportControls().playFromSearch(query, new android.os.Bundle());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void toggle() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if (playing) c.getTransportControls().pause();
            else c.getTransportControls().play();
        } catch (RuntimeException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    /**
     * Stopped from the widget: hidden until something plays again. Spotify only pauses, so its song would come back
     * (in the screensaver, which has its own NowPlaying, or after Home was closed): the stopped song is kept in a file
     * every process reads, and dropped once anything plays.
     */
    private boolean dismissed;
    private static final String STOPPED = "now-playing-stopped.txt";

    /** The stop button: the app stops (pausing where it can't), and the widget goes. */
    void stopPlayback() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if ((actions & PlaybackState.ACTION_STOP) != 0) c.getTransportControls().stop();
            else c.getTransportControls().pause();
        } catch (RuntimeException ignored) {
        }
        dismissed = true;
        try {
            java.nio.file.Files.write(stoppedFile().toPath(), (pkg + "\n" + title).getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w("now playing: " + e.getMessage());
        }
        listener.onNowPlaying();
    }

    private java.io.File stoppedFile() {
        return new java.io.File(context.getFilesDir(), STOPPED);
    }

    /** The song stopped from the widget (in any process), or "" for none. */
    private String stopped() {
        try {
            return new String(java.nio.file.Files.readAllBytes(stoppedFile().toPath()), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** In the screensaver: only a song that plays (a paused one there is old news). */
    boolean playingVisible() {
        return playing && visible();
    }

    boolean visible() {
        if (title.isEmpty() || dismissed) return false;
        return playing || SystemClock.elapsedRealtime() - pausedAt < PAUSED_SHOWN;
    }

    /** 0 to 1, moving on its own while playing. */
    float progress() {
        if (duration <= 0) return 0;
        long pos = position;
        if (playing) pos += (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
        return Math.max(0f, Math.min(1f, pos / (float) duration));
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            // The one playing; else the first that has a song loaded (a paused Spotify).
            for (MediaController m : list) {
                if (isVideo(m.getPackageName())) continue;
                PlaybackState s = m.getPlaybackState();
                if (s != null && playingState(s.getState())) { pick = m; break; }
            }
            if (pick == null) {
                for (MediaController m : list) {
                    if (!isVideo(m.getPackageName()) && m.getMetadata() != null) { pick = m; break; }
                }
            }
        }
        watch(pick);
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { read(); }
        @Override public void onSessionDestroyed() { watch(null); }
    };

    private void watch(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        if (c != null) c.registerCallback(callback, main);
        read();
    }

    private void read() {
        MediaController c = controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState s = c == null ? null : c.getPlaybackState();
        String t = "", a = "";
        Bitmap b = null;
        long d = 0;
        if (m != null) {
            t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            boolean newSong = !t.equals(title) || !a.equals(artist);
            long now = SystemClock.elapsedRealtime();
            if (newSong) {
                songAt = now;
                startSig = null;
                startReplaced = false;
            }
            if (b != null && b != raw && (startSig == null || (!startReplaced && now - startSigAt < 8000))) {
                // The first picture of a song replaced by another within seconds: that first one was the app's logo.
                int[] sg = signature(b);
                if (startSig == null) {
                    startSig = sg;
                    startSigAt = now;
                } else if (!startReplaced && now - startSigAt < 8000 && !similar(sg, startSig)) {
                    startReplaced = true;
                    rememberLogo(startSig);
                }
            }
            if (b != null && isLogo(b)) b = null;   // the logo: as if no cover came yet
            if (b == null) {
                // No cover yet (Spotify sends its logo first on a new song): the last cover stays while the new one
                // comes, up to 15 s; never the logo while there's a cover to show.
                if (raw != null && now - songAt < 15000) {
                    b = raw;
                    main.removeCallbacks(reread);
                    main.postDelayed(reread, 15100 - (now - songAt));
                } else if (raw == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            d = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        boolean wasPlaying = playing;
        playing = s != null && playingState(s.getState());
        if (wasPlaying && !playing) pausedAt = SystemClock.elapsedRealtime();
        // First seen paused (a new process, the screensaver): when it paused, from the state, not now.
        if (!playing && pausedAt == 0) {
            long at = s == null ? 0 : s.getLastPositionUpdateTime();
            pausedAt = at > 0 && at <= SystemClock.elapsedRealtime() ? at : SystemClock.elapsedRealtime();
        }
        if (s != null && playingState(s.getState())) {   // playing again: back on screen, everywhere
            dismissed = false;
            if (stoppedFile().exists()) stoppedFile().delete();
        }
        actions = s == null ? 0 : s.getActions();
        position = s == null ? 0 : s.getPosition();
        positionAt = s == null ? SystemClock.elapsedRealtime() : s.getLastPositionUpdateTime();
        if (positionAt <= 0) positionAt = SystemClock.elapsedRealtime();
        speed = s == null ? 0 : (s.getPlaybackSpeed() == 0 ? 1 : s.getPlaybackSpeed());
        duration = d;
        pkg = c == null ? "" : c.getPackageName();
        boolean coverChanged = b != raw && !sameCover(b, raw);
        if (!t.equals(title) || !a.equals(artist) || coverChanged) {
            title = t;
            artist = a;
            if (coverChanged) {
                art = b == null ? null : square(b, 160);
                // Glass edition: the open player shows the cover big, so it keeps a sharper copy too (up to 512 px).
                artLarge = b == null ? null : square(b, Math.min(512, Math.min(b.getWidth(), b.getHeight())));
                artVersion++;
            }
            version++;
        }
        if (b != null) raw = b;
        else if (coverChanged) raw = null;
        if (!playing && !dismissed && !title.isEmpty()) dismissed = stopped().equals(pkg + "\n" + title);
        listener.onNowPlaying();
    }

    /** Between songs Spotify reports buffering or skipping for a moment: that still counts as playing. */
    private static boolean playingState(int st) {
        switch (st) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return true;
            default:
                return false;
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    /** The same picture sent again: every read parcels a new copy of it. */
    private static boolean sameCover(Bitmap x, Bitmap y) {
        if (x == null || y == null) return x == y;
        if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) return false;
        try {
            return x.sameAs(y);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The cover, center-cropped to a square of `size` px: drawn at that size, it stays sharp and cheap. */
    private static Bitmap square(Bitmap b, int size) {
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        return s == size ? crop : Bitmap.createScaledBitmap(crop, size, size, true);
    }
}
