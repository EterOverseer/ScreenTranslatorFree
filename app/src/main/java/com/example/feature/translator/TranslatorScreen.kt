package com.example.feature.translator

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.ui.EterCard
import com.example.core.ui.SectionHeader
import com.example.core.ui.StatusPill
import com.example.domain.model.CaptureMode
import com.example.domain.model.CaptureStatus
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationProviderType
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalVoid
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusErrorContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.StatusWarningContainer
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslatorScreen(
    uiState: TranslatorUiState,
    onRequestStartCapture: () -> Unit,
    onManualCapture: () -> Unit,
    onPauseCapture: () -> Unit,
    onResumeCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onCancelJob: () -> Unit,
    onSelectCaptureMode: (CaptureMode) -> Unit,
    onSelectReadingDirection: (ReadingDirection) -> Unit,
    onEditSourceText: (String) -> Unit,
    onRetranslateEdited: (TranslationProviderType?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state = uiState.translatorState
    val settings = uiState.settings
    val isCaptureActive = uiState.permissions.isMediaProjectionActive
    val isBusy = state.isOcrRunning || state.isTranslating

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
            // Live Status & Capture Controls
            EterCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "ตัวควบคุมการแปลหน้าจอ",
                        icon = Icons.Rounded.Translate,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(
                        text = state.captureStatus.displayThai,
                        color = when (state.captureStatus) {
                            CaptureStatus.ACTIVE, CaptureStatus.CAPTURING -> StatusSuccess
                            CaptureStatus.PAUSED -> StatusWarning
                            CaptureStatus.ERROR -> StatusError
                            else -> ElectricCyan
                        },
                        containerColor = when (state.captureStatus) {
                            CaptureStatus.ACTIVE, CaptureStatus.CAPTURING -> StatusSuccessContainer
                            CaptureStatus.PAUSED -> StatusWarningContainer
                            CaptureStatus.ERROR -> StatusErrorContainer
                            else -> ElectricCyanContainer
                        }
                    )
                }

                if (isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ElectricCyan
                    )
                    Text(
                        text = if (state.isOcrRunning) "กำลังสกัดข้อความด้วย OCR..." else "กำลังแปลภาษาไทย...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }

                if (!state.lastErrorThai.isNullOrBlank()) {
                    EterCard(
                        containerColor = StatusErrorContainer,
                        borderColor = StatusError.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = state.lastErrorThai,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextError
                        )
                    }
                } else if (!state.lastSkippedReason.isNullOrBlank()) {
                    Text(
                        text = state.lastSkippedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isCaptureActive) {
                        Button(
                            onClick = onRequestStartCapture,
                            modifier = Modifier.testTag("translator_start_capture_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = CharcoalVoid
                            )
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("เปิดจับภาพหน้าจอ")
                        }
                    } else {
                        Button(
                            onClick = onManualCapture,
                            enabled = !isBusy,
                            modifier = Modifier.testTag("manual_capture_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = CharcoalVoid
                            )
                        ) {
                            Icon(Icons.Rounded.Screenshot, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("จับภาพและแปลทันที")
                        }

                        if (state.captureStatus == CaptureStatus.PAUSED) {
                            FilledTonalButton(onClick = onResumeCapture) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ทำต่อ")
                            }
                        } else {
                            FilledTonalButton(onClick = onPauseCapture) {
                                Icon(Icons.Rounded.Pause, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("พักชั่วคราว")
                            }
                        }

                        OutlinedButton(onClick = onStopCapture) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("หยุด")
                        }
                    }

                    if (isBusy) {
                        OutlinedButton(onClick = onCancelJob) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ยกเลิกงาน")
                        }
                    }
                }
            }

            // Capture & Reading Order Selector
            EterCard {
                SectionHeader(
                    title = "โหมดจับภาพและลำดับการอ่าน",
                    icon = Icons.Rounded.MenuBook
                )
                Text("โหมดการจับภาพ", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.captureMode == mode,
                            onClick = { onSelectCaptureMode(mode) },
                            label = { Text(mode.displayThai) }
                        )
                    }
                }

                Text("ลำดับช่องคำพูด (Manga / Manhwa)", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingDirection.entries.forEach { dir ->
                        FilterChip(
                            selected = settings.readingDirection == dir,
                            onClick = { onSelectReadingDirection(dir) },
                            label = { Text(dir.displayThai) }
                        )
                    }
                }
            }

            // Thai Translation Output Card
            EterCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("คำแปลภาษาไทย", style = MaterialTheme.typography.titleMedium, color = ElectricCyan)
                        val providerLabel = state.activeProvider?.displayThai ?: "รอการแปล"
                        val langLabel = state.lastDetectedLanguage.takeIf { it.isNotBlank() }?.uppercase() ?: "-"
                        Text(
                            text = "ผู้ให้บริการ: $providerLabel • ภาษาที่ตรวจพบ: $langLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = {
                            if (state.lastTranslatedText.isNotBlank()) {
                                clipboard.setText(AnnotatedString(state.lastTranslatedText))
                                Toast.makeText(context, "คัดลอกคำแปลภาษาไทยแล้ว", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("copy_thai_button")
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "คัดลอกคำแปลภาษาไทย")
                    }
                }

                Text(
                    text = state.lastTranslatedText.ifBlank {
                        "ยังไม่มีผลลัพธ์ — กด 'จับภาพและแปลทันที' หรือพิมพ์ข้อความต้นฉบับด้านล่างเพื่อทดสอบแปล"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CharcoalElevated, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                )
            }

            // Editable OCR Source Text Card
            EterCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "แก้ไขข้อความ OCR ก่อนแปลใหม่",
                        icon = Icons.Rounded.Edit,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (state.editableSourceText.isNotBlank()) {
                                clipboard.setText(AnnotatedString(state.editableSourceText))
                                Toast.makeText(context, "คัดลอกข้อความต้นฉบับแล้ว", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("copy_source_button")
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "คัดลอกข้อความต้นฉบับ")
                    }
                }

                OutlinedTextField(
                    value = state.editableSourceText,
                    onValueChange = onEditSourceText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editable_ocr_input"),
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text("วางหรือแก้ไขข้อความญี่ปุ่น / เกาหลี / อังกฤษ...") }
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onRetranslateEdited(null) },
                        enabled = !isBusy && state.editableSourceText.isNotBlank(),
                        modifier = Modifier.testTag("retranslate_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("แปลตามโหมดปัจจุบัน")
                    }

                    if (settings.aiEnabled) {
                        FilledTonalButton(
                            onClick = { onRetranslateEdited(TranslationProviderType.GEMINI_AI) },
                            enabled = !isBusy && state.editableSourceText.isNotBlank(),
                            modifier = Modifier.testTag("retranslate_gemini_button")
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ลองแปลด้วย Gemini")
                        }
                    }

                    OutlinedButton(
                        onClick = { onRetranslateEdited(TranslationProviderType.OFFLINE_MLKIT) },
                        enabled = !isBusy && state.editableSourceText.isNotBlank(),
                        modifier = Modifier.testTag("retranslate_offline_button")
                    ) {
                        Text("ลองแปลออฟไลน์")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private val ColorTextError = androidx.compose.ui.graphics.Color(0xFFFCA5A5)
