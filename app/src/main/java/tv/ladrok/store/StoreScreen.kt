// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

// The Ladrok palette (ladrok-page), on pure black like the Launcher.
private val Black = Color(0xFF000000)
private val Raised = Color(0xFF121212)
private val Line = Color(0xFF262626)
private val Ink = Color(0xFFE4E4E4)
private val Muted = Color(0xFF8A8A8A)
private val Silver = Color(0xFFCFCFCF)

/**
 * The Store: a row per category of Catalog Apps, with Update all and the automatic-updates switch
 * at the top. OK on an App opens its page, with Install, Update, Open and Uninstall.
 */
@Composable
fun StoreScreen(
    catalog: Catalog?,
    installed: Map<String, InstalledPackage>,
    work: Map<String, Work>,
    error: String?,
    autoUpdate: Boolean,
    onInstall: (CatalogApp) -> Unit,
    onOpen: (CatalogApp) -> Unit,
    onUninstall: (CatalogApp) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val firstApp = remember { FocusRequester() }
    val ready = catalog != null && catalog.apps.isNotEmpty()
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos {} // the first row is drawn
        runCatching { firstApp.requestFocus() }
    }
    val updates = catalog?.apps.orEmpty().count { Status.of(it, installed[it.id]) is Status.UpdateAvailable && it.id !in work }

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .safeDrawingPadding() // no-op on a TV; keeps clear of system bars elsewhere
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), color = Ink, fontSize = 28.sp, modifier = Modifier.weight(1f))
            if (updates > 0) {
                Pill(stringResource(R.string.update_all, updates), onUpdateAll)
                Spacer(Modifier.width(16.dp))
            }
            Pill(stringResource(if (autoUpdate) R.string.auto_update_on else R.string.auto_update_off), onToggleAutoUpdate)
        }
        if (error != null) {
            Text(error, color = Muted, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (catalog == null) {
            if (error == null) Text(stringResource(R.string.loading), color = Muted, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(catalog.rows(), key = { _, row -> row.first }) { rowIndex, (category, apps) ->
                Column {
                    Text(category, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
                            AppCard(
                                app = app,
                                line = statusLine(Status.of(app, installed[app.id]), work[app.id]),
                                onClick = { selected = app.id },
                                modifier = if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstApp) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    val app = catalog?.apps?.firstOrNull { it.id == selected }
    if (app != null) {
        AppPage(
            app = app,
            status = Status.of(app, installed[app.id]),
            work = work[app.id],
            onDismiss = { selected = null },
            onInstall = { onInstall(app) },
            onOpen = { selected = null; onOpen(app) },
            onUninstall = { onUninstall(app) },
        )
    }
}

/** One line under an App: what it is doing now, or else where it stands. */
@Composable
private fun statusLine(status: Status, work: Work?): String = when (work) {
    is Work.Downloading -> work.percent?.let { stringResource(R.string.downloading_percent, it) } ?: stringResource(R.string.downloading)
    Work.Installing -> stringResource(R.string.installing)
    Work.Waiting -> stringResource(R.string.waiting)
    Work.Removing -> stringResource(R.string.removing)
    is Work.Failed -> work.message
    null -> when (status) {
        Status.NotInstalled -> stringResource(R.string.not_installed)
        is Status.UpToDate -> stringResource(R.string.installed, status.version)
        is Status.UpdateAvailable -> stringResource(R.string.update_available, status.from, status.to)
        is Status.OtherSigner -> stringResource(R.string.other_signer_short)
    }
}

private val shape = RoundedCornerShape(8.dp)
private val outline = Border(BorderStroke(2.dp, Silver), shape = shape)

@Composable
private fun cardColors() = ClickableSurfaceDefaults.colors(
    containerColor = Raised,
    contentColor = Ink,
    focusedContainerColor = Raised,
    focusedContentColor = Ink,
    pressedContainerColor = Raised,
    pressedContentColor = Ink,
)

@Composable
private fun AppCard(app: CatalogApp, line: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(156.dp).semantics { contentDescription = app.label },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = cardColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f, pressedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = outline, pressedBorder = outline),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(app, Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text(app.label, color = Ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                line, color = Muted, fontSize = 14.sp, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The App's icon from the Catalog, or its initial while there is none. */
@Composable
private fun AppIcon(app: CatalogApp, modifier: Modifier) {
    Box(modifier.background(Line, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(app.label.take(1), color = Ink, fontSize = 20.sp)
        if (app.icon != null) {
            AsyncImage(app.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** An App's page: what it is, its version, and what can be done with it. */
@Composable
private fun AppPage(
    app: CatalogApp,
    status: Status,
    work: Work?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        val busy = work != null && work !is Work.Failed
        Column(
            Modifier.width(560.dp).background(Raised, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, Modifier.size(48.dp))
                Column(Modifier.padding(start = 20.dp)) {
                    Text(app.label, color = Ink, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(status, work), color = Muted, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (app.summary.isNotEmpty()) Text(app.summary, color = Ink, fontSize = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (status is Status.OtherSigner && !busy) Text(stringResource(R.string.other_signer), color = Muted, fontSize = 16.sp)
            Text(stringResource(R.string.version, app.versionName), color = Muted, fontSize = 14.sp)
            val actions = when {
                busy -> emptyList()
                status == Status.NotInstalled -> listOf(stringResource(R.string.install) to onInstall)
                status is Status.UpdateAvailable -> listOf(
                    stringResource(R.string.update) to onInstall,
                    stringResource(R.string.open) to onOpen,
                    stringResource(R.string.uninstall) to onUninstall,
                )
                status is Status.OtherSigner -> listOf(stringResource(R.string.uninstall) to onUninstall)
                else -> listOf(stringResource(R.string.open) to onOpen, stringResource(R.string.uninstall) to onUninstall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { index, (label, onClick) ->
                    Pill(label, onClick, if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            LaunchedEffect(actions.size) {
                if (actions.isEmpty()) return@LaunchedEffect
                withFrameNanos {}
                runCatching { first.requestFocus() }
            }
        }
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
        Text(label, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}
