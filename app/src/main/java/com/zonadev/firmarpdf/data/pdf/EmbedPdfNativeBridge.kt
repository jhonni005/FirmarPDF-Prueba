package com.zonadev.firmarpdf.data.pdf

import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import com.zonadev.firmarpdf.BuildConfig

/** No document/page/bitmap native handles ever leave the synchronous JNI operation. */
internal object EmbedPdfNativeBridge {
    const val ENGINE_REVISION = "5f7608ba4a634c94cd718067d6b678a45ba89088"
    val available: Boolean get() = BuildConfig.DEBUG && BuildConfig.EMBEDPDF_ENGINE_PRESENT &&
        Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") &&
        System.getProperty("os.arch").orEmpty() in setOf("aarch64", "arm64")

    private var loaded = false // Accessed only by the exporter's single physical thread.
    fun export(fd: Int, expectedPages: Int, pages: IntArray, callbacks: EmbedPdfNativeCallbacks, metrics: LongArray): Int {
        check(available)
        if (!loaded) { System.loadLibrary("embedpdf_signature"); loaded = true }
        return nativeExport(fd, expectedPages, pages, callbacks, metrics)
    }

    private external fun nativeExport(fd: Int, expectedPages: Int, pages: IntArray,
                                     callbacks: EmbedPdfNativeCallbacks, metrics: LongArray): Int
}

/** Stable JVM method names/signatures consumed by the isolated bridge. All calls are synchronous. */
internal interface EmbedPdfNativeCallbacks {
    fun checkActive()
    fun phase(value: Int)
    fun bitmap(index: Int): Bitmap
    fun alphaMode(index: Int): Int
    fun matrix(index: Int, left: Float, bottom: Float, right: Float, top: Float, turns: Int): DoubleArray
    fun beginOutput()
    fun writeBlock(data: ByteArray)
}
