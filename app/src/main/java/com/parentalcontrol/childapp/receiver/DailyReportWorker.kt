package com.parentalcontrol.childapp.receiver

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.util.Log

class DailyReportWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        generateDailyReport()
        return Result.success()
    }

    private fun generateDailyReport() {
        val prefs = applicationContext.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val childId = prefs.getString("child_id", null) ?: return

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val db = FirebaseDatabase.getInstance().reference

        val usageRef = db.child("children").child(childId).child("app_usage").child(date)
        val blockedRef = db.child("children").child(childId).child("blocked_attempts").child(date)
        val reportRef = db.child("children").child(childId).child("reports").child(date)

        usageRef.get().addOnSuccessListener { snapshot ->
            var totalMinutes = 0
            var mostUsedApp = ""
            var mostUsedMinutes = 0

            for (appSnap in snapshot.children) {
                val minutes = appSnap.child("usedMinutes").getValue(Int::class.java) ?: 0
                totalMinutes += minutes

                if (minutes > mostUsedMinutes) {
                    mostUsedMinutes = minutes
                    mostUsedApp = appSnap.key ?: ""
                }
            }

            blockedRef.get().addOnSuccessListener { blockedSnap ->
                val blockedAttempts = blockedSnap.childrenCount.toInt()

                val safetyScore = calculateSafetyScore(totalMinutes, blockedAttempts)
                val safetyLevel = getSafetyLevel(safetyScore)
                val advice = generateAdvice(totalMinutes, blockedAttempts, mostUsedApp)

                val report = mapOf(
                    "totalScreenTime" to totalMinutes,
                    "mostUsedApp" to mostUsedApp,
                    "blockedAttempts" to blockedAttempts,
                    "safetyScore" to safetyScore,
                    "safetyLevel" to safetyLevel,
                    "advice" to advice,
                    "timestamp" to ServerValue.TIMESTAMP
                )

                reportRef.setValue(report)
            }
        }
    }

    private fun calculateSafetyScore(screenTime: Int, blocked: Int): Int {
        var score = 100

        if (screenTime > 300) score -= 30
        else if (screenTime > 180) score -= 15

        if (blocked > 5) score -= 10

        if (score < 0) score = 0
        return score
    }

    private fun getSafetyLevel(score: Int): String {
        return when {
            score >= 80 -> "SAFE"
            score >= 50 -> "RISKY"
            else -> "DANGEROUS"
        }
    }

    private fun generateAdvice(screenTime: Int, blocked: Int, mostUsedApp: String): String {
        if (screenTime > 300) return "Screen time is very high. Consider reducing usage."
        if (blocked > 5) return "Child attempted blocked apps multiple times."
        if (mostUsedApp.contains("youtube", true)) return "High video usage detected."
        return "Usage looks normal."
    }
}