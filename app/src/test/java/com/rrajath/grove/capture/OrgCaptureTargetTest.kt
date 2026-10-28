package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class OrgCaptureTargetTest {

    @Test
    fun `top and bottom of file`() {
        assertEquals(""":target (file "tasks.org") :prepend t""", orgCaptureTarget("tasks.org", TargetLocation.TopOfFile))
        assertEquals(""":target (file "tasks.org")""", orgCaptureTarget("tasks.org", TargetLocation.BottomOfFile))
    }

    @Test
    fun `under heading prefers id, then outline path, then title`() {
        assertEquals(
            """:target (id "ABC")""",
            orgCaptureTarget("t.org", TargetLocation.UnderHeading(id = "ABC", path = listOf("Tasks"))),
        )
        assertEquals(
            """:target (file+olp "t.org" "Work" "Tasks")""",
            orgCaptureTarget("t.org", TargetLocation.UnderHeading(path = listOf("Work", "Tasks"))),
        )
        assertEquals(
            """:target (file+headline "t.org" "Tasks")""",
            orgCaptureTarget("t.org", TargetLocation.UnderHeading(title = "Tasks")),
        )
    }

    @Test
    fun `datetree with heading and tree type`() {
        assertEquals(""":target (file+datetree "w.org")""", orgCaptureTarget("w.org", TargetLocation.Datetree()))
        assertEquals(
            """:target (file+olp+datetree "w.org" "Meetings") :tree-type week""",
            orgCaptureTarget("w.org", TargetLocation.Datetree(path = listOf("Meetings"), treeType = DatetreeType.WEEK)),
        )
        assertEquals(
            """:target (file+datetree "w.org") :tree-type month""",
            orgCaptureTarget("w.org", TargetLocation.Datetree(treeType = DatetreeType.MONTH)),
        )
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals(
            """:target (file+olp "a.org" "Say \"hi\" \\ bye")""",
            orgCaptureTarget("a.org", TargetLocation.UnderHeading(path = listOf("Say \"hi\" \\ bye"))),
        )
    }
}
