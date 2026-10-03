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
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    // Shared with the Android modules: the BPE vocabulary ships as an app asset.
    systemProperty("clip.vocab", file("../ml/src/main/assets/clip/bpe_simple_vocab_16e6.txt.gz").absolutePath)
    // Produced by tools/model/export_mobileclip.py. Model parity tests are skipped when absent.
    systemProperty("clip.modelOut", file("../../model-out").absolutePath)
}
