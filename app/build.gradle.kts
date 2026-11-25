plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nexus.visionos"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nexus.visionos"
        minSdk = 29
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}
dependencies {
    implementation(project(":audio-engine"))
    implementation(project(":ai-assistant"))
    implementation(project(":xr-engine"))
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}

// Google's Hand Landmarker model (Apache 2.0) is fetched at build time instead of being committed.
val handModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val fetchHandModel = tasks.register("fetchHandModel") {
    outputs.file(handModel)
    onlyIf { !handModel.exists() }
    doLast {
        handModel.parentFile.mkdirs()
        val partial = File(handModel.path + ".part")
        uri("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task")
            .toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(partial.length() > 1_000_000) { "Hand model download is incomplete" }
        check(partial.renameTo(handModel))
        println("hand_landmarker.task sha256: " + java.security.MessageDigest.getInstance("SHA-256")
            .digest(handModel.readBytes()).joinToString("") { "%02x".format(it) })
    }
}
tasks.named("preBuild") { dependsOn(fetchHandModel) }
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach { dependsOn(fetchHandModel) }
