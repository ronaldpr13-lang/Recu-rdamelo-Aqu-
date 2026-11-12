plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gigaconet.recordatorios"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gigaconet.recordatorios"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "4.4"
    }

    // Firma fija: así cada versión nueva se instala encima sin borrar tus notas
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Avisos por ubicación (geovallas) y ubicación actual
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Mapa gratis de OpenStreetMap (no pide tarjeta ni clave)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
