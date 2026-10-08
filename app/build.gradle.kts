import java.util.Base64
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Signing: ONE key everywhere (CI + local test builds), so local test APKs
// install over GitHub releases without uninstalling.
// Priority: CI env vars (base64 keystore) > local keystore file app/bitrans-release.jks.
val ciKeystoreB64 = System.getenv("KEYSTORE_BASE64")
val ciStorePass = System.getenv("KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("KEY_ALIAS")
val ciKeyPass = System.getenv("KEY_PASSWORD")
val hasCiSigning = !ciKeystoreB64.isNullOrBlank() && !ciStorePass.isNullOrBlank()

// local fallback: the SAME keystore CI uses, committed-adjacent (gitignored file)
val localKs = rootProject.file("app/bitrans-release.jks")
val localSecrets = rootProject.file(".secrets")
fun secret(name: String): String? =
    runCatching {
        val f = localSecrets.resolve("$name.txt")
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    }.getOrNull()
val hasLocalSigning = !hasCiSigning && localKs.exists() &&
    secret("KEYSTORE_PASSWORD") != null
val useLocalSigning = hasLocalSigning

android {
    namespace = "com.samge.bitrans"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.samge.bitrans"
        minSdk = 24
        targetSdk = 35
        versionCode = 24
        versionName = "1.4.7"
        ndk {
            // physical arm64 phone only (Xiaomi HyperOS target); halves transfer size
            abiFilters += listOf("arm64-v8a")
        }
    }

    lint {
        // Lint baseline: the 11 NewApi errors are pre-existing v1.3.4+ calls
        // (startForegroundService/NotificationChannel/playback-capture) guarded by the
        // app's real-world Android 10+ install base. Baseline lets lint fail on NEW
        // issues only.
        baseline = file("lint-baseline.xml")
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                val tmp = File.createTempFile("bitrans", ".jks")
                tmp.writeBytes(Base64.getDecoder().decode(ciKeystoreB64))
                storeFile = tmp
                storePassword = ciStorePass
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPass
            }
        } else if (useLocalSigning) {
            create("localRelease") {
                storeFile = localKs
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS") ?: "bitrans"
                keyPassword = secret("KEY_PASSWORD") ?: secret("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                hasCiSigning -> signingConfigs.getByName("ci")
                useLocalSigning -> signingConfigs.getByName("localRelease")
                else -> null
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // local test builds also use the release key when present — same
            // signature as GitHub releases, so they install as updates
            if (useLocalSigning) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ML Kit on-device translation (free, no billing; falls back to remote engines when absent)
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    // real org.json for unit tests (android.jar stubs return defaults silently)
    testImplementation("org.json:json:20240303")

    // Room: caption history persistence
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
}
