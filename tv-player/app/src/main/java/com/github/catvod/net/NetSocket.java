package com.github.catvod.net;

/** 个别爬虫用的长连接；本应用不提供，send 总是失败。 */
public class NetSocket {
    public interface Listener {
    }

    public static class Event {
    }

    public boolean send(byte[] data) {
        return false;
    }
}
