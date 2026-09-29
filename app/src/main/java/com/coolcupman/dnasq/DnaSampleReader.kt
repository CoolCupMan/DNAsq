package com.coolcupman.dnasq

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Opens a DNA sample chosen through the Storage Access Framework. That covers files on the phone,
 * USB-OTG drives, cloud providers, and any sequencer / lab companion app that exposes its results
 * through a DocumentsProvider. Plain text, `.gz` and `.zip` (as downloaded from 23andMe, Ancestry…)
 * are accepted. The data is streamed; it is never copied into app storage.
 */
object DnaSampleReader {
    private const val MAX_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024
    private val DATA_EXTENSIONS = listOf(".txt", ".csv", ".tsv", ".vcf")

    fun open(resolver: ContentResolver, uri: Uri): BufferedReader {
        val raw = resolver.openInputStream(uri) ?: throw IOException("Cannot open sample")
        val buffered = BufferedInputStream(raw, 64 * 1024)
        val decoded = unwrap(buffered)
        return LimitedInputStream(decoded, MAX_UNCOMPRESSED_BYTES).bufferedReader(Charsets.UTF_8)
    }

    private fun unwrap(input: BufferedInputStream): InputStream {
        input.mark(4)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return when {
            b0 == 0x1f && b1 == 0x8b -> GZIPInputStream(input, 64 * 1024)
            b0 == 'P'.code && b1 == 'K'.code -> firstDataEntry(ZipInputStream(input))
            else -> input
        }
    }

    private fun firstDataEntry(zip: ZipInputStream): InputStream {
        while (true) {
            val entry = zip.nextEntry ?: throw IOException("ZIP archive contains no DNA raw-data file")
            val name = entry.name.lowercase()
            if (!entry.isDirectory && !name.contains("..") && DATA_EXTENSIONS.any(name::endsWith)) return zip
        }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw IOException("DNA sample is too large")
        }
    }
}
