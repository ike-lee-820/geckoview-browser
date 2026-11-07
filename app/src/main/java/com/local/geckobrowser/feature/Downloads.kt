package com.local.geckobrowser.feature

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.local.geckobrowser.R
import com.local.geckobrowser.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * onExternalResponse 的 WebResponse 与 WorkManager 处于同一进程，用进程内注册表传递响应体。
 * 不经过任何云端服务。
 */
object DownloadRegistry {
    private val map = ConcurrentHashMap<String, WebResponse>()
    fun register(id: String, response: WebResponse) { map[id] = response }
    fun take(id: String): WebResponse? = map.remove(id)
    fun peek(id: String): WebResponse? = map[id]
}

object DownloadControl {
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    fun setPaused(id: String, v: Boolean) { if (v) paused.add(id) else paused.remove(id) }
    fun setCancelled(id: String, v: Boolean) { if (v) cancelled.add(id) else cancelled.remove(id) }
    fun isPaused(id: String) = paused.contains(id)
    fun isCancelled(id: String) = cancelled.contains(id)
    fun clear(id: String) { paused.remove(id); cancelled.remove(id) }
}

/**
 * 下载执行单元：WorkManager + 断点续传 + 失败重试。
 * ContentDelegate.onExternalResponse 的 content-disposition 在 v160 起是经桌面同等规则校验的
 * 文件名，DownloadEntity.fileName 直接采用该值，不假设为服务器原始值。
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_ID = "download_id"
        private const val CHANNEL = "downloads"

        fun enqueue(context: Context, id: String) {
            val data = Data.Builder().putString(KEY_ID, id).build()
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("download-$id")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun setPaused(id: String, v: Boolean) = DownloadControl.setPaused(id, v)
        fun setCancelled(id: String, v: Boolean) = DownloadControl.setCancelled(id, v)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val db = AppDatabase.build(applicationContext)
        val entity = db.downloadDao().byId(id) ?: return@withContext Result.failure()
        val dir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val target = File(dir, entity.fileName)

        if (DownloadControl.isCancelled(id)) {
            db.downloadDao().finish(id, "CANCELLED", null)
            DownloadControl.clear(id)
            return@withContext Result.success()
        }

        try {
            db.downloadDao().finish(id, "RUNNING", target.absolutePath)
            var received = if (target.exists()) target.length() else 0L
            val response = DownloadRegistry.take(id)

            if (response != null) {
                // 直接使用 GeckoView 交出的响应体；若本地已有部分文件则从该偏移量继续写（断点续传）。
                response.body.use { input ->
                    FileOutputStream(target, true).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var last = System.currentTimeMillis()
                        var windowBytes = 0L
                        while (true) {
                            if (DownloadControl.isPaused(id) || DownloadControl.isCancelled(id)) break
                            val n = input?.read(buf) ?: -1
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            windowBytes += n
                            val now = System.currentTimeMillis()
                            if (now - last >= 500) {
                                val speed = windowBytes * 1000 / (now - last).coerceAtLeast(1)
                                val eta = if (speed > 0 && entity.bytesTotal > 0)
                                    (entity.bytesTotal - received) / speed else -1
                                db.downloadDao().updateProgress(id, received, speed, eta, "RUNNING")
                                last = now; windowBytes = 0
                            }
                        }
                        out.flush()
                    }
                }
            }

            // 响应体已读完但连接中断时，用 Range 请求从断点继续拉取。
            if (entity.bytesTotal > 0 && received < entity.bytesTotal && !DownloadControl.isCancelled(id)) {
                val conn = URL(entity.uri).openConnection() as HttpURLConnection
                conn.setRequestProperty("Range", "bytes=$received-")
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.connect()
                conn.inputStream.use { input ->
                    FileOutputStream(target, true).use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            if (DownloadControl.isPaused(id) || DownloadControl.isCancelled(id)) break
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); received += n
                            db.downloadDao().updateProgress(id, received, 0, -1, "RUNNING")
                        }
                    }
                }
                conn.disconnect()
            }

            when {
                DownloadControl.isCancelled(id) -> {
                    runCatching { target.delete() }
                    db.downloadDao().finish(id, "CANCELLED", null)
                    DownloadControl.clear(id)
                    Result.success()
                }
                DownloadControl.isPaused(id) -> {
                    db.downloadDao().finish(id, "PAUSED", target.absolutePath)
                    Result.retry()   // 恢复时由 ViewModel 重新入队
                }
                else -> {
                    db.downloadDao().finish(id, "COMPLETED", target.absolutePath)
                    notifyCompleted(applicationContext, entity.fileName)
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            db.downloadDao().finish(id, "FAILED", target.absolutePath)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notifyCompleted(context: Context, name: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载完成", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(name)
            .setAutoCancel(true)
            .build()
        manager.notify(name.hashCode(), notification)
    }
}

/** 下载前台服务占位：实际下载由 WorkManager 执行；此 Service 不常驻、不联网、不上传数据。 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
