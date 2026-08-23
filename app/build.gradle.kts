import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Stable upload keystore so CI/user APKs can update in-place (same signature).
// Override via env/local.properties if needed; defaults match app/filenest-upload.jks.
val uploadStorePassword: String = localProperties.getProperty("FILENEST_STORE_PASSWORD")
    ?: System.getenv("FILENEST_STORE_PASSWORD")
    ?: "filenest-upload"
val uploadKeyPassword: String = localProperties.getProperty("FILENEST_KEY_PASSWORD")
    ?: System.getenv("FILENEST_KEY_PASSWORD")
    ?: "filenest-upload"
val uploadKeyAlias: String = localProperties.getProperty("FILENEST_KEY_ALIAS")
    ?: System.getenv("FILENEST_KEY_ALIAS")
    ?: "filenest"
val uploadStoreFile = rootProject.file("app/filenest-upload.jks")

android {
    namespace = "com.zipextract.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zipextract.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 95
        versionName = "2.4.50"
    }

    signingConfigs {
        create("filenest") {
            storeFile = uploadStoreFile
            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }

    buildTypes {
        debug {
            // Same key as release so GitHub APKs update over each other without uninstall.
            signingConfig = signingConfigs.getByName("filenest")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("filenest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
