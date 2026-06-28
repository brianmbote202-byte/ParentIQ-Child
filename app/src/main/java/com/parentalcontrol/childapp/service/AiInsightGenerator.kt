package com.parentalcontrol.childapp.service

import com.google.firebase.database.*
import android.util.Log
import kotlinx.coroutines.*
import android.content.Context
import com.google.firebase.database.*
import com.parentalcontrol.childapp.model.Insight
import java.util.Calendar
import kotlinx.coroutines.tasks.await
import com.parentalcontrol.childapp.model.SafetyScore

class AiInsightGenerator(
    private val childId: String,
    private val db: DatabaseReference = FirebaseDatabase.getInstance().reference
) {

    private val usageRef =
        db.child("child_usage")
            .child(childId)
            .child("app_sessions")

    private val browsingRef =
        db.child("analytics_browsing").child(childId)

    private val insightsRef =
        db.child("analytics_ai_insights").child(childId).child("daily_insights")

    //---------list if apps to be analysed---------
    private val socialApps = listOf(
        "com.facebook.katana",
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.twitter.android",
        "com.snapchat.android"
    )

    private val socialDomains = listOf(
        "facebook.com",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com",
        "snapchat.com"
    )


    // ================================
    // MAIN ENTRY POINT
    // ================================
    fun generateDailyInsights() = runBlocking {

        val usageSnap = usageRef.get().await()
        val browseSnap = browsingRef.get().await()

        val usageData = parseUsage(usageSnap)
        Log.e("AI_SCORE", "Usage apps = ${usageData.size}")

        usageData.forEach { (app, time) ->

            Log.e(
                "AI_SCORE",
                "$app = ${time / 60000} minutes"
            )
        }



        val visited = parseVisited(browseSnap)
        Log.d("AI_SCORE", "Visited count = ${visited.size}")

        visited.forEach {
            Log.d(
                "AI_SCORE",
                "Category = ${it["category"]}"
            )
        }


        val blocked = parseBlocked(browseSnap)
        val youtube = parseYoutube(browseSnap)

        Log.e("AI_SCORE", "usageData size = ${usageData.size}")
        Log.e("AI_SCORE", "visited size = ${visited.size}")
        Log.e("AI_SCORE", "blocked size = ${blocked.size}")
        Log.e("AI_SCORE", "youtube size = ${youtube.size}")

        val insights = mutableListOf<Insight>()

        insights += detectTopApp(usageData)
        insights += detectOveruse(usageData)
        insights += detectNightUsage(visited)
        insights += detectBlockedAttempts(blocked)
        insights += detectRiskyBehavior(visited)
        insights += detectYoutubeAddiction(youtube)
        insights += detectSocialMediaUsage(usageData, visited)
        insights += detectTopSocialApp(usageData)
        insights += detectShortVideoAddiction(usageData, youtube)


        val score = calculateSafetyScore(
            usageData,
            visited,
            blocked,
            youtube
        )
        Log.e("AI_SCORE", "Final Score = ${score.score}")
        Log.e("AI_SCORE", "Level = ${score.level}")
        Log.e("AI_SCORE", "Breakdown = ${score.breakdown}")


        insights += generateScoreInsights(score)
        insights += generateBreakdownInsights(score)
        insights += detectDailySummary(usageData)

        saveSafetyScore(score)



        saveInsights(insights)
        generateDailyReport(usageData, score)
    }

    //--------build the score calculator-------
    private fun calculateSafetyScore(
        usage: Map<String, Long>,
        visited: List<Map<String, Any>>,
        blocked: List<Map<String, Any>>,
        youtube: List<Map<String, Any>>
    ): SafetyScore {

        var score = 100
        val breakdown = mutableMapOf<String, Int>()

        // ---------------- SCREEN TIME ----------------
        val totalMinutes = usage.values.sum() / 60000

        val screenPenalty = when {
            totalMinutes > 300 -> 20
            totalMinutes > 180 -> 10
            else -> 0
        }

        score -= screenPenalty
        breakdown["screen_time"] = screenPenalty

        // ---------------- NIGHT USAGE ----------------
        val nightCount = visited.count {
            val time = it["time"] as? Long ?: 0L
            val hour = Calendar.getInstance().apply {
                timeInMillis = time
            }.get(Calendar.HOUR_OF_DAY)

            hour >= 22 || hour <= 5
        }

        val nightPenalty = when {
            nightCount > 10 -> 15
            nightCount > 3 -> 8
            else -> 0
        }

        score -= nightPenalty
        breakdown["night_usage"] = nightPenalty

        // ---------------- BLOCKED ATTEMPTS ----------------
        val blockedPenalty = when {
            blocked.size > 10 -> 20
            blocked.size > 3 -> 10
            else -> 0
        }

        score -= blockedPenalty
        breakdown["blocked_attempts"] = blockedPenalty

        // ---------------- RISKY CONTENT ----------------
        val riskyCount = visited.count {
            val category = it["category"] as? String ?: ""
            category == "adult" || category == "gambling"
        }

        val riskPenalty = when {
            riskyCount > 5 -> 25
            riskyCount > 0 -> 15
            else -> 0
        }

        score -= riskPenalty
        breakdown["risky_content"] = riskPenalty

        // ---------------- SOCIAL MEDIA ----------------
        val socialMinutes = usage
            .filterKeys { app -> socialApps.any { app.contains(it) } }
            .values.sum() / 60000

        val socialPenalty = when {
            socialMinutes > 180 -> 10
            socialMinutes > 120 -> 5
            else -> 0
        }

        score -= socialPenalty
        breakdown["social_media"] = socialPenalty

        // ---------------- SHORT VIDEO ----------------
        val ytSessions = youtube.count {
            val type = it["type"] as? String ?: ""
            type.contains("youtube_session")
        }

        val shortPenalty = when {
            ytSessions > 20 -> 10
            ytSessions > 10 -> 5
            else -> 0
        }

        score -= shortPenalty
        breakdown["short_videos"] = shortPenalty

        // ---------------- FINAL CLAMP ----------------
        if (score < 0) score = 0

        val level = when {
            score >= 80 -> "Safe"
            score >= 50 -> "Moderate"
            else -> "High Risk"
        }

        return SafetyScore(score, level, breakdown)
    }


    //----------save safety score to firebase---------
    private fun saveSafetyScore(score: SafetyScore) {

        insightsRef.child("score").setValue(
            mapOf(
                "score" to score.score,
                "level" to score.level,
                "breakdown" to score.breakdown,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    //----------generate insights---------
    private fun generateScoreInsights(score: SafetyScore): List<Insight> {

        val insights = mutableListOf<Insight>()

        when {
            score.score < 50 -> {
                insights.add(
                    Insight(
                        type = "high_risk",
                        message = "Child behavior indicates elevated risk. Immediate attention recommended.",
                        severity = "critical"
                    )
                )
            }

            score.score in 50..79 -> {
                insights.add(
                    Insight(
                        type = "moderate_risk",
                        message = "Moderate risk behavior detected. Monitor usage patterns.",
                        severity = "medium"
                    )
                )
            }

            else -> {
                insights.add(
                    Insight(
                        type = "safe_behavior",
                        message = "Child behavior is within safe limits.",
                        severity = "low"
                    )
                )
            }
        }

        return insights
    }

    //----------------reacting to why the score dropped-----------
    private fun generateBreakdownInsights(score: SafetyScore): List<Insight> {

        val insights = mutableListOf<Insight>()
        val breakdown = score.breakdown

        if ((breakdown["risky_content"] ?: 0) >= 15) {
            insights.add(
                Insight(
                    type = "risky_content_warning",
                    message = "Child accessed potentially harmful content",
                    severity = "critical"
                )
            )
        }

        if ((breakdown["blocked_attempts"] ?: 0) >= 10) {
            insights.add(
                Insight(
                    type = "bypass_attempt",
                    message = "Repeated attempts to bypass restrictions detected",
                    severity = "high"
                )
            )
        }

        if ((breakdown["night_usage"] ?: 0) >= 8) {
            insights.add(
                Insight(
                    type = "sleep_disruption",
                    message = "Late-night device usage detected",
                    severity = "high"
                )
            )
        }

        if ((breakdown["screen_time"] ?: 0) >= 10) {
            insights.add(
                Insight(
                    type = "overuse_warning",
                    message = "Excessive screen time detected",
                    severity = "medium"
                )
            )
        }

        return insights
    }


    //-----------detect most used app----
    private fun detectTopApp(usage: Map<String, Long>): List<Insight> {
        val top = usage.maxByOrNull { it.value } ?: return emptyList()

        return listOf(
            Insight(
                type = "top_app",
                message = "Most used app: ${top.key}",
                severity = "low"
            )
        )
    }

    //--------overuse detection---------
    private fun detectOveruse(usage: Map<String, Long>): List<Insight> {
        val insights = mutableListOf<Insight>()

        usage.forEach { (app, time) ->
            val minutes = time / 60000

            if (minutes > 180) { // 3 hours
                insights.add(
                    Insight(
                        type = "overuse",
                        message = "$app used for ${minutes} minutes",
                        severity = "medium"
                    )
                )
            }
        }

        return insights
    }

    //-----------detect social media misuse-----------
    private fun detectSocialMediaUsage(
        usage: Map<String, Long>,
        visited: List<Map<String, Any>>
    ): List<Insight> {

        var totalMinutes = 0L

        // 📱 App usage
        usage.forEach { (app, time) ->
            if (socialApps.any { app.contains(it) }) {
                totalMinutes += time / 60000
            }
        }

        // 🌐 Website usage (approximate count)
        val socialVisits = visited.count {
            val domain = it["domain"] as? String ?: ""
            socialDomains.any { domain.contains(it) }
        }

        return if (totalMinutes > 120 || socialVisits > 30) {
            listOf(
                Insight(
                    type = "social_overuse",
                    message = "Heavy social media usage detected (${totalMinutes} min, $socialVisits visits)",
                    severity = "medium"
                )
            )
        } else emptyList()
    }

    //------------detect most used social media-----
    private fun detectTopSocialApp(usage: Map<String, Long>): List<Insight> {

        val socialUsage = usage.filterKeys { app ->
            socialApps.any { app.contains(it) }
        }

        val top = socialUsage.maxByOrNull { it.value } ?: return emptyList()

        val minutes = top.value / 60000

        return listOf(
            Insight(
                type = "top_social_app",
                message = "Most used social app: ${top.key} (${minutes} min)",
                severity = "low"
            )
        )
    }

    //-----------detect short content addiction-------
    private fun detectShortVideoAddiction(
        usage: Map<String, Long>,
        youtube: List<Map<String, Any>>
    ): List<Insight> {

        val tiktokTime = usage["com.zhiliaoapp.musically"] ?: 0L
        val ytShorts = youtube.count {
            val title = it["title"] as? String ?: ""
            title.contains("short", ignoreCase = true)
        }

        val minutes = tiktokTime / 60000

        return if (minutes > 90 || ytShorts > 15) {
            listOf(
                Insight(
                    type = "short_video_addiction",
                    message = "High short-form video consumption detected",
                    severity = "high"
                )
            )
        } else emptyList()
    }


    //----------night usage detection-------
    private fun detectNightUsage(browsing: List<Map<String, Any>>): List<Insight> {
        val nightEvents = browsing.filter {
            val time = (it["time"] as? Number)?.toLong()
                ?: (it["createdAt"] as? Number)?.toLong()
                ?: 0L
            val hour = java.util.Calendar.getInstance().apply {
                timeInMillis = time
            }.get(java.util.Calendar.HOUR_OF_DAY)

            hour >= 22 || hour <= 5
        }

        return if (nightEvents.isNotEmpty()) {
            listOf(
                Insight(
                    type = "night_usage",
                    message = "Device used late at night (${nightEvents.size} events)",
                    severity = "high"
                )
            )
        } else emptyList()
    }

    //----------blocked attempts patterns---------
    private fun detectBlockedAttempts(blocked: List<Map<String, Any>>): List<Insight> {

        val count = blocked.size

        return if (count > 3) {
            listOf(
                Insight(
                    type = "blocked_attempts",
                    message = "Multiple blocked access attempts ($count)",
                    severity = "high"
                )
            )
        } else emptyList()
    }

    //---------------------risky behaviour detection----------------
    private fun detectRiskyBehavior(browsing: List<Map<String, Any>>): List<Insight> {
        val riskySites = browsing.count {
            val domain = it["domain"] as? String ?: ""
            domain.contains("adult") ||
                    domain.contains("bet") ||
                    domain.contains("casino")
        }

        return if (riskySites > 0) {
            listOf(
                Insight(
                    type = "risk",
                    message = "Potential risky browsing detected",
                    severity = "critical"
                )
            )
        } else emptyList()
    }

    //----------------save insights-----------
    private fun saveInsights(insights: List<Insight>) {
        insights.forEach { insight ->

            insightsRef.push().setValue(
                mapOf(
                    "type" to insight.type,
                    "message" to insight.message,
                    "severity" to insight.severity,
                    "timestamp" to insight.timestamp
                )
            )
        }
    }

    private fun detectDailySummary(usage: Map<String, Long>): List<Insight> {

        val totalTime = usage.values.sum() / 60000

        return listOf(
            Insight(
                type = "daily_summary",
                message = "Total screen time today: ${totalTime} minutes",
                severity = if (totalTime > 300) "high" else "low"
            )
        )
    }

    //-------------helper parsers---------
    private fun parseUsage(snapshot: DataSnapshot): Map<String, Long> {

        val usage = mutableMapOf<String, Long>()

        // date nodes
        snapshot.children.forEach { dateNode ->

            // app nodes
            dateNode.children.forEach { appNode ->

                var totalMillis = 0L

                // session nodes
                appNode.children.forEach { sessionNode ->

                    val seconds =
                        sessionNode.child("durationSeconds")
                            .getValue(Long::class.java)
                            ?: 0L

                    totalMillis += seconds * 1000
                }

                usage[appNode.key ?: "unknown"] = totalMillis
            }
        }

        return usage
    }

    private fun parseBrowsing(snapshot: DataSnapshot): List<Map<String, Any>> {

        val list = mutableListOf<Map<String, Any>>()

        snapshot.children.forEach { child ->

            val map = child.value

            if (map is Map<*, *>) {
                list.add(map as Map<String, Any>)
            }
        }

        return list
    }

    //-------------ai in sights on aalytics_breowsing-----------
    // ----------- VISITED URLS -----------
    private fun parseVisited(snapshot: DataSnapshot): List<Map<String, Any>> {

        val list = mutableListOf<Map<String, Any>>()

        snapshot.child("visited_urls")
            .children
            .forEach { dateSnapshot ->

                dateSnapshot.children.forEach { recordSnapshot ->

                    val map =
                        recordSnapshot.value as? Map<String, Any>

                    if (map != null) {
                        list.add(map)
                    }
                }
            }

        return list
    }

    // ----------- BLOCKED ATTEMPTS -----------
    private fun parseBlocked(snapshot: DataSnapshot): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        snapshot.child("blocked_attempts").children.forEach {
            val map = it.value as? Map<String, Any>
            if (map != null) list.add(map)
        }
        return list
    }

    // ----------- YOUTUBE HISTORY -----------
    private fun parseYoutube(snapshot: DataSnapshot): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        snapshot.child("youtube_history").children.forEach {
            val map = it.value as? Map<String, Any>
            if (map != null) list.add(map)
        }
        return list
    }

    //------------detect youtube addiction---------
    private fun detectYoutubeAddiction(youtube: List<Map<String, Any>>): List<Insight> {

        val totalVideos = youtube.count {
            val type = it["type"] as? String ?: ""
            type.contains("youtube_session")
        }

        return if (totalVideos > 10) {
            listOf(
                Insight(
                    type = "youtube_overuse",
                    message = "High YouTube usage detected ($totalVideos videos)",
                    severity = "medium"
                )
            )
        } else emptyList()
    }

    //---------generate daily reports--------------------------
    private fun generateDailyReport(
        usage: Map<String, Long>,
        score: SafetyScore
    ) {

        val totalMinutes = usage.values.sum() / 60000
        val mostUsed = usage.maxByOrNull { it.value }?.key ?: "None"

        val safetyLevel = when {
            score.score >= 80 -> "SAFE"
            score.score >= 50 -> "RISKY"
            else -> "DANGEROUS"
        }

        val advice = when (safetyLevel) {
            "SAFE" -> "Healthy digital habits 👍"
            "RISKY" -> "Monitor usage and reduce screen time"
            else -> "Immediate parental attention required ⚠️"
        }

        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

        val report = mapOf(
            "totalScreenTime" to totalMinutes,
            "safetyScore" to score.score,
            "safetyLevel" to safetyLevel,
            "advice" to advice,
            "mostUsedApp" to mostUsed,
            "timestamp" to System.currentTimeMillis()
        )

        // ✅ NEW NODE
        db.child("analytics_reports")
            .child(childId)
            .child(date)
            .setValue(report)
    }
}

