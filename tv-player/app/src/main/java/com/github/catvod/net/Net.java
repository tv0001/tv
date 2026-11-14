package com.github.catvod.net;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Spider.net：简单的 GET。 */
public class Net {
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    public String get(String url) {
        return get(url, 15);
    }

    public String get(String url, int timeoutSeconds) {
        try (Response r = client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
                .newCall(new Request.Builder().url(url).build()).execute()) {
            return r.body() == null ? "" : r.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
