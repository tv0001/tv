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
import com.tv.player.core.Library
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
        if (!hidden) refreshInfo()
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
