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
 * 本地诊断日志，目录固定为 Download/bcrash。
 *
 * 只写设备本地，不联网、不上传、不引入任何崩溃上报 SDK。
 *
 * 采集通道：
 * - Java 层未捕获异常：Thread.UncaughtExceptionHandler（GeckoRuntime 初始化之后安装，避免被覆盖）
 * - Gecko 内容进程崩溃 / 被回收：ContentDelegate.onCrash / onKill
 * - ViewModel 的 EngineEvent.Crash 与启动期被 try/catch 兜住的异常
 * - 进程存活标记：系统信号 / ROM 强杀不会走 Java 回调，下次冷启动补写现场
 *
 * 【本次修复：针对「一次运行出来多个 crash」】
 *
 * 用户日志里 ApplicationExitInfo 明确给出
 * `reason=USER_REQUESTED(10)` + `[FORCE STOP] stop ... due to single-cleaner`，
 * 这是 vivo/OriginOS 一键清理强杀应用，**不是崩溃**。旧实现一律写成
 * previous_session_died 并猜测「native 崩溃」，造成一屏误导性文件。
 *
 * 1. 按系统退出原因准确分类：force_stopped_by_system / user_stopped_app /
 *    install_or_update_restart / previous_session_died / previous_session_died_unknown。
 *    系统清理与用户主动结束不再带「崩溃」字样。
 * 2. 单一会话标记 session.marker：state / pid / timeMs / stage / build 同一次写入，
 *    彻底消除旧版 alive.marker 与 stage.marker 两份文件 pid 对不上的问题；
 *    写临时文件后原子 rename，并加同步锁，避免多进程并发写撕裂。
 * 3. 只在主进程写会话标记：GeckoView 内容进程等不跑 Application.onCreate 的进程
 *    不参与，避免它们覆盖主进程的标记。
 * 4. 补记去重：last_reported.marker 记录「上次进程 pid+timeMs」，
 *    同一进程死亡无论被多少次冷启动读到，只补记一次。
 * 5. ApplicationExitInfo 没有匹配上次 pid 的记录时明确说明，
 *    其余历史条目标注「不含上次进程」，不再把无关旧记录当成原因展示。
 *
 * 目录：API 29+ 用 MediaStore.Downloads 写公共 Download/bcrash；
 * API 26~28 写公共 Download/bcrash（WRITE_EXTERNAL_STORAGE 限定 maxSdk 28）；
 * 同时无条件写外部私有 Download/bcrash 与内部 filesDir/bcrash 兜底。
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val DIR_NAME = "bcrash"
    private const val SESSION_MARKER = "session.marker"
    private const val REPORTED_MARKER = "last_reported.marker"
    private const val LATEST = "latest.log"

    /** 兼容旧版：读取时若新标记不存在，仍尝试旧的两个文件。 */
    private const val OLD_ALIVE = "alive.marker"
    private const val OLD_STAGE = "stage.marker"

    @Volatile private var app: Application? = null

    /** 仅用于诊断记录，不主动调用，避免触发 GeckoView CrashHandler 的 Socorro 上报。 */
    @Volatile private var replacedHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile private var currentStage: String = "app_start"

    /** 会话标记的整体读写锁；多进程各持有自己的锁，文件级用 tmp+rename 保证原子。 */
    private val markerLock = Any()

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    private data class SessionMark(
        val state: String,
        val pid: Int,
        val timeMs: Long,
        val stage: String,
        val build: String
    )

    private data class ExitSummary(
        val text: String,
        val matched: Boolean,
        val forceStopped: Boolean,
        val userStopped: Boolean
    )

    private data class Reported(
        val signature: String,
        val tag: String,
        val timeMs: Long
    )

    private fun readReported(file: File): Reported {
        val text = runCatching { file.readText() }.getOrDefault("")
        // 兼容 build 22~28：旧文件只存一行 signature。
        if (text.isBlank()) return Reported("", "", 0L)
        fun f(k: String) = text.lineSequence()
            .firstOrNull { it.startsWith(k) }?.substringAfter('=')
        val sig = f("signature=") ?: text.trim()
        return Reported(
            signature = sig,
            tag = f("tag=") ?: "",
            timeMs = f("timeMs=")?.trim()?.toLongOrNull() ?: 0L
        )
    }

    private fun writeReported(file: File, signature: String, tag: String, now: Long) {
        runCatching {
            val dir = file.parentFile ?: return
            if (!dir.exists()) dir.mkdirs()
            val text = "signature=$signature\ntag=$tag\ntimeMs=$now\n"
            val tmp = File(dir, "$REPORTED_MARKER.tmp")
            FileOutputStream(tmp, false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) file.writeText(text)
        }
    }

    /** 在 Application.onCreate 最早调用。非主进程只保留日志能力，不碰会话标记。 */
    fun init(application: Application) {
        app = application
        if (!isMainProcess(application)) return
        // 【关键顺序】先读上次会话，再写本次阶段标记，否则本次会立刻覆盖上次现场。
        checkPreviousSession()
        updateMark(stage = "crash_reporter_init", state = "alive")
        record("boot", extra = "日志通道自检：能看到本文件说明 Download/bcrash 写入正常。")
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
        if (isMainProcess) updateMark(stage = "ueh_installed")
    }

    /** 供 Activity.onResume 调用：Gecko 可能延迟注册 CrashHandler，被覆盖就重新装回来。 */
    fun ensureInstalled() {
        if (Thread.getDefaultUncaughtExceptionHandler() !== this) installHandler()
    }

    /** 记录当前执行到的阶段。同步写入，崩溃/强杀后下次启动能读出最后停留点。 */
    fun markStage(stage: String) {
        currentStage = stage
        if (isMainProcess) updateMark(stage = stage)
    }

    /** 生命周期状态：alive / background / finished。 */
    fun markState(state: String) {
        if (isMainProcess) updateMark(state = state)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
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
    fun record(
        tag: String,
        throwable: Throwable? = null,
        extra: String = "",
        stageOverride: String? = null
    ) {
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
            val prefix = if (tag == "boot") "boot" else if (
                tag == "force_stopped_by_system" || tag == "user_stopped_app" ||
                tag == "install_or_update_restart"
            ) "info" else "crash"
            val fileName = "$prefix-$stamp-$tag.txt"

            // 先写最稳的 File 通道，再尝试公共目录；顺序保证私有通道优先成功。
            writeToFiles(a, fileName, report, append = false)
            writeToFiles(a, LATEST, report + "\n", append = true)
            writeToPublicDownloads(a, fileName, report)
        }
    }

    // ---------------------------------------------------------------- 主进程判定

    @Volatile private var isMainProcess: Boolean = true

    private fun isMainProcess(a: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }
                .getOrDefault(a.packageName)
        }
        isMainProcess = name == a.packageName
        return isMainProcess
    }

    // ---------------------------------------------------------------- 单一会话标记

    /**
     * 读现有标记 → 只覆盖传入字段 → 写 tmp 后 rename。
     * 单次写入包含全部字段，state 与 stage 永远属于同一个 pid。
     */
    private fun updateMark(stage: String? = null, state: String? = null) {
        val a = app ?: return
        synchronized(markerLock) {
            runCatching {
                val dir = File(a.filesDir, DIR_NAME).apply { mkdirs() }
                val file = File(dir, SESSION_MARKER)
                val old = readMark(file) ?: readLegacyMark(dir)
                val mark = SessionMark(
                    state = state ?: old?.state ?: "alive",
                    pid = android.os.Process.myPid(),
                    timeMs = System.currentTimeMillis(),
                    stage = stage ?: old?.stage ?: "app_start",
                    build = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_NUMBER}"
                )
                val text = "state=${mark.state}\n" +
                    "pid=${mark.pid}\n" +
                    "timeMs=${mark.timeMs}\n" +
                    "time=${Date(mark.timeMs)}\n" +
                    "stage=${mark.stage}\n" +
                    "build=${mark.build}\n" +
                    "process=${a.packageName}\n"
                val tmp = File(dir, "$SESSION_MARKER.tmp")
                FileOutputStream(tmp, false).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                }
            }
        }
    }

    private fun readMark(file: File): SessionMark? = runCatching {
        if (!file.exists()) return null
        val t = file.readText()
        fun f(k: String) = t.lineSequence().firstOrNull { it.startsWith(k) }?.substringAfter('=')
        SessionMark(
            state = f("state=") ?: "unknown",
            pid = f("pid=")?.trim()?.toIntOrNull() ?: 0,
            timeMs = f("timeMs=")?.trim()?.toLongOrNull() ?: 0L,
            stage = f("stage=") ?: "unknown",
            build = f("build=") ?: ""
        )
    }.getOrNull()

    /** 兼容从 build 22~28 升级上来的旧标记文件。 */
    private fun readLegacyMark(dir: File): SessionMark? {
        val alive = runCatching { File(dir, OLD_ALIVE).readText() }.getOrNull() ?: return null
        val stage = runCatching { File(dir, OLD_STAGE).readText() }.getOrDefault("")
        fun f(text: String, k: String) = text.lineSequence()
            .firstOrNull { it.startsWith(k) }?.substringAfter('=')
        return SessionMark(
            state = f(alive, "state=") ?: "unknown",
            pid = f(alive, "pid=")?.trim()?.toIntOrNull() ?: 0,
            timeMs = f(alive, "timeMs=")?.trim()?.toLongOrNull() ?: 0L,
            stage = f(stage, "stage=") ?: "unknown",
            build = f(alive, "build=") ?: ""
        )
    }

    // ---------------------------------------------------------------- 上次会话判定

    private fun checkPreviousSession() {
        val a = app ?: return
        val dir = File(a.filesDir, DIR_NAME)
        val mark = readMark(File(dir, SESSION_MARKER)) ?: readLegacyMark(dir) ?: return
        if (mark.state == "finished") return // 正常退出，无需补记

        // 去重：同一「上次进程 pid + 存活标记时间」只补记一次。
        // vivo 一键清理后系统会连续拉起多个进程，旧实现会把同一份标记反复写成多条。
        val signature = "${mark.pid}:${mark.timeMs}:${mark.state}"
        val reportedFile = File(dir, REPORTED_MARKER)
        val reported = readReported(reportedFile)
        if (reported.signature == signature) return

        val install = readInstallInfo(mark.timeMs)
        val exit = readExitInfo(mark.pid)
        val now = System.currentTimeMillis()
        val elapsed = now - mark.timeMs

        val tag = when {
            install.second -> "install_or_update_restart"
            exit.forceStopped -> "force_stopped_by_system"
            exit.userStopped -> "user_stopped_app"
            exit.matched -> "previous_session_died"
            else -> "previous_session_died_unknown"
        }

        // 系统清理器连杀时会在几秒内产生多个新进程，同类信息 30 秒内只留一条；
        // 真实崩溃（previous_session_died*）永不抑制，避免漏掉现场。
        val infoOnly = tag == "force_stopped_by_system" || tag == "user_stopped_app"
        if (infoOnly && reported.tag == tag && now - reported.timeMs < 30_000L) {
            writeReported(reportedFile, signature, tag, now)
            return
        }
        writeReported(reportedFile, signature, tag, now)

        record(
            tag = tag,
            stageOverride = mark.stage,
            extra = buildString {
                append("上次进程 pid=").append(mark.pid)
                    .append("，存活标记状态=").append(mark.state)
                    .append("，最后阶段=").append(mark.stage).append("。\n")
                append("本次启动距上次标记 ").append(elapsed).append(" ms。\n\n")
                append("--- 判定 ---\n")
                when (tag) {
                    "force_stopped_by_system" -> append(
                        "系统（vivo/OriginOS 一键清理 single-cleaner）FORCE STOP 强杀应用，\n" +
                            "属于系统主动终止，**不是应用崩溃**，无需修复代码。\n" +
                            "若希望避免，请在系统「设置 → 电池/后台管理」把本应用加入白名单/允许后台运行。\n"
                    )
                    "user_stopped_app" -> append(
                        "用户主动结束应用（从最近任务划掉等），不是崩溃。\n"
                    )
                    "install_or_update_restart" -> append(
                        "APK 安装/覆盖更新期间系统包管理器终止旧进程，属于正常重启，不是崩溃。\n"
                    )
                    "previous_session_died" -> append(
                        "系统记录了真实异常退出（崩溃 / native 信号 / 低内存 / ANR），详见下方退出原因。\n"
                    )
                    else -> append(
                        "系统未记录上次进程 pid=").append(mark.pid).append(
                        " 的退出原因。\n" +
                            "vivo 的 FORCE STOP 经常不生成 exit reason；也可能是系统记录已被清理。\n" +
                            "此条**不能据此断定是崩溃**，下方历史记录均不含上次进程。\n"
                    )
                }
                append("\n--- 会话标记原文 ---\n")
                append("state=").append(mark.state).append('\n')
                append("pid=").append(mark.pid).append('\n')
                append("timeMs=").append(mark.timeMs).append('\n')
                append("stage=").append(mark.stage).append('\n')
                append("build=").append(mark.build).append('\n')
                append("\n--- 安装/更新时间（PackageInfo，系统 API，本地读取）---\n")
                    .append(install.first).append('\n')
                append("--- 系统退出原因（ApplicationExitInfo）---\n").append(exit.text).append('\n')
            }
        )
    }

    /**
     * 用 PackageInfo.lastUpdateTime 判断上次进程是否死于 APK 安装。
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
            // marker 写于安装之后属正常；只有 marker 早于/贴近安装时刻才可能是安装期重启。
            val delta = pi.lastUpdateTime - markerMs
            val recent = markerMs > 0L && delta >= 0L && delta <= 10L * 60L * 1000L
            val text = buildString {
                append("firstInstallTime=").append(Date(pi.firstInstallTime)).append('\n')
                append("lastUpdateTime=").append(Date(pi.lastUpdateTime)).append('\n')
                append("上次存活标记时间=").append(
                    if (markerMs > 0L) Date(markerMs).toString() else "(旧版日志无 timeMs 字段)"
                ).append('\n')
                append("lastUpdateTime - marker = ").append(delta).append(" ms")
                append(if (delta < 0) "（标记晚于安装，属正常启动后）" else "").append('\n')
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
     * 无需权限、不联网。返回按「是否匹配上次 pid」分类后的摘要。
     */
    private fun readExitInfo(previousPid: Int): ExitSummary {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ExitSummary("本机 sdk=${Build.VERSION.SDK_INT} < 30，系统不提供 ApplicationExitInfo。",
                false, false, false)
        }
        val a = app ?: return ExitSummary("Application 未初始化。", false, false, false)
        return runCatching {
            val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(a.packageName, 0, 20)
            val matchedInfo = list.firstOrNull { it.pid == previousPid }
            val sb = StringBuilder()
            if (matchedInfo == null) {
                sb.append("未找到 pid=").append(previousPid)
                    .append(" 的退出记录。系统未返回该进程的退出原因，")
                    .append("不能据此断定崩溃。\n")
            }
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
                    .append(if (matched) "  <== 上次进程" else "  (历史记录，不含上次进程)")
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
            if (list.isEmpty()) sb.append(
                "(系统未返回任何历史退出记录；vivo/OriginOS 可能未实现或已清理)\n"
            )
            val r = matchedInfo?.reason ?: -1
            val desc = matchedInfo?.description.orEmpty()
            val force = r == 10 && (
                desc.contains("FORCE STOP", true) ||
                    desc.contains("single-cleaner", true) ||
                    desc.contains("force stop", true)
            )
            ExitSummary(sb.toString(), matchedInfo != null, force, r == 10)
        }.getOrElse { t ->
            ExitSummary("读取 ApplicationExitInfo 失败：${t.javaClass.simpleName}: ${t.message}",
                false, false, false)
        }
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

    // ---------------------------------------------------------------- 写入

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
