package com.arrafi.prayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Prayer.ACTION_PRAYED.equals(a)) {
            Prayer.markFromNotification(c, intent.getStringExtra("d"), intent.getStringExtra("p"));
            return;
        }
        if (Prayer.ACTION_EVENT.equals(a)) {
            try {
                JSONObject e = new JSONObject(intent.getStringExtra("ev"));
                String d = e.optString("d"), p = e.optString("p");
                boolean late = System.currentTimeMillis() - e.optLong("t") > 20 * 60_000L; // phone was off
                boolean skip = "remind".equals(e.optString("k")) && Prayer.isDone(c, d, p);
                if (!late && !skip) Prayer.show(c, d, p, e.optString("title"), e.optString("text"));
            } catch (Exception ignored) {}
            Prayer.scheduleNext(c);
        }
    }
}
