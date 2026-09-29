# DNAsq: DNA-sequence app lock for Android

DNAsq locks any apps you choose on an Android phone (API 30+). To open a locked app you need
**two things**: a strong biometric or your device PIN, which releases a hardware-bound key, and
**a DNA sample that matches your enrolled DNA**. The sample can be a laboratory raw-data file
or output from a sequencing device.

## Lab editions (parallel installation)

Each testing-lab family is its own build flavor. Every flavor has its own application ID and its
own version code and name, so any combination can be installed side by side.

| Flavor       | Application ID                     | Version name     | Version code | Accepted input                         |
|--------------|------------------------------------|------------------|--------------|----------------------------------------|
| `universal`  | `com.coolcupman.dnasq.universal`   | `1.0.0-universal`| `1000000`    | all formats below                      |
| `ttam`       | `com.coolcupman.dnasq.ttam`        | `1.0.0-ttam`     | `1000001`    | 23andMe raw data (`.txt`/`.zip`)       |
| `ancestry`   | `com.coolcupman.dnasq.ancestry`    | `1.0.0-ancestry` | `1000002`    | AncestryDNA raw data                   |
| `myheritage` | `com.coolcupman.dnasq.myheritage`  | `1.0.0-myheritage`| `1000003`   | MyHeritage CSV                         |
| `ftdna`      | `com.coolcupman.dnasq.ftdna`       | `1.0.0-ftdna`    | `1000004`    | FamilyTreeDNA CSV                      |
| `wgs`        | `com.coolcupman.dnasq.wgs`         | `1.0.0-wgs`      | `1000005`    | VCF from sequencing labs and sequencers (`.vcf`/`.gz`) |

Version code = `(major*10000 + minor*100 + patch) * 100 + labCode`. Debug builds add the suffix
`.debug` to the application ID and `-debug` to the version name. The app version is set at the top of
`app/build.gradle.kts`.

## Security design

* **No network permission.** The APK has no `INTERNET` permission, so it cannot upload anything.
* **The DNA file is never stored.** The file is streamed line by line from the Storage Access
  Framework and is never copied into app storage.
* **Irreversible, keyed template.** Enrollment picks a secret panel of up to 8,192 SNP loci and stores
  only `HMAC-SHA256(K, locus) → HMAC-SHA256(K, locus‖genotype)` tags. Without `K` the tags reveal
  nothing about the genome. A new random `K` is generated for every enrollment, so two templates of
  the same person cannot be linked to each other.
* **Hardware sealing.** The template and `K` are encrypted with **AES-256-GCM** under an Android
  Keystore key that has these properties:
  * It lives in the StrongBox secure element if the phone has one, otherwise in the TEE.
  * It needs a **fresh strong-biometric or device-credential authentication for every use**
    (timeout 0 plus a `CryptoObject`).
  * It only works while the device is unlocked.
  * It is destroyed automatically when new fingerprints or faces are enrolled.
* **Tolerant matching.** Replicate tests from different chips, strands or labs are compared on the
  loci both tests share. A match needs ≥ 97 % concordance over at least 300 loci. Unrelated people
  score about 40–65 % and close relatives about 65–80 %.
* **Brute-force lock-out.** After 3 failed attempts the lock-out grows exponentially from 30 s up to
  24 h, and it survives restarts.
* **No leaks.** Screens use `FLAG_SECURE` (no screenshots or recents thumbnails). Cloud backup and
  device-transfer are disabled, and release builds strip all logging.
* **Session.** One verification unlocks all locked apps until the screen turns off, for at most
  15 minutes. Unlock state is only in memory.
* **Managing DNAsq itself requires a verification.** You must verify before you can change the
  locked-app list, re-enroll or delete.

## Compatible devices

No phone can read DNA by itself. Sequencing (for example with a nanopore sequencer) needs sample
prep and takes minutes to hours. DNAsq accepts a sequencing device's output through Android's
document picker. That includes:

* a USB-OTG drive or SD card,
* a sequencer or lab companion app that exposes results as a `DocumentsProvider`,
* any file downloaded from the lab (`.txt`, `.csv`, `.vcf`, `.gz`, `.zip`).

Use the `wgs` edition for VCF output from sequencers.

## Limitations

* **DNA is not a secret.** You leave it everywhere, and raw-data files get shared and leaked. Anyone
  who has a copy of your raw-data file has the DNA factor. That is why DNAsq always also requires
  biometric or device-PIN authentication.
* **DNA cannot be revoked.** If your raw data leaks, you cannot change it the way you change a
  password.
* **Identical twins cannot be told apart.**
* Cross-lab matching only works through rsIDs. `chrom:pos`-only VCFs match only files from the same
  genome build or pipeline.
* An accessibility-based app lock is not a sandbox. The device owner can turn the service off in
  Settings, boot into safe mode, or uninstall DNAsq. To make that harder, lock the **Settings** app
  as well. The JVM cannot guarantee that strings from the parsed file are wiped from memory.
* Google Play restricts the accessibility API. Sideloading or enterprise distribution is simplest.

## Build

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew :dnacore:test          # unit tests for parser, template, matching, lock-out
./gradlew assembleDebug          # debug APKs for all six lab editions
./gradlew assembleTtamRelease    # a single edition, release
```

Without the Android SDK you can still build and test the core: `./gradlew -PcoreOnly=true :dnacore:test`.

APKs are written to `app/build/outputs/apk/<flavor>/<buildType>/`. The GitHub Actions workflow
builds all editions on every push and uploads them as the `dnasq-apks` artifact.

Release signing uses these environment variables: `DNASQ_KEYSTORE`, `DNASQ_KEYSTORE_PASSWORD`,
`DNASQ_KEY_ALIAS` and `DNASQ_KEY_PASSWORD`. In CI, set the secrets `DNASQ_KEYSTORE_BASE64`,
`DNASQ_KEYSTORE_PASSWORD`, `DNASQ_KEY_ALIAS` and `DNASQ_KEY_PASSWORD`.

## Usage

1. Install an edition, set a device screen lock, and open DNAsq.
2. Enable the **DNAsq** accessibility service. The app links to the setting.
3. **Enroll**: pick your lab raw-data file or VCF, then confirm with your fingerprint, face or PIN.
4. Tick the apps to lock (or **Lock all**).
5. When a locked app opens, DNAsq covers it. Tap **Verify with DNA sample**, authenticate, and
   pick your sample.

## Layout

```
dnacore/   pure Kotlin: lab formats, streaming parser, keyed template, matcher, lock-out (unit-tested)
app/       Android: Keystore vault, sealed template store, accessibility app lock, Compose UI
```
