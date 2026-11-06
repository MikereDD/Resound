package com.typezero.resound.core.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One native input/output iteration; progress must mean consumed input or decoded PCM. */
data class DecodeStep(val progressed: Boolean, val done: Boolean)

/** Shared production drain loop. Native step calls remain cooperatively, not forcibly, bounded. */
class DecodeDrain(private val budget: DecodeBudget) {
    suspend fun run(step: () -> DecodeStep) {
        val context = currentCoroutineContext()
        while (true) {
            context.ensureActive()
            budget.check()
            val result = step()
            context.ensureActive()
            budget.check(result.progressed)
            if (result.done) return
        }
    }
}
