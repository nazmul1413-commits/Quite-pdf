package com.quietpdf.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

data class DocItem(val uri: String, val name: String, val lastPage: Int = 0)
data class Mark(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { QuietPdfApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietPdfApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quiet_pdf", 0) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var currentName by remember { mutableStateOf("") }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var descriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(listOf<Int>()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf(listOf<DocItem>()) }
    var marks by remember { mutableStateOf(listOf<Mark>()) }
    var drawing by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    fun saveDocs() {
        prefs.edit().putString("docs", docs.joinToString("\n") { "${it.uri}\t${it.name}\t${it.lastPage}" }).apply()
    }
    fun loadDocs() {
        docs = prefs.getString("docs", "")!!.lines().filter { it.contains('\t') }.mapNotNull {
            val p = it.split('\t')
            if (p.size >= 3) DocItem(p[0], p[1], p[2].toIntOrNull() ?: 0) else null
        }
    }
    fun saveMarks() {
        val encoded = marks.joinToString("\n") { "${it.page},${it.left},${it.top},${it.right},${it.bottom}" }
        prefs.edit().putString("marks_${currentUri}", encoded).apply()
    }
    fun openPdf(uri: Uri, name: String) {
        try {
            renderer?.close()
            descriptor?.close()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            val r = PdfRenderer(pfd)
            descriptor = pfd
            renderer = r
            currentUri = uri
            currentName = name
            pageCount = r.pageCount
            page = prefs.getInt("page_${uri}", 0).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
            marks = prefs.getString("marks_${uri}", "")!!.lines().mapNotNull { line ->
                val a = line.split(",")
                if (a.size == 5) runCatching { Mark(a[0].toInt(), a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a[4].toFloat()) }.getOrNull() else null
            }
            docs = listOf(DocItem(uri.toString(), name, page)) + docs.filterNot { it.uri == uri.toString() }
            saveDocs()
            message = ""
        } catch (e: Exception) { message = "Could not open this PDF: ${e.message}" }
    }
    fun renderPage(index: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        try {
            r.openPage(index).use { pdfPage ->
                val width = 1200
                val height = (width.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AndroidColor.WHITE)
                pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap = bmp
            }
            page = index
            currentUri?.let { prefs.edit().putInt("page_$it", index).apply() }
            docs = docs.map { if (it.uri == currentUri.toString()) it.copy(lastPage = index) else it }
            saveDocs()
        } catch (_: Exception) { message = "Page could not be rendered." }
    }

    LaunchedEffect(Unit) { loadDocs() }
    LaunchedEffect(renderer, page) { if (renderer != null) renderPage(page) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            openPdf(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document")
        }
    }

    MaterialTheme(colorScheme = if (dark || night) darkColorScheme() else lightColorScheme()) {
        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Dark theme", fontWeight = FontWeight.Medium)
                                Text("Use a darker app interface", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = dark, onCheckedChange = {
                                dark = it
                                prefs.edit().putBoolean("dark", it).apply()
                            })
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Night reading", fontWeight = FontWeight.Medium)
                                Text("Use the darker reading interface", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = night, onCheckedChange = { night = it })
                        }
                        Text("Quiet PDF · Offline PDF reader", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
                shape = MaterialTheme.shapes.extraLarge
            )
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Quiet PDF", fontWeight = FontWeight.SemiBold)
                            if (currentUri != null) Text(currentName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "Settings") }
                        IconButton(onClick = { night = !night }) { Icon(Icons.Default.DarkMode, "Night reading") }
                        IconButton(onClick = { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() }) {
                            Icon(Icons.Default.Contrast, "Toggle theme")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Default.Add, "Open PDF")
                }
            }
        ) { padding ->
            if (currentUri == null) {
                Column(Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
                    Text("Your library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("A quiet place for your documents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(22.dp))
                    if (docs.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PictureAsPdf, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text("No PDFs yet")
                                Text("Tap + to open a document", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn {
                            items(docs, key = { it.uri }) { doc ->
                                ListItem(
                                    headlineContent = { Text(doc.name, maxLines = 1) },
                                    supportingContent = { Text("Last read · page ${doc.lastPage + 1}") },
                                    leadingContent = { Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = { IconButton(onClick = { docs = docs.filterNot { it.uri == doc.uri }; saveDocs() }) { Icon(Icons.Default.Close, "Remove from recent") } },
                                    modifier = Modifier.fillMaxWidth().clickable { openPdf(Uri.parse(doc.uri), doc.name) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { renderPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }
                        Text("${page + 1} / $pageCount", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { renderPage(page + 1) }, enabled = page < pageCount - 1) { Icon(Icons.Default.ChevronRight, "Next page") }
                        IconButton(onClick = {
                            val key = "bookmarks_${currentUri}"
                            val old = prefs.getString(key, "")!!.split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
                            val updated = if (page in old) old - page else old + page
                            prefs.edit().putString(key, updated.joinToString(",")).apply()
                            message = if (page in old) "Bookmark removed" else "Page bookmarked"
                        }) { Icon(Icons.Default.BookmarkAdd, "Toggle bookmark") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search in this PDF") })
                        IconButton(onClick = { searching = true; searchResults = emptyList() }) { Icon(Icons.Default.Search, "Search text") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = drawing, onClick = { drawing = !drawing }, label = { Text(if (drawing) "Highlight: on" else "Highlight") }, leadingIcon = { Icon(Icons.Default.Highlight, null) })
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val key = "bookmarks_${currentUri}"
                            val pages = prefs.getString(key, "")!!.split(",").filter { it.isNotBlank() }
                            message = if (pages.isEmpty()) "No bookmarks yet" else "Bookmarked pages: ${pages.mapNotNull { it.toIntOrNull() }.sorted().joinToString { (it + 1).toString() }}"
                        }) { Text("Bookmarks") }
                    }
                    bitmap?.let { bmp ->
                        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                                androidx.compose.foundation.Image(bitmap = bmp.asImageBitmap(), contentDescription = "PDF page ${page + 1}", modifier = Modifier.fillMaxWidth())
                                Canvas(
                                    Modifier.fillMaxWidth().height((bmp.height.toFloat() / bmp.width * (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 16)).dp)
                                        .pointerInput(drawing, page) {
                                            if (drawing) detectDragGestures(
                                                onDragStart = { dragStart = it; dragEnd = it },
                                                onDrag = { change, _ -> dragEnd = change.position },
                                                onDragEnd = {
                                                    val s = dragStart; val e = dragEnd
                                                    if (s != null && e != null) {
                                                        val w = size.width.toFloat(); val h = size.height.toFloat()
                                                        marks = marks + Mark(page, minOf(s.x,e.x)/w, minOf(s.y,e.y)/h, maxOf(s.x,e.x)/w, maxOf(s.y,e.y)/h)
                                                        saveMarks()
                                                    }
                                                    dragStart = null; dragEnd = null
                                                }
                                            )
                                        }
                                ) {
                                    marks.filter { it.page == page }.forEach {
                                        drawRect(Color(0x66FFEB3B), Offset(it.left*size.width, it.top*size.height), Size((it.right-it.left)*size.width, (it.bottom-it.top)*size.height))
                                    }
                                    val s = dragStart; val e = dragEnd
                                    if (drawing && s != null && e != null) drawRect(Color(0x66FFEB3B), Offset(minOf(s.x,e.x), minOf(s.y,e.y)), Size(kotlin.math.abs(e.x-s.x), kotlin.math.abs(e.y-s.y)))
                                }
                            }
                        }
                    } ?: Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    if (message.isNotBlank()) Text(message, Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(
                            onClick = { currentUri = null; renderer?.close(); renderer = null; descriptor?.close(); descriptor = null },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.LibraryBooks, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Library")
                        }
                        FilledTonalButton(
                            onClick = { marks = marks.filterNot { it.page == page }; saveMarks(); message = "Highlights cleared on this page" },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.HighlightOff, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clear highlights")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(searching) {
        if (searching && query.isNotBlank() && currentUri != null) {
            val uri = currentUri!!
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "search.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                    PDDocument.load(temp).use { doc ->
                        (0 until doc.numberOfPages).filter { index ->
                            val stripper = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }
                            stripper.getText(doc).contains(query, ignoreCase = true)
                        }
                    }
                }.getOrDefault(emptyList())
            }
            searchResults = result
            message = if (result.isEmpty()) "No matches found" else "Found on pages: ${result.map { it + 1 }.joinToString()}"
            searching = false
        } else if (searching) searching = false
    }
}
