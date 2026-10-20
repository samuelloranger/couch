import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

/**
 * The version comes from the latest `vX.Y.Z` tag, so a release is just a tag. Builds between
 * tags keep the last tag's version; the code is derived from it so it always increases.
 */
val gitTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "v0.1.0" }

val semver = gitTag.removePrefix("v").split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }

/**
 * Release signing, from environment variables (CI) or an env-style file named by the
 * `couch.signing` Gradle property (local). Without either, release builds fall back to the
 * debug key so a fresh checkout still builds.
 */
val signing: Map<String, String> = run {
    val fromEnv = System.getenv("COUCH_KEYSTORE")?.let {
        mapOf(
            "store" to it,
            "storePassword" to System.getenv("COUCH_KEYSTORE_PASSWORD").orEmpty(),
            "alias" to System.getenv("COUCH_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("COUCH_KEY_PASSWORD").orEmpty(),
        )
    }
    fromEnv ?: providers.gradleProperty("couch.signing").orNull?.let { path ->
        val props = Properties().apply { File(path).inputStream().use { load(it) } }
        mapOf(
            "store" to props.getProperty("COUCH_KEYSTORE"),
            "storePassword" to props.getProperty("COUCH_KEYSTORE_PASSWORD"),
            "alias" to props.getProperty("COUCH_KEY_ALIAS"),
            "keyPassword" to props.getProperty("COUCH_KEY_PASSWORD"),
        )
    } ?: emptyMap()
}

android {
    namespace = "cloud.samlo.couch"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.samlo.couch"
        // Fire OS 7 (Android 9) is the oldest target.
        minSdk = 28
        targetSdk = 36
        versionCode = (semver[0] * 10_000 + semver[1] * 100 + semver[2]).coerceAtLeast(1)
        versionName = semver.joinToString(".")
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getValue("store"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("alias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        // Installs beside the release build, so a dev build never replaces the Couch in use.
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // Kotlin's builtins metadata only serves kotlin-reflect, which Couch doesn't use.
    packaging {
        resources {
            excludes += "kotlin/**"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The android.jar used by JVM tests only has stubs for org.json.
    testImplementation("org.json:json:20240303")
}

// Release is the variant that ships, so its unit tests must run too (AGP skips them by default).
androidComponents {
    beforeVariants { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}
