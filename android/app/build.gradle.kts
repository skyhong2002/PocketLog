// AGP 9 compiles Kotlin itself; the root build pins the Kotlin Gradle plugin version.
plugins {
    id("com.android.application")
}
// Release signing comes only from the environment (CI secrets); keys never live in the repo.
val releaseKeystore: String? = System.getenv("POCKETLOG_KEYSTORE")?.takeIf { it.isNotBlank() }
android {
    namespace = "tw.skyhong.pocketlog"
    compileSdk = 36
    defaultConfig {
        applicationId = "tw.skyhong.pocketlog"
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "tw.skyhong.pocketlog.UiSnapshotInstrumentation"
        versionCode = 46
        versionName = "0.4.40"
        // In-app updates read GitHub releases of this repository; only release builds check.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"skyhong2002/PocketLog\"")
        buildConfigField("boolean", "UPDATE_CHECK", "false")
    }
    // AGP 9 disables resValue/BuildConfig by default; sandbox naming and update checks use them.
    buildFeatures { resValues = true; buildConfig = true }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("POCKETLOG_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("POCKETLOG_KEY_ALIAS")
            keyPassword = System.getenv("POCKETLOG_KEY_PASSWORD")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "UPDATE_CHECK", "true")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // A new workstation's debug key must never replace the owner's installation.
        create("sandbox") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-sandbox"
            resValue("string", "app_name", "PocketLog Dev")
            matchingFallbacks += "debug"
        }
    }
}
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
