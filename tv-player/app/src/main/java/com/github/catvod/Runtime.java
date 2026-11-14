package com.github.catvod;

import android.content.Context;

/** TVBox 爬虫 jar 依赖的宿主接口：jar 里的代码通过它拿到应用的 Context（由 com.tv.player.core.Spiders 设置）。 */
public final class Runtime {
    private static Context context;

    public static void set(Context c) {
        context = c.getApplicationContext();
    }

    public static Context context() {
        return context;
    }
}
