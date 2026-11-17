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

    /**
     * Format A laid out like the real inDrive list screen (positions taken from a 308 px wide screenshot):
     * big fare, pickup + distance, destination + distance, addresses wrapping onto a 2nd truncated line,
     * then a passenger row "Name * rating (count)" with "N min." on the right.
     */
    private fun listScreen(): List<OcrLine> {
        fun l(t: String, x: Int, cy: Int, w: Int = 180, h: Int = 18, c: Float = 0.95f) = OcrLine(t, x, cy - h / 2, x + w, cy + h / 2, c)
        return listOf(
            l("Offline", 120, 60), l("Ride requests", 40, 662), l("Performance", 200, 662),
            // card 1
            l("Rs434", 18, 122, 80, 26),
            l("Stylo shoe (Mall Road,", 44, 155), l("4.6 km", 241, 155, 50),
            l("Multan Cantt Commercial ...", 44, 173),
            l("Faiz Town (Multan)", 44, 195), l("8.5 km", 241, 195, 50),
            l("Imran * 4.62 (103)", 44, 222, 140), l("1 min.", 252, 222, 40),
            // card 2
            l("Rs243", 18, 277, 80, 26),
            l("Shaheen St (Mohalla Kotla", 44, 320), l("5.3 km", 241, 320, 50),
            l("Tollay Khan)", 44, 338),
            l("Hotel Luxury Express (Tariq", 44, 352), l("2.7 km", 241, 352, 50),
            l("Road, beside Hotel One, ...", 44, 370),
            l("Nisar * 4.91 (43)", 44, 397, 140), l("2 min.", 252, 397, 40),
            // card 3 (with a payment tag above the fare)
            l("JazzCash", 14, 443, 60),
            l("Rs573", 18, 476, 80, 26),
            l("Akbar Rd (Basti Sadu", 44, 512), l("6.1 km", 241, 512, 50),
            l("Hassan)", 44, 530),
            l("Bahawalpur Road", 44, 552), l("16.6 km", 235, 552, 55),
            l("(Raitarwala Khandarwala, ...", 44, 570),
            l("Naina * 3.89 (126)", 44, 596, 140), l("2 min.", 252, 596, 40),
        )
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
        assertEquals("Stylo shoe (Mall Road, Multan Cantt Commercial)", offers[0].pickup)
        assertEquals("Faiz Town (Multan)", offers[0].destination)
        assertEquals("Shaheen St (Mohalla Kotla Tollay Khan)", offers[1].pickup)
        assertEquals("Hotel Luxury Express (Tariq Road, beside Hotel One)", offers[1].destination)
        assertEquals("Akbar Rd (Basti Sadu Hassan)", offers[2].pickup)
        assertEquals("Bahawalpur Road (Raitarwala Khandarwala)", offers[2].destination)
    }

    @Test fun passengerRowIsNeverTakenAsAddress() {
        for (o in RideParser.parse(listScreen())) {
            for (text in listOfNotNull(o.pickup, o.destination)) {
                assertTrue(text, !text.contains("Imran") && !text.contains("Nisar") && !text.contains("Naina"))
                assertTrue(text, !text.contains("Ride requests") && !text.contains("Performance") && !text.contains("JazzCash"))
            }
        }
    }

    @Test fun listCardDistancesRatingAndEtaAreAssignedCorrectly() {
        val offers = RideParser.parse(listScreen())
        assertEquals(4.6, offers[0].pickupDistanceKm!!, 1e-9)
        assertEquals(8.5, offers[0].tripDistanceKm!!, 1e-9)
        assertEquals(1, offers[0].pickupEtaMin)
        assertNull(offers[0].tripDurationMin)
        assertEquals(4.62, offers[0].rating!!, 1e-9)
        assertEquals(5.3, offers[1].pickupDistanceKm!!, 1e-9)
        assertEquals(2.7, offers[1].tripDistanceKm!!, 1e-9)
        assertEquals(6.1, offers[2].pickupDistanceKm!!, 1e-9)
        assertEquals(16.6, offers[2].tripDistanceKm!!, 1e-9)
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
