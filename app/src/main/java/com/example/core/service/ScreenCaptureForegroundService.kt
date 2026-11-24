package com.example.core.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.app.AppContainer
import com.example.app.EterScreenApplication
import com.example.core.overlay.FloatingOverlayManager
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.CaptureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScreenCaptureForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var overlayManager: FloatingOverlayManager
    private var latestSettings = AppSettings()
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        container = (application as EterScreenApplication).container
        createNotificationChannel()

        overlayManager = FloatingOverlayManager(
            context = this,
            onManualCapture = { triggerManualCapture() },
            onTogglePause = { togglePause() },
            onStopCapture = { stopCaptureService() },
            onDisableOverlays = {
                serviceScope.launch {
                    container.settingsRepository.updateSettings { it.copy(overlayEnabled = false) }
                    overlayManager.hide()
                    container.translateScreenUseCase.updateOverlayShowing(false)
                }
            },
            onSaveBubblePosition = { x, y ->
                serviceScope.launch {
                    container.settingsRepository.updateSettings { it.copy(bubbleX = x, bubbleY = y) }
                }
            },
            onOpenHistory = { /* Handled via PendingIntent / Activity launch inside overlay */ }
        )

        container.screenCaptureManager.onExternalStopListener = {
            stopCaptureService()
        }

        serviceScope.launch {
            combine(
                container.settingsRepository.settingsFlow,
                container.translateScreenUseCase.state
            ) { settings, translatorState ->
                settings to translatorState
            }.collect { (settings, state) ->
                latestSettings = settings
                syncAutoCaptureScheduler(settings, state.captureStatus)
                if (container.screenCaptureManager.isSessionActive.value) {
                    overlayManager.update(settings, state)
                    container.translateScreenUseCase.updateOverlayShowing(settings.overlayEnabled)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                // Mandatory Android 14+: Promote to foreground with mediaProjection type BEFORE getMediaProjection()
                promoteToForeground(isPaused = false)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val started = container.screenCaptureManager.startProjection(resultCode, resultData)
                    if (started) {
                        isPaused = false
                        container.translateScreenUseCase.clearScreenDeduplication()
                        container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.ACTIVE)
                        serviceScope.launch {
                            val settings = container.settingsRepository.settingsFlow.first()
                            latestSettings = settings
                            overlayManager.show(settings, container.translateScreenUseCase.state.value)
                            container.translateScreenUseCase.updateOverlayShowing(settings.overlayEnabled)
                        }
                    } else {
                        container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.ERROR)
                        stopCaptureService()
                    }
                } else {
                    container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.IDLE)
                    stopCaptureService()
                }
            }
            ACTION_MANUAL_CAPTURE -> triggerManualCapture()
            ACTION_PAUSE -> {
                isPaused = true
                container.autoCaptureScheduler.cancelPending()
                container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.PAUSED)
                promoteToForeground(isPaused = true)
            }
            ACTION_RESUME -> {
                isPaused = false
                container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.ACTIVE)
                promoteToForeground(isPaused = false)
            }
            ACTION_STOP -> stopCaptureService()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.screenCaptureManager.handleConfigurationChange()
        overlayManager.handleScreenMetricsChange()
    }

    override fun onDestroy() {
        container.autoCaptureScheduler.cancelPending()
        container.autoCaptureScheduler.isEnabled = false
        container.translateScreenUseCase.cancelActiveTranslation()
        container.translateScreenUseCase.updateOverlayShowing(false)
        container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.IDLE)
        overlayManager.hide()
        container.screenCaptureManager.onExternalStopListener = null
        container.screenCaptureManager.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun triggerManualCapture() {
        if (!container.screenCaptureManager.isSessionActive.value) return
        if (isPaused) {
            isPaused = false
            container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.ACTIVE)
            promoteToForeground(isPaused = false)
        }
        container.translateScreenUseCase.processCapturedBitmap(
            bitmapProvider = { container.screenCaptureManager.captureSingleFrame() },
            settings = latestSettings,
            forceProcess = true
        )
    }

    private fun togglePause() {
        if (isPaused) {
            isPaused = false
            container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.ACTIVE)
            promoteToForeground(isPaused = false)
        } else {
            isPaused = true
            container.autoCaptureScheduler.cancelPending()
            container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.PAUSED)
            promoteToForeground(isPaused = true)
        }
    }

    private fun syncAutoCaptureScheduler(settings: AppSettings, status: CaptureStatus) {
        val autoAllowed = settings.captureMode != CaptureMode.MANUAL &&
            !isPaused &&
            status != CaptureStatus.PAUSED &&
            container.screenCaptureManager.isSessionActive.value
        container.autoCaptureScheduler.delayMillis = settings.autoCaptureDelayMs
        container.autoCaptureScheduler.cooldownMillis = settings.cooldownMs
        container.autoCaptureScheduler.isEnabled = autoAllowed
        if (!autoAllowed) {
            container.autoCaptureScheduler.cancelPending()
        }
    }

    private fun stopCaptureService() {
        container.autoCaptureScheduler.cancelPending()
        container.autoCaptureScheduler.isEnabled = false
        container.translateScreenUseCase.cancelActiveTranslation()
        container.translateScreenUseCase.updateOverlayShowing(false)
        container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.IDLE)
        overlayManager.hide()
        container.screenCaptureManager.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun promoteToForeground(isPaused: Boolean) {
        val notification = buildNotification(isPaused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ScreenCaptureForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeAction = if (isPaused) {
            val resumePendingIntent = PendingIntent.getService(
                this,
                102,
                Intent(this, ScreenCaptureForegroundService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                getString(R.string.notif_action_resume),
                resumePendingIntent
            ).build()
        } else {
            val pausePendingIntent = PendingIntent.getService(
                this,
                103,
                Intent(this, ScreenCaptureForegroundService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_action_pause),
                pausePendingIntent
            ).build()
        }

        val statusText = if (isPaused) {
            "พักการจับภาพชั่วคราว — แตะเพื่อทำต่อหรือหยุด"
        } else {
            getString(R.string.notif_capture_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eterscreen_fg)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(pauseOrResumeAction)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_capture_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_capture_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "eterscreen_capture_channel"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val ACTION_PAUSE = "com.example.action.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.example.action.RESUME_CAPTURE"
        const val ACTION_MANUAL_CAPTURE = "com.example.action.MANUAL_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_STOP)
        }

        fun manualCaptureIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_MANUAL_CAPTURE)
        }

        fun pauseIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_PAUSE)
        }

        fun resumeIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_RESUME)
        }
    }
}
