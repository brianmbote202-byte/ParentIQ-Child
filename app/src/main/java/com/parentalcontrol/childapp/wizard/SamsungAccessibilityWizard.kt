package com.parentalcontrol.childapp.wizard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

class SamsungAccessibilityWizard(
    private val context: Context,
    private val onInstruction: (step: Double, message: String, retry: (() -> Unit)?) -> Unit,
    private val onStepComplete: () -> Unit
) {

    private val accessibilityServiceName =
        "${context.packageName}/com.parentalcontrol.childapp.accessibility.BrowserUrlService2"

    fun startWizard() {
        if (isAccessibilityEnabled()) {
            onStepComplete()
            return
        }

        // Step 3 instruction
        onInstruction(
            3.0,
            "STEP 3:\nEnable Accessibility for ChildApp.\nFind 'Child App' and turn ON the service.",
            { startWizard() } // retry logic, passed positionally
        )

        openAccessibilitySettings()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(accessibilityServiceName) ?: false
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Unable to open Accessibility Settings. Please open manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}