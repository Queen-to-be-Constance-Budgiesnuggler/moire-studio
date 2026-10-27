package dk.cbavf.studio.studio

import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.MathParams
import dk.cbavf.studio.plugins.PluginStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StudioTab(val label: String) { File("File"), Preferences("Preferences"), Theme("Theme"), Config("Config"), Plugins("Plugins"), Notebook("Notebook"), About("About") }

/**
 * The Studio module: the main file menu (projects), preferences, theme, profile and what the app
 * knows about itself. Switching project restarts the screen with that project's Library and rooms.
 */
@Composable
fun StudioRoom(
    prefs: StudioPrefs,
    rooms: List<Pair<String, String>>,
    libraryCount: Int,
    addresses: List<String>,
    onSaveAll: () -> Unit,
    /** The Library entries, newest first, for the lab notebook, and how to open one. */
    notebook: List<dk.cbavf.studio.library.AssetRecord> = emptyList(),
    onOpenAsset: (String) -> Unit = {},
    /** Saves a note on a notebook entry (blank removes it). */
    onNote: (id: String, note: String?) -> Unit = { _, _ -> },
    /** Runs an entry again from its recipe, as a new entry; [rerunning] is true while one is being made. */
    onRerun: (dk.cbavf.studio.library.AssetRecord) -> Unit = {},
    rerunning: Boolean = false,
) {
    var tab by remember { mutableStateOf(StudioTab.File) }
    val page: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                StudioTab.File -> FileTab(prefs, onSaveAll)
                StudioTab.Preferences -> PreferencesTab(prefs, rooms)
                StudioTab.Theme -> ThemeTab(prefs)
                StudioTab.Config -> ConfigTab(prefs)
                StudioTab.Plugins -> PluginsTab(prefs)
                StudioTab.Notebook -> NotebookTab(notebook, onOpenAsset, onNote, onRerun, rerunning)
                StudioTab.About -> AboutTab(prefs, libraryCount, addresses)
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        if (dk.cbavf.studio.ui.layout.RoomLayoutMath.pageRail(maxWidth.value, maxHeight.value)) {
            // Landscape: the header and the tabs are a rail on the left, so the page keeps the window's height.
            Row(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 2.dp, modifier = Modifier.width(dk.cbavf.studio.ui.layout.RoomLayoutMath.RAIL.dp).fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(prefs.name)
                            Text("Moiré Studio", style = MaterialTheme.typography.titleMedium)
                        }
                        ProjectLine(prefs)
                        StudioTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
                page(Modifier.weight(1f).fillMaxSize())
            }
        } else Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(prefs.name)
                        Column(Modifier.weight(1f)) {
                            Text("Moiré Studio", style = MaterialTheme.typography.titleLarge)
                            ProjectLine(prefs)
                        }
                    }
                    ScrollRow { StudioTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) } }
                }
            }
            page(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ProjectLine(prefs: StudioPrefs) {
    Text(
        "Project “${prefs.project}”" + (prefs.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Avatar(name: String) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "MS" }
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

// --- File -----------------------------------------------------------------------------------------

@Composable
private fun FileTab(prefs: StudioPrefs, onSaveAll: () -> Unit) {
    val context = LocalContext.current
    val projects = remember { Projects(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val list = remember(refresh) { projects.list() }
    val current = prefs.project
    fun switchTo(name: String) {
        onSaveAll()
        prefs.setProject(name)
        // Every room reloads from the new project.
        (context as? Activity)?.recreate()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip")?.removePrefix("moire-studio-") ?: "imported"
            val n = withContext(Dispatchers.IO) { runCatching { projects.import(uri, name) }.getOrNull() }
            refresh++
            Toast.makeText(context, if (n != null) "Imported as “$n”" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
        }
    }

    Section("Project")
    ScrollRow {
        TextButton(onClick = { asking = "New project name" to { n -> projects.create(n)?.let(::switchTo) ?: Toast.makeText(context, "That name is taken", Toast.LENGTH_SHORT).show() } }) { Text("New…") }
        TextButton(onClick = onSaveAll) { Text("Save all now") }
        TextButton(onClick = { asking = "Copy “$current” as" to { n -> projects.duplicate(current, n)?.let { refresh++ } ?: Toast.makeText(context, "That name is taken", Toast.LENGTH_SHORT).show() } }) { Text("Save a copy as…") }
        TextButton(onClick = {
            scope.launch {
                onSaveAll()
                // The profile (never the workspace, which is only about this phone) travels with the project.
                val profile = UserConfigJson.encode(prefs.config.profile)
                val uri = withContext(Dispatchers.IO) { runCatching { projects.export(current, mapOf("user.profile" to profile)) }.getOrNull() }
                Toast.makeText(context, if (uri != null) "Exported to Download/Studio/moire-studio-$current.zip" else "Export failed", Toast.LENGTH_LONG).show()
            }
        }) { Text("Export .zip") }
        TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Import .zip…") }
    }
    Section("Print")
    TextButton(onClick = {
        // Pitches of common lenticular sheets, 100 down to 20 lines per inch, on an A4 page: print at 100 %.
        val svg = dk.cbavf.studio.calibration.CalibrationSheet.svg(210.0, 297.0, listOf(100, 75, 60, 50, 40, 30, 20).map { 25.4 / it })
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "moire-pitch-sheet-A4.svg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/svg+xml")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Studio")
        }
        val uri = runCatching {
            context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { u ->
                context.contentResolver.openOutputStream(u)?.use { it.write(svg.toByteArray()) }
            }
        }.getOrNull()
        Toast.makeText(context, if (uri != null) "Saved Download/Studio/moire-pitch-sheet-A4.svg: print it at 100 %" else "Could not save the sheet", Toast.LENGTH_LONG).show()
    }) { Text("Pitch test sheet (A4, SVG)") }
    Text("Seven bands of bars at lenticular pitches (100 to 20 lines per inch), in millimetres: print at actual size and hold your sheet against it.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Everything is saved as you go; projects are complete Libraries with every room's work.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Section("Open")
    list.forEach { name ->
        val (bytes, files) = remember(name, refresh) { projects.size(name) }
        Surface(
            Modifier.fillMaxWidth().clickable(enabled = name != current) { switchTo(name) },
            tonalElevation = if (name == current) 4.dp else 1.dp,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name + if (name == current) "  · open" else "", style = MaterialTheme.typography.titleSmall)
                    Text("${"%.1f".format(bytes / 1048576.0)} MB · $files files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    asking = "Rename “$name” to" to { n ->
                        val r = projects.rename(name, n)
                        if (r == null) Toast.makeText(context, "That name is taken", Toast.LENGTH_SHORT).show()
                        else if (name == current) switchTo(r) else refresh++
                    }
                }) { Text("Rename") }
                if (name != current) TextButton(onClick = { confirmDelete = name }) { Text("Delete") }
            }
        }
    }

    asking?.let { (title, done) ->
        var text by remember(title) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { done(text); asking = null }, enabled = text.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “$name”?") },
            text = { Text("Its Library, recipes and every room's work go for good. Export it first if in doubt.") },
            confirmButton = { TextButton(onClick = { projects.delete(name); confirmDelete = null; refresh++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

// --- Preferences ----------------------------------------------------------------------------------

@Composable
private fun PreferencesTab(prefs: StudioPrefs, rooms: List<Pair<String, String>>) {
    Section("Start in")
    ScrollRow {
        FilterChip(selected = prefs.startRoom == "last", onClick = { prefs.updateStartRoom("last") }, label = { Text("Where I left off") })
        rooms.forEach { (id, title) -> FilterChip(selected = prefs.startRoom == id, onClick = { prefs.updateStartRoom(id) }, label = { Text(title) }) }
    }
    Section("Display")
    LabeledSlider("Interface size", prefs.uiScale * 100, 80f..140f, " %", format = { "${it.toInt()}" }) { prefs.updateUiScale(it / 100) }
    Toggle("Keep the screen on while the studio is open", prefs.keepScreenOn, prefs::updateKeepScreenOn)
    Section("Touch")
    Toggle("Haptic feedback (hold to pick, drag to reorder)", prefs.haptics, prefs::updateHaptics)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

// --- Theme ----------------------------------------------------------------------------------------

@Composable
private fun ThemeTab(prefs: StudioPrefs) {
    Section("Theme")
    StudioTheme.entries.forEach { t ->
        val p = t.p
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(p.bg), RoundedCornerShape(8.dp))
                .border(if (prefs.theme == t) 3.dp else 1.dp, if (prefs.theme == t) Color(p.accent) else Color(p.outline), RoundedCornerShape(8.dp))
                .clickable { prefs.updateTheme(t) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(t.label, color = Color(p.text), style = MaterialTheme.typography.titleSmall)
                Text("fun moire(spacing: Float) = beat", color = Color(p.muted), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
            listOf(p.accent, p.second, p.third, p.error, p.surface, p.raised).forEach { c ->
                Box(Modifier.size(18.dp).background(Color(c), CircleShape).border(1.dp, Color(p.outline), CircleShape))
            }
        }
    }
}

// --- Plugins --------------------------------------------------------------------------------------

/** Installed plugins: switch on or off, remove, export, install a .studiomodule from a file. */
@Composable
private fun PluginsTab(prefs: StudioPrefs) {
    val context = LocalContext.current
    val store = remember { PluginStore(context) }
    var refresh by remember { mutableStateOf(0) }
    val installed = remember(refresh) { store.installed() }
    val enabled = prefs.config.profile.enabledPlugins.toSet()
    fun setEnabled(id: String, on: Boolean) = prefs.config.updateProfile {
        it.copy(enabledPlugins = if (on) (it.enabledPlugins + id).distinct() else it.enabledPlugins - id)
    }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val m = withContext(Dispatchers.IO) { runCatching { store.install(uri) }.getOrNull() }
            refresh++
            Toast.makeText(context, if (m != null) "Installed “${m.name}”. Switch it on below." else "That is not a plugin file", Toast.LENGTH_LONG).show()
        }
    }
    fun share(m: dk.cbavf.studio.plugins.PluginManifest) = scope.launch {
        val uri = withContext(Dispatchers.IO) { runCatching { store.shareFile(m) }.getOrNull() }
        if (uri == null || !PluginStore.openShareSheet(context, m, uri)) Toast.makeText(context, "Could not share it", Toast.LENGTH_SHORT).show()
    }
    Section("Plugins")
    Text(
        "A plugin is a module's face and settings as data (a .studiomodule file), never code. Switched on, its controls get a panel in the UI Editor. " +
            "Build your own in the Module Forge room.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ScrollRow {
        TextButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Install .studiomodule…") }
    }
    val all = PluginStore.BUILT_IN.map { it to true } + installed.map { it to false }
    all.forEach { (m, builtIn) ->
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + "  ·  v${m.version}" + if (builtIn) "  ·  built in" else "", style = MaterialTheme.typography.titleSmall)
                        Text(m.kind.name + (m.author.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · ${m.widgets.size} controls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = m.id in enabled, onCheckedChange = { setEnabled(m.id, it) })
                }
                if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall)
                if (!builtIn) Row {
                    TextButton(onClick = { share(m) }) { Text("Share…") }
                    TextButton(onClick = {
                        val uri = runCatching { store.export(m) }.getOrNull()
                        Toast.makeText(context, if (uri != null) "Exported to Pictures/Studio" else "Export failed", Toast.LENGTH_SHORT).show()
                    }) { Text("Export") }
                    TextButton(onClick = { store.remove(m.id); setEnabled(m.id, false); refresh++ }) { Text("Remove") }
                }
            }
        }
    }
    if (installed.isEmpty()) Text("No installed plugins yet.", style = MaterialTheme.typography.labelMedium)
}

// --- Config ---------------------------------------------------------------------------------------

/** Everything in user.profile: who you are, every flag, the developer options, and the maths parameters. */
@Composable
private fun ConfigTab(prefs: StudioPrefs) {
    val cfg = prefs.config
    val p = cfg.profile
    val context = LocalContext.current
    ProfileTab(prefs)

    Section("Studio flags")
    LabeledSlider("Interface size", prefs.uiScale * 100, 80f..140f, " %", format = { "${it.toInt()}" }) { prefs.updateUiScale(it / 100) }
    Toggle("Keep the screen on while the studio is open", p.keepScreenOn, prefs::updateKeepScreenOn)
    Toggle("Haptic feedback", p.haptics, prefs::updateHaptics)
    Toggle("A/B compare: hold with two fingers instead of one", p.abTwoFingerHold) { v -> cfg.updateProfile { it.copy(abTwoFingerHold = v) } }
    Toggle("Command palette in the notification shade", p.paletteNotification) { v -> cfg.updateProfile { it.copy(paletteNotification = v) } }

    dk.cbavf.studio.feedback.FeedbackSection(p) { Section(it) }

    Section("OSC out")
    Text(
        "Where the OSC out unit on the Studio's back sends: the other computer's address (for example 192.168.1.20) and its port. The unit is switched on from the back.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var oscHost by remember { mutableStateOf(p.oscOutHost) }
    OutlinedTextField(oscHost, { oscHost = it.trim(); cfg.updateProfile { x -> x.copy(oscOutHost = oscHost) } },
        label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    var oscPort by remember { mutableStateOf(p.oscOutPort.toString()) }
    OutlinedTextField(oscPort, { t ->
        oscPort = t.filter { it.isDigit() }.take(5)
        oscPort.toIntOrNull()?.let { n -> cfg.updateProfile { x -> x.copy(oscOutPort = n) } }
    }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())

    Section("Developer")
    Text("Debug output level", style = MaterialTheme.typography.labelMedium)
    ScrollRow {
        listOf("Off", "1 Errors", "2 Info", "3 Trace").forEachIndexed { level, label ->
            FilterChip(selected = p.debugLevel == level, onClick = { cfg.updateProfile { it.copy(debugLevel = level) } }, label = { Text(label) })
        }
    }
    Toggle("Show the debug terminal over every room", p.terminalVisible) { v -> cfg.updateProfile { it.copy(terminalVisible = v) } }
    Toggle("Offer “Copy as script” on controls (dev.copyAsScript; no scripting module yet)", p.copyAsScript) { v -> cfg.updateProfile { it.copy(copyAsScript = v) } }

    Section("Math parameters")
    Text(
        "The numbers behind the maths, changed live and kept in user.profile. Only the ones listed are wired in so far.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var filter by remember { mutableStateOf("") }
    OutlinedTextField(filter, { filter = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val shown = MathParams.all.filter { filter.isBlank() || it.key.contains(filter, true) || it.description.contains(filter, true) }
    shown.groupBy { it.group }.forEach { (group, params) ->
        Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        params.forEach { m ->
            Text(m.key + if (MathParams.isChanged(m.key)) "  · changed" else "", style = MaterialTheme.typography.labelMedium)
            Text(m.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val whole = m.max - m.min >= 50
            LabeledSlider(
                "", MathParams.get(m.key).toFloat(), m.min.toFloat()..m.max.toFloat(), " " + m.unit,
                format = { if (whole) "${it.toInt()}" else "%.2f".format(it) },
                step = if (whole) 1f else 0.1f,
            ) { v -> MathParams.set(m.key, v.toDouble()); cfg.saveMathParams() }
            TextButton(onClick = { MathParams.reset(m.key); cfg.saveMathParams() }, enabled = MathParams.isChanged(m.key)) { Text("Reset to ${m.default.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}") }
        }
    }
    if (shown.isEmpty()) Text("Nothing matches.", style = MaterialTheme.typography.labelMedium)

    Section("Where things are saved")
    cfg.locations(Projects(context).dir(prefs.project)).forEach { (what, where) -> Info(what, where) }
    Text(
        "user.profile is this studio's configuration, user.workspace is this phone's state (open project, last room) and the project files stay in the project folders.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Profile --------------------------------------------------------------------------------------

@Composable
private fun ProfileTab(prefs: StudioPrefs) {
    var name by remember { mutableStateOf(prefs.name) }
    var handle by remember { mutableStateOf(prefs.handle) }
    var about by remember { mutableStateOf(prefs.about) }
    Section("Profile")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(name)
        Text("Shown on this screen and kept with project exports.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(handle, { handle = it }, label = { Text("Artist name / handle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(about, { about = it }, label = { Text("About") }, modifier = Modifier.fillMaxWidth().height(110.dp))
    TextButton(onClick = { prefs.setProfile(name, handle, about) }, enabled = name != prefs.name || handle != prefs.handle || about != prefs.about) { Text("Save profile") }
}

// --- About ----------------------------------------------------------------------------------------

@Composable
private fun AboutTab(prefs: StudioPrefs, libraryCount: Int, addresses: List<String>) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val free by produceState(0L) { value = withContext(Dispatchers.IO) { context.filesDir.usableSpace } }
    Section("About")
    Info("App", "Moiré Studio $version")
    Info("Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    Info("Project", "${prefs.project} · $libraryCount images in the Library")
    Info("Free space", "${"%.1f".format(free / 1073741824.0)} GB")
    Info("Addresses", addresses.joinToString().ifBlank { "offline" })
    HorizontalDivider()
    Section("Pre-release notes")
    Text("Decide before release: whether sound processing joins the studio (a DAW mode); the long-term question of scripting languages and stacks; the naming and vocabulary themes.", style = MaterialTheme.typography.bodySmall)
    Text("Next dev session after the pilot test: set up a scheduled agentic workflow for the project, and research how.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Two-phone experimental image studio: Draw, Animate, Sequence, Bridge, Camera, Loop, Patch, Develop, FFT, Glitch, Compose and Node Lab, patched together in the studio routing.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The lab notebook: everything the studio made or took in, newest first, with what made it and the settings it kept. Every entry
 * already carries its recipe, so tapping one opens it in the Library, where it can be reopened in the room that made it. Each entry
 * can carry a note, and the kinds in [Notebook] can be run again from their recipe, as a new entry beside the first run.
 */
@Composable
private fun NotebookTab(
    all: List<dk.cbavf.studio.library.AssetRecord>,
    onOpen: (String) -> Unit,
    onNote: (String, String?) -> Unit,
    onRerun: (dk.cbavf.studio.library.AssetRecord) -> Unit,
    rerunning: Boolean,
) {
    val entries = all.take(NOTEBOOK_SHOWN)
    Section("Lab notebook")
    Text("The last ${entries.size} things made in this project. Tap one to open it in the Library.", style = MaterialTheme.typography.labelMedium)
    if (entries.isEmpty()) {
        Text("Nothing yet: make something and save it.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    var editing by remember { mutableStateOf<dk.cbavf.studio.library.AssetRecord?>(null) }
    editing?.let { a ->
        var text by remember(a.id) { mutableStateOf(a.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    text, { text = it.take(Notebook.NOTE_MAX) },
                    placeholder = { Text("What you tried, what you saw") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Notebook.noteChanged(a, text)) onNote(a.id, Notebook.cleanNote(text))
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    entries.forEach { a ->
        androidx.compose.material3.Surface(
            onClick = { onOpen(a.id) },
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("${fmt.format(java.util.Date(a.createdAt))} · ${a.operation} · ${a.widthPx}×${a.heightPx}", style = MaterialTheme.typography.labelLarge)
                val info = a.summary ?: a.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }
                if (!info.isNullOrBlank()) Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.parents.isNotEmpty()) Text("from ${a.parents.size} earlier image${if (a.parents.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
                Notebook.runLine(all, a) { fmt.format(java.util.Date(it)) }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editing = a }) { Text(if (a.note == null) "Add note" else "Edit note") }
                    if (Notebook.canRerun(a)) TextButton(onClick = { onRerun(a) }, enabled = !rerunning) { Text(if (rerunning) "Running…" else "Rerun") }
                }
            }
        }
    }
}

/** How many entries the notebook lists; reruns are matched against the whole Library. */
private const val NOTEBOOK_SHOWN = 100
