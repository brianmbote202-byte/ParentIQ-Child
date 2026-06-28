package com.parentalcontrol.childapp.setup

import android.content.Context
import android.provider.Settings

object AccessibilityUtils {

    fun isAccessibilityEnabled(
        context: Context
    ): Boolean {

        return try {

            val expectedService =
                "${context.packageName}/com.parentalcontrol.childapp.accessibility.BrowserAccessibilityService"

            val enabledServices =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

            enabledServices
                .split(":")
                .any {
                    it.equals(expectedService, ignoreCase = true)
                }

        } catch (e: Exception) {
            false
        }
    }
}