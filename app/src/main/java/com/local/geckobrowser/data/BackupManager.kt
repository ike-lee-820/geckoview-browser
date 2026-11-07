package com.local.geckobrowser.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyProperties
import android.util.Base64
import com.local.geckobrowser.BuildConfig
import com.local.geckobrowser.GeckoBrowserApp
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地数据备份与恢复，全部通过 SAF 读写用户选择的文件，不经过任何网络请求。
 *
 * 背景：项目早期 release 复用 AGP 的 debug 签名，而 CI 每次是全新 runner 且没有预置
 * ~/.android/debug.keystore，导致每个 APK 证书都不同；换成固定签名后用户必须卸载重装一次，
 * 卸载会清空应用私有数据。备份/恢复用于把这次迁移以及今后换机、重装的损失降到最低。
 *
 * 覆盖范围：
 * - Room 的全部用户表（书签、历史、下载、标签组与快照、站点设置、最近关闭、搜索引擎、
 *   语言包、崩溃记录、登录条目），用通用 SQLite 读写，新增实体无需改这里。
 * - DataStore 偏好，由 PreferencesDataStore.exportAll/importAll 处理。
 * - 登录密码：Android Keystore 的密钥在卸载后不可恢复，直接复制密文没有意义；
 *   导出时用用户口令（PBKDF2WithHmacSHA256 + AES/GCM）重新加密明文，
 *   导入时用同一口令解密，再用新设备的 Keystore 重新加密写入。
 *
 * 备份文件是纯 JSON，用户可自行保存、检查；不含任何云端地址。
 */
object BackupManager {

    private const val FORMAT = 1
    private const val BACKUP_PREFIX = "geckobrowser-backup-"

    private val random = SecureRandom()

    fun defaultFileName(): String =
        BACKUP_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

    /** 导出到 SAF 选择的 Uri。返回简要结果文本。 */
    suspend fun exportTo(context: Context, uri: Uri, passphrase: CharArray?): String {
        val app = context.applicationContext as GeckoBrowserApp
        val db = app.database.openHelper.writableDatabase

        // 先 checkpoint，确保 WAL 里的数据全部落进主库文件，导出的行才是完整的。
        runCatching { db.query("PRAGMA wal_checkpoint(FULL)").close() }

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("createdAt", System.currentTimeMillis())
        root.put("packageName", context.packageName)
        root.put("versionName", BuildConfig.VERSION_NAME)
        root.put("buildNumber", BuildConfig.BUILD_NUMBER)
        root.put("signingSha256", BuildConfig.SIGNING_SHA256)
        root.put("device", "${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")

        // 1) 全部 Room 用户表
        val tables = JSONArray()
        val tableNames = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table','android_metadata') ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) tableNames.add(c.getString(0))
        }
        var rowCount = 0
        for (table in tableNames) {
            val tableJson = JSONObject()
            tableJson.put("name", table)
            val columns = JSONArray()
            val rows = JSONArray()
            db.query("SELECT * FROM \"$table\"").use { c ->
                val names = c.columnNames
                names.forEach { columns.put(it) }
                while (c.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until c.columnCount) {
                        row.put(encodeCell(c, i))
                    }
                    rows.put(row)
                    rowCount++
                }
            }
            tableJson.put("columns", columns)
            tableJson.put("rows", rows)
            tables.put(tableJson)
        }
        root.put("tables", tables)

        // 2) 登录密码：从 Keystore 解密后用口令再加密，供跨安装迁移。
        val secureLogins = JSONArray()
        var skippedLogins = 0
        db.query("SELECT * FROM \"logins\"").use { c ->
            while (c.moveToNext()) {
                val guid = c.getString(c.getColumnIndexOrThrow("guid"))
                val username = runCatching {
                    c.getString(c.getColumnIndexOrThrow("username"))
                }.getOrDefault("")
                val cipherText = runCatching {
                    c.getString(c.getColumnIndexOrThrow("passwordCipher"))
                }.getOrDefault("")
                val iv = runCatching {
                    c.getString(c.getColumnIndexOrThrow("passwordIv"))
                }.getOrDefault("")
                val plain = runCatching {
                    PasswordCrypto.decrypt(PasswordCrypto.Sealed(cipherText, iv))
                }.getOrNull()
                if (plain == null || passphrase == null || passphrase.isEmpty()) {
                    skippedLogins++
                    continue
                }
                val item = JSONObject()
                item.put("guid", guid)
                item.put("username", username)
                item.put("sealed", sealWithPassphrase(plain, passphrase))
                secureLogins.put(item)
            }
        }
        root.put("secureLogins", secureLogins)
        root.put("skippedLogins", skippedLogins)
        root.put("hasSecureLogins", secureLogins.length() > 0)

        // 3) DataStore 偏好
        val prefsJson = JSONObject()
        app.preferences.exportAll().forEach { (k, v) ->
            prefsJson.put(k, encodePrefValue(v))
        }
        root.put("preferences", prefsJson)

        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("无法打开备份文件输出流")

        return "备份已导出：$rowCount 条记录，${tableNames.size} 张表；" +
            if (skippedLogins > 0) "有 $skippedLogins 条密码未提供口令或未解密，未导出。" else "密码已按口令加密导出。"
    }

    /** 从 SAF 选择的 Uri 恢复。passphrase 为导出时使用的口令；无密码备份可传 null。 */
    suspend fun importFrom(context: Context, uri: Uri, passphrase: CharArray?): String {
        val app = context.applicationContext as GeckoBrowserApp
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法读取备份文件")
        val root = JSONObject(text)
        if (root.optInt("format", 0) != FORMAT) error("备份格式不兼容（format=${root.optInt("format", 0)}）")
        val db = app.database.openHelper.writableDatabase

        // 口令解锁的密码：guid -> 明文
        val recovered = HashMap<String, String>()
        val secure = root.optJSONArray("secureLogins") ?: JSONArray()
        for (i in 0 until secure.length()) {
            val item = secure.optJSONObject(i) ?: continue
            val sealed = item.optJSONObject("sealed") ?: continue
            if (passphrase == null || passphrase.isEmpty()) continue
            val plain = runCatching { openWithPassphrase(sealed, passphrase) }.getOrNull() ?: continue
            recovered[item.optString("guid")] = plain
            recovered["username:" + item.optString("guid")] = item.optString("username")
        }
        val skippedSecure = secure.length() - recovered.count { !it.key.startsWith("username:") }

        var restoredRows = 0
        db.beginTransaction()
        try {
            val tables = root.optJSONArray("tables") ?: JSONArray()
            for (t in 0 until tables.length()) {
                val tableJson = tables.optJSONObject(t) ?: continue
                val table = tableJson.optString("name")
                if (table.isBlank() || table.startsWith("sqlite_") ||
                    table == "room_master_table" || table == "android_metadata") continue
                val columnArray = tableJson.optJSONArray("columns") ?: continue
                val columns = (0 until columnArray.length()).map { columnArray.optString(it) }
                if (columns.isEmpty()) continue
                val placeholders = columns.joinToString(",") { "?" }
                val quoted = columns.joinToString(",") { "\"$it\"" }
                val sql = "INSERT OR REPLACE INTO \"$table\" ($quoted) VALUES ($placeholders)"
                val rows = tableJson.optJSONArray("rows") ?: JSONArray()
                for (r in 0 until rows.length()) {
                    val row = rows.optJSONArray(r) ?: continue
                    val args = arrayOfNulls<Any>(columns.size)
                    for (i in columns.indices) {
                        val cell = if (i < row.length()) row.optJSONObject(i) else null
                        var value = decodeCell(cell)
                        // logins 表：用新设备的 Keystore 重新加密口令解锁出来的明文。
                        if (table == "logins") {
                            val guidIndex = columns.indexOf("guid")
                            val guid = if (guidIndex >= 0) args[guidIndex]?.toString() ?: "" else ""
                            val plain = recovered[guid]
                            if (columns[i] == "passwordCipher" || columns[i] == "passwordIv") {
                                if (plain != null) {
                                    val sealedNew = PasswordCrypto.encrypt(plain)
                                    value = if (columns[i] == "passwordCipher") sealedNew.cipher else sealedNew.iv
                                } else {
                                    value = "" // 未解锁的条目置空，避免写入无法解密的旧密文
                                }
                            }
                            if (columns[i] == "username" && plain != null) {
                                recovered["username:$guid"]?.let { value = it }
                            }
                        }
                        args[i] = value
                    }
                    db.execSQL(sql, args)
                    restoredRows++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 偏好：按类型写回，随后刷新内存缓存。
        val prefsObj = root.optJSONObject("preferences")
        if (prefsObj != null) {
            val map = LinkedHashMap<String, Any?>()
            prefsObj.keys().forEach { k -> map[k] = decodePrefValue(prefsObj.optJSONObject(k)) }
            app.preferences.importAll(map)
        }

        return buildString {
            append("恢复完成：$restoredRows 条记录。")
            if (skippedSecure > 0) append("有 $skippedSecure 条密码因口令不正确未提供，未恢复（其余数据不受影响）。")
            append("如果界面未立即刷新，请重新打开应用。")
        }
    }

    /** 备份文件里是否包含需要口令的密码条目，用于 UI 决定是否弹口令框。 */
    fun needsPassphrase(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return false
        JSONObject(text).optBoolean("hasSecureLogins", false)
    }.getOrDefault(false)

    // ---------------------------------------------------------------- SQLite 值编解码

    private fun encodeCell(c: android.database.Cursor, i: Int): JSONObject {
        val o = JSONObject()
        when (c.getType(i)) {
            android.database.Cursor.FIELD_TYPE_NULL -> o.put("t", "null")
            android.database.Cursor.FIELD_TYPE_INTEGER -> { o.put("t", "int"); o.put("v", c.getLong(i)) }
            android.database.Cursor.FIELD_TYPE_FLOAT -> { o.put("t", "float"); o.put("v", c.getDouble(i)) }
            android.database.Cursor.FIELD_TYPE_BLOB -> {
                o.put("t", "blob")
                o.put("v", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
            }
            else -> { o.put("t", "text"); o.put("v", c.getString(i) ?: "") }
        }
        return o
    }

    private fun decodeCell(cell: JSONObject?): Any? {
        if (cell == null) return null
        return when (cell.optString("t")) {
            "null" -> null
            "int" -> cell.optLong("v")
            "float" -> cell.optDouble("v")
            "blob" -> Base64.decode(cell.optString("v"), Base64.NO_WRAP)
            else -> cell.optString("v", "")
        }
    }

    // ---------------------------------------------------------------- DataStore 值编解码

    private fun encodePrefValue(v: Any?): JSONObject {
        val o = JSONObject()
        when (v) {
            null -> o.put("t", "null")
            is Boolean -> { o.put("t", "bool"); o.put("v", v) }
            is Int -> { o.put("t", "int"); o.put("v", v.toLong()) }
            is Long -> { o.put("t", "int"); o.put("v", v) }
            is Float -> { o.put("t", "float"); o.put("v", v.toDouble()) }
            is Double -> { o.put("t", "float"); o.put("v", v) }
            is Set<*> -> { o.put("t", "stringset"); o.put("v", JSONArray(v.map { it.toString() })) }
            else -> { o.put("t", "string"); o.put("v", v.toString()) }
        }
        return o
    }

    private fun decodePrefValue(cell: JSONObject?): Any? {
        if (cell == null) return null
        return when (cell.optString("t")) {
            "null" -> null
            "bool" -> cell.optBoolean("v")
            "int" -> cell.optLong("v")
            "float" -> cell.optDouble("v").toFloat()
            "stringset" -> {
                val a = cell.optJSONArray("v") ?: JSONArray()
                (0 until a.length()).map { a.optString(it) }.toSet()
            }
            else -> cell.optString("v", "")
        }
    }

    // ---------------------------------------------------------------- 口令加密（PBKDF2 + AES/GCM）

    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    private fun derive(passphrase: CharArray, salt: ByteArray) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, 256))
                .encoded,
            KeyProperties.KEY_ALGORITHM_AES
        )

    private fun sealWithPassphrase(plain: String, passphrase: CharArray): JSONObject {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        }
    }

    private fun openWithPassphrase(sealed: JSONObject, passphrase: CharArray): String {
        val salt = Base64.decode(sealed.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(sealed.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(sealed.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
