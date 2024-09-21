package info.plateaukao.einkbro.viewmodel

import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.preference.ConfigManager
import info.plateaukao.einkbro.service.OpenAiRepository
import info.plateaukao.einkbro.service.TtsManager
import info.plateaukao.einkbro.tts.ByteArrayMediaDataSource
import info.plateaukao.einkbro.tts.ETts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class TtsViewModel : ViewModel(), KoinComponent {
    private val config: ConfigManager by inject()

    private val ttsManager: TtsManager by inject()

    private val eTts: ETts = ETts()

    private val mediaPlayer by lazy { MediaPlayer() }
    private var byteArrayChannel: Channel<ByteArray>? = null

    private val _speakingState = MutableStateFlow(false)
    val speakingState: StateFlow<Boolean> = _speakingState.asStateFlow()

    private val _readProgress = MutableStateFlow("")
    val readProgress: StateFlow<String> = _readProgress.asStateFlow()

    private val openaiRepository: OpenAiRepository by lazy { OpenAiRepository() }

    private fun useOpenAiTts(): Boolean = config.useOpenAiTts && config.gptApiKey.isNotBlank()

    private val type: TtsType
        get() = if (useOpenAiTts()) TtsType.GPT else config.ttsType

    private val articlesToBeRead: MutableList<String> = mutableListOf()

    fun readArticle(text: String) {
        articlesToBeRead.add(text)
        if (isReading()) {
            return
        }

        viewModelScope.launch {
            while (articlesToBeRead.isNotEmpty()) {
                val article = articlesToBeRead.removeAt(0)

                when (type) {
                    TtsType.ETTS,
                    TtsType.GPT,
                    -> readByEngine(type, article)

                    TtsType.SYSTEM -> {
                        _readProgress.value = if (articlesToBeRead.isNotEmpty()) {
                            "(${articlesToBeRead.size})"
                        } else {
                            ""
                        }

                        readBySystemTts(article)
                    }
                }
            }
        }

//        if (Build.MODEL.startsWith("Pixel 8")) {
//            IntentUnit.tts(context as Activity, text)
//            return
//        }
    }

    private suspend fun readBySystemTts(text: String) {
        _speakingState.value = true
        ttsManager.readText(text)

        while (ttsManager.isSpeaking()) {
            delay(2000)
        }
        _speakingState.value = false
    }

    private suspend fun readByEngine(ttsType: TtsType, text: String) {
        _speakingState.value = true
        byteArrayChannel = Channel(1)

        viewModelScope.launch(Dispatchers.IO) {
            _speakingState.value = true

            val chunks = processedTextToChunks(text)
            chunks.forEachIndexed { index, chunk ->
                if (byteArrayChannel == null) return@launch

                _readProgress.value =
                    "${index + 1}/${chunks.size} " + if (articlesToBeRead.isNotEmpty()) {
                        "(${articlesToBeRead.size})"
                    } else {
                        ""
                    }

                fetchSemaphore.withPermit {
                    Log.d("TtsViewModel", "tts sentence fetch: $chunk")
                    val byteArray = if (ttsType == TtsType.ETTS) {
                        eTts.tts(config.ettsVoice, config.ttsSpeedValue, chunk)
                    } else {
                        openaiRepository.tts(chunk)
                    }

                    if (byteArray != null) {
                        Log.d("TtsViewModel", "tts sentence send: $chunk")
                        byteArrayChannel?.send(byteArray)
                        Log.d("TtsViewModel", "tts sentence sent: $chunk")
                    }
                }
            }
            byteArrayChannel?.close()
        }

        var index = 0
        for (byteArray in byteArrayChannel!!) {
            Log.d("TtsViewModel", "play audio $index")
            playAudioByteArray(byteArray)
            index++
            if (byteArrayChannel?.isClosedForSend == true && byteArrayChannel?.isEmpty == true
            ) break
        }

        _speakingState.value = false
        byteArrayChannel = null
    }

    private fun processedTextToChunks(text: String): MutableList<String> {
        val processedText = text.replace("\\n", " ").replace("\\\"", "").replace("\\t", "")
        val sentences = processedText.split("(?<=\\.)(?!\\d)|(?<=。)|(?<=？)|(?<=\\?)".toRegex())
        val chunks = sentences.fold(mutableListOf<String>()) { acc, sentence ->
            if (acc.isEmpty() || acc.last().length + sentence.length > 100) {
                acc.add(sentence)
            } else {
                val last = acc.last()
                acc[acc.size - 1] = "$last$sentence"
            }
            acc
        }
        return chunks
    }

    private val fetchSemaphore = Semaphore(3)

    fun setSpeechRate(rate: Float) = ttsManager.setSpeechRate(rate)

    fun pauseOrResume() {
        if (type == TtsType.SYSTEM) {
            // TODO
            return
        } else {
            mediaPlayer.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.start()
                }
            }
        }
    }

    fun stop() {
        ttsManager.stopReading()

        byteArrayChannel?.cancel()
        byteArrayChannel?.close()
        byteArrayChannel = null
        mediaPlayer.stop()
        mediaPlayer.reset()

        articlesToBeRead.clear()

        _speakingState.value = false
    }

    fun isReading(): Boolean {
        Log.d("TtsViewModel", "isSpeaking: ${ttsManager.isSpeaking()} ${byteArrayChannel != null}")
        return ttsManager.isSpeaking() || byteArrayChannel != null
    }

    fun isVoicePlaying(): Boolean {
        return mediaPlayer.isPlaying
    }

    fun getAvailableLanguages(): List<Locale> = ttsManager.getAvailableLanguages()

    private suspend fun playAudioByteArray(byteArray: ByteArray) = suspendCoroutine { cont ->
        try {
            mediaPlayer.setDataSource(ByteArrayMediaDataSource(byteArray))
            mediaPlayer.setOnPreparedListener {
                mediaPlayer.start()
            }
            mediaPlayer.prepare()

            mediaPlayer.setOnCompletionListener {
                mediaPlayer.reset()
                cont.resume(0)
            }
        } catch (e: Exception) {
            Log.e("TtsViewModel", "playAudioArray: ${e.message}")
            mediaPlayer.reset()
            cont.resume(0)
        }
    }
}

enum class TtsType {
    SYSTEM, GPT, ETTS
}

fun TtsType.toStringResId(): Int {
    return when (this) {
        TtsType.GPT -> R.string.tts_type_gpt
        TtsType.ETTS -> R.string.tts_type_etts
        TtsType.SYSTEM -> R.string.tts_type_system
    }
}
