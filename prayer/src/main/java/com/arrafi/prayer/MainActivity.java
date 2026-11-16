package com.arrafi.prayer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

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
        Prayer.ensureChannel(this);
        askNotifications();
    }

    void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.onNativeChange && window.onNativeChange()");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 2) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) fetchLocation();
            else locError("Location permission was not allowed. Pick your city from the list instead.");
        }
    }

    void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    void locError(String msg) {
        js("window.onNativeLocationError && window.onNativeLocationError(" + JSONObject.quote(msg) + ")");
    }

    void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
            return;
        }
        fetchLocation();
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    void fetchLocation() {
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) { locError("Location isn't available on this phone."); return; }
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 6 * 3600_000L) { deliver(best); return; }

            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (provider == null) {
                if (best != null) { deliver(best); return; }
                locError("Turn on Location in your phone settings, then try again.");
                return;
            }
            final boolean[] done = {false};
            final Location fallback = best;
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (done[0]) return; done[0] = true; lm.removeUpdates(this); deliver(l);
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            main.postDelayed(() -> {
                if (done[0]) return; done[0] = true; lm.removeUpdates(listener);
                if (fallback != null) deliver(fallback);
                else locError("Couldn't find your location. Pick your city from the list instead.");
            }, 20000);
        } catch (SecurityException e) {
            locError("Location permission was not allowed.");
        }
    }

    void deliver(Location l) {
        final double lat = l.getLatitude(), lng = l.getLongitude();
        new Thread(() -> {
            String name = "My location";
            try {
                if (Geocoder.isPresent()) {
                    @SuppressWarnings("deprecation")
                    List<Address> a = new Geocoder(this, Locale.US).getFromLocation(lat, lng, 1);
                    if (a != null && !a.isEmpty()) {
                        Address ad = a.get(0);
                        String n = ad.getLocality() != null ? ad.getLocality()
                                : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea();
                        if (n != null && !n.isEmpty()) name = n;
                    }
                }
            } catch (Exception ignored) {}
            js("window.onNativeLocation && window.onNativeLocation(" + lat + "," + lng + "," + JSONObject.quote(name) + ")");
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Functions the page calls as window.Android.xxx() */
    static class Bridge {
        private final MainActivity act;
        private final Context c;

        Bridge(MainActivity a) { act = a; c = a.getApplicationContext(); }

        @JavascriptInterface public void scheduleEvents(String events, String done) { Prayer.saveEvents(c, events, done); }

        @JavascriptInterface public void setMarked(String d, String p, boolean done) {
            Prayer.setDone(c, d, p, done);
            Prayer.scheduleNext(c);
        }

        @JavascriptInterface public String takePendingMarks() { return Prayer.takePending(c); }

        @JavascriptInterface public boolean notificationsAllowed() {
            return Build.VERSION.SDK_INT < 33
                    || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void askPermissions() {
            act.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && act.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
                        && act.getSharedPreferences("prayer", MODE_PRIVATE).getBoolean("askedNotif", false)) {
                    // Android won't ask again; send them to the app's settings page.
                    act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName())));
                } else {
                    act.getSharedPreferences("prayer", MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply();
                    act.askNotifications();
                }
            });
        }

        @JavascriptInterface public void testNotification() {
            Prayer.show(c, "test", "asr", "🕌 Assalamu alaikum!", "Notifications are working. You'll be notified at each prayer time, in sha Allah.");
        }

        @JavascriptInterface public void requestLocation() { act.runOnUiThread(act::requestLocation); }

        /** Download text from an https URL off the main thread; result goes to window.onNativeFetch(id, body|null). */
        @JavascriptInterface public void fetch(String url, String id) {
            new Thread(() -> {
                String body = null;
                java.net.HttpURLConnection conn = null;
                try {
                    if (!url.startsWith("https://")) throw new IllegalArgumentException();
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    if (conn.getResponseCode() == 200) {
                        java.io.InputStream in = conn.getInputStream();
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        body = out.toString("UTF-8");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                act.js("window.onNativeFetch && window.onNativeFetch(" + JSONObject.quote(id) + ","
                        + (body == null ? "null" : JSONObject.quote(body)) + ")");
            }).start();
        }

        @JavascriptInterface public void copy(String text) {
            act.runOnUiThread(() -> {
                android.content.ClipboardManager cm = c.getSystemService(android.content.ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Quran verse", text));
            });
        }
    }
}
