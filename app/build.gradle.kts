plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.thundernotes"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.thundernotes"
        // minSdk 31 = Android 12 — AndroidX Ink low-latency stylus paths require API 29+;
        // 31 ensures we get the S-pen / OnePlus Pen low-latency Surface APIs.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            isMinifyEnabled = false // will flip to true once we have proper R8 keep rules
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true // spec: XML Views (not Compose)
        buildConfig = true
        // aidl = true   // will enable when we add the AIDL bound service for injection
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Generate real JVM default methods for interface methods with bodies.
        // Required so Room's @Transaction default methods on DAO interfaces
        // (e.g. FolderClosureDao.rebuildClosureFor) compile correctly.
        freeCompilerArgs.add("-jvm-default=enable")
        // kotlinx-serialization-protobuf (used for .thunder stroke blobs) is still
        // marked experimental; opt in project-wide instead of annotating each file.
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

ksp {
    // Export the Room schema so future migrations can be written + tested
    // against real JSON schemas instead of guesswork.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ─── AndroidX core ───────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // ─── Lifecycle + ViewModel + LiveData ────────────────────────────────
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.common.java8)

    // ─── Navigation (single-Activity host) ────────────────────────────────
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ─── Room ────────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ─── Coroutines ──────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)

    // ─── Serialization (manifest.json + .thunder JSON parts + stroke proto) ───
    implementation(libs.kotlinx.serialization.json)
    // Stroke blobs use kotlinx-serialization-protobuf (wire-compatible with
    // standard protobuf, pure-Kotlin — no protoc needed). Mirrors Notein's
    // 14-field InkStrokeProto with our own field numbers via @ProtoNumber.
    implementation(libs.kotlinx.serialization.protobuf)

    // ─── Networking (snip API calls: Gemini, GLM, PaddleOCR remote) ──────
    implementation(libs.okhttp)

    // ─── Testing ─────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
