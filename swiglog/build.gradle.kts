plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    `maven-publish`
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar() // 👈 推荐：自动附加 sources.jar
    withJavadocJar() // 👈 推荐：自动附加 javadoc.jar
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// --- Maven Publish 配置 ---
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.github.cdAhmad"
            artifactId = "swagger-interceptor"
            version = "1.0.7.3"

            // ✅ 修正：使用 "java" 而不是 "release"
            from(components["java"])   // ←←← 关键修复！


        }
    }
    repositories {
        // 发布到本地 Maven (~/.m2/repository)
//        mavenLocal()

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
