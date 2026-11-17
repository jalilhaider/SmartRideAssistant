package com.smartride.assistant.capture

import com.smartride.assistant.data.RideAnalysis
import kotlinx.coroutines.flow.MutableStateFlow

data class MonitorUiState(
    val running: Boolean = false,
    val captureStatus: String = "Stopped",
    val ocrStatus: String = "Idle",
    val detectedRides: Int = 0,
    val framesAnalysed: Int = 0,
    val framesSkipped: Int = 0,
    val lastAnalysisAt: Long? = null,
    val lastOcrMs: Long? = null,
    val note: String? = null,
)

/** In-process state shared by the capture service (writer) and the UI (reader). Holds no screen content. */
object MonitorBus {
    val state = MutableStateFlow(MonitorUiState())
    /** Analyses from the most recent ride-offer screen. */
    val results = MutableStateFlow<List<RideAnalysis>>(emptyList())
    val lastRide = MutableStateFlow<RideAnalysis?>(null)

    inline fun update(block: (MonitorUiState) -> MonitorUiState) {
        state.value = block(state.value)
    }
}
