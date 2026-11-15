plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
