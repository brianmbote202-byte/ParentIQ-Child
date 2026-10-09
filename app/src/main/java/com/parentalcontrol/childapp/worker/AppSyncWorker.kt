package com.parentalcontrol.childapp.worker

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.model.AppInfo
import com.parentalcontrol.childapp.service.UsageLoggerWorker
import com.parentalcontrol.childapp.utils.AppIconUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class AppSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParams
) {

    companion object {

        private const val TAG =
            "AppSyncWorker"

        private const val PREFS_NAME =
            "child_prefs"

        private const val CHILD_ID_KEY =
            "child_id"

        private const val BACKEND_URL =
            "https://parentiq-backend.onrender.com"

        private const val USAGE_TRACKER_PREFS =
            "usage_tracker"

        private const val TRACKED_APPS_KEY =
            "tracked_apps"

        private const val USAGE_WORK_NAME =
            "UsageLoggerWorker"

        private const val USAGE_INTERVAL_MINUTES =
            15L
    }

    // =========================================================
    // WORK EXECUTION
    // =========================================================

    override suspend fun doWork(): Result {

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥🔥🔥 APP SYNC WORKER STARTED 🔥🔥🔥"
        )

        Log.e(
            TAG,
            "🔥 Worker attempt = $runAttemptCount"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        return try {

            val childId =
                getChildId()

            // =================================================
            // CHILD ID VALIDATION
            // =================================================

            if (
                childId.isBlank() ||
                childId == "unknown_child"
            ) {

                Log.e(
                    TAG,
                    "❌ INVALID CHILD ID"
                )

                Log.e(
                    TAG,
                    "⏸️ App synchronization skipped"
                )

                return Result.success()
            }

            Log.e(
                TAG,
                "👶 CHILD ID = $childId"
            )

            // =================================================
            // RUN SYNCHRONIZATION
            // =================================================

            val success =
                syncAppsToFirebase(
                    childId
                )

            if (!success) {

                Log.e(
                    TAG,
                    "❌ APP SYNC DID NOT COMPLETE"
                )

                Log.e(
                    TAG,
                    "🔄 WorkManager will retry"
                )

                return Result.retry()
            }

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "✅ APP SYNC WORKER COMPLETED"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ APP SYNC WORKER FAILED"
            )

            Log.e(
                TAG,
                "❌ Exception type = ${e::class.java.name}"
            )

            Log.e(
                TAG,
                "❌ Exception message = ${e.message}"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ FULL APP SYNC FAILURE",
                e
            )

            Result.retry()
        }
    }

    // =========================================================
    // MAIN SYNC
    // =========================================================

    private suspend fun syncAppsToFirebase(
        childId: String
    ): Boolean {

        return withContext(Dispatchers.IO) {

            try {

                Log.d(
                    TAG,
                    "🚀 Starting installed-app synchronization"
                )

                val pm =
                    applicationContext.packageManager

                // =================================================
                // 1. GET LAUNCHER APPS
                // =================================================

                val launcherIntent =
                    Intent(
                        Intent.ACTION_MAIN,
                        null
                    ).apply {

                        addCategory(
                            Intent.CATEGORY_LAUNCHER
                        )
                    }

                val launcherApps =
                    pm.queryIntentActivities(
                        launcherIntent,
                        0
                    )

                val launcherPackages =
                    launcherApps
                        .map {
                            it.activityInfo.packageName
                        }
                        .toSet()

                Log.d(
                    TAG,
                    "📱 Launcher apps = ${launcherPackages.size}"
                )

                // =================================================
                // 2. GET INSTALLED APPS
                // =================================================

                val installedApps =
                    pm.getInstalledApplications(0)

                Log.d(
                    TAG,
                    "📦 Installed apps = ${installedApps.size}"
                )

                // =================================================
                // FIREBASE REFERENCE
                // =================================================

                val dbRef =
                    FirebaseDatabase
                        .getInstance()
                        .getReference(
                            "installed_apps"
                        )
                        .child(childId)

                // =================================================
                // TRACKED PACKAGES
                // =================================================

                val trackedPackages =
                    mutableListOf<String>()

                // =================================================
                // FIREBASE UPDATES
                // =================================================

                val updates =
                    hashMapOf<String, Any>()

                // =================================================
                // PROCESS APPS
                // =================================================

                for (app in installedApps) {

                    try {

                        val packageName =
                            app.packageName

                        // -----------------------------------------
                        // SKIP CHILD APP ITSELF
                        // -----------------------------------------

                        if (
                            packageName.equals(
                                applicationContext.packageName,
                                ignoreCase = true
                            )
                        ) {

                            continue
                        }

                        // -----------------------------------------
                        // SYSTEM APP FLAGS
                        // -----------------------------------------

                        val isSystemApp =
                            (
                                    app.flags and
                                            ApplicationInfo.FLAG_SYSTEM
                                    ) != 0

                        val isUpdatedSystemApp =
                            (
                                    app.flags and
                                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                                    ) != 0

                        val isLauncherApp =
                            launcherPackages
                                .contains(packageName)

                        // -----------------------------------------
                        // SKIP PURE HIDDEN SYSTEM APPS
                        // -----------------------------------------

                        if (
                            isSystemApp &&
                            !isUpdatedSystemApp &&
                            !isLauncherApp
                        ) {

                            Log.d(
                                TAG,
                                "⏭️ Skipping pure hidden system app: $packageName"
                            )

                            continue
                        }

                        // -----------------------------------------
                        // TRACK PACKAGE
                        // -----------------------------------------

                        trackedPackages.add(
                            packageName
                        )

                        // -----------------------------------------
                        // GET CATEGORY
                        // -----------------------------------------

                        val category =
                            getAppCategoryFromFirebase(
                                packageName
                            )

                        // -----------------------------------------
                        // APP NAME
                        // -----------------------------------------

                        val appName =
                            pm
                                .getApplicationLabel(
                                    app
                                )
                                .toString()

                        // -----------------------------------------
                        // ICON
                        // -----------------------------------------

                        val iconBase64 =
                            AppIconUtils
                                .drawableToBase64(
                                    pm.getApplicationIcon(
                                        app
                                    )
                                )

                        // -----------------------------------------
                        // APP INFO
                        // -----------------------------------------

                        val appInfo =
                            AppInfo(
                                appName =
                                    appName,

                                packageName =
                                    packageName,

                                category =
                                    category,

                                iconBase64 =
                                    iconBase64,

                                hidden =
                                    !isLauncherApp
                            )

                        // -----------------------------------------
                        // FIREBASE UPDATE
                        // -----------------------------------------

                        updates[
                            packageName.replace(
                                ".",
                                "_"
                            )
                        ] =
                            appInfo

                        Log.d(
                            TAG,
                            "📱 APP CLASSIFIED: $appName → $category"
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "❌ Failed to process ${app.packageName}",
                            e
                        )
                    }
                }

                // =================================================
                // NO APPS
                // =================================================

                if (updates.isEmpty()) {

                    Log.d(
                        TAG,
                        "ℹ️ No application records to upload"
                    )

                    saveTrackedApps(
                        trackedPackages
                    )

                    startUsageWorker(
                        trackedPackages
                    )

                    return@withContext true
                }

                // =================================================
                // UPLOAD ALL APPS
                // =================================================

                val uploadResult =
                    awaitFirebaseUpdate(
                        dbRef,
                        updates
                    )

                if (!uploadResult) {

                    Log.e(
                        TAG,
                        "❌ Firebase installed_apps update failed"
                    )

                    return@withContext false
                }

                Log.d(
                    TAG,
                    "✅ Uploaded ${updates.size} apps with Firebase categories"
                )

                // =================================================
                // SAVE TRACKED APPS
                // =================================================

                saveTrackedApps(
                    trackedPackages
                )

                // =================================================
                // START USAGE LOGGER WORK
                // =================================================

                startUsageWorker(
                    trackedPackages
                )

                Log.d(
                    TAG,
                    "✅ App synchronization complete"
                )

                true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ syncAppsToFirebase failed",
                    e
                )

                false
            }
        }
    }

    // =========================================================
    // FIREBASE CATEGORY LOOKUP
    // =========================================================

    private suspend fun getAppCategoryFromFirebase(
        packageName: String
    ): String {

        val packageKey =
            packageName.replace(
                ".",
                "_"
            )

        val categoryRef =
            FirebaseDatabase
                .getInstance()
                .getReference(
                    "app_categories"
                )
                .child(packageKey)

        return suspendCancellableCoroutine { continuation ->

            categoryRef
                .get()
                .addOnSuccessListener { snapshot ->

                    if (snapshot.exists()) {

                        val category =
                            snapshot
                                .child("category")
                                .getValue(
                                    String::class.java
                                )
                                ?.trim()
                                ?.lowercase()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "other"

                        Log.d(
                            TAG,
                            "🏷️ Existing category: $packageName → $category"
                        )

                        if (
                            category == "pending"
                        ) {

                            Log.d(
                                TAG,
                                "⏳ APP IS PENDING — REQUESTING BACKEND: $packageName"
                            )

                            // -------------------------------------
                            // BACKEND CLASSIFICATION
                            // -------------------------------------

                            requestBackendClassification(
                                packageKey
                            ) { classifiedCategory ->

                                if (
                                    classifiedCategory != null &&
                                    classifiedCategory != "pending"
                                ) {

                                    Log.d(
                                        TAG,
                                        "✅ APP CLASSIFIED BY BACKEND: $packageName → $classifiedCategory"
                                    )

                                    if (
                                        continuation.isActive
                                    ) {

                                        continuation.resume(
                                            classifiedCategory
                                        )
                                    }

                                } else {

                                    Log.d(
                                        TAG,
                                        "⏳ APP REMAINS PENDING: $packageName"
                                    )

                                    if (
                                        continuation.isActive
                                    ) {

                                        continuation.resume(
                                            "pending"
                                        )
                                    }
                                }
                            }

                        } else {

                            if (
                                continuation.isActive
                            ) {

                                continuation.resume(
                                    category
                                )
                            }
                        }

                    } else {

                        // -----------------------------------------
                        // UNKNOWN APP
                        // -----------------------------------------

                        val pm =
                            applicationContext
                                .packageManager

                        val applicationInfo =
                            try {

                                pm.getApplicationInfo(
                                    packageName,
                                    0
                                )

                            } catch (
                                e: Exception
                            ) {

                                null
                            }

                        val appName =
                            applicationInfo
                                ?.let {

                                    pm.getApplicationLabel(
                                        it
                                    ).toString()

                                }
                                ?: packageName

                        val pendingData =
                            hashMapOf<String, Any>(
                                "appName" to appName,
                                "packageName" to packageName,
                                "category" to "pending",
                                "updatedAt" to
                                        System.currentTimeMillis()
                            )

                        categoryRef
                            .setValue(
                                pendingData
                            )
                            .addOnSuccessListener {

                                Log.d(
                                    TAG,
                                    "🆕 UNKNOWN APP REGISTERED: $appName ($packageName)"
                                )

                                requestBackendClassification(
                                    packageKey
                                ) { classifiedCategory ->

                                    if (
                                        classifiedCategory != null &&
                                        classifiedCategory != "pending"
                                    ) {

                                        Log.d(
                                            TAG,
                                            "✅ APP CLASSIFIED BY BACKEND: $appName → $classifiedCategory"
                                        )

                                        if (
                                            continuation.isActive
                                        ) {

                                            continuation.resume(
                                                classifiedCategory
                                            )
                                        }

                                    } else {

                                        Log.d(
                                            TAG,
                                            "⏳ APP REMAINS PENDING: $appName"
                                        )

                                        if (
                                            continuation.isActive
                                        ) {

                                            continuation.resume(
                                                "pending"
                                            )
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener { error ->

                                Log.e(
                                    TAG,
                                    "❌ Failed to register unknown app: $packageName",
                                    error
                                )

                                if (
                                    continuation.isActive
                                ) {

                                    continuation.resume(
                                        "other"
                                    )
                                }
                            }
                    }
                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "❌ Failed to get category for $packageName",
                        error
                    )

                    if (
                        continuation.isActive
                    ) {

                        continuation.resume(
                            "other"
                        )
                    }
                }
        }
    }

    // =========================================================
    // BACKEND CLASSIFICATION
    // =========================================================

    private fun requestBackendClassification(
        packageKey: String,
        onResult: (String?) -> Unit
    ) {

        Thread {

            var connection:
                    HttpURLConnection? = null

            try {

                Log.d(
                    TAG,
                    "🌐 REQUESTING BACKEND CLASSIFICATION: $packageKey"
                )

                val url =
                    URL(
                        "$BACKEND_URL/app-classification/classify-pending"
                    )

                connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    30000

                connection.doOutput =
                    true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val requestBody =
                    JSONObject()
                        .put(
                            "packageKey",
                            packageKey
                        )
                        .toString()

                connection
                    .outputStream
                    .use { output ->

                        output.write(
                            requestBody.toByteArray(
                                Charsets.UTF_8
                            )
                        )

                        output.flush()
                    }

                val responseCode =
                    connection.responseCode

                Log.d(
                    TAG,
                    "🌐 BACKEND RESPONSE CODE: $responseCode"
                )

                val responseStream =
                    if (
                        responseCode in 200..299
                    ) {

                        connection.inputStream

                    } else {

                        connection.errorStream
                    }

                val response =
                    responseStream
                        ?.bufferedReader()
                        ?.use {
                            it.readText()
                        }
                        ?: ""

                Log.d(
                    TAG,
                    "🌐 BACKEND RESPONSE: $response"
                )

                if (
                    responseCode !in 200..299
                ) {

                    Log.e(
                        TAG,
                        "❌ Backend classification failed: HTTP $responseCode"
                    )

                    onResult(null)

                    return@Thread
                }

                val json =
                    JSONObject(
                        response
                    )

                val success =
                    json.optBoolean(
                        "success",
                        false
                    )

                if (!success) {

                    Log.e(
                        TAG,
                        "❌ Backend returned classification failure"
                    )

                    onResult(null)

                    return@Thread
                }

                val category =
                    json.optString(
                        "category",
                        ""
                    )
                        .trim()
                        .lowercase()

                if (
                    category.isBlank()
                ) {

                    Log.e(
                        TAG,
                        "❌ Backend returned empty category"
                    )

                    onResult(null)

                    return@Thread
                }

                Log.d(
                    TAG,
                    "🤖 BACKEND CLASSIFICATION RESULT: $packageKey → $category"
                )

                onResult(
                    category
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "❌ BACKEND CLASSIFICATION REQUEST FAILED",
                    e
                )

                onResult(null)

            } finally {

                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // FIREBASE UPDATE
    // =========================================================

    private suspend fun awaitFirebaseUpdate(
        dbRef:
        com.google.firebase.database.DatabaseReference,
        updates:
        Map<String, Any>
    ): Boolean {

        return suspendCancellableCoroutine { continuation ->

            dbRef
                .updateChildren(
                    updates
                )
                .addOnSuccessListener {

                    if (
                        continuation.isActive
                    ) {

                        continuation.resume(
                            true
                        )
                    }
                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "❌ Firebase upload failed",
                        error
                    )

                    if (
                        continuation.isActive
                    ) {

                        continuation.resume(
                            false
                        )
                    }
                }
        }
    }

    // =========================================================
    // SAVE TRACKED APPS
    // =========================================================

    private fun saveTrackedApps(
        apps: List<String>
    ) {

        val prefs =
            applicationContext
                .getSharedPreferences(
                    USAGE_TRACKER_PREFS,
                    Context.MODE_PRIVATE
                )

        prefs
            .edit()
            .putStringSet(
                TRACKED_APPS_KEY,
                apps.toSet()
            )
            .apply()

        Log.d(
            TAG,
            "💾 Saved ${apps.size} tracked apps"
        )
    }

    // =========================================================
    // START USAGE LOGGER
    // =========================================================

    private fun startUsageWorker(
        installedPackages: List<String>
    ) {

        val childId =
            getChildId()

        if (
            childId.isBlank() ||
            childId == "unknown_child"
        ) {

            Log.e(
                TAG,
                "❌ Cannot schedule usage worker"
            )

            return
        }

        val data =
            workDataOf(
                "childId" to childId,
                "trackedApps" to
                        installedPackages.toTypedArray()
            )

        val workRequest =
            PeriodicWorkRequestBuilder<UsageLoggerWorker>(
                USAGE_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setInputData(
                    data
                )
                .build()

        WorkManager
            .getInstance(
                applicationContext
            )
            .enqueueUniquePeriodicWork(
                USAGE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

        Log.d(
            TAG,
            "⏱️ Usage worker scheduled for ${installedPackages.size} apps"
        )
    }

    // =========================================================
    // CHILD ID
    // =========================================================

    private fun getChildId(): String {

        val prefs =
            applicationContext
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )

        return prefs
            .getString(
                CHILD_ID_KEY,
                "unknown_child"
            )
            ?.trim()
            ?: "unknown_child"
    }
}