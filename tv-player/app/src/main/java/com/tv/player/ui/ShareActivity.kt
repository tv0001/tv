package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.Quark
import com.tv.player.core.QuarkWeb
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Somebody's 夸克 share, browsed without any login. A video is saved into your own drive (folder "TV Player" / the
 * share's title) when picked, and plays from there; that needs the web login.
 */
class ShareActivity : BaseActivity() {
    companion object {
        private const val EXTRA_ID = "pwd_id"
        private const val EXTRA_CODE = "passcode"

        fun start(context: Context, pwdId: String, passcode: String) =
            context.startActivity(Intent(context, ShareActivity::class.java).putExtra(EXTRA_ID, pwdId).putExtra(EXTRA_CODE, passcode))

        /** Asks for a share link (the text may also carry the pass code) and opens it. */
        fun prompt(activity: AppCompatActivity) {
            val input = EditText(activity).apply {
                hint = "粘贴夸克分享链接，可带提取码"
                setSingleLine(false)
                maxLines = 3
            }
            val box = android.widget.FrameLayout(activity).apply { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0); addView(input) }
            MaterialAlertDialogBuilder(activity)
                .setTitle("添加夸克分享链接")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("打开") { _, _ ->
                    val parsed = QuarkWeb.parseShare(input.text.toString())
                    if (parsed == null) Toast.makeText(activity, "这不是夸克分享链接（pan.quark.cn/s/…）", Toast.LENGTH_LONG).show()
                    else start(activity, parsed.first, parsed.second)
                }
                .show()
        }
    }

    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: View
    private val adapter = PanAdapter(::open)
    private var share: QuarkWeb.Share? = null
    private val path = ArrayList<Pair<String, String>>() // folder id in the share, name
    private var files: Map<String, QuarkWeb.ShareFile> = emptyMap()
    private var job: Job? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pan)
        if (isTv()) findViewById<View>(R.id.panContent).setPadding(dp(48), dp(27), dp(48), dp(27)) // overscan-safe area
        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.addShare).visibility = View.GONE
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_short)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "这个文件夹是空的"
        empty.findViewById<TextView>(R.id.emptySub).text = ""
        empty.visibility = View.GONE
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@ShareActivity)
            adapter = this@ShareActivity.adapter
            isFocusable = false // RecyclerView overrides the layout's focusable="false"; the rows take the focus
        }
        onBackPressedDispatcher.addCallback(this) {
            if (path.size > 1) {
                path.removeAt(path.size - 1)
                load()
            } else {
                finish()
            }
        }
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        busy("正在打开分享…")
        lifecycleScope.launch {
            try {
                val s = QuarkWeb.openShare(id, code)
                share = s
                path += "0" to s.title
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(e.message ?: "分享打不开")
            }
        }
    }

    private fun busy(text: String) {
        status.text = text
        progress.visibility = View.VISIBLE
    }

    private fun done(text: String) {
        status.text = text
        progress.visibility = View.GONE
    }

    private fun load() {
        val s = share ?: return
        val (fid, name) = path.last()
        heading.text = name
        job?.cancel()
        busy("正在读取…")
        job = lifecycleScope.launch {
            try {
                val list = QuarkWeb.shareList(s, fid)
                files = list.associateBy { it.fid }
                adapter.submitList(list.map { Quark.File(it.fid, it.name, it.isDir, it.size, if (it.isVideo) 1 else 0, 0, "") }) { findViewById<RecyclerView>(R.id.list).scrollToPosition(0) }
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                done(if (list.any { it.isVideo }) "" else if (list.any { !it.isDir }) "这里没有能播放的视频（光盘镜像 .iso 播放器打不开）" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(e.message ?: "读取失败")
            }
        }
    }

    private fun open(row: Quark.File) {
        val f = files[row.fid] ?: return
        when {
            f.isDir -> { path += f.fid to f.name; load() }
            f.isVideo -> saveAndPlay(f)
            else -> Toast.makeText(this, if (f.name.endsWith(".iso", true)) "光盘镜像（.iso）播放器打不开" else "这不是视频文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndPlay(f: QuarkWeb.ShareFile) {
        val s = share ?: return
        if (saving) return // a save is running; a second tap must not start another
        if (!QuarkWeb.loggedIn) {
            PanLogin.showWeb(this, onDone = { saveAndPlay(f) })
            return
        }
        saving = true
        busy("正在转存到你的网盘…")
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val app = QuarkWeb.ensureFolder("0", QuarkWeb.APP_FOLDER)
                val sub = QuarkWeb.ensureFolder(app, QuarkWeb.folderName(s.title))
                // Picked before: it is in the folder already, so play that one instead of saving a copy.
                val have = QuarkWeb.list(sub).firstOrNull { it.name == f.name && it.size == f.size }
                val savedFid = have?.fid ?: QuarkWeb.save(s, f, path.last().first, sub)
                // Saved by the web login; played through the TV login when there is one (it offers the transcoded lines, the
                // original file's address can be slow), else through the web login (the original file only).
                // Matched by the drive's file id (the names only back that up); a TV login that cannot read the folder (dead,
                // another account) or does not count the file as a video falls back to the web login.
                suspend fun tvIndex(): Int? = runCatching {
                    val v = Quark.list(sub).filter { it.isVideo }
                    v.indexOfFirst { it.fid == savedFid }.takeIf { it >= 0 } ?: v.indexOfFirst { it.name == f.name }.takeIf { it >= 0 }
                }.getOrNull()
                val tvAt = if (Quark.loggedIn) tvIndex() else null
                val viaTv = tvAt != null
                val at = tvAt ?: QuarkWeb.list(sub).filter { it.isVideo }.let { v ->
                    v.indexOfFirst { it.fid == savedFid }.takeIf { it >= 0 } ?: v.indexOfFirst { it.name == f.name }.coerceAtLeast(0)
                }
                val group = VodGroup(
                    (if (viaTv) "quark:" else "quarkweb:") + sub, s.title, "", "",
                    listOf(VodItem(if (viaTv) Quark.SOURCE_KEY else QuarkWeb.SOURCE_KEY, "夸克分享", sub, s.title, "", "", kind = "网盘")),
                )
                saving = false
                done("")
                PlayActivity.startVod(this@ShareActivity, group, startEp = at)
            } catch (e: CancellationException) {
                saving = false
                throw e
            } catch (e: QuarkWeb.ApiException) {
                saving = false
                done(e.message ?: "转存失败")
                if (e.loggedOut) {
                    QuarkWeb.logout()
                    PanLogin.showWeb(this@ShareActivity, onDone = { saveAndPlay(f) })
                }
            } catch (e: Exception) {
                saving = false
                done("转存失败：${e.message ?: "网络不通"}")
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
