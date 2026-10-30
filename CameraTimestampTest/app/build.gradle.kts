plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.baux.cameratimestamptest"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.baux.cameratimestamptest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "CAMERA-TIMESTAMP-TEST-0.1.0"
        buildConfigField("String", "BUILD_ID", "\"CAMERA-TIMESTAMP-TEST-0.1.0\"")
        buildConfigField("String", "CAMERAX_VERSION", "\"1.4.2\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    testImplementation("junit:junit:4.13.2")
}
