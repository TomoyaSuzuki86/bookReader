plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tomoya.rsvpreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tomoya.rsvpreader"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}
