// Isolated EmbedPDF engine. No handles or symbols are shared with the viewer.
// Public headers are from 5f7608ba4a634c94cd718067d6b678a45ba89088.
#include <jni.h>
#include <android/bitmap.h>
#include <android/api-level.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cerrno>
#include <climits>
#include <limits>
#include <new>
#include <vector>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "epdf_layer.h"
#include "epdf_save.h"
#include "epdf_view.h"
#include "fpdf_edit.h"
#include "fpdf_signature.h"

namespace {
enum Failure { OK=0, SOURCE=1, PROTECTED=2, CERTIFIED=3, INVALID=4, ANNOTATIONS=5, WRITE=6, MEMORY=7 };
enum Metric { OPEN=0, TRANSACTION=1, INSERT=2, COPY=3, SAVE=4, CLOSE=5,
              INPUT_BYTES=6, COPIED_BYTES=7, DELTA_BYTES=8, ERROR=9, IMAGE=10, PAGE_COUNT=11, METRICS=12 };
jlong now() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<jlong>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
struct Timer {
    jlong& accumulated;
    jlong start = now();
    explicit Timer(jlong& target) : accumulated(target) {}
    ~Timer() { accumulated += now() - start; }
};
struct Context {
    JNIEnv* env;
    jobject callback;
    jmethodID active=nullptr, phase=nullptr, bitmap=nullptr, alpha=nullptr, matrix=nullptr, begin=nullptr, write=nullptr;
    jthrowable exception=nullptr;
    jlong metrics[METRICS]{};
    int failure=OK;
    int fd;
    uint64_t length=0;
    bool closing=false;
    Context(JNIEnv* env, jobject callback, int fd) : env(env), callback(callback), fd(fd) {
        jclass cls = env->GetObjectClass(callback);
        if (capture() || !cls) { fail(INVALID); return; }
        active=env->GetMethodID(cls,"checkActive","()V");
        if (!capture()) phase=env->GetMethodID(cls,"phase","(I)V");
        if (!capture()) bitmap=env->GetMethodID(cls,"bitmap","(I)Landroid/graphics/Bitmap;");
        if (!capture()) alpha=env->GetMethodID(cls,"alphaMode","(I)I");
        if (!capture()) matrix=env->GetMethodID(cls,"matrix","(IFFFFI)[D");
        if (!capture()) begin=env->GetMethodID(cls,"beginOutput","()V");
        if (!capture()) write=env->GetMethodID(cls,"writeBlock","([B)V");
        capture();
        env->DeleteLocalRef(cls);
    }
    void fail(int code) { if (failure==OK) failure=code; }
    bool capture() {
        if (env->ExceptionCheck()) {
            jthrowable caught=env->ExceptionOccurred();
            env->ExceptionClear(); // Cleanup and metrics must run with no pending JNI exception.
            if (!exception) exception=caught; else env->DeleteLocalRef(caught);
            fail(INVALID);
        }
        return exception != nullptr;
    }
    bool checkActive() {
        if (failure) return false;
        env->CallVoidMethod(callback,active);
        capture();
        return !failure;
    }
    bool onPhase(int value) {
        if (!checkActive()) return false;
        env->CallVoidMethod(callback,phase,value);
        capture();
        return !failure;
    }
    bool readAt(uint64_t offset, uint8_t* target, uint64_t size) {
        if (!closing && !checkActive()) return false;
        if (offset>length || size>length-offset || offset>static_cast<uint64_t>(std::numeric_limits<off64_t>::max())) {
            fail(SOURCE); return false;
        }
        uint64_t done=0;
        while (done<size) {
            if (!closing && !checkActive()) return false;
            const size_t amount=static_cast<size_t>(std::min<uint64_t>(size-done,static_cast<uint64_t>(SSIZE_MAX)));
            const ssize_t got=pread64(fd,target+done,amount,static_cast<off64_t>(offset+done));
            if (got<0 && errno==EINTR) continue;
            if (got<=0) { fail(SOURCE); return false; }
            done+=static_cast<uint64_t>(got);
        }
        return true;
    }
    bool writeBytes(const void* data, uint64_t size, int counter) {
        const auto* bytes=static_cast<const uint8_t*>(data);
        uint64_t done=0;
        while (done<size) {
            if (!checkActive()) return false;
            const jsize amount=static_cast<jsize>(std::min<uint64_t>(size-done,65536));
            jbyteArray block=env->NewByteArray(amount);
            if (capture() || !block) { fail(MEMORY); return false; }
            env->SetByteArrayRegion(block,0,amount,reinterpret_cast<const jbyte*>(bytes+done));
            if (!capture()) env->CallVoidMethod(callback,write,block);
            capture();
            env->DeleteLocalRef(block);
            if (failure) return false;
            done+=static_cast<uint64_t>(amount);
            metrics[counter]+=amount;
        }
        return true;
    }
};
int getBlock(void* param, unsigned long offset, unsigned char* target, unsigned long size) {
    return static_cast<Context*>(param)->readAt(offset,target,size) ? 1 : 0;
}
struct Writer : FPDF_FILEWRITE {
    Context* context;
    explicit Writer(Context* context) : context(context) {
        version=1;
        WriteBlock=[](FPDF_FILEWRITE* self,const void* data,unsigned long size)->int {
            return static_cast<Writer*>(self)->context->writeBytes(data,size,DELTA_BYTES) ? 1 : 0;
        };
    }
};
// Same RGBA -> straight BGRA conversion as the existing Android extension,
// compiled into THIS engine only; no bitmap handle crosses engine boundaries.
uint8_t straight(uint8_t channel,uint8_t alpha,bool premultiplied) {
    if (!alpha) return 0;
    if (!premultiplied || alpha==255) return channel;
    return static_cast<uint8_t>(std::min(255u,(unsigned(channel)*255u+alpha/2u)/alpha));
}
FPDF_PAGEOBJECT makeImage(Context& c, FPDF_DOCUMENT doc, int index) {
    jobject bitmap=c.env->CallObjectMethod(c.callback,c.bitmap,index);
    c.capture();
    if (c.failure || !bitmap) { if (bitmap) c.env->DeleteLocalRef(bitmap); c.fail(ANNOTATIONS); return nullptr; }
    FPDF_BITMAP native=nullptr;
    FPDF_PAGEOBJECT image=nullptr;
    bool locked=false;
    do {
        const jint alpha=c.env->CallIntMethod(c.callback,c.alpha,index);
        if (c.capture()) break;
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(c.env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || (info.flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE) ||
            !info.width || !info.height || info.width>uint32_t(INT_MAX/4) || info.height>uint32_t(INT_MAX) ||
            info.stride<uint64_t(info.width)*4 || uint64_t(info.stride)*info.height>SIZE_MAX ||
            (alpha!=ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE && alpha!=ANDROID_BITMAP_FLAGS_ALPHA_PREMUL && alpha!=ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL)) {
            c.capture(); c.fail(ANNOTATIONS); break;
        }
        if (android_get_device_api_level()>=30 && (info.flags&ANDROID_BITMAP_FLAGS_ALPHA_MASK)!=static_cast<uint32_t>(alpha)) {
            c.fail(ANNOTATIONS); break;
        }
        native=FPDFBitmap_CreateEx(int(info.width),int(info.height),FPDFBitmap_BGRA,nullptr,0);
        if (!native) { c.fail(MEMORY); break; }
        const int stride=FPDFBitmap_GetStride(native);
        auto* target=static_cast<uint8_t*>(FPDFBitmap_GetBuffer(native));
        if (!target || stride<int(info.width*4) || uint64_t(stride)*info.height>SIZE_MAX) { c.fail(ANNOTATIONS); break; }
        void* pixels=nullptr;
        if (AndroidBitmap_lockPixels(c.env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) { c.capture(); c.fail(ANNOTATIONS); break; }
        locked=true;
        if (!pixels) { c.fail(ANNOTATIONS); break; }
        for (uint32_t y=0;y<info.height;++y) {
            if ((y&63)==0 && !c.checkActive()) break;
            const auto* input=static_cast<const uint8_t*>(pixels)+size_t(y)*info.stride;
            auto* output=target+size_t(y)*stride;
            for (uint32_t x=0;x<info.width;++x,input+=4,output+=4) {
                const uint8_t a=alpha==ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE ? 255 : input[3];
                const bool premultiplied=alpha==ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
                output[0]=straight(input[2],a,premultiplied);
                output[1]=straight(input[1],a,premultiplied);
                output[2]=straight(input[0],a,premultiplied);
                output[3]=a;
            }
        }
        if (AndroidBitmap_unlockPixels(c.env,bitmap)!=ANDROID_BITMAP_RESULT_SUCCESS) c.fail(ANNOTATIONS);
        locked=false;
        c.capture();
        if (c.failure) break;
        image=FPDFPageObj_NewImageObj(doc);
        if (!image || !FPDFImageObj_SetBitmap(nullptr,0,image,native)) { c.fail(ANNOTATIONS); break; }
    } while (false);
    if (locked && AndroidBitmap_unlockPixels(c.env,bitmap)!=ANDROID_BITMAP_RESULT_SUCCESS) c.fail(ANNOTATIONS);
    c.capture();
    if (native) FPDFBitmap_Destroy(native);
    c.env->DeleteLocalRef(bitmap);
    if (c.failure && image) { FPDFPageObj_Destroy(image); image=nullptr; }
    return image;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zonadev_firmarpdf_data_pdf_EmbedPdfNativeBridge_nativeExport(
    JNIEnv* env,jobject,jint fd,jint expectedPages,jintArray pageIndices,jobject callback,jlongArray metrics) {
    if (!callback || !pageIndices || !metrics || env->GetArrayLength(metrics)!=METRICS) return INVALID;
    Context c(env,callback,fd);
    EPDF_BASE_DOCUMENT base=nullptr;
    FPDF_DOCUMENT layer=nullptr;
    FPDF_PAGE page=nullptr;
    FPDF_PAGEOBJECT pendingImage=nullptr;
    bool initialized=false,transaction=false;
    try {
        const jsize count=env->GetArrayLength(pageIndices);
        std::vector<jint> pages(static_cast<size_t>(count));
        env->GetIntArrayRegion(pageIndices,0,count,pages.data());
        c.capture();
        if (count<=0 || expectedPages<=0) c.fail(INVALID);
        for (jsize i=0;i<count;++i) {
            if (pages[i]<0 || pages[i]>=expectedPages || (i>0 && pages[i]<pages[i-1])) c.fail(INVALID);
        }
        FPDF_FILEACCESS access{};
        do {
            if (c.failure || !c.onPhase(0)) break;
            {
                Timer elapsed(c.metrics[OPEN]);
                struct stat64 stat{};
                if (fstat64(fd,&stat)!=0 || stat.st_size<=0 ||
                    static_cast<uint64_t>(stat.st_size)>std::numeric_limits<unsigned long>::max()) { c.fail(SOURCE); break; }
                c.length=static_cast<uint64_t>(stat.st_size);
                c.metrics[INPUT_BYTES]=stat.st_size;
                access.m_FileLen=static_cast<unsigned long>(c.length);
                access.m_Param=&c; access.m_GetBlock=getBlock;
                EPDF_InitThread(); initialized=true;
                base=EPDF_LoadBaseDocument(&access,nullptr);
                if (!base) c.fail(FPDF_GetLastError()==FPDF_ERR_PASSWORD ? PROTECTED : INVALID);
            }
            if (c.failure || !c.onPhase(1)) break;
            {
                Timer elapsed(c.metrics[TRANSACTION]);
                EPDFLayerOpenStatus status=EPDFLayerOpenStatus_kOpenFailed;
                layer=EPDFLayer_OpenLayer(base,nullptr,nullptr,&status);
                if (!layer || status!=EPDFLayerOpenStatus_kSuccess) { c.fail(INVALID); break; }
                c.metrics[PAGE_COUNT]=FPDF_GetPageCount(layer);
                if (c.metrics[PAGE_COUNT]!=expectedPages) { c.fail(INVALID); break; }
                const int signatures=FPDF_GetSignatureCount(layer);
                if (signatures<0) { c.fail(INVALID); break; }
                if (signatures>0) { c.fail(CERTIFIED); break; }
                if (FPDF_GetSecurityHandlerRevision(layer)>=0 || (FPDF_GetDocPermissions(layer)&8u)==0) { c.fail(PROTECTED); break; }
                if (!EPDFLayer_BeginTransaction(layer)) { c.fail(INVALID); break; }
                transaction=true;
            }
            for (jsize i=0;i<count && !c.failure;) {
                if (!c.checkActive()) break;
                const jint index=pages[i];
                FS_RECTF box{};
                int turns=-1;
                {
                    Timer elapsed(c.metrics[INSERT]);
                    page=FPDF_LoadPage(layer,index);
                    if (!page || !FPDF_GetPageBoundingBox(page,&box) || (turns=FPDFPage_GetRotation(page))<0 || turns>3) { c.fail(INVALID); break; }
                }
                while (i<count && pages[i]==index && !c.failure) {
                    {
                        Timer elapsed(c.metrics[IMAGE]);
                        pendingImage=makeImage(c,layer,i);
                        if (!pendingImage) { c.fail(ANNOTATIONS); break; }
                    }
                    {
                        Timer elapsed(c.metrics[INSERT]);
                        auto matrix=static_cast<jdoubleArray>(env->CallObjectMethod(callback,c.matrix,i,box.left,box.bottom,box.right,box.top,turns));
                        c.capture();
                        double values[6]{};
                        if (!c.failure && matrix && env->GetArrayLength(matrix)==6) env->GetDoubleArrayRegion(matrix,0,6,values);
                        else c.fail(ANNOTATIONS);
                        c.capture();
                        if (matrix) env->DeleteLocalRef(matrix);
                        if (c.failure) break;
                        for (double v:values) if (!std::isfinite(v)) c.fail(ANNOTATIONS);
                        const double determinant=values[0]*values[3]-values[1]*values[2];
                        if (!std::isfinite(determinant) || determinant==0) c.fail(ANNOTATIONS);
                        if (c.failure || !FPDFImageObj_SetMatrix(pendingImage,values[0],values[1],values[2],values[3],values[4],values[5])) { c.fail(ANNOTATIONS); break; }
                        const FPDF_BOOL inserted=FPDFPage_InsertObject(page,pendingImage);
                        pendingImage=nullptr; // Public contract: transferred on success, freed on failure.
                        if (!inserted) { c.fail(ANNOTATIONS); break; }
                    }
                    ++i;
                }
                if (c.failure) break;
                {
                    Timer elapsed(c.metrics[INSERT]);
                    if (!c.checkActive() || !FPDFPage_GenerateContent(page)) { c.fail(ANNOTATIONS); break; }
                    FPDF_ClosePage(page); page=nullptr;
                }
            }
            if (c.failure || !c.checkActive()) break;
            {
                Timer elapsed(c.metrics[TRANSACTION]);
                if (!EPDFLayer_CommitTransaction(layer)) { c.fail(INVALID); break; }
                transaction=false;
            }
            if (!c.onPhase(2)) break;
            env->CallVoidMethod(callback,c.begin);
            c.capture();
            if (c.failure) break;
            {
                Timer elapsed(c.metrics[COPY]);
                std::vector<uint8_t> buffer(65536);
                uint64_t offset=0;
                while (offset<c.length && !c.failure) {
                    const size_t amount=static_cast<size_t>(std::min<uint64_t>(buffer.size(),c.length-offset));
                    if (!c.readAt(offset,buffer.data(),amount) || !c.writeBytes(buffer.data(),amount,COPIED_BYTES)) break;
                    offset+=amount;
                }
                if (offset!=c.length) { c.fail(WRITE); break; }
            }
            if (!c.checkActive()) break;
            {
                Timer elapsed(c.metrics[SAVE]);
                Writer output(&c);
                EPDFLayerSaveStatus status=EPDFLayerSaveStatus_kSaveFailed;
                const bool saved=EPDFLayer_SaveDelta(layer,&output,&status);
                if (!saved || status!=EPDFLayerSaveStatus_kSuccess || c.metrics[DELTA_BYTES]<=0) c.fail(WRITE);
            }
        } while (false);
    } catch (const std::bad_alloc&) { c.fail(MEMORY); }
      catch (...) { c.fail(INVALID); }
    c.capture();
    c.closing=true;
    {
        Timer elapsed(c.metrics[CLOSE]);
        if (pendingImage) FPDFPageObj_Destroy(pendingImage);
        if (page) FPDF_ClosePage(page);
        if (transaction && layer && !EPDFLayer_AbortTransaction(layer)) c.fail(INVALID);
        if (layer) FPDF_CloseDocument(layer);
        if (base) EPDF_ReleaseBaseDocument(base);
        if (initialized) EPDF_ShutdownThread();
    }
    c.metrics[ERROR]=c.failure;
    env->SetLongArrayRegion(metrics,0,METRICS,c.metrics);
    if (c.exception) { env->Throw(c.exception); env->DeleteLocalRef(c.exception); }
    return c.failure;
}
