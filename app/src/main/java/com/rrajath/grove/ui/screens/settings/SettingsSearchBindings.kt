package com.rrajath.grove.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rrajath.grove.R
import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.settings.AttachPhotoSize
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.NewNoteCursor
import com.rrajath.grove.settings.NotebookDisplayNameMode
import com.rrajath.grove.settings.NotebookSortKey
import com.rrajath.grove.settings.NoteOpenMode
import com.rrajath.grove.ui.AppViewModel

/** A search result's own control, already bound to its ViewModel setter. */
sealed interface InlineControl {
    class Toggle(val checked: (GroveSettings) -> Boolean, val onToggle: (Boolean) -> Unit) : InlineControl

    class Choice(
        val labels: @Composable () -> List<String>,
        val selectedIndex: (GroveSettings) -> Int,
        val onSelect: (Int) -> Unit,
    ) : InlineControl
}

private typealias Binding = (AppViewModel) -> InlineControl

private fun toggle(checked: (GroveSettings) -> Boolean, set: AppViewModel.(Boolean) -> Unit): Binding =
    { vm -> InlineControl.Toggle(checked) { vm.set(it) } }

private fun <T> choice(
    options: List<T>,
    label: @Composable (T) -> String,
    current: (GroveSettings) -> T,
    set: AppViewModel.(T) -> Unit,
): Binding = { vm ->
    InlineControl.Choice(
        labels = { options.map { label(it) } },
        selectedIndex = { s -> options.indexOf(current(s)).coerceAtLeast(0) },
        onSelect = { i -> vm.set(options[i]) },
    )
}

private fun fontSizes(current: (GroveSettings) -> FontSizePreference, set: AppViewModel.(FontSizePreference) -> Unit) =
    choice(FontSizePreference.entries, { stringResource(it.labelRes) }, current, set)

/**
 * The one place an inline search result is bound to its getter and setter. Every
 * setter here must be the same ViewModel method `GroveApp` passes to the setting's
 * page, so changing it from search does exactly what the page does. Keys must match
 * the [SettingSpec.inline] specs (`SettingSpecsTest` checks this).
 */
internal val INLINE_BINDINGS: Map<String, Binding> = mapOf(
    SettingSpecs.AppearanceTextSize.id to fontSizes({ it.appFontSize }, AppViewModel::setAppFontSize),

    SettingSpecs.NotebooksFileIcons.id to toggle({ it.showNotebookFileIcons }, AppViewModel::setShowNotebookFileIcons),
    SettingSpecs.NotebooksFlatten.id to toggle({ it.flattenNotebookFolders }, AppViewModel::setFlattenNotebookFolders),
    SettingSpecs.NotebooksDisplayName.id to choice(
        NotebookDisplayNameMode.entries,
        {
            stringResource(
                when (it) {
                    NotebookDisplayNameMode.FILENAME -> R.string.settings_notebooks_display_name_filename
                    NotebookDisplayNameMode.TITLE -> R.string.settings_notebooks_display_name_title
                },
            )
        },
        { it.notebookDisplayNameMode },
        AppViewModel::setNotebookDisplayNameMode,
    ),
    SettingSpecs.NotebooksSort.id to choice(
        NotebookSortKey.entries, { stringResource(it.labelRes) }, { it.notebookSortKey }, AppViewModel::setNotebookSortKey,
    ),
    SettingSpecs.NotebooksOrder.id to choice(
        listOf(true, false),
        { stringResource(if (it) R.string.settings_notebooks_order_ascending else R.string.settings_notebooks_order_descending) },
        { it.notebookSortAscending },
        AppViewModel::setNotebookSortAscending,
    ),

    SettingSpecs.NotesDefaultPriority.id to choice(
        listOf(null, 'A', 'B', 'C'),
        { it?.toString() ?: stringResource(R.string.settings_notes_priority_none) },
        { it.defaultPriority },
        AppViewModel::setDefaultPriority,
    ),
    SettingSpecs.NotesDefaultMode.id to choice(
        NoteOpenMode.entries,
        { stringResource(if (it == NoteOpenMode.READ) R.string.mode_read else R.string.mode_edit) },
        { it.defaultNoteOpenMode },
        AppViewModel::setDefaultNoteOpenMode,
    ),
    SettingSpecs.NotesShowPreface.id to toggle({ it.showPreface }, AppViewModel::setShowPreface),
    SettingSpecs.NotesShowDrawers.id to toggle({ it.showPropertyDrawers }, AppViewModel::setShowPropertyDrawers),
    SettingSpecs.NotesAddId.id to toggle({ it.addIdToNewNotes }, AppViewModel::setAddIdToNewNotes),
    SettingSpecs.NotesAddCreated.id to toggle({ it.addCreatedToNewNotes }, AppViewModel::setAddCreatedToNewNotes),
    SettingSpecs.NotesNewNoteCursor.id to choice(
        NewNoteCursor.entries,
        {
            stringResource(
                when (it) {
                    NewNoteCursor.HEADING -> R.string.settings_notes_new_note_cursor_heading
                    NewNoteCursor.BODY -> R.string.settings_notes_new_note_cursor_body
                },
            )
        },
        { it.newNoteCursor },
        AppViewModel::setNewNoteCursor,
    ),
    SettingSpecs.NotesAutoSave.id to toggle({ it.autoSaveNotes }, AppViewModel::setAutoSaveNotes),
    SettingSpecs.NotesCapitalize.id to toggle({ it.capitalizeHeadings }, AppViewModel::setCapitalizeHeadings),
    SettingSpecs.NotesHighlightLine.id to toggle({ it.highlightCurrentLine }, AppViewModel::setHighlightCurrentLine),
    SettingSpecs.NotesLineNumbers.id to toggle({ it.showLineNumbers }, AppViewModel::setShowLineNumbers),
    SettingSpecs.NotesAutoArchive.id to toggle({ it.autoArchiveDoneItems }, AppViewModel::setAutoArchiveDoneItems),

    SettingSpecs.WidgetShowFileName.id to toggle({ it.agendaWidgetShowFileName }, AppViewModel::setAgendaWidgetShowFileName),
    SettingSpecs.WidgetShowTags.id to toggle({ it.agendaWidgetShowTags }, AppViewModel::setAgendaWidgetShowTags),
    SettingSpecs.WidgetShowPriority.id to toggle({ it.agendaWidgetShowPriority }, AppViewModel::setAgendaWidgetShowPriority),
    SettingSpecs.WidgetFontSize.id to fontSizes({ it.agendaWidgetFontSize }, AppViewModel::setAgendaWidgetFontSize),

    SettingSpecs.RoamEnable.id to toggle({ it.roamFeaturesEnabled }, AppViewModel::setRoamFeaturesEnabled),
    SettingSpecs.RoamBacklinks.id to toggle({ it.roamShowBacklinks }, AppViewModel::setRoamShowBacklinks),
    SettingSpecs.RoamSuggestions.id to toggle({ it.roamShowSuggestions }, AppViewModel::setRoamShowSuggestions),
    SettingSpecs.RoamWholeFile.id to toggle({ it.roamOpenWholeFile }, AppViewModel::setRoamOpenWholeFile),

    SettingSpecs.AttachPhotoSize.id to choice(
        AttachPhotoSize.entries, { stringResource(it.labelRes) }, { it.attachPhotoSize }, AppViewModel::setAttachPhotoSize,
    ),
    SettingSpecs.AttachImageWidth.id to choice(
        AttachImageWidth.entries, { stringResource(it.labelRes) }, { it.attachImageWidth }, AppViewModel::setAttachImageWidth,
    ),

    SettingSpecs.SharingPlacement.id to choice(
        listOf(NotePlacement.TOP, NotePlacement.BOTTOM),
        { stringResource(if (it == NotePlacement.TOP) R.string.settings_share_placement_top else R.string.settings_share_placement_bottom) },
        { it.shareInsertPlacement },
        AppViewModel::setShareInsertPlacement,
    ),
    SettingSpecs.SharingFetchTitles.id to toggle({ it.fetchSharedLinkTitles }, AppViewModel::setFetchSharedLinkTitles),
)

/** [INLINE_BINDINGS] bound to [vm]: what Settings search renders for each inline result. */
fun inlineControls(vm: AppViewModel): Map<String, InlineControl> = INLINE_BINDINGS.mapValues { (_, bind) -> bind(vm) }
