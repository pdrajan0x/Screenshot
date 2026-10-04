plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.pdrajan.dotscreenshots"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.pdrajan.dotscreenshots"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        ndk {
            // Every phone this targets is 64-bit ARM; skipping other ABIs keeps ONNX Runtime small.
            // (-Pdot.abi=x86_64 builds for the emulator that takes the README screenshots.)
            abiFilters += (project.findProperty("dot.abi") as String?) ?: "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Provided by CI (secrets or a cached key); never committed. See .github/workflows/build.yml.
            val store = System.getenv("DOT_SIGNING_STORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("DOT_SIGNING_PASSWORD")
                keyAlias = "dot"
                keyPassword = System.getenv("DOT_SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("DOT_SIGNING_STORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    androidResources {
        // The CLIP models are memory-mapped from the APK, which requires them to be stored uncompressed.
        noCompress.add("onnx")
    }

    packaging {
        jniLibs {
            // Store native libraries (ONNX Runtime, ML Kit's text reader) compressed: a much smaller
            // APK to download, extracted once at install.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Personal sideloaded build: don't let release lint block the APK.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:media"))
    implementation(project(":core:ml"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.telephoto.zoomable.coil)
}
