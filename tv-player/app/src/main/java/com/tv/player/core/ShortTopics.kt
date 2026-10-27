package com.tv.player.core

/**
 * Themes for the 短剧 tab. The sources do not say what a short drama is about (one in thirty-odd has sub-categories, the
 * rest a single "短剧" bin, and the tags on the items are noise), but the titles are their own synopsis: "重生七零倒插门",
 * "战神归来". So a theme is the few words that such titles are built from, searched across every source and kept to the
 * short-drama category.
 */
object ShortTopics {
    class Topic(val name: String, val keywords: List<String>)

    val ALL = listOf(
        Topic("重生", listOf("重生", "重回", "重返")),
        Topic("穿越", listOf("穿越", "穿成", "穿到")),
        Topic("逆袭", listOf("逆袭", "翻身", "崛起")),
        Topic("总裁", listOf("总裁", "霸总", "首富")),
        Topic("战神", listOf("战神", "龙王", "至尊")),
        Topic("神医", listOf("神医", "医圣", "医仙")),
        Topic("赘婿", listOf("赘婿", "入赘", "女婿")),
        Topic("离婚", listOf("离婚", "前夫", "前妻")),
        Topic("闪婚", listOf("闪婚", "替嫁", "先婚")),
        Topic("甜宠", listOf("甜宠", "宠妻", "团宠")),
        Topic("萌宝", listOf("萌宝", "奶团", "崽")),
        Topic("千金", listOf("千金", "豪门", "真假")),
        Topic("古装", listOf("王爷", "皇后", "王妃")),
        Topic("年代", listOf("七零", "八零", "年代")),
        Topic("悬疑", listOf("悬疑", "凶手", "谜案")),
        Topic("系统", listOf("系统", "神豪", "签到")),
    )

    /** Only what the sources file under 短剧: a movie called "重生" is not a short drama. */
    fun isShort(kind: String) = kind == "短剧"
}
