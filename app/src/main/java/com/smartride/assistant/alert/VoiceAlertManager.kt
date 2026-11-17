package com.smartride.assistant.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.smartride.assistant.data.AlertLanguage
import com.smartride.assistant.data.AppSettings
import com.smartride.assistant.data.RideAnalysis
import java.util.Locale

/**
 * Voice alerts through Android Text-to-Speech.
 * English and Roman Urdu are read with the English voice. Urdu script needs an installed Urdu TTS voice; when none is
 * available the app falls back to Roman Urdu automatically (and says so in [lastNote]).
 */
class VoiceAlertManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayDeque<Pair<RideAnalysis, AppSettings>>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null

    @Volatile var lastNote: String? = null
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Synchronized
    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setAudioAttributes(attrs)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { releaseFocus() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { releaseFocus() }
                    })
                    while (queued.isNotEmpty()) queued.removeFirst().let { speakNow(it.first, it.second) }
                } else {
                    lastNote = "Text-to-speech engine is not available on this phone."
                }
            }
        }
    }

    @Synchronized
    fun speak(analysis: RideAnalysis, settings: AppSettings) {
        ensureEngine()
        if (!ready) { queued.addLast(analysis to settings); return }
        speakNow(analysis, settings)
    }

    private fun speakNow(analysis: RideAnalysis, settings: AppSettings) {
        val engine = tts ?: return
        var lang = settings.language
        if (lang == AlertLanguage.URDU) {
            val avail = engine.isLanguageAvailable(Locale("ur", "PK"))
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Locale("ur", "PK"))
                lastNote = null
            } else {
                lang = AlertLanguage.ROMAN_URDU
                lastNote = "No Urdu voice installed - using Roman Urdu. Install an Urdu voice in the phone's text-to-speech settings."
            }
        }
        if (lang != AlertLanguage.URDU) engine.setLanguage(Locale.ENGLISH)
        val text = AlertMessages.compose(analysis, lang)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.alertVolume.coerceIn(0.1f, 1f)) }
        requestFocus()
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, "ride-${analysis.analysedAt}")
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null; ready = false
    }
}
