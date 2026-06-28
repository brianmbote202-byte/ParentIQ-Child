package com.parentalcontrol.childapp.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.parentalcontrol.childapp.R
import android.location.LocationManager
import android.os.PowerManager

class PermissionFragment(
    private val iconRes: Int,
    private val titleText: String,
    private val descriptionText: String,
    private val steps: List<String>,
    private val samsungFlow: List<String>? = null
) : Fragment(R.layout.layout_permission_screen) {

    private var statusIcon: ImageView? = null
    private var statusText: TextView? = null
    private var listener: OnPermissionCheckListener? = null
    private lateinit var stepsRecycler: RecyclerView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.permissionIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.permissionTitle).text = titleText
        view.findViewById<TextView>(R.id.permissionDescription).text = descriptionText

        statusIcon = view.findViewById(R.id.statusIcon)
        statusText = view.findViewById(R.id.statusText)
        updateStatus(false)

        stepsRecycler = view.findViewById(R.id.stepsRecycler)
        stepsRecycler.layoutManager = LinearLayoutManager(requireContext())

        val allSteps = if (Build.MANUFACTURER.equals("samsung", true) && samsungFlow != null) {
            steps + samsungFlow
        } else steps

        stepsRecycler.adapter = StepsAdapter(allSteps)

        // Grant button → open App Info (Samsung friendly)
        view.findViewById<MaterialButton>(R.id.btnGrant).setOnClickListener {
            openAppInfo()
        }

        // Check permission button
        view.findViewById<MaterialButton>(R.id.btnCheck).setOnClickListener {
            val granted = isPermissionGranted()
            updateStatus(granted)

            if (granted) {
                listener?.onCheckPermission()
            } else {
                showNotGrantedDialog()
            }
        }
    }

    private fun openAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${requireContext().packageName}")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showNotGrantedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission not enabled")
            .setMessage("Please follow the steps and grant permission.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun setListener(listener: OnPermissionCheckListener) {
        this.listener = listener
    }

    private fun isPermissionGranted(): Boolean {
        return when (titleText) {
            "Enable Accessibility" -> isAccessibilityEnabled()
            "Enable Location" -> isLocationEnabled()
            "Disable Battery Optimization" -> isBatteryOptimizationDisabled()
            else -> false
        }
    }

    fun updateStatus(granted: Boolean) {
        if (granted) {
            statusIcon?.setImageResource(android.R.drawable.checkbox_on_background)
            statusText?.text = "Permission granted"
        } else {
            statusIcon?.setImageResource(android.R.drawable.ic_popup_sync)
            statusText?.text = "Permission not granted"
        }
    }

    // Permission checks

    private fun isAccessibilityEnabled(): Boolean {
        val expectedService =
            "${requireContext().packageName}/com.parentalcontrol.childapp.accessibility.BrowserAccessibilityService"

        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(expectedService)
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        } else true
    }

    interface OnPermissionCheckListener {
        fun onCheckPermission()
    }

    // Recycler adapter

    private class StepsAdapter(private val steps: List<String>) :
        RecyclerView.Adapter<StepsAdapter.StepViewHolder>() {

        class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView.findViewById(R.id.stepText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_permission_step, parent, false)
            return StepViewHolder(view)
        }

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            holder.textView.text = steps[position]
        }

        override fun getItemCount(): Int = steps.size
    }
}