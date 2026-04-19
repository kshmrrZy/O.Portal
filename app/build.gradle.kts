// Модульный build.gradle.kts (папка app)
plugins {
    id("com.android.application")
    id("kotlin-android") // Используем короткое имя, чтобы не было конфликта
}

android {
    namespace = "com.example.o_portal_ott"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.o_portal_ott"
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
}

dependencies {
    // Основные библиотеки Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // LibVLC — заменяет ExoPlayer, включает в себя все кодеки
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // OkHttp для загрузки плейлиста
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RecyclerView и Lifecycle
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation ("com.github.bumptech.glide:glide:4.15.1")
    implementation ("org.apache.commons:commons-compress:1.21") // Для распаковки если нужно

}
