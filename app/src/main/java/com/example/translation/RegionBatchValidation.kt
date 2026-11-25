package com.example.translation

import com.example.domain.model.MangaRegion
import com.example.domain.model.RegionTranslationInput
import com.example.domain.model.RegionTranslationItem
import com.example.domain.model.TranslationFailureReason
import org.json.JSONObject

internal sealed interface RegionBatchValidationResult {
    data class Valid(
        val translationsById: Map<String, String>,
        val detectedLanguage: String?,
        val note: String?
    ) : RegionBatchValidationResult

    data class Invalid(
        val reason: TranslationFailureReason,
        val userMessageThai: String,
        val technicalDetails: String
    ) : RegionBatchValidationResult
}

internal object RegionBatchValidator {

    fun validateItems(
        expectedInputs: List<RegionTranslationInput>,
        returnedItems: List<RegionTranslationItem>,
        detectedLanguage: String? = null,
        note: String? = null
    ): RegionBatchValidationResult {
        if (expectedInputs.isEmpty()) {
            return RegionBatchValidationResult.Invalid(
                reason = TranslationFailureReason.EMPTY_INPUT,
                userMessageThai = "ไม่พบพื้นที่ข้อความสำหรับแปล",
                technicalDetails = "No expected input regions"
            )
        }

        val expectedIds = expectedInputs.map { it.id }.toSet()
        val mapped = LinkedHashMap<String, String>(expectedIds.size)

        for (item in returnedItems) {
            val id = item.id.trim()
            if (id.isEmpty() || id !in expectedIds) {
                return RegionBatchValidationResult.Invalid(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "โครงสร้างผลลัพธ์จาก AI ไม่ตรงกับตำแหน่งข้อความ (พบ ID ที่ไม่รู้จัก)",
                    technicalDetails = "Unknown region id '$id'"
                )
            }
            if (mapped.containsKey(id)) {
                return RegionBatchValidationResult.Invalid(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "โครงสร้างผลลัพธ์จาก AI มี ID ซ้ำกัน จึงไม่แสดงคำแปลทับหน้าจอ",
                    technicalDetails = "Duplicate region id '$id'"
                )
            }
            val translated = item.translatedText.trim()
            if (translated.isEmpty()) {
                return RegionBatchValidationResult.Invalid(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "คำแปลบางตำแหน่งว่างเปล่า ระบบจึงยกเลิกการวางคำแปลทับ",
                    technicalDetails = "Blank translation for region '$id'"
                )
            }
            mapped[id] = translated
        }

        val missingIds = expectedIds.filter { it !in mapped }
        if (missingIds.isNotEmpty()) {
            return RegionBatchValidationResult.Invalid(
                reason = TranslationFailureReason.UNKNOWN,
                userMessageThai = "ผลลัพธ์การแปลไม่ครบทุกช่องคำพูด (${mapped.size}/${expectedIds.size})",
                technicalDetails = "Missing region ids: ${missingIds.joinToString(",")}"
            )
        }

        // Reorder map entries in the original MangaRegion reading order while preserving ID mapping
        val orderedMap = LinkedHashMap<String, String>(expectedInputs.size)
        for (input in expectedInputs) {
            orderedMap[input.id] = mapped.getValue(input.id)
        }

        return RegionBatchValidationResult.Valid(
            translationsById = orderedMap,
            detectedLanguage = detectedLanguage?.takeIf { it.isNotBlank() && it != "null" },
            note = note?.takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun parseAndValidateGeminiBatchJson(
        rawJson: String,
        expectedInputs: List<RegionTranslationInput>,
        fallbackLanguage: String
    ): RegionBatchValidationResult {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return RegionBatchValidationResult.Invalid(
                reason = TranslationFailureReason.UNKNOWN,
                userMessageThai = "Gemini AI ไม่ได้ส่งข้อมูล JSON กลับมา",
                technicalDetails = "Empty batch JSON response"
            )
        }

        return try {
            val json = JSONObject(trimmed)
            val regionsArray = json.optJSONArray("regions")
                ?: return RegionBatchValidationResult.Invalid(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "โครงสร้าง JSON จาก Gemini AI ไม่มีรายการ regions",
                    technicalDetails = "Missing 'regions' array in JSON"
                )

            val parsedItems = ArrayList<RegionTranslationItem>(regionsArray.length())
            for (i in 0 until regionsArray.length()) {
                val obj = regionsArray.optJSONObject(i)
                    ?: return RegionBatchValidationResult.Invalid(
                        reason = TranslationFailureReason.UNKNOWN,
                        userMessageThai = "ข้อมูลตำแหน่งคำแปลใน JSON ไม่ถูกต้อง",
                        technicalDetails = "Non-object entry at regions[$i]"
                    )
                val id = obj.optString("id", "")
                val text = obj.optString("translatedText", "")
                parsedItems.add(RegionTranslationItem(id = id, translatedText = text))
            }

            val detectedLang = json.optString("detectedLanguage", fallbackLanguage)
                .ifBlank { fallbackLanguage }
            val toneNote = json.optString("toneNote", "")
                .takeIf { it.isNotBlank() && it != "null" }

            validateItems(
                expectedInputs = expectedInputs,
                returnedItems = parsedItems,
                detectedLanguage = detectedLang,
                note = toneNote
            )
        } catch (e: Exception) {
            RegionBatchValidationResult.Invalid(
                reason = TranslationFailureReason.UNKNOWN,
                userMessageThai = "ไม่สามารถอ่านข้อมูล JSON การแปลเฉพาะตำแหน่งจาก Gemini AI ได้",
                technicalDetails = "Malformed JSON: ${e.javaClass.simpleName}"
            )
        }
    }

    fun formatCombinedPanelText(
        regions: List<MangaRegion>,
        translationsById: Map<String, String>
    ): String {
        return regions.mapNotNull { region ->
            val translated = translationsById[region.id]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (region.isSfx && !translated.startsWith("[SFX", ignoreCase = true)) {
                "[SFX] $translated"
            } else {
                translated
            }
        }.joinToString("\n\n")
    }
}
