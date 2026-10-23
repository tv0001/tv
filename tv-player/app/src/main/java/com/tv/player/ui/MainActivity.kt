package com.tv.player.ui

import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationBarView
import com.tv.player.R
import com.tv.player.core.AppUpdate
import com.tv.player.core.Store
import com.tv.player.core.Tracker
import com.tv.player.core.Updater
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private val tags = listOf("search", "live", "mine", "settings")
    private val navIds = listOf(R.id.nav_search, R.id.nav_live, R.id.nav_mine, R.id.nav_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (useSideNav()) R.layout.activity_main_tv else R.layout.activity_main)
        // TV sets can crop the picture edges (overscan): keep content inside the 5% safe area.
        if (isTv()) findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).setPadding(dp(24), dp(27), dp(48), dp(27))

        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            val created = listOf<Fragment>(SearchFragment(), LiveFragment(), MineFragment(), SettingsFragment())
            fm.beginTransaction().setReorderingAllowed(true).apply {
                created.forEachIndexed { i, f ->
                    add(R.id.container, f, tags[i])
                    if (i != 0) hide(f)
                }
            }.commit()
        }

        val nav = findViewById<NavigationBarView>(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            val target = navIds.indexOf(item.itemId)
            if (target < 0) return@setOnItemSelectedListener false
            fm.beginTransaction().setReorderingAllowed(true).apply {
                tags.forEachIndexed { i, tag ->
                    val f = fm.findFragmentByTag(tag) ?: return@forEachIndexed
                    if (i == target) show(f) else hide(f)
                }
            }.commit()
            true
        }

        onBackPressedDispatcher.addCallback(this) {
            if (nav.selectedItemId != R.id.nav_search) nav.selectedItemId = R.id.nav_search else finish()
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
