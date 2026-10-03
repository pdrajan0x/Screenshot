plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.pdrajan.dotgallery"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.pdrajan.dotgallery"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
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
        noCompress.add("onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // llama.cpp (photo descriptions) picks its CPU backend by scanning nativeLibraryDir at
            // runtime, so native libraries must be extracted on install.
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Face model fetched in CI (tools/model/export_faces.py) into model-out-faces/.
val faceAssets = rootProject.file("model-out-faces/assets")
androidComponents {
    onVariants { variant ->
        if (faceAssets.isDirectory) {
            variant.sources.assets?.addStaticSourceDirectory(faceAssets.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:media"))
    implementation(project(":core:ml"))
    implementation(project(":core:engine"))
    implementation(project(":core:llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    // Biometric 1.1.0 pulls Fragment 1.2.5, whose FragmentActivity swallows ActivityResultRegistry results.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.exifinterface)
    implementation(libs.telephoto.zoomable.coil)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.face)
    implementation(libs.mlkit.text.devanagari)
}
