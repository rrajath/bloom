package com.rrajath.grove.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {

    private val sample = GroveSettings(
        themeSelection = ThemeSelection(ThemeMode.SYSTEM, ThemePreference.ROSEPINEDAWN, ThemePreference.TOKYONIGHT),
        syncAppIconWithTheme = true,
        appFontSize = FontSizePreference.LARGE,
        readModeFontSize = FontSizePreference.LARGE,
        editModeFontSize = FontSizePreference.SMALL,
        defaultNoteOpenMode = NoteOpenMode.EDIT,
        syncMode = SyncMode.PERIODIC,
        periodicSyncMinutes = 60,
        todoKeywords = "TODO NEXT | DONE",
        ignoreList = "archive\n*.bak",
        defaultPriority = 'B',
        addIdToNewNotes = true,
        addCreatedToNewNotes = false,
        newNoteCursor = NewNoteCursor.HEADING,
        capitalizeHeadings = false,
        // Nested vault paths are valid keys now the vault is a tree.
        notebookModes = mapOf("work.org" to "edit", "projects/clients/acme.org" to "read"),
        notebookColors = mapOf("work.org" to "green", "projects/clients/acme.org" to "amber"),
        folderColors = mapOf("projects" to "cobalt", "projects/clients" to "rose"),
        captureNotification = true,
        shareTargetFile = "capture.org",
        fetchSharedLinkTitles = false,
        showTagsInOutline = false,
        showTimestampsInOutline = false,
        showKeywordsInOutline = true,
        // Interleaved file/folder pins to prove chronological order round-trips.
        pinnedItems = listOf(
            PinnedItem(PinKind.FILE, "pinned-first.org"),
            PinnedItem(PinKind.FOLDER, "projects"),
            PinnedItem(PinKind.FILE, "pinned-second.org"),
            PinnedItem(PinKind.FOLDER, "projects/clients"),
            PinnedItem(PinKind.FILE, "area/sub/deep.org"),
        ),
        showPreface = false,
        showPropertyDrawers = false,
        showNotebookFileIcons = false,
        flattenNotebookFolders = true,
        notebookSortKey = NotebookSortKey.LAST_MODIFIED,
        notebookSortAscending = false,
        remindersEnabled = false,
        morningBriefEnabled = false,
        notifyUntimedTasks = true,
        defaultReminderTime = java.time.LocalTime.of(7, 45),
        reminderLeadTime = ReminderLeadTime.MIN_15,
        agendaSwipeLeftAction = AgendaSwipeAction.MARK_DONE,
        agendaSwipeRightAction = AgendaSwipeAction.SET_DEADLINE,
        agendaGroupingToday = AgendaGrouping.PRIORITY,
        agendaGroupingUpcoming = AgendaGrouping.TAG,
        agendaStateFilterToday = AgendaStateFilter.Keyword("NEXT"),
        agendaStateFilterUpcoming = AgendaStateFilter.All,
        agendaWidgetTransparency = 0.35f,
        agendaWidgetDaysAhead = 21,
        agendaWidgetShowFileName = true,
        agendaWidgetShowTags = false,
        agendaWidgetShowPriority = false,
        agendaWidgetFontSize = FontSizePreference.LARGE,
        autoArchiveDoneItems = true,
        autoArchiveFile = "archive.org",
        autoArchiveHeadingPath = "Done/2026",
        quickAddTargetFile = "tasks.org",
        quickAddHeadingPath = "Work/Inbox",
        lastRefileFile = "projects.org",
        lastRefileHeadingPath = "Active/Grove",
        roamFeaturesEnabled = true,
        roamShowBacklinks = false,
        roamShowSuggestions = true,
        roamOpenWholeFile = true,
        dailiesDirectory = "roam/daily",
        dailiesFilenamePattern = "%<%Y%m%d>.org",
        dailiesHeaderTemplate = ":PROPERTIES:\n:ID: %(id)\n:END:\n#+title: %<%Y-%m-%d>\n%?",
        // Device-specific fields that must NOT travel with an export.
        vaultTreeUri = "content://com.android.externalstorage/tree/primary%3Aorg",
        onboardingDone = true,
    )

    @Test
    fun `portable preferences survive an export then import`() {
        val json = SettingsSerialization.export(sample)
        // Re-import onto fresh defaults so we can prove every portable field carried.
        val restored = SettingsSerialization.import(json, GroveSettings())

        assertEquals(sample.themeSelection, restored.themeSelection)
        assertEquals(sample.syncAppIconWithTheme, restored.syncAppIconWithTheme)
        assertEquals(sample.appFontSize, restored.appFontSize)
        assertEquals(sample.readModeFontSize, restored.readModeFontSize)
        assertEquals(sample.editModeFontSize, restored.editModeFontSize)
        assertEquals(sample.defaultNoteOpenMode, restored.defaultNoteOpenMode)
        assertEquals(sample.syncMode, restored.syncMode)
        assertEquals(sample.periodicSyncMinutes, restored.periodicSyncMinutes)
        assertEquals(sample.todoKeywords, restored.todoKeywords)
        assertEquals(sample.ignoreList, restored.ignoreList)
        assertEquals(sample.defaultPriority, restored.defaultPriority)
        assertEquals(sample.addIdToNewNotes, restored.addIdToNewNotes)
        assertEquals(sample.addCreatedToNewNotes, restored.addCreatedToNewNotes)
        assertEquals(sample.newNoteCursor, restored.newNoteCursor)
        assertEquals(sample.capitalizeHeadings, restored.capitalizeHeadings)
        assertEquals(sample.notebookModes, restored.notebookModes)
        assertEquals(sample.notebookColors, restored.notebookColors)
        assertEquals(sample.folderColors, restored.folderColors)
        assertEquals(sample.captureNotification, restored.captureNotification)
        assertEquals(sample.shareTargetFile, restored.shareTargetFile)
        assertEquals(sample.fetchSharedLinkTitles, restored.fetchSharedLinkTitles)
        assertEquals(sample.showTagsInOutline, restored.showTagsInOutline)
        assertEquals(sample.showTimestampsInOutline, restored.showTimestampsInOutline)
        assertEquals(sample.showKeywordsInOutline, restored.showKeywordsInOutline)
        assertEquals(sample.pinnedItems, restored.pinnedItems)
        assertEquals(sample.pinnedNotebooks, restored.pinnedNotebooks)
        assertEquals(sample.pinnedFolders, restored.pinnedFolders)
        assertEquals(sample.showPreface, restored.showPreface)
        assertEquals(sample.showPropertyDrawers, restored.showPropertyDrawers)
        assertEquals(sample.showNotebookFileIcons, restored.showNotebookFileIcons)
        assertEquals(sample.flattenNotebookFolders, restored.flattenNotebookFolders)
        assertEquals(sample.notebookSortKey, restored.notebookSortKey)
        assertEquals(sample.notebookSortAscending, restored.notebookSortAscending)
        assertEquals(sample.remindersEnabled, restored.remindersEnabled)
        assertEquals(sample.morningBriefEnabled, restored.morningBriefEnabled)
        assertEquals(sample.notifyUntimedTasks, restored.notifyUntimedTasks)
        assertEquals(sample.defaultReminderTime, restored.defaultReminderTime)
        assertEquals(sample.reminderLeadTime, restored.reminderLeadTime)
        assertEquals(sample.agendaSwipeLeftAction, restored.agendaSwipeLeftAction)
        assertEquals(sample.agendaSwipeRightAction, restored.agendaSwipeRightAction)
        assertEquals(sample.agendaGroupingToday, restored.agendaGroupingToday)
        assertEquals(sample.agendaGroupingUpcoming, restored.agendaGroupingUpcoming)
        assertEquals(sample.agendaStateFilterToday, restored.agendaStateFilterToday)
        assertEquals(sample.agendaStateFilterUpcoming, restored.agendaStateFilterUpcoming)
        assertEquals(sample.agendaWidgetTransparency, restored.agendaWidgetTransparency)
        assertEquals(sample.agendaWidgetDaysAhead, restored.agendaWidgetDaysAhead)
        assertEquals(sample.agendaWidgetShowFileName, restored.agendaWidgetShowFileName)
        assertEquals(sample.agendaWidgetShowTags, restored.agendaWidgetShowTags)
        assertEquals(sample.agendaWidgetShowPriority, restored.agendaWidgetShowPriority)
        assertEquals(sample.agendaWidgetFontSize, restored.agendaWidgetFontSize)
        assertEquals(sample.autoArchiveDoneItems, restored.autoArchiveDoneItems)
        assertEquals(sample.autoArchiveFile, restored.autoArchiveFile)
        assertEquals(sample.autoArchiveHeadingPath, restored.autoArchiveHeadingPath)
        assertEquals(sample.quickAddTargetFile, restored.quickAddTargetFile)
        assertEquals(sample.quickAddHeadingPath, restored.quickAddHeadingPath)
        assertEquals(sample.lastRefileFile, restored.lastRefileFile)
        assertEquals(sample.lastRefileHeadingPath, restored.lastRefileHeadingPath)
        assertEquals(sample.roamFeaturesEnabled, restored.roamFeaturesEnabled)
        assertEquals(sample.roamShowBacklinks, restored.roamShowBacklinks)
        assertEquals(sample.roamShowSuggestions, restored.roamShowSuggestions)
        assertEquals(sample.roamOpenWholeFile, restored.roamOpenWholeFile)
        assertEquals(sample.dailiesDirectory, restored.dailiesDirectory)
        assertEquals(sample.dailiesFilenamePattern, restored.dailiesFilenamePattern)
        assertEquals(sample.dailiesHeaderTemplate, restored.dailiesHeaderTemplate)
    }

    @Test
    fun `attachment settings survive an export then import`() {
        val custom = GroveSettings(
            attachPhotoSize = AttachPhotoSize.MEDIUM,
            attachImageWidth = AttachImageWidth.FIXED,
            attachImageWidthPx = 480,
        )
        val restored = SettingsSerialization.import(SettingsSerialization.export(custom), GroveSettings())
        assertEquals(AttachPhotoSize.MEDIUM, restored.attachPhotoSize)
        assertEquals(AttachImageWidth.FIXED, restored.attachImageWidth)
        assertEquals(480, restored.attachImageWidthPx)
    }

    @Test
    fun `unknown attachment enums fall back to defaults and the width is clamped`() {
        val base = SettingsSerialization.export(GroveSettings())
        val bad = base
            .replace("\"attachPhotoSize\": \"ORIGINAL\"", "\"attachPhotoSize\": \"HUGE\"")
            .replace("\"attachImageWidth\": \"FIT\"", "\"attachImageWidth\": \"STRETCH\"")
            .replace("\"attachImageWidthPx\": 320", "\"attachImageWidthPx\": 9000")
        assertTrue(bad.contains("HUGE") && bad.contains("STRETCH") && bad.contains("9000"))
        val restored = SettingsSerialization.import(
            bad,
            GroveSettings(attachPhotoSize = AttachPhotoSize.SMALL, attachImageWidth = AttachImageWidth.FIXED),
        )
        assertEquals(AttachPhotoSize.ORIGINAL, restored.attachPhotoSize)
        assertEquals(AttachImageWidth.FIT, restored.attachImageWidth)
        assertEquals(AttachImageWidth.MAX_PX, restored.attachImageWidthPx)
    }

    @Test
    fun `an export without attachment keys imports the defaults`() {
        val json = SettingsSerialization.export(GroveSettings())
            .lines().filterNot { it.contains("\"attach") }.joinToString("\n")
            .replace(Regex(",(\\s*})"), "$1")
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(AttachPhotoSize.ORIGINAL, restored.attachPhotoSize)
        assertEquals(AttachImageWidth.FIT, restored.attachImageWidth)
        assertEquals(AttachImageWidth.DEFAULT_PX, restored.attachImageWidthPx)
        assertEquals("data/", restored.attachFolder)
    }

    @Test
    fun `the attachment folder survives an export then import`() {
        val json = SettingsSerialization.export(GroveSettings(attachFolder = "media/files/"))
        assertTrue(json.contains("\"version\": 14"))
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals("media/files/", restored.attachFolder)
    }

    @Test
    fun `an invalid attachment folder imports as data`() {
        for (bad in listOf("../attach", "/attach", "~/attach", "")) {
            val json = SettingsSerialization.export(GroveSettings(attachFolder = "x/"))
                .replace("\"attachFolder\": \"x/\"", "\"attachFolder\": \"$bad\"")
            assertTrue(bad, json.contains("\"attachFolder\": \"$bad\""))
            val restored = SettingsSerialization.import(json, GroveSettings(attachFolder = "attach/"))
            assertEquals(bad, "data/", restored.attachFolder)
        }
    }

    @Test
    fun `a v12 export without the folder imports as data`() {
        val v12 = SettingsSerialization.export(GroveSettings(attachPhotoSize = AttachPhotoSize.SMALL))
            .replace("\"version\": 14", "\"version\": 12")
            .lines().filterNot { it.contains("\"attachFolder\"") || it.contains("\"attachAutoTag\"") }.joinToString("\n")
            .replace(Regex(",(\\s*})"), "$1")
        val restored = SettingsSerialization.import(v12, GroveSettings(attachFolder = "attach/"))
        assertEquals(AttachPhotoSize.SMALL, restored.attachPhotoSize)
        assertEquals("data/", restored.attachFolder)
    }

    @Test
    fun `the attachment tag survives an export then import, empty included`() {
        for (tag in listOf("receipt", "")) {
            val restored = SettingsSerialization.import(
                SettingsSerialization.export(GroveSettings(attachAutoTag = tag)), GroveSettings(),
            )
            assertEquals(tag, restored.attachAutoTag)
        }
    }

    @Test
    fun `a v13 export without the tag, or an invalid tag, imports as ATTACH`() {
        val v13 = SettingsSerialization.export(GroveSettings(attachAutoTag = "receipt"))
            .replace("\"version\": 14", "\"version\": 13")
            .lines().filterNot { it.contains("\"attachAutoTag\"") }.joinToString("\n")
            .replace(Regex(",(\\s*})"), "$1")
        assertEquals("ATTACH", SettingsSerialization.import(v13, GroveSettings(attachAutoTag = "x")).attachAutoTag)
        val bad = SettingsSerialization.export(GroveSettings(attachAutoTag = "receipt"))
            .replace("\"attachAutoTag\": \"receipt\"", "\"attachAutoTag\": \"a b\"")
        assertEquals("ATTACH", SettingsSerialization.import(bad, GroveSettings()).attachAutoTag)
    }

    @Test
    fun `an old-format export with only pinnedNotebooks and pinnedFolders imports into the unified list`() {
        // A pre-unification export: no "pinnedItems" key, just the two legacy arrays.
        val json = """
            {
              "pinnedNotebooks": ["inbox.org", "area/sub/deep.org"],
              "pinnedFolders": ["projects", "projects/clients"]
            }
        """.trimIndent()
        val restored = SettingsSerialization.import(json, GroveSettings())

        // Legacy lists carry no cross-order, so folders come first then files.
        assertEquals(
            listOf(
                PinnedItem(PinKind.FOLDER, "projects"),
                PinnedItem(PinKind.FOLDER, "projects/clients"),
                PinnedItem(PinKind.FILE, "inbox.org"),
                PinnedItem(PinKind.FILE, "area/sub/deep.org"),
            ),
            restored.pinnedItems,
        )
        assertEquals(listOf("inbox.org", "area/sub/deep.org"), restored.pinnedNotebooks)
        assertEquals(listOf("projects", "projects/clients"), restored.pinnedFolders)
    }

    @Test
    fun `an export without fetchSharedLinkTitles imports with link-title fetching on`() {
        val restored = SettingsSerialization.import("{}", GroveSettings(fetchSharedLinkTitles = false))
        assertTrue(restored.fetchSharedLinkTitles)
    }

    @Test
    fun `a pinned path containing a colon survives the round trip`() {
        val weird = GroveSettings(
            pinnedItems = listOf(PinnedItem(PinKind.FILE, "odd:name.org"), PinnedItem(PinKind.FOLDER, "a:b")),
        )
        val restored = SettingsSerialization.import(SettingsSerialization.export(weird), GroveSettings())
        assertEquals(weird.pinnedItems, restored.pinnedItems)
    }

    @Test
    fun `import preserves the existing vault and onboarding state`() {
        val json = SettingsSerialization.export(sample)
        val base = GroveSettings(vaultTreeUri = "content://existing/tree", onboardingDone = false)
        val restored = SettingsSerialization.import(json, base)

        // The export never carried these, so the base install's values win.
        assertEquals("content://existing/tree", restored.vaultTreeUri)
        assertEquals(false, restored.onboardingDone)
        assertNotEquals(sample.vaultTreeUri, restored.vaultTreeUri)
    }

    @Test
    fun `exact-alarm device flags are never exported or overwritten on import`() {
        val source = sample.copy(exactAlarmAccessLastKnown = true, exactAlarmPromptHandled = true)
        val json = SettingsSerialization.export(source)
        assertTrue("device-local flags must not be exported", !json.contains("exactAlarm"))

        val base = GroveSettings(exactAlarmAccessLastKnown = false, exactAlarmPromptHandled = false)
        val restored = SettingsSerialization.import(json, base)
        // Device-specific: exact-alarm access and whether this device was asked are per install.
        assertEquals(false, restored.exactAlarmAccessLastKnown)
        assertEquals(false, restored.exactAlarmPromptHandled)
    }

    @Test
    fun `exported document is human-readable json`() {
        val json = SettingsSerialization.export(sample)
        assertTrue(json.contains("\"themeMode\": \"system\""))
        assertTrue(json.contains("\"darkTheme\": \"tokyonight\""))
        assertTrue(json.contains("\"version\": ${SettingsExport.CURRENT_VERSION}"))
        // Pretty-printed across multiple lines.
        assertTrue(json.contains("\n"))
    }

    @Test
    fun `unknown enum values fall back to defaults on import`() {
        val json = """{ "theme": "sepia", "readModeFontSize": "huge", "editModeFontSize": "tiny",
            "syncMode": "warp", "checklistStates": "four",
            "agendaSwipeLeftAction": "cartwheel", "agendaSwipeRightAction": "backflip", "reminderLeadTime": "next week" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())

        assertEquals(ThemeSelection.fromLegacy(ThemePreference.LIGHT), restored.themeSelection)
        assertEquals(FontSizePreference.MEDIUM, restored.readModeFontSize)
        assertEquals(FontSizePreference.MEDIUM, restored.editModeFontSize)
        assertEquals(SyncMode.ON_OPEN_CLOSE, restored.syncMode)
        // "checklistStates" is a now-removed key; it must be ignored, not throw.
        assertEquals(AgendaSwipeAction.MARK_DONE, restored.agendaSwipeLeftAction)
        assertEquals(AgendaSwipeAction.SET_SCHEDULED, restored.agendaSwipeRightAction)
        assertEquals(ReminderLeadTime.AT_TIME, restored.reminderLeadTime)
    }

    @Test
    fun `a pre-v3 export without appFontSize imports as MEDIUM`() {
        val json = """{ "readModeFontSize": "large" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(FontSizePreference.MEDIUM, restored.appFontSize)
    }

    @Test
    fun `a pre-split export seeds both read and edit font size from the old fontSize`() {
        // v1 exports carried a single "fontSize"; it must migrate into both new fields.
        val json = """{ "fontSize": "large" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(FontSizePreference.LARGE, restored.readModeFontSize)
        assertEquals(FontSizePreference.LARGE, restored.editModeFontSize)
    }

    @Test
    fun `a new export no longer writes the retired fontSize key`() {
        val json = SettingsSerialization.export(sample)
        assertTrue(json.contains("\"readModeFontSize\""))
        assertTrue(!json.contains("\"fontSize\""))
    }

    @Test
    fun `agenda widget transparency and days-ahead are clamped on import`() {
        val json = """{ "agendaWidgetTransparency": 1.5, "agendaWidgetDaysAhead": 1 }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(1f, restored.agendaWidgetTransparency)
        assertEquals(2, restored.agendaWidgetDaysAhead)
    }

    @Test
    fun `a pre-v4 export without the widget-only levers imports at today's defaults`() {
        val json = """{ "theme": "dark" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(false, restored.agendaWidgetShowFileName)
        assertEquals(true, restored.agendaWidgetShowTags)
        assertEquals(true, restored.agendaWidgetShowPriority)
        assertEquals(FontSizePreference.MEDIUM, restored.agendaWidgetFontSize)
    }

    @Test
    fun `a pre-v8 export without ignoreList imports as an empty list`() {
        val json = """{ "theme": "dark" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals("", restored.ignoreList)
    }

    @Test
    fun `a pre-v9 export without dailies fields imports at their defaults`() {
        val legacy = """{"version": 8, "roamOpenWholeFile": true}"""
        val restored = SettingsSerialization.import(legacy)
        assertEquals("", restored.dailiesDirectory)
        assertEquals(GroveSettings().dailiesFilenamePattern, restored.dailiesFilenamePattern)
        assertEquals(GroveSettings().dailiesHeaderTemplate, restored.dailiesHeaderTemplate)
    }

    @Test
    fun `malformed default reminder time falls back to 9am`() {
        val json = """{ "defaultReminderTime": "not a time" }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(java.time.LocalTime.of(9, 0), restored.defaultReminderTime)
    }

    @Test
    fun `unknown keys are ignored so newer exports stay importable`() {
        val json = """{ "theme": "light", "futureField": 42 }"""
        val restored = SettingsSerialization.import(json, GroveSettings())
        assertEquals(ThemeSelection.fromLegacy(ThemePreference.LIGHT), restored.themeSelection)
    }

    @Test
    fun `a pre-mode export maps its single theme onto the matching mode and slot`() {
        val restored = SettingsSerialization.import("""{ "version": 10, "theme": "lantern" }""", GroveSettings())
        assertEquals(
            ThemeSelection(ThemeMode.DARK, ThemePreference.BLOOMLIGHT, ThemePreference.LANTERN),
            restored.themeSelection,
        )
    }

    @Test
    fun `export still writes the legacy theme so older versions import a sensible one`() {
        fun legacyTheme(selection: ThemeSelection) =
            Regex("\"theme\": \"(\\w+)\"").find(SettingsSerialization.export(GroveSettings(themeSelection = selection)))!!.groupValues[1]
        val s = ThemeSelection(ThemeMode.SYSTEM, ThemePreference.SAGE, ThemePreference.MOSS)
        assertEquals("sage", legacyTheme(s))
        assertEquals("sage", legacyTheme(s.copy(mode = ThemeMode.LIGHT)))
        assertEquals("moss", legacyTheme(s.copy(mode = ThemeMode.DARK)))
    }

    @Test
    fun `malformed json throws`() {
        assertThrows(Exception::class.java) {
            SettingsSerialization.import("not json at all", GroveSettings())
        }
    }
}
