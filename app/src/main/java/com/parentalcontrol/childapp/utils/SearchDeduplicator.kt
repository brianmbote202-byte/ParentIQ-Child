package com.parentalcontrol.childapp.utils

import android.net.Uri

class SearchDeduplicator {

    private var lastQuery = ""
    private var lastTime = 0L

    private val COOLDOWN = 15000L

    fun shouldSave(
        query: String,
        time: Long
    ): Boolean {

        val clean = Uri.decode(query).trim().lowercase()

        if (clean.length < 2) return false

        if (clean == lastQuery && time - lastTime < COOLDOWN) {
            return false
        }

        lastQuery = clean
        lastTime = time

        return true
    }

    fun normalize(query: String): String {
        return Uri.decode(query).trim().lowercase()
    }
}