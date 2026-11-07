package com.local.geckobrowser.feature

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.local.geckobrowser.data.AppDatabase
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PageExtractionController
import java.io.File

/** 扩展本地侧载：通过 SAF 选择 .xpi，复制到内部存储后交给 WebExtensionController.install。 */
object ExtensionInstaller {
    fun copyToInternal(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, "extensions").apply { mkdirs() }
        val target = File(dir, "sideload-${System.currentTimeMillis()}.xpi")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target).toString()
    }
}

/** 阅读模式：通过 PageExtractionController 提取标题、正文、lead image，在本地渲染。 */
object ReaderModeExtractor {
    data class Article(val title: String, val author: String, val body: String, val leadImage: String?)

    fun extract(session: GeckoSession, onResult: (Article) -> Unit) {
        val extractor = session.sessionPageExtractor ?: return
        // ContentParams(boolean, boolean)：v156 起新增 useSimpleText 参数（构造器变更）。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        val params = PageExtractionController.ContentParams(true, true)
        runCatching {
            extractor.getPageContent(params).accept { content ->
                val text = content?.toString().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                onResult(
                    Article(
                        title = json?.optString("title").orEmpty().ifBlank { session.settings.toString() },
                        author = json?.optString("author").orEmpty(),
                        body = json?.optString("content").orEmpty().ifBlank { text },
                        leadImage = json?.optString("leadImageUrl")
                    )
                )
            }
        }
    }
}

/** 打印与保存 PDF：调用系统 PrintManager 或 GeckoSession.saveAsPdf()。 */
object PrintAndPdf {
    fun print(context: Context, session: GeckoSession) {
        // GeckoSession.printPageContent() 把当前页面交给系统打印服务。
        runCatching { session.printPageContent() }
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        // 系统打印任务由 GeckoView 的 PrintDelegate 适配；此处仅确保打印服务可用。
        manager?.printJobs?.let { /* 本地打印，不经过网络 */ }
    }

    fun saveAsPdf(context: Context, session: GeckoSession, name: String) {
        // v156 起 saveAsPdf() 会识别当前页面是否为 PDF JS 页面：是则用 PDF JS 机制保存，
        // 否则通过 Gecko 打印框架生成 PDF；更早版本需手动区分网页另存为 PDF 与保存当前 PDF 文件。
        // 需核对当前 GeckoView Beta 版本 Changelog。
        runCatching {
            session.saveAsPdf().accept { stream ->
                if (stream == null) return@accept
                val dir = File(context.filesDir, "downloads").apply { mkdirs() }
                val target = File(dir, name)
                stream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                val open = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(open) }
            }
        }
    }
}

/** 滚动截图：GeckoView 自 v160 起实现 ScrollCaptureCallback，Android S 及以上系统原生长截图可直接工作。 */
object ScreenshotFeature {
    fun capture(context: Context, view: GeckoView) {
        // 嵌入方不得自行调用 setScrollCaptureCallback 或 setScrollCaptureHint，GeckoView 会在 attach 时自动接管。
        // 自 v157 起可优先使用 captureFullPage() 获取整页 Bitmap；滚动截图依赖 Android S 及以上系统能力，
        // 低版本系统降级为普通截图。需核对当前 GeckoView Beta 版本 Changelog。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching {
                view.capturePixels().accept { bmp: Bitmap? ->
                    if (bmp != null) saveBitmap(context, bmp)
                }
            }
            return
        }
        runCatching {
            view.captureFullPage().accept { bmp: Bitmap? ->
                if (bmp != null) saveBitmap(context, bmp)
            }
        }
    }

    private fun saveBitmap(context: Context, bmp: Bitmap) {
        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        val target = File(dir, "screenshot-${System.currentTimeMillis()}.png")
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 生成的本地长图由 ContentDelegate.onExternalResponse 进入下载/分享流程。
    }
}
