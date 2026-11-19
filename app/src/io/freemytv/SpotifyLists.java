package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass edition: Spotify's popular playlists for the app's panel. The TV doesn't tell which playlists are popular,
 * so this is a fixed list of Spotify's own; their covers come from Spotify's public oEmbed page (no account, no key).
 * OK opens the playlist in Spotify.
 */
final class SpotifyLists {
    static final String PKG = "com.spotify.tv.android";
    static final String[] IDS = {"37i9dQZEVXbMMy2roB9myp", "37i9dQZF1DXcBWIGoYBM5M", "37i9dQZF1DX10zKzsJ2jva",
            "37i9dQZEVXbMDoHDwVN2tF", "37i9dQZF1DWY7IeIP1cdjF"};
    static final String[] NAMES = {"Top 50 Argentina", "Today's Top Hits", "Viva Latino", "Top 50 Global",
            "Baila Reggaeton"};
    static final Bitmap[] covers = new Bitmap[IDS.length];
    private static boolean asked;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private SpotifyLists() {}

    /** Fetches the covers once (in the background); `ready` runs on the worker after each one arrives. */
    static synchronized void load(Runnable ready) {
        if (asked) return;
        asked = true;
        WORKER.execute(() -> {
            for (int i = 0; i < IDS.length; i++) {
                try {
                    String json = read("https://open.spotify.com/oembed?url=https://open.spotify.com/playlist/" + IDS[i]);
                    String img = new org.json.JSONObject(json).optString("thumbnail_url", "");
                    if (img.isEmpty()) continue;
                    HttpURLConnection c = (HttpURLConnection) new URL(img).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) covers[i] = Bitmap.createScaledBitmap(b, 200, 200, true);
                    }
                    if (ready != null) ready.run();
                } catch (Exception e) {
                    AppLog.w("spotify cover: " + e.getMessage());
                }
            }
        });
    }

    private static String read(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTVGlass/1.0");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static android.content.Intent open(int i) {
        return new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("spotify:playlist:" + IDS[i])).setPackage(PKG)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
