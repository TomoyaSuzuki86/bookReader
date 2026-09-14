plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.tomoya.bookreader"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.tomoya.bookreader"
    minSdk = 34
    targetSdk = 34
    versionCode = 2
    versionName = "0.2.0"
    val serverUrl = providers.gradleProperty("BOOK_SERVER_URL").orElse("https://bookreader.invalid").get()
    buildConfigField("String", "BOOK_SERVER_URL", "\"$serverUrl\"")
  }

  packaging { resources.excludes.add("META-INF/LICENSE") }
  lint { abortOnError = false; checkReleaseBuilds = false }
  buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)
  debugImplementation(libs.androidx.ui.tooling)

  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.isdk)
}

spatial { allowUsageDataCollection.set(false) }
