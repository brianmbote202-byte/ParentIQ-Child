package com.parentalcontrol.childapp.model

data class GeofenceZone(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 100f
)