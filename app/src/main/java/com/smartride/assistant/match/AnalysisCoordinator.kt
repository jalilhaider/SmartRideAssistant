package com.smartride.assistant.match

import android.graphics.Bitmap
import android.os.SystemClock
import com.smartride.assistant.alert.AlertPolicy
import com.smartride.assistant.alert.AnnounceGate
import com.smartride.assistant.alert.VoiceAlertManager
import com.smartride.assistant.capture.MonitorBus
import com.smartride.assistant.data.AppSettings
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.HistoryRepository
import com.smartride.assistant.data.LocationTracker
import com.smartride.assistant.data.RideAnalysis
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import com.smartride.assistant.data.SettingsRepository
import com.smartride.assistant.ocr.OcrEngine
import com.smartride.assistant.ocr.RideParser

data class FrameOutcome(
    val relevant: Boolean,
    val analyses: List<RideAnalysis>,
    val ocrMs: Long,
    val newOffers: Int,
)

/**
 * One pipeline for both live capture frames and screenshots picked for testing:
 * OCR -> detect ride screen -> parse -> route-match -> history -> voice alert.
 */
class AnalysisCoordinator(
    private val settingsRepo: SettingsRepository,
    private val historyRepo: HistoryRepository,
    private val ocr: OcrEngine,
    private val location: LocationTracker,
    private val matcher: RouteMatcher,
    private val voice: VoiceAlertManager,
) {
    private val gate = AnnounceGate()
    private data class Cached(val analysis: RideAnalysis, val atMs: Long)
    private val cache = HashMap<String, Cached>()
    private val seenKeys = HashSet<String>()

    fun resetSession() {
        synchronized(cache) { cache.clear(); seenKeys.clear() }
        gate.reset()
    }

    suspend fun processBitmap(bitmap: Bitmap, speak: Boolean = true, liveMode: Boolean = true): FrameOutcome {
        val t0 = SystemClock.elapsedRealtime()
        val lines = ocr.recognise(bitmap)
        val ocrMs = SystemClock.elapsedRealtime() - t0
        if (RideParser.detectFormat(lines) == ScreenFormat.UNKNOWN) {
            return FrameOutcome(false, emptyList(), ocrMs, 0)
        }
        val offers = RideParser.parse(lines)
        val settings = settingsRepo.current()
        val analyses = offers.map { analyseCached(it, settings, speak, liveMode) }
        val newCount = synchronized(cache) { offers.count { seenKeys.add(it.key) } }
        MonitorBus.results.value = analyses
        analyses.firstOrNull { it.classification == Classification.FAVOURABLE }?.let { MonitorBus.lastRide.value = it }
            ?: analyses.firstOrNull()?.let { MonitorBus.lastRide.value = it }
        return FrameOutcome(true, analyses, ocrMs, newCount)
    }

    /** Re-run one offer after the user corrected OCR text by hand. Bypasses the cache. */
    suspend fun analyseCorrected(oldKey: String, corrected: RideOffer): RideAnalysis {
        val settings = settingsRepo.current()
        val a = analyse(corrected.copy(corrected = true, ocrConfidence = 1f), settings)
        historyRepo.record(a)
        synchronized(cache) { cache[a.offer.key] = Cached(a, SystemClock.elapsedRealtime()) }
        MonitorBus.results.value = MonitorBus.results.value.map { if (it.offer.key == oldKey) a else it }
        maybeAnnounce(a, settings)
        return a
    }

    private suspend fun analyseCached(offer: RideOffer, settings: AppSettings, speak: Boolean, liveMode: Boolean): RideAnalysis {
        val now = SystemClock.elapsedRealtime()
        val hit = synchronized(cache) { cache[offer.key] }
        val ttl = if (hit?.analysis?.classification == Classification.NEEDS_REVIEW) REVIEW_TTL_MS else TTL_MS
        val a = if (hit != null && now - hit.atMs < ttl) {
            hit.analysis
        } else {
            analyse(offer, settings, liveMode).also {
                synchronized(cache) { cache[offer.key] = Cached(it, now) }
                historyRepo.record(it)
            }
        }
        if (speak) maybeAnnounce(a, settings)
        return a
    }

    private suspend fun analyse(offer: RideOffer, settings: AppSettings, liveMode: Boolean = true): RideAnalysis {
        val ctx = MatchContext(
            // live driving needs a fresh fix; screenshot tests accept an older one
            current = location.current(if (liveMode) 120_000L else 900_000L),
            office = settings.officePoint,
            maxDetourKm = settings.maxDetourKm,
            minOcrConfidence = settings.minOcrConfidence,
            now = System.currentTimeMillis(),
        )
        return matcher.analyse(offer, ctx)
    }

    private fun maybeAnnounce(a: RideAnalysis, settings: AppSettings) {
        if (!settings.voiceEnabled || !AlertPolicy.shouldAnnounce(a, settings.announceModerate)) return
        if (gate.tryAcquire(a.offer.key, SystemClock.elapsedRealtime(), settings.cooldownSec * 1000L)) {
            voice.speak(a, settings)
        }
    }

    private companion object {
        const val TTL_MS = 45_000L          // reuse a finished analysis for the same offer
        const val REVIEW_TTL_MS = 20_000L   // retry uncertain ones sooner, but not on every frame
    }
}
