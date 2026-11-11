import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val hasGoogleServices = layout.projectDirectory.file("google-services.json").asFile.exists()

val keystoreProperties = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText.orNull?.let { text -> Properties().apply { load(text.reader()) } }
val missingKeystoreKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .filter { keystoreProperties?.getProperty(it).isNullOrBlank() }
if (missingKeystoreKeys.isNotEmpty()) {
    val problem = if (keystoreProperties == null) "not found" else "has no ${missingKeystoreKeys.joinToString()}"
    logger.warn("Warning: keystore.properties $problem; using default signing.")
}

android {
    namespace = "com.hwangsihu.smt"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.hwangsihu.smt"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProperties != null && missingKeystoreKeys.isEmpty()) {
            create("keystore") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("keystore") ?: signingConfigs.getByName("debug")
            configure<CrashlyticsExtension> { nativeSymbolUploadEnabled = true }
        }
        release {
            signingConfig = signingConfigs.findByName("keystore")
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
                mappingFileUploadEnabled = hasGoogleServices
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += listOf("lng", "am", "cfg", "dt", "qs", "cart", "pb")
        generateLocaleConfig = true
        localeFilters += listOf("en", "ko")
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

googleServices {
    missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.firebase.installations)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
