package com.github.tvbox.osc.update;

import android.util.Log;

/**
 * 统一日志前缀，方便 adb logcat -s TVBoxUpdate 过滤。
 */
final class UpdateLog {

    private static final String TAG = "TVBoxUpdate";

    private UpdateLog() {
    }

    static void i(String msg) {
        if (UpdateConfig.DEBUG) Log.i(TAG, msg == null ? "null" : msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg == null ? "null" : msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg == null ? "null" : msg, t);
    }
}
