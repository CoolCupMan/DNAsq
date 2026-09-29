package com.coolcupman.dnasq

import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import com.coolcupman.dnasq.core.AttemptLimiter
import com.coolcupman.dnasq.core.DnaParseException
import com.coolcupman.dnasq.core.DnaTemplate
import com.coolcupman.dnasq.core.GenotypeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-factor unlock: something you are twice over.
 *  1. Strong biometric / device credential releases the hardware-bound AES key (possession + user).
 *  2. A fresh DNA sample must match the sealed template (genetic factor).
 */
class DnaAuthenticator(private val activity: FragmentActivity) {
    private val app = activity.application as DnasqApp
    private val limiter = AttemptLimiter()

    sealed interface Outcome {
        data class Success(val message: String) : Outcome
        data class Failure(val message: String) : Outcome
    }

    suspend fun enroll(sample: Uri): Outcome {
        val template = try {
            withContext(Dispatchers.IO) {
                val builder = DnaTemplate.Builder(app.lab)
                DnaSampleReader.open(activity.contentResolver, sample).use { reader ->
                    GenotypeParser(app.lab).parse(reader, builder::accept)
                }
                builder.build()
            }
        } catch (e: DnaParseException) {
            return Outcome.Failure(e.message ?: "Unreadable DNA file")
        } catch (e: Exception) {
            return Outcome.Failure("Could not read the DNA file")
        }

        val plaintext = template.encode()
        try {
            val (alias, cipher) = try {
                KeystoreVault.newEncryptCipher()
            } catch (e: Exception) {
                return Outcome.Failure("Secure key could not be created. Set a screen lock (PIN/pattern/password) first.")
            }
            val unlocked = BiometricGate.unlock(
                activity, cipher, "Seal your DNA template", "Protects the template with secure hardware",
            )
            if (unlocked == null) {
                KeystoreVault.deleteKeysExcept(runCatching { app.templateStore.read().keyAlias }.getOrNull())
                return Outcome.Failure("Enrollment cancelled")
            }
            app.templateStore.save(alias, unlocked, plaintext)
        } finally {
            plaintext.fill(0)
            template.wipe()
        }
        app.settings.limiterState = limiter.onSuccess()
        Session.unlock()
        return Outcome.Success("DNA enrolled. ${template.panelSize} protected markers stored; the file itself was not kept.")
    }

    suspend fun verify(sample: Uri): Outcome {
        val wait = limiter.remainingLockMs(app.settings.limiterState)
        if (wait > 0) return Outcome.Failure("Too many failed attempts. Try again in ${(wait + 999) / 1000} s")

        val sealed = try {
            app.templateStore.read()
        } catch (e: Exception) {
            return Outcome.Failure("No DNA enrolled")
        }
        val cipher = try {
            KeystoreVault.decryptCipher(sealed.keyAlias, sealed.iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return Outcome.Failure("Biometrics changed; the DNA key was destroyed. Reset and enroll again.")
        } catch (e: Exception) {
            return Outcome.Failure("DNA key unavailable. Reset and enroll again.")
        }
        val unlocked = BiometricGate.unlock(
            activity, cipher, "Unlock DNA template", "Then your DNA sample is compared",
        ) ?: return Outcome.Failure("Authentication cancelled")

        val plaintext = try {
            unlocked.doFinal(sealed.ciphertext)
        } catch (e: Exception) {
            return Outcome.Failure("Sealed template failed integrity check")
        }
        val template = try {
            DnaTemplate.decode(plaintext)
        } catch (e: Exception) {
            return Outcome.Failure("Sealed template is corrupt")
        } finally {
            plaintext.fill(0)
        }

        val result = try {
            withContext(Dispatchers.IO) {
                val verifier = template.verifier()
                DnaSampleReader.open(activity.contentResolver, sample).use { reader ->
                    GenotypeParser(app.lab).parse(reader, verifier::accept)
                }
                verifier.result()
            }
        } catch (e: DnaParseException) {
            return Outcome.Failure(e.message ?: "Unreadable DNA file")
        } catch (e: Exception) {
            return Outcome.Failure("Could not read the DNA file")
        } finally {
            template.wipe()
        }

        return if (result.matched) {
            app.settings.limiterState = limiter.onSuccess()
            Session.unlock()
            Outcome.Success("DNA verified (%.1f%% of %d markers)".format(result.concordance * 100, result.overlap))
        } else {
            app.settings.limiterState = limiter.onFailure(app.settings.limiterState)
            Outcome.Failure("DNA does not match")
        }
    }
}
