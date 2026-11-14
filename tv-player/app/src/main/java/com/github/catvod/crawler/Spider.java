package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;

import com.github.catvod.net.Net;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;

/**
 * TVBox 爬虫基类（catvod 接口）。jar 里的爬虫继承它，方法签名必须和 jar 编译时用的一致，否则加载时找不到方法。
 * 返回值都是 TVBox 约定的 JSON 字符串。
 */
public abstract class Spider {
    public Net net = new Net();

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public void initApi(SpiderApi api) {
    }

    /** 有的 jar（如 XBPQ）用的另一种写法：多一个参数，返回是否接受。 */
    public boolean initApi(SpiderApi api, String extend) {
        initApi(api);
        return true;
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        return null;
    }

    public void destroy() {
    }

    /** 本应用不把页面交给爬虫操作。 */
    public Activity getActivity() {
        return null;
    }

    public static Dns safeDns() {
        return Dns.SYSTEM;
    }
}
