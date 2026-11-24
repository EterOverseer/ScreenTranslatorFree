package com.example.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ui.EterCard
import com.example.core.ui.SectionHeader
import com.example.core.ui.StatusPill
import com.example.domain.model.CaptureStatus
import com.example.domain.model.TranslationMode
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

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRequestStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onAcknowledgeAiConsent: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNavigateToTranslator: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAiDisclosureDialog by remember { mutableStateOf(false) }

    val isActive = uiState.permissions.isMediaProjectionActive
    val settings = uiState.settings
    val downloadedCount = uiState.offlineModels.count { it.isDownloaded }

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
            // Hero Branding Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(22.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_translator),
                    contentDescription = stringResource(id = R.string.hero_banner_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    CharcoalVoid.copy(alpha = 0.25f),
                                    CharcoalVoid.copy(alpha = 0.92f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            text = if (isActive) uiState.translatorState.captureStatus.displayThai else "พร้อมเริ่มต้น",
                            color = if (isActive) StatusSuccess else ElectricCyan,
                            containerColor = if (isActive) StatusSuccessContainer else ElectricCyanContainer
                        )
                        StatusPill(
                            text = settings.translationMode.displayThai,
                            color = CobaltBlue,
                            containerColor = CharcoalVoid.copy(alpha = 0.8f),
                            showDot = false
                        )
                    }

                    Column {
                        Text(
                            text = "EterScreen Translator",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = "แปลมังงะญี่ปุ่น มันฮวาเกาหลี และหน้าจอแอปเป็นภาษาไทย",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Primary Start / Stop CTA Card
            EterCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isActive) "บริการแปลหน้าจอกำลังทำงาน" else "เริ่มการแปลหน้าจอ",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isActive) {
                                "กดปุ่มลอยบนหน้าจอหรือหยุดเลื่อนเพื่อแปลอัตโนมัติ"
                            } else {
                                "ระบบจะขอสิทธิ์จับภาพหน้าจอ MediaProjection เพื่ออ่านข้อความ"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isActive) {
                        Button(
                            onClick = {
                                val needsAiNotice = settings.aiEnabled &&
                                    settings.translationMode != TranslationMode.OFFLINE &&
                                    !settings.aiDataNoticeAccepted
                                if (needsAiNotice) {
                                    showAiDisclosureDialog = true
                                } else {
                                    onRequestStartCapture()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("start_translation_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = CharcoalVoid
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("เริ่มแปลหน้าจอ", style = MaterialTheme.typography.titleSmall)
                        }
                    } else {
                        Button(
                            onClick = onStopCapture,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_translation_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusErrorContainer,
                                contentColor = StatusError
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("หยุดแปลหน้าจอ", style = MaterialTheme.typography.titleSmall)
                        }
                        FilledTonalButton(
                            onClick = onNavigateToTranslator,
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("open_translator_control_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Translate, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ควบคุม")
                        }
                    }
                }
            }

            // Engine & Language Summary
            EterCard {
                SectionHeader(
                    title = "สถานะระบบแปลและภาษา",
                    icon = Icons.Rounded.AutoAwesome
                )
                SummaryRow(
                    label = "ภาษาต้นทาง → ปลายทาง",
                    value = "${settings.sourceLanguage.displayThai} → ${settings.targetLanguage.displayThai}"
                )
                SummaryRow(
                    label = "โหมดการจับภาพ",
                    value = settings.captureMode.displayThai
                )
                SummaryRow(
                    label = "โมเดลออฟไลน์ (ML Kit)",
                    value = "ติดตั้งแล้ว $downloadedCount/${uiState.offlineModels.size} ภาษา"
                )
                SummaryRow(
                    label = "Gemini AI (Firebase AI Logic)",
                    value = when {
                        !settings.aiEnabled -> "ปิดใช้งาน (ออฟไลน์ล้วน)"
                        settings.translationMode == TranslationMode.OFFLINE -> "เปิดไว้ แต่ขณะนี้ใช้โหมดออฟไลน์"
                        else -> "พร้อมใช้งานผ่าน App Check"
                    }
                )
            }

            // Permissions Status Card
            EterCard {
                SectionHeader(
                    title = "สิทธิ์ที่จำเป็นและการเข้าถึง",
                    icon = Icons.Rounded.Shield,
                    subtitle = "โหมดกดแปลเองใช้งานได้แม้ยังไม่เปิด Accessibility"
                )
                PermissionStatusRow(
                    icon = Icons.Rounded.CameraAlt,
                    title = "จับภาพหน้าจอ (MediaProjection)",
                    subtitle = if (uiState.permissions.isMediaProjectionActive) "กำลังทำงาน" else "ขอสิทธิ์เมื่อกดเริ่มแปล",
                    granted = uiState.permissions.isMediaProjectionActive,
                    actionLabel = null,
                    testTag = "perm_mediaprojection",
                    onAction = {}
                )
                PermissionStatusRow(
                    icon = Icons.Rounded.Layers,
                    title = "หน้าต่างลอย (Overlay)",
                    subtitle = if (uiState.permissions.hasOverlayPermission) "อนุญาตแล้ว" else "จำเป็นสำหรับปุ่มลอยบนแอปอื่น",
                    granted = uiState.permissions.hasOverlayPermission,
                    actionLabel = if (uiState.permissions.hasOverlayPermission) null else "อนุญาต",
                    testTag = "grant_overlay_button",
                    onAction = onRequestOverlayPermission
                )
                PermissionStatusRow(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = "ตรวจจับหยุดเลื่อน (Accessibility - ตัวเลือกเสริม)",
                    subtitle = if (uiState.permissions.hasAccessibilityPermission) "เปิดใช้งาน Auto-Scroll แล้ว" else "เปิดเพื่อแปลอัตโนมัติหลังหยุดเลื่อน",
                    granted = uiState.permissions.hasAccessibilityPermission,
                    actionLabel = if (uiState.permissions.hasAccessibilityPermission) null else "ตั้งค่า",
                    testTag = "grant_accessibility_button",
                    onAction = onRequestAccessibilityPermission
                )
                if (!uiState.permissions.hasNotificationPermission) {
                    PermissionStatusRow(
                        icon = Icons.Rounded.Notifications,
                        title = "การแจ้งเตือน Foreground Service",
                        subtitle = "จำเป็นสำหรับแสดงปุ่มหยุดด่วนบนแถบแจ้งเตือน",
                        granted = false,
                        actionLabel = "อนุญาต",
                        testTag = "grant_notification_button",
                        onAction = onRequestNotificationPermission
                    )
                }
            }

            // Quick Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("quick_history_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ประวัติการแปล")
                }
                OutlinedButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("quick_settings_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ตั้งค่า")
                }
            }

            TextButton(
                onClick = onNavigateToPrivacy,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("quick_privacy_button")
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ตรวจสอบความเป็นส่วนตัวและบันทึกเทคนิค")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showAiDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showAiDisclosureDialog = false },
            title = { Text("แจ้งเตือนการส่งข้อความไปยัง Gemini AI") },
            text = {
                Text(
                    "ขณะนี้คุณเปิดใช้งานโหมดที่มี Gemini AI ระบบจะอ่านข้อความบนหน้าจอ (OCR) บนเครื่องก่อน แล้วส่งเฉพาะข้อความที่ตรวจจับได้ไปยังบริการ Google Gemini ผ่าน Firebase AI Logic เพื่อแปลภาษา\n\nหากต้องการให้ข้อมูลอยู่บนเครื่อง 100% คุณสามารถสลับเป็น 'โหมดออฟไลน์' หรือปิด AI ได้ในหน้าตั้งค่า"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAiDisclosureDialog = false
                        onAcknowledgeAiConsent()
                        onRequestStartCapture()
                    }
                ) {
                    Text("รับทราบและเริ่มแปล")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiDisclosureDialog = false }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CharcoalElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(0.48f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.52f)
        )
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String?,
    testTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CharcoalElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) StatusSuccess else StatusWarning,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        if (actionLabel != null) {
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.testTag(testTag)
            ) {
                Text(actionLabel)
            }
        } else {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = if (granted) "อนุญาตแล้ว" else "ยังไม่ได้เริ่ม",
                tint = if (granted) StatusSuccess else StatusWarningContainer.copy(alpha = 1f).let { StatusWarning },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
