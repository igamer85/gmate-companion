plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "live.elitecraft.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "live.elitecraft.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.01"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("GMATE_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("GMATE_KEYSTORE_PASS") ?: ""
                keyAlias = System.getenv("GMATE_KEY_ALIAS") ?: "gmate"
                keyPassword = System.getenv("GMATE_KEY_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksPath = System.getenv("GMATE_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
