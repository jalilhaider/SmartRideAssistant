package com.smartride.assistant.routing

import com.smartride.assistant.data.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object OsrmParser {
    /** Parses an OSRM /route/v1 response. Returns null for any non-"Ok" or malformed response. */
    fun parse(body: String): RouteResult? = try {
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.content != "Ok") {
            null
        } else {
            val route = root["routes"]!!.jsonArray[0].jsonObject
            val legs = route["legs"]!!.jsonArray.map { it.jsonObject["distance"]!!.jsonPrimitive.content.toDouble() }
            RouteResult(
                totalMeters = route["distance"]!!.jsonPrimitive.content.toDouble(),
                totalSeconds = route["duration"]!!.jsonPrimitive.content.toDouble(),
                legMeters = legs,
            )
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Road routing through an OSRM-compatible HTTP API (default: the public OSRM demo server, which is OpenStreetMap
 * based). The demo server is for light, non-commercial use only (https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy);
 * for heavier use point [baseUrl] at your own OSRM instance from Settings. Data (c) OpenStreetMap contributors (ODbL).
 *
 * Coordinates are quantised to 4 decimals (~11 m) so repeated frames hit the cache instead of the network.
 */
class OsrmRoutingProvider(
    private val baseUrlProvider: () -> String,
    private val userAgent: String = "SmartRideAssistant/1.0 (personal-use Android app)",
) : RoutingProvider {

    private val cache = object : LinkedHashMap<String, RouteResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RouteResult>?) = size > 200
    }

    override suspend fun route(waypoints: List<LatLng>): RouteResult? {
        if (waypoints.size < 2) return null
        val coords = waypoints.joinToString(";") {
            String.format(Locale.US, "%.4f,%.4f", it.lon, it.lat)
        }
        val base = baseUrlProvider().trimEnd('/')
        val key = "$base|$coords"
        synchronized(cache) { cache[key]?.let { return it } }
        val body = withContext(Dispatchers.IO) {
            try {
                val url = URL("$base/route/v1/driving/$coords?overview=false&steps=false&alternatives=false")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", userAgent)
                try {
                    if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        } ?: return null
        val result = OsrmParser.parse(body) ?: return null
        synchronized(cache) { cache[key] = result }
        return result
    }
}
