package com.local.geckobrowser.diag

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.local.geckobrowser.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志，目录固定为 Download/bcrash。
 *
 * 只写设备本地，不联网、不上传、不引入任何崩溃上报 SDK。
 *
 * 可靠性设计（针对「上次崩溃没写下日志」）：
 *
 * 1. 安装时机：JavaDoc 中 org.mozilla.geckoview.CrashHandler 实现了
 *    Thread.UncaughtExceptionHandler，并在 GeckoRuntime 初始化时注册到全局。
 *    旧实现把自定义 handler 装在 GeckoEngine.runtime() 之前，会被直接覆盖，因此完全不生效。
 *    本实现改为 Gecko 初始化之后安装，并在 Activity.onResume 用 ensureInstalled() 复检。
 *    注意：不链式调用 GeckoView 的 CrashHandler —— 该类的 reportException() 会向 Socorro
 *    上报，与「无云端」约束冲突；本项目用本地日志与存活标记替代。
 *
 * 2. 采集通道（互不依赖，任一通道失败不影响其它通道）：
 *    - Java 层未捕获异常：Thread.UncaughtExceptionHandler
 *    - Gecko 内容进程崩溃 / 被回收：ContentDelegate.onCrash / onKill
 *    - ViewModel 的 EngineEvent.Crash 与启动期被 try/catch 兜住的异常
 *    - 进程存活标记：native SIGSEGV / OOM / 系统强杀不会走任何 Java 回调，
 *      下次冷启动通过未清除的 alive.marker 反推出「上次进程异常终止」并补写记录。
 *
 * 3. 写入可靠性：全程同步、不开协程、不碰 Room 与 Gecko；
 *    FileOutputStream.flush() + FileDescriptor.sync() 强制落盘；
 *    独立崩溃文件 + 追加式 latest.log + 三处目录，任一成功即可。
 *    启动时写一条 boot 自检记录，用户打开应用即可确认日志通道本身可用。
 *
 * 4. 目录：API 29+ 用 MediaStore.Downloads 写公共 Download/bcrash；
 *    API 26~28 写公共 Download/bcrash（需 WRITE_EXTERNAL_STORAGE，已在 manifest 限定 maxSdk 28）；
 *    同时无条件写外部私有 Download/bcrash 与内部 filesDir/bcrash 作为兜底。
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val DIR_NAME = "bcrash"
    private const val MARKER = "alive.marker"
    private const val LATEST = "latest.log"

    @Volatile private var app: Application? = null

    /** 仅用于诊断记录，不主动调用，避免触发 GeckoView CrashHandler 的 Socorro 上报。 */
    @Volatile private var replacedHandler: Thread.UncaughtExceptionHandler? = null

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /**
     * 在 Application.onCreate 最早调用：建立可写通道、检查上次会话、写自检记录。
     * 此时不安装全局 handler，避免被随后初始化的 GeckoView CrashHandler 覆盖。
     */
    fun init(application: Application) {
        app = application
        checkPreviousSession()
        record("boot", extra = "日志通道自检：能看到本文件说明 Download/bcrash 写入正常。")
        markState("alive")
    }

    /** 必须在 GeckoRuntime.create() 之后调用。 */
    fun installHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) return
        synchronized(this) {
            val again = Thread.getDefaultUncaughtExceptionHandler()
            if (again === this) return
            replacedHandler = again
            Thread.setDefaultUncaughtExceptionHandler(this)
        }
    }

    /** 供 Activity.onResume 调用：Gecko 可能延迟注册 CrashHandler，被覆盖就重新装回来。 */
    fun ensureInstalled() {
        if (Thread.getDefaultUncaughtExceptionHandler() !== this) installHandler()
    }

    /** 生命周期状态写入存活标记，用于区分正常退出、后台回收与前台崩溃。 */
    fun markState(state: String) {
        val a = app ?: return
        runCatching {
            val dir = File(a.filesDir, DIR_NAME).apply { mkdirs() }
            val text = "state=$state\npid=${android.os.Process.myPid()}\n" +
                "time=${Date()}\nbuild=${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_NUMBER}\n"
            FileOutputStream(File(dir, MARKER), false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 第一条：同步落盘，绝不依赖任何异步框架。
        record(
            tag = "uncaught_exception",
            throwable = throwable,
            extra = "thread=${thread.name}\nreplacedHandler=${replacedHandler?.javaClass?.name ?: "none"}"
        )
        // 不调用 GeckoView 的 CrashHandler：其 reportException() 会向 Socorro 上报，
        // 违反「无云端」约束。直接结束进程，交由下次启动的存活标记补记现场。
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    /** 任何位置发现异常都可调用；内部全程兜底，绝不向外抛异常。 */
    fun record(tag: String, throwable: Throwable? = null, extra: String = "") {
        val a = app ?: return
        runCatching {
            val now = Date()
            val stamp = stampFormat.format(now)
            val report = buildString {
                append("=== bcrash : ").append(tag).append(" ===\n")
                append("time=").append(now).append('\n')
                append("pid=").append(android.os.Process.myPid()).append('\n')
                append("thread=").append(Thread.currentThread().name).append('\n')
                append("version=").append(BuildConfig.VERSION_NAME)
                    .append('-').append(BuildConfig.BUILD_NUMBER).append('\n')
                append("device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
                    .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
                if (extra.isNotBlank()) append("--- context ---\n").append(extra).append('\n')
                if (throwable != null) {
                    append("--- stacktrace ---\n")
                        .append(Log.getStackTraceString(throwable)).append('\n')
                }
                val rt = java.lang.Runtime.getRuntime()
                append("--- memory ---\n")
                    .append("max=").append(rt.maxMemory())
                    .append(" total=").append(rt.totalMemory())
                    .append(" free=").append(rt.freeMemory()).append('\n')
            }
            val prefix = if (tag == "boot") "boot" else "crash"
            val fileName = "$prefix-$stamp-$tag.txt"

            // 先写最稳的 File 通道，再尝试公共目录；顺序保证私有通道优先成功。
            writeToFiles(a, fileName, report, append = false)
            writeToFiles(a, LATEST, report + "\n", append = true)
            writeToPublicDownloads(a, fileName, report)
        }
    }

    private fun checkPreviousSession() {
        val a = app ?: return
        val marker = File(File(a.filesDir, DIR_NAME), MARKER)
        if (!marker.exists()) return // 首次安装，无需补记
        val state = runCatching { marker.readText() }
            .getOrDefault("unknown")
            .lineSequence()
            .firstOrNull { it.startsWith("state=") }
            ?.removePrefix("state=")
            ?: "unknown"
        // 只有「已正常结束」不补记；其余状态说明上次进程没走完结束流程。
        if (state == "finished") return
        record(
            tag = "previous_session_died",
            extra = "上次进程未正常结束，存活标记状态=$state。\n" +
                "可能原因：native 崩溃、OOM、系统回收或强杀；这类终止不会触发 Java 回调。\n" +
                "存活标记原文：\n" + runCatching { marker.readText() }.getOrDefault("(不可读)")
        )
    }

    /** 三处本地目录：公共下载（低版本）、外部私有下载、内部私有目录。 */
    private fun writeToFiles(a: Application, name: String, text: String, append: Boolean) {
        val dirs = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                runCatching {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }.getOrNull()?.let { add(File(it, DIR_NAME)) }
            }
            a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { add(File(it, DIR_NAME)) }
            add(File(a.filesDir, DIR_NAME))
        }
        dirs.forEach { dir ->
            // 每个目录独立 try：某一处写失败不影响其它目录。
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name), append).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
            }
        }
    }

    /** API 29+ 分区存储：写公共 Download/bcrash，文件管理器可直接看到。 */
    private fun writeToPublicDownloads(a: Application, name: String, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = a.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
            val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "w") ?: run {
                resolver.delete(uri, null, null)
                return@runCatching
            }
            var ok = false
            try {
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                ok = true
            } finally {
                runCatching { pfd.close() }
                if (ok) {
                    val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    runCatching { resolver.update(uri, publish, null, null) }
                } else {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }
}
