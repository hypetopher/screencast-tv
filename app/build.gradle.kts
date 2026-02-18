plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screencast.tv"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.screencast.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // TV UI - Leanback
    implementation("androidx.leanback:leanback:1.0.0")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui-leanback:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // HTTP Server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // mDNS for AirPlay discovery
    implementation("org.jmdns:jmdns:3.5.9")

    // Ed25519 for AirPlay pairing (pure Java, works on all Android versions)
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // RFC7748 X25519 implementation (avoids custom Curve25519 math bugs)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // Binary plist parser for RTSP SETUP request handling
    implementation("com.googlecode.plist:dd-plist:1.28")
}
