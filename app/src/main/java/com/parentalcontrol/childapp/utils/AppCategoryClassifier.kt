package com.parentalcontrol.childapp.util

object AppCategoryClassifier {

    fun getAppCategory(packageName: String): String {

        val pkg = packageName.lowercase()

        return when {

            // ------------------------------------------------
            // SOCIAL
            // ------------------------------------------------
            pkg.contains("whatsapp") ||
                    pkg.contains("facebook") ||
                    pkg.contains("instagram") ||
                    pkg.contains("tiktok") ||
                    pkg.contains("snapchat") ||
                    pkg.contains("twitter") ||
                    pkg.contains("telegram") ||
                    pkg.contains("messenger") ||
                    pkg.contains("reddit") ->
                "social"

            // ------------------------------------------------
            // GAMES
            // ------------------------------------------------
            pkg.contains("game") ||
                    pkg.contains("pubg") ||
                    pkg.contains("freefire") ||
                    pkg.contains("clash") ||
                    pkg.contains("candy") ||
                    pkg.contains("arcade") ||
                    pkg.contains("gaming") ->
                "games"

            // ------------------------------------------------
            // PRODUCTIVITY / EDUCATION
            // ------------------------------------------------
            pkg.contains("docs") ||
                    pkg.contains("drive") ||
                    pkg.contains("sheets") ||
                    pkg.contains("slides") ||
                    pkg.contains("office") ||
                    pkg.contains("wps") ||
                    pkg.contains("microsoft") ||
                    pkg.contains("notion") ||
                    pkg.contains("pdf") ->
                "productivity"

            // ------------------------------------------------
            // BROWSER
            // ------------------------------------------------
            pkg.contains("chrome") ||
                    pkg.contains("firefox") ||
                    pkg.contains("opera") ||
                    pkg.contains("browser") ->
                "browser"

            // ------------------------------------------------
            // FINANCE
            // ------------------------------------------------
            pkg.contains("bank") ||
                    pkg.contains("paypal") ||
                    pkg.contains("binance") ||
                    pkg.contains("wallet") ||
                    pkg.contains("crypto") ||
                    pkg.contains("coin") ->
                "finance"

            // ------------------------------------------------
            // FALLBACK SMART DETECTION
            // ------------------------------------------------
            else -> inferFromPackage(pkg)
        }
    }

    // ------------------------------------------------
    // 🔥 SMART FALLBACK (IMPORTANT)
    // ------------------------------------------------
    private fun inferFromPackage(packageName: String): String {

        val parts = packageName.split(".", "_", "-")

        return when {

            parts.any { it in listOf("game", "play", "arcade") } ->
                "games"

            parts.any { it in listOf("chat", "messenger", "social", "msg") } ->
                "social"

            parts.any {
                it in listOf(
                    "docs",
                    "drive",
                    "office",
                    "sheet",
                    "slides",
                    "pdf",
                    "wps"
                )
            } ->
                "productivity"

            parts.any { it in listOf("bank", "pay", "wallet", "crypto") } ->
                "finance"

            parts.any { it in listOf("browser", "web", "chrome", "firefox") } ->
                "browser"

            else -> "other"
        }
    }
}