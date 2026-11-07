package com.local.geckobrowser.diag

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Context
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
 * 可靠性设计（针对「上次崩溃没写下日志」与「补记里没有原因」）：
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
 * 3. 【本次新增】阶段标记 stage.marker：每个关键节点同步覆盖写入，
 *    previous_session_died 日志会附上「最后停留的阶段」，直接定位死在哪一行。
 *
 * 4. 【本次新增】ApplicationExitInfo（Android 11 / API 30+ 系统 API，非 GeckoView API）：
 *    通过 ActivityManager.getHistoricalProcessExitReasons 读取上次进程的退出原因
 *    （REASON_CRASH_NATIVE / SIGNALED / LOW_MEMORY / ANR 等）、信号号、系统描述与
 *    native tombstone 摘要（getTraceInputStream）。这补齐了「补记没有堆栈」的缺口，
 *    且完全本地、无需任何权限。用户设备 sdk=34，可直接使用。
 *
 * 5. 写入可靠性：全程同步、不开协程、不碰 Room 与 Gecko；
 *    FileOutputStream.flush() + FileDescriptor.sync() 强制落盘；
 *    独立崩溃文件 + 追加式 latest.log + 三处目录，任一成功即可。
 *    启动时写一条 boot 自检记录，用户打开应用即可确认日志通道本身可用。
 *
 * 6. 【本次修复】必须先 checkPreviousSession 再 markStage；旧顺序下本次进程会立刻
 *    覆盖 stage.marker，使「最后停留阶段」永远显示 crash_reporter_init。
 *    同时校验 stage.marker 的 pid 是否属于上次进程，并用 PackageInfo.lastUpdateTime
 *    识别「APK 覆盖安装时系统包管理器强杀旧进程」这类正常重启，不再误报为崩溃。
 *
 * 7. 目录：API 29+ 用 MediaStore.Downloads 写公共 Download/bcrash；
 *    API 26~28 写公共 Download/bcrash（需 WRITE_EXTERNAL_STORAGE，已在 manifest 限定 maxSdk 28）；
 *    同时无条件写外部私有 Download/bcrash 与内部 filesDir/bcrash 作为兜底。
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val DIR_NAME = "bcrash"
    private const val MARKER = "alive.marker"
    private const val STAGE_MARKER = "stage.marker"
    private const val LATEST = "latest.log"

    @Volatile private var app: Application? = null

    /** 仅用于诊断记录，不主动调用，避免触发 GeckoView CrashHandler 的 Socorro 上报。 */
    @Volatile private var replacedHandler: Thread.UncaughtExceptionHandler? = null

    /** 当前阶段（内存副本），每条日志都会带上。 */
    @Volatile private var currentStage: String = "app_start"

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /** 在 Application.onCreate 最早调用：建立可写通道、检查上次会话、写自检记录。 */
    fun init(application: Application) {
        app = application
        // 【修复】必须先读上次会话，再写本次阶段标记。
        // 旧顺序下 markStage 立刻覆盖 stage.marker，checkPreviousSession 读到的
        // 「最后停留阶段」永远是本次进程的 crash_reporter_init —— 用户日志里
        // stage marker 的 pid 与当前 pid 相同（都是新进程）正是这个 bug 的证据。
        checkPreviousSession()
        markStage("crash_reporter_init")
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
        markStage("ueh_installed")
    }

    /** 供 Activity.onResume 调用：Gecko 可能延迟注册 CrashHandler，被覆盖就重新装回来。 */
    fun ensureInstalled() {
        if (Thread.getDefaultUncaughtExceptionHandler() !== this) installHandler()
    }

    /**
     * 记录当前执行到的阶段。同步覆盖写入，崩溃后下次启动能读出「最后停留点」。
     * 每个调用点都包在 runCatching 里，绝不影响业务流程。
     */
    fun markStage(stage: String) {
        currentStage = stage
        val a = app ?: return
        runCatching {
            val dir = File(a.filesDir, DIR_NAME).apply { mkdirs() }
            val text = "stage=$stage\npid=${android.os.Process.myPid()}\n" +
                "time=${Date()}\nbuild=${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_NUMBER}\n"
            FileOutputStream(File(dir, STAGE_MARKER), false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** 生命周期状态写入存活标记，用于区分正常退出、后台回收与前台崩溃。 */
    fun markState(state: String) {
        val a = app ?: return
        runCatching {
            val dir = File(a.filesDir, DIR_NAME).apply { mkdirs() }
            val text = "state=$state\npid=${android.os.Process.myPid()}\n" +
                "timeMs=${System.currentTimeMillis()}\n" +
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
            extra = "thread=${thread.name}\nlastStage=$currentStage\n" +
                "replacedHandler=${replacedHandler?.javaClass?.name ?: "none"}"
        )
        // 不调用 GeckoView 的 CrashHandler：其 reportException() 会向 Socorro 上报，
        // 违反「无云端」约束。直接结束进程，交由下次启动的存活标记补记现场。
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    /** 任何位置发现异常都可调用；内部全程兜底，绝不向外抛异常。 */
    fun record(tag: String, throwable: Throwable? = null, extra: String = "", stageOverride: String? = null) {
        val a = app ?: return
        runCatching {
            val now = Date()
            val stamp = stampFormat.format(now)
            val report = buildString {
                append("=== bcrash : ").append(tag).append(" ===\n")
                append("time=").append(now).append('\n')
                append("pid=").append(android.os.Process.myPid()).append('\n')
                append("thread=").append(Thread.currentThread().name).append('\n')
                append("lastStage=").append(stageOverride ?: currentStage).append('\n')
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
                // 明确标注：这是「记录时刻」的 heap，不是崩溃现场的内存快照。
                append("--- memory（记录时刻的 heap，非崩溃现场）---\n")
                    .append("max=").append(rt.maxMemory())
                    .append(" total=").append(rt.totalMemory())
                    .append(" free=").append(rt.freeMemory()).append('\n')
                append("--- end of record ---\n")
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
        val markerText = runCatching { marker.readText() }.getOrDefault("(不可读)")
        fun field(key: String): String? = markerText.lineSequence()
            .firstOrNull { it.startsWith(key) }?.substringAfter('=')

        val state = field("state=") ?: "unknown"
        val oldPid = field("pid=")?.trim()?.toIntOrNull() ?: 0
        val markerMs = field("timeMs=")?.trim()?.toLongOrNull() ?: 0L
        // 只有「已正常结束」不补记；其余状态说明上次进程没走完结束流程。
        if (state == "finished") return

        val stageText = runCatching {
            File(a.filesDir, "$DIR_NAME/$STAGE_MARKER").readText()
        }.getOrDefault("(无阶段标记，可能是旧版本写入的存活标记)")
        val stagePid = stageText.lineSequence()
            .firstOrNull { it.startsWith("pid=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0
        val stageValid = oldPid != 0 && stagePid == oldPid
        val stageName = stageText.lineSequence()
            .firstOrNull { it.startsWith("stage=") }?.substringAfter('=') ?: "unknown"
        val stageSummary = if (stageValid) stageName else "无效（本次启动已覆盖）"

        val install = readInstallInfo(markerMs)
        val exitInfo = readExitInfo(oldPid)
        // 安装/覆盖更新期间系统包管理器会强杀旧进程，这类重启不是崩溃，单独归类。
        record(
            tag = if (install.second) "install_or_update_restart" else "previous_session_died",
            stageOverride = stageSummary,
            extra = buildString {
                append("上次进程未正常结束，存活标记状态=").append(state)
                    .append("，上次 pid=").append(oldPid).append("。\n")
                append("state=alive 且未走到 onStop，说明进程在前台被终止；")
                    .append("Java 层没有任何异常记录。\n")
                if (install.second) {
                    append("判定：APK 安装/覆盖更新导致系统终止旧进程，属于正常重启，不是崩溃。\n")
                } else {
                    append("判定：非安装期终止，可能是 native 崩溃、系统信号或 ROM 后台策略强杀。\n")
                    append("注意：这类终止不走 Java 回调，本文件是下次冷启动补写的，不含崩溃瞬间堆栈；\n")
                    append("下方的 ApplicationExitInfo 才是系统的真实退出原因与 native trace。\n")
                }
                append("\n--- 存活标记原文 ---\n").append(markerText).append('\n')
                append("--- 最后停留阶段 ---\n")
                    .append(if (stageValid) stageText else
                        "阶段标记 pid=$stagePid，上次进程 pid=$oldPid，不一致；本次启动已覆盖，无法反映上次终止点。")
                    .append('\n')
                append("--- 安装/更新时间（PackageInfo，系统 API，本地读取）---\n")
                    .append(install.first).append('\n')
                append("--- 系统退出原因（ApplicationExitInfo）---\n").append(exitInfo).append('\n')
            }
        )
    }

    /**
     * 用 PackageInfo.firstInstallTime / lastUpdateTime 判断上次进程是否死于 APK 安装。
     * 返回「说明文本」与「是否为安装期重启」。完全本地、无需任何权限。
     */
    @Suppress("DEPRECATION")
    private fun readInstallInfo(markerMs: Long): Pair<String, Boolean> {
        val a = app ?: return "(Application 未初始化)" to false
        return runCatching {
            val pm = a.packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(a.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(a.packageName, 0)
            }
            val delta = pi.lastUpdateTime - markerMs
            // 存活标记写于安装时刻之后 10 分钟内：说明进程是在安装/覆盖更新时被杀的。
            val recent = markerMs > 0L && delta >= 0L && delta <= 10L * 60L * 1000L
            val text = buildString {
                append("firstInstallTime=").append(Date(pi.firstInstallTime)).append('\n')
                append("lastUpdateTime=").append(Date(pi.lastUpdateTime)).append('\n')
                append("上次存活标记时间=").append(
                    if (markerMs > 0L) Date(markerMs).toString() else "(旧版日志无 timeMs 字段)"
                ).append('\n')
                append("lastUpdateTime - marker = ").append(delta).append(" ms\n")
                append("判定：").append(
                    if (recent) "安装/覆盖更新期间被系统包管理器终止（正常重启）"
                    else "非安装期终止"
                ).append('\n')
            }
            text to recent
        }.getOrElse { t -> "读取安装信息失败：${t.javaClass.simpleName}: ${t.message}" to false }
    }

    /**
     * Android 11+ 官方本地接口：读取本包已退出进程的原因与 native tombstone 摘要。
     * 无需权限、不联网；这是补齐「补记没有堆栈」的关键。
     */
    private fun readExitInfo(previousPid: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "本机 sdk=${Build.VERSION.SDK_INT} < 30，系统不提供 ApplicationExitInfo。"
        }
        val a = app ?: return "Application 未初始化。"
        return runCatching {
            val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // pid=0 表示取本包全部历史记录；按时间倒序。
            val list = am.getHistoricalProcessExitReasons(a.packageName, 0, 20)
            val sb = StringBuilder()
            if (list.isEmpty()) sb.append(
                "(系统未返回任何历史退出记录)\n" +
                "  可能原因：vivo/OriginOS 未实现或已清理该记录；或上次进程是被包管理器在 APK\n" +
                "  安装/覆盖更新时终止（这类终止不产生 exit reason）。此时请优先看上面的安装/更新时间段。\n"
            )
            list.take(8).forEach { info ->
                val matched = info.pid == previousPid
                sb.append("reason=").append(reasonName(info.reason))
                    .append("(reasonCode=").append(info.reason).append(')')
                    .append(" status=").append(info.status)
                    .append("(signal=").append(signalName(info.status)).append(')')
                    .append(" pid=").append(info.pid)
                    .append(" time=").append(Date(info.timestamp))
                    .append(" pss=").append(info.pss).append("KB")
                    .append(" rss=").append(info.rss).append("KB")
                    .append(if (matched) "  <== 上次进程" else "")
                    .append('\n')
                info.description?.let { sb.append("  description=").append(it).append('\n') }
                if (matched) {
                    runCatching {
                        val trace = info.traceInputStream?.bufferedReader()?.use { it.readText() }
                        if (trace.isNullOrBlank()) {
                            sb.append("  trace=(系统未提供，Java 层崩溃通常为空)\n")
                        } else {
                            sb.append("  --- trace（").append(trace.length)
                                .append(" 字符，最多保留 60000）---\n")
                                .append(trace.take(60000)).append('\n')
                        }
                    }.onFailure { t ->
                        sb.append("  trace 读取失败：").append(t.message).append('\n')
                    }
                }
            }
            sb.toString()
        }.getOrElse { t -> "读取 ApplicationExitInfo 失败：${t.javaClass.simpleName}: ${t.message}" }
    }

    /** 用数字常量映射，避免低版本加载 API 30 类。 */
    private fun reasonName(code: Int): String = when (code) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZE"
        else -> "CODE_$code"
    }

    private fun signalName(status: Int): String = when (status) {
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        8 -> "SIGFPE"
        4 -> "SIGILL"
        else -> "-"
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
