package com.coolcupman.dnasq

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.Cipher

/**
 * Stores the sealed template: `keyAlias || iv || AES-256-GCM(template)` in no-backup app-private
 * storage. The plaintext template never touches disk.
 */
class TemplateStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "dna_template.sealed"))

    class Sealed(val keyAlias: String, val iv: ByteArray, val ciphertext: ByteArray)

    val isEnrolled: Boolean get() = file.baseFile.exists()

    fun read(): Sealed =
        DataInputStream(file.openRead()).use { input ->
            require(input.readUnsignedByte() == VERSION) { "Unknown sealed template version" }
            val alias = input.readUTF()
            val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val size = input.readInt()
            require(size in 1..64 * 1024 * 1024) { "Corrupt sealed template" }
            Sealed(alias, iv, ByteArray(size).also(input::readFully))
        }

    /** Seals [plaintext] with a cipher that the user has just authorised, then drops older keys. */
    fun save(keyAlias: String, authenticatedEncryptCipher: Cipher, plaintext: ByteArray) {
        val ciphertext = authenticatedEncryptCipher.doFinal(plaintext)
        val iv = authenticatedEncryptCipher.iv
        val out = file.startWrite()
        try {
            DataOutputStream(out).apply {
                writeByte(VERSION)
                writeUTF(keyAlias)
                writeByte(iv.size)
                write(iv)
                writeInt(ciphertext.size)
                write(ciphertext)
                flush()
            }
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        KeystoreVault.deleteKeysExcept(keyAlias)
    }

    fun delete() {
        file.delete()
        KeystoreVault.deleteKeysExcept(null)
    }

    private companion object {
        const val VERSION = 1
    }
}
