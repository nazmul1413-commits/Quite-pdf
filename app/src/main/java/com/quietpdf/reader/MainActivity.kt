package com.quietpdf.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import android.util.Base64
import android.util.LruCache
import android.graphics.Paint as AndroidPaint
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

data class DocItem(val uri: String, val name: String, val lastPage: Int = 0)
data class Annotation(val page: Int, val kind: String, val points: List<Offset>, val color: Int = 0xFFFFD600.toInt(), val width: Float = 3f, val text: String = "")

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
    val scope = rememberCoroutineScope()
    val renderMutex = remember { Mutex() }
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
    var searchBarOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf(listOf<DocItem>()) }
    var annotations by remember { mutableStateOf(listOf<Annotation>()) }
    var activeTool by remember { mutableStateOf("HIGHLIGHTER") }
    var annotationColor by remember { mutableIntStateOf(0xFFFFEB3B.toInt()) }
    val pageBitmapCache = remember(renderer) {
        object : LruCache<Int, Bitmap>(128 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    var pendingText by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var textDraft by remember { mutableStateOf("") }
    var drawing by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var documentZoom by remember { mutableFloatStateOf(1f) }
    var documentPan by remember { mutableStateOf(Offset.Zero) }
    val latestDocumentZoom by rememberUpdatedState(documentZoom)
    var annotationUndo by remember { mutableStateOf(listOf<List<Annotation>>()) }
    var annotationRedo by remember { mutableStateOf(listOf<List<Annotation>>()) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = page)

    BackHandler(enabled = fullScreen) { fullScreen = false }
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
    fun saveAnnotations() {
        val encoded = annotations.joinToString("\n") { item ->
            val pts = item.points.joinToString(";") { "${it.x},${it.y}" }
            val txt = Base64.encodeToString(item.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            listOf(item.page,item.kind,item.color,item.width,txt,pts).joinToString("|")
        }
        prefs.edit().putString("annotations_${currentUri}", encoded).apply()
    }
    fun updateAnnotations(next: List<Annotation>) {
        annotationUndo = annotationUndo + listOf(annotations)
        annotationRedo = emptyList()
        annotations = next
        saveAnnotations()
    }
    fun undoAnnotation() {
        if (annotationUndo.isNotEmpty()) {
            annotationRedo = annotationRedo + listOf(annotations)
            annotations = annotationUndo.last()
            annotationUndo = annotationUndo.dropLast(1)
            saveAnnotations()
        }
    }
    fun redoAnnotation() {
        if (annotationRedo.isNotEmpty()) {
            annotationUndo = annotationUndo + listOf(annotations)
            annotations = annotationRedo.last()
            annotationRedo = annotationRedo.dropLast(1)
            saveAnnotations()
        }
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
            documentZoom = 1f
            documentPan = Offset.Zero
            annotationUndo = emptyList()
            annotationRedo = emptyList()
            fullScreen = true
            pageCount = r.pageCount
            page = prefs.getInt("page_${uri}", 0).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
            scope.launch { listState.scrollToItem(page) }
            val savedAnnotations = prefs.getString("annotations_${uri}", null)
            annotations = if (savedAnnotations != null) savedAnnotations.lines().mapNotNull { line ->
                runCatching {
                    val parts = line.split("|", limit=6)
                    val points = parts[5].split(";").map { pair -> pair.split(",").let { Offset(it[0].toFloat(),it[1].toFloat()) } }
                    Annotation(parts[0].toInt(),parts[1],points,parts[2].toInt(),parts[3].toFloat(),String(Base64.decode(parts[4],Base64.DEFAULT),Charsets.UTF_8))
                }.getOrNull()
            } else {
                // Migrate rectangle highlights created by versions 1.2–1.3.
                prefs.getString("marks_${uri}", "")!!.lines().mapNotNull { line ->
                    runCatching {
                        val v=line.split(",")
                        Annotation(v[0].toInt(),"HIGHLIGHT_BOX",listOf(Offset(v[1].toFloat(),v[2].toFloat()),Offset(v[3].toFloat(),v[4].toFloat())),0xFFFFEB3B.toInt(),0f)
                    }.getOrNull()
                }.also { if(it.isNotEmpty()) { annotations=it; saveAnnotations() } }
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

    val hostActivity = context as? ComponentActivity
    val hostView = LocalView.current
    LaunchedEffect(fullScreen) {
        hostActivity?.window?.let { window ->
            // Restore normal insets outside immersive mode to prevent the oversized top strip.
            WindowCompat.setDecorFitsSystemWindows(window, !fullScreen)
            val controller = WindowCompat.getInsetsController(window, hostView)
            if (fullScreen) {
                window.statusBarColor = AndroidColor.TRANSPARENT
                window.navigationBarColor = AndroidColor.TRANSPARENT
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.statusBarColor = if (dark || night) AndroidColor.BLACK else AndroidColor.WHITE
                window.navigationBarColor = if (dark || night) AndroidColor.BLACK else AndroidColor.WHITE
                controller.isAppearanceLightStatusBars = !(dark || night)
                controller.isAppearanceLightNavigationBars = !(dark || night)
            }
        }
    }
    

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
                AnimatedVisibility(visible = !fullScreen, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 }) {
                    Surface(
                        modifier = Modifier.statusBarsPadding(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    searchBarOpen = !searchBarOpen
                                    if (!searchBarOpen) { query = ""; searchResults = emptyList() }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(if (searchBarOpen) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search in PDF")
                            }
                            if (searchBarOpen) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it; searchResults = emptyList() },
                                    modifier = Modifier.width(190.dp).height(48.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && currentUri != null) searching = true }),
                                    placeholder = { Text("Find in PDF", style = MaterialTheme.typography.bodySmall) }
                                )
                                IconButton(
                                    onClick = { if (query.isNotBlank() && currentUri != null) searching = true },
                                    enabled = query.isNotBlank() && currentUri != null,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Run search")
                                }
                            }
                            IconButton(
                                onClick = { settingsOpen = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!fullScreen && currentUri == null) FloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
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
                    AnimatedVisibility(visible = !fullScreen, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 }) {
                    if (!fullScreen) Row(
                        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        IconButton(onClick = { searchBarOpen = true }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Search, contentDescription = "Search in PDF")
                        }
                        IconButton(onClick = { editMode = !editMode; message = if (editMode) "Reading mode" else "Choose a tool and annotate the page" }, modifier = Modifier.size(38.dp)) {
                            Icon(if (editMode) Icons.Default.Done else Icons.Default.Edit, contentDescription = if (editMode) "Finish annotating" else "Annotate")
                        }
                        IconButton(onClick = {
                            currentUri = null
                            renderer?.close(); renderer = null
                            descriptor?.close(); descriptor = null
                            editMode = false
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.LibraryBooks, contentDescription = "Library")
                        }
                        if (editMode) {
                            IconButton(onClick = { undoAnnotation() }, enabled = annotationUndo.isNotEmpty(), modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Default.Undo, contentDescription = "Undo annotation")
                            }
                            IconButton(onClick = { redoAnnotation() }, enabled = annotationRedo.isNotEmpty(), modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Default.Redo, contentDescription = "Redo annotation")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            currentUri?.let { uri ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share PDF"))
                            }
                        }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Share, contentDescription = "Share PDF") }
                    }
                    }
                    AnimatedVisibility(visible = !fullScreen, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 }) {
                    Column {

                    if(editMode) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                            listOf("PEN" to "Pen","HIGHLIGHTER" to "Highlight","UNDERLINE" to "Underline","RECTANGLE" to "Rectangle","TEXT" to "Text","ERASER" to "Eraser").forEach{(tool,label)->
                                FilterChip(selected=activeTool==tool,onClick={activeTool=tool},label={Text(label)})
                            }
                            if(activeTool=="HIGHLIGHTER" || activeTool=="UNDERLINE" || activeTool=="PEN") {
                                HorizontalDivider(Modifier.height(24.dp).width(1.dp), color=MaterialTheme.colorScheme.outlineVariant)
                                listOf(0xFFFFEB3B.toInt(),0xFFFF9800.toInt(),0xFF4CAF50.toInt(),0xFF2196F3.toInt(),0xFFE91E63.toInt(),0xFF202124.toInt()).forEach { swatch ->
                                    Surface(
                                        modifier=Modifier.size(28.dp).clickable { annotationColor=swatch },
                                        shape=MaterialTheme.shapes.small,
                                        color=Color(swatch),
                                        border=if(annotationColor==swatch) androidx.compose.foundation.BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null
                                    ) {}
                                }
                            }
                        }
                    }
                    }
                    }
                    if (searchResults.isNotEmpty() && !fullScreen) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Matches:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            searchResults.forEach { resultPage ->
                                AssistChip(
                                    onClick = { scope.launch { listState.animateScrollToItem(resultPage) } },
                                    label = { Text("Page ${resultPage + 1}") }
                                )
                            }
                        }
                    }
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }.collect { visiblePage ->
                            page = visiblePage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            currentUri?.let { prefs.edit().putInt("page_$it", page).apply() }
                            docs = docs.map { if (it.uri == currentUri.toString()) it.copy(lastPage = page) else it }
                            saveDocs()
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .background(if (dark || night) Color(0xFF202020) else Color(0xFFF7F7F7))
                            .clipToBounds()
                            .graphicsLayer {
                                scaleX = documentZoom
                                scaleY = documentZoom
                                translationX = documentPan.x
                                translationY = documentPan.y
                            }
                            .pointerInput(Unit) {
                                // Only a two-finger gesture controls zoom. One-finger
                                // drags are left entirely to LazyColumn for page scrolling.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val oldCenter = Offset(
                                                pressed.map { it.previousPosition.x }.average().toFloat(),
                                                pressed.map { it.previousPosition.y }.average().toFloat()
                                            )
                                            val newCenter = Offset(
                                                pressed.map { it.position.x }.average().toFloat(),
                                                pressed.map { it.position.y }.average().toFloat()
                                            )
                                            val oldDistance = pressed.map {
                                                (it.previousPosition - oldCenter).getDistance()
                                            }.average().toFloat()
                                            val newDistance = pressed.map {
                                                (it.position - newCenter).getDistance()
                                            }.average().toFloat()
                                            if (oldDistance > 0f && newDistance > 0f) {
                                                documentZoom = (documentZoom * (newDistance / oldDistance)).coerceIn(1f, 8f)
                                                documentPan = Offset.Zero
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = if (pageCount == 1) Arrangement.Center else Arrangement.Top,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        items(pageCount) { index ->
                            PdfPageItem(
                                renderer = renderer!!,
                                renderMutex = renderMutex,
                                bitmapCache = pageBitmapCache,
                                renderWidth = (context.resources.displayMetrics.widthPixels * 1.8f).toInt().coerceIn(1600, 2400),
                                pageIndex = index,
                                annotations = annotations.filter { it.page == index },
                                documentZoom = documentZoom,
                                editMode = editMode,
                                activeTool = activeTool,
                                annotationColor = annotationColor,
                                onAddAnnotation = { item -> updateAnnotations(annotations + item) },
                                onErase = { point ->
                                    updateAnnotations(annotations.filterNot { item -> item.page == index && item.points.any { candidate -> val dx=candidate.x-point.x; val dy=candidate.y-point.y; dx*dx+dy*dy < 0.002f } })
                                },
                                onTextTap = { point -> pendingText = index to point; textDraft = "" },
                                onTapPage = { fullScreen = !fullScreen }
                            )
                        }
                    }
                        if (pageCount > 1 && documentZoom <= 1.05f) {
                            val scrubberColor = MaterialTheme.colorScheme.primary
                            Column(
                                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).width(38.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "${page + 1}/${pageCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Canvas(
                                    Modifier.fillMaxHeight(0.72f).width(34.dp)
                                        .pointerInput(pageCount) {
                                            detectTapGestures { tap ->
                                                val fraction = ((tap.y - 8.dp.toPx()) / (size.height - 16.dp.toPx())).coerceIn(0f, 1f)
                                                val target = (fraction * (pageCount - 1)).toInt().coerceIn(0, pageCount - 1)
                                                page = target
                                                scope.launch { listState.scrollToItem(target) }
                                            }
                                        }
                                        .pointerInput(pageCount) {
                                            detectDragGestures(
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val fraction = ((change.position.y - 8.dp.toPx()) / (size.height - 16.dp.toPx())).coerceIn(0f, 1f)
                                                    val target = (fraction * (pageCount - 1)).toInt().coerceIn(0, pageCount - 1)
                                                    if (target != page) {
                                                        page = target
                                                        scope.launch { listState.scrollToItem(target) }
                                                    }
                                                }
                                            )
                                        }
                                ) {
                                    val top = 8.dp.toPx()
                                    val bottom = size.height - top
                                    val x = size.width / 2f
                                    drawLine(Color.LightGray.copy(alpha = 0.8f), Offset(x, top), Offset(x, bottom), strokeWidth = 3.dp.toPx())
                                    val fraction = if (pageCount > 1) page.toFloat() / (pageCount - 1) else 0f
                                    drawCircle(scrubberColor, radius = 7.dp.toPx(), center = Offset(x, top + fraction * (bottom - top)))
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = !fullScreen, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 3 }) {
                    Column {
                    if (message.isNotBlank()) Text(message, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    }
                }
            }
        }
    }

    if (pendingText != null) AlertDialog(
        onDismissRequest = { pendingText = null },
        title = { Text("Add text annotation") },
        text = { OutlinedTextField(value=textDraft,onValueChange={textDraft=it},label={Text("Text")}) },
        confirmButton = { TextButton(onClick={
            val target = pendingText!!
            if(textDraft.isNotBlank()){ updateAnnotations(annotations + Annotation(target.first,"TEXT",listOf(target.second),0xFF1565C0.toInt(),0.004f,textDraft)) }
            pendingText = null
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick={pendingText=null}) { Text("Cancel") } }
    )

    LaunchedEffect(searching) {
        if (searching) {
            val pdfRenderer = renderer
            val term = query.trim()
            if (pdfRenderer == null || term.isBlank()) {
                message = "Open a PDF and enter a search term."
                searching = false
            } else {
                message = "Searching…"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val matches = ArrayList<Int>()
                        // Use PDFBox consistently across Android versions. This searches
                        // the document text layer; scanned/image-only PDFs require OCR.
                        val uri = currentUri ?: error("The PDF is no longer open.")
                        val temp = File.createTempFile("quiet_search_", ".pdf", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(temp).use { output -> input.copyTo(output) }
                            } ?: error("Android could not read this PDF file.")
                            PDDocument.load(temp).use { doc ->
                                val stripper = PDFTextStripper().apply {
                                    sortByPosition = true
                                    setShouldSeparateByBeads(false)
                                }
                                for (index in 0 until doc.numberOfPages) {
                                    stripper.startPage = index + 1
                                    stripper.endPage = index + 1
                                    val extracted = stripper.getText(doc)
                                        .replace('\\u00A0', ' ')
                                        .replace(Regex("[\\\\t\\\\r\\\\n\\\\u000C]+"), " ")
                                        .replace(Regex(" +"), " ")
                                    if (extracted.contains(term, ignoreCase = true)) matches.add(index)
                                }
                            }
                        } finally {
                            temp.delete()
                        }
                        matches
                    }
                }
                outcome.onSuccess { matches ->
                    searchResults = matches
                    if (matches.isNotEmpty()) {
                        page = matches.first()
                        listState.animateScrollToItem(matches.first())
                        message = "Found on ${matches.size} page(s)."
                    } else {
                        message = "No text match for “${term}”. Scanned/image-only PDFs need OCR."
                    }
                }.onFailure { error ->
                    searchResults = emptyList()
                    message = "Search error: ${error.message ?: error.javaClass.simpleName}"
                }
                searching = false
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfRenderer, renderMutex: Mutex, bitmapCache: LruCache<Int, Bitmap>, renderWidth: Int,
    pageIndex: Int, annotations: List<Annotation>,
    documentZoom: Float,
    editMode: Boolean, activeTool: String, annotationColor: Int, onAddAnnotation: (Annotation)->Unit,
    onErase: (Offset)->Unit, onTextTap: (Offset)->Unit, onTapPage: ()->Unit
) {
    var pageBitmap by remember(pageIndex,renderer){mutableStateOf<Bitmap?>(null)}
    var strokePoints by remember(pageIndex){mutableStateOf(listOf<Offset>())}
    var dragEnd by remember(pageIndex){mutableStateOf<Offset?>(null)}
    LaunchedEffect(renderer,pageIndex,renderWidth) {
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            pageBitmap = cached
        } else {
            val rendered = withContext(Dispatchers.IO) {
                runCatching {
                    renderMutex.withLock {
                        renderer.openPage(pageIndex).use { pdfPage ->
                            val w = renderWidth
                            val h = (w.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(AndroidColor.WHITE)
                                pdfPage.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }.getOrNull()
            }
            if (rendered != null) {
                bitmapCache.put(pageIndex, rendered)
                pageBitmap = rendered
            }
        }
    }
    pageBitmap?.let{bmp->
        Box(
            Modifier.fillMaxWidth()
                .clickable(enabled=!editMode){onTapPage()},
            contentAlignment=Alignment.TopCenter
        ){
            androidx.compose.foundation.Image(bitmap=bmp.asImageBitmap(),contentDescription="PDF page ${pageIndex+1}",modifier=Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat()/bmp.height.toFloat()),contentScale=ContentScale.FillBounds)
            Canvas(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat()/bmp.height.toFloat()).pointerInput(editMode,activeTool,pageIndex){
                if(editMode && activeTool=="TEXT") detectTapGestures{p->onTextTap(Offset((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f)))}
                else if(editMode) detectDragGestures(
                    onDragStart={p->strokePoints=listOf(Offset((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f)));dragEnd=p},
                    onDrag={change,_->
                        dragEnd=change.position
                        val p=Offset((change.position.x/size.width).coerceIn(0f,1f),(change.position.y/size.height).coerceIn(0f,1f))
                        if(activeTool=="PEN"||activeTool=="HIGHLIGHTER"||activeTool=="UNDERLINE")strokePoints=strokePoints+p
                        if(activeTool=="ERASER")onErase(p)
                    },
                    onDragEnd={
                        if((activeTool=="PEN"||activeTool=="HIGHLIGHTER")&&strokePoints.isNotEmpty()) onAddAnnotation(Annotation(pageIndex,activeTool,strokePoints,annotationColor,if(activeTool=="PEN")0.0025f else 0.018f))
                        if(activeTool=="UNDERLINE"&&strokePoints.size>=2) onAddAnnotation(Annotation(pageIndex,"UNDERLINE",listOf(strokePoints.first(),strokePoints.last()),annotationColor,0.0035f))
                        if(activeTool=="RECTANGLE"&&strokePoints.isNotEmpty()&&dragEnd!=null){val p=dragEnd!!;onAddAnnotation(Annotation(pageIndex,"RECTANGLE",listOf(strokePoints.first(),Offset((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f))),0xFF1565C0.toInt(),0.0025f))}
                        strokePoints=emptyList();dragEnd=null
                    }
                )
            }){
                annotations.forEach{item->val pts=item.points
                    if(pts.isNotEmpty()){
                        val path=Path().apply{moveTo(pts[0].x*size.width,pts[0].y*size.height);pts.drop(1).forEach{lineTo(it.x*size.width,it.y*size.height)}}
                        when(item.kind){
                            "PEN"->drawPath(path,Color(item.color),style=androidx.compose.ui.graphics.drawscope.Stroke(item.width*size.width,cap=StrokeCap.Round))
                            "HIGHLIGHTER"->drawPath(path,Color(item.color).copy(alpha=0.30f),style=androidx.compose.ui.graphics.drawscope.Stroke(item.width*size.width,cap=StrokeCap.Round))
                            "UNDERLINE"->if(pts.size>=2) drawLine(Color(item.color),Offset(pts.first().x*size.width,pts.first().y*size.height),Offset(pts.last().x*size.width,pts.last().y*size.height),strokeWidth=item.width*size.width,cap=StrokeCap.Round)
                            "RECTANGLE"->if(pts.size>=2)drawRect(Color(item.color),Offset(minOf(pts[0].x,pts[1].x)*size.width,minOf(pts[0].y,pts[1].y)*size.height),Size(kotlin.math.abs(pts[1].x-pts[0].x)*size.width,kotlin.math.abs(pts[1].y-pts[0].y)*size.height),style=androidx.compose.ui.graphics.drawscope.Stroke(item.width*size.width))
                            "HIGHLIGHT_BOX"->if(pts.size>=2)drawRect(Color(item.color).copy(alpha=0.38f),Offset(minOf(pts[0].x,pts[1].x)*size.width,minOf(pts[0].y,pts[1].y)*size.height),Size(kotlin.math.abs(pts[1].x-pts[0].x)*size.width,kotlin.math.abs(pts[1].y-pts[0].y)*size.height))
                            "TEXT"->drawContext.canvas.nativeCanvas.drawText(item.text,pts[0].x*size.width,pts[0].y*size.height,AndroidPaint().apply{color=item.color;textSize=16f*density;isAntiAlias=true})
                        }
                    }
                }
                if(editMode&&strokePoints.isNotEmpty()&&activeTool!="ERASER"){
                    val path=Path().apply{moveTo(strokePoints[0].x*size.width,strokePoints[0].y*size.height);strokePoints.drop(1).forEach{lineTo(it.x*size.width,it.y*size.height)}}
                    if(activeTool=="RECTANGLE"&&dragEnd!=null){val p=dragEnd!!;drawRect(Color(0xFF1565C0),Offset(minOf(strokePoints[0].x*size.width,p.x),minOf(strokePoints[0].y*size.height,p.y)),Size(kotlin.math.abs(p.x-strokePoints[0].x*size.width),kotlin.math.abs(p.y-strokePoints[0].y*size.height)),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}
                    else if(activeTool=="UNDERLINE" && strokePoints.size>=2) drawLine(Color(annotationColor),Offset(strokePoints.first().x*size.width,strokePoints.first().y*size.height),Offset(strokePoints.last().x*size.width,strokePoints.last().y*size.height),strokeWidth=2.dp.toPx(),cap=StrokeCap.Round)
                    else drawPath(path,if(activeTool=="HIGHLIGHTER")Color(annotationColor).copy(alpha=0.30f) else Color(annotationColor),style=androidx.compose.ui.graphics.drawscope.Stroke(if(activeTool=="HIGHLIGHTER")18.dp.toPx() else 3.dp.toPx(),cap=StrokeCap.Round))
                }
            }
            Text(
                text = "${pageIndex + 1}",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }?:Box(Modifier.fillMaxWidth().height(240.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}
}
