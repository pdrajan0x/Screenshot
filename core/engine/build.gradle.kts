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
    // Android has org.json built in; only compiled against here (picture keyword list parsing).
    compileOnly(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    // Shared with the Android modules: the BPE vocabulary ships as an app asset.
    systemProperty("clip.vocab", file("../ml/src/main/assets/clip/bpe_simple_vocab_16e6.txt.gz").absolutePath)
    systemProperty("clip.pictureWords", file("../ml/src/main/assets/clip/picture_words.json").absolutePath)
    // Produced by tools/model/export_mobileclip.py. Model parity tests are skipped when absent.
    systemProperty("clip.modelOut", System.getenv("CLIP_MODEL_OUT") ?: file("../../model-out").absolutePath)
}
