package com.example.feature.privacy

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.ui.EterCard
import com.example.core.ui.SectionHeader
import com.example.core.ui.StatusPill
import com.example.ui.theme.CharcoalElevated
import com.example.ui.theme.CharcoalVoid
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.StatusWarningContainer
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacyScreen(
    uiState: PrivacyUiState,
    onRefresh: () -> Unit,
    onClearErrors: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ความเป็นส่วนตัวและการวินิจฉัย", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "รีเฟรชสถานะ")
                }
            }

            EterCard {
                SectionHeader(title = "สถานะการทำงานและความปลอดภัยแบบเรียลไทม์", icon = Icons.Rounded.Shield)
                PrivacyIndicatorRow(
                    label = "การจับภาพหน้าจอ (MediaProjection)",
                    active = uiState.permissions.isMediaProjectionActive,
                    activeText = "กำลังจับภาพ",
                    inactiveText = "ปิดอยู่"
                )
                PrivacyIndicatorRow(
                    label = "การประมวลผล Gemini AI",
                    active = uiState.settings.aiEnabled,
                    activeText = "เปิดอนุญาต",
                    inactiveText = "ปิดใช้งาน"
                )
                PrivacyIndicatorRow(
                    label = "การส่งข้อมูลผ่านเครือข่าย",
                    active = uiState.isNetworkTransmissionActive,
                    activeText = "ส่งเฉพาะข้อความ OCR เมื่อใช้ Gemini",
                    inactiveText = "ไม่มีการส่งข้อมูลออกนอกเครื่อง",
                    warnWhenActive = true
                )
                PrivacyIndicatorRow(
                    label = "การบันทึกภาพหน้าจอถาวร",
                    active = uiState.settings.saveHistoryImages,
                    activeText = "เปิดบันทึกภาพย่อ",
                    inactiveText = "ปิด (ไม่บันทึกภาพ)",
                    warnWhenActive = true
                )
            }

            EterCard {
                SectionHeader(title = "ตรวจสอบสิทธิ์ระบบและ Firebase", icon = Icons.Rounded.Security)
                DiagnosticTextRow("ปุ่มลอย (Overlay)", if (uiState.permissions.hasOverlayPermission) "อนุญาตแล้ว" else "ยังไม่อนุญาต")
                DiagnosticTextRow("ตรวจจับหยุดเลื่อน (Accessibility)", if (uiState.permissions.hasAccessibilityPermission) "เปิดใช้งาน" else "ปิด (ใช้โหมด Manual ได้)")
                DiagnosticTextRow("การแจ้งเตือน (Notifications)", if (uiState.permissions.hasNotificationPermission) "อนุญาตแล้ว" else "ยังไม่อนุญาต")
                DiagnosticTextRow("FirebaseApp", if (uiState.firebaseStatus.isFirebaseAppReady) "พร้อมใช้งาน" else "ยังไม่ได้ตั้งค่า (ออฟไลน์ใช้งานได้)")
                DiagnosticTextRow("Firebase App Check", if (uiState.firebaseStatus.isAppCheckConfigured) "ตั้งค่าแล้ว" else "ยังไม่พร้อมใช้งาน")
            }

            EterCard {
                SectionHeader(title = "โมเดลออฟไลน์ที่ติดตั้ง", icon = Icons.Rounded.DownloadDone)
                uiState.offlineModels.forEach { model ->
                    DiagnosticTextRow(
                        label = "${model.thaiName} (${model.languageCode.uppercase()})",
                        value = if (model.isDownloaded) "พร้อมใช้งานออฟไลน์" else "ยังไม่ติดตั้ง"
                    )
                }
            }

            EterCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "บันทึกข้อผิดพลาดทางเทคนิค",
                        icon = Icons.Rounded.BugReport,
                        subtitle = "ไม่บันทึกข้อความหน้าจอ ภาพ หรือข้อมูลส่วนตัว",
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.recentErrors.isNotEmpty()) {
                        TextButton(onClick = onClearErrors) {
                            Text("ล้างบันทึก", color = StatusError)
                        }
                    }
                }

                if (uiState.recentErrors.isEmpty()) {
                    Text(
                        text = "ไม่พบข้อผิดพลาดทางเทคนิคในเซสชันนี้",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusSuccess
                    )
                } else {
                    uiState.recentErrors.forEach { err ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CharcoalElevated, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "${formatter.format(Date(err.timestampMillis))} • ${err.component}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan
                            )
                            Text(
                                text = err.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PrivacyIndicatorRow(
    label: String,
    active: Boolean,
    activeText: String,
    inactiveText: String,
    warnWhenActive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val pillColor = when {
            active && warnWhenActive -> StatusWarning
            active -> StatusSuccess
            else -> ElectricCyan
        }
        val pillBg = when {
            active && warnWhenActive -> StatusWarningContainer
            active -> StatusSuccessContainer
            else -> ElectricCyanContainer
        }
        StatusPill(
            text = if (active) activeText else inactiveText,
            color = pillColor,
            containerColor = pillBg,
            showDot = active
        )
    }
}

@Composable
private fun DiagnosticTextRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
