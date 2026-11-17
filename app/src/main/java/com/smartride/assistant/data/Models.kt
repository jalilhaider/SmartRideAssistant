package com.smartride.assistant.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double) {
    fun asText(): String = "%.5f, %.5f".format(java.util.Locale.US, lat, lon)

    /** Great-circle distance in km. Only used for plausibility checks, never as a driving distance. */
    fun straightLineKmTo(o: LatLng): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(o.lat - lat)
        val dLon = Math.toRadians(o.lon - lon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private val COORD = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

        /** Accepts "30.19,71.47" style input so the user can paste exact coordinates. */
        fun parse(text: String): LatLng? {
            val m = COORD.matchEntire(text) ?: return null
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null
        }
    }
}

enum class Classification(val label: String) {
    FAVOURABLE("FAVOURABLE"),
    MODERATE("MODERATE"),
    NOT_SUITABLE("NOT SUITABLE"),
    NEEDS_REVIEW("NEEDS REVIEW"),
}

enum class ScreenFormat { LIST, DETAIL, UNKNOWN }

enum class AlertLanguage(val label: String) {
    ENGLISH("English"),
    ROMAN_URDU("Roman Urdu"),
    URDU("اردو (Urdu script)"),
}

/** One OCR text line with its pixel bounding box and ML Kit confidence (0..1). */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 1f,
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/** A ride offer exactly as read from the screen. Null means "not visible / not read" - never guessed. */
data class RideOffer(
    val fare: Int?,
    val pickup: String?,
    val destination: String?,
    val pickupDistanceKm: Double?,
    val pickupEtaMin: Int?,
    val tripDistanceKm: Double?,
    val tripDurationMin: Int?,
    val rating: Double?,
    val ocrConfidence: Float,
    val format: ScreenFormat,
    val corrected: Boolean = false,
) {
    /** Stable identity used for duplicate detection across frames. */
    val key: String
        get() = "${fare ?: "?"}|${normalise(pickup)}|${normalise(destination)}"

    companion object {
        fun normalise(s: String?): String =
            (s ?: "").lowercase().filter { it.isLetterOrDigit() }
    }
}

data class RideAnalysis(
    val offer: RideOffer,
    val classification: Classification,
    val additionalKm: Double?,
    /** How much closer to the office the drop-off is than the driver's current position (km, may be negative). */
    val destinationProgressKm: Double?,
    /** Heuristic 0..100 index. NOT a probability. Null when it cannot be computed. */
    val fitIndex: Int?,
    val reason: String,
    val analysedAt: Long,
)

data class HistoryEntry(
    val time: Long,
    val key: String,
    val fare: Int?,
    val pickup: String?,
    val destination: String?,
    val classification: Classification,
    val additionalKm: Double?,
)

data class AppSettings(
    val homeText: String = "VIP Colony, Suraj Miani, Multan, Pakistan",
    val homePoint: LatLng? = null,
    val homeResolvedName: String? = null,
    val officeText: String = "Government Higher Secondary School 138/10-R, Jahanian, Khanewal, Pakistan",
    val officePoint: LatLng? = null,
    val officeResolvedName: String? = null,
    val maxDetourKm: Double = 2.0,
    val voiceEnabled: Boolean = true,
    val language: AlertLanguage = AlertLanguage.ENGLISH,
    val alertVolume: Float = 1.0f,
    val cooldownSec: Int = 120,
    val announceModerate: Boolean = false,
    val minOcrConfidence: Float = 0.6f,
    val sampleIntervalMs: Long = 1000L,
    val routingBaseUrl: String = "https://router.project-osrm.org",
)
