package com.rrajath.grove.ui.screens

import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import com.rrajath.grove.ui.components.GroveDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.res.painterResource
import com.rrajath.grove.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.capture.FilenameValidation
import com.rrajath.grove.settings.PinKind
import com.rrajath.grove.settings.PinnedItem
import com.rrajath.grove.sync.SyncState
import com.rrajath.grove.ui.components.ChangeIconColorDialog
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.MonogramTile
import com.rrajath.grove.ui.components.notebookIcon
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.ReminderPermissionBanner
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.components.monogramLetter
import com.rrajath.grove.ui.components.monogramPalette
import com.rrajath.grove.ui.components.nameHashPaletteKey
import com.rrajath.grove.ui.components.searchIcon
import com.rrajath.grove.ui.components.SyncedCheckIcon
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDotBadge
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.ui.vault.FOLDER_DRILL_THRESHOLD
import com.rrajath.grove.ui.vault.FolderNode
import com.rrajath.grove.ui.vault.NotebookItem
import com.rrajath.grove.ui.vault.NotebookTreeRow
import com.rrajath.grove.ui.vault.NotebookTreeRun
import com.rrajath.grove.ui.vault.TREE_EXPAND_MILLIS
import com.rrajath.grove.ui.vault.drillLevel
import com.rrajath.grove.ui.vault.groupNotebookTreeRuns
import com.rrajath.grove.ui.vault.NotebookEditEvent
import com.rrajath.grove.ui.vault.NotebooksUiState
import com.rrajath.grove.ui.vault.NotebooksViewModel
import com.rrajath.grove.ui.vault.PinnedRow
import com.rrajath.grove.vault.vaultPath

/** Notebook list home screen (design spec §2), driven by the sync index. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreen(
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenConflict: (String) -> Unit,
    viewModel: NotebooksViewModel = viewModel(factory = NotebooksViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Macrobenchmark's timeToFullDisplayMs: signal Activity.reportFullyDrawn()
    // once the notebook tree has actually been built and laid out, not just when
    // the first (empty) frame renders. Notebooks is the app's start destination,
    // so this is the "app is usable" moment. NoVault stays unreported — there is
    // no content to wait for, and the app never reported fully-drawn before this.
    ReportDrawnWhen { state is NotebooksUiState.Loaded }
    // Non-null while the "New notebook" dialog is open; the value is the target
    // directory ("" = vault root, or the folder being browsed in the drill view).
    var createInDir by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var styleTarget by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<String?>(null) }
    // Non-null while the "Delete notebook?" confirmation is open; the file name.
    var notebookDeleteTarget by remember { mutableStateOf<String?>(null) }
    // Folder long-press menu targets (variant 1a follow-up); the value is the folder's dir path.
    // Stored as the path (not the node) so each dialog reads the folder's live state on recompose.
    var folderRenameTarget by remember { mutableStateOf<String?>(null) }
    var folderColorTarget by remember { mutableStateOf<String?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<String?>(null) }
    // Drill-down view (variant 1b): the folder currently being browsed as a full
    // screen, or null for the inline tree. A folder with more than
    // FOLDER_DRILL_THRESHOLD files opens here instead of expanding in place.
    var drillDir by rememberSaveable { mutableStateOf<String?>(null) }
    fun drillUp() {
        drillDir = drillDir?.substringBeforeLast('/', "")?.ifEmpty { null }
    }

    BackHandler(enabled = drillDir != null) { drillUp() }

    // Create/rename outcomes: close the open name dialog on success, or toast the
    // collision and leave the dialog mounted so the user can correct the name.
    LaunchedEffect(Unit) {
        viewModel.editEvents.collect { event ->
            when (event) {
                is NotebookEditEvent.Succeeded -> {
                    // Only one name dialog is ever open at a time.
                    createInDir = null
                    renameTarget = null
                    folderRenameTarget = null
                }
                is NotebookEditEvent.NameTaken ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.saveVaultUri(uri.toString())
        }
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            val loadedState = state as? NotebooksUiState.Loaded
            val currentDrill = drillDir
            if (loadedState != null && currentDrill != null) {
                GroveTopBar(
                    leading = { IconGlyph("←", onClick = { drillUp() }) },
                    title = {
                        Text(
                            currentDrill.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleLarge,
                            color = c.ink,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    subtitle = {
                        Breadcrumb(
                            vaultName = loadedState.vaultDisplayName,
                            dir = currentDrill,
                            onExit = { drillDir = null },
                            onNavigate = { drillDir = it },
                        )
                    },
                )
            } else {
                GroveTopBar(
                    leading = {
                        NewDotBadge(NewAnchors.TOPBAR_MENU) {
                            IconGlyph("☰", onClick = onOpenDrawer)
                        }
                    },
                    title = {
                        Text(
                            "Notebooks",
                            style = MaterialTheme.typography.titleLarge,
                            color = c.ink,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    actions = {
                        if (loadedState != null) {
                            // The 8dp end padding pulls the trailing icons' visual
                            // edge onto the same line as the list rows' trailing
                            // pin icons. GroveTopBar insets actions by only 8dp and
                            // an IconButton centres its 24dp glyph in a 48dp box
                            // (~12dp of slack on the right); the list's pin icons
                            // land ~8dp further out (14dp Column + 8dp Row + 6dp
                            // pin end padding, less the pin vector's own margin).
                            Row(
                                modifier = Modifier.padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (loadedState.hasFolders) {
                                    IconGlyph(
                                        if (loadedState.allFoldersCollapsed) Icons.Default.UnfoldMore
                                        else Icons.Default.UnfoldLess,
                                        contentDescription = if (loadedState.allFoldersCollapsed) {
                                            "Expand all folders"
                                        } else {
                                            "Collapse all folders"
                                        },
                                        onClick = {
                                            viewModel.setAllFoldersExpanded(loadedState.allFoldersCollapsed)
                                        },
                                    )
                                }
                                SyncStatusIcon(loadedState, context)
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        // The reachability dock floats over the list (Files-by-Google style):
        // it is an overlay in this Box, not a bottomBar slot, so rows stay
        // full-height and scroll behind it instead of being inset above it.
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            when (val s = state) {
                is NotebooksUiState.NoVault ->
                    NoVaultState(onChooseFolder = { folderPicker.launch(null) })

                is NotebooksUiState.Loaded -> {
                    ReminderPermissionBanner(pendingCount = s.remindersPendingPermission)

                    // Read once as primitives instead of inside the row closures below: `s` is
                    // a fresh instance on every sync-pulling tick, and a composable lambda that
                    // captures `s` directly can't be memoized across those ticks even when
                    // these two fields didn't change. Capturing the primitives instead lets the
                    // compiler's automatic per-lambda remember key off values that are actually
                    // stable across an unrelated field change.
                    val showFileIcons = s.showFileIcons
                    val drillThreshold = s.drillThreshold
                    val livePins = s.livePins

                    @Composable
                    fun fileRow(
                        nb: NotebookItem,
                        depth: Int,
                        showPath: Boolean,
                        flat: Boolean = false,
                        pinnedByFolder: Boolean = false,
                    ) {
                        FileRow(
                            notebook = nb,
                            showFileIcon = showFileIcons,
                            depth = depth,
                            showPathSubtitle = showPath,
                            flat = flat,
                            pinnedByFolder = pinnedByFolder,
                            onClick = { onOpenNotebook(nb.fileName) },
                            onOpenConflict = { onOpenConflict(nb.fileName) },
                            onRename = { renameTarget = nb.fileName },
                            onMove = { moveTarget = nb.fileName },
                            onChangeIcon = { styleTarget = nb.fileName },
                            onDelete = { notebookDeleteTarget = nb.fileName },
                            onForceReload = { viewModel.forceReload(nb.fileName) },
                            onPin = { viewModel.pinNotebook(nb.fileName) },
                            onUnpin = { viewModel.unpinNotebook(nb.fileName) },
                            pinIndex = livePins.indexOf(PinnedItem(PinKind.FILE, nb.fileName)),
                            pinCount = livePins.size,
                            onMovePin = { viewModel.movePin(PinnedItem(PinKind.FILE, nb.fileName), it) },
                        )
                    }

                    @Composable
                    fun folderRow(
                        node: FolderNode,
                        expanded: Boolean = false,
                        flat: Boolean = false,
                        pinnedStrip: Boolean = false,
                        onClick: () -> Unit,
                    ) {
                        FolderRow(
                            node = node,
                            expanded = expanded,
                            chevron = !flat && node.recursiveOrgCount > drillThreshold,
                            flat = flat,
                            pinnedStrip = pinnedStrip,
                            onClick = onClick,
                            onPin = { viewModel.pinFolder(node.dir) },
                            onUnpin = { viewModel.unpinFolder(node.dir) },
                            pinIndex = livePins.indexOf(PinnedItem(PinKind.FOLDER, node.dir)),
                            pinCount = livePins.size,
                            onMovePin = { viewModel.movePin(PinnedItem(PinKind.FOLDER, node.dir), it) },
                            onRename = { folderRenameTarget = node.dir },
                            onChangeColor = { folderColorTarget = node.dir },
                            onDelete = { folderDeleteTarget = node.dir },
                        )
                    }

                    // Tree/pinned-strip tap: drill into a big folder, else toggle it in place.
                    fun openFolder(node: FolderNode) {
                        if (node.recursiveOrgCount > s.drillThreshold) drillDir = node.dir
                        else viewModel.toggleFolder(node.dir)
                    }

                    if (s.flat) {
                        // Settings § Look and Feel → "Flatten folders": one path-subtitled
                        // file list, no tree / drill-down / expand-collapse.
                        val isRefreshing = s.syncState is SyncState.Checking || s.syncState is SyncState.Pulling
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.requestSync() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (s.notebooks.isEmpty()) {
                                CenterMessage("✦", "No .org files here yet", "Capture a note or make a notebook from the bar below")
                            } else {
                                val listState = rememberLazyListState()
                                Box(Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().testTag("notebooks_list"),
                                        // Last-row clearance for the floating dock the list scrolls under.
                                        contentPadding = PaddingValues(top = 4.dp, bottom = DOCK_CLEARANCE),
                                    ) {
                                        items(s.flatPinned, key = { "pin:${it.fileName}" }) { nb ->
                                            Box(Modifier.animateItem()) {
                                                fileRow(
                                                    nb, depth = 0, showPath = true, flat = true,
                                                    pinnedByFolder = true,
                                                )
                                            }
                                        }
                                        items(s.flatRows, key = { "file:${it.fileName}" }) { nb ->
                                            Box(Modifier.animateItem()) {
                                                fileRow(nb, depth = 0, showPath = true, flat = true)
                                            }
                                        }
                                    }
                                    ScrollJumpButtons(
                                        listState = listState,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(bottom = DOCK_CLEARANCE, end = 16.dp),
                                    )
                                }
                            }
                        }
                    } else {
                    val currentDrill = drillDir
                    if (currentDrill != null) {
                        // Variant 1b: one folder as a full screen, rows never indent.
                        val level = remember(s.notebooks, currentDrill, s.pinnedFolders, s.folderColors, s.sort) {
                            drillLevel(
                                s.notebooks, currentDrill,
                                folderColors = s.folderColors,
                                pinnedFolders = s.pinnedFolders.map { it.dir },
                                sort = s.sort,
                            )
                        }
                        if (level.childFolders.isEmpty() && level.files.isEmpty()) {
                            CenterMessage("✦", "Nothing in this folder yet")
                        } else {
                            val listState = rememberLazyListState()
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().testTag("notebooks_drill_list"),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = DOCK_CLEARANCE),
                                ) {
                                    items(level.childFolders, key = { "dir:${it.dir}" }) { node ->
                                        folderRow(
                                            node = node,
                                            flat = true,
                                            onClick = { drillDir = node.dir },
                                        )
                                    }
                                    items(level.files, key = { "file:${it.fileName}" }) { nb ->
                                        fileRow(nb, depth = 0, showPath = false, flat = true)
                                    }
                                }
                                ScrollJumpButtons(
                                    listState = listState,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = DOCK_CLEARANCE, end = 16.dp),
                                )
                            }
                        }
                    } else {
                        val isRefreshing = s.syncState is SyncState.Checking || s.syncState is SyncState.Pulling
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.requestSync() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (s.notebooks.isEmpty()) {
                                CenterMessage("✦", "No .org files here yet", "Capture a note or make a notebook from the bar below")
                            } else {
                                val listState = rememberLazyListState()
                                // Each top-level folder + its visible descendants is one list
                                // item, so a toggle animates the whole subtree's height as a
                                // single block instead of N competing per-row animations.
                                val treeRuns = remember(s.rows) { groupNotebookTreeRuns(s.rows) }
                                Box(Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().testTag("notebooks_list"),
                                        // Last-row clearance for the floating dock the list scrolls under.
                                        contentPadding = PaddingValues(top = 4.dp, bottom = DOCK_CLEARANCE),
                                    ) {
                                        if (s.pinnedStrip.isNotEmpty()) {
                                            // One block, in the order the user pinned things.
                                            items(
                                                s.pinnedStrip,
                                                key = { row ->
                                                    when (row) {
                                                        is PinnedRow.Folder -> "pindir:${row.node.dir}"
                                                        is PinnedRow.File -> "pin:${row.item.fileName}"
                                                    }
                                                },
                                            ) { row ->
                                                Box(Modifier.animateItem()) {
                                                    when (row) {
                                                        is PinnedRow.Folder -> Column {
                                                            folderRow(
                                                                node = row.node,
                                                                expanded = row.node.dir in s.expandedFolders,
                                                                pinnedStrip = true,
                                                                onClick = { openFolder(row.node) },
                                                            )
                                                            // Expanded pinned folder: its subtree renders
                                                            // indented directly beneath its strip row,
                                                            // growing in with a height animation.
                                                            Column(
                                                                Modifier.animateContentSize(
                                                                    tween(TREE_EXPAND_MILLIS, easing = FastOutSlowInEasing)
                                                                )
                                                            ) {
                                                                s.pinnedFolderExpansions[row.node.dir]?.forEach { sub ->
                                                                    when (sub) {
                                                                        is NotebookTreeRow.Folder ->
                                                                            folderRow(
                                                                                node = sub.node,
                                                                                expanded = sub.expanded,
                                                                                onClick = { openFolder(sub.node) },
                                                                            )
                                                                        is NotebookTreeRow.File ->
                                                                            fileRow(sub.item, sub.depth, showPath = false)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        is PinnedRow.File ->
                                                            fileRow(row.item, depth = 0, showPath = true)
                                                    }
                                                }
                                            }
                                        }
                                        items(
                                            treeRuns,
                                            key = { run ->
                                                when (run) {
                                                    is NotebookTreeRun.Subtree -> "dir:${run.header.node.dir}"
                                                    is NotebookTreeRun.Loose -> when (val r = run.row) {
                                                        is NotebookTreeRow.Folder -> "dir:${r.node.dir}"
                                                        is NotebookTreeRow.File -> "file:${r.item.fileName}"
                                                    }
                                                }
                                            },
                                        ) { run ->
                                            when (run) {
                                                is NotebookTreeRun.Subtree ->
                                                    Column(Modifier.animateItem()) {
                                                        folderRow(
                                                            node = run.header.node,
                                                            expanded = run.header.expanded,
                                                            onClick = { openFolder(run.header.node) },
                                                        )
                                                        // The whole subtree grows/shrinks its height as
                                                        // one block when a folder inside it toggles.
                                                        Column(
                                                            Modifier.animateContentSize(
                                                                tween(TREE_EXPAND_MILLIS, easing = FastOutSlowInEasing)
                                                            )
                                                        ) {
                                                            run.descendants.forEach { sub ->
                                                                when (sub) {
                                                                    is NotebookTreeRow.Folder ->
                                                                        folderRow(
                                                                            node = sub.node,
                                                                            expanded = sub.expanded,
                                                                            onClick = { openFolder(sub.node) },
                                                                        )
                                                                    is NotebookTreeRow.File ->
                                                                        fileRow(sub.item, sub.depth, showPath = false)
                                                                }
                                                            }
                                                        }
                                                    }
                                                is NotebookTreeRun.Loose ->
                                                    Box(Modifier.animateItem()) {
                                                        when (val r = run.row) {
                                                            is NotebookTreeRow.Folder ->
                                                                folderRow(
                                                                    node = r.node,
                                                                    expanded = r.expanded,
                                                                    onClick = { openFolder(r.node) },
                                                                )
                                                            is NotebookTreeRow.File ->
                                                                fileRow(r.item, r.depth, showPath = false)
                                                        }
                                                    }
                                            }
                                        }
                                    }
                                    ScrollJumpButtons(
                                        listState = listState,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(bottom = DOCK_CLEARANCE, end = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        // Reachability dock: Search / Capture / New notebook all sit in the
        // thumb zone. Shown on the tree view and while drilling a folder;
        // "New notebook" targets the folder currently in view.
        if (state is NotebooksUiState.Loaded) {
            NotebooksDock(
                onSearch = onOpenSearch,
                onCapture = onOpenCapture,
                onNewNotebook = { createInDir = drillDir ?: "" },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        }
    }

    createInDir?.let { dir ->
        NameDialog(
            title = "New notebook",
            initial = "",
            confirmLabel = "Create",
            contextLabel = if (dir.isNotEmpty()) "in $dir/" else null,
            placeholder = "ideas",
            filenameSuffix = ".org",
            validate = FilenameValidation::errorForNewNotebook,
            helperText = "Use a slash to nest it in folders, e.g. work/ideas",
            onDismiss = { createInDir = null },
            // Closed by the editEvents collector: on success, or left open with a
            // toast when the name already exists.
            onConfirm = { name -> viewModel.createNotebook(name, dir) },
        )
    }
    moveTarget?.let { target ->
        val loaded = state as? NotebooksUiState.Loaded
        val nb = loaded?.notebooks?.firstOrNull { it.fileName == target }
        if (loaded == null || nb == null) {
            moveTarget = null
        } else {
            MoveToFolderSheet(
                displayName = nb.displayName,
                currentDir = nb.dir,
                notebooks = loaded.notebooks,
                vaultName = loaded.vaultDisplayName,
                onConfirm = { newDir ->
                    viewModel.moveNotebook(target, newDir)
                    moveTarget = null
                },
                onDismiss = { moveTarget = null },
            )
        }
    }
    renameTarget?.let { target ->
        NameDialog(
            title = "Rename $target",
            initial = target,
            confirmLabel = "Rename",
            filenameSuffix = ".org",
            validate = FilenameValidation::errorForNewNotebook,
            onDismiss = { renameTarget = null },
            // Closed by the editEvents collector (see the create dialog).
            onConfirm = { name -> viewModel.renameNotebook(target, name) },
        )
    }
    styleTarget?.let { target ->
        val notebook = (state as? NotebooksUiState.Loaded)?.notebooks
            ?.firstOrNull { it.fileName == target }
        if (notebook == null) {
            styleTarget = null
        } else {
            ChangeIconColorDialog(
                name = notebook.displayName,
                hint = if (notebook.displayName == notebook.fileName.substringAfterLast('/')) {
                    "Letter follows the file name"
                } else {
                    "Letter follows the title"
                },
                letter = monogramLetter(notebook.displayName),
                currentColorKey = notebook.color ?: nameHashPaletteKey(notebook.fileName),
                onPickColor = { key -> viewModel.setNotebookColor(target, key) },
                onDismiss = { styleTarget = null },
            )
        }
    }
    folderRenameTarget?.let { dir ->
        NameDialog(
            title = "Rename ${dir.substringAfterLast('/')}",
            initial = dir.substringAfterLast('/'),
            confirmLabel = "Rename",
            placeholder = "folder name",
            onDismiss = { folderRenameTarget = null },
            // Closed by the editEvents collector (see the create dialog).
            onConfirm = { name -> viewModel.renameFolder(dir, name) },
        )
    }
    folderColorTarget?.let { dir ->
        val loaded = state as? NotebooksUiState.Loaded
        ChangeIconColorDialog(
            name = dir.substringAfterLast('/'),
            hint = "Color follows the folder name",
            letter = "▪",
            glyph = "▪",
            currentColorKey = loaded?.folderColors?.get(dir) ?: nameHashPaletteKey(dir),
            onPickColor = { key -> viewModel.setFolderColor(dir, key) },
            onDismiss = { folderColorTarget = null },
        )
    }
    folderDeleteTarget?.let { dir ->
        val c = MaterialTheme.grove
        val loaded = state as? NotebooksUiState.Loaded
        val node = loaded?.let { l ->
            fun findIn(rows: List<NotebookTreeRow>) =
                rows.firstNotNullOfOrNull { (it as? NotebookTreeRow.Folder)?.node?.takeIf { n -> n.dir == dir } }
            findIn(l.rows)
                ?: l.pinnedFolders.firstOrNull { it.dir == dir }
                ?: l.pinnedFolderExpansions.values.firstNotNullOfOrNull { findIn(it) }
        }
        if (node == null) {
            folderDeleteTarget = null
        } else {
            val count = node.recursiveOrgCount
            AlertDialog(
                onDismissRequest = { folderDeleteTarget = null },
                containerColor = c.surface,
                title = {
                    Text(
                        "Delete ${node.name}?",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink,
                    )
                },
                text = {
                    val subject = if (count == 1) "1 notebook" else "$count notebooks"
                    Text(
                        buildAnnotatedString {
                            append("This will ")
                            withStyle(SpanStyle(color = c.red, fontWeight = FontWeight.Bold)) {
                                append("permanently delete")
                            }
                            append(" $subject and cannot be undone. Are you sure?")
                        },
                        fontFamily = PlexSans, fontSize = 13.sp, color = c.ink2,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteFolder(dir)
                            folderDeleteTarget = null
                        },
                    ) { Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    TextButton(onClick = { folderDeleteTarget = null }) {
                        Text("Cancel", color = c.ink2)
                    }
                },
            )
        }
    }
    notebookDeleteTarget?.let { target ->
        val c = MaterialTheme.grove
        val nb = (state as? NotebooksUiState.Loaded)?.notebooks?.firstOrNull { it.fileName == target }
        AlertDialog(
            onDismissRequest = { notebookDeleteTarget = null },
            containerColor = c.surface,
            title = {
                Text(
                    "Delete ${target.substringAfterLast('/').removeSuffix(".org")}?",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink,
                )
            },
            text = {
                // Note count from the row, when the notebook has been indexed;
                // otherwise fall back to naming the notebook itself.
                val count = nb?.takeIf { it.isIndexed }?.noteCount
                val subject = when {
                    count == null -> "this notebook"
                    count == 1 -> "1 note"
                    else -> "$count notes"
                }
                Text(
                    buildAnnotatedString {
                        append("This will ")
                        withStyle(SpanStyle(color = c.red, fontWeight = FontWeight.Bold)) {
                            append("permanently delete")
                        }
                        append(" $subject and cannot be undone. Are you sure?")
                    },
                    fontFamily = PlexSans, fontSize = 13.sp, color = c.ink2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNotebook(target)
                        notebookDeleteTarget = null
                    },
                ) { Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { notebookDeleteTarget = null }) {
                    Text("Cancel", color = c.ink2)
                }
            },
        )
    }
}

/**
 * Sync status indicator in the top app bar. Shows a green check when the last sync
 * completed successfully with no conflicts, a warning glyph when there is an active
 * sync error or any notebook has an unresolved conflict, or a spinner mid-sync.
 * Tapping any of them shows a toast with the sync status detail.
 */
@Composable
private fun SyncStatusIcon(state: NotebooksUiState.Loaded, context: android.content.Context) {
    val c = MaterialTheme.grove
    // Only re-scan for conflicts when the notebooks list itself changes, not on every
    // sync-state tick (this icon is in the always-visible top bar).
    val conflictCount = remember(state.notebooks) { state.notebooks.count { it.hasConflict } }
    when (val sync = state.syncState) {
        is SyncState.Error -> {
            IconButton(
                onClick = {
                    Toast.makeText(context, "Sync issue: ${sync.message}", Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Sync issue", tint = c.amber)
            }
        }
        is SyncState.Checking -> {
            IconButton(
                onClick = { Toast.makeText(context, "Checking for changes…", Toast.LENGTH_SHORT).show() },
            ) {
                Icon(Icons.Default.Sync, contentDescription = "Checking sync", tint = c.ink2)
            }
        }
        is SyncState.Pulling -> {
            IconButton(
                onClick = {
                    Toast.makeText(
                        context,
                        "Syncing ${sync.fileName.substringAfterLast('/')}…",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Icon(Icons.Default.Sync, contentDescription = "Syncing", tint = c.ink2)
            }
        }
        else -> {
            val minutes = state.lastSyncAt
                ?.let { ((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0) }
            val syncedMessage = if (minutes != null) "Synced $minutes minutes ago" else "Not synced yet"
            if (conflictCount > 0) {
                IconButton(
                    onClick = {
                        val conflictMessage = if (conflictCount == 1) {
                            "1 file has a conflict"
                        } else {
                            "$conflictCount files have conflicts"
                        }
                        Toast.makeText(context, "$syncedMessage · $conflictMessage", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Sync conflicts", tint = c.amber)
                }
            } else {
                IconButton(
                    onClick = { Toast.makeText(context, syncedMessage, Toast.LENGTH_SHORT).show() },
                ) {
                    SyncedCheckIcon(contentDescription = "Synced")
                }
            }
        }
    }
}

/**
 * Vertical indent rail + left inset shared by every tree row. Mirrors the
 * prototype math: indent caps at 2 levels (`min(depth, 2) * 20dp`), with a 1.5dp
 * `line2` guide two dp left of the content for any row below the root.
 */
@Composable
private fun TreeRowContainer(depth: Int, content: @Composable () -> Unit) {
    val c = MaterialTheme.grove
    val indent: Dp = (minOf(depth, 2) * 20).dp
    // drawBehind reads the box's own measured size for free; height(IntrinsicSize.Min) used
    // to force an extra intrinsic-measurement pass on every tree row just to size a 1.5dp
    // divider Box to match.
    val railModifier = if (depth > 0) {
        Modifier.drawBehind {
            drawRoundRect(
                color = c.line2,
                topLeft = Offset((indent - 2.dp).toPx(), 0f),
                size = Size(1.5.dp.toPx(), size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxWidth().then(railModifier)) {
        Box(Modifier.padding(start = indent)) { content() }
    }
}

/**
 * Drill-down breadcrumb (variant 1b): the vault folder's name, then one crumb
 * per path segment. Tapping the root name returns to the inline tree; tapping a
 * segment jumps to that folder. The last crumb is the current folder, highlighted.
 */
@Composable
private fun Breadcrumb(
    vaultName: String,
    dir: String,
    onExit: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val c = MaterialTheme.grove
    val segments = dir.split('/').filter { it.isNotEmpty() }

    @Composable
    fun crumb(label: String, current: Boolean, onClick: () -> Unit) {
        Text(
            label,
            fontFamily = PlexMono,
            fontSize = 12.sp,
            color = if (current) c.ink else c.ink3,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (current) Modifier.background(c.surface2) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumb(vaultName, current = segments.isEmpty(), onClick = onExit)
        segments.forEachIndexed { i, seg ->
            Text(
                "/",
                fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.padding(horizontal = 1.dp),
            )
            val path = segments.subList(0, i + 1).joinToString("/")
            crumb(seg, current = i == segments.lastIndex, onClick = { onNavigate(path) })
        }
    }
}

/**
 * A folder row. The 42dp tile starts at the same x-position as a [FileRow]'s
 * monogram tile at the same tree level, so folder and file icons line up in a
 * single column; nesting is conveyed only by [TreeRowContainer]'s indent.
 *
 * The expand/collapse affordance sits at the right end of the row, outboard of
 * the pin / conflict indicators. In the inline tree (variant 1a) a normal folder
 * shows a `▾` chevron that animates 180° between collapsed and [expanded]. A
 * folder over [FOLDER_DRILL_THRESHOLD] files ([chevron]) shows a static `›`
 * instead, since tapping it opens the drill-down view rather than expanding in
 * place; the drill-down view itself ([flat]) also shows a static `›`.
 *
 * The tile is a 42dp rounded square (matching the file monogram tiles) with the
 * folder's palette colour: soft-tint fill, a tinted border, and a `▪` glyph. A
 * descendant sync conflict shows a non-interactive amber warning glyph.
 *
 * The top-level folder row sits flush with the root files (no rail): its indent
 * rail belongs to its *children*, so the container depth is one less than the
 * folder's own nesting level. [pinnedStrip] forces that flush depth for a copy
 * shown in the Pinned strip.
 *
 * Long-pressing the row opens a menu mirroring the file row's: pin/unpin,
 * rename, change icon colour, and delete (permanently deletes every descendant file).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    node: FolderNode,
    expanded: Boolean,
    chevron: Boolean = false,
    flat: Boolean = false,
    pinnedStrip: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit = {},
    onUnpin: () -> Unit = {},
    /** Position among the live pins (-1 when not pinned) and their count, for Move up/down. */
    pinIndex: Int = -1,
    pinCount: Int = 0,
    onMovePin: (delta: Int) -> Unit = {},
    onRename: () -> Unit = {},
    onChangeColor: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val c = MaterialTheme.grove
    val (fg, bg) = monogramPalette(c, node.colorKey)
    var menuOpen by remember { mutableStateOf(false) }
    TreeRowContainer(depth = if (flat || pinnedStrip) 0 else node.depth - 1) {
      Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▪", fontFamily = PlexMono, fontSize = 15.sp, color = fg)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp, color = c.ink,
                )
                Text(
                    buildString {
                        append(pluralCount(node.recursiveOrgCount, "file"))
                        if (node.directFolderCount > 0) {
                            append(" / ")
                            append(pluralCount(node.directFolderCount, "folder"))
                        }
                    },
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (node.isPinned) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.padding(end = 6.dp).size(14.dp),
                )
            }
            if (node.hasConflictDescendant) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Contains a sync conflict",
                    tint = c.amber,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // 6dp end inset so the chevron's glyph lands on the same trailing
            // line as the file rows' pin icon (also end = 6.dp) and the top bar's
            // trailing icons. The Box keeps the padding off the rotating "▾" so
            // it stays clear of the graphicsLayer pivot.
            Box(Modifier.padding(end = 6.dp)) {
                if (flat || chevron) {
                    // Tapping the row navigates away (drill-down / flat list), so
                    // the affordance is a static right-pointing chevron.
                    Text("›", fontFamily = PlexMono, fontSize = 15.sp, color = c.ink3)
                } else {
                    // Expands in place: a downward chevron that flips 180° when open,
                    // matching the app's dropdown-picker chevron animation.
                    val chevronAngle by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = tween(TREE_EXPAND_MILLIS, easing = FastOutSlowInEasing),
                        label = "folderChevron",
                    )
                    Text(
                        "▾",
                        fontFamily = PlexMono,
                        fontSize = 13.sp,
                        color = c.ink3,
                        modifier = Modifier.graphicsLayer { rotationZ = chevronAngle },
                    )
                }
            }
        }
        GroveDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (node.isPinned) {
                DropdownMenuItem(
                    text = { Text("Unpin", fontFamily = PlexSans, color = c.ink) },
                    onClick = { menuOpen = false; onUnpin() },
                )
                PinMoveItems(pinIndex, pinCount, onMovePin, onDone = { menuOpen = false })
            } else {
                DropdownMenuItem(
                    text = { Text("Pin to top", fontFamily = PlexSans, color = c.ink) },
                    onClick = { menuOpen = false; onPin() },
                )
            }
            DropdownMenuItem(
                text = { Text("Rename", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Change icon color", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onChangeColor() },
            )
            DropdownMenuItem(
                text = { Text("Delete", fontFamily = PlexSans, color = c.red) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
      }
    }
}

/** The pinned-row Move up / Move down menu items, styled like the drawer's Favorites menu. */
@Composable
private fun PinMoveItems(pinIndex: Int, pinCount: Int, onMovePin: (delta: Int) -> Unit, onDone: () -> Unit) {
    if (pinIndex < 0) return
    val c = MaterialTheme.grove
    val canMoveUp = pinIndex > 0
    val canMoveDown = pinIndex < pinCount - 1
    DropdownMenuItem(
        text = { Text("Move up", fontFamily = PlexSans, color = if (canMoveUp) c.ink else c.ink3) },
        enabled = canMoveUp,
        onClick = { onDone(); onMovePin(-1) },
    )
    DropdownMenuItem(
        text = { Text("Move down", fontFamily = PlexSans, color = if (canMoveDown) c.ink else c.ink3) },
        enabled = canMoveDown,
        onClick = { onDone(); onMovePin(1) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    notebook: NotebookItem,
    showFileIcon: Boolean,
    depth: Int,
    showPathSubtitle: Boolean,
    flat: Boolean = false,
    /** Force the pin indicator on: the file itself isn't pinned, but a pinned ancestor folder is. */
    pinnedByFolder: Boolean = false,
    onClick: () -> Unit,
    onOpenConflict: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit,
    onForceReload: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    /** Position among the live pins (-1 when not pinned) and their count, for Move up/down. */
    pinIndex: Int = -1,
    pinCount: Int = 0,
    onMovePin: (delta: Int) -> Unit = {},
) {
    val c = MaterialTheme.grove
    val letter = monogramLetter(notebook.displayName)
    val colorKey = notebook.color ?: nameHashPaletteKey(notebook.fileName)
    var menuOpen by remember { mutableStateOf(false) }
    // The monogram tile always starts at the row's own start padding; nesting is
    // conveyed by TreeRowContainer's indent alone, so a file's tile lines up with
    // its sibling folder tiles (which no longer carry a leading caret column).

    TreeRowContainer(depth = if (flat) 0 else depth) {
      Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .testTag("notebook_row")
                .semantics { contentDescription = notebook.displayName }
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFileIcon) {
                MonogramTile(letter = letter, colorKey = colorKey, size = 42.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    notebook.displayName,
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = c.ink,
                )
                if (showPathSubtitle && notebook.dir.isNotEmpty()) {
                    Text(
                        "${notebook.dir}/",
                        fontFamily = PlexMono, fontSize = 11.sp, color = c.ink3,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                val ago = remember(notebook.lastModified) { DateUtils.getRelativeTimeSpanString(notebook.lastModified) }
                // Stub rows show only the timestamp until the background parse
                // fills in the count; avoids a "0 notes" flash before the jump.
                Text(
                    if (notebook.isIndexed) {
                        "${pluralCount(notebook.noteCount, "note")} · $ago"
                    } else {
                        "$ago"
                    },
                    fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2,
                )
            }
            if (notebook.isPinned || pinnedByFolder) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.padding(end = 6.dp).size(14.dp),
                )
            }
            if (notebook.hasConflict) {
                Pill("Conflict", fg = c.amber, bg = c.amberSoft, onClick = onOpenConflict)
            }
        }
        GroveDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (notebook.isPinned) {
                DropdownMenuItem(
                    text = { Text("Unpin", fontFamily = PlexSans, color = c.ink) },
                    onClick = { menuOpen = false; onUnpin() },
                )
                PinMoveItems(pinIndex, pinCount, onMovePin, onDone = { menuOpen = false })
            } else {
                DropdownMenuItem(
                    text = { Text("Pin to top", fontFamily = PlexSans, color = c.ink) },
                    onClick = { menuOpen = false; onPin() },
                )
            }
            DropdownMenuItem(
                text = { Text("Rename", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Move to folder", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("Change icon color", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onChangeIcon() },
            )
            DropdownMenuItem(
                text = { Text("Force reload", fontFamily = PlexSans, color = c.ink) },
                onClick = { menuOpen = false; onForceReload() },
            )
            if (notebook.hasConflict) {
                DropdownMenuItem(
                    text = { Text("Resolve conflict", fontFamily = PlexSans, color = c.amber) },
                    onClick = { menuOpen = false; onOpenConflict() },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", fontFamily = PlexSans, color = c.red) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
      }
    }
}

@Composable
private fun NoVaultState(onChooseFolder: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier.fillMaxSize().testTag("notebooks_empty_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✦", fontFamily = PlexMono, fontSize = 28.sp, color = c.ink3)
            Spacer(Modifier.height(10.dp))
            Text(
                "Choose your org folder",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = c.ink2,
            )
            Text(
                "Pick the folder Syncthing shares with your laptop",
                fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accent)
                    .clickable(onClick = onChooseFolder)
                    .padding(horizontal = 22.dp, vertical = 13.dp),
            ) {
                Text(
                    "Choose folder",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, color = c.accentInk,
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(glyph: String, title: String, subtitle: String? = null) {
    val c = MaterialTheme.grove
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontFamily = PlexMono, fontSize = 28.sp, color = c.ink3)
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = c.ink2,
            )
            if (subtitle != null) {
                Text(subtitle, fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3)
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    contextLabel: String? = null,
    placeholder: String = "notebook.org",
    // Returns a user-facing reason the name is invalid, or null when it's fine.
    // When set, the confirm button stays disabled until the name validates.
    validate: ((String) -> String?)? = null,
    // Persistent hint under the field, shown while there's no validation error.
    helperText: String? = null,
    // A fixed extension (e.g. ".org") shown as a non-editable suffix inside the
    // field; the field then holds just the stem, and it's re-appended on confirm.
    filenameSuffix: String? = null,
) {
    val c = MaterialTheme.grove
    var name by remember {
        mutableStateOf(filenameSuffix?.let { initial.removeSuffix(it) } ?: initial)
    }
    // Normalise so the callback always gets exactly one trailing suffix, whether
    // the user left it off or typed it in themselves.
    val resolved = filenameSuffix?.let { name.removeSuffix(it) + it } ?: name
    val error = if (name.isBlank()) null else validate?.invoke(resolved)
    val canConfirm = name.isNotBlank() && error == null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink) },
        text = {
            Column {
                if (contextLabel != null) {
                    Text(
                        contextLabel,
                        fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text(placeholder, fontFamily = PlexMono, color = c.ink3) },
                    suffix = filenameSuffix?.let {
                        { Text(it, fontFamily = PlexMono, color = c.ink3) }
                    },
                    textStyle = TextStyle(fontFamily = PlexMono, color = c.ink),
                    supportingText = if (error != null || helperText != null) {
                        {
                            Text(
                                error ?: helperText!!,
                                fontFamily = PlexSans, fontSize = 12.sp,
                                color = if (error != null) c.red else c.ink3,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(resolved) },
                enabled = canConfirm,
            ) { Text(confirmLabel, color = c.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) }
        },
    )
}

/**
 * "Move to folder…" destination picker (nested-folders plan §6). A bottom sheet
 * that drills the vault folder tree one level at a time: tapping a folder row
 * navigates into it, the breadcrumb jumps back out, and "Move here" targets the
 * folder currently shown. "New folder here" types a fresh sub-path that
 * [com.rrajath.grove.vault.Vault.moveNotebook] creates on the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToFolderSheet(
    displayName: String,
    currentDir: String,
    notebooks: List<NotebookItem>,
    vaultName: String,
    onConfirm: (newDir: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    var pickerDir by remember { mutableStateOf("") }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    val childFolders = remember(notebooks, pickerDir) {
        drillLevel(notebooks, pickerDir).childFolders
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                "Move $displayName",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Currently in ${currentDir.ifEmpty { vaultName }}",
                fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
            )
            Spacer(Modifier.height(10.dp))
            Breadcrumb(
                vaultName = vaultName,
                dir = pickerDir,
                onExit = { pickerDir = "" },
                onNavigate = { pickerDir = it },
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (childFolders.isEmpty()) {
                    item {
                        Text(
                            "No sub-folders here",
                            fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(childFolders, key = { it.dir }) { node ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .clickable { pickerDir = node.dir }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▪", fontFamily = PlexMono, fontSize = 15.sp, color = c.ink3)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            node.name,
                            fontFamily = PlexMono, fontWeight = FontWeight.Medium,
                            fontSize = 14.5.sp, color = c.ink, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${node.recursiveOrgCount}",
                            fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("›", fontFamily = PlexMono, fontSize = 15.sp, color = c.ink3)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (creatingFolder) {
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    singleLine = true,
                    placeholder = { Text("folder name", fontFamily = PlexMono, color = c.ink3) },
                    textStyle = TextStyle(fontFamily = PlexMono, color = c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val newDir = vaultPath(pickerDir, newFolder.trim().trim('/'))
                MoveConfirmButton(
                    label = "Create and move here",
                    enabled = newFolder.isNotBlank() && newDir != currentDir,
                    onClick = { onConfirm(newDir) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { creatingFolder = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("＋", fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "New folder here",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = c.accent,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = c.line)
            Row(
                Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface2)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        "Cancel",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, color = c.ink2,
                    )
                }
                MoveConfirmButton(
                    label = "Move to ${pickerDir.ifEmpty { vaultName }}",
                    enabled = pickerDir != currentDir && !creatingFolder,
                    onClick = { onConfirm(pickerDir) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MoveConfirmButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) c.accent else c.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = if (enabled) c.accentInk else c.ink3,
            maxLines = 1,
        )
    }
}

/**
 * Bottom inset added to every notebook list's `contentPadding` so the last row
 * can scroll clear of the floating [NotebooksDock] (≈ 60dp capsule + 6dp Capture
 * overhang + 22dp gap + breathing room). The dock's own `navigationBarsPadding()`
 * handles the system bar.
 */
private val DOCK_CLEARANCE = 96.dp

/**
 * Home-screen reachability dock — replaces the old Capture FAB. M3 Expressive's
 * floating toolbar (design variant 1e): a single `surface2` capsule
 * (`RoundedCornerShape(30.dp)`, `1dp` `line` border, 6dp inner padding, 4dp gap)
 * centred at the bottom of the screen, holding a Search icon button, a raised
 * pill-shaped "＋ Capture" button in the middle (the primary action — 72dp tall
 * via `requiredHeight`, so it overhangs the capsule ~6dp top and bottom and reads
 * as lifted), and a New notebook icon button. The capsule carries a layered drop
 * shadow so it floats above the list scrolling behind it; the Capture pill adds
 * its own accent-tinted shadow. Shown on the tree view and while drilling a
 * folder; the caller points New notebook at whichever folder is in view.
 */
@Composable
private fun NotebooksDock(
    onSearch: () -> Unit,
    onCapture: () -> Unit,
    onNewNotebook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    val capsuleShape = RoundedCornerShape(30.dp)
    val pillShape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A fixed-height capsule: the raised Capture pill overhangs it (see below)
        // and must not be clipped, so the shape is applied via background/shadows
        // (clip = false) rather than a .clip() on this Row. The border is a
        // drawBehind stroke (not Modifier.border, which paints over children) so
        // it passes behind the overhanging Capture pill.
        Row(
            Modifier
                .height(60.dp)
                .shadow(18.dp, capsuleShape, clip = false)
                .shadow(6.dp, capsuleShape, clip = false)
                .background(c.surface2, capsuleShape)
                .drawBehind {
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        color = c.line,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(30.dp.toPx() - w / 2),
                        style = Stroke(w),
                    )
                }
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockIconButton(searchIcon(), "Search", onSearch)
            // requiredHeight ignores the 48dp content slot so the pill grows to
            // 72dp and, centred, overhangs the capsule by ~6dp top and bottom.
            Row(
                Modifier
                    .requiredHeight(72.dp)
                    .shadow(8.dp, pillShape, clip = false, ambientColor = c.accent, spotColor = c.accent)
                    .background(c.accent, pillShape)
                    .clip(pillShape)
                    .clickable(onClick = onCapture)
                    .testTag("notebooks_capture")
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", fontFamily = PlexSans, fontSize = 22.sp, color = c.accentInk)
                Spacer(Modifier.width(9.dp))
                Text(
                    "Capture",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = c.accentInk,
                )
            }
            DockIconButton(notebookIcon(), "New notebook", onNewNotebook)
        }
    }
}

@Composable
private fun DockIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .testTag("dock_" + contentDescription.lowercase().replace(' ', '_')),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = c.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun IconGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontFamily = PlexMono,
            fontSize = 22.sp,
            color = MaterialTheme.grove.ink,
        )
    }
}

@Composable
internal fun IconGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.grove.ink,
            modifier = Modifier.size(22.dp),
        )
    }
}
