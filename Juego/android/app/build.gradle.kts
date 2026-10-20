plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.sesgaro.control_angulos"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.sesgaro.gato"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // phone: la app de siempre. quest: panel 2D para Meta Quest 3 (Horizon OS
    // es Android, así que comparte todo el código; solo cambia el manifiesto,
    // ver src/quest/AndroidManifest.xml). Id distinto para tener ambas.
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            manifestPlaceholders["appLabel"] = "Gato cartesiano"
        }
        create("quest") {
            dimension = "device"
            applicationIdSuffix = ".quest"
            versionNameSuffix = "-quest"
            manifestPlaceholders["appLabel"] = "Gato cartesiano (Quest)"
            // Sin esto R8 rompe el Spatial SDK en release (ver el archivo).
            proguardFiles("proguard-quest.pro")
            // Horizon OS de Quest 3 es Android 12L+; además, sin
            // BLUETOOTH_SCAN/CONNECT de Android 12 no hay BLE allí.
            minSdk = 32
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// Meta Spatial SDK, solo para el Quest: el tablero 3D (GameActivity3D, en
// src/quest) es una actividad inmersiva que convive con el panel 2D.
// No se usa el plugin de Gradle de Spatial: no hay escenas del Spatial
// Editor, todo se arma en código con mallas primitivas.
val metaSpatialSdkVersion = "0.14.0"
dependencies {
    "questImplementation"("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
    "questImplementation"("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
    "questImplementation"("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
    // Interaction SDK: manos virtuales y agarre con la palma de la cabeza.
    "questImplementation"("com.meta.spatial:meta-spatial-sdk-isdk:$metaSpatialSdkVersion")
}
