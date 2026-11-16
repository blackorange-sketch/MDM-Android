package dev.blackorange.mdm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** A link or file that arrived from outside; the UI adds it. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = linkFrom(intent)
        setContent {
            MaterialTheme {
                MainScreen(
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = linkFrom(intent)
    }

    /** The link in a VIEW or SEND intent: a magnet or address as text, or a .torrent as content://. */
    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.toString()
        Intent.ACTION_SEND -> {
            // Apps share "Title https://…" as often as a bare link: take the first link in it.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            text?.let { LINK.find(it)?.value ?: it.trim() }
                ?: streamOf(intent)?.toString()
        }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private companion object {
        val LINK = Regex("""(magnet:\?\S+|(https?|ftp)://\S+)""", RegexOption.IGNORE_CASE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(incoming: String?, onIncomingHandled: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    // Downloads go to the public Download folder, which needs access to all files (Android 11+)
    // or the storage permission (older).
    var hasStorage by remember { mutableStateOf(storageAccess(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hasStorage = storageAccess(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val legacyStorage = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasStorage = storageAccess(context) }
    LaunchedEffect(hasStorage) {
        if (hasStorage) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            withContext(Dispatchers.IO) { Native.setDownloadDir(dir.absolutePath) }
        }
    }

    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    fun report(answer: String) {
        Native.errorOf(answer)?.let { scope.launch { snackbar.showSnackbar(it) } }
    }

    /** Adds a link, magnet, or a content:// .torrent file. */
    fun add(link: String) {
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                if (link.startsWith("content://") || link.startsWith("file://")) {
                    val copy = File(context.cacheDir, "added-${System.nanoTime()}.torrent")
                    context.contentResolver.openInputStream(Uri.parse(link))?.use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    }
                    Native.addTorrentFile(copy.absolutePath)
                } else {
                    Native.add(link)
                }
            }
            report(answer)
            DownloadService.start(context)
        }
    }

    val pickTorrent = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { add(it.toString()) } }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(incoming) {
        incoming?.let { add(it); onIncomingHandled() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { Native.list() }?.let { items = it }
            delay(1000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MDM") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text("Add link") }
                Button(onClick = { pickTorrent.launch(arrayOf("*/*")) }) { Text(".torrent file") }
            }
            LazyColumn(
                Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ItemCard(
                        item,
                        onPause = { scope.launch(Dispatchers.IO) { report(Native.pause(item.id)) } },
                        onResume = {
                            scope.launch(Dispatchers.IO) { report(Native.resume(item.id)) }
                            DownloadService.start(context)
                        },
                        onRemove = { scope.launch(Dispatchers.IO) { Native.remove(item.id, false) } },
                    )
                }
            }
        }
    }

    if (!hasStorage) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Access to the Download folder") },
            text = { Text("To save files into your Download folder, MDM needs access to files. Until then it saves into its own folder.") },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    } else {
                        legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { hasStorage = true }) { Text("Later") } },
        )
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Link or magnet") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://… or magnet:?…") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) add(text.trim())
                    showAdd = false
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ItemCard(item: Item, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (item.total > 0) {
                LinearProgressIndicator(
                    progress = { (item.done.toFloat() / item.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (item.active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val size = if (item.total > 0) "${formatSize(item.done)} / ${formatSize(item.total)}" else formatSize(item.done)
            val speed = if (item.speed > 0) " · ${formatSize(item.speed)}/s" else ""
            Text("${item.status} · $size$speed", style = MaterialTheme.typography.bodySmall)
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.active) TextButton(onClick = onPause) { Text("Pause") }
                else if (item.status == "paused" || item.status == "error") TextButton(onClick = onResume) { Text("Resume") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private fun storageAccess(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
