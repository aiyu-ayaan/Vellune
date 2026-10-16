import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.vellune"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.vellune"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.ink.brush.ExperimentalInkCustomBrushApi",
        )
    }
}

// The Yjs core (crates/mobile) is Rust; cargo-ndk builds it into jniLibs before every build.
val rustRoot = rootProject.file("../..")
val jniOut = layout.projectDirectory.dir("src/main/jniLibs")
val sdkDir = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
    .getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME").orEmpty()
val cargoNdk by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds crates/mobile for Android ABIs with cargo-ndk"
    workingDir = rustRoot
    environment("ANDROID_HOME", sdkDir)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-P", "26",
        "-o", jniOut.asFile.absolutePath, "build", "--release", "-p", "vellune-mobile",
    )
    inputs.dir(rustRoot.resolve("crates/mobile/src"))
    inputs.file(rustRoot.resolve("Cargo.lock"))
    outputs.dir(jniOut)
}
tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.ink.strokes)
    implementation(libs.input.motionprediction)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
