package com.github.catvod.crawler;

import com.google.gson.JsonArray;
import com.tv.player.core.SpiderProxy;

/**
 * 部分爬虫（如 XBPQ）通过 Spider.initApi 拿到的宿主能力。本地代理地址给真实的（见 SpiderProxy）；
 * 网页解析和批量请求本应用不提供，返回空，用到它们的站点会失败，其余功能不受影响。
 */
public class SpiderApi {
    public void log(String msg) {
        SpiderDebug.log(msg);
    }

    public String getAddress(boolean local) {
        int port = SpiderProxy.INSTANCE.getPort();
        return port > 0 ? "http://127.0.0.1:" + port + "/" : "";
    }

    public String getPort() {
        int port = SpiderProxy.INSTANCE.getPort();
        return port > 0 ? String.valueOf(port) : "";
    }

    /** 屏幕方向（android.content.res.Configuration.ORIENTATION_*）：电视和横屏播放都按横屏答。 */
    public int getScreenOrientation() {
        return 2;
    }

    /** LRC 歌词转 SRT 字幕：每行 [mm:ss.xx]文字 显示到下一行开始（最后一行显示 5 秒）。 */
    public String lrcToSrt(String lrc) {
        if (lrc == null) return "";
        java.util.List<long[]> times = new java.util.ArrayList<>();
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]([^\\[\\n]*)").matcher(lrc);
        while (m.find()) {
            String frac = m.group(3) == null ? "0" : m.group(3);
            long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000 + Long.parseLong((frac + "00").substring(0, 3));
            times.add(new long[]{ms});
            texts.add(m.group(4).trim());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            long start = times.get(i)[0];
            long end = i + 1 < times.size() ? times.get(i + 1)[0] : start + 5000;
            out.append(i + 1).append('\n').append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n').append(texts.get(i)).append("\n\n");
        }
        return out.toString();
    }

    private static String srtTime(long ms) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    public String webParse(String url, String flag) {
        return "";
    }

    public String multiReq(JsonArray reqs) {
        return "";
    }
}
