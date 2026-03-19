plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.cdahmad.swiggerlog"
    compileSdk =35

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 配置发布变体
    publishing {
        singleVariant("release") {
            withSourcesJar() // 发布源码 B-side
            // withJavadocJar() // 如果需要 JavaDoc
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
// --- Maven Publish 配置 ---
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // 坐标配置
                groupId = "com.github.cdAhmad"
                artifactId = "swagger-interceptor"
                version = "1.0.6"

                // 核心：从 Android release 组件导入
                // KTS 语法：components["release"]
                from(components["release"])


            }
        }

        repositories {
            // 发布到本地 Maven (~/.m2/repository)
            mavenLocal()

            // 如果发布到私有远程仓库
            /*
            maven {
                url = uri("https://your.private.repo/repository/maven-releases/")
                credentials {
                    username = "admin"
                    password = "password"
                }
            }
            */
        }
    }
}