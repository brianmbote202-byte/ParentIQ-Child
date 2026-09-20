package com.parentalcontrol.childapp.utils

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WebsiteCategoryService {

    private const val TAG = "WebsiteCategoryService"

    // ============================================================
    // SITE CATEGORY API
    // ============================================================

    private const val SESSION_URL =
        "https://api.sitecategory.com/api/session"

    private const val CATEGORY_URL =
        "https://api.sitecategory.com/api/categorize"

    // ============================================================
    // NETWORK SETTINGS
    // ============================================================

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 10_000

    private const val MAX_RETRIES = 2

    private val executor =
        Executors.newCachedThreadPool()

    // ============================================================
    // SESSION TOKEN
    // ============================================================

    @Volatile
    private var sessionToken: String? = null

    @Volatile
    private var tokenCreatedAt: Long = 0L

    /*
     * We refresh the token periodically instead of assuming
     * it remains valid forever.
     *
     * The API uses session-based authentication.
     */
    private const val TOKEN_REFRESH_INTERVAL =
        6L * 60L * 60L * 1000L

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * Classifies a domain using SiteCategory.
     *
     * Example:
     *
     * classify("sportybet.com") { category ->
     *
     *     // gambling
     * }
     *
     * The callback is always executed asynchronously.
     *
     * Returns one of our internal categories:
     *
     * adult
     * gambling
     * games
     * video
     * social
     * messaging
     * academic
     * news
     * religious
     * books
     * ecommerce
     * general
     */
    fun classify(
        domain: String,
        callback: (String?) -> Unit
    ) {

        val normalizedDomain =
            normalizeDomain(domain)

        if (normalizedDomain.isBlank()) {

            Log.d(
                TAG,
                "Ignored blank domain"
            )

            callback(null)

            return
        }

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "CLASSIFYING DOMAIN"
        )

        Log.d(
            TAG,
            "Domain = $normalizedDomain"
        )

        executor.execute {

            try {

                val category =
                    classifyWithRetries(
                        normalizedDomain
                    )

                Log.d(
                    TAG,
                    "Final category = $category"
                )

                callback(category)

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Classification failed for $normalizedDomain",
                    e
                )

                callback(null)
            }
        }
    }

    // ============================================================
    // RETRY LOGIC
    // ============================================================

    private fun classifyWithRetries(
        domain: String
    ): String? {

        var attempt = 0

        while (attempt < MAX_RETRIES) {

            attempt++

            try {

                Log.d(
                    TAG,
                    "Classification attempt $attempt/$MAX_RETRIES"
                )

                val token =
                    getSessionToken(
                        forceRefresh = false
                    )

                if (token.isNullOrBlank()) {

                    Log.e(
                        TAG,
                        "Could not obtain SiteCategory session token"
                    )

                    continue
                }

                val result =
                    categorizeDomain(
                        domain = domain,
                        token = token
                    )

                if (result.success) {

                    return mapExternalCategory(
                        result.category
                    )
                }

                /*
                 * A 401/403 means our session token may have
                 * expired or become invalid.
                 *
                 * Force a fresh token and retry.
                 */
                if (
                    result.httpCode == 401 ||
                    result.httpCode == 403
                ) {

                    Log.d(
                        TAG,
                        "Session token rejected; refreshing token"
                    )

                    getSessionToken(
                        forceRefresh = true
                    )

                    continue
                }

                /*
                 * Rate limit.
                 */
                if (result.httpCode == 429) {

                    Log.w(
                        TAG,
                        "SiteCategory rate limit reached"
                    )

                    return null
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Attempt $attempt failed",
                    e
                )
            }
        }

        return null
    }

    // ============================================================
    // SESSION TOKEN
    // ============================================================

    private fun getSessionToken(
        forceRefresh: Boolean
    ): String? {

        val now =
            System.currentTimeMillis()

        val existingToken =
            sessionToken

        if (
            !forceRefresh &&
            !existingToken.isNullOrBlank() &&
            now - tokenCreatedAt <
            TOKEN_REFRESH_INTERVAL
        ) {

            return existingToken
        }

        synchronized(this) {

            /*
             * Check again after acquiring the lock.
             *
             * Another thread may have already refreshed it.
             */
            val currentToken =
                sessionToken

            if (
                !forceRefresh &&
                !currentToken.isNullOrBlank() &&
                now - tokenCreatedAt <
                TOKEN_REFRESH_INTERVAL
            ) {

                return currentToken
            }

            Log.d(
                TAG,
                "Requesting new SiteCategory session token"
            )

            val connection =
                try {

                    URL(
                        SESSION_URL
                    ).openConnection()
                            as HttpURLConnection

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to create session connection",
                        e
                    )

                    return null
                }

            try {

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    CONNECT_TIMEOUT

                connection.readTimeout =
                    READ_TIMEOUT

                connection.doOutput =
                    true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                /*
                 * Empty JSON body.
                 */
                OutputStreamWriter(
                    connection.outputStream
                ).use { writer ->

                    writer.write("{}")
                    writer.flush()
                }

                val responseCode =
                    connection.responseCode

                val responseBody =
                    readResponse(
                        connection
                    )

                Log.d(
                    TAG,
                    "Session HTTP = $responseCode"
                )

                Log.d(
                    TAG,
                    "Session response = $responseBody"
                )

                if (
                    responseCode !in 200..299
                ) {

                    return null
                }

                if (
                    responseBody.isBlank()
                ) {

                    return null
                }

                val json =
                    JSONObject(
                        responseBody
                    )

                val token =
                    json.optString(
                        "token"
                    ).trim()

                if (token.isBlank()) {

                    Log.e(
                        TAG,
                        "SiteCategory response contained no token"
                    )

                    return null
                }

                sessionToken =
                    token

                tokenCreatedAt =
                    System.currentTimeMillis()

                Log.d(
                    TAG,
                    "SiteCategory session token acquired"
                )

                return token

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to obtain SiteCategory token",
                    e
                )

                return null

            } finally {

                connection.disconnect()
            }
        }
    }

    // ============================================================
    // CATEGORY REQUEST
    // ============================================================

    private fun categorizeDomain(
        domain: String,
        token: String
    ): CategoryResult {

        val connection =
            try {

                URL(
                    CATEGORY_URL
                ).openConnection()
                        as HttpURLConnection

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to create category connection",
                    e
                )

                return CategoryResult(
                    success = false,
                    category = null,
                    httpCode = -1
                )
            }

        try {

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                CONNECT_TIMEOUT

            connection.readTimeout =
                READ_TIMEOUT

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )

            val requestBody =
                JSONObject()
                    .put(
                        "domain",
                        domain
                    )
                    .toString()

            Log.d(
                TAG,
                "Sending category request for $domain"
            )

            OutputStreamWriter(
                connection.outputStream
            ).use { writer ->

                writer.write(
                    requestBody
                )

                writer.flush()
            }

            val responseCode =
                connection.responseCode

            val responseBody =
                readResponse(
                    connection
                )

            Log.d(
                TAG,
                "Category HTTP = $responseCode"
            )

            Log.d(
                TAG,
                "Category response = $responseBody"
            )

            if (
                responseCode !in 200..299
            ) {

                return CategoryResult(
                    success = false,
                    category = null,
                    httpCode = responseCode
                )
            }

            if (
                responseBody.isBlank()
            ) {

                return CategoryResult(
                    success = false,
                    category = null,
                    httpCode = responseCode
                )
            }

            val json =
                JSONObject(
                    responseBody
                )

            val success =
                json.optBoolean(
                    "success",
                    true
                )

            if (!success) {

                Log.w(
                    TAG,
                    "SiteCategory reported success=false"
                )

                return CategoryResult(
                    success = false,
                    category = null,
                    httpCode = responseCode
                )
            }

            val externalCategory =
                json.optString(
                    "category"
                )
                    .trim()

            if (
                externalCategory.isBlank()
            ) {

                Log.d(
                    TAG,
                    "No category returned for $domain"
                )

                return CategoryResult(
                    success = true,
                    category = "general",
                    httpCode = responseCode
                )
            }

            Log.d(
                TAG,
                "External category = $externalCategory"
            )

            return CategoryResult(
                success = true,
                category = externalCategory,
                httpCode = responseCode
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Category request failed",
                e
            )

            return CategoryResult(
                success = false,
                category = null,
                httpCode = -1
            )

        } finally {

            connection.disconnect()
        }
    }

    // ============================================================
    // CATEGORY MAPPING
    // ============================================================

    /**
     * Converts SiteCategory's category names into the categories
     * already used by your application.
     *
     * We deliberately do NOT use loose substring matching here.
     *
     * Instead, category names are normalized and checked against
     * known category families.
     */
    private fun mapExternalCategory(
        external: String?
    ): String? {

        if (external.isNullOrBlank()) {
            return "general"
        }

        val value =
            external
                .trim()
                .lowercase()

        Log.d(
            TAG,
            "Mapping external category = $external"
        )

        /*
         * ========================================================
         * ADULT / SEXUAL
         * ========================================================
         */
        if (
            containsAny(
                value,
                "adult",
                "porn",
                "pornography",
                "sexual",
                "sexually explicit",
                "erotic",
                "nudity"
            )
        ) {

            return "adult"
        }

        /*
         * ========================================================
         * GAMBLING
         * ========================================================
         */
        if (
            containsAny(
                value,
                "gambling",
                "betting",
                "casino",
                "poker",
                "sports betting",
                "lottery",
                "wagering"
            )
        ) {

            return "gambling"
        }

        /*
         * ========================================================
         * GAMES
         * ========================================================
         */
        if (
            containsAny(
                value,
                "games",
                "gaming",
                "video games",
                "online games",
                "game"
            )
        ) {

            return "games"
        }

        /*
         * ========================================================
         * SOCIAL
         * ========================================================
         */
        if (
            containsAny(
                value,
                "social network",
                "social media",
                "social networking",
                "online community"
            )
        ) {

            return "social"
        }

        /*
         * ========================================================
         * MESSAGING
         * ========================================================
         */
        if (
            containsAny(
                value,
                "instant messaging",
                "messaging",
                "chat",
                "web chat"
            )
        ) {

            return "messaging"
        }

        /*
         * ========================================================
         * VIDEO
         * ========================================================
         */
        if (
            containsAny(
                value,
                "video",
                "video streaming",
                "streaming media",
                "streaming",
                "online video",
                "music streaming"
            )
        ) {

            return "video"
        }

        /*
         * ========================================================
         * ACADEMIC / EDUCATION
         * ========================================================
         */
        if (
            containsAny(
                value,
                "education",
                "educational",
                "academic",
                "e-learning",
                "online learning",
                "education technology",
                "universities",
                "schools"
            )
        ) {

            return "academic"
        }

        /*
         * ========================================================
         * NEWS
         * ========================================================
         */
        if (
            containsAny(
                value,
                "news",
                "newspapers",
                "current events",
                "journalism",
                "media"
            )
        ) {

            return "news"
        }

        /*
         * ========================================================
         * RELIGIOUS
         * ========================================================
         */
        if (
            containsAny(
                value,
                "religion",
                "religious",
                "faith",
                "christian",
                "christianity",
                "islam",
                "islamic",
                "judaism",
                "hinduism"
            )
        ) {

            return "religious"
        }

        /*
         * ========================================================
         * BOOKS
         * ========================================================
         */
        if (
            containsAny(
                value,
                "books",
                "book",
                "literature",
                "reading",
                "publishing"
            )
        ) {

            return "books"
        }

        /*
         * ========================================================
         * ECOMMERCE
         * ========================================================
         */
        if (
            containsAny(
                value,
                "e-commerce",
                "ecommerce",
                "shopping",
                "online shopping",
                "retail",
                "marketplace"
            )
        ) {

            return "ecommerce"
        }

        /*
         * ========================================================
         * GENERAL
         * ========================================================
         *
         * We intentionally don't try to force every API category
         * into one of the app categories.
         */
        return "general"
    }

    // ============================================================
    // STRING HELPERS
    // ============================================================

    private fun containsAny(
        value: String,
        vararg terms: String
    ): Boolean {

        return terms.any { term ->

            value.contains(
                term,
                ignoreCase = true
            )
        }
    }

    // ============================================================
    // DOMAIN NORMALIZATION
    // ============================================================

    private fun normalizeDomain(
        domain: String
    ): String {

        var clean =
            domain
                .trim()
                .lowercase()
                .trimEnd('.')

        /*
         * Remove URL scheme if accidentally supplied.
         */
        clean =
            clean
                .removePrefix("https://")
                .removePrefix("http://")

        /*
         * Remove www.
         */
        while (
            clean.startsWith("www.")
        ) {

            clean =
                clean.substring(4)
        }

        /*
         * Remove path.
         */
        clean =
            clean
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")

        /*
         * Remove port.
         */
        clean =
            clean.substringBefore(":")

        return clean.trim()
    }

    // ============================================================
    // HTTP RESPONSE READER
    // ============================================================

    private fun readResponse(
        connection: HttpURLConnection
    ): String {

        val stream =
            try {

                if (
                    connection.responseCode >= 400
                ) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }

            } catch (_: Exception) {
                connection.errorStream
            }

        if (stream == null) {
            return ""
        }

        return try {

            BufferedReader(
                InputStreamReader(
                    stream
                )
            ).use { reader ->

                val result =
                    StringBuilder()

                var line: String?

                while (
                    reader.readLine()
                        .also {
                            line = it
                        } != null
                ) {

                    result.append(
                        line
                    )
                }

                result.toString()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to read HTTP response",
                e
            )

            ""

        } finally {

            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    // ============================================================
    // INTERNAL RESULT
    // ============================================================

    private data class CategoryResult(
        val success: Boolean,
        val category: String?,
        val httpCode: Int
    )
}