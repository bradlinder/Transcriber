package org.scrib.transcriber

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val multilingual: Boolean,
    val tier: Int,
    val recommended: Boolean = false,
    val custom: Boolean = false
)

object ModelCatalog {

    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    // The ggml weights every family but whisper is published in, one file per model and quant.
    private const val GGUF = "https://huggingface.co/handy-computer/"

    // Silero VAD, which finds the speech in a recording. It transcribes nothing on its own and is
    // never the active model — it is a small companion to whichever whisper model is chosen.
    const val VAD_FILE = "ggml-silero-v5.1.2.bin"
    const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"
    const val SHERPA_VAD_FILE = "silero_vad.onnx"
    const val SHERPA_VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SHERPA_VAD_FILE"

    private fun standard(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        tier: Int,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, BASE + fileName, approxBytes, multilingual, tier, recommended)

    private fun gguf(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        tier: Int,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, GGUF + id + "/resolve/main/" + fileName, approxBytes, multilingual, tier, recommended)

    val MODELS: List<WhisperModel> = listOf(
        standard("tiny-q5_1", "Whisper Tiny (Multilingual)", "ggml-tiny-q5_1.bin", 32_200_000, true, 1),
        standard("base-q5_1", "Whisper Base (Multilingual)", "ggml-base-q5_1.bin", 57_800_000, true, 2, recommended = true),
        standard("small-q5_1", "Whisper Small (Multilingual)", "ggml-small-q5_1.bin", 190_000_000, true, 3),
        standard("tiny.en-q5_1", "Whisper Tiny (English)", "ggml-tiny.en-q5_1.bin", 32_200_000, false, 1),
        standard("base.en-q5_1", "Whisper Base (English)", "ggml-base.en-q5_1.bin", 57_800_000, false, 2),
        standard("small.en-q5_1", "Whisper Small (English)", "ggml-small.en-q5_1.bin", 190_000_000, false, 3),
        // The families below are not whisper at all: one ggml library reads them all, one file is
        // the whole model, and the quant is part of the file name. Ordered by what a phone can
        // still run at a sensible pace, cheapest first.
        gguf("parakeet-tdt_ctc-110m-gguf", "Parakeet English", "parakeet-tdt_ctc-110m-Q4_K_M.gguf", 90_000_000, false, 2, recommended = true),
        gguf("canary-180m-flash-gguf", "Canary Flash (Multilingual)", "canary-180m-flash-Q4_K_M.gguf", 139_200_000, true, 3),
        gguf("parakeet-tdt-0.6b-v3-gguf", "Parakeet Multilingual", "parakeet-tdt-0.6b-v3-Q4_K_M.gguf", 485_400_000, true, 4)
    )

    val PARAKEET_LANGUAGES: List<String> = listOf(
        "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it",
        "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk"
    )

    fun byFileName(fileName: String): WhisperModel? = MODELS.firstOrNull { it.fileName == fileName }

    fun isEnglishOnly(fileName: String): Boolean {
        val name = fileName.lowercase()
        if (name.contains(".en-") || name.contains(".en.") || name.endsWith(".en.bin")) {
            return true
        }
        // A gguf file carries the quant in its name rather than a language marker, so the families
        // that only ever transcribe English are recognised by what they are called.
        return name.contains("moonshine") || name.contains("gigaam") || name.contains("110m")
    }

    fun customModel(fileName: String, url: String): WhisperModel = WhisperModel(
        id = "custom:$fileName",
        displayName = fileName.removePrefix("ggml-").removeSuffix(".bin").removeSuffix(".gguf"),
        fileName = fileName,
        url = url,
        approxBytes = 0,
        multilingual = !isEnglishOnly(fileName),
        tier = 3,
        custom = true
    )
}
