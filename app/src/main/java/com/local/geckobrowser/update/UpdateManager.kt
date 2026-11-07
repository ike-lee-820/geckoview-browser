package com.local.geckobrowser.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.local.geckobrowser.BuildConfig
import com.local.geckobrowser.GeckoBrowserApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Release 元信息，仅来自公开 GitHub Release API。 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val apkName: String,
    val directUrl: String,
    val sizeBytes: Long,
    val buildNumber: Int,
    val notes: String,
    val publishedAt: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val progress: Int, val received: Long, val total: Long) : UpdateState
    data class Ready(val version: String, val apkPath: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * 应用内更新：每次打开应用（可在设置关闭）与设置内手动查询时检查 GitHub Release。
 *
 * 隐私边界：只发起对公开 GitHub Release API 的 GET 与 APK 下载 GET，
 * 不携带、不上传任何本地浏览数据（书签、历史、密码、标签等），也不引入任何云端同步能力。
 * 默认下载走 https://v6.gh-proxy.com/https://github.com/ 加速，失败自动回退直连。
 */
class UpdateManager(private val context: Context) {

    private val prefs get() = (context.applicationContext as GeckoBrowserApp).preferences
    private val repo: String get() = BuildConfig.UPDATE_REPO
    private val currentVersion: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    @Volatile private var latest: ReleaseInfo? = null

    fun checkForUpdate(): Flow<UpdateState> = flow {
        emit(UpdateState.Checking)
        try {
            val body = withContext(Dispatchers.IO) { get("https://api.github.com/repos/$repo/releases/latest") }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) {
                emit(UpdateState.Error("未解析到 Release 版本")); return@flow
            }
            val assets = json.optJSONArray("assets")
            var apkName = ""
            var directUrl = ""
            var size = 0L
            var buildNumber = 0
            // 同一 tag 下可能残留多个历史构建产物，取运行号最大的那个，避免更新到旧包。
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val run = name.removeSuffix(".apk").substringAfterLast('-', "0").toIntOrNull() ?: 0
                    if (run >= buildNumber) {
                        buildNumber = run
                        apkName = name
                        directUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                    }
                }
            }
            val version = tag.removePrefix("v")
            val info = ReleaseInfo(
                tag = tag, version = version, apkName = apkName,
                directUrl = directUrl, sizeBytes = size, buildNumber = buildNumber,
                notes = json.optString("body"), publishedAt = json.optString("published_at")
            )
            latest = info
            // versionName 与内核版本一致，代码更新时版本号不变；因此同时比较构建运行号。
            val hasNewCore = isNewer(version, currentVersion)
            val hasNewBuild = buildNumber > BuildConfig.BUILD_NUMBER
            if (directUrl.isBlank() || (!hasNewCore && !hasNewBuild)) {
                emit(UpdateState.UpToDate(currentVersion))
            } else {
                emit(UpdateState.Available(info))
            }
        } catch (t: Throwable) {
            emit(UpdateState.Error("检查更新失败：${t.message ?: "网络不可用"}"))
        }
    }

    /** 用户点击「下载更新」：优先代理加速，失败回退直连，完成后可触发安装。 */
    fun downloadAndInstall(): Flow<UpdateState> = flow {
        val info = latest ?: run {
            // 未检查过时先拉取一次元信息。
            checkForUpdate().collect { state ->
                if (state is UpdateState.Available) latest = state.info
            }
            latest
        }
        if (info == null || info.directUrl.isBlank()) {
            emit(UpdateState.Error("没有可用的 APK 下载地址")); return@flow
        }
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, info.apkName.ifBlank { "update-${info.version}.apk" })

        val proxyBase = prefs.proxyBase().trimEnd('/')
        val tail = info.directUrl.removePrefix("https://github.com/")
        val candidates = buildList {
            if (proxyBase.isNotBlank()) add("$proxyBase/$tail")
            add(info.directUrl)
        }

        var success = false
        for (url in candidates) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "GeckoViewBrowser/${BuildConfig.VERSION_NAME}")
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    conn.disconnect(); error("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.coerceAtLeast(1L)
                conn.inputStream.use { input ->
                    FileOutputStream(target, false).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            emit(UpdateState.Downloading(
                                ((received * 100) / total).toInt().coerceIn(0, 100), received, total
                            ))
                        }
                        out.flush()
                    }
                }
                conn.disconnect()
                success = true
                break
            } catch (t: Throwable) {
                // 代理失败则自动回退直连，不中断整体更新流程。
                if (target.exists()) runCatching { target.delete() }
            }
        }
        if (success) emit(UpdateState.Ready(info.version, target.absolutePath))
        else emit(UpdateState.Error("下载失败：代理与直连均不可用"))
    }.flowOn(Dispatchers.IO)

    /** 通过 FileProvider 触发系统安装界面；不静默安装、不请求任何云端授权。 */
    fun install(apkPath: String) {
        val file = File(apkPath)
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "GeckoViewBrowser/${BuildConfig.VERSION_NAME}")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); error("HTTP $code") }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    /** 版本号形如 160.0.20261017034010：按数字分段比较，避免字典序误判。 */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-')
        val b = current.split('.', '-')
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i)?.toLongOrNull() ?: 0L
            val y = b.getOrNull(i)?.toLongOrNull() ?: 0L
            if (x > y) return true
            if (x < y) return false
        }
        return candidate != current && candidate > current
    }
}
