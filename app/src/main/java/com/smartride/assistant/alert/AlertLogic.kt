package com.smartride.assistant.alert

import com.smartride.assistant.data.AlertLanguage
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.RideAnalysis
import java.util.Locale

/** Decides whether an offer may be announced now. Prevents repeats across consecutive frames. */
class AnnounceGate(private val minGapBetweenAnyMs: Long = 4_000L) {
    private val lastByKey = HashMap<String, Long>()
    private var lastAny = Long.MIN_VALUE / 2

    @Synchronized
    fun tryAcquire(key: String, now: Long, cooldownMs: Long): Boolean {
        lastByKey.entries.removeAll { now - it.value > cooldownMs * 4 }
        val last = lastByKey[key]
        if (last != null && now - last < cooldownMs) return false
        if (now - lastAny < minGapBetweenAnyMs) return false
        lastByKey[key] = now
        lastAny = now
        return true
    }

    @Synchronized
    fun reset() { lastByKey.clear(); lastAny = Long.MIN_VALUE / 2 }
}

object AlertPolicy {
    fun shouldAnnounce(a: RideAnalysis, announceModerate: Boolean): Boolean = when (a.classification) {
        Classification.FAVOURABLE -> true
        Classification.MODERATE -> announceModerate
        else -> false
    }
}

object AlertMessages {
    fun compose(a: RideAnalysis, lang: AlertLanguage): String {
        val fare = a.offer.fare?.toString() ?: "unknown"
        val pickup = a.offer.pickup ?: "unknown"
        val dest = a.offer.destination ?: "unknown"
        val add = a.additionalKm?.let { String.format(Locale.US, "%.1f", it.coerceAtLeast(0.0)) } ?: "unknown"
        val moderate = a.classification == Classification.MODERATE
        return when (lang) {
            AlertLanguage.ENGLISH ->
                "${if (moderate) "Moderate" else "Favourable"} ride found. Fare $fare rupees. Pickup $pickup. " +
                    "Destination $dest. Additional distance $add kilometres."
            AlertLanguage.ROMAN_URDU ->
                "${if (moderate) "Darmiyani" else "Munasib"} ride mili hai. Kiraya $fare rupay. Pickup $pickup. " +
                    "Manzil $dest. Izafi fasla $add kilometer."
            AlertLanguage.URDU ->
                "${if (moderate) "درمیانی" else "موزوں"} رائیڈ ملی ہے۔ کرایہ $fare روپے۔ پک اپ $pickup۔ " +
                    "منزل $dest۔ اضافی فاصلہ $add کلومیٹر۔"
        }
    }
}
