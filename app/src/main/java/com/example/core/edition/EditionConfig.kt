package com.example.core.edition

import com.example.BuildConfig
import com.example.domain.model.CaptureMode

object EditionConfig {
    val flavorName: String = BuildConfig.FLAVOR
    val editionDisplayName: String = BuildConfig.EDITION_NAME
    val isAutoScrollSupported: Boolean = BuildConfig.AUTO_SCROLL_ENABLED

    fun normalizeCaptureMode(requested: CaptureMode): CaptureMode {
        return if (isAutoScrollSupported) requested else CaptureMode.MANUAL
    }
}
