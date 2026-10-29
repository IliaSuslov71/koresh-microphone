package com.koresh.microphone

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*

/** Offline Russian Small Zipformer. Keep a single recognizer per listening service. */
class SherpaBridge(assets: AssetManager) {
    private val recognizer: OfflineRecognizer

    init {
        val folder = "sherpa-onnx-small-zipformer-ru-2024-09-18"
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$folder/encoder.int8.onnx",
                decoder = "$folder/decoder.onnx",
                joiner = "$folder/joiner.int8.onnx"
            ),
            tokens = "$folder/tokens.txt",
            numThreads = 4,
            provider = "cpu",
            modelType = "transducer"
        )
        recognizer = OfflineRecognizer(
            assetManager = assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = model,
                decodingMethod = "greedy_search"
            )
        )
    }

    /** PCM16 little-endian mono at 16kHz. */
    fun recognize(pcm: ByteArray): String {
        val stream = recognizer.createStream()
        try {
            val samples = FloatArray(pcm.size / 2) { i ->
                val low = pcm[i * 2].toInt() and 0xff
                val high = pcm[i * 2 + 1].toInt()
                ((high shl 8) or low).toShort() / 32768.0f
            }
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun close() = recognizer.release()
}
