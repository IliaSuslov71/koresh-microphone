package com.koresh.microphone

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*

/** Offline Russian Small Zipformer; initialized once per service. */
class SherpaBridge(assets: AssetManager) {
    private val recognizer: OnlineRecognizer

    init {
        val folder = "sherpa-onnx-small-zipformer-ru-2024-09-18"
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$folder/encoder.int8.onnx",
                decoder = "$folder/decoder.onnx",
                joiner = "$folder/joiner.int8.onnx"
            ),
            tokens = "$folder/tokens.txt",
            numThreads = 4,
            provider = "cpu"
        )
        recognizer = OnlineRecognizer(
            assetManager = assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = model,
                enableEndpoint = false,
                decodingMethod = "greedy_search"
            )
        )
    }

    fun recognize(pcm: ByteArray): String {
        val stream = recognizer.createStream()
        try {
            val samples = FloatArray(pcm.size / 2) { i ->
                val lo = pcm[i * 2].toInt() and 0xff
                val hi = pcm[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768.0f
            }
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 1600, samples.size)
                stream.acceptWaveform(samples.copyOfRange(offset, end), sampleRate = 16000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                offset = end
            }
            stream.acceptWaveform(FloatArray(12800), sampleRate = 16000)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun close() = recognizer.release()
}
