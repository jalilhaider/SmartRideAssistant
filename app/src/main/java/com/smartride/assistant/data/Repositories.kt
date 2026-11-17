package com.smartride.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.historyStore: DataStore<Preferences> by preferencesDataStore(name = "history")

/** All settings stay on the device (Jetpack DataStore). */
class SettingsRepository(private val context: Context) {
    private object K {
        val homeText = stringPreferencesKey("home_text")
        val homePoint = stringPreferencesKey("home_point")
        val homeName = stringPreferencesKey("home_name")
        val officeText = stringPreferencesKey("office_text")
        val officePoint = stringPreferencesKey("office_point")
        val officeName = stringPreferencesKey("office_name")
        val maxDetour = doublePreferencesKey("max_detour_km")
        val voice = booleanPreferencesKey("voice")
        val language = stringPreferencesKey("language")
        val volume = floatPreferencesKey("volume")
        val cooldown = intPreferencesKey("cooldown_sec")
        val moderate = booleanPreferencesKey("announce_moderate")
        val minConf = floatPreferencesKey("min_conf")
        val interval = longPreferencesKey("interval_ms")
        val routingUrl = stringPreferencesKey("routing_url")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { read(it) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p -> write(p, transform(read(p))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            homeText = p[K.homeText] ?: d.homeText,
            homePoint = p[K.homePoint]?.let { LatLng.parse(it) },
            homeResolvedName = p[K.homeName],
            officeText = p[K.officeText] ?: d.officeText,
            officePoint = p[K.officePoint]?.let { LatLng.parse(it) },
            officeResolvedName = p[K.officeName],
            maxDetourKm = p[K.maxDetour] ?: d.maxDetourKm,
            voiceEnabled = p[K.voice] ?: d.voiceEnabled,
            language = p[K.language]?.let { n -> AlertLanguage.values().firstOrNull { it.name == n } } ?: d.language,
            alertVolume = p[K.volume] ?: d.alertVolume,
            cooldownSec = p[K.cooldown] ?: d.cooldownSec,
            announceModerate = p[K.moderate] ?: d.announceModerate,
            minOcrConfidence = p[K.minConf] ?: d.minOcrConfidence,
            sampleIntervalMs = p[K.interval] ?: d.sampleIntervalMs,
            routingBaseUrl = p[K.routingUrl] ?: d.routingBaseUrl,
        )
    }

    private fun write(p: MutablePreferencesCompat, s: AppSettings) {
        p[K.homeText] = s.homeText
        setOrRemove(p, K.homePoint, s.homePoint?.let { "${it.lat},${it.lon}" })
        setOrRemove(p, K.homeName, s.homeResolvedName)
        p[K.officeText] = s.officeText
        setOrRemove(p, K.officePoint, s.officePoint?.let { "${it.lat},${it.lon}" })
        setOrRemove(p, K.officeName, s.officeResolvedName)
        p[K.maxDetour] = s.maxDetourKm
        p[K.voice] = s.voiceEnabled
        p[K.language] = s.language.name
        p[K.volume] = s.alertVolume
        p[K.cooldown] = s.cooldownSec
        p[K.moderate] = s.announceModerate
        p[K.minConf] = s.minOcrConfidence
        p[K.interval] = s.sampleIntervalMs
        p[K.routingUrl] = s.routingBaseUrl
    }

    private fun setOrRemove(p: MutablePreferencesCompat, key: Preferences.Key<String>, v: String?) {
        if (v == null) p.remove(key) else p[key] = v
    }
}

private typealias MutablePreferencesCompat = androidx.datastore.preferences.core.MutablePreferences

/**
 * Ride history. Stored locally only. Only what the user needs to review a decision is kept:
 * time, fare, pickup, destination, classification, extra distance. No passenger names or ratings.
 */
class HistoryRepository(private val context: Context) {
    private val key = stringPreferencesKey("entries")

    val entries: Flow<List<HistoryEntry>> = context.historyStore.data.map { decode(it[key]) }

    suspend fun record(a: RideAnalysis) {
        context.historyStore.edit { p ->
            val list = decode(p[key]).toMutableList()
            val entry = HistoryEntry(
                a.analysedAt, a.offer.key, a.offer.fare, a.offer.pickup, a.offer.destination,
                a.classification, a.additionalKm,
            )
            // the same offer seen again within 10 minutes updates the existing row instead of adding a new one
            val idx = list.indexOfFirst { it.key == entry.key && entry.time - it.time < 10 * 60_000L }
            if (idx >= 0) list[idx] = entry.copy(time = list[idx].time) else list.add(0, entry)
            p[key] = encode(list.take(MAX_ENTRIES))
        }
    }

    suspend fun clear() {
        context.historyStore.edit { it.remove(key) }
    }

    private fun encode(list: List<HistoryEntry>): String =
        JsonArray(
            list.map { e ->
                buildJsonObject {
                    put("t", e.time)
                    put("k", e.key)
                    if (e.fare != null) put("f", e.fare) else put("f", JsonNull)
                    put("p", e.pickup)
                    put("d", e.destination)
                    put("c", e.classification.name)
                    put("a", e.additionalKm)
                }
            },
        ).toString()

    private fun decode(text: String?): List<HistoryEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            (Json.parseToJsonElement(text) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun prim(n: String): JsonPrimitive? = (o[n] as? JsonPrimitive)?.takeUnless { it is JsonNull }
                HistoryEntry(
                    time = prim("t")?.longOrNull ?: return@mapNotNull null,
                    key = prim("k")?.contentOrNull ?: "",
                    fare = prim("f")?.intOrNull,
                    pickup = prim("p")?.contentOrNull,
                    destination = prim("d")?.contentOrNull,
                    classification = Classification.values().firstOrNull { it.name == prim("c")?.contentOrNull }
                        ?: Classification.NEEDS_REVIEW,
                    additionalKm = prim("a")?.doubleOrNull,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 300
    }
}
