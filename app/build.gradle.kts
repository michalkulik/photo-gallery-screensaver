import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

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
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "PHOTOS_SCOPE", "\"https://www.googleapis.com/auth/photospicker.mediaitems.readonly\"")
        buildConfigField("String", "PICKER_API_BASE", "\"https://photospicker.googleapis.com/v1\"")
    }

    signingConfigs {
        val keystoreFile = findProperty("keystore.file") as String?
        val keystorePassword = findProperty("keystore.password") as String?
        val keyAlias = findProperty("signing.key.alias") as String?
        val keyPassword = findProperty("signing.key.password") as String?

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
