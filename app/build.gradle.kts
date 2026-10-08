plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número do run do GitHub = versionCode (é isso que o app compara para achar versão nova)
val numeroRun: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.tempocongelado.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tempocongelado.app"
        minSdk = 24
        targetSdk = 34
        versionCode = numeroRun
        versionName = "1.$numeroRun"
    }

    // Keystore FIXO: nunca troque, senão a atualização por cima deixa de funcionar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("tempocongelado.keystore")
            storeType = "pkcs12"
            storePassword = "tempo123456"
            keyAlias = "tempo"
            keyPassword = "tempo123456"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
