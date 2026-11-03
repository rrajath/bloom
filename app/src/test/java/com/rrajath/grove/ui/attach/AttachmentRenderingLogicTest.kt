package com.rrajath.grove.ui.attach

import com.rrajath.grove.attach.ImageBounds
import com.rrajath.grove.org.BlockParser
import com.rrajath.grove.ui.screens.imageAttrRuns
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure pieces of 1d rendering: sizing, chip labels, chip detection, ATTR runs. */
class AttachmentRenderingLogicTest {

    @Test
    fun `image fits the column and keeps its ratio`() {
        val s = imageDisplaySize(maxW = 1000f, capH = 5000f, density = 2f, widthAttr = null, fixedWidth = null, bounds = ImageBounds(4000, 3000))
        assertEquals(1000f, s.width, 0.01f)
        assertEquals(750f, s.height, 0.01f)
    }

    @Test
    fun `attr and fixed widths are dp and the smallest wins`() {
        val s = imageDisplaySize(1000f, 5000f, 2f, widthAttr = 300, fixedWidth = 320, bounds = ImageBounds(4000, 2000))
        assertEquals(600f, s.width, 0.01f)
        assertEquals(300f, s.height, 0.01f)
    }

    @Test
    fun `small images never upscale past one pixel per dp`() {
        val s = imageDisplaySize(1000f, 5000f, 2f, null, null, ImageBounds(100, 50))
        assertEquals(200f, s.width, 0.01f)
    }

    @Test
    fun `tall images shrink to the height cap and stay proportional`() {
        val s = imageDisplaySize(1000f, capH = 600f, density = 1f, widthAttr = null, fixedWidth = null, bounds = ImageBounds(1000, 2000))
        assertEquals(600f, s.height, 0.01f)
        assertEquals(300f, s.width, 0.01f)
    }

    @Test
    fun `unknown bounds draw a 4 by 3 placeholder`() {
        val s = imageDisplaySize(400f, 5000f, 1f, null, null, null)
        assertEquals(300f, s.height, 0.01f)
    }

    @Test
    fun `chip labels middle-truncate past 28 chars`() {
        assertEquals("receipt.pdf", chipLabel("receipt.pdf"))
        assertEquals("boarding-pass-ky…kansen.pdf", chipLabel("boarding-pass-kyoto-shinkansen.pdf"))
    }

    @Test
    fun `chip links are local non-org links, labelled by description`() {
        val chips = chipLinksIn("Kept the [[attachment:receipt.pdf]] and a [[attachment:menu.jpg][menu photo]], see [[https://x.com]] and [[file:b.org]]")
        assertEquals(listOf("receipt.pdf", "menu photo"), chips.map { it.link.label })
        assertEquals(listOf("PDF", "JPG"), chips.map { it.link.badge })
    }

    @Test
    fun `an ATTR-only run directly above an image paragraph belongs to it`() {
        val blocks = BlockParser.parse(
            listOf(
                "#+ATTR_ORG: :width 240",
                "[[attachment:route.png]]",
                "",
                "#+ATTR_ORG: :width 99",
                "text first [[attachment:x.png]]",
                "",
                "#+CAPTION: kept",
                "[[attachment:y.png]]",
            )
        )
        val runs = imageAttrRuns(blocks)
        assertEquals(mapOf(0 to 240), runs)
    }
}
