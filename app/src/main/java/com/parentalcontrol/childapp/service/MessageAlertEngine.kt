package com.parentalcontrol.childapp.service

import java.util.Locale

/**
 * MessageAlertEngine
 *
 * Performs LOCAL message analysis.
 *
 * IMPORTANT:
 * The complete SMS does NOT need to be uploaded to Firebase
 * merely for keyword analysis.
 *
 * Flow:
 *
 * SMS received/sent
 *        ↓
 * MessageAlertEngine.analyze()
 *        ↓
 * Match found?
 *     ↓       ↓
 *    NO      YES
 *    ↓        ↓
 * nothing   AlertMatch
 *              ↓
 *       MessageService
 *              ↓
 *       Firebase alert
 */
object MessageAlertEngine {

    // =========================================================
    // CATEGORIES
    // =========================================================

    enum class Category {
        ADULT,
        GAMBLING,
        GROOMING,
        MONEY,
        FINANCIAL_CREDENTIAL
    }

    // =========================================================
    // SEVERITY
    // =========================================================

    enum class Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    // =========================================================
    // ALERT MATCH
    // =========================================================

    data class AlertMatch(
        val category: Category,
        val severity: Severity,
        val matchedKeywords: List<String>
    )

    // =========================================================
    // ANALYSIS RESULT
    // =========================================================

    data class AnalysisResult(
        val matched: Boolean,
        val matches: List<AlertMatch>
    ) {

        /**
         * Highest severity found in this message.
         */
        fun highestSeverity(): Severity? {

            if (matches.isEmpty()) {
                return null
            }

            return matches
                .map { it.severity }
                .maxByOrNull { severityRank(it) }
        }

        /**
         * All categories detected.
         */
        fun categories(): List<Category> {

            return matches
                .map { it.category }
                .distinct()
        }
    }

    // =========================================================
    // ADULT CONTENT
    // =========================================================

    private val adultKeywords = listOf(

        "adult",

        "porn",
        "pornography",
        "xxx",
        "nude",
        "nudity",
        "naked",
        "explicit",
        "sex",
        "sexual",
        "erotic",
        "intimate",
        "onlyfans",
        "nsfw",
        "hookup",
        "hook up",
        "camgirl",
        "webcam",
        "strip",
        "stripper"
    )

    // =========================================================
    // GAMBLING
    // =========================================================

    private val gamblingKeywords = listOf(

        "gamble",
        "gambling",
        "bet",
        "betting",
        "casino",
        "sportsbook",
        "odds",
        "wager",
        "wagering",
        "jackpot",
        "slot",
        "slots",
        "poker",
        "roulette",
        "blackjack",
        "lottery",
        "stake",
        "stakebet",
        "bet slip",
        "cash out"
    )

    // =========================================================
    // GROOMING / SUSPICIOUS CONTACT
    // =========================================================

    private val groomingKeywords = listOf(

        "keep this secret",

        "don't tell your parents",
        "dont tell your parents",

        "don't tell anyone",
        "dont tell anyone",

        "our little secret",

        "delete this chat",
        "delete our messages",

        "send me a picture",
        "send me a photo",

        "private picture",
        "private photo",

        "send a selfie",
        "send me a selfie",

        "are you alone",

        "where do you live",

        "what school do you go to",
        "what school do you attend",

        "can we meet",
        "meet me alone",

        "come alone",
        "don't bring anyone",
        "dont bring anyone",

        "don't tell your mom",
        "dont tell your mom",

        "don't tell your dad",
        "dont tell your dad",

        "how old are you",

        "age",

        "are you home alone",

        "can i call you privately",
        "can i call you private",

        "move to another chat",

        "talk somewhere private"
    )

    // =========================================================
    // MONEY / FINANCIAL ACTIVITY
    // =========================================================

    private val moneyKeywords = listOf(

        "money",
        "cash",

        "send money",
        "send me money",

        "pay me",
        "payment",
        "pay",

        "transfer",

        "bank",
        "bank account",

        "account number",
        "account details",

        "mpesa",
        "m-pesa",
        "airtel money",

        "cash out",

        "withdraw",
        "deposit",

        "loan",
        "borrow",
        "debt",
        "owe",

        "credit",
        "card",
        "credit card",
        "debit card",

        "pin",
        "pin number",

        "password",
        "passcode",

        "otp",
        "verification code",
        "security code",

        "transaction",
        "transaction fee",
        "fee",

        "refund",
        "invoice",

        "salary",
        "commission"
    )

    // =========================================================
    // FINANCIAL CREDENTIALS
    //
    // HIGHER SEVERITY THAN NORMAL MONEY DISCUSSION
    // =========================================================

    private val financialCredentialKeywords = listOf(

        "otp",

        "one time password",

        "verification code",

        "security code",

        "pin",

        "pin number",

        "password",

        "passcode",

        "bank password",

        "account password",

        "card number",

        "cvv",

        "security number",

        "login code",

        "verification"
    )

    // =========================================================
    // PUBLIC ANALYSIS FUNCTION
    // =========================================================

    /**
     * Analyze one SMS locally.
     *
     * direction should normally be:
     *
     * INCOMING
     * OUTGOING
     *
     * The direction is returned to MessageService separately
     * and does not change keyword detection.
     */
    fun analyze(
        message: String?
    ): AnalysisResult {

        if (message.isNullOrBlank()) {

            return AnalysisResult(
                matched = false,
                matches = emptyList()
            )
        }

        val normalized = normalize(message)

        val results = mutableListOf<AlertMatch>()

        // =====================================================
        // ADULT
        // =====================================================

        val adultMatches =
            findMatches(
                normalized,
                adultKeywords
            )

        if (adultMatches.isNotEmpty()) {

            results.add(
                AlertMatch(
                    category = Category.ADULT,
                    severity = Severity.HIGH,
                    matchedKeywords = adultMatches
                )
            )
        }

        // =====================================================
        // GAMBLING
        // =====================================================

        val gamblingMatches =
            findMatches(
                normalized,
                gamblingKeywords
            )

        if (gamblingMatches.isNotEmpty()) {

            results.add(
                AlertMatch(
                    category = Category.GAMBLING,
                    severity = Severity.HIGH,
                    matchedKeywords = gamblingMatches
                )
            )
        }

        // =====================================================
        // GROOMING
        // =====================================================

        val groomingMatches =
            findMatches(
                normalized,
                groomingKeywords
            )

        if (groomingMatches.isNotEmpty()) {

            results.add(
                AlertMatch(
                    category = Category.GROOMING,
                    severity = Severity.HIGH,
                    matchedKeywords = groomingMatches
                )
            )
        }

        // =====================================================
        // MONEY
        // =====================================================

        val moneyMatches =
            findMatches(
                normalized,
                moneyKeywords
            )

        if (moneyMatches.isNotEmpty()) {

            results.add(
                AlertMatch(
                    category = Category.MONEY,
                    severity = Severity.MEDIUM,
                    matchedKeywords = moneyMatches
                )
            )
        }

        // =====================================================
        // FINANCIAL CREDENTIAL
        // =====================================================

        val credentialMatches =
            findMatches(
                normalized,
                financialCredentialKeywords
            )

        if (credentialMatches.isNotEmpty()) {

            results.add(
                AlertMatch(
                    category = Category.FINANCIAL_CREDENTIAL,
                    severity = Severity.CRITICAL,
                    matchedKeywords = credentialMatches
                )
            )
        }

        return AnalysisResult(
            matched = results.isNotEmpty(),
            matches = results
        )
    }

    // =========================================================
    // MATCHING
    // =========================================================

    private fun findMatches(
        message: String,
        keywords: List<String>
    ): List<String> {

        return keywords
            .asSequence()
            .filter { keyword ->

                containsKeyword(
                    message,
                    keyword
                )
            }
            .distinct()
            .toList()
    }

    /**
     * Word/phrase-aware matching.
     *
     * This prevents:
     *
     * "bet"
     *
     * from matching:
     *
     * "better"
     *
     * while still matching:
     *
     * "I want to bet"
     */
    private fun containsKeyword(
        message: String,
        keyword: String
    ): Boolean {

        val normalizedKeyword =
            normalize(keyword)

        if (normalizedKeyword.isEmpty()) {
            return false
        }

        val regex =
            Regex(
                "(^|\\s|[^a-z0-9])" +
                        Regex.escape(normalizedKeyword) +
                        "($|\\s|[^a-z0-9])",
                RegexOption.IGNORE_CASE
            )

        return regex.containsMatchIn(message)
    }

    // =========================================================
    // NORMALIZATION
    // =========================================================

    private fun normalize(
        value: String
    ): String {

        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(
                Regex("\\s+"),
                " "
            )
    }

    // =========================================================
    // SEVERITY RANK
    // =========================================================

    private fun severityRank(
        severity: Severity
    ): Int {

        return when (severity) {

            Severity.LOW ->
                1

            Severity.MEDIUM ->
                2

            Severity.HIGH ->
                3

            Severity.CRITICAL ->
                4
        }
    }
}