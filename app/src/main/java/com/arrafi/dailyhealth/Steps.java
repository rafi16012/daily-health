package com.arrafi.dailyhealth;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reads the phone's hardware step counter (counts all the time, even with the app closed)
 * and turns its "steps since the phone restarted" value into "steps today".
 */
public final class Steps {
    private Steps() {}

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static boolean hasSensor(Context c) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Steps counted today, or -1 if no reading yet. */
    static int today(Context c) {
        SharedPreferences p = Health.prefs(c);
        if (!todayKey().equals(p.getString("sDate", ""))) return p.getLong("sLast", -1) >= 0 ? 0 : -1;
        return (int) Math.max(0, p.getLong("sLast", 0) - p.getLong("sBase", 0));
    }

    static synchronized void record(Context c, long raw) {
        SharedPreferences p = Health.prefs(c);
        String today = todayKey();
        String date = p.getString("sDate", "");
        long last = p.getLong("sLast", -1);
        long base = p.getLong("sBase", raw);

        if (!today.equals(date)) {
            // New day: everything after the last reading counts for today.
            if (last >= 0 && raw >= last) base = last;
            else if (last >= 0) base = 0;   // phone restarted overnight
            else base = raw;                // very first reading ever
        } else if (last >= 0 && raw < last) {
            // Phone restarted today: counter went back to zero, keep what we had.
            base = base - last;
        }
        p.edit().putString("sDate", today).putLong("sBase", base).putLong("sLast", raw).apply();
    }

    /** Take one reading from the sensor, then call done (on the main thread). */
    static void refresh(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        SensorManager sm = app.getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null || !permitted(app)) {
            if (done != null) done.run();
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        final SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (finished[0]) return;
                finished[0] = true;
                sm.unregisterListener(this);
                record(app, (long) e.values[0]);
                if (done != null) done.run();
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, h);
        h.postDelayed(() -> {
            if (finished[0]) return;
            finished[0] = true;
            sm.unregisterListener(l);
            if (done != null) done.run();
        }, 4000);
    }
}
