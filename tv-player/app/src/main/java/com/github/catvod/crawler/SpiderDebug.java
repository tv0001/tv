package com.github.catvod.crawler;

import android.util.Log;

/** 爬虫 jar 的日志出口。 */
public final class SpiderDebug {
    public static void log(String msg) {
        if (msg != null) Log.d("Spider", msg);
    }

    public static void log(Throwable e) {
        if (e != null) Log.d("Spider", "error", e);
    }
}
