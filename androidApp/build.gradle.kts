import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.exifiler.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.exifiler.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.0.2"
    }

    // Release signing — credentials are supplied via environment variables set by CI.
    // When any variable is absent (e.g. local debug builds) the signing config is skipped
    // and the release variant falls back to the default (unsigned) behaviour.
    val ksPath     = System.getenv("KEYSTORE_PATH")
    val ksPassword = System.getenv("KEYSTORE_PASSWORD")
    val ksAlias    = System.getenv("KEY_ALIAS")
    val ksKeyPwd   = System.getenv("KEY_PASSWORD")
    val hasSigningVars = listOf(ksPath, ksPassword, ksAlias, ksKeyPwd).all { !it.isNullOrBlank() }

    if (hasSigningVars) {
        signingConfigs {
            create("release") {
                storeFile     = file(ksPath!!)
                storePassword = ksPassword
                keyAlias      = ksAlias
                keyPassword   = ksKeyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasSigningVars) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
