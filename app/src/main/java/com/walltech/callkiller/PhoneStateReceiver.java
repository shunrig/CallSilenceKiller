package com.walltech.callkiller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * 监听 android.intent.action.PHONE_STATE 和 NEW_OUTGOING_CALL 广播，
 * 触发 CallMonitorService 在后台监控通话音频能量。
 */
public class PhoneStateReceiver extends BroadcastReceiver {

    private static final String TAG = "CallKiller-PhoneState";
    public  static final String EXTRA_NUMBER = "android.intent.extra.PHONE_NUMBER";

    // 防止重复触发
    private static boolean sServiceRunning = false;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "收到广播: " + action);

        if (!AppPrefs.isEnabled(ctx)) {
            Log.d(TAG, "监控未开启，忽略");
            return;
        }

        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
            // 拨出电话
            String number = intent.getStringExtra(EXTRA_NUMBER);
            Log.d(TAG, "拨出电话: " + number);
            startMonitorService(ctx, "拨出: " + number);
            return;
        }

        if (Intent.ACTION_PHONE_STATE.equals(action)) {
            String state = intent.getStringExtra("state");
            String number = intent.getStringExtra("incoming_number");
            Log.d(TAG, "电话状态: " + state + " | " + number);

            switch (state) {
                case "RINGING":
                    // 来电响铃——等接通再启动监控
                    break;
                case "OFFHOOK":
                    // 摘机（接通/保持通话中）
                    startMonitorService(ctx, "来电: " + number);
                    break;
                case "IDLE":
                    // 通话结束
                    stopMonitorService(ctx);
                    break;
            }
        }
    }

    private void startMonitorService(Context ctx, String label) {
        if (sServiceRunning) {
            Log.d(TAG, "服务已在运行，跳过重复启动");
            return;
        }
        sServiceRunning = true;

        Intent svc = new Intent(ctx, CallMonitorService.class);
        svc.putExtra("call_label", label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
        Log.d(TAG, "服务已启动: " + label);
    }

    private static void stopMonitorService(Context ctx) {
        if (!sServiceRunning) return;
        sServiceRunning = false;
        ctx.stopService(new Intent(ctx, CallMonitorService.class));
        Log.d(TAG, "服务已停止");
    }

    // 测试用：直接触发挂断（验证权限）
    public static void testHangup(Context ctx) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.endCall();
                Log.d(TAG, "TEST: 挂断成功");
            }
        } catch (Exception e) {
            Log.e(TAG, "挂断失败: " + e.getMessage());
        }
    }

    // 主界面需要调用这个来控制服务
    public static void startService(Context ctx) {
        if (AppPrefs.isEnabled(ctx)) {
            // 不自动启动，等待通话事件
            Log.d(TAG, "已开启监控，等待下一次通话...");
        }
    }

    public static void stopService(Context ctx) {
        stopMonitorService(ctx);
    }
}
