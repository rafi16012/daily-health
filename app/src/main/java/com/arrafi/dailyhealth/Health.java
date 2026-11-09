package com.arrafi.dailyhealth;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/** Water-reminder scheduling and notifications. */
public final class Health {
    private Health() {}

    static final String CHANNEL = "water";
    static final int NOTIF_ID = 1;
    static final String ACTION_WATER = "com.arrafi.dailyhealth.WATER";
    static final String ACTION_DRINK = "com.arrafi.dailyhealth.DRINK";
    static final String ACTION_NIGHT = "com.arrafi.dailyhealth.NIGHT";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("health", Context.MODE_PRIVATE);
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Water reminders",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Reminds you to drink water during the day");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 300, 150, 300});
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent broadcast(Context c, String action, int code) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Saved by the app: reminders on/off, wake-up time, last reminder time, interval. */
    static void saveWaterPlan(Context c, boolean on, long wakeMs, long untilMs, int everyMin) {
        prefs(c).edit()
                .putBoolean("wOn", on)
                .putLong("wWake", wakeMs)
                .putLong("wUntil", untilMs)
                .putInt("wEvery", Math.max(15, everyMin))
                .apply();
        scheduleNextWater(c);
    }

    /** Next reminder slot after now, or -1 if none is left today. */
    static long nextWaterTime(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("wOn", false)) return -1;
        long wake = p.getLong("wWake", -1), until = p.getLong("wUntil", -1);
        long every = p.getInt("wEvery", 60) * 60_000L;
        if (wake <= 0 || until <= wake) return -1;
        long now = System.currentTimeMillis() + 5_000;
        long t = wake + every;
        if (t <= now) t = wake + ((now - wake) / every + 1) * every;
        return t <= until ? t : -1;
    }

    static void scheduleNextWater(Context c) {
        PendingIntent pi = broadcast(c, ACTION_WATER, 100);
        long next = nextWaterTime(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (next < 0) {
            if (am != null) am.cancel(pi);
            return;
        }
        setAlarm(c, next, pi);
    }

    /** A daily alarm just before midnight so the step count closes the day correctly. */
    static void scheduleNight(Context c) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 58);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), broadcast(c, ACTION_NIGHT, 101));
    }

    static void showWater(Context c, boolean force) {
        SharedPreferences p = prefs(c);
        long lastDrink = p.getLong("lastDrink", 0);
        if (!force && System.currentTimeMillis() - lastDrink < 15 * 60_000L) return; // drank recently

        ensureChannel(c);
        int glasses = p.getInt("glassesToday", 0), goal = p.getInt("goal", 8);
        int left = Math.max(0, goal - glasses);
        String text = left > 0
                ? left + " more glass" + (left > 1 ? "es" : "") + " to reach today's goal."
                : "You've hit today's goal. One more won't hurt!";

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action drink = new Notification.Action.Builder(null, "💧 I drank a glass",
                broadcast(c, ACTION_DRINK, 102)).build();

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_drop)
                .setColor(0xFF1E88E5)
                .setContentTitle("💧 Time to drink water")
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(drink)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // notification permission not granted
        }
    }

    static void logDrinkFromNotification(Context c) {
        SharedPreferences p = prefs(c);
        p.edit()
                .putInt("pendingGlasses", p.getInt("pendingGlasses", 0) + 1)
                .putInt("glassesToday", p.getInt("glassesToday", 0) + 1)
                .putLong("lastDrink", System.currentTimeMillis())
                .apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
    }
}
