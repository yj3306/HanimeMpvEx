package com.yj3306.hanimempvex

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var play: Button
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.urlInput)
        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        play = findViewById(R.id.playButton)

        findViewById<Button>(R.id.pasteButton).setOnClickListener { paste() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { urlInput.setText(""); status.text = "等待视频地址" }
        play.setOnClickListener { resolveAndPlay() }
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip() || !cm.primaryClipDescription!!.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            status.text = "剪贴板里没有文本"
            return
        }
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        urlInput.setText(text)
        status.text = if (text.isBlank()) "剪贴板为空" else "已粘贴"
    }

    private fun resolveAndPlay() {
        val pageUrl = urlInput.text?.toString()?.trim().orEmpty()
        if (!pageUrl.startsWith("https://hanime1.com/") && !pageUrl.startsWith("https://hanime1.me/")) {
            status.text = "请输入 Hanime1 视频页面地址"
            return
        }
        setBusy(true, "正在解析最高画质…")
        executor.execute {
            try {
                val video = resolveMedia(pageUrl)
                runOnUiThread {
                    setBusy(false, if (video != null) "解析成功，正在打开 mpvEx…" else "没有找到视频直链")
                    if (video != null) openMpvEx(video)
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, "解析失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    private fun resolveMedia(pageUrl: String): String? {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36",
            "Referer" to "https://hanime1.com/"
        )
        val visited = mutableSetOf<String>()
        val candidates = mutableListOf<String>()

        fun crawl(url: String, depth: Int) {
            if (depth > 3 || !visited.add(url)) return
            val doc = Jsoup.connect(url).headers(headers).timeout(15000).followRedirects(true).get()
            doc.select("video[src], video source[src], source[src]").forEach { el ->
                el.absUrl("src").takeIf { it.startsWith("http") }?.let(candidates::add)
            }
            val html = doc.html().replace("\\/", "/")
            Regex("https?://[^\\\"'<> ]+\\.(?:mp4|m3u8)(?:\\?[^\\\"'<> ]*)?", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { candidates.add(it.value.replace("&amp;", "&")) }
            doc.select("iframe[src]").map { it.absUrl("src") }.filter { it.startsWith("http") }.forEach {
                try { crawl(it, depth + 1) } catch (_: Exception) { }
            }
        }

        crawl(pageUrl, 0)
        return candidates.distinct().maxByOrNull { score(it) }
    }

    private fun score(url: String): Int {
        val s = url.lowercase()
        var n = 0
        if (s.contains("2160")) n += 400
        if (s.contains("1440")) n += 300
        if (s.contains("1080")) n += 200
        if (s.contains("720")) n += 100
        if (s.contains(".mp4")) n += 20
        if (s.contains("vdownload") || s.contains("hembed")) n += 10
        return n
    }

    private fun openMpvEx(videoUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                component = ComponentName(
                    "io.github.yaodao0yaodao.mpvex",
                    "app.marlboroadvance.mpvex.ui.player.PlayerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                    setPackage("io.github.yaodao0yaodao.mpvex")
                })
            } catch (_: Exception) {
                status.text = "未找到 mpvEx，或当前版本不接受外部播放链接"
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        play.isEnabled = !busy
        status.text = text
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
