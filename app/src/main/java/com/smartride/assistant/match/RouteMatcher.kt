package com.smartride.assistant.match

import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.LatLng
import com.smartride.assistant.data.RideAnalysis
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.routing.GeocodingProvider
import com.smartride.assistant.routing.RoutingProvider
import java.util.Locale
import kotlin.math.max

/** Inputs the matcher needs besides the offer itself. */
data class MatchContext(
    val current: LatLng?,
    val office: LatLng?,
    val maxDetourKm: Double,
    val minOcrConfidence: Float,
    val now: Long,
)

/**
 * Business logic: compares an offer with the driver's remaining commute.
 *
 *   NORMAL_ROUTE = current -> office
 *   RIDE_ROUTE   = current -> pickup -> destination -> office     (one road-routing request)
 *   ADDITIONAL   = RIDE_ROUTE - NORMAL_ROUTE
 *
 * Anything that cannot be established with confidence becomes NEEDS REVIEW instead of a guess.
 */
class RouteMatcher(
    private val geocoder: GeocodingProvider,
    private val routing: RoutingProvider,
) {

    suspend fun analyse(offer: RideOffer, ctx: MatchContext): RideAnalysis {
        fun review(reason: String) = RideAnalysis(
            offer, Classification.NEEDS_REVIEW, null, null, null, reason, ctx.now,
        )

        val missing = buildList {
            if (offer.fare == null) add("fare")
            if (offer.pickup.isNullOrBlank()) add("pickup")
            if (offer.destination.isNullOrBlank()) add("destination")
        }
        if (missing.isNotEmpty()) return review("Could not read: ${missing.joinToString(", ")}. Check the screen or correct it manually.")
        if (offer.ocrConfidence < ctx.minOcrConfidence) {
            return review("Text recognition confidence is low (${(offer.ocrConfidence * 100).toInt()}%). Please verify the addresses.")
        }
        val current = ctx.current ?: return review("Current GPS position is not available yet.")
        val office = ctx.office ?: return review("Office location is not resolved. Open Settings and tap Find on map.")

        val pickupGeo = geocoder.geocode(offer.pickup!!, current)
            ?: return review("Pickup \"${offer.pickup}\" could not be located on the map.")
        val destGeo = geocoder.geocode(offer.destination!!, current)
            ?: return review("Destination \"${offer.destination}\" could not be located on the map.")
        if (pickupGeo.confidence < 0.6f) return review("Pickup \"${offer.pickup}\" matches several different places.")
        if (destGeo.confidence < 0.6f) return review("Destination \"${offer.destination}\" matches several different places.")

        checkPlausibility(offer, current, pickupGeo.point, destGeo.point)?.let { return review(it) }

        val normal = routing.route(listOf(current, office))
            ?: return review("Routing service unavailable (check internet connection).")
        val ride = routing.route(listOf(current, pickupGeo.point, destGeo.point, office))
            ?: return review("Routing service unavailable (check internet connection).")
        if (ride.legMeters.size < 3) return review("Routing service returned an incomplete route.")

        val additionalKm = (ride.totalMeters - normal.totalMeters) / 1000.0
        val destToOfficeKm = ride.legMeters[2] / 1000.0
        val normalKm = normal.totalMeters / 1000.0
        val progressKm = normalKm - destToOfficeKm
        return classify(offer, additionalKm, progressKm, ctx)
    }

    /**
     * Sanity-check the geocoded points against numbers printed on the screen. Straight-line distance can never
     * exceed road distance, so a geocoded pickup much farther than the displayed pickup distance is wrong.
     */
    internal fun checkPlausibility(offer: RideOffer, current: LatLng, pickup: LatLng, dest: LatLng): String? {
        offer.pickupDistanceKm?.let { shown ->
            val straight = current.straightLineKmTo(pickup)
            if (straight > shown * 1.2 + 1.0) {
                return "Pickup location looks wrong: map says ${fmt(straight)} km away but the screen shows ${fmt(shown)} km."
            }
        }
        offer.tripDistanceKm?.let { shown ->
            val straight = pickup.straightLineKmTo(dest)
            if (straight > shown * 1.2 + 1.0) {
                return "Destination location looks wrong: map says ${fmt(straight)} km from pickup but the screen shows ${fmt(shown)} km."
            }
        }
        return null
    }

    companion object {
        /**
         * Classification rules (limit = configured maximum additional distance):
         *  FAVOURABLE   additional <= limit AND drop-off not behind the driver by more than limit/2
         *  MODERATE     additional <= 2*limit (but not favourable)
         *  NOT SUITABLE additional > 2*limit
         */
        fun classify(offer: RideOffer, additionalKm: Double, progressKm: Double, ctx: MatchContext): RideAnalysis {
            val limit = ctx.maxDetourKm
            val extra = max(additionalKm, 0.0)
            val fit = (100.0 * (1.0 - extra / (2.0 * limit))).coerceIn(0.0, 100.0).toInt()
            val compatible = progressKm >= -(limit / 2.0)
            val progressText = if (progressKm >= 0) "${fmt(progressKm)} km closer to the office than you are now"
            else "${fmt(-progressKm)} km farther from the office than you are now"

            val (cls, reason) = when {
                extra <= limit && compatible ->
                    Classification.FAVOURABLE to "Adds ${fmt(extra)} km (limit ${fmt(limit)} km). Drop-off is $progressText."
                extra <= limit ->
                    Classification.MODERATE to "Adds only ${fmt(extra)} km, but the drop-off is $progressText, so it is not along your route."
                extra <= 2 * limit ->
                    Classification.MODERATE to "Adds ${fmt(extra)} km, above your ${fmt(limit)} km limit. Drop-off is $progressText."
                else ->
                    Classification.NOT_SUITABLE to "Adds ${fmt(extra)} km, more than twice your ${fmt(limit)} km limit. Drop-off is $progressText."
            }
            return RideAnalysis(offer, cls, additionalKm, progressKm, fit, reason, ctx.now)
        }

        private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    }
}
