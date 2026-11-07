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
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        SearchEngineManager.appContextHolder = applicationContext

        // 延后到首帧之后：启动即弹权限框会让 Activity 立刻 pause/resume，
        // GeckoView 刚 attach 的 surface 被快速销毁重建，部分设备上会触发渲染进程崩溃。
        window.decorView.post { requestRuntimePermissions() }
        handleIncomingIntent(intent)

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
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // 媒体通知依赖通知权限开启；WebRTC 需要 CAMERA / RECORD_AUDIO，由应用自行声明。
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
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
        // GeckoView 可能延迟注册自己的 CrashHandler，每次回到前台复检一次。
        CrashReporter.ensureInstalled()
        CrashReporter.markState("alive")
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
