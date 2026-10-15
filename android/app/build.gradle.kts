plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Separate package for safe device testing when the original signing key is unavailable.
val previewInstall = providers.gradleProperty("qnzPreview").orNull == "true"

android {
    namespace = "dev.qnzapret"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = if (previewInstall) "dev.qnzapret.preview" else "dev.qnzapret"
        manifestPlaceholders["qnzApplicationLabel"] = if (previewInstall) "QNZapret Preview" else "QNZapret"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/ndkLibs"))
            assets.srcDir(file("../../runtime/assets"))
            assets.srcDir(layout.buildDirectory.dir("generated/compactHostlists"))
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    args(
        "NDK_PROJECT_PATH=${layout.buildDirectory.dir("intermediates/ndkBuild").get().asFile.absolutePath}",
        "NDK_LIBS_OUT=${layout.buildDirectory.dir("generated/ndkLibs").get().asFile.absolutePath}",
        "APP_BUILD_SCRIPT=${file("src/main/jni/Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${file("src/main/jni/Application.mk").absolutePath}",
        "REV_ID=873541b",
    )
}

tasks.named("preBuild") {
    dependsOn("runNdkBuild")
}

tasks.register<Exec>("generateHostlistIndexes") {
    val source = file("../../runtime/assets/qnzapret/lists")
    val output = layout.buildDirectory.dir("generated/compactHostlists")
    inputs.dir(source)
    inputs.file(file("../../packaging/android/build_hostlist_indexes.py"))
    outputs.dir(output)
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
    args(file("../../packaging/android/build_hostlist_indexes.py").absolutePath,
        "--source-dir", source.absolutePath, "--output-dir", output.get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn("generateHostlistIndexes") }
