plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

version = libs.versions.pdfium.get() + "-firmarpdf1"

android {
    namespace = "io.legere.pdfiumandroid"
    compileSdk = 36
    // NDK instalado. El linker se configura explícitamente para páginas de 16 KB.
    ndkVersion = "26.3.11579264"
    // CMake links the imported library; AGP packages the original binaries explicitly.
    sourceSets.getByName("main").jniLibs.srcDir("prebuilt")
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-Xstring-concat=inline"
    }
}

dependencies {
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(libs.androidx.annotation)
    implementation(libs.guava)
}
