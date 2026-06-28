package com.parentalcontrol.childapp.detector

object SearchIntentDetector {

    private var lastQuery = ""
    private var lastTime = 0L
    private const val COOLDOWN = 3000L

    fun detect(appPackage: String, text: String?): String? {
        if (text.isNullOrBlank()) return null

        val clean = text.lowercase().trim()

        if (!isLikelySearch(clean)) return null

        val now = System.currentTimeMillis()

        // 🔥 Prevent duplicates
        if (clean == lastQuery && now - lastTime < COOLDOWN) {
            return null
        }

        lastQuery = clean
        lastTime = now

        return clean
    }

    private fun isLikelySearch(text: String): Boolean {

        if (text.length < 3) return false
        if (text.matches(Regex("^[0-9]+$"))) return false
        if (text.startsWith("http")) return false

        val keywords = listOf(
            "how to", "what is", "why", "who", "where",
            "best", "top", "tutorial", "guide",
            "download", "watch", "buy", "near me"
        )

        return keywords.any { text.contains(it) }
    }
}