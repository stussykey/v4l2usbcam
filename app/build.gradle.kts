plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.v4l2usbcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.v4l2usbcam"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 指定 NDK 支援的架構，確保能產出 .so
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // CMake 選項（可加入 cppFlags）
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
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

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "version"
    productFlavors {
        create("original") {
            dimension = "version"
            applicationId = "com.example.v4l2usbcam"
            resValue("string", "app_name", "SoundBar 4K")
            versionNameSuffix = "-original"
            versionName = "1.0.0 (4K)"
            buildConfigField("boolean", "IS_CLONE", "false")

            externalNativeBuild {
                cmake {
                    // 傳遞參數給 C++
                    arguments += "-DIS_CLONE=0"
                }
            }
        }
        create("clone") {
            dimension = "version"
            applicationId = "com.example.v4l2usbcam.clone"
            resValue("string", "app_name", "SoundBar FHD")
            versionNameSuffix = "-clone"
            versionName = "1.0.0 (FHD)"
            buildConfigField("boolean", "IS_CLONE", "true")

            externalNativeBuild {
                cmake {
                    arguments += "-DIS_CLONE=1"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 外部 native build 設定：CMake 的路徑
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // 告知 Gradle 需要 native 多架構輸出
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    // 確保 .so 包進 APK
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
