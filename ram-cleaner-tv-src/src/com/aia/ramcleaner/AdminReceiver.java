package com.aia.ramcleaner;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

public class AdminReceiver extends DeviceAdminReceiver {

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx.getApplicationContext(), AdminReceiver.class);
    }
}
