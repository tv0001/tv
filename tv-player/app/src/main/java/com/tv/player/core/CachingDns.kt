package com.tv.player.core

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 域名解析：成功的结果在内存里留 [ttlMs]，IPv4 地址排在前面。
 *
 * - 系统自己的解析缓存只有几秒，播放时每个分片都可能再查一次；运营商 DNS 慢时每次都要等。
 *   留 1 分钟就省掉了几乎所有重复查询，又不至于在 CDN 换地址（短 TTL 做故障切换）后把旧地址留太久。
 * - 家里网络分到了 IPv6 地址、但 IPv6 实际不通时，先连 IPv6 要等到连接超时才换 IPv4。IPv4 放前面，
 *   IPv6 仍留在后面兜底（只有 IPv6 的网络里，连 IPv4 会立刻失败，再接着试 IPv6）。
 * 失败不缓存：下一次照常重新查。
 */
class CachingDns(
    private val ttlMs: Long = TTL_MS,
    private val delegate: Dns = Dns.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis,
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = HashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = clock()
        synchronized(cache) {
            cache[hostname]?.takeIf { now - it.at < ttlMs }?.let { return it.addresses }
        }
        // 查询本身不加锁：一个慢域名不能挡住别的域名
        val addresses = ipv4First(delegate.lookup(hostname))
        synchronized(cache) {
            if (cache.size >= MAX_HOSTS) cache.entries.removeAll { now - it.value.at >= ttlMs }
            if (cache.size >= MAX_HOSTS) cache.clear()
            cache[hostname] = Entry(addresses, now)
        }
        return addresses
    }

    /** 网络换了（换 Wi-Fi、断线重连）时清掉：旧网络查到的地址在新网络里不一定能用。 */
    fun clear() = synchronized(cache) { cache.clear() }

    companion object {
        const val TTL_MS = 60_000L
        private const val MAX_HOSTS = 256

        /** IPv4 在前，各自的先后顺序不变。 */
        fun ipv4First(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
