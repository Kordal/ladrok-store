// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date

// The Ladrok palette (ladrok-page), on pure black like the Launcher.
private val Black = Color(0xFF000000)
private val Raised = Color(0xFF121212)
private val Line = Color(0xFF262626)
private val Ink = Color(0xFFE4E4E4)
private val Muted = Color(0xFF8A8A8A)
private val Silver = Color(0xFFCFCFCF)

/** The curated Catalog's category for the Launcher and the Store: listed under "This TV". */
private const val SYSTEM = "System"

/** The home screen: it has no launcher entry, and the resolver checks it without one. */
private const val LAUNCHER = "tv.ladrok.launcher"

/**
 * The Store, shaped like the Launcher: a short list on the left, the focused App's page on the
 * right. OK does the one obvious thing (Install, Update or Open); holding OK opens a menu with the
 * rest. The list ends with Search, the FOSS Catalog and the automatic-updates switch.
 * The FOSS Catalog ([shelf] FOSS) adds a column of groups on the left. While [query] is not null
 * Search takes the screen: the field, the results in one row under it, and the focused result's page.
 * [show] (a package name, and when it was asked for) moves focus to that App's page.
 */
@Composable
fun StoreScreen(
    shelf: Shelf,
    catalogs: Map<Shelf, Catalog>,
    errors: Map<Shelf, String>,
    installed: Map<String, InstalledPackage>,
    work: Map<String, Work>,
    autoUpdate: Boolean,
    query: String?,
    results: List<Found>,
    onSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onVoice: (() -> Unit)?,
    onOpenFoss: () -> Unit,
    onInstall: (CatalogApp) -> Unit,
    onOpen: (CatalogApp) -> Unit,
    onUninstall: (CatalogApp) -> Unit,
    onAppInfo: (CatalogApp) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
    show: Pair<String, Long>? = null,
) {
    var menuFor by remember { mutableStateOf<CatalogApp?>(null) }
    // What had focus in the list, so coming back from FOSS apps or Search lands there again.
    var listFocus by remember { mutableStateOf<String?>(null) }
    // The App whose page shows: the last App in the list that had focus.
    var pageFor by remember { mutableStateOf<String?>(null) }
    // An App another app asked to show (the Launcher's "Updates"), and a count that moves focus to it.
    var fossShow by remember { mutableStateOf<String?>(null) }
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(show) {
        val id = show?.first ?: return@LaunchedEffect
        if (catalogs[Shelf.CURATED]?.apps?.any { it.id == id } == true) {
            listFocus = id
            pageFor = id
        } else {
            fossShow = id
        }
        menuFor = null
        refocus++
    }
    val actions = @Composable { app: CatalogApp ->
        actionsFor(
            app, Status.of(app, installed[app.id]), work[app.id], installed[app.id],
            install = { onInstall(app) }, open = { onOpen(app) }, uninstall = { onUninstall(app) }, info = { onAppInfo(app) },
        )
    }
    val page = @Composable { app: CatalogApp, foss: Boolean, modifier: Modifier ->
        AppPage(app, Status.of(app, installed[app.id]), work[app.id], actions(app), foss, modifier)
    }
    val row = @Composable { app: CatalogApp, modifier: Modifier, onFocus: () -> Unit ->
        val all = actions(app)
        AppRow(
            app = app,
            note = newsOf(Status.of(app, installed[app.id]), work[app.id], app.category == SYSTEM),
            onClick = { all.primary?.second?.invoke() ?: run { if (all.menu.isNotEmpty()) menuFor = app } },
            onLongClick = { if (all.menu.isNotEmpty()) menuFor = app },
            modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .safeDrawingPadding() // no-op on a TV; keeps clear of system bars elsewhere
            .padding(horizontal = 36.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = when {
                query != null -> R.string.search_title
                shelf == Shelf.FOSS -> R.string.foss_title
                else -> R.string.app_name
            }
            Text(stringResource(title), color = Muted, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (query != null && onVoice != null) {
                Pill(stringResource(R.string.search_voice), onVoice)
                Spacer(Modifier.width(24.dp))
            }
            Clock()
        }
        val error = if (query == null) errors[shelf] else null
        if (error != null) Text(error, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        val catalog = catalogs[shelf]
        when {
            query != null -> SearchPanel(query, results, onQueryChange, row, page)
            catalog == null -> {
                if (error == null) Text(stringResource(R.string.loading), color = Muted, fontSize = 20.sp, modifier = Modifier.padding(12.dp))
            }
            shelf == Shelf.FOSS -> FossPanes(catalog, fossShow, refocus, row, page)
            else -> CuratedPanes(
                catalog = catalog,
                fossCount = catalogs[Shelf.FOSS]?.apps?.size,
                updates = catalog.apps.count { Status.of(it, installed[it.id]) is Status.UpdateAvailable && it.id !in work },
                autoUpdate = autoUpdate,
                focus = listFocus,
                pageFor = pageFor,
                refocus = refocus,
                onFocus = { key -> listFocus = key; if (catalog.apps.any { it.id == key }) pageFor = key },
                onSearch = onSearch,
                onOpenFoss = onOpenFoss,
                onUpdateAll = onUpdateAll,
                onToggleAutoUpdate = onToggleAutoUpdate,
                row = row,
                page = page,
            )
        }
    }

    menuFor?.let { app ->
        val items = actions(app).menu
        if (items.isEmpty()) {
            LaunchedEffect(app) { menuFor = null } // it got busy since
        } else {
            Menu(items.map { (label, run) -> label to { menuFor = null; run() } }, onDismiss = { menuFor = null })
        }
    }
}

private typealias RowSlot = @Composable (CatalogApp, Modifier, () -> Unit) -> Unit
private typealias PageSlot = @Composable (CatalogApp, Boolean, Modifier) -> Unit

/** The curated Apps, then "This TV" (the Launcher and the Store), then Search, FOSS apps and the switch. */
@Composable
private fun CuratedPanes(
    catalog: Catalog,
    fossCount: Int?,
    updates: Int,
    autoUpdate: Boolean,
    focus: String?,
    pageFor: String?,
    refocus: Int,
    onFocus: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFoss: () -> Unit,
    onUpdateAll: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
    row: RowSlot,
    page: PageSlot,
) {
    val apps = catalog.apps.filter { it.category != SYSTEM }
    val ours = catalog.apps.filter { it.category == SYSTEM }
    val listed = apps + ours
    val first = remember { FocusRequester() } // where focus was before FOSS apps or Search
    val top = remember { FocusRequester() } // the first App, if that row is gone or scrolled away
    val target = focus?.takeIf { key -> key in setOf(ROW_SEARCH, ROW_FOSS, ROW_AUTO) || (key == ROW_UPDATE_ALL && updates > 1) || listed.any { it.id == key } }
    LaunchedEffect(refocus) {
        withFrameNanos {} // the list is drawn
        if (target == null || runCatching { first.requestFocus() }.isFailure) runCatching { top.requestFocus() }
    }
    fun Modifier.at(key: String) = (if (key == target) focusRequester(first) else this)
        .then(if (key == listed.firstOrNull()?.id) Modifier.focusRequester(top) else Modifier)
    val shown = listed.firstOrNull { it.id == pageFor } ?: listed.firstOrNull()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        LazyColumn(
            Modifier.width(300.dp).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(apps, key = { it.id }) { app -> row(app, Modifier.at(app.id)) { onFocus(app.id) } }
            if (ours.isNotEmpty()) {
                item(key = "head:system") { Heading(stringResource(R.string.this_tv)) }
                items(ours, key = { it.id }) { app -> row(app, Modifier.at(app.id)) { onFocus(app.id) } }
            }
            item(key = "divider") { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(Line)) }
            item(key = ROW_SEARCH) {
                TextRow(stringResource(R.string.search), null, onSearch, Modifier.at(ROW_SEARCH).onFocusChanged { if (it.isFocused) onFocus(ROW_SEARCH) })
            }
            item(key = ROW_FOSS) {
                TextRow(
                    stringResource(R.string.foss_open), fossCount?.toString(), onOpenFoss,
                    Modifier.at(ROW_FOSS).onFocusChanged { if (it.isFocused) onFocus(ROW_FOSS) },
                )
            }
            // With one update, the App itself is the action.
            if (updates > 1) item(key = ROW_UPDATE_ALL) {
                TextRow(
                    stringResource(R.string.update_all), updates.toString(), onUpdateAll,
                    Modifier.at(ROW_UPDATE_ALL).onFocusChanged { if (it.isFocused) onFocus(ROW_UPDATE_ALL) },
                )
            }
            item(key = ROW_AUTO) {
                TextRow(
                    stringResource(R.string.auto_update), stringResource(if (autoUpdate) R.string.on else R.string.off), onToggleAutoUpdate,
                    Modifier.at(ROW_AUTO).onFocusChanged { if (it.isFocused) onFocus(ROW_AUTO) },
                )
            }
        }
        if (shown != null) page(shown, false, Modifier.weight(1f).fillMaxHeight())
    }
}

private const val ROW_SEARCH = "row:search"
private const val ROW_FOSS = "row:foss"
private const val ROW_UPDATE_ALL = "row:update-all"
private const val ROW_AUTO = "row:auto"

/** The FOSS Catalog in three panes: its groups, the group's Apps, the focused App's page. */
@Composable
private fun FossPanes(catalog: Catalog, show: String?, refocus: Int, row: RowSlot, page: PageSlot) {
    val groups = remember(catalog) { FossGroups.group(catalog.apps) }
    var group by remember { mutableStateOf(groups.firstOrNull()?.first) }
    var focused by remember { mutableStateOf<String?>(null) }
    val firstGroup = remember { FocusRequester() }
    val firstApp = remember { FocusRequester() }
    val shownApp = remember { FocusRequester() }
    LaunchedEffect(refocus) {
        val app = catalog.apps.firstOrNull { it.id == show }
        if (app != null) {
            group = FossGroups.of(app.category)
            focused = app.id
            withFrameNanos {} // the group's Apps are listed
            withFrameNanos {}
            if (runCatching { shownApp.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        withFrameNanos {}
        runCatching { firstGroup.requestFocus() }
    }
    val apps = groups.firstOrNull { it.first == group }?.second.orEmpty()
    val shown = apps.firstOrNull { it.id == focused } ?: apps.firstOrNull()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(
            Modifier.width(210.dp).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(groups, key = { it.first }) { (name, list) ->
                TextRow(
                    label = name,
                    note = list.size.toString(),
                    onClick = { runCatching { firstApp.requestFocus() } },
                    modifier = (if (name == groups.firstOrNull()?.first) Modifier.focusRequester(firstGroup) else Modifier)
                        .onFocusChanged { if (it.isFocused && group != name) { group = name; focused = null } },
                    selected = name == group,
                    height = 36.dp,
                    fontSize = 15,
                )
            }
        }
        LazyColumn(
            Modifier.width(260.dp).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(apps, key = { it.id }) { app ->
                val modifier = (if (app == apps.first()) Modifier.focusRequester(firstApp) else Modifier)
                    .then(if (app.id == show) Modifier.focusRequester(shownApp) else Modifier)
                row(app, modifier) { focused = app.id }
            }
        }
        if (shown != null) page(shown, true, Modifier.weight(1f).fillMaxHeight())
    }
}

/**
 * The search field, the results in one row under it (so the on-screen keyboard never covers them),
 * and the focused result's page. Curated results come first, then the FOSS Catalog's under a heading.
 * The keyboard's search key moves to the results.
 */
@Composable
private fun SearchPanel(
    query: String,
    results: List<Found>,
    onQueryChange: (String) -> Unit,
    row: RowSlot,
    page: PageSlot,
) {
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 20.sp),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                if (results.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }),
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .focusRequester(field)
                .onFocusChanged { fieldFocused = it.isFocused }
                // Down from the field goes to the results (the text field would keep it otherwise).
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
                    if (down && results.isNotEmpty()) {
                        keyboard?.hide()
                        runCatching { firstResult.requestFocus() }.isSuccess
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Raised, shape)
                        .border(2.dp, if (fieldFocused) Silver else Line, shape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = Muted, fontSize = 20.sp)
                    inner()
                }
            },
        )
        if (query.isNotBlank() && results.isEmpty()) {
            Text(stringResource(R.string.search_none, query.trim()), color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
            return@Column
        }
        val firstFoss = results.indexOfFirst { it.shelf == Shelf.FOSS }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            results.forEachIndexed { index, (app, _) ->
                if (index == firstFoss) item(key = "head:foss") { Heading(stringResource(R.string.foss_title)) }
                item(key = app.id) {
                    ResultTile(
                        app = app,
                        row = row,
                        modifier = (if (index == 0) Modifier.focusRequester(firstResult) else Modifier),
                        onFocus = { focused = app.id },
                    )
                }
            }
        }
        val shown = results.firstOrNull { it.app.id == focused } ?: results.firstOrNull()
        if (shown != null) page(shown.app, shown.shelf == Shelf.FOSS, Modifier.padding(top = 16.dp).fillMaxSize())
    }
}

/** A search result: the App's row, narrow, so several fit across the screen. */
@Composable
private fun ResultTile(app: CatalogApp, row: RowSlot, modifier: Modifier, onFocus: () -> Unit) {
    Box(Modifier.width(220.dp)) { row(app, modifier, onFocus) }
}

/** What OK and holding OK do on an App: [primary] for OK; [menu] (everything, primary first) for holding it. */
private class Actions(val primary: Pair<String, () -> Unit>?, val menu: List<Pair<String, () -> Unit>>)

@Composable
private fun actionsFor(
    app: CatalogApp,
    status: Status,
    work: Work?,
    installed: InstalledPackage?,
    install: () -> Unit,
    open: () -> Unit,
    uninstall: () -> Unit,
    info: () -> Unit,
): Actions {
    if (work != null && work !is Work.Failed) return Actions(null, emptyList())
    val openIt = if (installed?.launchable == true) stringResource(R.string.open) to open else null
    val removeIt = if (installed != null && !installed.system) stringResource(R.string.uninstall) to uninstall else null
    val infoIt = if (installed != null) stringResource(R.string.app_info) to info else null
    val primary = when (status) {
        Status.NotInstalled -> stringResource(R.string.install) to install
        is Status.UpdateAvailable -> stringResource(R.string.update) to install
        is Status.OtherSigner -> null
        is Status.UpToDate -> openIt
    }
    val menu = listOfNotNull(primary, openIt, removeIt, infoIt).distinctBy { it.first }
    return Actions(primary, menu)
}

/** A list row's word on the right: only when there is news. The Launcher and the Store show their version. */
@Composable
private fun newsOf(status: Status, work: Work?, ours: Boolean): String? = when {
    work != null -> workLine(work)
    status is Status.UpdateAvailable -> stringResource(R.string.news_update)
    status is Status.OtherSigner -> stringResource(R.string.other_signer_short)
    status is Status.UpToDate -> if (ours) status.version else stringResource(R.string.news_installed)
    else -> null
}

@Composable
private fun workLine(work: Work): String = when (work) {
    is Work.Downloading -> work.percent?.let { stringResource(R.string.downloading_percent, it) } ?: stringResource(R.string.downloading)
    Work.Installing -> stringResource(R.string.installing)
    Work.Waiting -> stringResource(R.string.waiting)
    Work.Removing -> stringResource(R.string.removing)
    is Work.Failed -> work.message
}

/** Where an App stands, for its page: what it is doing now, or else whether it's installed. */
@Composable
private fun statusLine(status: Status, work: Work?): String = work?.let { workLine(it) } ?: when (status) {
    Status.NotInstalled -> stringResource(R.string.not_installed)
    is Status.UpToDate -> stringResource(R.string.installed, status.version)
    is Status.UpdateAvailable -> stringResource(R.string.update_available, status.from, status.to)
    is Status.OtherSigner -> stringResource(R.string.other_signer_short)
}

/**
 * The focused App's page: icon, name, category and status, what it is, what's new, the checks the
 * Catalog made (or, for the FOSS Catalog, where it comes from), what OK does, and version, size, source.
 */
@Composable
private fun AppPage(app: CatalogApp, status: Status, work: Work?, actions: Actions, foss: Boolean, modifier: Modifier) {
    Column(modifier.padding(top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, Modifier.size(88.dp), RoundedCornerShape(16.dp), 34)
            Column(Modifier.padding(start = 22.dp)) {
                Text(app.label, color = Ink, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (app.category == SYSTEM) stringResource(R.string.this_tv) else app.category} · ${statusLine(status, work)}",
                    color = Muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (app.summary.isNotEmpty()) {
            Text(app.summary, color = Ink, fontSize = 19.sp, lineHeight = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (status is Status.OtherSigner && work == null) Text(stringResource(R.string.other_signer), color = Muted, fontSize = 14.sp)
        val notes = remember(app.notes) { plainNotes(app.notes) }
        if (notes.isNotEmpty()) {
            Column {
                Text(stringResource(R.string.new_in, app.versionName), color = Silver, fontSize = 14.sp)
                Text(notes, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (foss) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Line))
                Text(stringResource(R.string.foss_note), color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 10.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Check(stringResource(R.string.check_signer))
                if (app.id != LAUNCHER) Check(stringResource(R.string.check_launcher))
                Check(stringResource(R.string.check_abi))
            }
        }
        val more = actions.menu.size > (if (actions.primary != null) 1 else 0)
        if (actions.primary != null || more) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.primary?.let { (label, _) ->
                    KeyCap(stringResource(R.string.key_ok))
                    Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, end = 24.dp))
                }
                if (more) {
                    KeyCap(stringResource(if (actions.primary != null) R.string.key_hold_ok else R.string.key_ok))
                    Text(stringResource(R.string.more), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Fact(app.versionName, stringResource(R.string.fact_version))
            Fact(sizeText(app.size), stringResource(R.string.fact_size))
            Fact(sourceOf(app.url), stringResource(R.string.fact_source))
        }
    }
}

@Composable
private fun Check(text: String) {
    Row {
        Text("✓", color = Silver, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun KeyCap(text: String) {
    Text(
        text, color = Silver, fontSize = 13.sp,
        modifier = Modifier.border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Fact(value: String, label: String) {
    Column {
        Text(value, color = Silver, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 4.dp))
}

private val shape = RoundedCornerShape(8.dp)

@Composable
private fun rowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = Ink,
    focusedContainerColor = Line,
    focusedContentColor = Ink,
    pressedContainerColor = Line,
    pressedContentColor = Ink,
)

/** One App in a list: its icon, its name, and a word on the right when there is news. */
@Composable
private fun AppRow(app: CatalogApp, note: String?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = app.label },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = rowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, Modifier.size(26.dp), RoundedCornerShape(6.dp), 14)
            Text(
                app.label, color = Ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            if (note != null) Text(note, color = Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A row that isn't an App (Search, FOSS apps, a FOSS group): a name and a note on the right. */
@Composable
private fun TextRow(
    label: String,
    note: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    selected: Boolean = false,
    height: Dp = 40.dp,
    fontSize: Int = 17,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Ink else Muted,
            focusedContainerColor = Line,
            focusedContentColor = Ink,
            pressedContainerColor = Line,
            pressedContentColor = Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (note != null) Text(note, color = Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** The App's icon from the Catalog, or its initial while there is none. */
@Composable
private fun AppIcon(app: CatalogApp, modifier: Modifier, corner: RoundedCornerShape, letter: Int) {
    Box(modifier.background(Line, corner), contentAlignment = Alignment.Center) {
        Text(app.label.take(1), color = Ink, fontSize = letter.sp, textAlign = TextAlign.Center)
        if (app.icon != null) {
            AsyncImage(app.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A short list of choices, like the Launcher's. */
@Composable
private fun Menu(items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(Modifier.width(320.dp).background(Raised, RoundedCornerShape(12.dp)).padding(8.dp)) {
            items.forEachIndexed { index, (label, onClick) ->
                MenuItem(label, onClick, if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Raised,
            contentColor = Muted,
            focusedContainerColor = Line,
            focusedContentColor = Ink,
            pressedContainerColor = Line,
            pressedContentColor = Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Text(label, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Line,
            contentColor = Muted,
            focusedContainerColor = Ink,
            focusedContentColor = Black,
            pressedContainerColor = Ink,
            pressedContentColor = Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Text(label, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
    }
}

/** The time, in the system's 12- or 24-hour format, updated on the minute (as on the Launcher). */
@Composable
private fun Clock() {
    val context = LocalContext.current
    val time by produceState("") {
        while (true) {
            value = DateFormat.getTimeFormat(context).format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(time, color = Muted, fontSize = 20.sp)
}
