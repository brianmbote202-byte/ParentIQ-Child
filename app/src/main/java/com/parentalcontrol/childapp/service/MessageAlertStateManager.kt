package com.parentalcontrol.childapp.service

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.util.Locale

/**
 * MessageAlertStateManager
 *
 * Keeps only the CURRENT alert state in Firebase.
 *
 * It does NOT upload the complete SMS message.
 *
 * Firebase structure:
 *
 * child_alert_state/
 *     {childId}/
 *         adult/
 *             active
 *             severity
 *             keyword
 *             direction
 *             timestamp
 *             conversationId
 *             phoneNumber
 *
 *         gambling/
 *             ...
 *
 *         grooming/
 *             ...
 *
 *         money/
 *             ...
 *
 *         financialCredential/
 *             ...
 *
 *         hasAlert
 *
 * Only one state is maintained per category.
 */
object MessageAlertStateManager {

    private const val TAG = "MESSAGE_ALERT_STATE"

    private val database =
        FirebaseDatabase.getInstance()

    // =========================================================
    // FIREBASE CATEGORY NAMES
    // =========================================================

    private fun categoryKey(
        category: MessageAlertEngine.Category
    ): String {

        return when (category) {

            MessageAlertEngine.Category.ADULT ->
                "adult"

            MessageAlertEngine.Category.GAMBLING ->
                "gambling"

            MessageAlertEngine.Category.GROOMING ->
                "grooming"

            MessageAlertEngine.Category.MONEY ->
                "money"

            MessageAlertEngine.Category.FINANCIAL_CREDENTIAL ->
                "financialCredential"
        }
    }

    // =========================================================
    // PROCESS ANALYSIS RESULT
    // =========================================================

    fun processMatches(
        childId: String,
        direction: String,
        conversationId: String?,
        phoneNumber: String?,
        result: MessageAlertEngine.AnalysisResult
    ) {

        if (childId.isBlank()) {

            Log.w(
                TAG,
                "Cannot process alert: childId is empty"
            )

            return
        }

        if (!result.matched || result.matches.isEmpty()) {

            Log.d(
                TAG,
                "No message alert match"
            )

            return
        }

        val normalizedDirection =
            direction
                .trim()
                .uppercase(Locale.ROOT)

        // =====================================================
        // NORMALIZE CONVERSATION INFORMATION
        // =====================================================

        val normalizedConversationId =
            conversationId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val normalizedPhoneNumber =
            phoneNumber
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        // =====================================================
        // PROCESS EACH CATEGORY
        // =====================================================

        for (match in result.matches) {

            processCategory(
                childId = childId,
                direction = normalizedDirection,
                conversationId = normalizedConversationId,
                phoneNumber = normalizedPhoneNumber,
                match = match
            )
        }
    }

    // =========================================================
    // PROCESS CATEGORY
    // =========================================================

    private fun processCategory(
        childId: String,
        direction: String,
        conversationId: String?,
        phoneNumber: String?,
        match: MessageAlertEngine.AlertMatch
    ) {

        val category =
            categoryKey(match.category)

        val categoryRef =
            database
                .getReference("child_alert_state")
                .child(childId)
                .child(category)

        // =====================================================
        // READ CURRENT STATE
        // =====================================================

        categoryRef
            .get()
            .addOnSuccessListener { snapshot ->

                val alreadyActive =
                    snapshot
                        .child("active")
                        .getValue(Boolean::class.java)
                        ?: false

                // =================================================
                // ALERT ALREADY ACTIVE
                //
                // IMPORTANT:
                // Do NOT write again.
                //
                // This preserves your Firebase-write optimization.
                // =================================================

                if (alreadyActive) {

                    Log.d(
                        TAG,
                        "Alert already active -> " +
                                "category=$category, " +
                                "no category Firebase write"
                    )

                    // Make sure the global flag is still correct.
                    database
                        .getReference("child_alert_state")
                        .child(childId)
                        .child("hasAlert")
                        .setValue(true)
                        .addOnFailureListener { error ->

                            Log.e(
                                TAG,
                                "Failed to restore hasAlert",
                                error
                            )
                        }

                    return@addOnSuccessListener
                }

                // =================================================
                // NEW ALERT
                // =================================================

                val timestamp =
                    System.currentTimeMillis()

                val matchedKeyword =
                    match.matchedKeywords
                        .joinToString(", ")

                val state =
                    mutableMapOf<String, Any>(
                        "active" to true,
                        "severity" to match.severity.name,
                        "keyword" to matchedKeyword,
                        "direction" to direction,
                        "timestamp" to timestamp
                    )

                // =================================================
                // CONVERSATION REFERENCE
                // =================================================

                if (!conversationId.isNullOrBlank()) {

                    state["conversationId"] =
                        conversationId
                }

                // =================================================
                // PHONE NUMBER
                // =================================================

                if (!phoneNumber.isNullOrBlank()) {

                    state["phoneNumber"] =
                        phoneNumber
                }

                // =================================================
                // WRITE CURRENT ALERT STATE
                // =================================================

                categoryRef
                    .updateChildren(state)
                    .addOnSuccessListener {

                        Log.d(
                            TAG,
                            "NEW ALERT CREATED -> " +
                                    "child=$childId " +
                                    "category=$category " +
                                    "severity=${match.severity} " +
                                    "direction=$direction " +
                                    "keyword=$matchedKeyword " +
                                    "conversationId=$conversationId " +
                                    "phoneNumber=$phoneNumber"
                        )
                    }
                    .addOnFailureListener { error ->

                        Log.e(
                            TAG,
                            "Failed creating alert state: " +
                                    "category=$category",
                            error
                        )
                    }

                // =================================================
                // GLOBAL FLAG
                // =================================================

                database
                    .getReference("child_alert_state")
                    .child(childId)
                    .child("hasAlert")
                    .setValue(true)
                    .addOnFailureListener { error ->

                        Log.e(
                            TAG,
                            "Failed to set hasAlert",
                            error
                        )
                    }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Failed reading alert state: " +
                            "category=$category",
                    error
                )
            }
    }

    // =========================================================
    // CLEAR CATEGORY
    // =========================================================

    /**
     * Called when the parent acknowledges/resolves an alert.
     *
     * Only the active flag is changed.
     *
     * conversationId, phoneNumber, keyword, etc.
     * remain available for reference.
     */
    fun clearCategory(
        childId: String,
        category: MessageAlertEngine.Category
    ) {

        if (childId.isBlank()) {
            return
        }

        val categoryKey =
            categoryKey(category)

        database
            .getReference("child_alert_state")
            .child(childId)
            .child(categoryKey)
            .child("active")
            .setValue(false)
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "Alert cleared: " +
                            "child=$childId " +
                            "category=$categoryKey"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Failed to clear alert: " +
                            categoryKey,
                    error
                )
            }
    }

    // =========================================================
    // CLEAR ALL
    // =========================================================

    /**
     * Clears all alert categories for a child.
     */
    fun clearAll(
        childId: String
    ) {

        if (childId.isBlank()) {
            return
        }

        val updates =
            mutableMapOf<String, Any?>()

        updates["adult/active"] =
            false

        updates["gambling/active"] =
            false

        updates["grooming/active"] =
            false

        updates["money/active"] =
            false

        updates["financialCredential/active"] =
            false

        updates["hasAlert"] =
            false

        database
            .getReference("child_alert_state")
            .child(childId)
            .updateChildren(updates)
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "All alerts cleared for $childId"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Failed to clear all alerts",
                    error
                )
            }
    }
}