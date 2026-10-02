package com.chronicle.app.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * The result of a fallible operation — either a value or a typed failure.
 * Cancellation is never captured: [safeSuspend] always rethrows
 * [CancellationException] so coroutine teardown stays instant.
 */
sealed interface OperationResult<out T> {
    data class Ok<T>(val value: T) : OperationResult<T>
    data class Failed(val error: Throwable) : OperationResult<Nothing>
}

/**
 * Runs [block] and folds the outcome into [OperationResult].
 * [CancellationException] propagates — everything else becomes [OperationResult.Failed].
 */
suspend inline fun <T> safeSuspend(crossinline block: suspend () -> T): OperationResult<T> =
    try {
        OperationResult.Ok(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        OperationResult.Failed(t)
    }

/**
 * A short, single-line, user/log-safe description of a failure — capped so a
 * chatty exception can't flood a log line or a debug surface.
 */
fun sanitizeReason(t: Throwable, max: Int = 120): String {
    val firstLine = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return (firstLine.ifBlank { t.javaClass.simpleName }).take(max)
}
