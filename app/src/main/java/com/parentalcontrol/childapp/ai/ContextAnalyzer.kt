package com.parentalcontrol.childapp.ai

import android.util.Log
import com.parentalcontrol.childapp.utils.BehaviorMonitor
import com.parentalcontrol.childapp.utils.ContentClassifier2

object ContextAnalyzer {

    private const val TAG = "ContextAnalyzer"

    data class ContextResult(
        val riskScore: Float,
        val riskLevel: String,
        val categories: Set<String>
    )

    /**
     * Combine all signals:
     * - imageScore (0..1)
     * - search query
     * - domain category
     * - behavior events
     */
    fun analyze(
        imageScore: Float,
        searchQuery: String?,
        domainCategory: String?
    ): ContextResult {

        val categories = mutableSetOf<String>()
        var score = imageScore

        // Search query risk
        searchQuery?.let {
            val flags = ContentClassifier2.textFlags(it)
            categories.addAll(flags)
            if (flags.isNotEmpty()) score += 0.3f
        }

        // Domain category risk
        domainCategory?.let {
            categories.add(it)
            when (it) {
                "adult" -> score += 0.4f
                "gambling" -> score += 0.3f
                "violence" -> score += 0.25f
            }
        }

        // Behavior events (recent browsing patterns)
        val alert = BehaviorMonitor.recordEvent(domainCategory ?: "general")

        if (alert != null) {
            categories.add(alert.type)
            when (alert.severity) {
                "critical" -> score += 0.5f
                "high" -> score += 0.35f
                "medium" -> score += 0.2f
            }
        }

        // Normalize
        val finalScore = score.coerceIn(0f, 1f)

        val level = when {
            finalScore >= 0.75 -> "CRITICAL"
            finalScore >= 0.55 -> "HIGH"
            finalScore >= 0.35 -> "MEDIUM"
            else -> "LOW"
        }

        Log.d(TAG, "score=$finalScore level=$level categories=$categories")

        return ContextResult(
            riskScore = finalScore,
            riskLevel = level,
            categories = categories
        )
    }
}