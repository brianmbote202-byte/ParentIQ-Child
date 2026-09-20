package com.parentalcontrol.childapp.utils

import android.net.Uri
import java.util.Locale

/**
 * ContentClassifier
 *
 * Purpose:
 * - Classify websites using their actual hostname/domain.
 * - Avoid false positives caused by substring matching.
 * - Preserve the existing public API used by the app.
 *
 * IMPORTANT:
 * This is a DOMAIN-BASED classifier.
 *
 * It does NOT claim that every page on a general-purpose website
 * has the same content category.
 *
 * Example:
 *
 * roblox.com              -> games
 * www.roblox.com          -> games
 * blog.roblox.com         -> games
 * fakeroblox.com          -> general
 *
 * youtube.com             -> social
 * example.com/sex         -> general
 *
 * For unknown domains, an external categorization API can later
 * be added without changing the callers of this class.
 */
object ContentClassifier {

    // ============================================================
    // DOMAIN DATABASE
    // ============================================================

    /**
     * Adult / sexual-content domains.
     *
     * IMPORTANT:
     * Do NOT put generic words such as "sex", "adult", "xxx"
     * here because they create many false positives.
     *
     * Only add actual domains that are known to belong to this
     * category.
     */
    private val adultDomains = setOf(

        // Major adult sites
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "spankbang.com",
        "tube8.com",
        "porn.com",
        "hqporner.com",
        "beeg.com",
        "eporner.com",
        "rule34.xxx"
    )


    /**
     * Gambling / betting domains.
     */
    private val gamblingDomains = setOf(

        // Kenya / Africa
        "sportpesa.com",
        "sportpesa.co.ke",
        "betika.com",
        "betika.co.ke",
        "betway.com",
        "betway.co.ke",
        "odibets.com",
        "odibets.co.ke",
        "1xbet.com",
        "22bet.com",

        // International
        "bet365.com",
        "williamhill.com",
        "888.com",
        "888sport.com",
        "unibet.com",
        "bwin.com",
        "pokerstars.com",
        "draftkings.com",
        "fanduel.com"
    )


    /**
     * Gaming domains.
     *
     * Notice that we deliberately do NOT use:
     *
     * "game"
     * "games"
     * "play"
     *
     * as generic substring keywords.
     *
     * Otherwise:
     *
     * mybusinessgames.com
     *
     * could be incorrectly classified.
     */
    private val gamingDomains = setOf(

        // Roblox
        "roblox.com",

        // Minecraft
        "minecraft.net",
        "minecraft.net",

        // Epic / Fortnite
        "epicgames.com",
        "fortnite.com",

        // Steam
        "steampowered.com",
        "steamcommunity.com",

        // Nintendo
        "nintendo.com",

        // Xbox
        "xbox.com",

        // PlayStation
        "playstation.com",

        // Popular browser games
        "crazygames.com",
        "poki.com",
        "y8.com",
        "miniclip.com",
        "kongregate.com",
        "coolmathgames.com",

        // Game development / distribution
        "itch.io",
        "gamejolt.com",

        // EA
        "ea.com",

        // Ubisoft
        "ubisoft.com",

        // Riot Games
        "riotgames.com",
        "leagueoflegends.com",

        // Blizzard
        "blizzard.com",
        "battle.net",

        // Epic / Unreal
        "unrealengine.com"
    )


    /**
     * Academic / education domains.
     */
    private val academicDomains = setOf(

        // Research
        "scholar.google.com",
        "jstor.org",
        "arxiv.org",
        "academia.edu",
        "researchgate.net",

        // Education
        "khanacademy.org",
        "coursera.org",
        "edx.org",
        "udemy.com",

        // Universities / education
        "mit.edu",
        "stanford.edu",
        "harvard.edu",

        // Wikipedia / reference
        "wikipedia.org"
    )


    /**
     * News domains.
     */
    private val newsDomains = setOf(

        // International
        "bbc.com",
        "bbc.co.uk",
        "cnn.com",
        "reuters.com",
        "theguardian.com",
        "aljazeera.com",
        "nytimes.com",
        "washingtonpost.com",

        // Kenya / Africa
        "nation.africa",
        "standardmedia.co.ke",
        "citizen.digital",
        "the-star.co.ke",
        "tuko.co.ke",
        "capitalfm.co.ke",
        "ntvkenya.co.ke"
    )


    /**
     * Social-media domains.
     */
    private val socialDomains = setOf(

        "facebook.com",
        "fb.com",

        "instagram.com",

        "twitter.com",
        "x.com",

        "tiktok.com",

        "linkedin.com",

        "reddit.com",

        "pinterest.com",

        "snapchat.com"
    )


    /**
     * Messaging domains.
     *
     * Kept separate internally, but classifyDomain() can still
     * return "social" to preserve your existing category model.
     */
    private val messagingDomains = setOf(

        "whatsapp.com",
        "web.whatsapp.com",

        "telegram.org",
        "telegram.me",

        "signal.org",

        "messenger.com"
    )


    /**
     * Video / streaming domains.
     *
     * These are intentionally separate from social internally.
     */
    private val videoDomains = setOf(

        "youtube.com",
        "youtu.be",

        "vimeo.com",

        "dailymotion.com",

        "netflix.com",

        "twitch.tv",

        "primevideo.com",

        "crunchyroll.com"
    )


    /**
     * Religious domains.
     */
    private val religiousDomains = setOf(

        "bible.com",
        "biblegateway.com",

        "quran.com",

        "islamicity.org",

        "vatican.va"
    )


    /**
     * Books / reading domains.
     */
    private val bookDomains = setOf(

        "goodreads.com",

        "books.google.com",

        "archive.org",

        "openlibrary.org",

        "projectgutenberg.org"
    )


    /**
     * Ecommerce domains.
     */
    private val ecommerceDomains = setOf(

        // International
        "amazon.com",
        "amazon.co.uk",

        "ebay.com",

        "aliexpress.com",

        // Kenya
        "jumia.com",
        "jumia.co.ke",

        // Other
        "etsy.com",
        "walmart.com",
        "shopify.com"
    )


    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * Main domain classification function.
     *
     * Returns:
     *
     * adult
     * gambling
     * games
     * academic
     * news
     * social
     * religious
     * books
     * ecommerce
     * general
     */
    fun classifyDomain(domain: String): String {

        val host = extractHostname(domain)

        if (host.isBlank()) {
            return "general"
        }

        /*
         * Safety categories first.
         *
         * This prevents a domain from being treated as harmless
         * if it belongs to an explicitly restricted category.
         */
        return when {

            isAdult(host) ->
                "adult"

            isGambling(host) ->
                "gambling"

            isGame(host) ->
                "games"

            isAcademic(host) ->
                "academic"

            isNews(host) ->
                "news"

            isSocial(host) ->
                "social"

            /*
             * Messaging is represented as "social" so existing
             * UI/category handling does not need to change.
             */
            isMessaging(host) ->
                "messaging"

            isVideo(host) ->
                "video"

            isReligious(host) ->
                "religious"

            isBooks(host) ->
                "books"

            isEcommerce(host) ->
                "ecommerce"

            else ->
                "general"
        }
    }


    /**
     * Returns a blocking/safety flag for a URL.
     *
     * IMPORTANT:
     *
     * We only inspect the hostname.
     *
     * Therefore:
     *
     * https://example.com/sex-education
     *
     * does NOT automatically become "adult".
     *
     * The domain itself must be classified as adult/gambling.
     */
    fun urlFlag(url: String): String? {

        val host = extractHostname(url)

        if (host.isBlank()) {
            return null
        }

        return when {

            isAdult(host) ->
                "adult"

            isGambling(host) ->
                "gambling"

            else ->
                null
        }
    }


    // ============================================================
    // CATEGORY CHECKS
    // ============================================================

    /**
     * Check whether hostname belongs to an adult domain.
     */
    fun isAdult(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            adultDomains
        )
    }


    /**
     * Check whether hostname belongs to a gambling domain.
     */
    fun isGambling(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            gamblingDomains
        )
    }


    /**
     * Check whether hostname belongs to a gaming domain.
     */
    fun isGame(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            gamingDomains
        )
    }


    /**
     * Check whether hostname belongs to an academic domain.
     */
    fun isAcademic(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            academicDomains
        )
    }


    /**
     * Check whether hostname belongs to a news domain.
     */
    fun isNews(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            newsDomains
        )
    }


    /**
     * Check whether hostname belongs to a social-media domain.
     */
    fun isSocial(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            socialDomains
        )
    }


    /**
     * Check whether hostname belongs to a messaging domain.
     */
    fun isMessaging(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            messagingDomains
        )
    }


    /**
     * Check whether hostname belongs to a video/streaming domain.
     */
    fun isVideo(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            videoDomains
        )
    }


    /**
     * Check whether hostname belongs to a religious domain.
     */
    fun isReligious(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            religiousDomains
        )
    }


    /**
     * Check whether hostname belongs to a books/reading domain.
     */
    fun isBooks(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            bookDomains
        )
    }


    /**
     * Check whether hostname belongs to an ecommerce domain.
     */
    fun isEcommerce(domain: String): Boolean {

        val host = extractHostname(domain)

        return matchesAnyDomain(
            host,
            ecommerceDomains
        )
    }


    // ============================================================
    // HOSTNAME EXTRACTION
    // ============================================================

    /**
     * Converts an input URL/domain into a clean hostname.
     *
     * Handles:
     *
     * https://www.roblox.com/games
     * http://roblox.com
     * www.roblox.com
     * roblox.com
     * ROBLOX.COM
     *
     * and returns:
     *
     * roblox.com
     *
     * We intentionally ignore:
     *
     * paths
     * query strings
     * fragments
     *
     * because those should not determine the primary domain category.
     */
    private fun extractHostname(value: String): String {

        var input = value
            .trim()
            .lowercase(Locale.US)

        if (input.isBlank()) {
            return ""
        }

        /*
         * Remove surrounding whitespace and quotes that sometimes
         * appear in accessibility/browser data.
         */
        input = input
            .trim()
            .trim('"')
            .trim('\'')

        /*
         * If the value does not have a scheme, prepend https://
         * so Android Uri can correctly identify the host.
         */
        val uri = try {

            if (
                input.startsWith("http://") ||
                input.startsWith("https://")
            ) {
                Uri.parse(input)
            } else {
                Uri.parse("https://$input")
            }

        } catch (_: Exception) {
            return ""
        }

        var host = uri.host
            ?.trim()
            ?.lowercase(Locale.US)
            ?: ""

        /*
         * Remove a trailing dot.
         *
         * Example:
         *
         * roblox.com.
         *
         * becomes:
         *
         * roblox.com
         */
        host = host.trimEnd('.')

        /*
         * Remove repeated www prefix only when it is directly
         * at the beginning.
         *
         * This means:
         *
         * www.roblox.com -> roblox.com
         *
         * but:
         *
         * www2.roblox.com -> www2.roblox.com
         */
        while (host.startsWith("www.")) {
            host = host.substring(4)
        }

        return host
    }


    // ============================================================
    // DOMAIN MATCHING
    // ============================================================

    /**
     * Safely matches a hostname against a known domain.
     *
     * This is the critical difference from:
     *
     * domain.contains("game")
     *
     * We only return true when:
     *
     * host == knownDomain
     *
     * OR
     *
     * host ends with ".knownDomain"
     *
     * Therefore:
     *
     * roblox.com
     * www.roblox.com
     * api.roblox.com
     *
     * all match:
     *
     * roblox.com
     *
     * But:
     *
     * fakeroblox.com
     * roblox.com.fakewebsite.com
     *
     * do NOT match.
     */
    private fun matchesDomain(
        host: String,
        knownDomain: String
    ): Boolean {

        val normalizedHost =
            normalizeHostname(host)

        val normalizedKnown =
            normalizeHostname(knownDomain)

        if (
            normalizedHost.isBlank() ||
            normalizedKnown.isBlank()
        ) {
            return false
        }

        return normalizedHost == normalizedKnown ||
                normalizedHost.endsWith(
                    ".$normalizedKnown"
                )
    }


    /**
     * Checks a hostname against an entire domain set.
     */
    private fun matchesAnyDomain(
        host: String,
        domains: Set<String>
    ): Boolean {

        val normalizedHost =
            normalizeHostname(host)

        if (normalizedHost.isBlank()) {
            return false
        }

        return domains.any { knownDomain ->

            matchesDomain(
                normalizedHost,
                knownDomain
            )
        }
    }


    /**
     * Normalizes a hostname before comparison.
     */
    private fun normalizeHostname(
        value: String
    ): String {

        var host =
            value
                .trim()
                .lowercase(Locale.US)
                .trimEnd('.')

        /*
         * Remove www only at the beginning.
         */
        while (host.startsWith("www.")) {
            host = host.substring(4)
        }

        return host
    }


    // ============================================================
    // OPTIONAL DEBUG / DIAGNOSTIC API
    // ============================================================

    /**
     * Returns the hostname that the classifier sees.
     *
     * Useful while debugging browsing logs.
     *
     * Example:
     *
     * getHostname("https://www.roblox.com/games?id=123")
     *
     * -> roblox.com
     */
    fun getHostname(value: String): String {

        return extractHostname(value)
    }


    /**
     * Returns true when the supplied value is a valid-looking
     * hostname that the classifier can inspect.
     */
    fun hasHostname(value: String): Boolean {

        return extractHostname(value).isNotBlank()
    }
}