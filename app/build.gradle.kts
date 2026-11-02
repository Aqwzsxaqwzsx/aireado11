plugins {
    id("com.android.application")
}

android {
    namespace = "com.aireado.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aireado.reader"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Bundled Latin OCR model: scanned books remain readable without a first-run download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
