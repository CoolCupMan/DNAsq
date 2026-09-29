package com.coolcupman.dnasq.core

/**
 * A single genotype call, already normalised so that the same person tested by
 * different chips / strands / labs yields the same [locus] and [genotype].
 */
class SnpCall(val locus: String, val genotype: String)

object Normalizer {
    private val CHROM_ALIASES = mapOf(
        "23" to "X", "24" to "Y", "25" to "XY", "26" to "MT", "M" to "MT",
    )

    fun chromosome(raw: String): String {
        val c = raw.trim().uppercase().removePrefix("CHR")
        return CHROM_ALIASES[c] ?: c
    }

    /**
     * Locus identifier. dbSNP rsIDs are build independent and shared across labs, so they are
     * preferred. Otherwise falls back to `chrom:pos` (only comparable within the same genome build).
     */
    fun locus(id: String?, chrom: String, pos: String): String? {
        val rs = id?.trim()?.lowercase()
        if (rs != null && rs.startsWith("rs") && rs.length > 2 && rs.drop(2).all(Char::isDigit)) return rs
        val p = pos.trim()
        if (p.isEmpty() || !p.all(Char::isDigit)) return null
        return chromosome(chrom) + ":" + p
    }

    /**
     * Canonical, strand-independent genotype: alleles sorted, then the lexicographically smaller of
     * the call and its reverse-strand complement. Returns null for no-calls, indels (`D`/`I`) and
     * anything that is not a plain A/C/G/T call.
     */
    fun genotype(alleles: String): String? {
        val a = alleles.trim().uppercase()
        if (a.isEmpty() || a.length > 2) return null
        if (a.any { it !in "ACGT" }) return null
        val direct = a.toCharArray().sorted().joinToString("")
        val flipped = a.map(::complement).sorted().joinToString("")
        return if (direct <= flipped) direct else flipped
    }

    private fun complement(c: Char): Char = when (c) {
        'A' -> 'T'; 'T' -> 'A'; 'C' -> 'G'; 'G' -> 'C'
        else -> c
    }
}
