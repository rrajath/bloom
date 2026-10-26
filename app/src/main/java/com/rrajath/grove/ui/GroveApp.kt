package com.rrajath.grove.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.capture.ShortcutSyncer
import com.rrajath.grove.icon.AppIconManager
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaScreen
import com.rrajath.grove.ui.capture.CaptureEditorScreen
import com.rrajath.grove.ui.editor.EditNoteScreen
import com.rrajath.grove.ui.editor.EditIntroScreen
import com.rrajath.grove.ui.editor.EditPrefaceScreen
import com.rrajath.grove.ui.editor.EditRegion
import com.rrajath.grove.ui.editor.EditRegionScreen
import com.rrajath.grove.ui.editor.EditorViewModel
import com.rrajath.grove.ui.editor.DiscardBlankHeadingDialog
import com.rrajath.grove.ui.editor.EmptyHeadingAlertDialog
import com.rrajath.grove.ui.editor.UnsavedNoteDialog
import com.rrajath.grove.ui.capture.CapturePickerSheet
import com.rrajath.grove.ui.capture.TemplateEditScreen
import com.rrajath.grove.ui.components.GroveActionSnackbar
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.newbadge.LocalNewBadges
import com.rrajath.grove.ui.newbadge.NewBadges
import com.rrajath.grove.ui.nav.navEnterTransition
import com.rrajath.grove.ui.nav.navExitTransition
import com.rrajath.grove.ui.nav.navPopEnterTransition
import com.rrajath.grove.ui.nav.navPopExitTransition
import com.rrajath.grove.ui.reminders.ExactAlarmPromptDialog
import com.rrajath.grove.ui.reminders.ReminderAccessIntents
import com.rrajath.grove.ui.reminders.ReminderResolveScreen
import com.rrajath.grove.ui.screens.ConflictScreen
import com.rrajath.grove.ui.screens.GroveDrawerContent
import com.rrajath.grove.ui.screens.NotebooksScreen
import com.rrajath.grove.ui.screens.OnboardingScreen
import com.rrajath.grove.ui.screens.OutlineDisplayFlags
import com.rrajath.grove.ui.screens.OutlineScreen
import com.rrajath.grove.ui.screens.ReadFileScreen
import com.rrajath.grove.ui.screens.ReadNoteScreen
import com.rrajath.grove.ui.screens.RefileSheet
import com.rrajath.grove.ui.search.SearchScreen
import com.rrajath.grove.ui.screens.SettingsScreen
import com.rrajath.grove.ui.screens.settings.SettingsAgendaScreen
import com.rrajath.grove.ui.screens.settings.SettingsAppearanceScreen
import com.rrajath.grove.ui.screens.settings.SettingsBackupScreen
import com.rrajath.grove.ui.screens.settings.SettingsBugReportScreen
import com.rrajath.grove.ui.screens.settings.SettingsCaptureTemplatesScreen
import com.rrajath.grove.ui.screens.settings.SettingsDeveloperScreen
import com.rrajath.grove.ui.screens.settings.SettingsNotebooksScreen
import com.rrajath.grove.ui.screens.settings.SettingsNotesScreen
import com.rrajath.grove.ui.screens.settings.SettingsRemindersScreen
import com.rrajath.grove.ui.screens.settings.SettingsRoamScreen
import com.rrajath.grove.ui.screens.settings.SettingsSharingScreen
import com.rrajath.grove.ui.screens.settings.SettingsSyncScreen
import com.rrajath.grove.ui.screens.settings.SettingsTipsScreen
import com.rrajath.grove.ui.screens.settings.SettingsWhatsNewScreen
import com.rrajath.grove.ui.screens.settings.SettingsWidgetScreen
import com.rrajath.grove.ui.screens.SyncLogScreen
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.PendingEdit
import com.rrajath.grove.ui.vault.wholeFileLineLimitOverride
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.vault.Notebook
import com.rrajath.grove.vault.TestVaultHook
import com.rrajath.grove.vault.matchOpenedFileToNotebook
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun GroveApp(
    deepLinkIntent: android.content.Intent? = null,
    /** Called with the pending [deepLinkIntent] once it has been navigated, so
     *  the host Activity can drop it and it never re-fires on a later
     *  recreation. The Activity ignores the call if a newer Intent has since
     *  arrived. */
    onDeepLinkConsumed: (android.content.Intent) -> Unit = {},
    viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Wait for the first DataStore emission so theme and start destination don't flash.
    val loaded = settings ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    // Switching the enabled launcher alias closes the app's task, so defer it
    // until the app goes to the background (ON_STOP) instead of applying it live.
    val syncIcon by rememberUpdatedState(loaded.syncAppIconWithTheme)
    val iconTheme by rememberUpdatedState(loaded.theme)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val app = context.applicationContext as GroveApplication
                app.appScope.launch {
                    // Off the main thread: applyIcon does synchronous Binder IPC
                    // and ON_STOP runs on the main thread (PERFORMANCE_AUDIT #5).
                    // Runs before the shortcut re-sync, which binds shortcuts to
                    // whichever alias applyIcon enables.
                    AppIconManager.applyIcon(context, syncIcon, iconTheme)
                    // Dynamic shortcuts published while the old alias was still
                    // enabled are now stranded on it; republish against whichever
                    // alias applyIcon just switched to.
                    val templates = app.templatesRepository.templates.first()
                    ShortcutSyncer.sync(app, templates, iconTheme, syncIcon)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    GroveTheme(theme = loaded.theme) {
        // App-wide text-size baseline: scales every sp-sized text under one lever.
        // The per-mode read/edit levers nest inside this and compound on top.
        ContentFontScale(loaded.appFontSize) {
            GroveNavigation(loaded, viewModel, deepLinkIntent, onDeepLinkConsumed)
        }
    }
}

/**
 * True while this entry is the one on screen. Guards a dismiss/navigation
 * callback that can fire a second time after the entry is already popped (a fast
 * double back-press): a popped entry drops below RESUMED, so the late callback
 * no-ops instead of popping another entry off the back stack.
 */
private fun androidx.navigation.NavBackStackEntry.isResumed() =
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/** Favorites scoped to [fileName], for resolving each row's ★ by customId rather than raw line index. */
private fun favoritesFor(
    favorites: List<com.rrajath.grove.data.FavoriteNote>,
    fileName: String,
): List<com.rrajath.grove.data.FavoriteNote> = favorites.filter { it.fileName == fileName }

/**
 * A projection of [com.rrajath.grove.ui.editor.EditorUiState] carrying only what
 * the NOTE route needs to decide *whether* a pending (unsaved) edit exists —
 * deliberately without [com.rrajath.grove.ui.editor.EditorUiState.buffer], so
 * collecting it doesn't recompose the whole route on every keystroke (see C2,
 * PERFORMANCE_AUDIT 2026-09-16). The buffer itself is read separately, only
 * inside the read-mode branch that actually needs the text.
 */
private data class PendingEditKey(
    val dirty: Boolean,
    val region: EditRegion?,
    val fileName: String,
    val lineIndex: Int,
)

/**
 * The file name of an externally-opened .org file, e.g. from tapping one in a
 * file manager. `content://` URIs from other providers carry a display name
 * via [android.provider.OpenableColumns.DISPLAY_NAME] rather than a usable
 * path segment (the last segment is often an opaque document id); `file://`
 * URIs have no content resolver row, so the path segment is all there is.
 */
private fun externalOrgFileName(context: android.content.Context, uri: android.net.Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

/**
 * Human-readable form of the persisted SAF tree URI for the drawer header,
 * e.g. "primary:Documents/org" → "~/Documents/org".
 */
private fun vaultDisplayPath(treeUri: String?): String {
    if (treeUri == null) return "no folder selected"
    val docId = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(treeUri.toUri())
    }.getOrNull() ?: return treeUri
    val path = docId.substringAfter(':', docId).ifEmpty { "(storage root)" }
    return if (docId.startsWith("primary:")) "~/$path" else path
}

@Composable
private fun GroveNavigation(
    settings: GroveSettings,
    viewModel: AppViewModel,
    deepLinkIntent: android.content.Intent? = null,
    onDeepLinkConsumed: (android.content.Intent) -> Unit = {},
) {
    val navController = rememberNavController()
    val activity = androidx.activity.compose.LocalActivity.current
    // Set when a bloom://capture or bloom://note deep link (widget, notification,
    // shortcut) arrives with no real prior navigation on the back stack -- see
    // the "hasNoRealBackStack" check below. CAPTURE's onDismiss, CAPTURE_TEMPLATE's
    // onClose/onSaved, and NOTE's leaveNote read this to close by finishing the
    // Activity (back to wherever the user was before Grove) rather than by
    // popping the nav back stack onto Notebooks, which the user never chose to
    // visit. CAPTURE_TEMPLATE and NOTE clear it on disposal (however they're
    // left) so it can never leak into a later, ordinary in-app capture/note; the
    // "Manage templates" escape hatch off the picker clears it explicitly
    // since that's a deliberate trip further into the app, not a close.
    var closeActivityOnExit by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
            as com.rrajath.grove.GroveApplication

    // Shared-into-Grove content is appended to the configured file directly
    // (PRD §10); observed so it works even when the app was already running.
    val pendingShare by app.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) viewModel.consumeSharedContent()
    }

    // Launcher shortcuts, the capture widget, and the persistent capture
    // notification all launch bloom:// VIEW intents; NavHost doesn't consume
    // the hosting Activity's intent on its own, so route it through here on
    // both cold start and a warm-start onNewIntent (see MainActivity).
    val consumeDeepLink by rememberUpdatedState(onDeepLinkConsumed)
    LaunchedEffect(deepLinkIntent) {
        val intent = deepLinkIntent ?: return@LaunchedEffect
        val uri = intent.data
        try {
            val action = intent.action
            if (uri == null) return@LaunchedEffect
            if (action == android.content.Intent.ACTION_VIEW && DeepLinks.isAppScheme(uri.scheme)) {
                // deepLinkIsColdStart (true process cold start) turned out not to
                // track what actually matters: Grove's singleTask launchMode means
                // a widget/notification tap almost always redelivers onto an
                // *already-running* task via onNewIntent, so that flag was false in
                // the overwhelmingly common case and this never fired. What
                // determines whether "back" out of the target should exit the app
                // (rather than reveal whatever's underneath) is whether the
                // back stack, at the moment this deep link arrives, holds any
                // *real* prior navigation -- i.e. more than just the graph's own
                // mandatory start-destination entry. A size of 1 covers both a
                // genuine cold start and a warm start where the app was merely
                // sitting idle on Notebooks; either way there's nothing the user
                // actually visited to return to, so leaving should exit to
                // wherever the tap came from instead of surfacing Notebooks.
                val hasNoRealBackStack = navController.previousBackStackEntry == null
                if (hasNoRealBackStack && (uri.host == "capture" || uri.host == "note")) {
                    closeActivityOnExit = true
                }
                // A bloom://capture/{templateId} arrival is a launcher shortcut
                // tap; tell the system so it can rank that shortcut by usage.
                if (uri.host == "capture") {
                    uri.pathSegments.firstOrNull()?.let { ShortcutSyncer.reportShortcutUsed(app, it) }
                }
                navController.handleDeepLink(intent)
                return@LaunchedEffect
            }
            // A .org file opened from outside Grove (file manager, "Open with",
            // or Grove set as its default handler; see the file-open intent-filter
            // on MainActivity in the manifest). This isn't one of the bloom://
            // NavDeepLinks above -- there's no route pattern an arbitrary
            // content:// / file:// URI could match -- so it's resolved by hand:
            // match the tapped file's name against a notebook already indexed in
            // the vault and open its outline.
            if ((action == android.content.Intent.ACTION_VIEW || action == android.content.Intent.ACTION_EDIT) &&
                (uri.scheme == "content" || uri.scheme == "file")
            ) {
                val requestedName = externalOrgFileName(app, uri)
                val match = requestedName?.let { name ->
                    // Room index, not vault.notebooks(): this only needs file
                    // names, and the index is already populated without parsing
                    // every notebook (PERFORMANCE_AUDIT_2026-09-16 F7).
                    val known = app.database.indexDao().notebooks()
                        .map { Notebook(it.fileName, it.noteCount, it.lastModified) }
                    matchOpenedFileToNotebook(name, known)
                }
                if (match != null) {
                    navController.navigate(Routes.outline(match.fileName, autoOpen = true)) { launchSingleTop = true }
                } else {
                    android.widget.Toast.makeText(
                        app,
                        if (requestedName != null) {
                            "\"$requestedName\" isn't in your ${app.getString(R.string.app_name)} vault folder."
                        } else {
                            "Couldn't open that file in ${app.getString(R.string.app_name)}."
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } finally {
            // Navigated (or found nothing to do): the Intent is spent. Clearing
            // it here is what stops a cancelled capture from returning after a
            // process-death recreation or an uncovered configuration change.
            // Passes the exact Intent handled so a newer one that arrived
            // mid-resolve (cancelling this effect) is left untouched.
            consumeDeepLink(intent)
        }
    }

    fun closeDrawerAnd(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    val newBadgeState by viewModel.newBadgeState.collectAsStateWithLifecycle()
    // Memoized on newBadgeState alone: an unrelated settings write (see C1,
    // PERFORMANCE_AUDIT 2026-09-16) must not hand every NewDot/NewDotBadge
    // consumer a fresh NewBadges instance through LocalNewBadges.
    val onFeaturesSeen by rememberUpdatedState(viewModel::markNewFeaturesSeen)
    val newBadges = remember(newBadgeState) { NewBadges(newBadgeState) { ids -> onFeaturesSeen(ids) } }

    val whatsNewHistory by viewModel.whatsNewHistory.collectAsStateWithLifecycle()
    // A dot on Settings › About › What's New while the running build carries changes the user
    // hasn't opened the screen for; opening it stamps the build seen (also killing the launch modal).
    val whatsNewHasUnseen = settings.lastSeenChangelogBuild != com.rrajath.grove.BuildConfig.VERSION_CODE
    LaunchedEffect(Unit) { viewModel.loadWhatsNewHistory() }
    // Resolve today's daily note while the drawer is opening, so a tap on its Dailies
    // row finds the note already parsed (DailyNoteLookup) and renders at once.
    LaunchedEffect(drawerState.targetValue, settings.roamFeaturesEnabled) {
        if (drawerState.targetValue == DrawerValue.Open && settings.roamFeaturesEnabled) {
            app.dailyNoteLookup.warm()
        }
    }

    CompositionLocalProvider(LocalNewBadges provides newBadges) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute == Routes.NOTEBOOKS,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.grove.surface,
                modifier = Modifier.background(MaterialTheme.grove.surface),
            ) {
                GroveDrawerContent(
                    currentRoute = currentRoute,
                    vaultPath = vaultDisplayPath(settings.vaultTreeUri),
                    savedSearches = viewModel.savedSearches.collectAsStateWithLifecycle().value,
                    favorites = favorites,
                    logoFollowsTheme = settings.syncAppIconWithTheme,
                    roamFeaturesEnabled = settings.roamFeaturesEnabled,
                    todayShortLabel = remember {
                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d"))
                    },
                    onNavigate = { route -> closeDrawerAnd { navController.navigate(route) } },
                    onDeleteSavedSearch = { viewModel.deleteSavedSearch(it.id) },
                    onRenameSavedSearch = { id, name -> viewModel.renameSavedSearch(id, name) },
                    onMoveSavedSearch = { id, delta -> viewModel.moveSavedSearch(id, delta) },
                    onDeleteFavorite = { viewModel.removeFavorite(it.fileName, it.lineIndex, it.customId) },
                    onRenameFavorite = { fav, title -> viewModel.renameFavorite(fav.fileName, fav.lineIndex, title, fav.customId) },
                    onMoveFavorite = { fav, delta -> viewModel.moveFavorite(fav.fileName, fav.lineIndex, delta, fav.customId) },
                )
            }
        },
    ) {
    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (settings.onboardingDone || TestVaultHook.skipOnboarding) {
                Routes.NOTEBOOKS
            } else {
                Routes.ONBOARDING
            },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.grove.bg),
            // Predictive-back fade-through (see ui/nav/NavTransitions.kt). NavHost
            // seeks these with the system back gesture's progress, so the previous
            // screen fades in as far as the user has dragged and rewinds on release.
            enterTransition = navEnterTransition,
            exitTransition = navExitTransition,
            popEnterTransition = navPopEnterTransition,
            popExitTransition = navPopExitTransition,
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        viewModel.completeOnboarding()
                        navController.navigate(Routes.NOTEBOOKS) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                    onFolderPicked = viewModel::setVaultTreeUri,
                )
            }
            composable(Routes.NOTEBOOKS) {
                NotebooksScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSearch = { navController.navigate(Routes.search()) },
                    onOpenCapture = { navController.navigate(Routes.CAPTURE) },
                    // autoOpen: with Roam's "open small files as one note" on, a
                    // file under the line limit lands on the whole-file view instead.
                    onOpenNotebook = { id -> navController.navigate(Routes.outline(id, autoOpen = true)) },
                    onOpenConflict = { id -> navController.navigate(Routes.conflict(id)) },
                )
            }
            composable(Routes.OUTLINE) { entry ->
                val notebookId = entry.arguments?.getString("notebookId").orEmpty()
                val narrowTo = entry.arguments?.getString("narrowTo")?.toIntOrNull()
                val autoOpen = entry.arguments?.getString("auto") == "true"
                val reveal = entry.arguments?.getString("reveal")?.toIntOrNull()
                val wholeFileLineLimit by wholeFileLineLimitOverride.collectAsState()
                OutlineScreen(
                    notebookId = notebookId,
                    narrowLineIndex = narrowTo,
                    revealLineIndex = reveal,
                    onBack = { navController.popBackStack() },
                    onViewFile = { fileName -> navController.navigate(Routes.file(fileName)) },
                    wholeFileLineLimit = wholeFileLineLimit,
                    autoOpenWholeFile = autoOpen && settings.roamFeaturesEnabled && settings.roamOpenWholeFile,
                    // Replace this outline entry, so Back from the file view returns
                    // to wherever the notebook was opened from, not to an outline
                    // the user never saw.
                    onAutoOpenWholeFile = { fileName ->
                        navController.navigate(Routes.file(fileName, settings.defaultNoteOpenMode.storageKey)) {
                            popUpTo(Routes.OUTLINE) { inclusive = true }
                        }
                    },
                    onWiden = {
                        navController.navigate(Routes.outline(notebookId)) {
                            popUpTo(Routes.OUTLINE) { inclusive = true }
                        }
                    },
                    onOpenNote = { ref ->
                        // Always open in the mode configured in Settings.
                        navController.navigate(Routes.note(ref.encode(), settings.defaultNoteOpenMode.storageKey))
                    },
                    // A freshly created note opens straight in edit mode (blank heading).
                    onCreateNote = { ref -> navController.navigate(Routes.note(ref.encode(), "edit", isNew = true)) },
                    onSearchInNotebook = { navController.navigate(Routes.search(notebook = notebookId)) },
                    // The outline's ★ swipe action: OutlineScreen decides add vs. remove
                    // itself (it already resolves each row's favorite by customId to draw
                    // the star correctly) and, for adds, resolves a stable id first via
                    // viewModel.ensureStableId before calling onFavorite.
                    onFavorite = { fileName, lineIndex, title, customId ->
                        viewModel.addFavorite(fileName, lineIndex, title, customId)
                    },
                    onUnfavorite = { fileName, lineIndex, customId ->
                        viewModel.removeFavorite(fileName, lineIndex, customId)
                    },
                    favorites = remember(favorites, notebookId) { favoritesFor(favorites, notebookId) },
                    displayFlags = OutlineDisplayFlags(
                        tags = settings.showTagsInOutline,
                        timestamps = settings.showTimestampsInOutline,
                        keywords = settings.showKeywordsInOutline,
                    ),
                    onToggleDisplay = viewModel::setOutlineToggle,
                    showPreface = settings.showPreface,
                    showPropertyDrawers = settings.showPropertyDrawers,
                    showBacklinks = settings.roamFeaturesEnabled && settings.roamShowBacklinks,
                    onOpenPreface = { fileName -> navController.navigate(Routes.preface(fileName)) },
                    onOpenFileProperties = { fileName ->
                        navController.navigate(Routes.drawer(fileName, "fileProps"))
                    },
                )
            }
            composable(Routes.PREFACE) { entry ->
                val fileName = entry.arguments?.getString("fileName").orEmpty()
                EditPrefaceScreen(
                    fileName = fileName,
                    onBack = { navController.popBackStack() },
                    editModeFontSize = settings.editModeFontSize,
                )
            }
            composable(Routes.DRAWER) { entry ->
                val fileName = entry.arguments?.getString("fileName").orEmpty()
                val noteId = entry.arguments?.getString("noteId")
                val region = when (entry.arguments?.getString("kind")) {
                    "headingProps" -> EditRegion.HEADING_PROPERTIES
                    "headingLog" -> EditRegion.HEADING_LOGBOOK
                    else -> EditRegion.FILE_PROPERTIES
                }
                EditRegionScreen(
                    fileName = fileName,
                    region = region,
                    noteId = noteId,
                    onBack = { navController.popBackStack() },
                    editModeFontSize = settings.editModeFontSize,
                )
            }
            composable(Routes.BLOCK) { entry ->
                EditRegionScreen(
                    fileName = entry.arguments?.getString("fileName").orEmpty(),
                    region = EditRegion.BLOCK,
                    noteId = null,
                    blockLine = entry.arguments?.getString("line")?.toIntOrNull() ?: -1,
                    onBack = { navController.popBackStack() },
                    editModeFontSize = settings.editModeFontSize,
                )
            }
            composable(Routes.FILE) { entry ->
                val fileName = entry.arguments?.getString("fileName").orEmpty()
                // Local, not a nav argument, for the same reason as NOTE: the
                // Read/Edit toggle must not re-navigate.
                var mode by rememberSaveable(fileName) {
                    mutableStateOf(entry.arguments?.getString("mode") ?: "read")
                }
                // Hoisted, as on NOTE: the Read/Edit toggle never saves, so the
                // editor's unsaved buffer must outlive Edit mode for Read to render it.
                val editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory)
                // Only this key at route scope, so typing doesn't recompose the route.
                val pendingKeyFlow = remember(editorViewModel) {
                    editorViewModel.state
                        .map { PendingEditKey(it.dirty, it.region, it.fileName, it.lineIndex) }
                        .distinctUntilChanged()
                }
                val pendingKey by pendingKeyFlow.collectAsStateWithLifecycle(
                    initialValue = PendingEditKey(dirty = false, region = null, fileName = "", lineIndex = -1),
                )
                val hasPendingEdit = pendingKey.dirty && pendingKey.region == EditRegion.WHOLE_FILE &&
                    pendingKey.fileName == fileName
                var confirmLeavePending by remember(fileName) { mutableStateOf(false) }
                val leaveRead: () -> Unit = {
                    if (hasPendingEdit) confirmLeavePending = true else navController.popBackStack()
                }
                androidx.activity.compose.BackHandler(enabled = mode == "read" && hasPendingEdit) { leaveRead() }
                if (confirmLeavePending) {
                    UnsavedNoteDialog(
                        message = "This file has unsaved changes.",
                        onSave = {
                            confirmLeavePending = false
                            editorViewModel.save { navController.popBackStack() }
                        },
                        onDiscard = {
                            confirmLeavePending = false
                            navController.popBackStack()
                        },
                        onDismiss = { confirmLeavePending = false },
                    )
                }
                if (mode == "edit") {
                    // The whole buffer in one editor. Back returns to the file's
                    // Read view (mirroring the intro editor), never straight out.
                    EditRegionScreen(
                        fileName = fileName,
                        region = EditRegion.WHOLE_FILE,
                        noteId = null,
                        onBack = { mode = "read" },
                        onSwitchToRead = { mode = "read" },
                        editModeFontSize = settings.editModeFontSize,
                        showBacklinks = settings.roamFeaturesEnabled && settings.roamShowBacklinks,
                        roamSuggestionsEnabled = settings.roamSuggestionsActive,
                        onOpenNote = { target -> navController.navigate(Routes.note(target.encode())) },
                        viewModel = editorViewModel,
                    )
                } else {
                    // The buffer itself only here, where Read renders it.
                    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
                    val pendingEdit = if (hasPendingEdit) {
                        PendingEdit(editorState.fileName, 0, editorState.buffer, wholeFile = true)
                    } else {
                        null
                    }
                    ReadFileScreen(
                        fileName = fileName,
                        onBack = leaveRead,
                        onEdit = { mode = "edit" },
                        onOpenNote = { target -> navController.navigate(Routes.note(target.encode())) },
                        // Explicit outline (no autoOpen): the filename tap must land
                        // on the outline even when this file would auto-open whole.
                        onOpenOutline = { target -> navController.navigate(Routes.outline(target)) },
                        onOpenDrawer = { kind, drawerRef ->
                            navController.navigate(Routes.drawer(drawerRef.fileName, kind, drawerRef.encode()))
                        },
                        onOpenBlock = { target, line -> navController.navigate(Routes.block(target, line)) },
                        onOpenPreface = { target -> navController.navigate(Routes.preface(target)) },
                        onOpenFileProperties = { target ->
                            navController.navigate(Routes.drawer(target, "fileProps"))
                        },
                        showPreface = settings.showPreface,
                        showPropertyDrawers = settings.showPropertyDrawers,
                        showBacklinks = settings.roamFeaturesEnabled && settings.roamShowBacklinks,
                        readModeFontSize = settings.readModeFontSize,
                        favorites = remember(favorites, fileName) { favoritesFor(favorites, fileName) },
                        pendingEdit = pendingEdit,
                        onPendingBufferChanged = editorViewModel::onBufferChangedExternally,
                        onPendingPersisted = editorViewModel::onBufferPersistedElsewhere,
                        onSavePending = { editorViewModel.save() },
                    )
                }
            }
            // Date-to-date navigation (swipe / nav pill / Today / date picker) happens
            // inside DailyNoteScreen, not by re-navigating this route, so the route's
            // {date} is only the day it opens on.
            composable(Routes.DAILIES) { entry ->
                val dateArg = entry.arguments?.getString("date")
                val date = dateArg?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    ?: java.time.LocalDate.now()
                com.rrajath.grove.ui.dailies.DailyNoteScreen(
                    initialDate = date,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { target -> navController.navigate(Routes.note(target.encode())) },
                    onOpenOutline = { target -> navController.navigate(Routes.outline(target)) },
                    showBacklinks = settings.roamFeaturesEnabled && settings.roamShowBacklinks,
                    roamSuggestionsEnabled = settings.roamSuggestionsActive,
                    showPreface = settings.showPreface,
                    showPropertyDrawers = settings.showPropertyDrawers,
                    readModeFontSize = settings.readModeFontSize,
                    editModeFontSize = settings.editModeFontSize,
                )
            }
            composable(
                Routes.NOTE,
                deepLinks = DeepLinks.navDeepLinks(Routes.NOTE),
            ) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                val isNew = entry.arguments?.getString("isNew") == "true"
                val ref = NoteRef.decode(noteId)
                if (ref == null) {
                    navController.popBackStack()
                } else {
                    // A bloom://note/{id} deep link opened straight from a widget
                    // tap or notification, with no real prior navigation on the
                    // back stack (see "hasNoRealBackStack" above), puts this
                    // Activity's back stack under this entry to NOTEBOOKS --
                    // Navigation's deep-link synthesis, not anywhere the user
                    // actually visited -- so leaving this note by popping would
                    // surface the Notebooks list instead of returning to the
                    // widget/home screen the task was opened from. Mirrors
                    // closeActivityOnExit's use for CAPTURE/CAPTURE_TEMPLATE above.
                    // Cleared (like TEMPLATE's "Manage templates" escape hatch)
                    // the moment the user deliberately navigates further into the
                    // app from here, so a later back only exits the app for this
                    // one direct landing, not for anything pushed on top of it.
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        onDispose { closeActivityOnExit = false }
                    }
                    val leaveNote: () -> Unit = {
                        if (closeActivityOnExit) activity?.finish() else navController.popBackStack()
                    }
                    // Local, not a nav argument: switching read <-> edit for this
                    // same note must not re-navigate, or it re-triggers the full-screen
                    // enter/exit transition meant for moving between distinct screens.
                    var mode by rememberSaveable(noteId) {
                        mutableStateOf(entry.arguments?.getString("mode") ?: "read")
                    }
                    // Line of the subheading double-tapped in read mode, if any; carried
                    // alongside `mode` (not a nav arg) so the mode toggle keeps not
                    // re-navigating/re-triggering the enter/exit transition.
                    var editTargetLine by rememberSaveable(noteId) { mutableStateOf<Int?>(null) }
                    // Hoisted so read mode can see what the editor is holding: the
                    // Read/Edit toggle writes nothing, so an unsaved buffer has to
                    // survive the switch and be rendered by read mode (PendingEdit).
                    // Same ViewModelStore (this back-stack entry) as the one
                    // EditNoteScreen would create for itself.
                    val editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory)
                    // Only this key is collected at route scope (see PendingEditKey):
                    // typing changes editorState.buffer on every keystroke, but not
                    // dirty/region/fileName/lineIndex, so this route no longer
                    // recomposes while the user types in edit mode.
                    val pendingEditKeyFlow = remember(editorViewModel) {
                        editorViewModel.state
                            .map { PendingEditKey(it.dirty, it.region, it.fileName, it.lineIndex) }
                            .distinctUntilChanged()
                    }
                    val pendingKey by pendingEditKeyFlow.collectAsStateWithLifecycle(
                        initialValue = PendingEditKey(dirty = false, region = null, fileName = "", lineIndex = -1),
                    )
                    val hasPendingEdit = pendingKey.dirty && pendingKey.region == null &&
                        pendingKey.fileName == ref.fileName && !ref.isIntro
                    // Leaving the note entirely (not the Read/Edit toggle, which never
                    // validates or writes) mirrors EditNoteScreen's own leave(): a
                    // just-created, never-titled note offers to discard it outright;
                    // any other unsaved buffer asks to save or discard; saving with a
                    // still-blank heading is blocked the same way trySave() blocks it.
                    var confirmLeavePending by remember(noteId) { mutableStateOf(false) }
                    var confirmDiscardBlankHeadingRead by remember(noteId) { mutableStateOf(false) }
                    var showEmptyHeadingAlertRead by remember(noteId) { mutableStateOf(false) }
                    val leaveRead: () -> Unit = {
                        when {
                            isNew && editorViewModel.isCurrentHeadingBlank() -> confirmDiscardBlankHeadingRead = true
                            hasPendingEdit -> confirmLeavePending = true
                            else -> leaveNote()
                        }
                    }
                    androidx.activity.compose.BackHandler(
                        // A brand-new, still-blank note has nothing dirty (nothing was
                        // ever typed), so hasPendingEdit alone would miss it and let the
                        // system back gesture skip leaveRead()'s blank-heading check.
                        // Also enabled whenever closeActivityOnExit is set: the plain
                        // case (nothing pending) would otherwise fall through to
                        // NavController's default system-back handling, which pops
                        // straight to NOTEBOOKS instead of routing through leaveNote().
                        enabled = closeActivityOnExit || (mode == "read" &&
                            (hasPendingEdit || (isNew && editorViewModel.isCurrentHeadingBlank()))),
                    ) { leaveRead() }
                    if (confirmLeavePending) {
                        UnsavedNoteDialog(
                            onSave = {
                                confirmLeavePending = false
                                if (editorViewModel.isCurrentHeadingBlank()) {
                                    showEmptyHeadingAlertRead = true
                                } else {
                                    editorViewModel.save { leaveNote() }
                                }
                            },
                            onDiscard = {
                                confirmLeavePending = false
                                leaveNote()
                            },
                            onDismiss = { confirmLeavePending = false },
                        )
                    }
                    if (confirmDiscardBlankHeadingRead) {
                        DiscardBlankHeadingDialog(
                            onDiscard = {
                                confirmDiscardBlankHeadingRead = false
                                editorViewModel.deleteSubtree(onDeleted = { leaveNote() })
                            },
                            // Send the user into edit mode to actually fix the heading,
                            // rather than strand them on a read view with nothing to show.
                            onKeepEditing = { confirmDiscardBlankHeadingRead = false; mode = "edit" },
                        )
                    }
                    if (showEmptyHeadingAlertRead) {
                        EmptyHeadingAlertDialog(onDismiss = { showEmptyHeadingAlertRead = false })
                    }
                    if (mode == "edit" && ref.isIntro) {
                        // The intro has no heading to edit as a subtree: its editor is
                        // scoped to just that content, since the preface and the file's
                        // property drawer have editors of their own. Back returns to the
                        // intro read view.
                        EditIntroScreen(
                            fileName = ref.fileName,
                            onBack = { mode = "read" },
                            editModeFontSize = settings.editModeFontSize,
                        )
                    } else if (mode == "edit") {
                        EditNoteScreen(
                            noteRef = ref,
                            isNewNote = isNew,
                            initialCursorLine = editTargetLine,
                            editModeFontSize = settings.editModeFontSize,
                            newNoteCursor = settings.newNoteCursor,
                            roamSuggestionsEnabled = settings.roamSuggestionsActive,
                            onBack = leaveNote,
                            onSwitchToRead = { editTargetLine = null; mode = "read" },
                            viewModel = editorViewModel,
                        )
                    } else {
                        // The full buffer is only ever needed here, to splice the
                        // unsaved edit into the read-mode render — collected here
                        // rather than at route scope so typing in edit mode (where
                        // this branch isn't composed) can't trigger it.
                        val editorState by editorViewModel.state.collectAsStateWithLifecycle()
                        val pendingEdit = if (hasPendingEdit) {
                            PendingEdit(editorState.fileName, editorState.lineIndex, editorState.buffer)
                        } else {
                            null
                        }
                        ReadNoteScreen(
                            noteRef = ref,
                            onBack = leaveRead,
                            pendingEdit = pendingEdit,
                            onPendingBufferChanged = editorViewModel::onBufferChangedExternally,
                            onPendingPersisted = editorViewModel::onBufferPersistedElsewhere,
                            onSavePending = {
                                if (editorViewModel.isCurrentHeadingBlank()) {
                                    showEmptyHeadingAlertRead = true
                                } else {
                                    editorViewModel.save()
                                }
                            },
                            onOpenNote = { target -> navController.navigate(Routes.note(target.encode())) },
                            onOpenOutline = { fileName -> navController.navigate(Routes.outline(fileName, autoOpen = true)) },
                            onEdit = { targetLine -> editTargetLine = targetLine; mode = "edit" },
                            // null (file breadcrumb) opens the full outline; a heading's
                            // line index narrows the outline to that heading's subtree.
                            onOpenBreadcrumb = { targetLine ->
                                navController.navigate(Routes.outline(ref.fileName, targetLine))
                            },
                            onOpenDrawer = { kind, drawerRef ->
                                navController.navigate(Routes.drawer(drawerRef.fileName, kind, drawerRef.encode()))
                            },
                            onOpenBlock = { fileName, line ->
                                navController.navigate(Routes.block(fileName, line))
                            },
                            showPropertyDrawers = settings.showPropertyDrawers,
                            readModeFontSize = settings.readModeFontSize,
                            favorites = remember(favorites, ref.fileName) { favoritesFor(favorites, ref.fileName) },
                            // The intro just got a blank heading (a metadata action
                            // needed one); re-open the file at that heading so it
                            // continues as an ordinary note, replacing this entry.
                            onPromotedToHeading = { line ->
                                navController.navigate(
                                    Routes.note(NoteRef(ref.fileName, line).encode(), mode = "read")
                                ) {
                                    popUpTo(entry.destination.route ?: Routes.NOTE) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
            composable(
                Routes.REMINDER,
                deepLinks = DeepLinks.navDeepLinks(Routes.REMINDER),
            ) { entry ->
                ReminderResolveScreen(
                    fileName = entry.arguments?.getString("fileName").orEmpty(),
                    headingPath = entry.arguments?.getString("headingPath").orEmpty(),
                    level = entry.arguments?.getString("level")?.toIntOrNull() ?: 1,
                    onResolved = { ref ->
                        navController.navigate(
                            Routes.note(ref.encode(), mode = "read")
                        ) { popUpTo(Routes.REMINDER) { inclusive = true } }
                    },
                    onFailed = {
                        navController.navigate(Routes.NOTEBOOKS) {
                            popUpTo(Routes.REMINDER) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Routes.CAPTURE,
                deepLinks = DeepLinks.navDeepLinks(Routes.CAPTURE),
            ) { entry ->
                CapturePickerSheet(
                    // A quick double back-press used to pop twice: once from the
                    // sheet's own onDismissRequest and once from the system back
                    // dispatcher. On the widget's shallow deep-link back stack
                    // that popped past the start destination and left the NavHost
                    // empty (a blank white screen). Ignore the second callback:
                    // the entry is no longer RESUMED once it has been popped.
                    onDismiss = {
                        if (entry.isResumed()) {
                            if (closeActivityOnExit) activity?.finish() else navController.popBackStack()
                        }
                    },
                    onPickTemplate = { template ->
                        navController.navigate(Routes.capture(template.id)) {
                            popUpTo(Routes.CAPTURE) { inclusive = true }
                        }
                    },
                    onManage = {
                        // Deliberately going deeper into the app rather than
                        // closing out of the capture flow -- this must not
                        // finish the Activity once Settings is reached.
                        closeActivityOnExit = false
                        navController.navigate(Routes.SETTINGS_CAPTURE_TEMPLATES) {
                            popUpTo(Routes.CAPTURE) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Routes.CAPTURE_TEMPLATE,
                // Launcher shortcuts (and any caller) can jump straight into a
                // specific template's editor: bloom://capture/builtin-journal etc.
                deepLinks = DeepLinks.navDeepLinks(Routes.CAPTURE_TEMPLATE),
            ) { entry ->
                // Any way of leaving this screen consumes the flag, so it never
                // survives to affect an unrelated, later capture.
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { closeActivityOnExit = false }
                }
                val closeCapture: () -> Unit = {
                    if (closeActivityOnExit) activity?.finish() else navController.popBackStack()
                }
                CaptureEditorScreen(
                    templateId = entry.arguments?.getString("templateId").orEmpty(),
                    onClose = closeCapture,
                    onSaved = { savedRoamFilePath ->
                        closeCapture()
                        if (savedRoamFilePath != null) viewModel.showCaptureSavedSnack(savedRoamFilePath)
                    },
                    editModeFontSize = settings.editModeFontSize,
                    roamSuggestionsEnabled = settings.roamSuggestionsActive,
                )
            }
            composable(Routes.TEMPLATE_EDIT) { entry ->
                TemplateEditScreen(
                    templateId = entry.arguments?.getString("templateId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    roamFeaturesEnabled = settings.roamFeaturesEnabled,
                )
            }
            composable(Routes.SYNC_LOG) {
                SyncLogScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SEARCH,
                deepLinks = DeepLinks.navDeepLinks(Routes.SEARCH),
            ) { entry ->
                SearchScreen(
                    initialQuery = entry.arguments?.getString("q"),
                    initialNotebook = entry.arguments?.getString("notebook"),
                    onBack = { navController.popBackStack() },
                    onOpenNote = { ref -> navController.navigate(Routes.note(ref.encode())) },
                    onOpenOutline = { fileName -> navController.navigate(Routes.outline(fileName, autoOpen = true)) },
                    onShowInNotebook = { ref -> navController.navigate(Routes.outline(ref.fileName, reveal = ref.lineIndex)) },
                )
            }
            composable(
                Routes.AGENDA,
                deepLinks = DeepLinks.navDeepLinks(Routes.AGENDA),
            ) {
                AgendaScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { ref -> navController.navigate(Routes.note(ref.encode())) },
                    onShowInNotebook = { ref -> navController.navigate(Routes.outline(ref.fileName, reveal = ref.lineIndex)) },
                )
            }
            composable(Routes.CONFLICT) { entry ->
                ConflictScreen(
                    notebookId = entry.arguments?.getString("notebookId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                    onOpenNotebooks = { navController.navigate(Routes.SETTINGS_NOTEBOOKS) },
                    onOpenCaptureTemplates = { navController.navigate(Routes.SETTINGS_CAPTURE_TEMPLATES) },
                    onOpenSync = { navController.navigate(Routes.SETTINGS_SYNC) },
                    onOpenNotes = { navController.navigate(Routes.SETTINGS_NOTES) },
                    onOpenAgenda = { navController.navigate(Routes.SETTINGS_AGENDA) },
                    onOpenWidget = { navController.navigate(Routes.SETTINGS_WIDGET) },
                    onOpenReminders = { navController.navigate(Routes.SETTINGS_REMINDERS) },
                    onOpenRoam = { navController.navigate(Routes.SETTINGS_ROAM) },
                    onOpenSharing = { navController.navigate(Routes.SETTINGS_SHARING) },
                    onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                    onOpenBugReport = { navController.navigate(Routes.SETTINGS_BUG_REPORT) },
                    onOpenTips = { navController.navigate(Routes.SETTINGS_TIPS) },
                    onOpenWhatsNew = { navController.navigate(Routes.SETTINGS_WHATS_NEW) },
                    onOpenDeveloper = { navController.navigate(Routes.SETTINGS_DEVELOPER) },
                    whatsNewHasUnseen = whatsNewHasUnseen,
                )
            }
            composable(Routes.SETTINGS_APPEARANCE) {
                SettingsAppearanceScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetTheme = viewModel::setTheme,
                    onSetSyncAppIconWithTheme = viewModel::setSyncAppIconWithTheme,
                    onSetAppFontSize = viewModel::setAppFontSize,
                )
            }
            composable(Routes.SETTINGS_NOTEBOOKS) {
                SettingsNotebooksScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetShowNotebookFileIcons = viewModel::setShowNotebookFileIcons,
                    onSetFlattenNotebookFolders = viewModel::setFlattenNotebookFolders,
                    onSetNotebookDisplayNameMode = viewModel::setNotebookDisplayNameMode,
                    onSetNotebookSortKey = viewModel::setNotebookSortKey,
                    onSetNotebookSortAscending = viewModel::setNotebookSortAscending,
                    onSetIgnoreList = viewModel::setIgnoreList,
                )
            }
            composable(Routes.SETTINGS_CAPTURE_TEMPLATES) {
                SettingsCaptureTemplatesScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onEditTemplate = { id ->
                        navController.navigate(Routes.templateEdit(id ?: Routes.NEW_TEMPLATE_ID))
                    },
                    onSetCaptureNotification = viewModel::setCaptureNotification,
                )
            }
            composable(Routes.SETTINGS_SYNC) {
                SettingsSyncScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetSyncMode = viewModel::setSyncMode,
                    onSetPeriodicMinutes = viewModel::setPeriodicSyncMinutes,
                    onOpenSyncLog = { navController.navigate(Routes.SYNC_LOG) },
                    onSetVaultUri = viewModel::setVaultTreeUri,
                )
            }
            composable(Routes.SETTINGS_NOTES) {
                SettingsNotesScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetTodoKeywords = viewModel::setTodoKeywords,
                    onSetDefaultPriority = viewModel::setDefaultPriority,
                    onSetNoteOpenMode = viewModel::setDefaultNoteOpenMode,
                    onSetReadModeFontSize = viewModel::setReadModeFontSize,
                    onSetEditModeFontSize = viewModel::setEditModeFontSize,
                    onSetShowPreface = viewModel::setShowPreface,
                    onSetShowPropertyDrawers = viewModel::setShowPropertyDrawers,
                    onSetAddId = viewModel::setAddIdToNewNotes,
                    onSetAddCreated = viewModel::setAddCreatedToNewNotes,
                    onSetNewNoteCursor = viewModel::setNewNoteCursor,
                    onSetAutoSaveNotes = viewModel::setAutoSaveNotes,
                    onSetAutoArchiveDoneItems = viewModel::setAutoArchiveDoneItems,
                    onOpenArchiveLocationPicker = viewModel::startArchiveLocationPick,
                )
                val locationPicker by viewModel.locationPicker.collectAsStateWithLifecycle()
                locationPicker?.let { picker ->
                    RefileSheet(
                        state = picker,
                        currentFileName = null,
                        currentDoc = null,
                        onPickNotebook = viewModel::locationPickNotebook,
                        onDrillInto = viewModel::locationDrillInto,
                        onBack = viewModel::locationBack,
                        onCancel = viewModel::locationCancel,
                        onConfirm = viewModel::locationConfirm,
                        onArchive = {},
                        onPickLastUsed = {},
                        onSelectSearchResult = viewModel::locationSelectSearchResult,
                        headerTitle = "Set archive location",
                        confirmLabel = "Set as archive location",
                    )
                }
            }
            composable(Routes.SETTINGS_AGENDA) {
                SettingsAgendaScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetAgendaSwipeLeftAction = viewModel::setAgendaSwipeLeftAction,
                    onSetAgendaSwipeRightAction = viewModel::setAgendaSwipeRightAction,
                )
            }
            composable(Routes.SETTINGS_WIDGET) {
                SettingsWidgetScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetAgendaWidgetTransparency = viewModel::setAgendaWidgetTransparency,
                    onSetAgendaWidgetDaysAhead = viewModel::setAgendaWidgetDaysAhead,
                    onSetAgendaWidgetShowFileName = viewModel::setAgendaWidgetShowFileName,
                    onSetAgendaWidgetShowTags = viewModel::setAgendaWidgetShowTags,
                    onSetAgendaWidgetShowPriority = viewModel::setAgendaWidgetShowPriority,
                    onSetAgendaWidgetFontSize = viewModel::setAgendaWidgetFontSize,
                    onOpenQuickAddTargetPicker = viewModel::startQuickAddTargetPick,
                )
                val locationPicker by viewModel.locationPicker.collectAsStateWithLifecycle()
                locationPicker?.let { picker ->
                    RefileSheet(
                        state = picker,
                        currentFileName = null,
                        currentDoc = null,
                        onPickNotebook = viewModel::locationPickNotebook,
                        onDrillInto = viewModel::locationDrillInto,
                        onBack = viewModel::locationBack,
                        onCancel = viewModel::locationCancel,
                        onConfirm = viewModel::locationConfirm,
                        onArchive = {},
                        onPickLastUsed = {},
                        onSelectSearchResult = viewModel::locationSelectSearchResult,
                        headerTitle = "Set quick add target",
                        confirmLabel = "Add tasks under this heading",
                        topLevelConfirmLabel = "Add tasks to this file",
                    )
                }
            }
            composable(Routes.SETTINGS_REMINDERS) {
                SettingsRemindersScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetRemindersEnabled = viewModel::setRemindersEnabled,
                    onSetMorningBriefEnabled = viewModel::setMorningBriefEnabled,
                    onSetNotifyUntimedTasks = viewModel::setNotifyUntimedTasks,
                    onSetDefaultReminderTime = viewModel::setDefaultReminderTime,
                    onSetReminderLeadTime = viewModel::setReminderLeadTime,
                )
            }
            composable(Routes.SETTINGS_SHARING) {
                SettingsSharingScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onSetShareTargetFile = viewModel::setShareTargetFile,
                    onSetFetchSharedLinkTitles = viewModel::setFetchSharedLinkTitles,
                )
            }
            composable(Routes.SETTINGS_ROAM) {
                SettingsRoamScreen(
                    settings = settings,
                    directories = viewModel.directories.collectAsStateWithLifecycle().value,
                    onBack = { navController.popBackStack() },
                    onSetRoamFeaturesEnabled = viewModel::setRoamFeaturesEnabled,
                    onSetRoamShowBacklinks = viewModel::setRoamShowBacklinks,
                    onSetRoamShowSuggestions = viewModel::setRoamShowSuggestions,
                    onSetRoamOpenWholeFile = viewModel::setRoamOpenWholeFile,
                    onSetDailiesDirectory = viewModel::setDailiesDirectory,
                    onSetDailiesFilenamePattern = viewModel::setDailiesFilenamePattern,
                    onSetDailiesHeaderTemplate = viewModel::setDailiesHeaderTemplate,
                )
            }
            composable(Routes.SETTINGS_BACKUP) {
                SettingsBackupScreen(
                    onBack = { navController.popBackStack() },
                    onExportSettings = viewModel::exportSettings,
                    onImportSettings = viewModel::importSettings,
                )
            }
            composable(Routes.SETTINGS_BUG_REPORT) {
                SettingsBugReportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS_TIPS) {
                SettingsTipsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS_WHATS_NEW) {
                // Opening the screen counts as seeing what's new: stamp the build so the
                // launch-time WhatsNewDialog won't also fire for these same releases.
                LaunchedEffect(Unit) { viewModel.markWhatsNewSeen() }
                SettingsWhatsNewScreen(
                    versions = whatsNewHistory,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS_DEVELOPER) {
                SettingsDeveloperScreen(
                    onBack = { navController.popBackStack() },
                    onResetNewBadges = viewModel::resetNewBadges,
                )
            }
        }
        // Overlays whatever the capture route's onSaved popped back onto, since
        // AppViewModel (not a route-scoped ViewModel) owns this state -- see
        // AppViewModel.captureSavedSnack.
        val captureSavedSnack by viewModel.captureSavedSnack.collectAsStateWithLifecycle()
        GroveActionSnackbar(
            visible = captureSavedSnack != null,
            message = "Saved \"${captureSavedSnack?.filePath?.substringAfterLast('/').orEmpty()}\"",
            actionLabel = "Visit file",
            onAction = {
                val path = captureSavedSnack?.filePath ?: return@GroveActionSnackbar
                viewModel.dismissCaptureSavedSnack()
                navController.navigate(Routes.file(path, "read"))
            },
            // navigationBarsPadding, not a Scaffold's contentWindowInsets, since
            // this overlay sits above the whole NavHost rather than inside any
            // one screen's own inset-aware Scaffold (see GroveUndoSnackbar/
            // GroveToast, which rely on the latter).
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        )
    }
    }
    }

    LaunchedEffect(settings.onboardingDone) {
        if (settings.onboardingDone) {
            viewModel.checkWhatsNew()
            viewModel.ensureNewBadgeBaseline()
        }
    }
    val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()
    if (whatsNew.isNotEmpty()) {
        com.rrajath.grove.ui.screens.WhatsNewDialog(versions = whatsNew, onDismiss = viewModel::dismissWhatsNew)
    }

    // One-time exact-alarm prompt: checked on every resume, and again once onboarding
    // finishes or What's New closes, so it queues behind those instead of stacking.
    androidx.lifecycle.compose.LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkExactAlarmPrompt()
    }
    LaunchedEffect(settings.onboardingDone, whatsNew.isEmpty()) {
        if (whatsNew.isEmpty()) viewModel.checkExactAlarmPrompt()
    }
    val exactAlarmPrompt by viewModel.exactAlarmPrompt.collectAsStateWithLifecycle()
    if (exactAlarmPrompt && whatsNew.isEmpty()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        ExactAlarmPromptDialog(
            onAllow = {
                viewModel.onExactAlarmPromptAllow()
                ReminderAccessIntents.openExactAlarmSettings(context)
            },
            onNotNow = viewModel::onExactAlarmPromptNotNow,
        )
    }
}
