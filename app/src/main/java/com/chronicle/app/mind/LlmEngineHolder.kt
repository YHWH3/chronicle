package com.chronicle.app.mind

/**
 * Something that pins a heavy native resource — freeing it early matters
 * more than waiting for GC (the Java wrapper is tiny; the native buffers
 * behind it are not).
 */
interface ReleasableEngine {
    /** Releases the native resource; must be safe to call twice. */
    fun close()
}

/**
 * App-scoped cache for the on-device LLM. Resolving an engine used to build
 * a fresh [LocalLlmMind] per capture — each one carried its own ~2 GB
 * LlmInference — so the holder keeps a single instance keyed by model path
 * and reuses it until the path changes or it is released (backend switch,
 * model deleted).
 *
 * The cache is keyed by path alone: only the local engine lives here, so
 * the backend dimension is implied. `acquire` is deliberately synchronous —
 * [MindRepository.resolve] is a synchronous API, and constructing the
 * wrapper is cheap; the expensive native load stays lazy inside the engine
 * and happens off the calling thread on first `extract`.
 */
class LlmEngineHolder<E : ReleasableEngine>(
    private val create: (modelPath: String) -> E,
) {

    private var cachedPath: String? = null
    private var cached: E? = null

    /**
     * The engine for [modelPath] — the cached one while the key is
     * unchanged, a freshly created one otherwise (releasing the stale).
     */
    @Synchronized
    fun acquire(modelPath: String): E {
        val current = cached
        if (current != null && cachedPath == modelPath) return current
        releaseLocked()
        return create(modelPath).also {
            cached = it
            cachedPath = modelPath
        }
    }

    /**
     * Drops the cached engine. The GC hint exists because close() frees the
     * bulk of the memory natively, but the dead JNI wrapper only becomes
     * unreachable garbage — on a Java heap with little pressure the runtime
     * may sit on it for a long time, and 2 GB of no-longer-addressable
     * native backing stores can still trip the OS before GC runs.
     */
    @Synchronized
    fun release() {
        if (cached == null) return
        releaseLocked()
        System.gc()
    }

    private fun releaseLocked() {
        val e = cached ?: return
        cached = null
        cachedPath = null
        runCatching { e.close() }
    }
}
