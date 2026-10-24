package com.chronicle.app.mind

import android.content.Context
import java.io.File

/** Which downloadable pack a model state refers to. */
enum class ModelKind(
    val title: String,
    /**
     * Measured size of the pack the default source serves, in bytes —
     * -1 when there is no default (bring-your-own model) and the size is
     * whatever the user's archive happens to be. Display hint only; the
     * real Content-Length wins once a download is in flight.
     */
    val nominalBytes: Long,
    /**
     * Optional SHA-256 pin (hex) for the pack. Null today — the default
     * sources publish no digests — but when set the installer verifies the
     * downloaded bytes before anything is unpacked.
     */
    val expectedSha256: String? = null,
) {
    // 487 170 055 B — github.com/k2-fsa release asset, measured via HEAD.
    PARAKEET_STT(
        "Parakeet TDT 0.6B · speech to text",
        nominalBytes = 487_170_055L,
    ),
    // The litert-community .task archive is licence-gated, so no default
    // ships and there is no canonical size to promise (~3 GB for the q8
    // build is typical, but users may point at any bundle).
    LOCAL_LLM(
        "On-device language model · bring your own",
        nominalBytes = -1L,
    );

    val packId: String get() = when (this) {
        PARAKEET_STT -> SmartModels.PARAKEET_PACK
        LOCAL_LLM -> SmartModels.CUSTOM_PACK
    }
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    /** Cancellable connection setup, before headers/progress are available. */
    data object Connecting : ModelState

    /**
     * [progress] is -1 when the server won't report a total size;
     * [totalBytes] carries the real Content-Length once known (-1 unknown).
     */
    data class Downloading(
        val progress: Float,
        val doneBytes: Long,
        val totalBytes: Long = -1L,
    ) : ModelState

    data object Extracting : ModelState
    data object Installed : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Pure on-disk state mapping for model packs: which directories exist under
 * filesDir/models/, which contain the `.installed` marker, and which files
 * a ready pack holds. Downloads, verification and jobs live in
 * [ModelInstallManager] — this class only answers questions about the
 * filesystem and never mutates during reads.
 */
class ModelStore(
    /** Directory that holds the pack roots — injectable for tests. */
    internal val root: File,
) {

    constructor(context: Context) : this(File(context.filesDir, "models"))

    /** The pack's final directory name (also the base for .part/.extracting siblings). */
    fun dirName(kind: ModelKind): String = dirName(kind.packId)
    fun dirName(packId: String): String {
        require(packId in packIds) { "Unknown model pack" }
        // Keep the existing Parakeet install path unchanged.
        return if (packId == SmartModels.PARAKEET_PACK) "parakeet_stt" else packId
    }
    val packIds: List<String> get() = listOf(SmartModels.PARAKEET_PACK, SmartModels.CUSTOM_PACK) +
        SmartModels.all.map { it.packId }

    fun dir(kind: ModelKind): File = dir(kind.packId)
    fun dir(packId: String): File = File(root, dirName(packId))

    /** The pack dir when it carries the install marker, else null. */
    fun installedDir(kind: ModelKind): File? =
        installedDir(kind.packId)

    fun installedDir(packId: String): File? = dir(packId).takeIf { File(it, MARKER).exists() }

    /** The installed bundle file for the local LLM (.task or .litertlm), if present. */
    fun localLlmFile(packId: String = SmartModels.CUSTOM_PACK): File? = installedDir(packId)
        ?.let { dir -> dir.walkTopDown().firstOrNull { f -> f.isFile && isLlmBundle(f) } }

    /** One-time relocation of legacy custom packs, including resumable transfers. */
    internal fun migrateCustomPack() {
        File(root, "llm").mkdirs()
        for (suffix in listOf("", ".part", ".extracting")) {
            val legacy = File(root, "local_llm" + suffix)
            val target = File(root, SmartModels.CUSTOM_PACK + suffix)
            if (legacy.exists() && !target.exists()) {
                check(legacy.renameTo(target)) { "Could not migrate custom model" }
            }
        }
    }

    internal fun markerFile(dir: File) = File(dir, MARKER)

    internal fun writeMarker(dir: File) {
        markerFile(dir).writeText("ok")
    }

    internal fun clearMarker(dir: File) {
        markerFile(dir).delete()
    }

    companion object {
        const val MARKER = ".installed"

        /** Model bundle extensions the local mind can run through LlmInference. */
        internal val LLM_EXTENSIONS = listOf("task", "litertlm")

        internal fun isLlmBundle(f: File): Boolean =
            LLM_EXTENSIONS.any { f.extension.equals(it, ignoreCase = true) }
    }
}
