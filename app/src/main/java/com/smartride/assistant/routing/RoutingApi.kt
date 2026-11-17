package com.smartride.assistant.routing

import com.smartride.assistant.data.LatLng

data class GeocodeResult(
    val point: LatLng,
    val displayName: String,
    /** 0..1. Low when several far-apart candidates matched the same text. */
    val confidence: Float,
)

/** Resolves free text (as read from the screen) into coordinates. Returns null when nothing credible is found. */
interface GeocodingProvider {
    /** [near] biases and bounds the search to the driver's area so "Mall Road" means the local one. */
    suspend fun geocode(query: String, near: LatLng?): GeocodeResult?
}

data class RouteResult(
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Distance of each leg between consecutive waypoints, in metres. */
    val legMeters: List<Double>,
)

/** Road-network routing. Implementations must return real road distances, never straight-line estimates. */
interface RoutingProvider {
    /** Returns null if the service is unreachable or no road route exists. */
    suspend fun route(waypoints: List<LatLng>): RouteResult?
}
