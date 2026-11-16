// AGP 9 compiles Kotlin itself; the root build pins the Kotlin Gradle plugin version.
plugins {
    id("com.android.application")
}
android {
    namespace = "tw.skyhong.pocketlog"
    compileSdk = 36
    defaultConfig {
        applicationId = "tw.skyhong.pocketlog"
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "tw.skyhong.pocketlog.UiSnapshotInstrumentation"
        versionCode = 36
        versionName = "0.4.31-dev"
    }
    // AGP 9 disables resValue by default; the sandbox build relies on it for its app name.
    buildFeatures { resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
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
