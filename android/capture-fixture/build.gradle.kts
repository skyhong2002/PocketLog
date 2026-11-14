plugins { id("com.android.application") }
android {
    namespace = "org.example.pocketlogfixture"
    compileSdk = 36
    defaultConfig { applicationId = "org.example.pocketlogfixture"; minSdk = 34; targetSdk = 34; versionCode = 1; versionName = "synthetic-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
