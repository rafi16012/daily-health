package com.arrafi.prayer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The page sends a list of upcoming events (prayer start / "time ending" reminders) for the next
 * 30 days. We keep one alarm set for the next event and move to the following one each time it fires.
 */
public final class Prayer {
    private Prayer() {}

    static final String CHANNEL = "prayer";
    static final String ACTION_EVENT = "com.arrafi.prayer.EVENT";
    static final String ACTION_PRAYED = "com.arrafi.prayer.PRAYED";
    static final String[] IDS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("prayer", Context.MODE_PRIVATE);
    }

    static int index(String p) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(p)) return i;
        return 0;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Prayer times", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Tells you when each prayer starts and when its time is ending");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 200, 400});
        nm.createNotificationChannel(ch);
    }

    static void saveEvents(Context c, String eventsJson, String doneJson) {
        prefs(c).edit().putString("events", eventsJson).putString("done", doneJson).apply();
        scheduleNext(c);
    }

    static boolean isDone(Context c, String d, String p) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("done", "[]"));
            String k = d + ":" + p;
            for (int i = 0; i < a.length(); i++) if (k.equals(a.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static synchronized void setDone(Context c, String d, String p, boolean done) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("done", "[]"));
            String k = d + ":" + p;
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!k.equals(a.optString(i))) out.put(a.optString(i));
            if (done) out.put(k);
            prefs(c).edit().putString("done", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static PendingIntent eventIntent(Context c, String evJson) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_EVENT);
        if (evJson != null) i.putExtra("ev", evJson);
        return PendingIntent.getBroadcast(c, 200, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void scheduleNext(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        JSONObject next = null;
        try {
            JSONArray ev = new JSONArray(prefs(c).getString("events", "[]"));
            long now = System.currentTimeMillis() + 1000;
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.getJSONObject(i);
                if (e.optLong("t") <= now) continue;
                if ("remind".equals(e.optString("k")) && isDone(c, e.optString("d"), e.optString("p"))) continue;
                next = e;
                break;
            }
        } catch (Exception ignored) {}
        if (next == null) {
            am.cancel(eventIntent(c, null));
            return;
        }
        PendingIntent pi = eventIntent(c, next.toString());
        long at = next.optLong("t");
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

    static void show(Context c, String d, String p, String title, String text) {
        ensureChannel(c);
        int idx = index(p);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent prayed = new Intent(c, AlarmReceiver.class).setAction(ACTION_PRAYED)
                .putExtra("d", d).putExtra("p", p);
        PendingIntent prayedPi = PendingIntent.getBroadcast(c, 10 + idx, prayed,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF0B3B2E)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "✅ I prayed", prayedPi).build())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(1 + idx, n);
        } catch (SecurityException ignored) {}
    }

    static synchronized void markFromNotification(Context c, String d, String p) {
        setDone(c, d, p, true);
        try {
            JSONArray pend = new JSONArray(prefs(c).getString("pending", "[]"));
            pend.put(new JSONObject().put("d", d).put("p", p));
            prefs(c).edit().putString("pending", pend.toString()).apply();
        } catch (Exception ignored) {}
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(1 + index(p));
        scheduleNext(c); // skips this prayer's "time ending" reminder
    }

    static synchronized String takePending(Context c) {
        String s = prefs(c).getString("pending", "[]");
        prefs(c).edit().putString("pending", "[]").apply();
        return s;
    }
}
