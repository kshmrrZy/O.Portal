// Корневой build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}