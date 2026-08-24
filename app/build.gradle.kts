import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun executeCommand(vararg command: String): String {
    return try {
        val process = ProcessBuilder(*command)
            .directory(rootProject.projectDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor(10, TimeUnit.SECONDS)
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        ""
    }
}

fun getTagName(): String {
    val tag = executeCommand("git", "describe", "--tags", "--always", "--dirty")
    // Remove "v" prefix if present
    return tag.removePrefix("v").ifBlank { "1.1.1-debug" }
}

fun getTagVersionCode(): Int {
    val count = executeCommand("git", "rev-list", "--count", "HEAD")
    return try { count.toInt() } catch (e: Exception) { 1 }
}

android {
    namespace = "com.openclaw.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = getTagVersionCode()
        versionName = getTagName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    tasks.register("printVersion") {
        doLast {
            println("Version Name: ${getTagName()}")
            println("Version Code: ${getTagVersionCode()}")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = localProperties.getProperty("storeFile")
            if (keystoreFile != null) {
                storeFile = rootProject.file(keystoreFile)
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // CI sets FIREBASE_ENABLED=false for fork PRs so the APK launches without a real API key.
            // Defaults to true for local development.
            val firebaseEnabled = System.getenv("FIREBASE_ENABLED")?.toBooleanStrictOrNull() ?: true
            buildConfigField("boolean", "FIREBASE_ENABLED", firebaseEnabled.toString())
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "FIREBASE_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    // Product Flavors for VOICEVOX support
    flavorDimensions += "voicevox"
    productFlavors {
        create("standard") {
            dimension = "voicevox"
            buildConfigField("boolean", "VOICEVOX_ENABLED", "false")
            // Sideload-tier capabilities (Accessibility Bridge, SMS) on by default.
            // The Play track currently uses the same flavor — flip this to false
            // there to match Google Play accessibility/SMS restrictions.
            buildConfigField("boolean", "IS_SIDELOAD", "true")
        }
        create("full") {
            dimension = "voicevox"
            buildConfigField("boolean", "VOICEVOX_ENABLED", "true")
            buildConfigField("boolean", "IS_SIDELOAD", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs
            .filterIsInstance<com.android.build.api.variant.impl.VariantOutputImpl>()
            .forEach { output ->
                val versionName = output.versionName.orNull ?: "0"
                val buildType = variant.buildType
                output.outputFileName = "openclaw-${versionName}-${buildType}.apk"
            }
    }
}

// Debug builds now use the actual version from git tags
// This ensures BuildConfig.VERSION_NAME matches the real app version

dependencies {
    implementation(project(":shared"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.webkit:webkit:1.10.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Vosk
    implementation("com.alphacephei:vosk-android:0.3.75")
    
    // VOICEVOX (full flavor only)
    // libvoicevox_onnxruntime.so (VoiceVox custom ORT v1.23.2) is bundled in
    // app/src/full/jniLibs/arm64-v8a/. The standard Microsoft ORT does not support
    // the "vv-bin" model format required by voicevox_core 0.16.4, and v1.23.2 is
    // the first VoiceVox ORT build with 16 KB page alignment.
    add("fullImplementation", files("libs/voicevoxcore-android-0.16.4.aar"))

    // Tink (Crypto)
    implementation("com.google.crypto.tink:tink-android:1.10.0")

    // Bouncy Castle (Ed25519 provider)
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")

    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.14.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX
    val cameraXVersion = "1.5.2"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // EXIF
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // DNS-SD (Wide-Area Bonjour)
    implementation("dnsjava:dnsjava:3.6.4")

    // Material Components (XML theme + resources)
    implementation("com.google.android.material:material:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.8.0")
    testImplementation("org.robolectric:robolectric:4.16")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")

    // QR Code Scanning (Google Code Scanner — no camera permission required)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
