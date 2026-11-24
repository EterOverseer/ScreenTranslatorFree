package com.example.feature.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.ui.EterCard
import com.example.core.ui.StatusPill
import com.example.domain.model.TranslationHistoryItem
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
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onSearchChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onDeleteItem: (TranslationHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showClearConfirm by remember { mutableStateOf(false) }

    val filters = listOf(
        "all" to "ทั้งหมด",
        "ja" to "ญี่ปุ่น (JA)",
        "ko" to "เกาหลี (KO)",
        "en" to "อังกฤษ (EN)"
    )

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ประวัติการแปล (${uiState.items.size})", style = MaterialTheme.typography.headlineSmall)
                if (uiState.items.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("clear_all_history_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = StatusError)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ล้างทั้งหมด", color = StatusError)
                    }
                }
            }

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("ค้นหาคำแปลหรือข้อความต้นฉบับ...") }
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { (code, label) ->
                    FilterChip(
                        selected = uiState.languageFilter == code,
                        onClick = { onFilterChange(code) },
                        label = { Text(label) }
                    )
                }
            }

            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = ElectricCyan.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text("ยังไม่มีประวัติการแปล", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ข้อความที่แปลแล้วจะถูกบันทึกไว้ในเครื่องของคุณเท่านั้น",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        HistoryItemCard(
                            item = item,
                            onCopySource = {
                                clipboard.setText(AnnotatedString(item.sourceText))
                                Toast.makeText(context, "คัดลอกต้นฉบับแล้ว", Toast.LENGTH_SHORT).show()
                            },
                            onCopyTranslated = {
                                clipboard.setText(AnnotatedString(item.translatedText))
                                Toast.makeText(context, "คัดลอกคำแปลไทยแล้ว", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("ล้างประวัติการแปลทั้งหมด?") },
            text = { Text("รายการประวัติการแปลทั้งหมดในเครื่องจะถูกลบอย่างถาวร") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusErrorContainer, contentColor = StatusError)
                ) {
                    Text("ลบทั้งหมด")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("ยกเลิก") }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    item: TranslationHistoryItem,
    onCopySource: () -> Unit,
    onCopyTranslated: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val timeLabel = formatter.format(Date(item.timestampMillis))
    val isGemini = item.provider == TranslationProviderType.GEMINI_AI

    EterCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = "${item.sourceLanguageCode.uppercase()} → ${item.targetLanguageCode.uppercase()}",
                    color = ElectricCyan,
                    containerColor = ElectricCyanContainer,
                    showDot = false
                )
                StatusPill(
                    text = item.provider.displayThai,
                    color = if (isGemini) CobaltBlue else StatusSuccess,
                    containerColor = if (isGemini) CharcoalElevated else StatusSuccessContainer,
                    showDot = false
                )
            }
            Text(text = timeLabel, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }

        if (!item.imagePath.isNullOrBlank() && File(item.imagePath).exists()) {
            AsyncImage(
                model = File(item.imagePath),
                contentDescription = "ภาพหน้าจอที่บันทึกไว้",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        }

        Text(
            text = item.translatedText,
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = item.sourceText,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .background(CharcoalElevated, RoundedCornerShape(10.dp))
                .padding(10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCopySource) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("ต้นฉบับ")
            }
            TextButton(onClick = onCopyTranslated) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("คำแปลไทย")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "ลบรายการนี้", tint = StatusError)
            }
        }
    }
}
