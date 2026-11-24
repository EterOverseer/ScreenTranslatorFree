package com.example.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.ui.EterCard
import com.example.core.ui.SectionHeader
import com.example.domain.model.CaptureMode
import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationMode
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalVoid
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusErrorContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSetSourceLanguage: (LanguageOption) -> Unit,
    onSetTranslationMode: (TranslationMode) -> Unit,
    onSetHybridPriority: (HybridPriority) -> Unit,
    onSetCaptureMode: (CaptureMode) -> Unit,
    onSetReadingDirection: (ReadingDirection) -> Unit,
    onSetAutoDelayMs: (Long) -> Unit,
    onSetCooldownMs: (Long) -> Unit,
    onSetAiEnabled: (Boolean) -> Unit,
    onSetCustomNote: (String) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onSetBubbleSize: (Float) -> Unit,
    onSetBubbleOpacity: (Float) -> Unit,
    onResetBubblePosition: () -> Unit,
    onSetSaveHistoryImages: (Boolean) -> Unit,
    onDownloadModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onClearCache: () -> Unit,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    var showResetDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalVoid),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("ตั้งค่าแอปพลิเคชัน", style = MaterialTheme.typography.headlineSmall)

            // 1. Languages & Reading Order
            EterCard {
                SectionHeader(title = "ภาษาและลำดับการอ่าน", icon = Icons.Rounded.Language)
                Text("ภาษาต้นฉบับ", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LanguageOption.AUTO,
                        LanguageOption.JAPANESE,
                        LanguageOption.KOREAN,
                        LanguageOption.ENGLISH
                    ).forEach { lang ->
                        FilterChip(
                            selected = settings.sourceLanguage == lang,
                            onClick = { onSetSourceLanguage(lang) },
                            label = { Text(lang.displayThai) }
                        )
                    }
                }
                Text("ภาษาปลายทาง: ภาษาไทย (ค่าเริ่มต้น)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("การจัดเรียงบทสนทนา", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingDirection.entries.forEach { dir ->
                        FilterChip(
                            selected = settings.readingDirection == dir,
                            onClick = { onSetReadingDirection(dir) },
                            label = { Text(dir.displayThai) }
                        )
                    }
                }
            }

            // 2. Translation Mode & Gemini AI
            EterCard {
                SectionHeader(title = "โหมดการแปลและ Gemini AI", icon = Icons.Rounded.AutoAwesome)
                SwitchRow(
                    title = "เปิดใช้งาน Gemini AI (Firebase AI Logic)",
                    subtitle = if (settings.aiEnabled) {
                        "ส่งเฉพาะข้อความ OCR ไปแปลเมื่อเลือกโหมด AI หรือ Hybrid"
                    } else {
                        "ปิด AI สมบูรณ์ ระบบจะไม่ส่งข้อมูลออกนอกเครื่อง"
                    },
                    checked = settings.aiEnabled,
                    testTag = "ai_enabled_switch",
                    onCheckedChange = onSetAiEnabled
                )

                Text("โหมดการทำงาน", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationMode.entries.forEach { mode ->
                        val enabled = mode != TranslationMode.GEMINI_AI || settings.aiEnabled
                        FilterChip(
                            selected = settings.translationMode == mode,
                            enabled = enabled,
                            onClick = { onSetTranslationMode(mode) },
                            label = { Text(mode.displayThai) }
                        )
                    }
                }

                if (settings.translationMode == TranslationMode.HYBRID && settings.aiEnabled) {
                    Text("ลำดับความสำคัญโหมดไฮบริด", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HybridPriority.entries.forEach { p ->
                            FilterChip(
                                selected = settings.hybridPriority == p,
                                onClick = { onSetHybridPriority(p) },
                                label = { Text(p.displayThai) }
                            )
                        }
                    }
                }

                if (settings.aiEnabled) {
                    OutlinedTextField(
                        value = settings.customMangaPromptNote,
                        onValueChange = onSetCustomNote,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("คำแนะนำชื่อตัวละคร/สำนวนเฉพาะเรื่อง (เข้ารหัสในเครื่อง)") },
                        placeholder = { Text("เช่น เรียกตัวเอกว่า 'ท่านพี่', สำนวนแฟนตาซี") },
                        maxLines = 3
                    )
                    uiState.cryptoErrorMessage?.let { errMsg ->
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError
                        )
                    }
                }
            }

            // 3. Offline ML Kit Model Management
            EterCard {
                SectionHeader(
                    title = "จัดการโมเดลแปลออฟไลน์ (ML Kit)",
                    icon = Icons.Rounded.Download,
                    subtitle = "ต้องดาวน์โหลดทั้งภาษาต้นทาง (JA/KO/EN) และภาษาไทย (TH) จึงจะแปลออฟไลน์ได้"
                )
                uiState.offlineModels.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CharcoalElevated, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${model.thaiName} (${model.languageCode.uppercase()})", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = when {
                                    model.isDownloading -> "กำลังดาวน์โหลด (~${model.estimatedSizeMb} MB)..."
                                    model.isDownloaded -> "ติดตั้งบนเครื่องแล้ว (~${model.estimatedSizeMb} MB)"
                                    !model.errorMessage.isNullOrBlank() -> model.errorMessage
                                    else -> "ยังไม่ได้ดาวน์โหลด (~${model.estimatedSizeMb} MB)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (model.isDownloaded) StatusSuccess else TextSecondary
                            )
                        }
                        when {
                            model.isDownloading -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                            model.isDownloaded -> {
                                IconButton(onClick = { onDeleteModel(model.languageCode) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "ลบโมเดล", tint = StatusError)
                                }
                            }
                            else -> {
                                FilledTonalButton(onClick = { onDownloadModel(model.languageCode) }) {
                                    Text("ดาวน์โหลด")
                                }
                            }
                        }
                    }
                }
            }

            // 4. Auto Capture Timing
            EterCard {
                SectionHeader(title = "การจับภาพและหน่วงเวลาหลังหยุดเลื่อน", icon = Icons.Rounded.Timer)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.captureMode == mode,
                            onClick = { onSetCaptureMode(mode) },
                            label = { Text(mode.displayThai) }
                        )
                    }
                }

                val delaySec = (settings.autoCaptureDelayMs / 100f).roundToInt() / 10f
                Text("รอหลังหยุดเลื่อน: $delaySec วินาที (1.0 - 5.0 วินาที)", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.autoCaptureDelayMs.toFloat(),
                    onValueChange = { onSetAutoDelayMs((it / 500f).roundToInt() * 500L) },
                    valueRange = 1000f..5000f,
                    steps = 7
                )

                val cooldownSec = (settings.cooldownMs / 100f).roundToInt() / 10f
                Text("ระยะพักขั้นต่ำ (Cooldown): $cooldownSec วินาที", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.cooldownMs.toFloat(),
                    onValueChange = { onSetCooldownMs((it / 500f).roundToInt() * 500L) },
                    valueRange = 1000f..10000f,
                    steps = 17
                )
            }

            // 5. Overlay Appearance
            EterCard {
                SectionHeader(title = "หน้าต่างลอย (Floating Overlay)", icon = Icons.Rounded.Layers)
                SwitchRow(
                    title = "แสดงปุ่มลอยบนหน้าจอ",
                    subtitle = "ปิดเพื่อซ่อนปุ่มลอยทั้งหมดทันที",
                    checked = settings.overlayEnabled,
                    testTag = "overlay_enabled_switch",
                    onCheckedChange = onSetOverlayEnabled
                )
                Text("ขนาดปุ่มลอย: ${settings.bubbleSizeDp.roundToInt()} dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.bubbleSizeDp,
                    onValueChange = onSetBubbleSize,
                    valueRange = 44f..80f
                )
                Text("ความโปร่งใส: ${(settings.bubbleOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.bubbleOpacity,
                    onValueChange = onSetBubbleOpacity,
                    valueRange = 0.35f..1.0f
                )
                OutlinedButton(onClick = onResetBubblePosition) {
                    Text("รีเซ็ตตำแหน่งปุ่มลอย")
                }
            }

            // 6. Privacy & History Retention
            EterCard {
                SectionHeader(title = "ความเป็นส่วนตัวและการจัดเก็บ", icon = Icons.Rounded.Security)
                SwitchRow(
                    title = "บันทึกภาพหน้าจอย่อในประวัติ (ปิดไว้เป็นค่าเริ่มต้น)",
                    subtitle = "แนะนำให้ปิดเพื่อประหยัดพื้นที่และความเป็นส่วนตัว",
                    checked = settings.saveHistoryImages,
                    testTag = "save_images_switch",
                    onCheckedChange = onSetSaveHistoryImages
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onClearCache,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ล้างแคชคำแปล")
                    }
                    Button(
                        onClick = { showResetDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reset_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusErrorContainer,
                            contentColor = StatusError
                        )
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("รีเซ็ตการตั้งค่า")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("รีเซ็ตการตั้งค่าทั้งหมด?") },
            text = { Text("การตั้งค่าภาษา โหมดแปล ปุ่มลอย และแคชจะกลับสู่ค่าเริ่มต้น") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusErrorContainer, contentColor = StatusError)
                ) {
                    Text("รีเซ็ต")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("ยกเลิก") }
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
