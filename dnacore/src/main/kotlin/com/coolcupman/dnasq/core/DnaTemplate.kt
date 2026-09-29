package com.coolcupman.dnasq.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.PriorityQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Irreversible, keyed DNA template.
 *
 * Nothing in the template is genetic data: every entry is `HMAC(K, locus) -> HMAC(K, locus|genotype)`
 * truncated. The loci that make up the panel are chosen by the keyed HMAC ordering, so even which
 * SNPs are used is secret. Without the 256-bit key K (which is sealed by the Android Keystore /
 * StrongBox and only released after biometric or device-credential authentication) the template
 * reveals nothing about the user's genome.
 *
 * Matching is tolerant: two lab tests of the same person rarely agree on 100% of calls (no-calls,
 * chip differences, genotyping errors), so we compare the genotype tags on the loci both samples
 * share and require a high concordance over a minimum number of loci.
 */
class DnaTemplate private constructor(
    val lab: LabProfile,
    val createdAtEpochMs: Long,
    private val key: ByteArray,
    private val entries: Map<Tag, Long>,
) {
    val panelSize: Int get() = entries.size

    /** Streaming verifier: feed every call of a fresh sample, then call [result]. */
    inner class Verifier {
        private val hasher = Hasher(key)
        private val seen = HashSet<Tag>()
        private var matches = 0

        fun accept(call: SnpCall) {
            val tag = hasher.locusTag(call.locus)
            val expected = entries[tag] ?: return
            if (!seen.add(tag)) return
            if (hasher.genotypeTag(call.locus, call.genotype) == expected) matches++
        }

        fun result(policy: MatchPolicy = MatchPolicy.DEFAULT): MatchResult {
            hasher.wipe()
            val overlap = seen.size
            val concordance = if (overlap == 0) 0.0 else matches.toDouble() / overlap
            val minOverlap = minOf(policy.minOverlap, panelSize)
            return MatchResult(
                matched = overlap >= minOverlap && concordance >= policy.minConcordance,
                overlap = overlap,
                matches = matches,
                concordance = concordance,
            )
        }
    }

    fun verifier(): Verifier = Verifier()

    /** Overwrite the key in memory. The template is unusable afterwards. */
    fun wipe() = key.fill(0)

    /** Plaintext serialisation. Must only ever be stored after sealing with an AEAD cipher. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(64 + entries.size * (TAG_BYTES + 8))
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(FORMAT_VERSION)
            out.writeByte(lab.code)
            out.writeLong(createdAtEpochMs)
            out.write(key)
            out.writeInt(entries.size)
            for ((tag, gt) in entries) {
                out.write(tag.bytes)
                out.writeLong(gt)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x444E5351 // "DNSQ"
        private const val FORMAT_VERSION = 1
        const val KEY_BYTES = 32
        const val TAG_BYTES = 16
        const val DEFAULT_PANEL_SIZE = 8192
        const val MIN_ENROLLMENT_CALLS = 2000

        fun decode(data: ByteArray): DnaTemplate {
            val input = DataInputStream(data.inputStream())
            if (input.readInt() != MAGIC) throw IllegalArgumentException("Not a DNAsq template")
            val version = input.readUnsignedByte()
            if (version != FORMAT_VERSION) throw IllegalArgumentException("Unsupported template version $version")
            val lab = LabProfile.fromCode(input.readUnsignedByte())
            val created = input.readLong()
            val key = ByteArray(KEY_BYTES).also(input::readFully)
            val count = input.readInt()
            require(count in 1..1_000_000) { "Corrupt template" }
            val entries = HashMap<Tag, Long>(count * 2)
            repeat(count) {
                val tag = ByteArray(TAG_BYTES).also(input::readFully)
                entries[Tag(tag)] = input.readLong()
            }
            return DnaTemplate(lab, created, key, entries)
        }
    }

    /** Builds a template from a streamed sample. Keeps only the [panelSize] smallest keyed tags. */
    class Builder(
        private val lab: LabProfile,
        private val panelSize: Int = DEFAULT_PANEL_SIZE,
        random: SecureRandom = SecureRandom(),
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        private val hasher = Hasher(key)
        // Max-heap on tag so the largest is evicted first.
        private val heap = PriorityQueue<Pair<Tag, Long>>(panelSize + 1, compareByDescending { it.first })
        private val inHeap = HashSet<Tag>()
        private var calls = 0

        fun accept(call: SnpCall) {
            calls++
            val tag = hasher.locusTag(call.locus)
            if (tag in inHeap) return
            if (heap.size >= panelSize && tag >= heap.peek().first) return
            heap.add(tag to hasher.genotypeTag(call.locus, call.genotype))
            inHeap.add(tag)
            if (heap.size > panelSize) inHeap.remove(heap.poll().first)
        }

        fun build(): DnaTemplate {
            hasher.wipe()
            if (calls < MIN_ENROLLMENT_CALLS) {
                key.fill(0)
                throw DnaParseException(
                    "Only $calls usable genotype calls found; at least $MIN_ENROLLMENT_CALLS are required",
                )
            }
            val entries = HashMap<Tag, Long>(heap.size * 2)
            for ((tag, gt) in heap) entries[tag] = gt
            heap.clear()
            inHeap.clear()
            return DnaTemplate(lab, clock(), key, entries)
        }
    }
}

data class MatchPolicy(val minConcordance: Double, val minOverlap: Int) {
    companion object {
        /**
         * Replicate tests of the same person typically agree on > 99.5% of shared calls; unrelated
         * people agree on roughly 40-65%, first-degree relatives on roughly 65-80%.
         * Identical (monozygotic) twins share the same DNA and cannot be told apart.
         */
        val DEFAULT = MatchPolicy(minConcordance = 0.97, minOverlap = 300)
    }
}

data class MatchResult(val matched: Boolean, val overlap: Int, val matches: Int, val concordance: Double)

internal class Tag(val bytes: ByteArray) : Comparable<Tag> {
    private val hash = bytes.contentHashCode()
    override fun hashCode() = hash
    override fun equals(other: Any?) = other is Tag && MessageDigest.isEqual(bytes, other.bytes)
    override fun compareTo(other: Tag): Int {
        for (i in bytes.indices) {
            val d = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}

/** Domain-separated HMAC-SHA256 helpers over the template key. */
private class Hasher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "HmacSHA256")
    private val mac = Mac.getInstance("HmacSHA256").apply { init(keySpec) }

    fun locusTag(locus: String): Tag {
        mac.update(0x01)
        return Tag(mac.doFinal(locus.toByteArray(Charsets.US_ASCII)).copyOf(DnaTemplate.TAG_BYTES))
    }

    fun genotypeTag(locus: String, genotype: String): Long {
        mac.update(0x02)
        mac.update(locus.toByteArray(Charsets.US_ASCII))
        mac.update(0x00)
        return ByteBuffer.wrap(mac.doFinal(genotype.toByteArray(Charsets.US_ASCII))).long
    }

    fun wipe() {
        mac.reset()
        runCatching { keySpec.destroy() } // SecretKeySpec.destroy is a no-op on most JVMs
    }
}
