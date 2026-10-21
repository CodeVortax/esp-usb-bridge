plugins {
    id("com.android.application")
}

android {
    namespace = "com.codevortax.espusbbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codevortax.espusbbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-beta"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // The interop test starts python3 with pySerial; it runs from the app/ folder.
            it.workingDir = projectDir
        }
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
}
