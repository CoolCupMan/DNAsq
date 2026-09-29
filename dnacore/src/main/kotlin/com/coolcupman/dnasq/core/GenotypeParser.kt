package com.coolcupman.dnasq.core

import java.io.BufferedReader

class DnaParseException(message: String) : Exception(message)

data class ParseReport(
    val format: DnaFileFormat,
    val acceptedCalls: Int,
    val skippedLines: Int,
)

/**
 * Streaming parser for laboratory raw-data files. Nothing is buffered beyond the current line, so
 * the full sequence is never held in memory and never written anywhere.
 */
class GenotypeParser(
    private val profile: LabProfile,
    private val maxCalls: Int = 12_000_000,
) {

    fun parse(reader: BufferedReader, sink: (SnpCall) -> Unit): ParseReport {
        var format: DnaFileFormat? = null
        var vcf: VcfState? = null
        var accepted = 0
        var skipped = 0
        var lineNo = 0

        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            if (lineNo == 1 && raw.startsWith("##fileformat=VCF")) {
                format = requireAllowed(DnaFileFormat.VCF)
                vcf = VcfState()
                continue
            }
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue

            if (format == DnaFileFormat.VCF) {
                if (line.startsWith("##")) continue
                if (line.startsWith("#CHROM")) { vcf!!.header(line); continue }
            } else if (line.startsWith("#")) {
                continue
            }

            if (format == null) {
                format = requireAllowed(detect(line))
                if (isHeaderRow(line)) continue
            }

            val call = when (format) {
                DnaFileFormat.TWENTY_THREE_AND_ME -> parse23andMe(line)
                DnaFileFormat.ANCESTRY_DNA -> parseAncestry(line)
                DnaFileFormat.QUOTED_CSV -> parseCsv(line)
                DnaFileFormat.VCF -> vcf!!.parse(line)
            }
            if (call == null) {
                skipped++
            } else {
                if (++accepted > maxCalls) throw DnaParseException("File contains too many calls")
                sink(call)
            }
        }
        if (format == null) throw DnaParseException("File is empty or not a recognised DNA raw-data file")
        return ParseReport(format, accepted, skipped)
    }

    private fun requireAllowed(format: DnaFileFormat): DnaFileFormat {
        if (format !in profile.formats) {
            throw DnaParseException(
                "${format.displayName} is not supported by the ${profile.displayName} edition",
            )
        }
        return format
    }

    private fun isHeaderRow(line: String): Boolean =
        line.trim('"', ' ').lowercase().startsWith("rsid")

    private fun detect(line: String): DnaFileFormat {
        val lower = line.lowercase()
        if (lower.startsWith("\"rsid\"") || lower.startsWith("rsid,") || line.startsWith("\"rs")) {
            return DnaFileFormat.QUOTED_CSV
        }
        return when (line.split('\t').size) {
            4 -> DnaFileFormat.TWENTY_THREE_AND_ME
            5 -> DnaFileFormat.ANCESTRY_DNA
            else -> throw DnaParseException("Unrecognised DNA raw-data layout")
        }
    }

    private fun parse23andMe(line: String): SnpCall? {
        val c = line.split('\t')
        if (c.size != 4) return null
        val locus = Normalizer.locus(c[0], c[1], c[2]) ?: return null
        val gt = Normalizer.genotype(c[3]) ?: return null
        return SnpCall(locus, gt)
    }

    private fun parseAncestry(line: String): SnpCall? {
        val c = line.split('\t')
        if (c.size != 5) return null
        val locus = Normalizer.locus(c[0], c[1], c[2]) ?: return null
        val gt = Normalizer.genotype(c[3].trim() + c[4].trim()) ?: return null
        return SnpCall(locus, gt)
    }

    private fun parseCsv(line: String): SnpCall? {
        val c = line.split(',').map { it.trim().trim('"') }
        if (c.size != 4) return null
        val locus = Normalizer.locus(c[0], c[1], c[2]) ?: return null
        val gt = Normalizer.genotype(c[3]) ?: return null
        return SnpCall(locus, gt)
    }

    /** VCF 4.x, first sample column only, biallelic/multiallelic SNVs only. */
    private class VcfState {
        private var headerSeen = false

        fun header(line: String) {
            val cols = line.split('\t')
            if (cols.size < 10) throw DnaParseException("VCF contains no sample column")
            headerSeen = true
        }

        fun parse(line: String): SnpCall? {
            if (!headerSeen) throw DnaParseException("VCF is missing the #CHROM header line")
            val c = line.split('\t')
            if (c.size < 10) return null
            val filter = c[6]
            if (filter != "PASS" && filter != ".") return null
            val ref = c[3].uppercase()
            if (ref.length != 1) return null
            val alts = if (c[4] == ".") emptyList() else c[4].uppercase().split(',')
            val alleles = listOf(ref) + alts
            val gtIndex = c[8].split(':').indexOf("GT")
            if (gtIndex < 0) return null
            val gt = c[9].split(':').getOrNull(gtIndex) ?: return null
            val bases = StringBuilder(2)
            for (part in gt.split('/', '|')) {
                val idx = part.toIntOrNull() ?: return null
                val allele = alleles.getOrNull(idx) ?: return null
                if (allele.length != 1) return null // indel, <NON_REF>, *
                bases.append(allele)
            }
            val locus = Normalizer.locus(c[2], c[0], c[1]) ?: return null
            val genotype = Normalizer.genotype(bases.toString()) ?: return null
            return SnpCall(locus, genotype)
        }
    }
}
