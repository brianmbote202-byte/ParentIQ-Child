package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

class UrlUploadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        Log.d("URL_UPLOAD", "Receiver triggered")

        val url = intent.getStringExtra("url")
        val pkg = intent.getStringExtra("package") ?: "unknown"
        val searchQuery = intent.getStringExtra("search_query")

        if (url.isNullOrBlank()) {
            Log.e("URL_UPLOAD", "URL is null — exiting")
            return
        }

        val prefs = context.applicationContext
            .getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

        val childId = prefs.getString("child_id", null)

        if (childId.isNullOrBlank()) {
            Log.e("URL_UPLOAD", "childId null — not saving")
            return
        }

        val database = FirebaseDatabase.getInstance()
            .getReference("children")
            .child(childId)
            .child("browsing")

        // 🔴 If search detected → save to search_history
        if (!searchQuery.isNullOrBlank()) {

            val searchData = mapOf(
                "query" to searchQuery,
                "engine" to detectEngine(url),
                "package" to pkg,
                "timestamp" to ServerValue.TIMESTAMP,
                "risk_level" to "unknown"
            )

            database.child("search_history")
                .push()
                .setValue(searchData)

            Log.d("URL_UPLOAD", "Search saved")

        } else {
            // 🌐 Normal website visit
            val visitData = mapOf(
                "url" to url,
                "package" to pkg,
                "timestamp" to ServerValue.TIMESTAMP
            )

            database.child("visited_urls")
                .push()
                .setValue(visitData)

            Log.d("URL_UPLOAD", "Website saved")
        }
    }

    private fun detectEngine(url: String): String {
        return when {
            url.contains("google.") -> "Google"
            url.contains("bing.com") -> "Bing"
            url.contains("duckduckgo.com") -> "DuckDuckGo"
            url.contains("yahoo.com") -> "Yahoo"
            else -> "Unknown"
        }
    }
}