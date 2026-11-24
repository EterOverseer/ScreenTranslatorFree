package com.example.core.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

data class FirebaseConfigStatus(
    val isFirebaseAppReady: Boolean = false,
    val isAppCheckConfigured: Boolean = false,
    val statusMessageThai: String = "กำลังตรวจสอบการตั้งค่า Firebase..."
)

fun configureAppCheck(context: Context, intent: Intent?): FirebaseConfigStatus {
    return try {
        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        } else {
            FirebaseApp.getInstance()
        }

        if (app == null || !hasValidFirebaseOptions(app)) {
            Log.e(
                TAG,
                "FirebaseApp is not configured (missing google-services.json or valid FirebaseOptions). Gemini AI disabled."
            )
            return FirebaseConfigStatus(
                isFirebaseAppReady = false,
                isAppCheckConfigured = false,
                statusMessageThai = "ยังไม่ได้ตั้งค่า Firebase (ไม่พบ google-services.json) — ใช้โหมดออฟไลน์ได้ตามปกติ"
            )
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = app.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.d(TAG, "DebugAppCheckProviderFactory will manage local debug token.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
            FirebaseConfigStatus(
                isFirebaseAppReady = true,
                isAppCheckConfigured = true,
                statusMessageThai = "พร้อมใช้งานผ่าน Firebase App Check (Debug)"
            )
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                    FirebaseConfigStatus(
                        isFirebaseAppReady = true,
                        isAppCheckConfigured = true,
                        statusMessageThai = "พร้อมใช้งานผ่าน Firebase App Check (reCAPTCHA Enterprise)"
                    )
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                    FirebaseConfigStatus(
                        isFirebaseAppReady = true,
                        isAppCheckConfigured = false,
                        statusMessageThai = "ยังไม่ได้กำหนด recaptcha_site_key สำหรับ Release Build"
                    )
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
                FirebaseConfigStatus(
                    isFirebaseAppReady = true,
                    isAppCheckConfigured = false,
                    statusMessageThai = "ไม่พบ recaptcha_site_key สำหรับ Release Build"
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check: ${e.javaClass.simpleName}", e)
        FirebaseConfigStatus(
            isFirebaseAppReady = false,
            isAppCheckConfigured = false,
            statusMessageThai = "ตั้งค่า Firebase / App Check ไม่สำเร็จ (${e.javaClass.simpleName}) — ใช้โหมดออฟไลน์ได้"
        )
    }
}

fun inspectFirebaseStatus(context: Context): FirebaseConfigStatus {
    return try {
        val apps = FirebaseApp.getApps(context)
        if (apps.isEmpty()) {
            FirebaseConfigStatus(
                isFirebaseAppReady = false,
                isAppCheckConfigured = false,
                statusMessageThai = "ยังไม่ได้ตั้งค่า Firebase (ไม่พบ google-services.json) — ใช้โหมดออฟไลน์ได้"
            )
        } else {
            val app = apps.first()
            if (!hasValidFirebaseOptions(app)) {
                FirebaseConfigStatus(
                    isFirebaseAppReady = false,
                    isAppCheckConfigured = false,
                    statusMessageThai = "ค่ากำหนด Firebase ไม่สมบูรณ์ — ใช้โหมดออฟไลน์ได้"
                )
            } else if (!BuildConfig.DEBUG) {
                val resId = context.resources.getIdentifier(
                    RECAPTCHA_SITE_KEY_RES,
                    "string",
                    context.packageName
                )
                val siteKey = if (resId != 0) context.getString(resId) else ""
                if (siteKey.isBlank()) {
                    FirebaseConfigStatus(
                        isFirebaseAppReady = true,
                        isAppCheckConfigured = false,
                        statusMessageThai = "Release Build ยังขาด recaptcha_site_key สำหรับ App Check"
                    )
                } else {
                    FirebaseConfigStatus(
                        isFirebaseAppReady = true,
                        isAppCheckConfigured = true,
                        statusMessageThai = "พร้อมใช้งานผ่าน Firebase App Check (Release)"
                    )
                }
            } else {
                FirebaseConfigStatus(
                    isFirebaseAppReady = true,
                    isAppCheckConfigured = true,
                    statusMessageThai = "พร้อมใช้งานผ่าน Firebase App Check (Debug)"
                )
            }
        }
    } catch (e: Exception) {
        FirebaseConfigStatus(
            isFirebaseAppReady = false,
            isAppCheckConfigured = false,
            statusMessageThai = "ไม่สามารถตรวจสอบสถานะ Firebase ได้"
        )
    }
}

private fun hasValidFirebaseOptions(app: FirebaseApp): Boolean {
    val options = app.options
    return options.applicationId.isNotBlank() &&
        options.apiKey.isNotBlank() &&
        !options.projectId.isNullOrBlank()
}
