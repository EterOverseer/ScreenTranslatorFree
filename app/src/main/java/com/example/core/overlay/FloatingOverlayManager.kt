package com.example.core.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.core.edition.EditionConfig
import com.example.core.permissions.PermissionHelper
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.CaptureStatus
import com.example.domain.model.TranslatorState
import kotlin.math.abs

class FloatingOverlayManager(
    private val context: Context,
    private val onManualCapture: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onStopCapture: () -> Unit,
    private val onDisableOverlays: () -> Unit,
    private val onSaveBubblePosition: (Int, Int) -> Unit,
    private val onOpenHistory: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleStatusDot: View? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var currentSettings = AppSettings()
    private var currentState = TranslatorState()

    // Panel dynamic views
    private var panelStatusText: TextView? = null
    private var panelProgress: ProgressBar? = null
    private var panelSourceText: TextView? = null
    private var panelTranslatedText: TextView? = null
    private var panelErrorText: TextView? = null
    private var panelPauseBtn: Button? = null
    private var panelAutoBadge: TextView? = null

    fun show(settings: AppSettings, state: TranslatorState) {
        currentSettings = settings
        currentState = state
        if (!settings.overlayEnabled || !PermissionHelper.hasOverlayPermission(context)) {
            hide()
            return
        }
        if (bubbleView == null) {
            createBubbleView()
        } else {
            applyBubbleAppearance()
        }
        if (isExpanded && panelView == null) {
            createPanelView()
        }
        renderState()
    }

    fun update(settings: AppSettings, state: TranslatorState) {
        currentSettings = settings
        currentState = state
        if (!settings.overlayEnabled || !PermissionHelper.hasOverlayPermission(context)) {
            hide()
            return
        }
        if (bubbleView == null) {
            createBubbleView()
        } else {
            applyBubbleAppearance()
        }
        renderState()
    }

    fun handleScreenMetricsChange() {
        val params = bubbleParams ?: return
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(currentSettings.bubbleSizeDp)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(currentSettings.bubbleSizeDp)).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    fun hide() {
        collapsePanel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
        bubbleStatusDot = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val sizePx = dp(currentSettings.bubbleSizeDp)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = currentSettings.bubbleOpacity
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6111826"))
                setStroke(dp(2f), Color.parseColor("#00E5FF"))
            }
            elevation = dp(8f).toFloat()
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_eterscreen_fg)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            contentDescription = "ปุ่มแปลหน้าจอลอย EterScreen"
        }

        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E5FF"))
            }
        }
        bubbleStatusDot = statusDot

        container.addView(icon, LinearLayout.LayoutParams(sizePx - dp(12f), sizePx - dp(16f)))
        container.addView(statusDot)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentSettings.bubbleX
            y = currentSettings.bubbleY
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                    }
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        togglePanel()
                    } else {
                        onSaveBubblePosition(params.x, params.y)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        bubbleParams = params
        runCatching { windowManager.addView(container, params) }
    }

    private fun applyBubbleAppearance() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val sizePx = dp(currentSettings.bubbleSizeDp)
        view.alpha = currentSettings.bubbleOpacity
        if (params.width != sizePx || params.height != sizePx) {
            params.width = sizePx
            params.height = sizePx
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun togglePanel() {
        if (isExpanded) {
            collapsePanel()
        } else {
            isExpanded = true
            createPanelView()
            renderState()
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelParams = null
        panelStatusText = null
        panelProgress = null
        panelSourceText = null
        panelTranslatedText = null
        panelErrorText = null
        panelPauseBtn = null
        panelAutoBadge = null
    }

    private fun createPanelView() {
        if (panelView != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor("#F2111826"))
                setStroke(dp(1.5f), Color.parseColor("#2B3A55"))
            }
            elevation = dp(12f).toFloat()
        }

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "EterScreen"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val autoBadge = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        }
        panelAutoBadge = autoBadge

        val closeBtn = createCompactButton("พับเก็บ", "#192235", "#F1F5F9") {
            collapsePanel()
        }
        header.addView(title)
        header.addView(autoBadge)
        header.addView(closeBtn)
        root.addView(header)

        // Status + Progress
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(8f))
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            setPadding(0, 0, dp(8f), 0)
        }
        panelProgress = progress
        val statusTv = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        panelStatusText = statusTv
        statusRow.addView(progress)
        statusRow.addView(statusTv)
        root.addView(statusRow)

        // Error box
        val errorTv = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#4B1113"))
            }
        }
        panelErrorText = errorTv
        root.addView(errorTv)

        // Scrollable text content
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210f)
            )
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val translatedLabel = TextView(context).apply {
            text = "คำแปลภาษาไทย"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4f), 0, dp(2f))
        }
        val translatedTv = TextView(context).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#192235"))
            }
        }
        panelTranslatedText = translatedTv

        val sourceLabel = TextView(context).apply {
            text = "ข้อความต้นฉบับ (OCR)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(10f), 0, dp(2f))
        }
        val sourceTv = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#0D1320"))
            }
        }
        panelSourceText = sourceTv

        textCol.addView(translatedLabel)
        textCol.addView(translatedTv)
        textCol.addView(sourceLabel)
        textCol.addView(sourceTv)
        scroll.addView(textCol)
        root.addView(scroll)

        // Action row 1: Capture / Pause / Copy Thai / Copy Source
        val actions1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(4f))
        }
        val captureBtn = createCompactButton("แปลหน้าจอ", "#00E5FF", "#0A0E17", weight = 1f) {
            onManualCapture()
        }
        val pauseBtn = createCompactButton("พัก", "#222D44", "#F1F5F9", weight = 0.8f) {
            onTogglePause()
        }
        panelPauseBtn = pauseBtn
        val copyThBtn = createCompactButton("คัดลอกไทย", "#2979FF", "#FFFFFF", weight = 1f) {
            copyToClipboard("คำแปลไทย", currentState.lastTranslatedText)
        }
        actions1.addView(captureBtn)
        actions1.addView(spacer(6))
        actions1.addView(pauseBtn)
        actions1.addView(spacer(6))
        actions1.addView(copyThBtn)
        root.addView(actions1)

        // Action row 2: Copy Source / History / Hide Overlay / Stop Service
        val actions2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, 0)
        }
        val copySrcBtn = createCompactButton("คัดลอกต้นฉบับ", "#192235", "#CBD5E1", weight = 1f) {
            copyToClipboard("ต้นฉบับ OCR", currentState.lastOcrText)
        }
        val historyBtn = createCompactButton("ประวัติ", "#192235", "#CBD5E1", weight = 0.7f) {
            collapsePanel()
            onOpenHistory()
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("navigate_to", "history")
            }
            context.startActivity(intent)
        }
        val hideOverlayBtn = createCompactButton("ซ่อนปุ่ม", "#192235", "#F59E0B", weight = 0.75f) {
            onDisableOverlays()
        }
        val stopBtn = createCompactButton("หยุด", "#4B1113", "#FCA5A5", weight = 0.65f) {
            onStopCapture()
        }
        actions2.addView(copySrcBtn)
        actions2.addView(spacer(6))
        actions2.addView(historyBtn)
        actions2.addView(spacer(6))
        actions2.addView(hideOverlayBtn)
        actions2.addView(spacer(6))
        actions2.addView(stopBtn)
        root.addView(actions2)

        val metrics = context.resources.displayMetrics
        val panelWidth = (metrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(420f))

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40f)
        }

        panelView = root
        panelParams = params
        runCatching { windowManager.addView(root, params) }
    }

    private fun renderState() {
        val dotColor = when {
            currentState.isOcrRunning || currentState.isTranslating -> Color.parseColor("#2979FF")
            currentState.captureStatus == CaptureStatus.PAUSED -> Color.parseColor("#F59E0B")
            currentState.captureStatus == CaptureStatus.ERROR -> Color.parseColor("#EF4444")
            else -> Color.parseColor("#00E5FF")
        }
        (bubbleStatusDot?.background as? GradientDrawable)?.setColor(dotColor)

        if (!isExpanded || panelView == null) return

        val busy = currentState.isOcrRunning || currentState.isTranslating
        panelProgress?.visibility = if (busy) View.VISIBLE else View.GONE

        val providerTag = currentState.activeProvider?.displayThai?.let { " • $it" }.orEmpty()
        panelStatusText?.text = when {
            currentState.isOcrRunning -> "กำลังอ่านข้อความจากหน้าจอ (OCR)..."
            currentState.isTranslating -> "กำลังแปลภาษา..."
            else -> "${currentState.captureStatus.displayThai}$providerTag"
        }

        val autoText = when {
            !EditionConfig.isAutoScrollSupported -> "Manual Edition"
            currentSettings.captureMode == CaptureMode.MANUAL -> "โหมดกดเอง"
            currentState.isAutoScrollServiceConnected -> "Auto: เปิด"
            else -> "Auto: รอสิทธิ์"
        }
        panelAutoBadge?.text = autoText

        panelPauseBtn?.text = if (currentState.captureStatus == CaptureStatus.PAUSED) "ทำต่อ" else "พัก"

        val err = currentState.lastErrorThai
        if (!err.isNullOrBlank()) {
            panelErrorText?.visibility = View.VISIBLE
            panelErrorText?.text = err
        } else {
            panelErrorText?.visibility = View.GONE
        }

        panelTranslatedText?.text = currentState.lastTranslatedText.ifBlank {
            if (EditionConfig.isAutoScrollSupported) {
                "กดปุ่ม 'แปลหน้าจอ' หรือหยุดเลื่อนเพื่อเริ่มแปล"
            } else {
                "กดปุ่ม 'แปลหน้าจอ' เพื่อจับภาพและแปลทันที"
            }
        }
        panelSourceText?.text = currentState.lastOcrText.ifBlank {
            "ยังไม่มีข้อความ OCR"
        }
    }

    private fun createCompactButton(
        label: String,
        bgHex: String,
        fgHex: String,
        weight: Float? = null,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = dp(40f)
            minimumHeight = dp(40f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            if (weight != null) {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(widthDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp.toFloat()), 1)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "คัดลอก${label}แล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }
}
