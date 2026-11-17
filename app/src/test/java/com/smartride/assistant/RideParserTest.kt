package com.smartride.assistant

import com.smartride.assistant.data.OcrLine
import com.smartride.assistant.data.ScreenFormat
import com.smartride.assistant.ocr.RideParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures below are SYNTHETIC OCR output built from the example values in the project brief
 * (fares, place names). Pixel positions are invented. They test the parser logic only; they are not real
 * inDrive offers. Real-screenshot validation must be done on the device (see README).
 */
class RideParserTest {

    private fun line(text: String, left: Int, top: Int, conf: Float = 0.95f, w: Int = 300) =
        OcrLine(text, left, top, left + w, top + 40, conf)

    /** Format A: three stacked cards, fare at the top-right of each card. */
    private fun listScreen(): List<OcrLine> = buildList {
        // card 1
        add(line("Rs.434", 800, 300, w = 200)); add(line("4.9", 60, 300, w = 60))
        add(line("Mall Road", 60, 360)); add(line("1.2 km", 800, 360, w = 160))
        add(line("Faiz Town", 60, 410)); add(line("3.1 km", 800, 410, w = 160))
        // card 2
        add(line("Rs.243", 800, 620, w = 200)); add(line("4.7", 60, 620, w = 60))
        add(line("Stylo Shoe", 60, 680)); add(line("800 m", 800, 680, w = 160))
        add(line("Bahawalpur Road", 60, 730)); add(line("2.8 km", 800, 730, w = 160))
        // card 3
        add(line("Rs.573", 800, 940, w = 200)); add(line("5.0", 60, 940, w = 60))
        add(line("Akbar Rd", 60, 1000)); add(line("2.0 km", 800, 1000, w = 160))
        add(line("Faiz Town", 60, 1050)); add(line("6.4 km", 800, 1050, w = 160))
    }

    /** Format B: a single detail screen with an "Accept" button repeating the fare. */
    private fun detailScreen(): List<OcrLine> = listOf(
        line("Rs.243", 400, 300, w = 250),
        line("Tindni Rd (Waqas Town)", 60, 420, w = 600), line("1.4 km", 800, 420, w = 150),
        line("6 min", 800, 470, w = 120),
        line("IS Drink Corner (Gillani Colony, Multan)", 60, 560, w = 700), line("2.8 km", 800, 560, w = 150),
        line("9 min", 800, 610, w = 120),
        line("Accept Rs.243", 100, 1500, w = 800),
        line("Offer your fare", 100, 1400, w = 600),
    )

    @Test fun listScreenIsDetectedAsList() {
        assertEquals(ScreenFormat.LIST, RideParser.detectFormat(listScreen()))
    }

    @Test fun listScreenYieldsThreeIndependentOffers() {
        val offers = RideParser.parse(listScreen())
        assertEquals(3, offers.size)
        assertEquals(listOf(434, 243, 573), offers.map { it.fare })
        assertEquals("Mall Road", offers[0].pickup); assertEquals("Faiz Town", offers[0].destination)
        assertEquals("Stylo Shoe", offers[1].pickup); assertEquals("Bahawalpur Road", offers[1].destination)
        assertEquals("Akbar Rd", offers[2].pickup); assertEquals("Faiz Town", offers[2].destination)
    }

    @Test fun fareStaysAssociatedWithItsOwnAddresses() {
        val o = RideParser.parse(listScreen()).first { it.fare == 243 }
        assertEquals("Stylo Shoe", o.pickup)
        assertEquals(0.8, o.pickupDistanceKm!!, 1e-9)   // 800 m -> km
        assertEquals(2.8, o.tripDistanceKm!!, 1e-9)
        assertEquals(4.7, o.rating!!, 1e-9)
    }

    @Test fun detailScreenYieldsOneOfferAndIgnoresAcceptButton() {
        assertEquals(ScreenFormat.DETAIL, RideParser.detectFormat(detailScreen()))
        val offers = RideParser.parse(detailScreen())
        assertEquals(1, offers.size)
        val o = offers[0]
        assertEquals(243, o.fare)
        assertEquals("Tindni Rd (Waqas Town)", o.pickup)
        assertEquals("IS Drink Corner (Gillani Colony, Multan)", o.destination)
        assertEquals(1.4, o.pickupDistanceKm!!, 1e-9)
        assertEquals(6, o.pickupEtaMin)
        assertEquals(2.8, o.tripDistanceKm!!, 1e-9)
        assertEquals(9, o.tripDurationMin)
    }

    @Test fun unrelatedScreenIsIgnored() {
        val lines = listOf(line("Settings", 60, 100), line("Battery 85%", 60, 200), line("Wi-Fi", 60, 300))
        assertEquals(ScreenFormat.UNKNOWN, RideParser.detectFormat(lines))
        assertTrue(RideParser.parse(lines).isEmpty())
    }

    @Test fun fareWithoutDistanceIsNotARideScreen() {
        val lines = listOf(line("Rs.500", 60, 100), line("Total balance", 60, 200))
        assertEquals(ScreenFormat.UNKNOWN, RideParser.detectFormat(lines))
    }

    @Test fun missingDestinationIsNotInventedAndLowersConfidence() {
        val lines = listOf(line("Rs.300", 800, 300, w = 200), line("Mall Road", 60, 360), line("1.2 km", 800, 360, w = 160))
        val o = RideParser.parse(lines).single()
        assertEquals("Mall Road", o.pickup)
        assertNull(o.destination)
        assertTrue(o.ocrConfidence < 0.6f)
    }

    @Test fun confidenceReflectsOcrLineConfidence() {
        val lines = listOf(
            line("Rs.300", 800, 300, conf = 0.3f, w = 200),
            line("Mall Road", 60, 360, conf = 0.3f), line("1.2 km", 800, 360, w = 160),
            line("Faiz Town", 60, 410, conf = 0.3f), line("3.0 km", 800, 410, w = 160),
        )
        assertEquals(0.3f, RideParser.parse(lines).single().ocrConfidence, 1e-4f)
    }

    @Test fun passengerNameLineDoesNotBecomePickup() {
        val lines = listOf(
            line("Rs.250", 800, 300, w = 200), line("Bilal Ahmed", 60, 300),
            line("Mall Road", 60, 360), line("1.0 km", 800, 360, w = 160),
            line("Faiz Town", 60, 410), line("3.0 km", 800, 410, w = 160),
        )
        val o = RideParser.parse(lines).single()
        assertEquals("Mall Road", o.pickup)
        assertEquals("Faiz Town", o.destination)
    }

    @Test fun fareFormatsAreRecognised() {
        for (t in listOf("Rs.434", "Rs 434", "RS. 434", "PKR 434", "Rs434")) {
            val o = RideParser.parse(listOf(line(t, 800, 300, w = 200), line("A Road", 60, 360), line("1 km", 800, 360, w = 100),
                line("B Road", 60, 410))).singleOrNull()
            assertNotNull(t, o)
            assertEquals(t, 434, o!!.fare)
        }
    }

    @Test fun duplicateOffersShareAKeyAcrossFrames() {
        val a = RideParser.parse(listScreen())
        val b = RideParser.parse(listScreen().map { it.copy(top = it.top + 3, bottom = it.bottom + 3) }) // slight scroll jitter
        assertEquals(a.map { it.key }, b.map { it.key })
    }
}
