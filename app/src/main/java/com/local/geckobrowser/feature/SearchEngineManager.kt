package com.local.geckobrowser.feature

import android.content.Context
import android.net.Uri
import com.local.geckobrowser.data.AppDatabase
import com.local.geckobrowser.data.BookmarkEntity
import com.local.geckobrowser.data.PreferencesDataStore
import com.local.geckobrowser.data.SearchEngineEntity
import com.local.geckobrowser.browser.TabState
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import java.util.Locale

data class Suggestion(val title: String, val url: String, val type: SuggestionType)
enum class SuggestionType { HISTORY, BOOKMARK, OPEN_TAB, PINNED_TAB, SEARCH }

/** 统一 OmniBox：URL 直接导航，非 URL 文本走本地配置的默认搜索引擎。 */
object SearchEngineManager {

    val builtIn = listOf(
        SearchEngineEntity("google", "Google", "https://www.google.com/search?q=%s", true, false),
        SearchEngineEntity("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s", false, false),
        SearchEngineEntity("bing", "Bing", "https://www.bing.com/search?q=%s", false, false),
        SearchEngineEntity("baidu", "百度", "https://www.baidu.com/s?wd=%s", false, false)
    )

    fun isUrl(text: String): Boolean {
        val t = text.trim()
        if (t.contains(' ') || t.contains('\n')) return false
        if (t.startsWith("about:") || t.startsWith("file:") || t.startsWith("data:")) return true
        val lower = t.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        val host = t.substringBefore('/').substringBefore(':')
        return host.contains('.') && !host.endsWith('.') && !host.startsWith('.')
    }

    fun normalizeUrl(text: String): String {
        val t = text.trim()
        return if (t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("about:") || t.startsWith("file:") || t.startsWith("data:")
        ) t else "https://$t"
    }

    suspend fun searchUrl(text: String, prefs: PreferencesDataStore? = null): String {
        val db = AppDatabase.build(appContextHolder)
        val engine = db.searchEngineDao().default() ?: builtIn.first()
        return engine.template.replace("%s", Uri.encode(text.trim()))
    }

    // 由 Application 在启动时注入，避免 object 持有 Activity 引用。
    @Volatile lateinit var appContextHolder: Context

    suspend fun suggest(
        text: String, db: AppDatabase, tabs: List<TabState>
    ): List<Suggestion> {
        if (text.isBlank()) return emptyList()
        val q = text.trim()
        val out = mutableListOf<Suggestion>()
        db.historyDao().search(q).first().take(4).forEach {
            out += Suggestion(it.title, it.url, SuggestionType.HISTORY)
        }
        db.bookmarkDao().search(q).first().take(4).forEach {
            out += Suggestion(it.title, it.url, SuggestionType.BOOKMARK)
        }
        tabs.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(4).forEach {
            out += Suggestion(
                it.title, it.url,
                if (it.isPinned) SuggestionType.PINNED_TAB else SuggestionType.OPEN_TAB
            )
        }
        if (!isUrl(q)) out += Suggestion("搜索：$q", "search:$q", SuggestionType.SEARCH)
        return out.distinctBy { it.url }
    }

    fun addCustomEngine(db: AppDatabase, name: String, template: String) {
        // 模板必须含 %s 占位符；自定义引擎存本地 Room。
        if (!template.contains("%s")) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            db.searchEngineDao().insert(
                SearchEngineEntity("custom-${System.currentTimeMillis()}", name, template, false, true)
            )
        }
    }
}

/** 本地密码生成器：长度、大小写、数字、符号可配置。 */
object PasswordGenerator {
    private val random = SecureRandom()
    fun generate(
        length: Int = 20, upper: Boolean = true, lower: Boolean = true,
        digits: Boolean = true, symbols: Boolean = true
    ): String {
        val pools = buildList {
            if (lower) add("abcdefghijkmnopqrstuvwxyz")
            if (upper) add("ABCDEFGHJKLMNPQRSTUVWXYZ")
            if (digits) add("23456789")
            if (symbols) add("!@#\$%&*+-=?")
        }
        val all = pools.joinToString("").ifEmpty { "abcdefghijkmnopqrstuvwxyz" }
        val chars = mutableListOf<Char>()
        pools.forEach { chars += it[random.nextInt(it.length)] }
        while (chars.size < length) chars += all[random.nextInt(all.length)]
        chars.shuffle(random)
        return chars.take(length).joinToString("")
    }
}

/** 书签导入/导出：仅使用 SAF 与本地文件流，不经过任何网络请求。 */
object BookmarkHtmlIo {
    fun export(bookmarks: List<BookmarkEntity>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Bookmarks</TITLE><H1>Bookmarks</H1><DL><p>")
        bookmarks.forEach {
            appendLine("<DT><A HREF=\"${it.url}\" ADD_DATE=\"${it.createdAt / 1000}\">${it.title}</A>")
        }
        appendLine("</DL><p>")
    }

    fun import(html: String): List<BookmarkEntity> {
        val regex = Regex("""<A[^>]+HREF="([^"]+)"[^>]*>([^<]*)</A>""", RegexOption.IGNORE_CASE)
        return regex.findAll(html).map { m ->
            BookmarkEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = m.groupValues[2].ifBlank { m.groupValues[1] },
                url = m.groupValues[1],
                folderId = null, createdAt = System.currentTimeMillis(), position = 0
            )
        }.toList()
    }
}
