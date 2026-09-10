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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
