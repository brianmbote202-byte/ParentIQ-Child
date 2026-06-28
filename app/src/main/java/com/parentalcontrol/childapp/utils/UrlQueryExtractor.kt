package com.parentalcontrol.childapp.utils

import android.net.Uri

object UrlQueryExtractor {

    fun extractQueryFromUrl(url: String): String? {
        val uri = Uri.parse(url)

        return when {
            url.contains("google.") ->
                uri.getQueryParameter("q")

            url.contains("youtube.com/results") ->
                uri.getQueryParameter("search_query")

            url.contains("bing.") ->
                uri.getQueryParameter("q")

            else -> null
        }
    }
}