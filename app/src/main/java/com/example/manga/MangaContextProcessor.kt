package com.example.manga

import com.example.domain.model.MangaContextBatch
import com.example.domain.model.MangaRegion
import com.example.domain.model.OcrBlock
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.OcrResult
import com.example.domain.model.ReadingDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MangaContextProcessor(
    private val maxHistoryLines: Int = 6
) {
    private val previousLines = ArrayDeque<String>()

    fun process(
        ocrResult: OcrResult,
        readingDirectionPreference: ReadingDirection = ReadingDirection.AUTO
    ): MangaContextBatch {
        val resolvedDirection = resolveDirection(
            preference = readingDirectionPreference,
            detectedLanguage = ocrResult.detectedLanguage
        )

        if (ocrResult.blocks.isEmpty()) {
            return MangaContextBatch(
                regions = emptyList(),
                formattedText = ocrResult.rawText.trim(),
                readingDirection = resolvedDirection,
                detectedSourceLanguage = ocrResult.detectedLanguage,
                previousContextLines = snapshotContext()
            )
        }

        val clusters = clusterNearbyBlocks(
            blocks = ocrResult.blocks,
            imageWidth = ocrResult.imageWidth.coerceAtLeast(1),
            imageHeight = ocrResult.imageHeight.coerceAtLeast(1)
        )

        val orderedClusters = orderClusters(
            clusters = clusters,
            direction = resolvedDirection,
            imageHeight = ocrResult.imageHeight.coerceAtLeast(1)
        )

        val regions = orderedClusters.mapIndexed { index, clusterBlocks ->
            val joinedText = joinClusterLines(clusterBlocks, resolvedDirection)
            val mergedBox = mergeBoxes(clusterBlocks.map { it.box })
            val isSfx = detectSfx(joinedText)
            val isShort = joinedText.length <= 8 && !isSfx
            MangaRegion(
                id = "region_${index + 1}",
                text = joinedText,
                isSfx = isSfx,
                isShortExpression = isShort,
                box = mergedBox
            )
        }

        val formatted = regions.joinToString("\n\n") { region ->
            if (region.isSfx) "[SFX] ${region.text}" else region.text
        }

        return MangaContextBatch(
            regions = regions,
            formattedText = formatted,
            readingDirection = resolvedDirection,
            detectedSourceLanguage = ocrResult.detectedLanguage,
            previousContextLines = snapshotContext()
        )
    }

    @Synchronized
    fun recordTranslatedContext(sourceLines: List<String>, translatedSummary: String) {
        val snippet = sourceLines.take(2).joinToString(" / ").take(80)
        val thaiSnippet = translatedSummary.lineSequence()
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" / ")
            .take(100)
        if (snippet.isNotBlank() && thaiSnippet.isNotBlank()) {
            previousLines.addLast("$snippet => $thaiSnippet")
            while (previousLines.size > maxHistoryLines) {
                previousLines.removeFirst()
            }
        }
    }

    @Synchronized
    fun snapshotContext(): List<String> = previousLines.toList()

    @Synchronized
    fun clearContext() {
        previousLines.clear()
    }

    private fun resolveDirection(
        preference: ReadingDirection,
        detectedLanguage: String
    ): ReadingDirection {
        if (preference != ReadingDirection.AUTO) return preference
        return when (detectedLanguage) {
            "ja" -> ReadingDirection.RTL_MANGA
            "ko", "en" -> ReadingDirection.LTR_MANHWA
            else -> ReadingDirection.LTR_MANHWA
        }
    }

    /**
     * Groups blocks that belong to the same speech bubble while keeping separate bubbles apart
     * so unrelated dialogue in adjacent panels is never merged.
     */
    private fun clusterNearbyBlocks(
        blocks: List<OcrBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): List<List<OcrBlock>> {
        val horizontalMergeLimit = max(24f, imageWidth * 0.045f)
        val verticalMergeLimit = max(20f, imageHeight * 0.025f)

        val remaining = blocks.toMutableList()
        val clusters = mutableListOf<MutableList<OcrBlock>>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val cluster = mutableListOf(seed)
            var mergedAny = true
            while (mergedAny) {
                mergedAny = false
                val clusterBox = mergeBoxes(cluster.map { it.box })
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (shouldMergeIntoCluster(clusterBox, candidate.box, horizontalMergeLimit, verticalMergeLimit)) {
                        cluster.add(candidate)
                        iterator.remove()
                        mergedAny = true
                    }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun shouldMergeIntoCluster(
        a: OcrBoundingBox,
        b: OcrBoundingBox,
        maxHGap: Float,
        maxVGap: Float
    ): Boolean {
        val hGap = max(0, max(a.left - b.right, b.left - a.right)).toFloat()
        val vGap = max(0, max(a.top - b.bottom, b.top - a.bottom)).toFloat()

        // Must be close both horizontally and vertically
        if (hGap > maxHGap || vGap > maxVGap) return false

        // Guard against merging distinct adjacent bubbles that happen to touch at one corner
        val xOverlap = min(a.right, b.right) - max(a.left, b.left)
        val yOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return xOverlap > -maxHGap * 0.6f && yOverlap > -maxVGap * 0.6f
    }

    private fun orderClusters(
        clusters: List<List<OcrBlock>>,
        direction: ReadingDirection,
        imageHeight: Int
    ): List<List<OcrBlock>> {
        val bandTolerance = max(80f, imageHeight * 0.14f)
        val clusterPairs = clusters.map { cluster -> cluster to mergeBoxes(cluster.map { it.box }) }

        // Group clusters into horizontal panel bands (top-to-bottom), then order within each band
        val topSorted = clusterPairs.sortedBy { it.second.top }
        val bands = mutableListOf<MutableList<Pair<List<OcrBlock>, OcrBoundingBox>>>()

        for (pair in topSorted) {
            val matchingBand = bands.lastOrNull { band ->
                val bandAnchorY = band.minOf { it.second.top }
                val bandBottomY = band.maxOf { it.second.bottom }
                abs(pair.second.top - bandAnchorY) <= bandTolerance || pair.second.top < bandBottomY - 20
            }
            if (matchingBand != null) {
                matchingBand.add(pair)
            } else {
                bands.add(mutableListOf(pair))
            }
        }

        val ordered = mutableListOf<List<OcrBlock>>()
        for (band in bands) {
            val sortedInBand = when (direction) {
                ReadingDirection.RTL_MANGA -> band.sortedWith(
                    compareByDescending<Pair<List<OcrBlock>, OcrBoundingBox>> { it.second.right }
                        .thenBy { it.second.top }
                )
                ReadingDirection.LTR_MANHWA, ReadingDirection.AUTO -> band.sortedWith(
                    compareBy<Pair<List<OcrBlock>, OcrBoundingBox>> { it.second.top }
                        .thenBy { it.second.left }
                )
            }
            ordered.addAll(sortedInBand.map { it.first })
        }
        return ordered
    }

    private fun joinClusterLines(
        clusterBlocks: List<OcrBlock>,
        direction: ReadingDirection
    ): String {
        val sortedBlocks = when (direction) {
            ReadingDirection.RTL_MANGA -> clusterBlocks.sortedWith(
                compareByDescending<OcrBlock> { it.box.right }.thenBy { it.box.top }
            )
            ReadingDirection.LTR_MANHWA, ReadingDirection.AUTO -> clusterBlocks.sortedWith(
                compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left }
            )
        }
        return sortedBlocks.joinToString("\n") { block ->
            if (block.isVerticalHint) {
                // Vertical manga columns often read cleanly when joined without artificial breaks
                block.lines.joinToString("")
            } else {
                block.lines.joinToString(" ")
            }
        }.trim()
    }

    private fun mergeBoxes(boxes: List<OcrBoundingBox>): OcrBoundingBox {
        return OcrBoundingBox(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom }
        )
    }

    fun detectSfx(text: String): Boolean {
        val cleaned = text.replace(Regex("""[\s!！?？…・ー〜]+"""), "")
        if (cleaned.isEmpty()) return false
        if (cleaned.length > 10) return false

        // Pure Katakana short sound effect (e.g. ドドド, ゴゴゴ, バン, バッ, ザワ)
        val allKatakana = cleaned.all { it in '\u30A0'..'\u30FF' || it in '\uFF65'..'\uFF9F' }
        if (allKatakana && cleaned.length in 1..8) return true

        // Repeated character onomatopoeia (e.g. ドドド, ゴゴ, ざわざわ, 두근두근)
        val isRepeatedSfx = (cleaned.length >= 3 && cleaned.toSet().size <= 2) ||
            (cleaned.length == 2 && cleaned[0] == cleaned[1])
        if (isRepeatedSfx && !cleaned.all { it.isDigit() }) {
            return true
        }

        // Common Korean short SFX tokens
        val commonKoreanSfx = setOf("쾅", "쿵", "쓱", "휙", "휘익", "두근", "덜덜", "짝", "찰칵", "후욱", "타닥", "부웅")
        if (cleaned in commonKoreanSfx) return true

        return false
    }
}
