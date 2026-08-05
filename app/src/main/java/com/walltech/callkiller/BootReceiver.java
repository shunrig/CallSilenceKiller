package com.walltech.callkiller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启 BroadcastReceiver */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("CallKiller-Boot", "开机广播收到");
            if (AppPrefs.isEnabled(ctx)) {
                // 只记录，不立即启动服务——等有通话才启动
                Log.d("CallKiller-Boot", "监控已开启，下次通话将自动启动保护");
            }
        }
    }
}
