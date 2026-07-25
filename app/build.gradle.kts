plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "me.treexhd.supertunnel"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "me.treexhd.supertunnel"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Ship only the English (en_US fallback) resources.
        androidResources.localeFilters += listOf("en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += listOf("-DANDROID") } }
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("../native-tun2socks/CMakeLists.txt"); version = "3.22.1" } }

    // Slipstream is an executable native client (not merely a dlopen library).
    // Android must extract it from the APK so ProcessBuilder can execute it.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets { getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/slipstream-jni").get().asFile) }
}

// Built from third_party/slipstream-rust (Apache-2.0) for arm64 Android.
val syncSlipstreamBinary = tasks.register<Copy>("syncSlipstreamBinary") {
    from("../third_party/slipstream-rust/target/aarch64-linux-android/release/slipstream-client")
    into(layout.buildDirectory.dir("generated/slipstream-jni/arm64-v8a"))
    rename { "libslipstream_client.so" }
}
tasks.named("preBuild").configure { dependsOn(syncSlipstreamBinary) }

kotlin { jvmToolchain(17) }

// sshlib 2.2.48 uses a 30 KiB SSH channel window.  That is a hard throughput
// ceiling on higher-latency paths, even when the TCP/TUN stack has capacity.
// Repackage the pinned BSD-licensed artifact with only Channel.class replaced
// by our source-compatible 1 MiB-window implementation.
val upstreamSshlib = configurations.detachedConfiguration(
    dependencies.create("org.connectbot:sshlib:2.2.48")
).apply { isTransitive = false }
val compileFastSshlibChannel = tasks.register<JavaCompile>("compileFastSshlibChannel") {
    source = fileTree("../third_party/sshlib-fast") { include("**/*.java") }
    classpath = files(upstreamSshlib)
    destinationDirectory.set(layout.buildDirectory.dir("generated/sshlib-fast/classes"))
    options.release.set(8)
}
val fastSshlibJar = tasks.register<Jar>("fastSshlibJar") {
    dependsOn(compileFastSshlibChannel)
    archiveBaseName.set("sshlib-fast")
    destinationDirectory.set(layout.buildDirectory.dir("generated/sshlib-fast"))
    from({ zipTree(upstreamSshlib.singleFile) }) {
        exclude("com/trilead/ssh2/channel/Channel.class")
    }
    from(compileFastSshlibChannel)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    // Pinned ConnectBot SSH engine (BSD-3-Clause); see THIRD_PARTY_NOTICES.md.
    implementation(files(fastSshlibJar))
    // Keep sshlib's published runtime dependencies when replacing its JAR.
    implementation("org.connectbot:simplesocks:1.0.1")
    implementation("com.google.crypto.tink:tink:1.21.0")
    implementation("org.connectbot:jbcrypt:1.0.2")
    implementation("asia.hombre:kyber:2.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
