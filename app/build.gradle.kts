import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jt.naicenotes"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jt.naicenotes"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read recipe-scan webhook config from local.properties (gitignored)
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }
        buildConfigField(
            "String",
            "RECIPE_SCAN_URL",
            "\"${localProps.getProperty("RECIPE_SCAN_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "RECIPE_SCAN_SECRET",
            "\"${localProps.getProperty("RECIPE_SCAN_SECRET", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Copy the produced debug APK to the project root as "Naice-Notes.apk" so it's
// easy to find / drag into Slack / AirDrop, instead of buried under
// app/build/outputs/apk/debug/.
// Copy the freshly built debug APK to the project root as Naice-Notes.apk so it's
// easy to find / drag into Slack / AirDrop. Attached to packageDebug because
// that's the task that actually produces the APK (assembleDebug is a lifecycle
// alias and gets marked UP-TO-DATE in incremental builds).
afterEvaluate {
    val copyApk: org.gradle.api.Task.() -> Unit = {
        doLast {
            val src = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
            val dst = rootProject.file("Naice-Notes.apk")
            if (src.exists()) src.copyTo(dst, overwrite = true)
        }
    }
    tasks.findByName("packageDebug")?.apply(copyApk)
    tasks.findByName("installDebug")?.apply(copyApk)
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Widget (Glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Networking + serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Image loading
    implementation(libs.coil.compose)

    // Drag-to-reorder
    implementation(libs.reorderable)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
