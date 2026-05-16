plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.cloudphone.hide.frida.maps"
    compileSdk = 34

    defaultConfig {
        minSdk = 28 // Android 9+ (ReDroid 12 target is API 31)
        targetSdk = 34

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-28"
                )
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        jniLibs {
            // Keep native libs uncompressed for direct loading by Zygisk/Vector
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Xposed API — provided at runtime by Vector/LSPosed framework
    compileOnly("de.robv.android.xposed:api:82") {
        isChanging = false
    }
    compileOnly("de.robv.android.xposed:api:82:sources") {
        isChanging = false
    }
}

repositories {
    maven { url = uri("https://api.xposed.info/") }
    google()
    mavenCentral()
}
