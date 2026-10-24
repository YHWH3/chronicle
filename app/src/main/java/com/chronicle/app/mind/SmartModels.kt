package com.chronicle.app.mind

enum class Runtime { MEDIAPIPE, LITERT_LM }

enum class PromptFormat {
    PLAIN, CHAT_ML;

    fun render(text: String, now: java.time.LocalDateTime, systemPrompt: String = MIND_PROMPT): String {
        val capture = "Current time: $now\nCapture:\n$text"
        return when (this) {
            PLAIN -> "$systemPrompt\n\n$capture"
            CHAT_ML -> "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
                "<|im_start|>user\n$capture<|im_end|>\n<|im_start|>assistant\n"
        }
    }
}

data class SmartModel(
    val id: String,
    val displayName: String,
    val whatItDoes: String,
    val repo: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val gated: Boolean,
    val minRamGb: Int,
    val runtime: Runtime,
    val promptFormat: PromptFormat,
) {
    val packId get() = "llm/$id"
    val url get() = "https://huggingface.co/$repo/resolve/main/$file"
    fun supportedBy(totalRamBytes: Long) = totalRamBytes >= minRamGb * 1_000_000_000L
}

object SmartModels {
    const val DEFAULT_ID = "qwen2_5_1_5b"
    const val CUSTOM_PACK = "llm/custom"
    const val PARAKEET_PACK = "parakeet"

    // Llama runtime is deliberately provisional until the WP3 hardware spike.
    // A load failure, not a file-extension guess, triggers the LiteRT-LM adapter.
    val all = listOf(
        SmartModel(
            DEFAULT_ID, "Qwen 1.5B",
            "Recommended. Good at spotting to-dos and times. 1.6 GB. No account needed.",
            "litert-community/Qwen2.5-1.5B-Instruct",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            1_598_556_720L,
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            false, 6, Runtime.MEDIAPIPE, PromptFormat.PLAIN,
        ),
        SmartModel(
            "llama3_2_1b", "Llama 3.2 1B",
            "Smaller and quicker, a little less accurate. About 1 GB. Needs a free Hugging Face account.",
            "litert-community/Llama-3.2-1B", "llama3_2_1b_mixed_int4_gpu.litertlm",
            963_903_488L,
            "c6f21c5304c40f45b69e5bfb15bb45acbaad7938e941d25a0a391aef0ec6ac27",
            true, 4, Runtime.MEDIAPIPE, PromptFormat.PLAIN,
        ),
        SmartModel(
            "llama3_2_3b", "Llama 3.2 3B",
            "Most accurate, but slower. About 2.2 GB. Needs a newer phone and a free Hugging Face account.",
            "litert-community/Llama-3.2-3B", "llama3_2_3b_mixed_int4_gpu.litertlm",
            2_207_940_608L,
            "63419d689ef141cc77d6ab5739f27426d779e1165546a4904edefa9fc36fea51",
            true, 8, Runtime.MEDIAPIPE, PromptFormat.PLAIN,
        ),
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
    fun forPack(packId: String) = all.firstOrNull { it.packId == packId }
}
