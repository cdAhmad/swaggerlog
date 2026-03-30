plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.koltin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.github.cdahmad.cop"
    compileSdk=35

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.espresso.core)
    // Specify the Compose BOM with a version definition
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
//    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    // Specify Compose library dependencies without a version definition
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // ..
    // 调试和预览依赖
    debugImplementation("androidx.compose.ui:ui-tooling")
//    debugImplementation("androidx.compose.ui:ui-tooling-preview")
//    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // ..
    // 如果需要 ViewModel 集成
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.activity:activity-compose")
    debugImplementation("com.github.cdAhmad:swagger-interceptor:1.0.7.6")
}