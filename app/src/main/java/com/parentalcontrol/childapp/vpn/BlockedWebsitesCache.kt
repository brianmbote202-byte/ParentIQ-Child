package com.parentalcontrol.childapp.vpn

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.net.URL

object BlockedWebsitesCache {

    private val blockedWebsites = mutableSetOf<String>()

    // Lazy Firebase reference for alerts
    private val alertsRef by lazy {
        val prefs = AppContext.instance.getSharedPreferences("child_prefs", 0)
        val childId = prefs.getString("child_id", "unknown_child")
        FirebaseDatabase.getInstance()
            .getReference("alerts")
            .child(childId ?: "unknown_child")
    }

    /** Update the blocked domains from Firebase */
    fun update(newList: Collection<String>) {
        blockedWebsites.clear()
        blockedWebsites.addAll(newList.map { cleanDomain(it) })
        Log.d("VPN_BLOCK", "Blocked list updated: $blockedWebsites")
    }

    /** Check if a URL or domain is blocked */
    fun isBlocked(url: String): Boolean {
        val domain = try {
            URL(if (url.startsWith("http")) url else "https://$url").host.lowercase()
        } catch (e: Exception) {
            return false
        }

        val cleanedDomain = cleanDomain(domain)

        Log.d("BLOCK_SERVICE", "Checking domain: $cleanedDomain")
        Log.d("BLOCK_SERVICE", "Blocked list: $blockedWebsites")

        // Check exact match or any subdomain
        val blocked = blockedWebsites.any {
            cleanedDomain == it || cleanedDomain.endsWith(".$it")
        }

        if (blocked) {
            Log.d("BLOCK_SERVICE", "BLOCKED DOMAIN MATCH: $cleanedDomain")
            reportBlockedAttempt(cleanedDomain)
        } else {
            Log.d("BLOCK_SERVICE", "ALLOWED DOMAIN: $cleanedDomain")
        }

        return blocked
    }

    /** Report blocked attempt directly to Firebase */
    private fun reportBlockedAttempt(domain: String) {
        val alert = mapOf(
            "domain" to domain,
            "timestamp" to System.currentTimeMillis()
        )
        alertsRef.push().setValue(alert)
        Log.d("BLOCK_SERVICE", "Reported blocked attempt: $domain")
    }

    /** Clean domain utility — strips http, www, trailing dot, and paths */
    private fun cleanDomain(input: String): String {
        return input
            .lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\."), "")
            .replace(Regex("\\.$"), "") // remove trailing dot
            .split("/")[0]               // remove paths
            .trim()
    }

    /** Application context helper for lazy Firebase reference */
    class AppContext : android.app.Application() {
        companion object {
            lateinit var instance: AppContext
                private set
        }
        override fun onCreate() {
            super.onCreate()
            instance = this
        }
    }
}