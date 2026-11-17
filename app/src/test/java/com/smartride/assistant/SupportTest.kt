package com.smartride.assistant

import com.smartride.assistant.alert.AlertMessages
import com.smartride.assistant.alert.AlertPolicy
import com.smartride.assistant.alert.AnnounceGate
import com.smartride.assistant.capture.FrameDiff
import com.smartride.assistant.data.AlertLanguage
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.LatLng
import com.smartride.assistant.data.RideAnalysis
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import com.smartride.assistant.routing.NominatimParser
import com.smartride.assistant.routing.OsrmParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {

    private fun analysis(cls: Classification = Classification.FAVOURABLE, extra: Double? = 1.24) = RideAnalysis(
        RideOffer(434, "Mall Road", "Faiz Town", null, null, null, null, null, 0.9f, ScreenFormat.LIST),
        cls, extra, 5.0, 80, "reason", 0L,
    )

    // ---- announce gate ----
    @Test fun sameOfferIsNotAnnouncedTwiceWithinCooldown() {
        val g = AnnounceGate(minGapBetweenAnyMs = 0)
        assertTrue(g.tryAcquire("a", 0, 120_000))
        assertFalse(g.tryAcquire("a", 5_000, 120_000))
        assertFalse(g.tryAcquire("a", 119_000, 120_000))
        assertTrue(g.tryAcquire("a", 121_000, 120_000))
    }

    @Test fun differentOffersRespectGlobalGap() {
        val g = AnnounceGate(minGapBetweenAnyMs = 4_000)
        assertTrue(g.tryAcquire("a", 0, 120_000))
        assertFalse(g.tryAcquire("b", 1_000, 120_000))
        assertTrue(g.tryAcquire("b", 4_500, 120_000))
    }

    // ---- policy / messages ----
    @Test fun onlyFavourableIsAnnouncedByDefault() {
        assertTrue(AlertPolicy.shouldAnnounce(analysis(Classification.FAVOURABLE), false))
        assertFalse(AlertPolicy.shouldAnnounce(analysis(Classification.MODERATE), false))
        assertTrue(AlertPolicy.shouldAnnounce(analysis(Classification.MODERATE), true))
        assertFalse(AlertPolicy.shouldAnnounce(analysis(Classification.NOT_SUITABLE), true))
        assertFalse(AlertPolicy.shouldAnnounce(analysis(Classification.NEEDS_REVIEW), true))
    }

    @Test fun englishMessageMatchesSpecWording() {
        assertEquals(
            "Favourable ride found. Fare 434 rupees. Pickup Mall Road. Destination Faiz Town. Additional distance 1.2 kilometres.",
            AlertMessages.compose(analysis(), AlertLanguage.ENGLISH),
        )
    }

    @Test fun urduAndRomanUrduMessagesContainTheFacts() {
        val u = AlertMessages.compose(analysis(), AlertLanguage.URDU)
        assertTrue(u.startsWith("موزوں رائیڈ ملی ہے"))
        assertTrue(u.contains("434") && u.contains("Mall Road") && u.contains("1.2"))
        val r = AlertMessages.compose(analysis(), AlertLanguage.ROMAN_URDU)
        assertTrue(r.contains("434") && r.contains("Mall Road") && r.contains("1.2"))
    }

    // ---- frame diff ----
    @Test fun identicalFramesAreSkippedAndChangesDetected() {
        val d = FrameDiff(threshold = 2.0)
        val a = IntArray(100) { 0xFF202020.toInt() }
        assertTrue(d.hasChanged(a))            // first frame
        assertFalse(d.hasChanged(a.clone()))   // identical
        val noisy = IntArray(100) { 0xFF212121.toInt() }
        assertFalse(d.hasChanged(noisy))       // below threshold
        val different = IntArray(100) { 0xFFF0F0F0.toInt() }
        assertTrue(d.hasChanged(different))
    }

    // ---- JSON parsers ----
    @Test fun osrmResponseIsParsed() {
        val body = """{"code":"Ok","routes":[{"distance":12345.6,"duration":900.2,
            "legs":[{"distance":1000.0},{"distance":2000.5},{"distance":9345.1}]}],"waypoints":[]}"""
        val r = OsrmParser.parse(body)!!
        assertEquals(12345.6, r.totalMeters, 1e-9)
        assertEquals(listOf(1000.0, 2000.5, 9345.1), r.legMeters)
    }

    @Test fun osrmErrorsGiveNull() {
        assertNull(OsrmParser.parse("""{"code":"NoRoute","message":"x"}"""))
        assertNull(OsrmParser.parse("not json"))
    }

    @Test fun nominatimResponseIsParsedAndAmbiguityLowersConfidence() {
        val close = """[{"lat":"30.1000","lon":"71.4000","display_name":"A"},{"lat":"30.1010","lon":"71.4010","display_name":"B"}]"""
        val far = """[{"lat":"30.1000","lon":"71.4000","display_name":"A"},{"lat":"30.3000","lon":"71.6000","display_name":"B"}]"""
        assertTrue(NominatimParser.toResult(NominatimParser.parse(close))!!.confidence >= 0.6f)
        assertTrue(NominatimParser.toResult(NominatimParser.parse(far))!!.confidence < 0.6f)
        assertNull(NominatimParser.toResult(NominatimParser.parse("[]")))
        assertTrue(NominatimParser.parse("garbage").isEmpty())
    }

    @Test fun queryVariantsHandleParentheses() {
        val v = NominatimParser.queryVariants("Tindni Rd (Waqas Town)")
        assertEquals("Tindni Rd (Waqas Town)", v[0])
        assertTrue(v.contains("Tindni Rd, Waqas Town"))
        assertTrue(v.contains("Waqas Town"))
    }

    @Test fun coordinatePasteIsParsed() {
        assertNotNull(LatLng.parse("30.1234, 71.5678"))
        assertNull(LatLng.parse("Mall Road"))
        assertNull(LatLng.parse("95.0, 10.0"))
    }
}
