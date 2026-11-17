package com.rrajath.grove.ui.screens.settings

import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.settings.GroveSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SharingPlacementDescriptionTest {

    @Test
    fun `breadcrumb drops the org extension and joins the heading path`() {
        assertEquals("inbox", shareTargetBreadcrumb(GroveSettings(shareTargetFile = "inbox.org")))
        assertEquals(
            "inbox › Projects › Reading",
            shareTargetBreadcrumb(GroveSettings(shareTargetFile = "inbox.org", shareTargetHeadingPath = "Projects/Reading")),
        )
    }

    @Test
    fun `a blank share file shows the default target`() {
        assertEquals("inbox", shareTargetBreadcrumb(GroveSettings(shareTargetFile = "  ")))
    }

    @Test
    fun `file targets describe the top or bottom of the file`() {
        assertEquals(
            "Shared items are added as a new heading at the bottom of inbox",
            placementDescription("inbox", toHeading = false, NotePlacement.BOTTOM),
        )
        assertEquals(
            "Shared items are added as a new heading at the top of inbox",
            placementDescription("inbox", toHeading = false, NotePlacement.TOP),
        )
    }

    @Test
    fun `heading targets describe the first or last subheading`() {
        assertEquals(
            "Shared items are added as the last subheading of inbox › Projects",
            placementDescription("inbox › Projects", toHeading = true, NotePlacement.BOTTOM),
        )
        assertEquals(
            "Shared items are added as the first subheading of inbox › Projects",
            placementDescription("inbox › Projects", toHeading = true, NotePlacement.TOP),
        )
    }
}
