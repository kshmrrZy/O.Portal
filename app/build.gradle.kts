// Модульный build.gradle.kts (папка app)
plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.twnkos.oportal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.twnkos.oportal"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }
}

configurations.configureEach {
    exclude(group = "androidx.annotation", module = "annotation-experimental")
    exclude(group = "androidx.legacy", module = "legacy-support-core-ui")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Optional local Media3 FFmpeg extension AAR.
    // Place built artifact at: app/libs/media3-decoder-ffmpeg.aar
    val localMedia3FfmpegAar = file("libs/media3-decoder-ffmpeg.aar")
    if (localMedia3FfmpegAar.exists()) {
        implementation(files(localMedia3FfmpegAar))
    }
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.github.bumptech.glide:glide:4.15.1")
    implementation("org.apache.commons:commons-compress:1.21")
}
