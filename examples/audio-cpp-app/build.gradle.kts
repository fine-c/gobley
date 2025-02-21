import io.gitlab.trixnity.gradle.RustHost
import io.gitlab.trixnity.gradle.cargo.dsl.android
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("io.gitlab.trixnity.rust.kotlin.multiplatform")
    id("io.gitlab.trixnity.cargo.kotlin.multiplatform")
    id("io.gitlab.trixnity.uniffi.kotlin.multiplatform")
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

cargo {
    builds.android {
        dynamicLibraries.addAll("aaudio", "c++_shared")
    }
}

uniffi {
    bindgenFromPath(rootProject.layout.projectDirectory.dir("bindgen"))
    generateFromLibrary()
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    if (RustHost.Platform.MacOS.isCurrent) {
        arrayOf(
            iosArm64(),
            iosSimulatorArm64(),
            iosX64(),
        ).forEach {
            it.binaries.framework {
                baseName = "AudioCppAppKotlin"
                isStatic = true
                binaryOption("bundleId", "io.gitlab.trixnity.uniffi.examples.audiocppapp.kotlin")
                binaryOption("bundleVersion", "0")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "io.gitlab.trixnity.uniffi.examples.audiocppapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.gitlab.trixnity.uniffi.examples.audiocppapp"
        minSdk = 26
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        ndk.abiFilters.add("arm64-v8a")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
