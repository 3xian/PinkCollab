package dev.pinkcollab.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.*

private const val DirectoryFilterThreshold = 7
private val BreadcrumbTagShape = RoundedCornerShape(999.dp)

@Composable
internal fun DirectoryBrowserScreen(
    state: LoadState<Listing>?,
    hostOs: String,
    routePath: String,
    roots: List<String>,
    parentPath: String?,
    knownPaths: List<String>,
    creating: Boolean,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    refresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        val ready = state as? LoadState.Ready
        when {
            ready != null -> DirectoryListing(
                listing = ready.value,
                hostOs = hostOs,
                roots = roots,
                parentPath = parentPath,
                knownPaths = knownPaths,
                creating = creating,
                refreshing = ready.refreshing,
                browse = browse,
                select = select,
                refresh = refresh,
            )
            state is LoadState.Failed -> EmptyState("Directory unavailable", state.message, "Retry", refresh)
            else -> DirectoryLoadingState(routePath)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryListing(
    listing: Listing,
    hostOs: String,
    roots: List<String>,
    parentPath: String?,
    knownPaths: List<String>,
    creating: Boolean,
    refreshing: Boolean,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    refresh: () -> Unit,
) {
    val windows = hostUsesWindowsPaths(hostOs)
    val crumbs = remember(listing.path, roots, windows) { directoryCrumbs(listing.path, roots, windows) }
    var query by rememberSaveable(listing.path) { mutableStateOf("") }
    val needle = query.trim()
    val shown = if (needle.isEmpty()) listing.directories
        else listing.directories.filter { it.name.contains(needle, ignoreCase = true) }
    val filtering = needle.isNotEmpty()
    val showFilter = listing.directories.size >= DirectoryFilterThreshold || filtering
    val pullState = rememberPullToRefreshState()
    val folder = directoryLeaf(listing.path).ifBlank { "this directory" }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            DirectoryBreadcrumb(crumbs, parentPath, knownPaths, windows, browse)
        }
        if (showFilter) DirectoryFilter(query) { query = it }
        Box(
            Modifier
                .weight(1f)
                .pullToRefresh(state = pullState, isRefreshing = refreshing, onRefresh = refresh),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 4.dp)) {
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        DirectoryEmpty(filtering, needle, Modifier.fillParentMaxSize())
                    }
                } else {
                    items(shown, key = { it.path }) { directory ->
                        FolderRow(directory.name) { browse(directory.path) }
                    }
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                color = BrandPink,
            )
        }
        DirectoryCreateBar(folder, creating) { select(listing.path) }
    }
}

@Composable
private fun DirectoryBreadcrumb(
    crumbs: List<DirectoryCrumb>,
    parentPath: String?,
    knownPaths: List<String>,
    windows: Boolean,
    browse: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) {
        repeat(2) { withFrameNanos { } }
        scroll.scrollTo(scroll.maxValue)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(horizontal = 2.dp).size(14.dp), tint = Gray400)
            }
            val target = crumbTarget(crumbs, index, parentPath, knownPaths, windows)
            BreadcrumbTag(
                label = crumb.label,
                current = crumb.current,
                onClick = target?.let { path -> { browse(path) } },
            )
        }
    }
}

@Composable
private fun BreadcrumbTag(label: String, current: Boolean, onClick: (() -> Unit)?) {
    val navigable = onClick != null
    val fill = when {
        current -> BrandPurple.copy(alpha = 0.22f)
        navigable -> Color.White.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val color = when {
        current -> TextHigh
        navigable -> Purple200
        else -> Gray400
    }
    Box(
        Modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = rememberHapticOnClick(onClick))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .defaultMinSize(minWidth = 48.dp)
                .clip(BreadcrumbTagShape)
                .background(fill)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DirectoryFilter(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Gray400)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = { onQuery(it.replace("\n", "")) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextHigh),
            cursorBrush = SolidColor(Purple400),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Filter folders",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray400,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = rememberHapticOnClick { onQuery("") }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, "Clear filter", tint = Gray400)
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = Purple200)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Gray400)
    }
}

@Composable
private fun DirectoryEmpty(filtering: Boolean, needle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (filtering) Icons.Outlined.SearchOff else Icons.Outlined.FolderOff,
            null,
            Modifier.size(28.dp),
            tint = Purple200,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (filtering) "No matching folders" else "No folders here",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (filtering) "Nothing matches “$needle”." else "Start a session in this directory.",
            Modifier.fillMaxWidth(),
            color = TextMid,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DirectoryCreateBar(folder: String, creating: Boolean, onCreate: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        PrimaryButton(
            onClick = onCreate,
            enabled = !creating,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Creating session")
            } else {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Create session in", maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        folder,
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryLoadingState(routePath: String) {
    val transition = rememberInfiniteTransition(label = "directoryLoading")
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "directoryLoadingPulse",
    )
    Column(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = "Loading directory" }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(32.dp)
                        .clip(BreadcrumbTagShape)
                        .background(BrandPurple.copy(alpha = 0.22f))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        directoryLeaf(routePath).ifBlank { "Workspace" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            repeat(8) { index ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = Purple200.copy(alpha = 0.34f + pulse * 0.2f))
                    Spacer(Modifier.width(10.dp))
                    DirectoryPlaceholder(if (index % 3 == 0) 0.46f else 0.32f, 12.dp, pulse)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
            DirectoryPlaceholder(1f, 48.dp, pulse)
        }
    }
}

@Composable
private fun DirectoryPlaceholder(width: Float, height: Dp, pulse: Float) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .alpha(pulse)
            .background(Color.White.copy(alpha = 0.11f), RoundedCornerShape(999.dp)),
    )
}
