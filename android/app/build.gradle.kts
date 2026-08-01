plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val nativeAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

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
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "dev.qnzapret"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = 1
        versionName = "0.0.1f"

        ndk {
            abiFilters.addAll(nativeAbis)
        }
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
    )
}

tasks.named("preBuild") {
    dependsOn("runNdkBuild")
}
