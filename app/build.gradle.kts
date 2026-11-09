// Modul aplikasi: Paradox ZX68 Configurator (porting driver Windows ke Android).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.paradox.zx68"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.paradox.zx68"
        minSdk = 23          // Android 6.0: USB Host API + runtime permission
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            // Tidak memakai minify agar mudah di-debug di lapangan.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Unit test JVM untuk logika protokol (tanpa perangkat/emulator).
    testImplementation("junit:junit:4.13.2")
}
