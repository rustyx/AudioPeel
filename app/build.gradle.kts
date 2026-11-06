import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is configured outside the repo, in ~/.gradle/gradle.properties or
// via ORG_GRADLE_PROJECT_<name> environment variables (e.g. on CI):
//   AUDIOPEEL_STORE_FILE, AUDIOPEEL_STORE_PASSWORD, AUDIOPEEL_KEY_ALIAS, AUDIOPEEL_KEY_PASSWORD
// If any is missing, release builds are produced unsigned.
val releaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { providers.gradleProperty("AUDIOPEEL_$it").orNull.orEmpty() }
    .takeIf { props -> props.values.none { it.isBlank() } }

android {
    namespace = "com.ltx.audiopeel"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.ltx.audiopeel"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("STORE_FILE"))
                storePassword = releaseSigning.getValue("STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("KEY_ALIAS")
                keyPassword = releaseSigning.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // Lets users pick the app language in system settings on Android 13+.
        generateLocaleConfig = true
        // Ship only the locales the app is translated into, so library strings
        // don't appear in a third language and the APK stays smaller.
        localeFilters += listOf("en", "zh", "b+zh+Hant")
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.ffmpeg.kit.audio)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}