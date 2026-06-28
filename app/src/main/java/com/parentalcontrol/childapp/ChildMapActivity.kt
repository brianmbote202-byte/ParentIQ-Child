package com.parentalcontrol.childapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class ChildMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_map)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
    }

    fun updateChildLocation(lat: Double, lon: Double) {
        val pos = LatLng(lat, lon)
        if (marker == null) {
            marker = mMap.addMarker(MarkerOptions().position(pos).title("📍 My location"))
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
        } else {
            marker?.position = pos
            mMap.animateCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }
}
