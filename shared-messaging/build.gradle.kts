// shared-messaging/build.gradle.kts
plugins {
    kotlin('multiplatform')
    id('org.jetbrains.kotlin.plugin.serialization') version '1.9.0'
}

kotlin {
    android()
    js(IR) {
        browser()
        binaries.executable()
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation('org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3')
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin('test'))
            }
        }
        val jsMain by getting {}
        val androidMain by getting {}
    }
}

android {
    compileSdk = 34
    namespace = 'com.example.sharedmessaging'
    defaultConfig { minSdk = 26 }
}
