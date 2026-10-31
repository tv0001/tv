package com.tv.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tv.player.R
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.tv.player.BuildConfig
import com.tv.player.core.AppUpdate
import com.tv.player.core.Backup
import com.tv.player.core.CrashLog
import com.tv.player.core.Library
import com.tv.player.core.LibrarySync
import com.tv.player.core.Quark
import com.tv.player.core.QuarkWeb
import com.tv.player.core.Store
import com.tv.player.core.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private lateinit var info: TextView
    private lateinit var backupStatus: TextView

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restore(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        info = view.findViewById(R.id.info)
        backupStatus = view.findViewById(R.id.backupStatus)
        val base = view.findViewById<TextInputEditText>(R.id.base)
        val status = view.findViewById<TextView>(R.id.status)
        val importUrl = view.findViewById<TextInputEditText>(R.id.importUrl)
        val importStatus = view.findViewById<TextView>(R.id.importStatus)
        listOf(R.id.status, R.id.importStatus, R.id.updateStatus).forEach { autoHide(view.findViewById(it)) }
        autoHide(backupStatus)
        val update = view.findViewById<MaterialButton>(R.id.update)
        val importBtn = view.findViewById<MaterialButton>(R.id.importBtn)

        base.setText(Store.baseUrl())
        refreshInfo()

        view.findViewById<TextView>(R.id.version).text = "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
        val checkUpdate = view.findViewById<MaterialButton>(R.id.checkUpdate)
        val updateStatus = view.findViewById<TextView>(R.id.updateStatus)
        checkUpdate.setOnClickListener {
            checkUpdate.isEnabled = false
            updateStatus.text = "正在检查…"
            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppUpdate.check(respectIgnored = false)
                checkUpdate.isEnabled = true
                val info = result.info
                updateStatus.text = when {
                    info != null -> "发现新版本 ${info.name}"
                    result.error != null -> "检查失败：${result.error}"
                    else -> "已经是最新版本"
                }
                if (info != null) UpdateUi.prompt(requireActivity() as AppCompatActivity, info)
            }
        }

        val panStatus = view.findViewById<TextView>(R.id.panStatus)
        val panLogin = view.findViewById<MaterialButton>(R.id.panLogin)
        fun syncPan() {
            val on = Quark.loggedIn
            panStatus.text = if (on) "已登录。「我的」页和这里的「打开我的网盘」都能进去" else "还没有登录。登录后可以浏览和播放自己网盘里的视频"
            panLogin.text = if (on) "退出登录" else "扫码登录"
        }
        syncPan()
        panLogin.setOnClickListener {
            if (Quark.loggedIn) {
                Quark.logout()
                syncPan()
            } else {
                PanLogin.show(requireActivity() as AppCompatActivity, onDone = { syncPan() })
            }
        }
        view.findViewById<View>(R.id.panOpen).setOnClickListener { PanActivity.start(requireContext()) }

        val panWebStatus = view.findViewById<TextView>(R.id.panWebStatus)
        val panWeb = view.findViewById<MaterialButton>(R.id.panWeb)
        fun syncPanWeb() {
            val on = QuarkWeb.loggedIn
            panWebStatus.text = if (on) "网页版已登录：可以把别人的分享转存到你的网盘并播放" else "转存别人的分享要用网页版登录，和上面的登录是两套"
            panWeb.text = if (on) "退出网页版登录" else "扫码登录（转存分享用）"
        }
        syncPanWeb()
        panWeb.setOnClickListener {
            if (QuarkWeb.loggedIn) {
                QuarkWeb.logout()
                syncPanWeb()
            } else {
                PanLogin.showWeb(requireActivity() as AppCompatActivity, onDone = { syncPanWeb() })
            }
        }
        view.findViewById<View>(R.id.panShare).setOnClickListener { ShareActivity.prompt(requireActivity() as AppCompatActivity) }

        val dm = resources.displayMetrics
        view.findViewById<TextView>(R.id.screenInfo).text =
            "当前屏幕 ${maxOf(dm.widthPixels, dm.heightPixels)}×${minOf(dm.widthPixels, dm.heightPixels)} 像素，" +
                "界面按 ${(maxOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()}dp 宽排版。只在电视模式下起作用；字太小或太大时在这里调。"
        val tvScale = view.findViewById<ChipGroup>(R.id.tvScale)
        tvScale.check(
            when (Store.tvScale()) {
                "small" -> R.id.scaleSmall
                "large" -> R.id.scaleLarge
                else -> R.id.scaleAuto
            },
        )
        tvScale.setOnCheckedStateChangeListener { _, ids ->
            val scale = when (ids.firstOrNull()) {
                R.id.scaleSmall -> "small"
                R.id.scaleLarge -> "large"
                else -> "auto"
            }
            if (scale != Store.tvScale()) {
                Store.setTvScale(scale)
                requireActivity().recreate()
            }
        }

        val lineMode = view.findViewById<ChipGroup>(R.id.lineMode)
        lineMode.check(
            when (Store.lineMode()) {
                "smart_always" -> R.id.lineSmartAlways
                "order" -> R.id.lineOrder
                else -> R.id.lineSmart
            },
        )
        lineMode.setOnCheckedStateChangeListener { _, ids ->
            Store.setLineMode(
                when (ids.firstOrNull()) {
                    R.id.lineSmartAlways -> "smart_always"
                    R.id.lineOrder -> "order"
                    else -> "smart"
                },
            )
        }

        val parallelPrefetch = view.findViewById<ChipGroup>(R.id.parallelPrefetch)
        parallelPrefetch.check(if (Store.parallelPrefetch()) R.id.prefetchOn else R.id.prefetchOff)
        parallelPrefetch.setOnCheckedStateChangeListener { _, ids -> Store.setParallelPrefetch(ids.firstOrNull() == R.id.prefetchOn) }

        val dlna = view.findViewById<ChipGroup>(R.id.dlnaReceiver)
        view.findViewById<TextView>(R.id.dlnaHint).text = "开启后，同一个 Wi-Fi 下的手机在视频 App（如哔哩哔哩）的投屏列表里能看到「TV Player（${android.os.Build.MODEL}）」，" +
            "选它就把视频投到这里播放，手机上可以暂停、拖进度、调音量。只在 TV Player 打开着的时候能被找到；爱奇艺、腾讯视频等用自家协议投屏的 App 找不到这里。"
        dlna.check(if (Store.dlnaReceiver(requireContext().isTv())) R.id.dlnaOn else R.id.dlnaOff)
        dlna.setOnCheckedStateChangeListener { _, ids ->
            val on = ids.firstOrNull() == R.id.dlnaOn
            Store.setDlnaReceiver(on)
            if (on) CastSession.startReceiver(requireContext()) else CastSession.stopReceiver()
        }

        view.findViewById<View>(R.id.frameRateBox).visibility = if (requireContext().isTv()) View.VISIBLE else View.GONE
        val matchFrameRate = view.findViewById<ChipGroup>(R.id.matchFrameRate)
        matchFrameRate.check(if (Store.matchFrameRate()) R.id.frameRateOn else R.id.frameRateOff)
        matchFrameRate.setOnCheckedStateChangeListener { _, ids -> Store.setMatchFrameRate(ids.firstOrNull() == R.id.frameRateOn) }

        val hotLayout = view.findViewById<ChipGroup>(R.id.hotLayout)
        hotLayout.check(if (Store.hotLayout() == "rows") R.id.hotLayoutRows else R.id.hotLayoutTabs)
        hotLayout.setOnCheckedStateChangeListener { _, ids ->
            Store.setHotLayout(if (ids.firstOrNull() == R.id.hotLayoutRows) "rows" else "tabs")
        }

        val hotMode = view.findViewById<ChipGroup>(R.id.hotMode)
        hotMode.check(if (Store.hotMode() == "cms") R.id.hotCms else R.id.hotAuto)
        hotMode.setOnCheckedStateChangeListener { _, ids ->
            Store.setHotMode(if (ids.firstOrNull() == R.id.hotCms) "cms" else "auto")
        }

        val aiSearch = view.findViewById<ChipGroup>(R.id.aiSearch)
        aiSearch.check(if (Store.aiSearch()) R.id.aiOn else R.id.aiOff)
        aiSearch.setOnCheckedStateChangeListener { _, ids -> Store.setAiSearch(ids.firstOrNull() == R.id.aiOn) }

        val modes = view.findViewById<ChipGroup>(R.id.uiMode)
        modes.check(
            when (Store.uiMode()) {
                "tv" -> R.id.modeTv
                "tablet" -> R.id.modeTablet
                "phone" -> R.id.modePhone
                else -> R.id.modeAuto
            },
        )
        modes.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.modeTv -> "tv"
                R.id.modeTablet -> "tablet"
                R.id.modePhone -> "phone"
                else -> "auto"
            }
            if (mode != Store.uiMode()) {
                Store.setUiMode(mode)
                requireActivity().recreate()
            }
        }

        view.findViewById<View>(R.id.phonePush).visibility = if (requireContext().isTv() || requireContext().isTablet()) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.phonePush).setOnClickListener {
            PhonePush.show(requireActivity() as androidx.appcompat.app.AppCompatActivity) { (activity as? MainActivity)?.pushSearch(it) }
        }
        view.findViewById<View>(R.id.manageSources).setOnClickListener {
            startActivity(Intent(requireContext(), SourcesActivity::class.java))
        }

        update.setOnClickListener {
            Store.setBaseUrl(base.text.toString().trim())
            update.isEnabled = false
            status.text = "正在下载…"
            viewLifecycleOwner.lifecycleScope.launch {
                val (_, message) = Updater.update(base.text.toString())
                status.text = message
                update.isEnabled = true
                refreshInfo()
            }
        }
        view.findViewById<View>(R.id.reset).setOnClickListener {
            base.setText(Store.DEFAULT_BASE)
            Store.setBaseUrl(Store.DEFAULT_BASE)
            status.text = "已恢复默认地址，点“立即更新”生效"
        }
        importBtn.setOnClickListener {
            importBtn.isEnabled = false
            importStatus.text = "正在下载…"
            viewLifecycleOwner.lifecycleScope.launch {
                val (_, message) = Updater.importUrl(importUrl.text.toString())
                importStatus.text = message
                importBtn.isEnabled = true
                refreshInfo()
            }
        }
        view.findViewById<View>(R.id.syncHost).setOnClickListener { hostSync() }
        view.findViewById<View>(R.id.syncJoin).setOnClickListener { joinSync() }
        view.findViewById<View>(R.id.crashView).setOnClickListener { showCrashLog() }
        syncCrash()
        view.findViewById<View>(R.id.exportBtn).setOnClickListener { exportLauncher.launch("tv-player-backup.json") }
        view.findViewById<View>(R.id.restoreBtn).setOnClickListener { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        view.findViewById<View>(R.id.clearHistory).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除搜索历史？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ ->
                    Store.clearHistory()
                    refreshInfo()
                }
                .show()
        }
    }

    /** A status line with no text must not leave an empty row inside its card. */
    private fun autoHide(view: TextView) {
        view.visibility = if (view.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.doAfterTextChanged { view.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshInfo()
            syncCrash()
        }
    }

    /** 等另一台设备来同步：显示配对码，同步完提示，关掉对话框就停止。 */
    private fun hostSync() {
        val context = requireContext()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val name = "TV Player（${android.os.Build.MODEL}）"
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val host = LibrarySync.Host(
            context, name,
            onSynced = { f, h ->
                main.post {
                    dialog?.setMessage("同步完成：从对方收到 $f 条收藏、$h 条观看记录。可以关掉了。")
                    refreshInfo()
                }
            },
            onClosed = { main.post { dialog?.dismiss() } },
        )
        if (!host.start()) {
            backupStatus.text = "没法开始等待同步，检查一下 Wi-Fi"
            return
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("配对码  ${host.code}")
            .setMessage("在另一台设备的 TV Player 里：设置 → 备份与恢复 → 同步另一台设备，选「$name」，输入上面的配对码。两台要连同一个 Wi-Fi。")
            .setNegativeButton("关闭", null)
            .show()
        RemoteControl.cover(dialog) { host.stop() }
    }

    /** 找到等待同步的设备，输入配对码，双向合并。 */
    private fun joinSync() {
        val context = requireContext()
        backupStatus.text = "正在找同一个 Wi-Fi 下等待同步的设备…"
        viewLifecycleOwner.lifecycleScope.launch {
            val peers = LibrarySync.discover(context)
            if (peers.isEmpty()) {
                backupStatus.text = "没找到。先在另一台设备上点「等待同步」，两台要连同一个 Wi-Fi"
                return@launch
            }
            backupStatus.text = ""
            if (peers.size == 1) askCode(peers[0])
            else RemoteControl.cover(
                MaterialAlertDialogBuilder(context)
                    .setTitle("选一台设备")
                    .setItems(peers.map { it.name }.toTypedArray()) { _, i -> askCode(peers[i]) }
                    .show(),
            )
        }
    }

    private fun askCode(peer: LibrarySync.Peer) {
        val context = requireContext()
        val input = TextInputEditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "4 位配对码"
        }
        val box = android.widget.FrameLayout(context).apply {
            val pad = context.dp(24)
            setPadding(pad, context.dp(8), pad, 0)
            addView(input)
        }
        RemoteControl.cover(
            MaterialAlertDialogBuilder(context)
                .setTitle("和「${peer.name}」同步")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("同步") { _, _ ->
                    backupStatus.text = "正在同步…"
                    viewLifecycleOwner.lifecycleScope.launch {
                        backupStatus.text = LibrarySync.sync(peer, input.text?.toString().orEmpty())
                        refreshInfo()
                    }
                }
                .show(),
        )
    }

    /** 有崩溃记录才显示这一块。 */
    private fun syncCrash() {
        val v = view ?: return
        val count = CrashLog.count()
        val status = v.findViewById<TextView>(R.id.crashStatus)
        val button = v.findViewById<View>(R.id.crashView)
        status.text = if (requireContext().isTv()) "本机记录了 $count 次崩溃。也可以在「用手机输入或遥控电视」扫码后，在手机网页里查看和复制"
        else "本机记录了 $count 次崩溃"
        status.visibility = if (count > 0) View.VISIBLE else View.GONE
        button.visibility = status.visibility
    }

    private fun showCrashLog() {
        val context = requireContext()
        val text = TextView(context).apply {
            text = CrashLog.read().ifEmpty { "没有崩溃记录" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(!context.isTv())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // 电视上滚动区域要能拿到焦点，上下键才滚得动
        val scroll = android.widget.ScrollView(context).apply { isFocusable = true; addView(text) }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("崩溃日志")
            .setView(scroll)
            .setNegativeButton("清空") { _, _ ->
                CrashLog.clear()
                syncCrash()
            }
            .setPositiveButton("关闭", null)
        if (!context.isTv()) builder.setNeutralButton("复制") { _, _ ->
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("崩溃日志", CrashLog.read()))
        }
        RemoteControl.cover(builder.show())
    }

    private fun refreshInfo() {
        if (view == null) return
        val enabled = Store.sources().size
        val total = Store.allSources().size
        val channels = Store.channels().size
        info.text = "点播源：启用 $enabled / 共 $total 个（订阅${if (Store.customConfig() == null) "为内置" else "已更新"}，自定义 ${Store.customSites().size} 个）\n" +
            "直播频道：$channels 个（订阅${if (Store.customLive() == null) "为内置" else "已更新"}，自定义列表 ${Store.customLives().size} 个）\n" +
            "收藏：${Library.favorites().size} 条 · 观看记录：${Library.history().size} 条 · 搜索历史：${Store.history().size} 条"
    }

    private fun export(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            backupStatus.text = try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.export().toByteArray()) }
                        ?: error("无法写入所选位置")
                }
                "已导出备份"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
        }
    }

    private fun restore(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            backupStatus.text = try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("无法读取所选文件")
                }
                Backup.restore(text)
            } catch (e: Exception) {
                "导入失败：${e.message}"
            }
            refreshInfo()
        }
    }
}
