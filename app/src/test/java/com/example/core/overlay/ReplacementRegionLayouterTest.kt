package com.example.core.overlay

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.TranslatedRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplacementRegionLayouterTest {

    private fun buildLayouter(): ReplacementRegionLayouter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ReplacementRegionLayouter(context)
    }

    private fun defaultMapper() = OverlayCoordinateMapper(
        sourceWidth = 1080,
        sourceHeight = 1920,
        overlayWidth = 1080,
        overlayHeight = 1920
    )

    private fun makeRegion(
        id: String,
        thai: String,
        box: OcrBoundingBox,
        confidence: Float = 0.9f
    ) = TranslatedRegion(
        id = id,
        sourceText = "src_$id",
        translatedText = thai,
        box = box,
        isSfx = false,
        isShortExpression = thai.length <= 8,
        detectedLanguage = "ja",
        estimatedBackgroundArgb = Color.WHITE,
        estimatedTextArgb = 0xFF111827.toInt(),
        backgroundConfidence = confidence,
        readingOrderIndex = 0,
        imageWidth = 1080,
        imageHeight = 1920
    )

    @Test
    fun `short Thai dialogue fits cleanly inside bubble patch`() {
        val layouter = buildLayouter()
        val region = makeRegion("r1", "ลุยเลย!", OcrBoundingBox(100, 150, 280, 230))
        val plan = layouter.buildPlan(listOf(region), defaultMapper())

        assertEquals(1, plan.patches.size)
        assertTrue(plan.fallbackRegions.isEmpty())
        assertTrue(plan.patches[0].staticLayout.lineCount >= 1)
    }

    @Test
    fun `long Thai dialogue shrinks font and fits inside bubble without overflow`() {
        val layouter = buildLayouter()
        val text = "ฉันจะไม่มีวันยอมแพ้ง่ายๆ พวกเรามาสู้ไปด้วยกันเถอะ!"
        val region = makeRegion(
            "r1",
            text,
            OcrBoundingBox(100, 150, 340, 184)
        )
        val plan = layouter.buildPlan(listOf(region), defaultMapper())

        assertEquals(1, plan.patches.size)
        val patch = plan.patches[0]
        assertTrue("Expected font size to shrink below 22f, got ${patch.textSizePx}", patch.textSizePx < 22f)
        assertTrue(patch.staticLayout.height <= patch.patchRect.height)
        val maxLineWidth = (0 until patch.staticLayout.lineCount).maxOfOrNull {
            patch.staticLayout.getLineMax(it)
        } ?: 0f
        assertTrue(maxLineWidth <= patch.patchRect.width)
    }

    @Test
    fun `multi-line Thai text with vowels and tone marks preserves layout integrity`() {
        val layouter = buildLayouter()
        val thaiWithDiacritics = "กิ๊บเก๋ที่สุดในปฐพี\nอยู่ที่นี่ทุกวันก็ยังไหว\nตั้งใจสู้เต็มที่!"
        val region = makeRegion("r1", thaiWithDiacritics, OcrBoundingBox(120, 200, 420, 400))
        val plan = layouter.buildPlan(listOf(region), defaultMapper())

        assertEquals(1, plan.patches.size)
        val layout = plan.patches[0].staticLayout
        assertTrue(layout.lineCount >= 3)
        assertEquals(thaiWithDiacritics, layout.text.toString())
    }

    @Test
    fun `adjacent MangaRegions do not overlap each other`() {
        val layouter = buildLayouter()
        val r1 = makeRegion("r1", "ข้อความฝั่งซ้ายของบอลลูนที่หนึ่ง", OcrBoundingBox(80, 200, 280, 300))
        val r2 = makeRegion("r2", "ข้อความฝั่งขวาของบอลลูนที่สอง", OcrBoundingBox(284, 200, 490, 300))
        val plan = layouter.buildPlan(listOf(r1, r2), defaultMapper())

        assertEquals(2, plan.patches.size)
        val p1 = plan.patches[0].patchRect
        val p2 = plan.patches[1].patchRect
        assertFalse("Adjacent patches must not intersect", p1.intersects(p2, paddingPx = -0.5f))
    }

    @Test
    fun `overlapping patch candidates never collide after padding and expansion`() {
        val layouter = buildLayouter()
        // Two vertically close regions whose 5dp padding + conservative expansion would overlap
        val top = makeRegion("r1", "ด้านบน", OcrBoundingBox(150, 100, 350, 160))
        val bottom = makeRegion("r2", "ด้านล่าง", OcrBoundingBox(150, 166, 350, 226))
        val plan = layouter.buildPlan(listOf(top, bottom), defaultMapper())

        for (i in plan.patches.indices) {
            for (j in i + 1 until plan.patches.size) {
                assertFalse(
                    "Rendered patches must never overlap",
                    plan.patches[i].patchRect.intersects(plan.patches[j].patchRect, paddingPx = -0.5f)
                )
            }
        }
        assertEquals(2, plan.patches.size + plan.fallbackRegions.size)
    }

    @Test
    fun `tiny region surrounded by neighbors that cannot fit safely falls back to panel`() {
        val layouter = buildLayouter()
        val leftBlocker = makeRegion("r1", "บอลลูนซ้าย", OcrBoundingBox(50, 200, 198, 280))
        val tinySqueezed = makeRegion(
            "r2",
            "บทสนทนายาวมากที่ไม่มีทางยัดลงในกรอบเล็กแค่นี้ได้เลยแม้จะลดขนาดตัวอักษรแล้วก็ตาม",
            OcrBoundingBox(200, 210, 232, 232)
        )
        val rightBlocker = makeRegion("r3", "บอลลูนขวา", OcrBoundingBox(234, 200, 420, 280))

        val plan = layouter.buildPlan(listOf(leftBlocker, tinySqueezed, rightBlocker), defaultMapper())

        assertTrue(plan.fallbackRegions.any { it.id == "r2" })
        assertTrue(plan.patches.none { it.region.id == "r2" })
    }

    @Test
    fun `very low confidence artwork region skips patch rendering and falls back safely`() {
        val layouter = buildLayouter()
        val chaoticRegion = makeRegion(
            id = "r_art",
            thai = "เอฟเฟกต์",
            box = OcrBoundingBox(100, 100, 280, 180),
            confidence = 0.12f
        )
        val plan = layouter.buildPlan(listOf(chaoticRegion), defaultMapper())

        assertTrue(plan.patches.isEmpty())
        assertEquals(1, plan.fallbackRegions.size)
        assertEquals("r_art", plan.fallbackRegions[0].id)
    }
}
