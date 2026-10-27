// FirmarPDF extension to the v1.0.35 bridge. Included after DocumentFile and JNI helpers.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <android/api-level.h>
#include "include/fpdf_signature.h"

namespace {
class LockedSignaturePixels {
    JNIEnv* env;
    jobject bitmap;
    bool locked = false;
public:
    void* pixels = nullptr;
    LockedSignaturePixels(JNIEnv* env, jobject bitmap) : env(env), bitmap(bitmap) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock signature bitmap");
        locked = true;
        if (!pixels) {
            unlock();
            throw std::runtime_error("Missing signature pixels");
        }
    }
    void unlock() {
        if (!locked) return;
        locked = false;
        pixels = nullptr;
        if (AndroidBitmap_unlockPixels(env, bitmap) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot unlock signature bitmap");
    }
    ~LockedSignaturePixels() {
        if (locked) AndroidBitmap_unlockPixels(env, bitmap);
    }
};

struct SignatureBitmap {
    FPDF_BITMAP value;
    explicit SignatureBitmap(FPDF_BITMAP value) : value(value) {}
    ~SignatureBitmap() { if (value) FPDFBitmap_Destroy(value); }
};

struct SignatureImage {
    FPDF_PAGEOBJECT value;
    explicit SignatureImage(FPDF_PAGEOBJECT value) : value(value) {}
    ~SignatureImage() { if (value) FPDFPageObj_Destroy(value); }
};

// Android RGBA_8888 pixels are R,G,B,A bytes (not integer ARGB ordering).
// PDFium BGRA requires straight channels, even when Android uses premultiplied alpha.
uint8_t straightSignatureChannel(uint8_t channel, uint8_t alpha, bool premultiplied) {
    if (!alpha) return 0;
    if (!premultiplied || alpha == 255) return channel;
    return static_cast<uint8_t>(std::min(255u, (unsigned(channel) * 255u + alpha / 2u) / alpha));
}

void signatureException(JNIEnv* env, const char* type, const char* message) {
    // Preserve pending Android/JNI exceptions instead of replacing them.
    if (!env->ExceptionCheck()) jniThrowException(env, type, message);
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_legere_pdfiumandroid_PdfPage_nativeInsertImage(
    JNIEnv* env, jobject, jlong doc_ptr, jlong page_ptr, jobject bitmap,
    jdouble a, jdouble b, jdouble c, jdouble d, jdouble e, jdouble f, jint declaredAlphaMode) {
    try {
        auto* file = reinterpret_cast<DocumentFile*>(doc_ptr);
        auto page = reinterpret_cast<FPDF_PAGE>(page_ptr);
        if (!file || !file->pdfDocument || !page || !bitmap)
            throw std::invalid_argument("Invalid image insertion handles");
        const double determinant = a * d - b * c;
        if (!std::isfinite(a) || !std::isfinite(b) || !std::isfinite(c) || !std::isfinite(d) ||
            !std::isfinite(e) || !std::isfinite(f) || !std::isfinite(determinant) || determinant == 0)
            throw std::invalid_argument("Invalid image matrix");

        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::invalid_argument("Cannot inspect signature bitmap");
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) || !info.width || !info.height ||
            info.width > uint32_t(INT32_MAX / 4) || info.height > uint32_t(INT32_MAX) ||
            info.stride < uint64_t(info.width) * 4 ||
            uint64_t(info.stride) * info.height > std::numeric_limits<size_t>::max())
            throw std::invalid_argument("Software RGBA_8888 bitmap with valid stride required");
        // flags was reserved before API 30. Kotlin supplies Bitmap.hasAlpha/isPremultiplied.
        const uint32_t alphaMode = static_cast<uint32_t>(declaredAlphaMode);
        if (alphaMode != ANDROID_BITMAP_FLAGS_ALPHA_PREMUL &&
            alphaMode != ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL && alphaMode != ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE)
            throw std::invalid_argument("Unsupported signature alpha mode");
        if (android_get_device_api_level() >= 30 && (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) != alphaMode)
            throw std::invalid_argument("Inconsistent signature alpha mode");

        // PDFium owns this buffer. Android memory is read only while locked.
        SignatureBitmap nativeBitmap(FPDFBitmap_CreateEx(int(info.width), int(info.height), FPDFBitmap_BGRA, nullptr, 0));
        if (!nativeBitmap.value) throw std::bad_alloc();
        const int nativeStride = FPDFBitmap_GetStride(nativeBitmap.value);
        auto* target = static_cast<uint8_t*>(FPDFBitmap_GetBuffer(nativeBitmap.value));
        if (!target || nativeStride < int(info.width * 4) ||
            uint64_t(nativeStride) * info.height > std::numeric_limits<size_t>::max())
            throw std::runtime_error("Invalid PDFium bitmap buffer");
        {
            LockedSignaturePixels locked(env, bitmap);
            for (uint32_t y = 0; y < info.height; ++y) {
                const auto* input = static_cast<const uint8_t*>(locked.pixels) + size_t(y) * info.stride;
                auto* output = target + size_t(y) * nativeStride;
                for (uint32_t x = 0; x < info.width; ++x, input += 4, output += 4) {
                    const uint8_t alpha = alphaMode == ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE ? 255 : input[3];
                    const bool premultiplied = alphaMode == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
                    output[0] = straightSignatureChannel(input[2], alpha, premultiplied); // B
                    output[1] = straightSignatureChannel(input[1], alpha, premultiplied); // G
                    output[2] = straightSignatureChannel(input[0], alpha, premultiplied); // R
                    output[3] = alpha;
                }
            }
            locked.unlock();
        }

        SignatureImage image(FPDFPageObj_NewImageObj(file->pdfDocument));
        if (!image.value) throw std::runtime_error("Cannot create signature image object");
        // SetBitmap encodes the bitmap into image/mask streams; no Android buffer escapes.
        if (!FPDFImageObj_SetBitmap(nullptr, 0, image.value, nativeBitmap.value))
            throw std::runtime_error("Cannot set signature image bitmap");
        if (!FPDFImageObj_SetMatrix(image.value, a, b, c, d, e, f))
            throw std::runtime_error("Cannot set signature image matrix");
        const int before = FPDFPage_CountObjects(page);
        if (before < 0) throw std::runtime_error("Cannot inspect page objects");
        const auto inserted = image.value;
        // Engine chromium/8111: boolean result; takes ownership and frees the object on failure.
        const auto insertedSuccessfully = FPDFPage_InsertObject(page, inserted);
        image.value = nullptr; // Never destroy again: owned by the page on success, freed on failure.
        if (!insertedSuccessfully) throw std::runtime_error("Signature image insertion failed");
        if (FPDFPage_CountObjects(page) != before + 1 || FPDFPage_GetObject(page, before) != inserted)
            throw std::runtime_error("Signature image insertion failed");
    } catch (const std::bad_alloc&) {
        signatureException(env, "java/lang/OutOfMemoryError", "Signature allocation failed");
    } catch (const std::invalid_argument&) {
        signatureException(env, "java/lang/IllegalArgumentException", "Unsupported signature bitmap or matrix");
    } catch (...) {
        signatureException(env, "java/lang/IllegalStateException", "Signature image insertion failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_legere_pdfiumandroid_PdfPage_nativeGenerateContent(JNIEnv* env, jobject, jlong page_ptr) {
    auto page = reinterpret_cast<FPDF_PAGE>(page_ptr);
    if (!page || !FPDFPage_GenerateContent(page))
        signatureException(env, "java/lang/IllegalStateException", "Cannot generate page content");
}

extern "C" JNIEXPORT jint JNICALL
Java_io_legere_pdfiumandroid_PdfDocument_nativeGetSignatureExportRestriction(JNIEnv* env, jobject, jlong doc_ptr) {
    auto* file = reinterpret_cast<DocumentFile*>(doc_ptr);
    if (!file || !file->pdfDocument) {
        signatureException(env, "java/lang/IllegalStateException", "Invalid export document");
        return -1;
    }
    const int count = FPDF_GetSignatureCount(file->pdfDocument);
    if (count < 0) {
        signatureException(env, "java/lang/IllegalStateException", "Cannot inspect document signatures");
        return -1;
    }
    if (count > 0) return 2;
    if (FPDF_GetSecurityHandlerRevision(file->pdfDocument) >= 0 ||
        (FPDF_GetDocPermissions(file->pdfDocument) & 8u) == 0) return 1;
    return 0;
}
