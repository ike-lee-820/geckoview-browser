package com.local.geckobrowser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.local.geckobrowser.browser.BrowserViewModel
import com.local.geckobrowser.diag.CrashReporter
import com.local.geckobrowser.feature.SearchEngineManager
import com.local.geckobrowser.ui.AppNavHost
import com.local.geckobrowser.ui.theme.GeckoBrowserTheme
import org.mozilla.geckoview.GeckoView

/** Compose 入口：Edge-to-edge、动态取色、单 GeckoView 由 AndroidView 嵌入。 */
class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()
    private val fileResult = mutableStateOf<android.net.Uri?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果由 PermissionDelegate 的回调在用户交互时读取系统状态 */ }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) fileResult.value = uri }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.markStage("activity_oncreate_start")
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        SearchEngineManager.appContextHolder = applicationContext

        // 【修复】启动期不再批量申请 CAMERA / RECORD_AUDIO / ACCESS_FINE_LOCATION。
        // 日志显示上次进程在 onResume 后 1 秒内、状态仍为 alive 时死亡，且 Java 层无任何异常；
        // vivo / OriginOS 上一次弹出多个危险权限对话框会让 Activity 连续 pause/resume，
        // GeckoView 刚 attach 的 surface 反复销毁重建，是前台瞬退的高风险点。
        // 这三个权限改由 PermissionDelegate.onAndroidPermissionsRequest 在站点真正请求时按需触发。
        // 仅保留通知权限（单个、Android 13+），且延后到首帧之后。
        window.decorView.post { requestRuntimePermissions() }
        handleIncomingIntent(intent)

        CrashReporter.markStage("setcontent_before")
        setContent {
            val state by vm.ui.collectAsState()
            GeckoBrowserTheme(mode = state.themeMode) {
                AppNavHost(
                    viewModel = vm,
                    fileResult = fileResult.value,
                    onFileResultConsumed = { fileResult.value = null },
                    onRequestFilePicker = { fileLauncher.launch("*/*") },
                    onEnterPip = { enterPip() }
                )
            }
        }
        CrashReporter.markStage("setcontent_after")
    }

    private fun requestRuntimePermissions() {
        CrashReporter.markStage("permission_request_before")
        // 只申请通知权限；摄像头/麦克风/定位由站点触发时按需申请，避免启动期多权限弹窗。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        CrashReporter.markStage("permission_request_after")
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 优先由 v160 实验性 SessionPictureInPicture 驱动；此处是 Android 原生 PiP 降级实现。
        // 实验性 API，需核对当前 Beta 版本 Changelog。
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.dataString
        if (!uri.isNullOrBlank()) vm.load(uri)
    }

    override fun onResume() {
        super.onResume()
        CrashReporter.markStage("activity_onresume")
        // GeckoView 可能延迟注册自己的 CrashHandler，每次回到前台复检一次。
        CrashReporter.ensureInstalled()
        CrashReporter.markState("alive")
        CrashReporter.markStage("activity_resumed_ready")
    }

    override fun onStop() {
        CrashReporter.markState("background")
        super.onStop()
        vm.manager.persistAll()
    }

    override fun onDestroy() {
        if (isFinishing) {
            CrashReporter.markState("finished")
            vm.runExitCleanup()
        }
        super.onDestroy()
    }
}
