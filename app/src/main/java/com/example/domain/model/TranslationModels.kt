package com.example.domain.model

enum class LanguageOption(
    val code: String,
    val displayThai: String,
    val shortLabel: String
) {
    AUTO("auto", "ตรวจจับอัตโนมัติ", "AUTO"),
    JAPANESE("ja", "ญี่ปุ่น (มังงะ)", "JA"),
    KOREAN("ko", "เกาหลี (มันฮวา)", "KO"),
    ENGLISH("en", "อังกฤษ", "EN"),
    THAI("th", "ไทย", "TH");

    companion object {
        fun fromCode(code: String?): LanguageOption {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class TranslationMode(val displayThai: String, val subtitleThai: String) {
    OFFLINE(
        displayThai = "ออฟไลน์เท่านั้น",
        subtitleThai = "ประมวลผลบนเครื่อง 100% ไม่ส่งข้อมูลออกเครือข่าย"
    ),
    GEMINI_AI(
        displayThai = "Gemini AI",
        subtitleThai = "แปลบริบทมังงะ/มันฮวาอย่างเป็นธรรมชาติผ่าน Firebase AI Logic"
    ),
    HYBRID(
        displayThai = "ไฮบริด (แนะนำ)",
        subtitleThai = "อ่าน OCR บนเครื่องก่อน แล้วเลือกแปลออฟไลน์หรือสลับใช้ Gemini AI"
    )
}

enum class HybridPriority(val displayThai: String) {
    OFFLINE_FIRST("ลองออฟไลน์ก่อน ถ้าไม่ได้ค่อยใช้ Gemini"),
    GEMINI_PREFERRED("เน้น Gemini AI เพื่อสำนวนมังงะ แล้วสำรองด้วยออฟไลน์")
}

enum class CaptureMode(val displayThai: String) {
    MANUAL("กดแปลเอง (Manual)"),
    AUTO("จับภาพอัตโนมัติหลังหยุดเลื่อน (Auto)"),
    BOTH("ทั้งกดเองและอัตโนมัติ")
}

enum class ReadingDirection(val displayThai: String) {
    AUTO("เลือกตามภาษาอัตโนมัติ"),
    RTL_MANGA("มังงะญี่ปุ่น (ขวาไปซ้าย)"),
    LTR_MANHWA("มันฮวาเกาหลี / ทั่วไป (ซ้ายไปขวา / บนลงล่าง)")
}

enum class TranslationProviderType(val displayThai: String) {
    OFFLINE_MLKIT("ออฟไลน์ (ML Kit)"),
    GEMINI_AI("Gemini AI"),
    CACHE("แคชบนเครื่อง"),
    UNAVAILABLE("ไม่พร้อมใช้งาน")
}

data class OcrBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Int get() = width * height
}

data class OcrBlock(
    val text: String,
    val lines: List<String>,
    val box: OcrBoundingBox,
    val detectedLanguage: String? = null,
    val isVerticalHint: Boolean = false
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val rawText: String,
    val detectedLanguage: String,
    val imageWidth: Int,
    val imageHeight: Int
)

data class MangaRegion(
    val id: String,
    val text: String,
    val isSfx: Boolean,
    val isShortExpression: Boolean,
    val box: OcrBoundingBox
)

data class MangaContextBatch(
    val regions: List<MangaRegion>,
    val formattedText: String,
    val readingDirection: ReadingDirection,
    val detectedSourceLanguage: String,
    val previousContextLines: List<String>
)

data class TranslationRequest(
    val sourceText: String,
    val sourceLanguage: LanguageOption,
    val targetLanguage: LanguageOption = LanguageOption.THAI,
    val readingDirection: ReadingDirection = ReadingDirection.AUTO,
    val previousContext: List<String> = emptyList(),
    val forceProvider: TranslationProviderType? = null
)

enum class TranslationFailureReason {
    OFFLINE_MODEL_NOT_DOWNLOADED,
    LANGUAGE_PAIR_UNAVAILABLE,
    AI_DISABLED,
    NETWORK_UNAVAILABLE,
    QUOTA_EXCEEDED,
    TIMEOUT,
    APP_CHECK_OR_SERVICE_UNAVAILABLE,
    EMPTY_INPUT,
    UNKNOWN
}

sealed interface TranslationResult {
    data class Success(
        val translatedText: String,
        val provider: TranslationProviderType,
        val detectedLanguage: String,
        val fromCache: Boolean = false,
        val note: String? = null
    ) : TranslationResult

    data class Failure(
        val reason: TranslationFailureReason,
        val userMessageThai: String,
        val technicalDetails: String
    ) : TranslationResult
}

enum class TranslationDisplayMode(
    val displayThai: String,
    val subtitleThai: String
) {
    PANEL(
        displayThai = "Panel",
        subtitleThai = "แสดงคำแปลในแผงควบคุมลอย"
    ),
    REPLACE(
        displayThai = "Replace",
        subtitleThai = "ปิดข้อความต้นฉบับและวางคำแปลทับตำแหน่งเดิม"
    )
}

enum class TranslationBackgroundTreatment(
    val displayThai: String,
    val subtitleThai: String
) {
    BUBBLE(
        displayThai = "Bubble",
        subtitleThai = "วิเคราะห์สีพื้นหลังและสร้างแผ่นปิดข้อความแบบบอลลูน"
    )
}

enum class OverlayRenderStatus(val displayThai: String) {
    IDLE("รอแสดงผล"),
    PANEL_ONLY("โหมด Panel"),
    ACTIVE_REPLACE("กำลังวางคำแปลทับหน้าจอ"),
    PARTIAL_FALLBACK("วางทับบางส่วน (ส่วนที่เหลือดูในแผงลอย)"),
    PERMISSION_REQUIRED("ต้องอนุญาตสิทธิ์หน้าต่างลอย (Overlay)"),
    SUSPENDED_FOR_CAPTURE("ซ่อนชั่วคราวระหว่างจับภาพ"),
    PAUSED("พักการวางคำแปลทับหน้าจอ"),
    FAILED("ไม่สามารถวางคำแปลทับหน้าจอ")
}

data class RegionTranslationInput(
    val id: String,
    val text: String,
    val isSfx: Boolean = false,
    val isShortExpression: Boolean = false
)

data class RegionTranslationRequest(
    val regions: List<RegionTranslationInput>,
    val sourceLanguage: LanguageOption,
    val targetLanguage: LanguageOption = LanguageOption.THAI,
    val readingDirection: ReadingDirection = ReadingDirection.AUTO,
    val previousContext: List<String> = emptyList(),
    val forceProvider: TranslationProviderType? = null
)

data class RegionTranslationItem(
    val id: String,
    val translatedText: String
)

sealed interface RegionTranslationResult {
    data class Success(
        val translationsById: Map<String, String>,
        val provider: TranslationProviderType,
        val detectedLanguage: String,
        val fromCache: Boolean = false,
        val note: String? = null
    ) : RegionTranslationResult

    data class Failure(
        val reason: TranslationFailureReason,
        val userMessageThai: String,
        val technicalDetails: String
    ) : RegionTranslationResult
}

data class TranslatedRegion(
    val id: String,
    val sourceText: String,
    val translatedText: String,
    val box: OcrBoundingBox,
    val isSfx: Boolean,
    val isShortExpression: Boolean,
    val detectedLanguage: String,
    val estimatedBackgroundArgb: Int,
    val estimatedTextArgb: Int,
    val backgroundConfidence: Float,
    val readingOrderIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int
)

data class OfflineModelInfo(
    val languageCode: String,
    val thaiName: String,
    val englishName: String,
    val isDownloaded: Boolean,
    val isDownloading: Boolean = false,
    val estimatedSizeMb: Int = 30,
    val errorMessage: String? = null
)
