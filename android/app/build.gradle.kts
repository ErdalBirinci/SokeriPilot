import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release signature is read from android/keystore.properties outside the repository.
// If the file is missing, the release build stays unsigned (supply it as a CI secret).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "app.sokeripilot"
    compileSdk = 35

    defaultConfig {
        // The install identity stays app.glcopilot on purpose: Android only updates
        // an app in place when the applicationId and signing key match, so changing
        // it would leave every existing install behind. The code package (namespace
        // above) carries the SokeriPilot name; users never see this string.
        applicationId = "app.glcopilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.1"
        // The app ships English UI text only; do not pull library translations into the APK.
        resourceConfigurations += listOf("en")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Robolectric inflates the real phone layout so the diagnostics screen can be
    // rendered to a PNG on this machine, without a device or emulator.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // alpha12 is the newest Health Connect client that supports compileSdk 35.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20260814")
}
