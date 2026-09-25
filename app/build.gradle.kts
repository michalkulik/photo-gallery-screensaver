import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Signing values are read from `gradle.properties` or, for local release builds, from an untracked
 * `signing.properties` file next to it. Keeping them out of the repository means a checkout never
 * carries a signing key or its password.
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(key: String): String? =
    (findProperty(key) as String?)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(key)?.takeIf { it.isNotBlank() }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.michalkulik.photogallery"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.michalkulik.photogallery"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 11
        versionName = "1.4.1"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "PICKER_API_BASE", "\"https://photospicker.googleapis.com/v1\"")
        // OAuth relay that performs the browser sign-in on the user's phone.
        // Override with -Prelay.baseUrl=... to point at your own deployment.
        val relayBaseUrl = (findProperty("relay.baseUrl") as String?)
            ?: "https://screensaver.mkulik.eu"
        buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")

        // OpenWeather key, so the weather works without any setup.
        //
        // This is a convenience default, not a secret: the source is public, so the key is
        // public too and anyone can spend its quota. It can be replaced at run time from the
        // slideshow settings, and built over with -PopenWeather.key=... for a private build.
        val openWeatherKey = (findProperty("openWeather.key") as String?)
            ?: "07af8dd914436ccc44eef5d8e24f3168"
        buildConfigField("String", "OPEN_WEATHER_KEY", "\"$openWeatherKey\"")
    }

    signingConfigs {
        val keystoreFile = signingValue("keystore.file")
        val keystorePassword = signingValue("keystore.password")
        val keyAlias = signingValue("signing.key.alias")
        val keyPassword = signingValue("signing.key.password")

        if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                storeFile = rootProject.file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
