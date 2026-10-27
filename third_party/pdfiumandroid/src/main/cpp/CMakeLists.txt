# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.12...4.1.0)
# Declares and names the project.

project("pdfiumandroid")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
        pdfiumandroid

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        pdfiumandroid.cpp)

target_compile_features(pdfiumandroid PRIVATE cxx_std_17)
# NDK r26 no activa por defecto los segmentos ELF de 16 KB.
target_link_options(pdfiumandroid PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

find_library( # Sets the name of the path variable.
        android-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        android)

find_library( # Sets the name of the path variable.
        jnigraphics-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        jnigraphics)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        pdfiumandroid

        libpdfium

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        )


add_library( libpdfium SHARED IMPORTED )

set_target_properties( libpdfium PROPERTIES IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/../../../prebuilt/${ANDROID_ABI}/libpdfium.so)

# Independent static EmbedPDF engine; existing viewer target/binaries stay untouched.
set(EMBEDPDF_ROOT "${PROJECT_SOURCE_DIR}/../../../../embedpdf")
set(EMBEDPDF_LIB "${EMBEDPDF_ROOT}/prebuilt/lib/arm64-v8a")
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${EMBEDPDF_LIB}/libembedpdf_engine.a")
    add_library(embedpdf_signature SHARED "${EMBEDPDF_ROOT}/jni/embedpdf_signature.cpp")
    target_include_directories(embedpdf_signature PRIVATE "${EMBEDPDF_ROOT}/include")
    target_compile_features(embedpdf_signature PRIVATE cxx_std_17)
    target_compile_options(embedpdf_signature PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_link_options(embedpdf_signature PRIVATE
        "-Wl,--version-script=${EMBEDPDF_ROOT}/jni/exports.map"
        "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,--no-undefined"
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
    set_property(TARGET embedpdf_signature APPEND PROPERTY LINK_DEPENDS "${EMBEDPDF_ROOT}/jni/exports.map")
    # Private C++ runtime from the engine's pinned NDK; host stays on NDK r26d.
    target_link_libraries(embedpdf_signature PRIVATE "-Wl,--start-group"
        "${EMBEDPDF_LIB}/libembedpdf_engine.a" "${EMBEDPDF_LIB}/libc++_static.a"
        "${EMBEDPDF_LIB}/libc++abi.a"
        "${EMBEDPDF_LIB}/libclang_rt.builtins-aarch64-android.a" "-Wl,--end-group"
        ${android-lib} ${jnigraphics-lib} ${log-lib})
    if(EXISTS "${EMBEDPDF_LIB}/libunwind.a")
        target_link_libraries(embedpdf_signature PRIVATE "${EMBEDPDF_LIB}/libunwind.a")
    endif()
endif()
