package com.typezero.resound.core.audio

/** Minted by the preparation owner before allocation; identity is reference-based. */
class PreparationTicket internal constructor(
    val sourceKey: String,
    val generation: Long,
    val ownerIdentity: Any,
)
