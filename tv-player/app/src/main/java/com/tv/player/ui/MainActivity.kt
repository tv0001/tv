package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.tv.player.R
import com.tv.player.core.AppUpdate
import com.tv.player.core.Library
import com.tv.player.core.Store
import com.tv.player.core.Tracker
import com.tv.player.core.Updater
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    companion object {
        private const val EXTRA_OPEN_KEY = "open_key"
        private const val EXTRA_SEARCH = "search"

        /** 回到发现页并在所有源里搜 [keyword]：分类筛选页点了一部片时用。 */
        fun searchIntent(context: Context, keyword: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SEARCH, keyword)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Opens the series [key] straight away: used by notifications and the TV home screen's continue-watching row. */
        fun openIntent(context: Context, key: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_KEY, key)
    }

    private val tags = listOf("search", "live", "short", "mine", "settings")
    private val navIds = listOf(R.id.nav_search, R.id.nav_live, R.id.nav_short, R.id.nav_mine, R.id.nav_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (useSideNav()) R.layout.activity_main_tv else R.layout.activity_main)
        // TV sets can crop the picture edges (overscan): keep content inside the 5% safe area.
        if (isTv()) findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).setPadding(dp(24), dp(27), dp(48), dp(27))
        openFrom(intent)

        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            val created = listOf<Fragment>(SearchFragment(), LiveFragment(), ShortFragment(), MineFragment(), SettingsFragment())
            fm.beginTransaction().setReorderingAllowed(true).apply {
                created.forEachIndexed { i, f ->
                    add(R.id.container, f, tags[i])
                    if (i != 0) hide(f)
                }
            }.commit()
        }

        val nav = findViewById<NavigationBarView>(R.id.nav)
        if (nav is com.google.android.material.navigationrail.NavigationRailView) setupSideNav(nav)
        nav.setOnItemSelectedListener { item ->
            val target = navIds.indexOf(item.itemId)
            if (target < 0) return@setOnItemSelectedListener false
            fm.beginTransaction().setReorderingAllowed(true).setCustomAnimations(R.animator.fade_in, R.animator.fade_out).apply {
                tags.forEachIndexed { i, tag ->
                    val f = fm.findFragmentByTag(tag) ?: return@forEachIndexed
                    if (i == target) show(f) else hide(f)
                }
            }.commit()
            true
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                nav.selectedItemId != R.id.nav_search -> nav.selectedItemId = R.id.nav_search
                // 电视：发现页的搜索框里有字时，返回键先清空它（手机上 clearOnBack 不处理，照旧）。
                (fm.findFragmentByTag("search") as? SearchFragment)?.clearOnBack() == true -> Unit
                else -> confirmExit()
            }
        }

        lifecycleScope.launch {
            if (Tracker.due()) Tracker.check()
            updateMineBadge()
        }
        if (Store.isStale()) lifecycleScope.launch { Updater.update(Store.baseUrl(), auto = true) }
        if (Store.updateCheckDue()) {
            lifecycleScope.launch {
                val result = AppUpdate.check(respectIgnored = true)
                // A failed check (offline, server down) must not silence the next 12 hours.
                if (result.error == null) Store.markUpdateChecked()
                result.info?.let { UpdateUi.prompt(this@MainActivity, it) }
            }
        }
    }

    /**
     * 侧边导航（电视、平板）：焦点项铺一层半透明浅色底、图标和字变白；导航有焦点时收起选中项的蓝色底块，只留蓝字，
     * 免得「焦点」和「当前页」两种块叠在一起分不清。电视上焦点在右边内容里时，导航整体压暗，让位给海报。
     */
    private fun setupSideNav(nav: com.google.android.material.navigationrail.NavigationRailView) {
        // 条目里的图标和字默认不跟条目的焦点状态走，颜色表里的 state_focused 就不生效
        fun follow(v: android.view.View) {
            v.isDuplicateParentStateEnabled = true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) follow(v.getChildAt(i))
        }
        val menuView = (0 until nav.childCount).map { nav.getChildAt(it) }
            .firstOrNull { it is com.google.android.material.navigation.NavigationBarMenuView } as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val item = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
            for (j in 0 until item.childCount) follow(item.getChildAt(j))
        }
        val indicator = nav.itemActiveIndicatorColor
        val tv = isTv()
        if (tv) nav.alpha = 0.7f
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            var p: android.view.View? = now
            while (p != null && p !== nav) p = p.parent as? android.view.View
            val inNav = p === nav
            nav.itemActiveIndicatorColor = if (inNav) android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT) else indicator
            if (tv) nav.animate().alpha(if (inNav) 1f else 0.7f).setDuration(160).start()
        }
    }

    /** Back on the first page would leave the app: ask first, so a stray press of the remote's Back does not. */
    private fun confirmExit() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("退出 TV Player？")
            .apply { if (isTv()) setMessage("按返回键取消，或选“退出”离开。") }
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> finish() }
            .create()
        // The remote's OK key must do something at once: Back then OK leaves, a Back alone only asks. The dialog
        // puts focus on its text first, so the button takes it once the window is up.
        dialog.setOnShowListener {
            val exit = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            exit.post { exit.requestFocus() }
        }
        dialog.show()
        RemoteControl.cover(dialog)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A notification or a home-screen tile asked for a series: open its details page (playback resumes from there). */
    private fun openFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SEARCH)?.let { keyword ->
            intent.removeExtra(EXTRA_SEARCH)
            // 页面刚重建时发现页可能还没建好：等这一轮界面初始化完再交给它（它没建好也会先记下）
            window.decorView.post { pushSearch(keyword) }
            return
        }
        val key = intent?.getStringExtra(EXTRA_OPEN_KEY) ?: return
        intent.removeExtra(EXTRA_OPEN_KEY)
        val entry = Library.progress(key) ?: Library.favorites().firstOrNull { it.key == key } ?: return
        PlayActivity.startVod(this, entry.toGroup(withProgress = false))
    }

    override fun onResume() {
        super.onResume()
        UpdateUi.resume(this)
        updateMineBadge()
    }

    /** A keyword typed on the phone while another tab is showing: go to the search tab and run it. */
    fun pushSearch(keyword: String) {
        findViewById<NavigationBarView>(R.id.nav).selectedItemId = R.id.nav_search
        (supportFragmentManager.findFragmentByTag("search") as? SearchFragment)?.runSearch(keyword)
    }

    /** A number on the "我的" tab: favorites that got new episodes. */
    fun updateMineBadge() {
        val nav = findViewById<NavigationBarView>(R.id.nav) ?: return
        val n = Tracker.newCount()
        if (n > 0) nav.getOrCreateBadge(R.id.nav_mine).apply { number = n; isVisible = true } else nav.removeBadge(R.id.nav_mine)
    }
}
