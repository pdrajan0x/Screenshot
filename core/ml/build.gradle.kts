plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.pdrajan.dot.ml"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:engine"))
    api(project(":core:media"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.devanagari)
}
