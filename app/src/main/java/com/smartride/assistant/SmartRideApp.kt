package com.smartride.assistant

import android.app.Application
import android.content.Context
import com.smartride.assistant.alert.VoiceAlertManager
import com.smartride.assistant.data.HistoryRepository
import com.smartride.assistant.data.LocationTracker
import com.smartride.assistant.data.SettingsRepository
import com.smartride.assistant.match.AnalysisCoordinator
import com.smartride.assistant.match.RouteMatcher
import com.smartride.assistant.ocr.OcrEngine
import com.smartride.assistant.routing.NominatimGeocoder
import com.smartride.assistant.routing.OsrmRoutingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tiny hand-rolled service locator (no DI framework needed for this size). */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepo = SettingsRepository(context)
    val historyRepo = HistoryRepository(context)
    val location = LocationTracker(context)
    val voice = VoiceAlertManager(context)
    val ocr = OcrEngine()
    val geocoder = NominatimGeocoder()

    @Volatile private var routingBaseUrl = "https://router.project-osrm.org"
    val routing = OsrmRoutingProvider({ routingBaseUrl })
    val matcher = RouteMatcher(geocoder, routing)
    val coordinator = AnalysisCoordinator(settingsRepo, historyRepo, ocr, location, matcher, voice)

    init {
        appScope.launch { settingsRepo.settings.collect { routingBaseUrl = it.routingBaseUrl } }
    }
}

class SmartRideApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
