package com.parentalcontrol.childapp.utils

object ContentClassifier {

    // ---------------- DOMAIN CATEGORY LISTS ----------------
    private val adult = listOf(
        "porn", "xvideos", "pornhub", "onlyfans", "adult", "sex"
    )

    private val gambling = listOf(
        "bet", "casino", "sportpesa", "odds", "poker", "betway"
    )

    private val games = listOf(
        "game", "games", "roblox", "minecraft", "fortnite", "steam"
    )

    private val academic = listOf(
        "edu.", "scholar", "research", "academia", "jstor", "arxiv"
    )

    private val news = listOf(
        "news", "bbc", "cnn", "reuters", "guardian", "aljazeera"
    )

    private val social = listOf(
        "facebook", "fb.com",
        "instagram",
        "twitter", "x.com",
        "tiktok",
        "linkedin",
        "youtube",
        "whatsapp",
        "telegram"
    )

    private val religious = listOf(
        "bible", "quran", "islam", "christian", "religion"
    )

    private val books = listOf(
        "goodreads", "books", "amazon.com/books", "wikipedia"
    )

    private val ecommerce = listOf(
        "amazon", "ebay", "shop", "store", "aliexpress", "jumia"
    )

    // ---------------- PUBLIC API ----------------

    fun classifyDomain(domain: String): String {
        val d = domain.lowercase()

        return when {
            isAdult(d) -> "adult"
            isGambling(d) -> "gambling"
            isGame(d) -> "games"
            isAcademic(d) -> "academic"
            isNews(d) -> "news"
            isSocial(d) -> "social"
            isReligious(d) -> "religious"
            isBooks(d) -> "books"
            isEcommerce(d) -> "ecommerce"
            else -> "general"
        }
    }

    fun urlFlag(url: String): String? {
        val d = url.lowercase()
        return when {
            isAdult(d) -> "adult"
            isGambling(d) -> "gambling"
            else -> null
        }
    }

    // ---------------- CATEGORY CHECKS ----------------

    fun isAdult(domain: String) =
        adult.any { domain.contains(it) }

    fun isGambling(domain: String) =
        gambling.any { domain.contains(it) }

    fun isGame(domain: String) =
        games.any { domain.contains(it) }

    fun isAcademic(domain: String) =
        academic.any { domain.contains(it) }

    fun isNews(domain: String) =
        news.any { domain.contains(it) }

    fun isSocial(domain: String) =
        social.any { domain.contains(it) }

    fun isReligious(domain: String) =
        religious.any { domain.contains(it) }

    fun isBooks(domain: String) =
        books.any { domain.contains(it) }

    fun isEcommerce(domain: String) =
        ecommerce.any { domain.contains(it) }
}