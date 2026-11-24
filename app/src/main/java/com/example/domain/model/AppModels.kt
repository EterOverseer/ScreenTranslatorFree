package com.example.domain.model

data class AppSettings(
    val sourceLanguage: LanguageOption = LanguageOption.AUTO,
    val targetLanguage: LanguageOption = LanguageOption.THAI,
    val translationMode: TranslationMode = TranslationMode.HYBRID,
    val hybridPriority: HybridPriority = HybridPriority.OFFLINE_FIRST,
    val captureMode: CaptureMode = CaptureMode.BOTH,
    val readingDirection: ReadingDirection = ReadingDirection.AUTO,
    val autoCaptureDelayMs: Long = 1500L,
    val cooldownMs: Long = 2500L,
    val aiEnabled: Boolean = true,
    val aiDataNoticeAccepted: Boolean = false,
    val overlayEnabled: Boolean = true,
    val bubbleSizeDp: Float = 56f,
    val bubbleOpacity: Float = 0.92f,
    val bubbleX: Int = 24,
    val bubbleY: Int = 360,
    val saveHistoryImages: Boolean = false,
    val customMangaPromptNote: String = ""
)

data class TranslationHistoryItem(
    val id: Long = 0L,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val provider: TranslationProviderType,
    val timestampMillis: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)

data class DiagnosticErrorEntry(
    val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val component: String,
    val message: String
)

enum class CaptureStatus(val displayThai: String) {
    IDLE("ไม่ได้จับภาพ"),
    REQUESTING_PERMISSION("กำลังขอสิทธิ์จับภาพ"),
    ACTIVE("พร้อมจับภาพหน้าจอ"),
    PAUSED("พักการจับภาพชั่วคราว"),
    CAPTURING("กำลังจับภาพหน้าจอ..."),
    ERROR("เกิดข้อผิดพลาด")
}

data class TranslatorState(
    val captureStatus: CaptureStatus = CaptureStatus.IDLE,
    val isOcrRunning: Boolean = false,
    val isTranslating: Boolean = false,
    val lastOcrText: String = "",
    val editableSourceText: String = "",
    val lastTranslatedText: String = "",
    val activeProvider: TranslationProviderType? = null,
    val lastDetectedLanguage: String = "",
    val lastErrorThai: String? = null,
    val isAutoScrollServiceConnected: Boolean = false,
    val isOverlayShowing: Boolean = false,
    val lastCaptureTimestamp: Long = 0L,
    val lastSkippedReason: String? = null
)
