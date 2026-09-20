package com.parentalcontrol.childapp.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

object ParentIQDevicePolicyManager {

    private const val TAG = "ParentIQDevicePolicy"

    private fun getDpm(context: Context): DevicePolicyManager {
        return context.getSystemService(
            Context.DEVICE_POLICY_SERVICE
        ) as DevicePolicyManager
    }

    private fun getAdmin(context: Context): ComponentName {
        return ComponentName(
            context,
            ChildAdminReceiver::class.java
        )
    }

    /**
     * Returns true when ParentIQ is provisioned as Device Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            getDpm(context).isDeviceOwnerApp(
                context.packageName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Device Owner status", e)
            false
        }
    }

    /**
     * Returns true when the ChildAdminReceiver is active.
     */
    fun isAdminActive(context: Context): Boolean {
        return try {
            getDpm(context).isAdminActive(
                getAdmin(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check admin status", e)
            false
        }
    }

    /**
     * Returns true when the receiver is ready for
     * DevicePolicyManager operations.
     */
    fun isPolicyManagerReady(context: Context): Boolean {
        return isDeviceOwner(context) &&
                isAdminActive(context)
    }

    /**
     * Prevent the Child app from being uninstalled.
     */
    fun blockUninstall(context: Context): Boolean {

        val dpm = getDpm(context)

        if (!isDeviceOwner(context)) {
            Log.w(TAG, "ParentIQ is not Device Owner")
            return false
        }

        return try {

            dpm.setUninstallBlocked(
                getAdmin(context),
                context.packageName,
                true
            )

            Log.i(TAG, "ParentIQ uninstall blocked")
            true

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Unable to block uninstall",
                e
            )

            false
        }
    }

    /**
     * Release the uninstall protection.
     */
    fun allowUninstall(context: Context): Boolean {

        val dpm = getDpm(context)

        if (!isDeviceOwner(context)) {
            Log.w(TAG, "ParentIQ is not Device Owner")
            return false
        }

        return try {

            dpm.setUninstallBlocked(
                getAdmin(context),
                context.packageName,
                false
            )

            Log.i(
                TAG,
                "ParentIQ uninstall protection released"
            )

            true

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Unable to release uninstall block",
                e
            )

            false
        }
    }

    /**
     * Lock the child's device immediately.
     */
    fun lockDevice(context: Context): Boolean {

        if (!isPolicyManagerReady(context)) {
            Log.w(
                TAG,
                "Cannot lock device: Device Owner/admin not ready"
            )
            return false
        }

        return try {

            getDpm(context).lockNow()

            Log.i(TAG, "Device locked")
            true

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Unable to lock device",
                e
            )

            false
        }
    }
}