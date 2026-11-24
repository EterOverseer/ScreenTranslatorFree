package com.example.manga

import com.example.domain.model.OcrBlock
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.OcrResult
import com.example.domain.model.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaContextProcessorTest {

    @Test
    fun `japanese manga dialogue orders right-to-left within band`() {
        val processor = MangaContextProcessor()
        // Right bubble vs Left bubble in the same top panel
        val leftBubble = OcrBlock(
            text = "本当か？",
            lines = listOf("本当か？"),
            box = OcrBoundingBox(100, 120, 260, 220),
            detectedLanguage = "ja"
        )
        val rightBubble = OcrBlock(
            text = "ケンジ、今すぐ逃げろ！",
            lines = listOf("ケンジ、今すぐ逃げろ！"),
            box = OcrBoundingBox(760, 100, 960, 240),
            detectedLanguage = "ja"
        )

        val ocr = OcrResult(
            blocks = listOf(leftBubble, rightBubble),
            rawText = "本当か？\nケンジ、今すぐ逃げろ！",
            detectedLanguage = "ja",
            imageWidth = 1080,
            imageHeight = 2400
        )

        val batch = processor.process(ocr, ReadingDirection.AUTO)
        assertEquals(ReadingDirection.RTL_MANGA, batch.readingDirection)
        assertEquals(2, batch.regions.size)
        assertEquals("ケンジ、今すぐ逃げろ！", batch.regions[0].text)
        assertEquals("本当か？", batch.regions[1].text)
    }

    @Test
    fun `japanese vertical text joins vertical columns cleanly`() {
        val processor = MangaContextProcessor()
        val verticalBlock = OcrBlock(
            text = "お前\nは\n誰だ",
            lines = listOf("お前", "は", "誰だ"),
            box = OcrBoundingBox(800, 300, 860, 620),
            detectedLanguage = "ja",
            isVerticalHint = true
        )
        val ocr = OcrResult(
            blocks = listOf(verticalBlock),
            rawText = "お前\nは\n誰だ",
            detectedLanguage = "ja",
            imageWidth = 1080,
            imageHeight = 2400
        )

        val batch = processor.process(ocr, ReadingDirection.RTL_MANGA)
        assertEquals("お前は誰だ", batch.regions.first().text)
    }

    @Test
    fun `korean manhwa dialogue orders top-to-bottom left-to-right`() {
        val processor = MangaContextProcessor()
        val topBubble = OcrBlock(
            text = "이봐, 잠깐 기다려!",
            lines = listOf("이봐, 잠깐 기다려!"),
            box = OcrBoundingBox(120, 200, 480, 300),
            detectedLanguage = "ko"
        )
        val lowerBubble = OcrBlock(
            text = "무슨 일이야?",
            lines = listOf("무슨 일이야?"),
            box = OcrBoundingBox(620, 750, 920, 860),
            detectedLanguage = "ko"
        )

        val ocr = OcrResult(
            blocks = listOf(lowerBubble, topBubble),
            rawText = "",
            detectedLanguage = "ko",
            imageWidth = 1080,
            imageHeight = 2400
        )

        val batch = processor.process(ocr, ReadingDirection.AUTO)
        assertEquals(ReadingDirection.LTR_MANHWA, batch.readingDirection)
        assertEquals("이봐, 잠깐 기다려!", batch.regions[0].text)
        assertEquals("무슨 일이야?", batch.regions[1].text)
    }

    @Test
    fun `mixed japanese and english text preserves both without dropping`() {
        val processor = MangaContextProcessor()
        val mixedBlock = OcrBlock(
            text = "SYSTEM LEVEL UP!\nスキルを獲得しました",
            lines = listOf("SYSTEM LEVEL UP!", "スキルを獲得しました"),
            box = OcrBoundingBox(200, 400, 780, 520),
            detectedLanguage = "ja"
        )
        val ocr = OcrResult(
            blocks = listOf(mixedBlock),
            rawText = mixedBlock.text,
            detectedLanguage = "ja",
            imageWidth = 1080,
            imageHeight = 2400
        )

        val batch = processor.process(ocr, ReadingDirection.RTL_MANGA)
        assertTrue(batch.formattedText.contains("SYSTEM LEVEL UP!"))
        assertTrue(batch.formattedText.contains("スキルを獲得しました"))
    }

    @Test
    fun `sound effects and short expressions are tagged and previous context is retained`() {
        val processor = MangaContextProcessor()
        val sfxJa = OcrBlock(
            text = "ゴゴゴゴ",
            lines = listOf("ゴゴゴゴ"),
            box = OcrBoundingBox(120, 200, 240, 280),
            detectedLanguage = "ja"
        )
        val shortName = OcrBlock(
            text = "先輩！",
            lines = listOf("先輩！"),
            box = OcrBoundingBox(760, 200, 920, 280),
            detectedLanguage = "ja"
        )
        val sfxKo = OcrBlock(
            text = "쾅!",
            lines = listOf("쾅!"),
            box = OcrBoundingBox(300, 1200, 400, 1280),
            detectedLanguage = "ko"
        )

        val ocr = OcrResult(
            blocks = listOf(sfxJa, shortName, sfxKo),
            rawText = "",
            detectedLanguage = "ja",
            imageWidth = 1080,
            imageHeight = 2400
        )

        val batch1 = processor.process(ocr, ReadingDirection.RTL_MANGA)
        assertEquals(3, batch1.regions.size)
        assertTrue(batch1.regions.any { it.isSfx && it.text == "ゴゴゴゴ" })
        assertTrue(batch1.regions.any { it.isSfx && it.text == "쾅!" })
        assertTrue(batch1.regions.any { it.isShortExpression && it.text == "先輩！" })

        processor.recordTranslatedContext(listOf("先輩！"), "รุ่นพี่!")
        val batch2 = processor.process(ocr, ReadingDirection.RTL_MANGA)
        assertEquals(1, batch2.previousContextLines.size)
        assertTrue(batch2.previousContextLines.first().contains("รุ่นพี่!"))
    }
}
