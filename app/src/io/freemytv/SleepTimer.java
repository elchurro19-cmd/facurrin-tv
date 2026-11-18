package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Glass edition: "Apagar en…", the home's sleep timer. Each OK on its button steps 30 → 60 → 90 min → off; when the
 * time comes an alarm (so it works from inside any app) puts the TV to sleep through the TV's own ADB, as the remote's
 * power key would. The end time is kept in a small file, so the home screen can show what's left.
 */
public class SleepTimer extends BroadcastReceiver {
    static final int[] STEPS = {0, 30, 60, 90};

    /** When it goes off (elapsedRealtime), or 0 when off. */
    static long endsAt(Context c) {
        long at = c.getSharedPreferences("sleep", Context.MODE_PRIVATE).getLong("at", 0);
        return at > SystemClock.elapsedRealtime() ? at : 0;
    }

    /** Minutes left, rounded up (0 when off). */
    static int minutesLeft(Context c) {
        long at = endsAt(c);
        return at == 0 ? 0 : (int) ((at - SystemClock.elapsedRealtime() + 59_999) / 60_000);
    }

    /** The next step after what's left now (off after the last). */
    static int next(Context c) {
        int left = minutesLeft(c);
        for (int s : STEPS) if (s > left) return s;
        return 0;
    }

    static void set(Context c, int minutes) {
        long at = minutes == 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60_000L;
        c.getSharedPreferences("sleep", Context.MODE_PRIVATE).edit().putLong("at", at).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimer.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am == null) return;
        am.cancel(pi);
        if (at == 0) return;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (SecurityException e) {   // no exact alarms allowed (Android 12+): a minute late at most
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        app.getSharedPreferences("sleep", Context.MODE_PRIVATE).edit().putLong("at", 0).apply();
        final PendingResult done = goAsync();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5_000, null)) {
                adb.shell("input keyevent KEYCODE_SLEEP");
                AppLog.w("sleep timer: the TV went to sleep");
            } catch (Exception e) {
                AppLog.w("sleep timer: " + e.getMessage());
            } finally {
                done.finish();
            }
        }).start();
    }
}
