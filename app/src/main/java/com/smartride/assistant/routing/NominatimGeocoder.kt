package com.smartride.assistant.routing

import com.smartride.assistant.data.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Pure parsing helpers, kept separate so they can be unit tested without a network. */
object NominatimParser {
    data class Candidate(val point: LatLng, val displayName: String)

    fun parse(body: String): List<Candidate> {
        val arr = try {
            Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            Candidate(LatLng(lat, lon), o["display_name"]?.jsonPrimitive?.content ?: "")
        }
    }

    /** Several far-apart candidates for the same text means the name is ambiguous. */
    fun toResult(cands: List<Candidate>): GeocodeResult? {
        if (cands.isEmpty()) return null
        val top = cands.first()
        val spread = cands.take(3).maxOf { it.point.straightLineKmTo(top.point) }
        val confidence = if (spread <= 1.5) 0.85f else 0.45f
        return GeocodeResult(top.point, top.displayName, confidence)
    }

    /**
     * "Tindni Rd (Waqas Town)" -> several query variants, most specific first. Business names are often missing from
     * OpenStreetMap, so the road/area inside the brackets is tried as a fallback (it is still bounded to the driver's area).
     */
    fun queryVariants(raw: String): List<String> {
        val t = raw.trim()
        val paren = Regex("""\(([^)]*)\)""").find(t)
        val outside = t.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\s+"""), " ").trim()
        val inside = paren?.groupValues?.get(1)?.trim().orEmpty()
        val skip = Regex("""(?i)^(beside|near|opposite|behind|infront|in front|next to)\b""")
        val insideParts = inside.split(',').map { it.trim() }.filter { it.isNotEmpty() && !skip.containsMatchIn(it) }
        return buildList {
            add(t)
            if (inside.isNotEmpty() && outside.isNotEmpty()) add("$outside, $inside")
            if (insideParts.isNotEmpty()) add(insideParts.joinToString(", "))
            if (inside.isNotEmpty()) add(inside)
            if (insideParts.size > 1) add(insideParts.first())
            if (outside.isNotEmpty() && outside != t) add(outside)
        }.distinct()
    }
}

/**
 * OpenStreetMap Nominatim geocoder.
 * Usage policy: https://operations.osmfoundation.org/policies/nominatim/ - identify the app with a User-Agent,
 * at most 1 request per second, cache results. This class enforces all three. Data (c) OpenStreetMap contributors (ODbL).
 */
class NominatimGeocoder(
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val userAgent: String = "SmartRideAssistant/1.0 (personal-use Android app)",
    private val searchRadiusDeg: Double = 0.4, // ~45 km box around the driver
) : GeocodingProvider {

    private val mutex = Mutex()
    private var lastRequestAt = 0L
    private val cache = object : LinkedHashMap<String, GeocodeResult?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeocodeResult?>?) = size > 300
    }

    override suspend fun geocode(query: String, near: LatLng?): GeocodeResult? {
        val q = query.trim()
        if (q.isEmpty()) return null
        LatLng.parse(q)?.let { return GeocodeResult(it, "Coordinates $q", 1f) }
        val cacheKey = q.lowercase() + "|" + (near?.let { "%.1f,%.1f".format(java.util.Locale.US, it.lat, it.lon) } ?: "-")
        synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }

        var result: GeocodeResult? = null
        for (variant in NominatimParser.queryVariants(q)) {
            val body = fetch(variant, near) ?: continue
            result = NominatimParser.toResult(NominatimParser.parse(body))
            if (result != null) break
        }
        synchronized(cache) { cache[cacheKey] = result }
        return result
    }

    private suspend fun fetch(q: String, near: LatLng?): String? = mutex.withLock {
        val wait = 1_100L - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) {
                val sb = StringBuilder("$baseUrl/search?format=jsonv2&limit=3&countrycodes=pk&q=")
                sb.append(URLEncoder.encode(q, "UTF-8"))
                if (near != null) {
                    val d = searchRadiusDeg
                    // viewbox = left,top,right,bottom (lon,lat,lon,lat); bounded=1 forbids results outside it
                    sb.append("&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}&bounded=1")
                }
                val conn = URL(sb.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Language", "en")
                try {
                    if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }
}
