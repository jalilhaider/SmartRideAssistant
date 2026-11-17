package com.smartride.assistant.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Keeps the driver's latest position using the platform LocationManager (no Google Play Services needed).
 * Position is used only on the device to compute detours; it is sent to nobody except as route waypoints
 * to the configured routing server.
 */
class LocationTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var latest: Location? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { latest = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (started || !hasPermission()) return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 3_000L, 5f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (isNewer(it)) latest = it }
                }
            }
            started = true
        } catch (e: SecurityException) {
            started = false
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        try { lm.removeUpdates(listener) } catch (e: SecurityException) { /* permission revoked while running */ }
        started = false
    }

    /** Latest fix no older than [maxAgeMs], otherwise null (the app then reports NEEDS REVIEW, not a guess). */
    fun current(maxAgeMs: Long = 120_000L): LatLng? {
        val l = latest ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        return if (ageMs <= maxAgeMs) LatLng(l.latitude, l.longitude) else null
    }

    private fun isNewer(l: Location): Boolean = latest?.let { l.elapsedRealtimeNanos > it.elapsedRealtimeNanos } ?: true
}
