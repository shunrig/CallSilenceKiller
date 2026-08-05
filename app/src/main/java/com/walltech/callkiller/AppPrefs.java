package com.walltech.callkiller;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置存储 */
public class AppPrefs {
    private static final String PREF   = "call_killer_prefs";
    private static final String KEY_ON  = "monitor_enabled";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
    }
}
