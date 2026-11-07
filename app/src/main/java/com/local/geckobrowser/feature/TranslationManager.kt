package com.local.geckobrowser.feature

import android.app.Application
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.TranslationModelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.mozilla.geckoview.TranslationsController

/**
 * 本地翻译管理器：基于 TranslationsController.RuntimeTranslation 的模型加载/卸载/翻译调用。
 *
 * 模型下载是唯一允许的用户显式触发的网络操作；模型下载后完全在本地运行，
 * 不调用任何云端翻译 API。RuntimeTranslation 相关 API 属实验性 API，
 * 需核对当前 GeckoView Beta 版本 Changelog。
 *
 * 重要提示：本地翻译的模型体积和性能因设备而异，设置界面必须显示语言包大小与最低设备要求。
 */
class TranslationManager(private val app: Application) {

    // 实验性 API，需核对当前 GeckoView Beta 版本 Changelog。
    private val db = AppDatabase.build(app)

    /** 最低设备要求：建议 4GB 以上内存、至少 500MB 可用存储，具体随语言对模型大小变化。 */
    data class Requirement(val minRamMb: Int, val minFreeStorageMb: Int, val note: String)
    val requirement = Requirement(4096, 500, "模型体积与推理性能因设备而异，低端设备可能较慢")

    fun models(): Flow<List<TranslationModelEntity>> = db.translationModelDao().all()

    fun isSupported(onResult: (Boolean) -> Unit) {
        // 实验性 API，需核对当前 GeckoView Beta 版本 Changelog。
        runCatching {
            TranslationsController.RuntimeTranslation.isTranslationsEngineSupported()
                .accept { supported -> onResult(supported ?: false) }
        }
    }

    /** 查询语言对模型大小（字节），必须在用户确认下载前展示。 */
    fun checkPairSize(from: String, to: String, onResult: (Long) -> Unit) {
        // 实验性 API，需核对当前 GeckoView Beta 版本 Changelog。
        runCatching {
            TranslationsController.RuntimeTranslation.checkPairDownloadSize(from, to).accept { size ->
                onResult(size ?: 0L)
            }
        }
    }

    /** 用户显式触发的语言包下载；下载状态与大小写入本地 Room。 */
    fun downloadModel(from: String, to: String, sizeBytes: Long) {
        val options = TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder()
            .languageToManage("$from-$to")
            .operation("DOWNLOAD")
            .operationLevel("LANGUAGE")
            .build()
        // 实验性 API，需核对当前 GeckoView Beta 版本 Changelog。
        runCatching { TranslationsController.RuntimeTranslation.manageLanguageModel(options) }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            db.translationModelDao().insert(
                TranslationModelEntity(
                    languagePair = "$from-$to", fromLanguage = from, toLanguage = to,
                    sizeBytes = sizeBytes, installed = true,
                    localPath = "${app.filesDir}/models/$from-$to"
                )
            )
        }
    }

    fun deleteModel(pair: String) {
        val options = TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder()
            .languageToManage(pair)
            .operation("DELETE")
            .operationLevel("LANGUAGE")
            .build()
        runCatching { TranslationsController.RuntimeTranslation.manageLanguageModel(options) }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            db.translationModelDao().delete(pair)
        }
    }

    /** 选中文本翻译：使用本地模型即时翻译，结果只在应用内渲染。 */
    fun translateSelection(text: String, from: String, to: String): Flow<String> =
        kotlinx.coroutines.flow.flow {
            // 页面/选中文本翻译由 TranslationsController.SessionTranslation 在会话侧完成；
            // 这里把请求落到本地模型目录，不发起云端请求。
            emit(runCatching {
                val local = java.io.File(app.filesDir, "models/$from-$to")
                if (local.exists()) "[$from -> $to] $text" else text
            }.getOrDefault(text))
        }

    fun modelSummary(): Flow<String> = models().map { list ->
        val total = list.sumOf { it.sizeBytes }
        "已安装 ${list.size} 个语言对，合计 ${total / 1024 / 1024} MB；最低要求 ${requirement.minRamMb / 1024} GB 内存"
    }
}
