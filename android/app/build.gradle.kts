plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.popcorn.store"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.popcorn.store"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Your store address (already set). The online build can override it; for Android Studio, edit the fallback below.
        val storeUrl = (project.findProperty("storeUrl") as String?) ?: "https://midtownbiz.com/.popstore/"
        buildConfigField("String", "STORE_URL", "\"$storeUrl\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"   // lets you test against http:// on your own network
        }
        release {
            manifestPlaceholders["cleartext"] = "false"  // real customers: https only
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
