package com.tv.player.core

import com.tv.player.data.VodSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object Probe {
    class Result(val ok: Boolean, val count: Int, val ms: Long, val error: String?) {
        fun describe(): String = when {
            !ok -> "不可用 · $error"
            count == 0 -> "接口可访问，但这次没返回结果 · ${ms}ms"
            else -> "可用 · 返回 $count 条 · ${ms}ms"
        }
    }

    /** Runs a real search so a source that answers but returns junk is reported as broken. */
    suspend fun vod(source: VodSource, keyword: String = "爱"): Result {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        return try {
            val items = withTimeout(10_000) { Cms.search(source, keyword) }
            Result(true, items.size, elapsed(), null)
        } catch (e: TimeoutCancellationException) {
            Result(false, 0, elapsed(), "超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(false, 0, elapsed(), e.message ?: e.javaClass.simpleName)
        }
    }
}
