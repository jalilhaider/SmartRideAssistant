package com.smartride.assistant

import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.LatLng
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import com.smartride.assistant.match.MatchContext
import com.smartride.assistant.match.RouteMatcher
import com.smartride.assistant.routing.GeocodeResult
import com.smartride.assistant.routing.GeocodingProvider
import com.smartride.assistant.routing.RouteResult
import com.smartride.assistant.routing.RoutingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses FAKE providers on an abstract flat test map (coordinates are arbitrary test values, not real places).
 * The fake router sums straight segments, which is enough to test the matcher's arithmetic and rules;
 * real road behaviour must be checked on the device with the real providers.
 */
class RouteMatcherTest {

    private val current = LatLng(0.0, 0.0)
    private val office = LatLng(0.0, 0.2) // ~22 km east of the start

    private class FakeGeo(val places: Map<String, LatLng>, val conf: Float = 0.9f) : GeocodingProvider {
        override suspend fun geocode(query: String, near: LatLng?): GeocodeResult? =
            places[query]?.let { GeocodeResult(it, query, conf) }
    }

    private class FakeRouting(val fail: Boolean = false) : RoutingProvider {
        override suspend fun route(waypoints: List<LatLng>): RouteResult? {
            if (fail) return null
            val legs = waypoints.zipWithNext { a, b -> a.straightLineKmTo(b) * 1000 }
            return RouteResult(legs.sum(), legs.sum() / 10, legs)
        }
    }

    private fun offer(pickup: String? = "P", dest: String? = "D", conf: Float = 0.9f,
                      pickupKm: Double? = null, tripKm: Double? = null) =
        RideOffer(300, pickup, dest, pickupKm, null, tripKm, null, null, conf, ScreenFormat.DETAIL)

    private fun ctx(cur: LatLng? = current, off: LatLng? = office, limit: Double = 2.0, minConf: Float = 0.6f) =
        MatchContext(cur, off, limit, minConf, now = 1L)

    private fun matcher(places: Map<String, LatLng>, fail: Boolean = false, geoConf: Float = 0.9f) =
        RouteMatcher(FakeGeo(places, geoConf), FakeRouting(fail))

    private fun run(m: RouteMatcher, o: RideOffer, c: MatchContext = ctx()) = runBlocking { m.analyse(o, c) }

    @Test fun onTheWayRideIsFavourable() {
        val m = matcher(mapOf("P" to LatLng(0.001, 0.05), "D" to LatLng(0.001, 0.15)))
        val a = run(m, offer())
        assertEquals(a.reason, Classification.FAVOURABLE, a.classification)
        assertTrue(a.additionalKm!! < 2.0)
        assertTrue(a.destinationProgressKm!! > 10.0)
        assertNotNull(a.fitIndex)
    }

    @Test fun oppositeDirectionRideIsNotSuitable() {
        val m = matcher(mapOf("P" to LatLng(0.0, -0.1), "D" to LatLng(0.0, -0.15)))
        val a = run(m, offer())
        assertEquals(Classification.NOT_SUITABLE, a.classification)
        assertTrue(a.additionalKm!! > 4.0)
    }

    @Test fun smallBackwardsStepGivesModerate() {
        // pickup 1.67 km behind the driver, drop-off far along the route -> extra ~3.3 km: above 2, below 4
        val m = matcher(mapOf("P" to LatLng(0.0, -0.015), "D" to LatLng(0.0, 0.1)))
        val a = run(m, offer())
        assertEquals(a.reason, Classification.MODERATE, a.classification)
    }

    @Test fun closePickupButWrongDestinationIsNotFavourable() {
        // Pickup is right here, but the passenger goes 11 km the wrong way and the driver must come back.
        val m = matcher(mapOf("P" to LatLng(0.0, 0.001), "D" to LatLng(0.0, -0.1)))
        val a = run(m, offer())
        assertTrue(a.classification != Classification.FAVOURABLE)
    }

    @Test fun unknownAddressNeedsReviewNotAGuess() {
        val a = run(matcher(emptyMap()), offer())
        assertEquals(Classification.NEEDS_REVIEW, a.classification)
        assertNull(a.additionalKm)
    }

    @Test fun ambiguousGeocodeNeedsReview() {
        val m = matcher(mapOf("P" to LatLng(0.001, 0.05), "D" to LatLng(0.001, 0.15)), geoConf = 0.45f)
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer()).classification)
    }

    @Test fun lowOcrConfidenceNeedsReview() {
        val m = matcher(mapOf("P" to LatLng(0.001, 0.05), "D" to LatLng(0.001, 0.15)))
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer(conf = 0.3f)).classification)
    }

    @Test fun missingFieldsNeedReview() {
        val m = matcher(emptyMap())
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer(dest = null)).classification)
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer(pickup = null)).classification)
    }

    @Test fun missingGpsOrOfficeNeedsReview() {
        val m = matcher(mapOf("P" to LatLng(0.001, 0.05), "D" to LatLng(0.001, 0.15)))
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer(), ctx(cur = null)).classification)
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer(), ctx(off = null)).classification)
    }

    @Test fun routingFailureNeedsReview() {
        val m = matcher(mapOf("P" to LatLng(0.001, 0.05), "D" to LatLng(0.001, 0.15)), fail = true)
        assertEquals(Classification.NEEDS_REVIEW, run(m, offer()).classification)
    }

    @Test fun geocodedPickupFarFromDisplayedDistanceIsRejected() {
        // Screen says the passenger is 1.0 km away but the geocoded pickup is ~5.5 km away -> wrong place.
        val m = matcher(mapOf("P" to LatLng(0.0, 0.05), "D" to LatLng(0.0, 0.15)))
        val a = run(m, offer(pickupKm = 1.0))
        assertEquals(Classification.NEEDS_REVIEW, a.classification)
        assertTrue(a.reason.contains("wrong"))
    }

    @Test fun detourLimitIsConfigurable() {
        val places = mapOf("P" to LatLng(0.0, -0.015), "D" to LatLng(0.0, 0.1)) // extra ~3.3 km
        val m = matcher(places)
        assertEquals(Classification.FAVOURABLE, run(m, offer(), ctx(limit = 4.0)).classification)
        assertEquals(Classification.MODERATE, run(m, offer(), ctx(limit = 2.0)).classification)
        assertEquals(Classification.NOT_SUITABLE, run(m, offer(), ctx(limit = 1.0)).classification)
    }

    @Test fun fitIndexIsBoundedAndNotAProbabilityClaim() {
        val a = RouteMatcher.classify(offer(), additionalKm = 50.0, progressKm = -10.0, ctx = ctx())
        assertEquals(0, a.fitIndex)
        val b = RouteMatcher.classify(offer(), additionalKm = -1.0, progressKm = 5.0, ctx = ctx())
        assertEquals(100, b.fitIndex)
    }
}
