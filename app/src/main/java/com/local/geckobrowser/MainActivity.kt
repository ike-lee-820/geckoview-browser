package com.local.geckobrowser

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.local.geckobrowser.feature.SearchEngineManager
import com.local.geckobrowser.ui.AppNavHost
import com.local.geckobrowser.ui.theme.GeckoBrowserTheme
import org.mozilla.geckoview.GeckoView

/** Compose 入口：Edge-to-edge、动态取色、单 GeckoView 由 AndroidView 嵌入。 */
class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()
    private val voiceText = mutableStateOf<String?>(null)
    private val fileResult = mutableStateOf<android.net.Uri?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果由 PermissionDelegate 的回调在用户交互时读取系统状态 */ }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) voiceText.value = text
    }

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

        requestRuntimePermissions()
        handleIncomingIntent(intent)

        setContent {
            val state by vm.ui.collectAsState()
            GeckoBrowserTheme(mode = state.themeMode) {
                AppNavHost(
                    viewModel = vm,
                    voiceResult = voiceText.value,
                    onVoiceResultConsumed = { voiceText.value = null },
                    fileResult = fileResult.value,
                    onFileResultConsumed = { fileResult.value = null },
                    onRequestVoice = { startVoiceSearch() },
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

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的内容")
        }
        runCatching { voiceLauncher.launch(intent) }
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

    override fun onStop() {
        super.onStop()
        vm.manager.persistAll()
    }

    override fun onDestroy() {
        if (isFinishing) vm.runExitCleanup()
        super.onDestroy()
    }
}
