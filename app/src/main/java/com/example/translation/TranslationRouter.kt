package com.example.translation

import com.example.domain.model.HybridPriority
import com.example.domain.model.RegionTranslationRequest
import com.example.domain.model.RegionTranslationResult
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import kotlinx.coroutines.CancellationException

class TranslationRouter(
    private val offlineEngine: TranslationEngine,
    private val geminiEngine: TranslationEngine,
    private val maxCacheEntries: Int = 64
) {
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, TranslationResult.Success>(
        maxCacheEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TranslationResult.Success>?
        ): Boolean = size > maxCacheEntries
    }

    private val regionCache = object : LinkedHashMap<String, RegionTranslationResult.Success>(
        maxCacheEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, RegionTranslationResult.Success>?
        ): Boolean = size > maxCacheEntries
    }

    suspend fun translate(
        request: TranslationRequest,
        mode: TranslationMode,
        hybridPriority: HybridPriority,
        aiEnabled: Boolean,
        bypassCache: Boolean = false
    ): TranslationResult {
        val trimmed = request.sourceText.trim()
        if (trimmed.isEmpty()) {
            return TranslationResult.Failure(
                reason = TranslationFailureReason.EMPTY_INPUT,
                userMessageThai = "ไม่พบข้อความสำหรับแปล",
                technicalDetails = "Empty request text"
            )
        }

        request.forceProvider?.let { forced ->
            return when (forced) {
                TranslationProviderType.OFFLINE_MLKIT -> offlineEngine.translate(request)
                TranslationProviderType.GEMINI_AI -> {
                    if (!aiEnabled) {
                        TranslationResult.Failure(
                            reason = TranslationFailureReason.AI_DISABLED,
                            userMessageThai = "ระบบปิดการใช้งาน Gemini AI อยู่ กรุณาเปิดใช้งานในหน้าตั้งค่า",
                            technicalDetails = "AI disabled by user"
                        )
                    } else {
                        geminiEngine.translate(request)
                    }
                }
                else -> offlineEngine.translate(request)
            }
        }

        val effectiveMode = if (!aiEnabled && mode == TranslationMode.GEMINI_AI) {
            return TranslationResult.Failure(
                reason = TranslationFailureReason.AI_DISABLED,
                userMessageThai = "ระบบปิดการใช้งาน Gemini AI อยู่ (โหมด AI ถูกปิดใช้งาน)",
                technicalDetails = "Gemini mode requested while aiEnabled=false"
            )
        } else {
            mode
        }

        val cacheKey = buildCacheKey(request, effectiveMode, hybridPriority, aiEnabled)
        if (!bypassCache) {
            val cached = synchronized(cacheLock) { cache[cacheKey] }
            if (cached != null) {
                return cached.copy(
                    fromCache = true,
                    note = "โหลดจากแคชบนเครื่อง (${cached.provider.displayThai})"
                )
            }
        }

        val result = try {
            when (effectiveMode) {
                TranslationMode.OFFLINE -> offlineEngine.translate(request)
                TranslationMode.GEMINI_AI -> geminiEngine.translate(request)
                TranslationMode.HYBRID -> routeHybrid(request, hybridPriority, aiEnabled)
            }
        } catch (e: CancellationException) {
            throw e
        }

        if (result is TranslationResult.Success) {
            synchronized(cacheLock) {
                cache[cacheKey] = result.copy(fromCache = false)
            }
        }
        return result
    }

    private suspend fun routeHybrid(
        request: TranslationRequest,
        priority: HybridPriority,
        aiEnabled: Boolean
    ): TranslationResult {
        if (!aiEnabled) {
            return offlineEngine.translate(request)
        }

        return when (priority) {
            HybridPriority.OFFLINE_FIRST -> {
                val offlineResult = offlineEngine.translate(request)
                if (offlineResult is TranslationResult.Success) {
                    offlineResult
                } else {
                    val geminiResult = geminiEngine.translate(request)
                    if (geminiResult is TranslationResult.Success) {
                        geminiResult.copy(
                            note = "สลับมาใช้ Gemini AI อัตโนมัติ (ออฟไลน์ยังไม่พร้อม)"
                        )
                    } else {
                        geminiResult
                    }
                }
            }
            HybridPriority.GEMINI_PREFERRED -> {
                val geminiResult = geminiEngine.translate(request)
                if (geminiResult is TranslationResult.Success) {
                    geminiResult
                } else {
                    val offlineFallback = offlineEngine.translate(request)
                    if (offlineFallback is TranslationResult.Success) {
                        offlineFallback.copy(
                            note = "ใช้การแปลออฟไลน์สำรองเนื่องจาก Gemini AI ไม่พร้อมใช้งาน"
                        )
                    } else {
                        geminiResult
                    }
                }
            }
        }
    }

    suspend fun translateRegions(
        request: RegionTranslationRequest,
        mode: TranslationMode,
        hybridPriority: HybridPriority,
        aiEnabled: Boolean,
        bypassCache: Boolean = false
    ): RegionTranslationResult {
        if (request.regions.isEmpty() || request.regions.all { it.text.isBlank() }) {
            return RegionTranslationResult.Failure(
                reason = TranslationFailureReason.EMPTY_INPUT,
                userMessageThai = "ไม่พบข้อความสำหรับแปล",
                technicalDetails = "Empty region batch"
            )
        }

        request.forceProvider?.let { forced ->
            return when (forced) {
                TranslationProviderType.OFFLINE_MLKIT -> offlineEngine.translateRegions(request)
                TranslationProviderType.GEMINI_AI -> {
                    if (!aiEnabled) {
                        RegionTranslationResult.Failure(
                            reason = TranslationFailureReason.AI_DISABLED,
                            userMessageThai = "ระบบปิดการใช้งาน Gemini AI อยู่ กรุณาเปิดใช้งานในหน้าตั้งค่า",
                            technicalDetails = "AI disabled by user"
                        )
                    } else {
                        geminiEngine.translateRegions(request)
                    }
                }
                else -> offlineEngine.translateRegions(request)
            }
        }

        if (!aiEnabled && mode == TranslationMode.GEMINI_AI) {
            return RegionTranslationResult.Failure(
                reason = TranslationFailureReason.AI_DISABLED,
                userMessageThai = "ระบบปิดการใช้งาน Gemini AI อยู่ (โหมด AI ถูกปิดใช้งาน)",
                technicalDetails = "Gemini mode requested while aiEnabled=false"
            )
        }

        val cacheKey = buildRegionCacheKey(request, mode, hybridPriority, aiEnabled)
        if (!bypassCache) {
            val cached = synchronized(cacheLock) { regionCache[cacheKey] }
            if (cached != null) {
                return cached.copy(
                    fromCache = true,
                    note = "โหลดจากแคชบนเครื่อง (${cached.provider.displayThai})"
                )
            }
        }

        val result = try {
            when (mode) {
                TranslationMode.OFFLINE -> offlineEngine.translateRegions(request)
                TranslationMode.GEMINI_AI -> geminiEngine.translateRegions(request)
                TranslationMode.HYBRID -> routeHybridRegions(request, hybridPriority, aiEnabled)
            }
        } catch (e: CancellationException) {
            throw e
        }

        if (result is RegionTranslationResult.Success) {
            synchronized(cacheLock) {
                regionCache[cacheKey] = result.copy(fromCache = false)
            }
        }
        return result
    }

    private suspend fun routeHybridRegions(
        request: RegionTranslationRequest,
        priority: HybridPriority,
        aiEnabled: Boolean
    ): RegionTranslationResult {
        if (!aiEnabled) {
            return offlineEngine.translateRegions(request)
        }

        return when (priority) {
            HybridPriority.OFFLINE_FIRST -> {
                val offlineResult = offlineEngine.translateRegions(request)
                if (offlineResult is RegionTranslationResult.Success) {
                    offlineResult
                } else {
                    val geminiResult = geminiEngine.translateRegions(request)
                    if (geminiResult is RegionTranslationResult.Success) {
                        geminiResult.copy(
                            note = "สลับมาใช้ Gemini AI อัตโนมัติ (ออฟไลน์ยังไม่พร้อม)"
                        )
                    } else {
                        geminiResult
                    }
                }
            }
            HybridPriority.GEMINI_PREFERRED -> {
                val geminiResult = geminiEngine.translateRegions(request)
                if (geminiResult is RegionTranslationResult.Success) {
                    geminiResult
                } else {
                    val offlineFallback = offlineEngine.translateRegions(request)
                    if (offlineFallback is RegionTranslationResult.Success) {
                        offlineFallback.copy(
                            note = "ใช้การแปลออฟไลน์สำรองเนื่องจาก Gemini AI ไม่พร้อมใช้งาน"
                        )
                    } else {
                        geminiResult
                    }
                }
            }
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            regionCache.clear()
        }
    }

    private fun buildCacheKey(
        request: TranslationRequest,
        mode: TranslationMode,
        priority: HybridPriority,
        aiEnabled: Boolean
    ): String {
        return listOf(
            request.sourceLanguage.code,
            request.targetLanguage.code,
            mode.name,
            if (mode == TranslationMode.HYBRID) priority.name else "",
            aiEnabled.toString(),
            request.sourceText.trim()
        ).joinToString("|")
    }

    private fun buildRegionCacheKey(
        request: RegionTranslationRequest,
        mode: TranslationMode,
        priority: HybridPriority,
        aiEnabled: Boolean
    ): String {
        val regionsKey = request.regions.joinToString(";") { "${it.id}:${it.text.trim()}" }
        return listOf(
            "regions",
            request.sourceLanguage.code,
            request.targetLanguage.code,
            mode.name,
            if (mode == TranslationMode.HYBRID) priority.name else "",
            aiEnabled.toString(),
            regionsKey
        ).joinToString("|")
    }
}
