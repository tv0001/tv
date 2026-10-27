package com.tv.player.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The experimental AI search (off until it is switched on in the settings): when nothing local finds anything, the typed
 * text is sent to Pollinations' anonymous text API, which answers with a few likely titles. They are only ever used as
 * search keywords for the sources, never shown as facts. The anonymous tier allows one reply per IP every 30 seconds,
 * and the titles are guesses, so a wrong one simply finds nothing.
 */
object Ai {
    const val ENDPOINT = "https://text.pollinations.ai/openai"
    private const val COOLDOWN_MS = 30_000L
    private const val MAX_TITLES = 5

    class Answer(val titles: List<String>, val error: String? = null)

    private var lastCall = 0L

    /** Milliseconds until the anonymous limit allows another call. */
    @Synchronized fun waitMs(now: Long = System.currentTimeMillis()): Long = maxOf(0L, lastCall + COOLDOWN_MS - now)

    @Synchronized private fun stamp() {
        lastCall = System.currentTimeMillis()
    }

    private const val SYSTEM = "你是影视搜索助手。用户用一句话描述想看的影视剧或短剧。只输出一个 JSON 数组，包含最多 5 个最可能的、真实存在的作品的中文片名；" +
        "不确定就输出 []。不要输出任何解释、前后缀或 Markdown。"

    fun requestBody(text: String): String = JSONObject()
        .put("model", "openai-fast") // the only model the anonymous tier has: a reasoning model
        // Without this it can spend its whole token budget thinking and answer with nothing; with it, it answers in about 10 s.
        .put("reasoning_effort", "low")
        .put("private", true) // not listed in the service's public feed
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", text.take(100))),
        )
        .toString()

    /** One call; never throws. The limit is also enforced here, so a second search a few seconds later does not hit the service. */
    suspend fun suggest(text: String, endpoint: String = ENDPOINT): Answer {
        val wait = waitMs()
        if (wait > 0) return Answer(emptyList(), "AI 是免费匿名的，每 30 秒只能用 1 次，请 ${(wait + 999) / 1000} 秒后再试")
        stamp()
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(endpoint).header("User-Agent", Http.UA)
                    .post(requestBody(text).toRequestBody("application/json".toMediaType())).build()
                // The shared client reads for 10 s at most; this service takes about that long to answer.
                Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                    val body = r.peekBody(200_000).string()
                    when {
                        r.code == 402 || r.code == 429 -> Answer(emptyList(), "AI 免费额度暂时用完了（匿名每 30 秒 1 次），稍后再试")
                        r.code !in 200..299 -> Answer(emptyList(), "AI 服务返回 ${r.code}")
                        else -> Answer(parseTitles(contentOf(body)))
                    }
                }
            } catch (e: Exception) {
                Answer(emptyList(), "AI 没有响应：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** The assistant's text out of an OpenAI-style reply; empty when the reply is not shaped like one. */
    fun contentOf(reply: String): String = try {
        JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    } catch (e: Exception) {
        ""
    }

    /**
     * Titles out of the model's text: the first JSON array of strings in it. Each is cleaned like a typed keyword and
     * must be short and plain; anything else is dropped, since this is untrusted text that ends up in a search request.
     */
    fun parseTitles(content: String): List<String> {
        // The first bracketed run that really is a list of quoted strings: the lenient JSON reader would also take
        // "[注：仅供参考]" for a list with one title, and a note in brackets may come before the answer.
        for (candidate in Regex("\\[[^\\[\\]]*]").findAll(content)) {
            if ('"' !in candidate.value) continue
            val array = try {
                JSONArray(candidate.value)
            } catch (e: Exception) {
                continue
            }
            val out = LinkedHashMap<String, String>()
            for (i in 0 until array.length()) {
                // 《》 and stray marks around a title ("昼阳戏？") are not part of it.
                val title = Search.clean((array.opt(i) as? String).orEmpty()).trim { !it.isLetterOrDigit() }
                if (title.length !in 2..30 || title.any { it.isISOControl() } || "://" in title) continue
                out.putIfAbsent(Search.normalize(title), title)
            }
            return out.values.take(MAX_TITLES)
        }
        return emptyList()
    }

    /** Pasted links and codes (a drive share, say) are not a description of a show and are never sent. */
    fun allowed(text: String): Boolean = "://" !in text && !Regex("[A-Za-z0-9]{12,}").containsMatchIn(text)
}
