plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gstRoot: String = (findProperty("gstAndroidRoot") as String?)
    ?: System.getenv("GSTREAMER_ROOT_ANDROID")
    ?: throw GradleException(
        "Set GSTREAMER_ROOT_ANDROID, or gstAndroidRoot in gradle.properties, " +
            "to the unpacked GStreamer Android binaries",
    )

android {
    namespace = "com.androidrvt.viewer"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.androidrvt.viewer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGStreamer_JAVA_SRC_DIR=java",
                    "-DGStreamer_ASSETS_DIR=assets",
                    "-DGSTREAMER_ROOT_ANDROID=$gstRoot",
                    "-GNinja",
                )
                targets += "rvtvideo"
            }
        }
    }

    // A fixed debug key lets each cloud build install over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// The native build generates org.freedesktop.gstreamer.GStreamer, which the Kotlin code uses.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching {
        it.name == "compile${variant}Kotlin" || it.name == "compile${variant}JavaWithJavac"
    }.configureEach {
        dependsOn("externalNativeBuild$variant")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
