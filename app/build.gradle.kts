plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ccdesk.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ccdesk.nativeapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.0-native"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
configurations.configureEach { exclude(group = "org.jetbrains", module = "annotations-java5") }

apply(from = rootProject.file("gradle/native-boundary.gradle.kts"))

val verifyBundledLicenses = tasks.register("verifyBundledLicenses") {
    group = "verification"
    description = "Keep the offline license and attribution documents in sync with the repository."
    val copies = mapOf("LICENSE" to "app_license.txt", "NOTICE" to "app_notice.txt")
    inputs.files(copies.keys.map { rootProject.file(it) })
    inputs.files(copies.values.map { file("src/main/res/raw/$it") })
    doLast {
        copies.forEach { (source, bundled) ->
            fun normalized(text: String) = text.replace("\r\n", "\n").trimEnd()
            check(normalized(rootProject.file(source).readText()) == normalized(file("src/main/res/raw/$bundled").readText())) {
                "$source and the offline $bundled document differ; update both before packaging."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledLicenses) }

tasks.named("verifyNativeBoundary") {
    doLast {
        val nativeRoot = rootProject.projectDir.canonicalFile.toPath()
        android.sourceSets.filter { !it.name.contains("test", ignoreCase = true) }.forEach { sourceSet ->
            val roots = sourceSet.java.srcDirs + sourceSet.res.srcDirs + sourceSet.assets.srcDirs + sourceSet.jniLibs.srcDirs + sourceSet.manifest.srcFile
            roots.forEach { source ->
                check(source.canonicalFile.toPath().startsWith(nativeRoot)) { "Production source set ${sourceSet.name} escapes the standalone native project: $source" }
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.compose.animation:animation:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    annotationProcessor("io.noties:prism4j-bundler:2.0.0")
    implementation("io.noties.markwon:image-coil:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
