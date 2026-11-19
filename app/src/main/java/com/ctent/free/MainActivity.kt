package com.ctent.free

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Servers.load(applicationContext)
        Index.load(applicationContext)
        if (Index.items.isEmpty()) Index.rebuild(applicationContext)
        setContent { MaterialTheme { App() } }
    }
}

fun play(ctx: Context, url: String) {
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", url))
}

fun startDownload(ctx: Context, title: String, url: String) {
    try {
        val fileName = Uri.decode(url.trimEnd('/').substringAfterLast('/'))
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        dm.enqueue(req)
        Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
    } catch (ex: Exception) {
        Toast.makeText(ctx, "Download failed: ${ex.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun App() {
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<String>() }
    val tabs = listOf("🔎" to "Search", "📂" to "Browse", "⚙" to "Servers")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SearchTab()
                1 -> BrowseTab(stack)
                else -> ServersTab()
            }
        }
    }
}

@Composable
fun SearchTab() {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<IndexItem>>(emptyList()) }
    val all = Index.items

    LaunchedEffect(query, all) {
        delay(250)
        results = withContext(Dispatchers.Default) { searchIndex(query, all) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search all servers") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        Text(
            if (Index.running) Index.status else "${all.size} titles indexed from ${Servers.list.size} server(s)",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        when {
            all.isEmpty() && !Index.running -> Text(
                "Nothing indexed yet. Open the Servers tab and tap Update index.",
                modifier = Modifier.padding(16.dp)
            )
            query.isNotBlank() && results.isEmpty() -> Text(
                "No result. Try fewer words, or add another server.",
                modifier = Modifier.padding(16.dp)
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.url }) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { play(ctx, r.url) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(r.tag, hostOf(r.server)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { startDownload(ctx, r.title, r.url) }) { Text("⬇") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseTab(stack: MutableList<String>) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val current = stack.lastOrNull()

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    LaunchedEffect(current, reload) {
        if (current == null) return@LaunchedEffect
        loading = true
        error = null
        query = ""
        try {
            entries = Repo.list(current)
        } catch (e: Exception) {
            error = e.message ?: "Error"
            entries = emptyList()
        }
        loading = false
    }

    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            Text("Pick a server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            LazyColumn {
                items(Servers.list) { s ->
                    Text(
                        "📂  " + hostOf(s),
                        modifier = Modifier.fillMaxWidth().clickable { stack.add(s) }.padding(16.dp)
                    )
                    HorizontalDivider()
                }
            }
        }
        return
    }

    val root = Servers.list.firstOrNull { current.startsWith(it) } ?: current
    val filtered = entries.filter { it.title.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    Uri.decode(current.removePrefix(root)).ifEmpty { hostOf(root) },
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = { TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text("←") } },
            actions = { TextButton(onClick = { Repo.cache.remove(current); reload++ }) { Text("⟳") } }
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter this folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Cannot load: $error")
                    Text("This server may not be reachable on this network.")
                    Button(onClick = { reload++ }) { Text("Retry") }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.url }) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (e.isDir) stack.add(e.url) else play(ctx, e.url) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (e.isDir) "📁" else "🎬")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (e.tag.isNotEmpty()) Text(e.tag, style = MaterialTheme.typography.labelSmall)
                        }
                        if (e.isVideo) TextButton(onClick = { startDownload(ctx, e.title, e.url) }) { Text("⬇") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ServersTab() {
    val ctx = LocalContext.current
    var newUrl by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                label = { Text("Add server address") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (Servers.add(ctx, newUrl)) newUrl = ""
                else Toast.makeText(ctx, "Bad address", Toast.LENGTH_SHORT).show()
            }) { Text("Add") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { Index.rebuild(ctx.applicationContext) },
            enabled = !Index.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (Index.running) Index.status else "Update index (scan all servers)") }
        Text(
            "Scan on each new network. A server that is not reachable here is skipped, and its old results stay.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        LazyColumn {
            items(Servers.list) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hostOf(s))
                        val n = Index.items.count { it.server == s }
                        Text(
                            "$n items · ${Index.serverState[s] ?: "not scanned"}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = { Servers.remove(ctx, s) }) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
    }
}
