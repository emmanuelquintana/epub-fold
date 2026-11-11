plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase (cuentas y sincronización) sólo se activa si existe google-services.json
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    // Informes de fallos: sólo con Firebase configurado (necesita el id de compilación)
    apply(plugin = "com.google.firebase.crashlytics")
}

val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasReleaseKeystore = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.emmanuel.epubfold"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.emmanuel.epubfold"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        buildConfigField("boolean", "HAS_FIREBASE", hasFirebaseConfig.toString())
        // El Galaxy Z Fold (y casi todos los Android actuales) usan arm64:
        // así el APK no lleva librerías nativas de otras arquitecturas.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 quita el código de las librerías que no se usa: APK más ligero y rápido
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        // Las pruebas de la lógica pura corren en el ordenador; las llamadas a Android devuelven valores vacíos
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Traductor sin conexión y detección de idioma
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Cuentas y sincronización en la nube
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")

    // Iniciar sesión con Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Controles de la lectura en voz alta en notificación y pantalla de bloqueo
    implementation("androidx.media:media:1.7.0")

    // Importar carpetas completas
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Carpeta de entrada revisada en segundo plano
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Cómics en CBR (RAR)
    implementation("com.github.junrar:junrar:7.5.5")

    // Asistente de lectura con Claude (SDK oficial de Anthropic)
    implementation("com.anthropic:anthropic-java:3.8.0")

    // Paleta de colores de las portadas (generador de temas)
    implementation("androidx.palette:palette-ktx:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
