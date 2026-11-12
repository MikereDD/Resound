package com.typezero.resound.core.update

/**
 * Typezer∅ version grammar (release manifest schema revision 2) and numeric comparator.
 * Components compare as integers, missing trailing components count as zero, and a stable
 * version orders above a development version with the same numeric core. Never lexical.
 */
data class TypezeroVersion(val core: List<Long>, val development: List<Long>?) : Comparable<TypezeroVersion> {

    val isDevelopment: Boolean get() = development != null

    override fun compareTo(other: TypezeroVersion): Int {
        compareParts(core, other.core).let { if (it != 0) return it }
        val mine = development
        val theirs = other.development
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1
            theirs == null -> -1
            else -> compareParts(mine, theirs)
        }
    }

    companion object {
        private val GRAMMAR = Regex("""^[0-9]+(?:\.[0-9]+)+(?:-dev(?:\.[0-9]+)+)?$""")

        /** Rejects malformed input instead of guessing; a parse failure is an update-check failure. */
        fun parse(value: String): TypezeroVersion {
            require(value.length in 3..96 && GRAMMAR.matches(value)) { "Invalid Typezer∅ version" }
            val parts = value.split("-dev.", limit = 2)
            fun numbers(text: String) = text.split('.').map {
                it.toLongOrNull() ?: throw IllegalArgumentException("Invalid Typezer∅ version")
            }
            return TypezeroVersion(numbers(parts[0]), parts.getOrNull(1)?.let(::numbers))
        }

        private fun compareParts(a: List<Long>, b: List<Long>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
                if (difference != 0) return difference
            }
            return 0
        }
    }
}
