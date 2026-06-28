package com.parentalcontrol.childapp.onboarding.models


data class PermissionStep(
    val id: Int,
    val title: String,
    val description: String,
    val iconResId: Int,
    val actionType: ActionType,
    var stepCompleted: Boolean = false
) {
    enum class ActionType {
        DEVICE_ADMIN,
        VPN,
        ACCESSIBILITY,
        LOCATION,
        BATTERY_OPTIMIZATION,
        NONE
    }
}
