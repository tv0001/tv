package com.tv.player.ui

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.tv.player.core.Store
import kotlin.math.roundToInt

/**
 * TV boxes report wildly different densities for the same panel (1080p as 160, 240 or 320 dpi), which makes a dp-based
 * layout come out tiny or huge. On TV the density is derived from the real pixel size instead, so the UI always works
 * out to about 960dp across, the standard Android TV design size, whatever the box claims.
 */
object UiScale {
    private const val TV_DESIGN_WIDTH_DP = 960f

    fun factor(): Float = when (Store.tvScale()) {
        "small" -> 0.85f
        "large" -> 1.15f
        else -> 1f
    }

    fun wrap(base: Context): Context {
        if (!base.isTv()) return base
        val metrics = base.resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        val dpi = (longSide / TV_DESIGN_WIDTH_DP * 160f * factor()).roundToInt().coerceIn(120, 800)
        Log.d("TVP", "tv scale: ${metrics.widthPixels}x${metrics.heightPixels}px, system ${metrics.densityDpi}dpi -> $dpi dpi")
        val config = Configuration(base.resources.configuration).apply { densityDpi = dpi }
        return base.createConfigurationContext(config)
    }
}

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }
}
