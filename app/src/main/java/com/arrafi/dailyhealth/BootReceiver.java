package com.arrafi.dailyhealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are wiped when the phone restarts, so set them again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Health.scheduleNextWater(c);
        Health.scheduleNight(c);
    }
}
