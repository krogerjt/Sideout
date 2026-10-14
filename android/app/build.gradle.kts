plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The score server's address is compiled in, like SIDEOUT_SERVER_URL in the iOS build. Pass it as an environment
// variable or a Gradle property (-PsideoutServerUrl=https://...); without it, the app asks for an address in Settings.
val serverUrl = (System.getenv("SIDEOUT_SERVER_URL") ?: findProperty("sideoutServerUrl") as String? ?: "").trim()
// A fixed signing key lets each new APK install over the last one. Without it, builds use the machine's debug key.
val keystorePath: String? = System.getenv("SIDEOUT_KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.krogerjt.sideout"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krogerjt.sideout"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"${serverUrl.replace("\"", "")}\"")
    }

    signingConfigs {
        if (keystorePath != null) create("sideout") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIDEOUT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SIDEOUT_KEY_ALIAS") ?: "sideout"
            keyPassword = System.getenv("SIDEOUT_KEY_PASSWORD") ?: System.getenv("SIDEOUT_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("sideout") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests, so tests use the real library.
    testImplementation("org.json:json:20240303")
}
