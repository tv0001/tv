package com.tv.player

import android.app.Application
import com.tv.player.core.Library
import com.tv.player.core.Store
import com.tv.player.ui.Episodes

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Library.init(this)
        Episodes.schedule(this)
    }
}
