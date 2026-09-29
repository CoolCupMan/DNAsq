plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Semantic app version shared by every lab edition.
val versionMajor = 1
val versionMinor = 0
val versionPatch = 0

/**
 * One edition per DNA-testing laboratory family (mirrors core LabProfile; codes must match and never
 * change). Every edition gets its own applicationId so any number of them can be installed in
 * parallel, and its own versionCode (last two digits = lab code) / versionName suffix.
 */
data class LabEdition(val flavor: String, val code: Int, val label: String)

val labEditions = listOf(
    LabEdition("universal", 0, "All labs"),
    LabEdition("ttam", 1, "23andMe"),
    LabEdition("ancestry", 2, "AncestryDNA"),
    LabEdition("myheritage", 3, "MyHeritage"),
    LabEdition("ftdna", 4, "FamilyTreeDNA"),
    LabEdition("wgs", 5, "Sequencing VCF"),
)

val baseVersionCode = versionMajor * 10_000 + versionMinor * 100 + versionPatch
val baseVersionName = "$versionMajor.$versionMinor.$versionPatch"

android {
    namespace = "com.coolcupman.dnasq"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coolcupman.dnasq"
        // API 30+: BiometricPrompt CryptoObject with device-credential fallback and
        // per-operation Keystore authentication.
        minSdk = 30
        targetSdk = 35
        versionCode = baseVersionCode * 100
        versionName = baseVersionName
    }

    flavorDimensions += "lab"
    productFlavors {
        labEditions.forEach { lab ->
            create(lab.flavor) {
                dimension = "lab"
                applicationIdSuffix = ".${lab.flavor}"
                versionCode = baseVersionCode * 100 + lab.code
                versionNameSuffix = "-${lab.flavor}"
                buildConfigField("String", "LAB_FLAVOR", "\"${lab.flavor}\"")
                resValue("string", "app_name", "DNAsq ${lab.label}")
            }
        }
    }

    signingConfigs {
        val storeFile = System.getenv("DNASQ_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = System.getenv("DNASQ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DNASQ_KEY_ALIAS")
                keyPassword = System.getenv("DNASQ_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":dnacore"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
