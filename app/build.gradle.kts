plugins {
    id("com.android.application")
}

android {
    namespace = "tw.chehu.testtools"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.chehu.testtools"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.22"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":quicksend"))
}
