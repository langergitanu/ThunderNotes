plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.thundernotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thundernotes"
        // minSdk 31 = Android 12 — AndroidX Ink low-latency stylus paths require API 29+;
        // 31 ensures we get the S-pen / OnePlus Pen low-latency Surface APIs.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // will flip to true once we have proper R8 keep rules
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        // AGP 8.5 requires JDK 17+. The build host JDK must be 17+.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Generate real JVM 8+ default methods for interface methods with bodies.
        // Required so Room's @Transaction default methods on DAO interfaces
        // (e.g. FolderClosureDao.rebuildClosureFor) compile correctly.
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }

    buildFeatures {
        viewBinding = true   // spec: XML Views (not Compose)
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

    // We don't want flavors yet; once we split into "stable" vs "experimental" or "lite" vs "full"
    // (e.g., base APK vs the 5 GB pack with the heavy VL/formula models), we can add them here.
}

dependencies {
    // ─── AndroidX core ───────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // ─── Lifecycle + ViewModel + LiveData ────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.4")

    // ─── Navigation (single-Activity host) ──────────────────────────────
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // ─── Room ────────────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ─── Coroutines ──────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ─── Serialization (manifest.json + .thunder JSON parts) ─────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // ─── Networking (snip API calls: Gemini, GLM, PaddleOCR remote) ──────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ─── Testing ─────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
