package com.parentalcontrol.childapp.utils

import kotlin.math.max

object RiskCombiner {

    data class RiskResult(
        val riskScore: Float,
        val riskLevel: String,
        val categories: Set<String>
    )

    fun combine(imageScore: Float, searchText: String?): RiskResult {

        val categories = mutableSetOf<String>()

        // 🔎 Analyze search text
        val textFlags = searchText?.let {
            ContentClassifier2.textFlags(it)
        } ?: emptySet()

        categories.addAll(textFlags)

        val textScore = when {
            textFlags.contains("adult") -> 0.9f
            textFlags.contains("violence") -> 0.7f
            textFlags.contains("gambling") -> 0.6f
            textFlags.contains("drugs") -> 0.8f
            else -> 0.0f
        }

        // 📷 Combine image + text
        val combinedScore = max(imageScore, textScore)

        val level = when {
            combinedScore >= 0.85 -> "critical"
            combinedScore >= 0.65 -> "high"
            combinedScore >= 0.40 -> "medium"
            combinedScore >= 0.20 -> "low"
            else -> "safe"
        }

        return RiskResult(
            riskScore = combinedScore,
            riskLevel = level,
            categories = categories
        )
    }
}