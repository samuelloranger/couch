buildscript {
    dependencies {
        // AGP's built-in Kotlin defaults to an older Kotlin Gradle plugin; this selects a newer one.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}
