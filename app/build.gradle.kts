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

val relayDir = layout.buildDirectory.dir("generated/relay")

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
    // Builds the remote relay (src/main/native); see the relay tasks below.
    ndkVersion = "30.0.16248370"

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
        // The relay is a program, not a library: Android only runs it from the unpacked copy.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].jniLibs.directories.add(relayDir.get().asFile.path)
}

/**
 * The remote relay is one C file run as the ADB shell user, so it is compiled straight with the
 * NDK's clang, once per ABI, and packaged as `librelay.so` for Android to unpack beside the app.
 */
val relayAbis = mapOf(
    "armeabi-v7a" to "armv7a-linux-androideabi28",
    "arm64-v8a" to "aarch64-linux-android28",
)
val relayTasks = relayAbis.map { (abi, target) ->
    tasks.register<Exec>("relay-$abi") {
        val source = file("src/main/native/relay.c")
        val out = relayDir.map { it.file("$abi/librelay.so") }
        inputs.file(source)
        outputs.file(out)
        doFirst { out.get().asFile.parentFile.mkdirs() }
        val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        val ndk = androidComponents.sdkComponents.ndkDirectory
        doFirst { executable = ndk.get().file("toolchains/llvm/prebuilt/$host/bin/clang").asFile.path }
        args("--target=$target", "-Os", "-Wall", "-pie", "-fPIE", "-s", "-o", out.get().asFile.path, source.path)
    }
}
tasks.named("preBuild") { dependsOn(relayTasks) }

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
