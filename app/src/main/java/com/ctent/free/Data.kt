package com.ctent.free

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// First server in the list. More servers can be added inside the app (Servers tab).
const val BASE = "https://ctgmovies.com/"

val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "wmv", "flv")
val QUALITY = Regex("(2160p|1080p|720p|480p|4K)", RegexOption.IGNORE_CASE)

fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

data class Entry(
    val name: String,
    val title: String,
    val url: String,
    val isDir: Boolean,
    val isVideo: Boolean,
    val tag: String
)

data class IndexItem(
    val title: String,
    val url: String,
    val server: String,
    val tag: String,
    val path: String
)

object Repo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val cache = HashMap<String, List<Entry>>()

    suspend fun list(url: String): List<Entry> {
        cache[url]?.let { return it }
        val r = fetch(url)
        cache[url] = r
        return r
    }

    suspend fun fetch(url: String): List<Entry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val doc = Jsoup.parse(r.body!!.string(), url)
            doc.select("a[href]").mapNotNull { a ->
                val abs = a.absUrl("href")
                if (abs.isEmpty() || !abs.startsWith(url) || abs.contains("?")) return@mapNotNull null
                val raw = abs.removePrefix(url)
                if (raw.isEmpty() || raw.trimEnd('/').contains('/')) return@mapNotNull null
                val isDir = raw.endsWith("/")
                val name = Uri.decode(raw.trimEnd('/'))
                val ext = name.substringAfterLast('.', "").lowercase()
                val video = !isDir && ext in VIDEO_EXT
                if (!isDir && !video) return@mapNotNull null
                val title = (if (video) name.substringBeforeLast('.') else name)
                    .replace('.', ' ').replace('_', ' ')
                Entry(name, title, abs, isDir, video, QUALITY.find(name)?.value ?: "")
            }.distinctBy { it.url }
                .sortedWith(compareBy({ !it.isDir }, { it.title.lowercase() }))
        }
    }
}

object Servers {
    var list by mutableStateOf(listOf(BASE))

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences("servers", Context.MODE_PRIVATE).getString("list", null)
        list = if (s == null) listOf(BASE) else s.split("\n").filter { it.isNotBlank() }
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences("servers", Context.MODE_PRIVATE)
            .edit().putString("list", list.joinToString("\n")).apply()
    }

    fun add(ctx: Context, raw: String): Boolean {
        var u = raw.trim()
        if (u.isEmpty()) return false
        if (!u.contains("://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        if (u.toHttpUrlOrNull() == null) return false
        if (u !in list) {
            list = list + u
            save(ctx)
        }
        return true
    }

    fun remove(ctx: Context, u: String) {
        list = list - u
        save(ctx)
        Index.removeServer(ctx, u)
    }
}

object Index {
    private const val MAX_DEPTH = 4
    private const val MAX_REQ = 4000

    var items by mutableStateOf<List<IndexItem>>(emptyList())
    var status by mutableStateOf("")
    var running by mutableStateOf(false)
    val serverState = mutableStateMapOf<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(ctx: Context) {
        try {
            val f = File(ctx.filesDir, "index.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                IndexItem(o.getString("t"), o.getString("u"), o.getString("s"), o.getString("q"), o.getString("p"))
            }
        } catch (e: Exception) {
            items = emptyList()
        }
    }

    private fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (i in items) {
                arr.put(JSONObject().put("t", i.title).put("u", i.url).put("s", i.server).put("q", i.tag).put("p", i.path))
            }
            File(ctx.filesDir, "index.json").writeText(arr.toString())
        } catch (e: Exception) { }
    }

    fun removeServer(ctx: Context, server: String) {
        items = items.filter { it.server != server }
        serverState.remove(server)
        save(ctx)
    }

    fun rebuild(ctx: Context) {
        if (running) return
        val servers = Servers.list
        scope.launch {
            running = true
            for (s in servers) {
                serverState[s] = "Scanning…"
                status = "Scanning ${hostOf(s)}…"
                val found = crawl(s)
                if (found == null) {
                    serverState[s] = "Unreachable on this network"
                } else {
                    items = items.filter { it.server != s } + found
                    serverState[s] = "OK"
                    save(ctx)
                }
            }
            status = "Done. ${items.size} titles indexed"
            running = false
        }
    }

    private suspend fun crawl(root: String): List<IndexItem>? = coroutineScope {
        val first = try { Repo.fetch(root) } catch (e: Exception) { return@coroutineScope null }
        val out = Collections.synchronizedList(mutableListOf<IndexItem>())
        val sem = Semaphore(6)
        val requests = AtomicInteger(1)

        suspend fun handle(entries: List<Entry>, depth: Int): Unit = coroutineScope {
            for (e in entries) {
                if (e.isVideo) {
                    out.add(IndexItem(e.title, e.url, root, e.tag, Uri.decode(e.url.removePrefix(root))))
                } else if (e.isDir && depth < MAX_DEPTH && requests.incrementAndGet() <= MAX_REQ) {
                    launch {
                        val sub = sem.withPermit {
                            try { Repo.fetch(e.url) } catch (ex: Exception) { emptyList<Entry>() }
                        }
                        status = "Scanning ${hostOf(root)}… ${out.size} found"
                        handle(sub, depth + 1)
                    }
                }
            }
        }

        handle(first, 1)
        out.toList()
    }
}

fun qualityScore(q: String): Int = when (q.lowercase()) {
    "1080p" -> 10
    "720p" -> 8
    "2160p", "4k" -> 6
    "480p" -> 3
    else -> 0
}

// Best result first: full phrase match, then good quality, then short clean title.
fun searchIndex(query: String, all: List<IndexItem>): List<IndexItem> {
    val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val phrase = tokens.joinToString(" ")
    return all.asSequence()
        .filter { i ->
            val hay = (i.title + " " + i.path).lowercase()
            tokens.all { hay.contains(it) }
        }
        .sortedByDescending { i ->
            val t = i.title.lowercase()
            (if (t.contains(phrase)) 50 else 0) +
                (if (t.startsWith(phrase)) 10 else 0) +
                qualityScore(i.tag) - t.length / 20
        }
        .take(200)
        .toList()
}
