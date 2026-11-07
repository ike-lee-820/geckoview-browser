package com.local.geckobrowser.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 书签：支持文件夹层级与拖拽排序（position），导入导出走本地文件流。 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val folderId: String?,
    val createdAt: Long,
    val position: Int,
    val isFolder: Boolean = false
)

/** 历史：GeckoView 本身不存储历史，由 HistoryDelegate 写入。 */
@Entity(tableName = "history", indices = [androidx.room.Index(value = ["url"])])
data class HistoryEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val visitedAt: Long
)

/** 下载记录：进行中 / 已完成 / 失败三分区由 status 区分。 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val fileName: String,
    val mimeType: String?,
    var bytesReceived: Long = 0,
    var bytesTotal: Long = -1,
    var status: String = "QUEUED",
    var speedBytesPerSec: Long = 0,
    var etaSeconds: Long = -1,
    var localPath: String? = null,
    val createdAt: Long
)

/** 标签分组：不是 GeckoView 原生概念，全部在应用层维护。 */
@Entity(tableName = "tab_groups")
data class TabGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val collapsed: Boolean
)

/** 标签快照：进程被杀后恢复标签列表、分组、激活位置与滚动位置。 */
@Entity(tableName = "tab_snapshots")
data class TabSnapshotEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val isPinned: Boolean,
    val groupId: String?,
    val lastUsed: Long,
    val scrollX: Int,
    val scrollY: Int,
    val readerModeActive: Boolean,
    val sessionStateSerialized: String?,
    val isActive: Boolean = false
)

/** 站点级权限与设置例外，全部本地。 */
@Entity(tableName = "site_settings")
data class SiteSettingEntity(
    @PrimaryKey val origin: String,
    val camera: Boolean? = null,
    val microphone: Boolean? = null,
    val geolocation: Boolean? = null,
    val notification: Boolean? = null,
    val autoplay: Boolean? = null,
    val javascriptEnabled: Boolean = true,
    val httpsException: Boolean = false
)

/** 登录条目：密码字段由 Android Keystore 加密后写入，解密需设备凭证/生物识别。 */
@Entity(tableName = "logins")
data class LoginEntity(
    @PrimaryKey val guid: String,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val passwordCipher: String,
    val passwordIv: String,
    val lastUsed: Long = 0
)

/** 最近关闭标签，本地保留 20 条，支持撤销恢复。 */
@Entity(tableName = "recent_closed")
data class RecentClosedTabEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val groupId: String?,
    val closedAt: Long,
    val sessionStateSerialized: String?
)

/** 搜索引擎：内置 + 用户自定义 URL 模板（含 %s 占位符）。 */
@Entity(tableName = "search_engines")
data class SearchEngineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val template: String,
    val isDefault: Boolean,
    val isCustom: Boolean
)

/** 本地翻译语言包记录：模型下载是唯一允许的用户显式触发的网络操作。 */
@Entity(tableName = "translation_models")
data class TranslationModelEntity(
    @PrimaryKey val languagePair: String,
    val fromLanguage: String,
    val toLanguage: String,
    val sizeBytes: Long,
    val installed: Boolean,
    val localPath: String?
)

/** 崩溃诊断日志，仅本地保存，绝不上传。 */
@Entity(tableName = "crash_logs")
data class CrashEntity(
    @PrimaryKey val id: String,
    val tabId: String,
    val timestamp: Long,
    val detail: String
)

/** 扩展列表 UI 展示模型（非 Room 实体，由 WebExtensionController.list() 映射）。 */
data class ExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val allowedInPrivate: Boolean
)
