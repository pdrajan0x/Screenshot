import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android has org.json built in; only compiled against here (word list and model config parsing).
    compileOnly(libs.org.json)
    // FlorenceModel uses the ONNX Runtime API; on Android onnxruntime-android provides it (core:ml).
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    // Shared with the Android modules: these ship as app assets.
    systemProperty("words.picture", file("../ml/src/main/assets/words/picture_words.json").absolutePath)
    systemProperty("florence.assets", file("../ml/src/main/assets/florence").absolutePath)
    // The Florence-2 model files, from tools/model/fetch_florence.py. Model parity tests are skipped when absent.
    systemProperty("model.out", System.getenv("MODEL_OUT") ?: file("../../model-out").absolutePath)
}
