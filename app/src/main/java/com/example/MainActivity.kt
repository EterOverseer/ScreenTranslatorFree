package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.app.AppContainer
import com.example.app.AppNavigation
import com.example.app.EterScreenApplication
import com.example.core.permissions.PermissionHelper
import com.example.core.security.configureAppCheck
import com.example.domain.model.CaptureStatus
import com.example.ui.theme.EterScreenTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private var snackbarHostStateRef: SnackbarHostState? = null
    private var deepLinkTab by mutableStateOf<String?>(null)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            container.startCaptureService(result.resultCode, data)
        } else {
            container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.IDLE)
            container.appScope.launch {
                snackbarHostStateRef?.showSnackbar(
                    "ไม่ได้รับสิทธิ์จับภาพหน้าจอ (MediaProjection) กรุณาลองใหม่อีกครั้ง"
                )
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        container.refreshPermissions()
        if (!granted) {
            container.appScope.launch {
                snackbarHostStateRef?.showSnackbar(
                    "ไม่ได้รับสิทธิ์แจ้งเตือน ปุ่มหยุดด่วนบนแถบสถานะอาจไม่แสดง"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch Intent before any UI, ViewModels, or AI calls
        val initialFirebaseStatus = configureAppCheck(this, intent)

        container = (application as EterScreenApplication).container
        container.updateFirebaseStatus(initialFirebaseStatus)
        deepLinkTab = intent?.getStringExtra("navigate_to")

        enableEdgeToEdge()
        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            snackbarHostStateRef = snackbarHostState
            val uiScope = rememberCoroutineScope()

            EterScreenTheme {
                AppNavigation(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    initialDeepLinkTab = deepLinkTab,
                    onConsumeDeepLink = { deepLinkTab = null },
                    onRequestStartCapture = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !PermissionHelper.hasNotificationPermission(this)
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (container.latestSettings.overlayEnabled &&
                            !PermissionHelper.hasOverlayPermission(this)
                        ) {
                            uiScope.launch {
                                snackbarHostState.showSnackbar(
                                    "กรุณาอนุญาตสิทธิ์แสดงหน้าต่างทับแอปอื่นก่อนเริ่มโหมดปุ่มลอย"
                                )
                            }
                            startActivity(PermissionHelper.createOverlaySettingsIntent(this))
                        } else {
                            requestMediaProjectionConsent()
                        }
                    },
                    onRequestOverlayPermission = {
                        startActivity(PermissionHelper.createOverlaySettingsIntent(this))
                    },
                    onRequestAccessibilityPermission = {
                        startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkTab = intent.getStringExtra("navigate_to")
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) {
            container.refreshPermissions()
        }
    }

    private fun requestMediaProjectionConsent() {
        container.translateScreenUseCase.updateCaptureStatus(CaptureStatus.REQUESTING_PERMISSION)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
