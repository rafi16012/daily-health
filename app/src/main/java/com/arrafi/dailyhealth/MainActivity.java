package com.arrafi.dailyhealth;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        Health.ensureChannel(this);
        Health.scheduleNight(this);
        askPermissions();
    }

    void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        Steps.refresh(this, this::notifyPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Steps.refresh(this, this::notifyPage);
    }

    void notifyPage() {
        if (web != null) web.evaluateJavascript("window.onNativeChange && window.onNativeChange()", null);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Functions the page can call as window.Android.xxx() */
    static class Bridge {
        private final MainActivity act;
        private final Context c;

        Bridge(MainActivity a) { act = a; c = a.getApplicationContext(); }

        @JavascriptInterface public boolean hasStepSensor() { return Steps.hasSensor(c); }

        @JavascriptInterface public boolean stepPermission() { return Steps.permitted(c); }

        @JavascriptInterface public int getTodaySteps() { return Steps.today(c); }

        @JavascriptInterface public void refreshSteps() {
            act.runOnUiThread(() -> Steps.refresh(c, act::notifyPage));
        }

        /** json: {on, wake (ms), until (ms), every (minutes), glasses, goal} */
        @JavascriptInterface public void scheduleWater(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Health.prefs(c).edit()
                        .putInt("glassesToday", o.optInt("glasses", 0))
                        .putInt("goal", o.optInt("goal", 8))
                        .apply();
                Health.saveWaterPlan(c, o.optBoolean("on", false),
                        o.optLong("wake", -1), o.optLong("until", -1), o.optInt("every", 60));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public long nextReminder() { return Health.nextWaterTime(c); }

        @JavascriptInterface public void noteDrink() {
            Health.prefs(c).edit().putLong("lastDrink", System.currentTimeMillis()).apply();
        }

        /** Glasses logged from the notification button since the app last looked. */
        @JavascriptInterface public int takePendingGlasses() {
            SharedPreferences p = Health.prefs(c);
            int n = p.getInt("pendingGlasses", 0);
            if (n > 0) p.edit().putInt("pendingGlasses", 0).apply();
            return n;
        }

        @JavascriptInterface public void testNotification() { Health.showWater(c, true); }

        @JavascriptInterface public boolean notificationsAllowed() {
            return Build.VERSION.SDK_INT < 33
                    || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void askPermissions() { act.runOnUiThread(act::askPermissions); }

        @JavascriptInterface public void openAppSettings() {
            act.runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + c.getPackageName()));
                act.startActivity(i);
            });
        }
    }
}
