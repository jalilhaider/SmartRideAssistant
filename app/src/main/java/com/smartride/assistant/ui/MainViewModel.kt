package com.smartride.assistant.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartride.assistant.SmartRideApp
import com.smartride.assistant.capture.MonitorBus
import com.smartride.assistant.data.AppSettings
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.HistoryEntry
import com.smartride.assistant.data.RideAnalysis
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SmartRideApp).container

    val settings: StateFlow<AppSettings> =
        c.settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val history: StateFlow<List<HistoryEntry>> =
        c.historyRepo.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val monitor = MonitorBus.state
    val results = MonitorBus.results
    val lastRide = MonitorBus.lastRide

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun postMessage(text: String) { _message.value = text }
    fun consumeMessage() { _message.value = null }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settingsRepo.update(transform) }
    }

    /** Geocode the typed text (or pasted "lat, lon") and store the result for home or office. */
    fun findOnMap(isHome: Boolean, text: String) {
        viewModelScope.launch {
            _busy.value = true
            val r = c.geocoder.geocode(text, null)
            _busy.value = false
            when {
                r == null -> {
                    c.settingsRepo.update { if (isHome) it.copy(homeText = text, homePoint = null, homeResolvedName = null)
                    else it.copy(officeText = text, officePoint = null, officeResolvedName = null) }
                    _message.value = "Not found on the map (or no internet). Try a more specific name, or paste exact coordinates like 30.1234, 71.5678."
                }
                r.confidence < 0.6f -> {
                    _message.value = "That text matches several different places. Paste exact coordinates (lat, lon) instead."
                }
                else -> {
                    c.settingsRepo.update {
                        if (isHome) it.copy(homeText = text, homePoint = r.point, homeResolvedName = r.displayName)
                        else it.copy(officeText = text, officePoint = r.point, officeResolvedName = r.displayName)
                    }
                    _message.value = "Saved: ${r.displayName.ifBlank { r.point.asText() }}. Check that this is the right place."
                }
            }
        }
    }

    fun useCurrentGps(isHome: Boolean) {
        c.location.start()
        val p = c.location.current(maxAgeMs = 600_000L)
        if (p == null) {
            _message.value = "No GPS fix yet. Grant location permission, go outdoors and try again."
            return
        }
        viewModelScope.launch {
            c.settingsRepo.update {
                if (isHome) it.copy(homePoint = p, homeResolvedName = "GPS position ${p.asText()}")
                else it.copy(officePoint = p, officeResolvedName = "GPS position ${p.asText()}")
            }
            _message.value = "Saved current GPS position (${p.asText()})."
        }
    }

    /** Optional test mode: analyse a screenshot picked with the Android Photo Picker. Nothing is uploaded. */
    fun analyseScreenshot(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            _busy.value = true
            try {
                val bmp: Bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getApplication<Application>().contentResolver, uri),
                ) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                val out = c.coordinator.processBitmap(bmp, speak = true)
                bmp.recycle()
                _message.value = if (!out.relevant) "No ride-offer screen recognised in that image (OCR took ${out.ocrMs} ms)."
                else "Found ${out.analyses.size} offer(s) in ${out.ocrMs} ms OCR. See the Results tab."
            } catch (e: Exception) {
                _message.value = "Could not read that image: ${e.javaClass.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Manual correction for low-confidence OCR. */
    fun applyCorrection(original: RideAnalysis, fare: Int?, pickup: String, destination: String) {
        viewModelScope.launch {
            _busy.value = true
            val o = original.offer
            val corrected: RideOffer = o.copy(fare = fare, pickup = pickup.trim().ifEmpty { null },
                destination = destination.trim().ifEmpty { null })
            c.coordinator.analyseCorrected(original.offer.key, corrected)
            _busy.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch { c.historyRepo.clear(); _message.value = "History cleared." }
    }

    fun testVoice() {
        val sample = RideAnalysis(
            RideOffer(100, "Test pickup", "Test destination", null, null, null, null, null, 1f, ScreenFormat.DETAIL),
            Classification.FAVOURABLE, 1.0, 1.0, 100, "Voice test only - not a real ride.", System.currentTimeMillis(),
        )
        c.voice.speak(sample, settings.value)
        c.voice.lastNote?.let { _message.value = it }
    }

    fun voiceNote(): String? = c.voice.lastNote
    fun hasLocationPermission(): Boolean = c.location.hasPermission()
}
