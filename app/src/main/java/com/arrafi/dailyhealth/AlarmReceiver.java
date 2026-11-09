package com.arrafi.dailyhealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Health.ACTION_DRINK.equals(a)) {
            Health.logDrinkFromNotification(c);
            return;
        }
        final PendingResult pr = goAsync();
        if (Health.ACTION_WATER.equals(a)) {
            Health.showWater(c, false);
            Health.scheduleNextWater(c);
        } else if (Health.ACTION_NIGHT.equals(a)) {
            Health.scheduleNight(c);
        }
        // Take a step reading too, so the daily count stays accurate.
        Steps.refresh(c, pr::finish);
    }
}
