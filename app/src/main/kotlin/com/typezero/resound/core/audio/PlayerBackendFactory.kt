package com.typezero.resound.core.audio

/** Resolve per-preparation policy before allocating any native resource. */
fun interface PlayerBackendFactory {
    fun bind(ticket: PreparationTicket): (PlayerBackend) -> PlayerBackend
}

/** Called on the owner's IO preparation dispatcher. */
internal fun allocatePlayerBackend(
    ticket: PreparationTicket,
    factory: PlayerBackendFactory,
    allocate: () -> PlayerBackend,
): PlayerBackend {
    val decorate = factory.bind(ticket)
    val candidate = allocate()
    return constructOwned(candidate, decorate) { it.release() }
}

/** Transfer a raw handle only after adapter/decorator construction succeeds. IO only. */
internal fun <T, R> constructOwned(resource: T, construct: (T) -> R, release: (T) -> Unit): R =
    try { construct(resource) } catch (primary: Throwable) {
        try { release(resource) } catch (cleanup: Throwable) {
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
        throw primary
    }
