plugins {
    id("com.android.application")
}

android {
    namespace = "com.hoons1994.iphoneduoanimation"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hoons1994.iphoneduoanimation"
        minSdk = 33
        targetSdk = 37
        versionCode = 12
        versionName = "0.12.0-projection"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
