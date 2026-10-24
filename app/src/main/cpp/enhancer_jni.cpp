// Real-ESRGAN (SRVGGNetCompact / realesr-animevideov3) ncnn + Vulkan engine for Android.
// Image ko tiles mein toor kar process karta hai taake phone ki RAM full na ho.
// GPU (Vulkan) fail ho jaye to khud-ba-khud CPU par chala jata hai.

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"
#include "cpu.h"

#include "look.h"

#define LOG_TAG "AIEnhancer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Har tile ke chaaron taraf itni extra border (pixels) li jati hai taake tiles ke jorr (seams) nazar na aayen.
constexpr int kPrepadding = 10;

std::mutex g_mutex;                 // net ko ek waqt mein ek hi thread use kare
std::atomic<bool> g_cancel{false};  // UI se cancel

ncnn::Net* g_net = nullptr;
jobject g_assetRef = nullptr;       // global ref: AAssetManager zinda rakhta hai
AAssetManager* g_mgr = nullptr;
std::string g_model;                // e.g. "realesr-animevideov3-x2"
int g_scale = 0;
bool g_wantGpu = false;
bool g_gpuActive = false;
bool g_gpuInited = false;

bool ensureGpu() {
    if (!g_gpuInited) {
        g_gpuInited = true;
        ncnn::create_gpu_instance();
    }
    return ncnn::get_gpu_count() > 0;
}

void destroyNet() {
    delete g_net;
    g_net = nullptr;
    g_gpuActive = false;
}

bool buildNet(bool wantGpu) {
    destroyNet();
    g_net = new ncnn::Net();

    const bool gpu = wantGpu && ensureGpu();

    g_net->opt.use_vulkan_compute = gpu;
    g_net->opt.use_fp16_packed = true;
    g_net->opt.use_fp16_storage = true;
    g_net->opt.use_fp16_arithmetic = false;  // Real-ESRGAN ke liye fp16 arithmetic se kharab output ho sakta hai
    g_net->opt.lightmode = true;

    const int big = ncnn::get_big_cpu_count();
    ncnn::set_cpu_powersave(big > 0 ? 2 : 0);  // sirf big cores
    int threads = big > 0 ? big : ncnn::get_cpu_count();
    g_net->opt.num_threads = std::max(1, std::min(threads, 4));

    if (gpu) g_net->set_vulkan_device(0);

    const std::string param = g_model + ".param";
    const std::string bin = g_model + ".bin";
    if (g_net->load_param(g_mgr, param.c_str()) < 0) {
        LOGE("load_param failed: %s", param.c_str());
        destroyNet();
        return false;
    }
    if (g_net->load_model(g_mgr, bin.c_str()) < 0) {
        LOGE("load_model failed: %s", bin.c_str());
        destroyNet();
        return false;
    }
    g_gpuActive = gpu;
    LOGI("model %s loaded, backend=%s, threads=%d", g_model.c_str(), gpu ? "GPU(Vulkan)" : "CPU",
         g_net->opt.num_threads);
    return true;
}

inline uint8_t toByte(float f) {
    if (!(f > 0.f)) return 0;  // NaN aur negative dono 0
    if (f >= 1.f) return 255;
    return (uint8_t)(f * 255.f + 0.5f);
}

// Ek tile process karta hai. Return 0 = ok.
// [x0,x1) x [y0,y1) tile ka "core" hai; chaaron taraf kPrepadding extra pixels (edges par replicate).
int runTile(const uint8_t* src, int w, int h, size_t srcStride,
            uint8_t* dst, size_t dstStride,
            int x0, int y0, int x1, int y1, int scale) {
    const int pad = kPrepadding;
    const int iw = (x1 - x0) + 2 * pad;
    const int ih = (y1 - y0) + 2 * pad;

    std::vector<unsigned char> rgb((size_t)iw * ih * 3);
    for (int y = 0; y < ih; ++y) {
        const int sy = std::min(std::max(y0 - pad + y, 0), h - 1);
        const uint8_t* row = src + (size_t)sy * srcStride;
        unsigned char* d = &rgb[(size_t)y * iw * 3];
        for (int x = 0; x < iw; ++x) {
            const int sx = std::min(std::max(x0 - pad + x, 0), w - 1);
            const uint8_t* p = row + (size_t)sx * 4;  // RGBA
            d[0] = p[0];
            d[1] = p[1];
            d[2] = p[2];
            d += 3;
        }
    }

    ncnn::Mat in = ncnn::Mat::from_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB, iw, ih);
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(nullptr, norm);

    ncnn::Mat out;
    {
        ncnn::Extractor ex = g_net->create_extractor();
        if (ex.input("data", in) != 0) return -1;
        if (ex.extract("output", out) != 0) return -2;
    }
    if (out.c != 3 || out.w != iw * scale || out.h != ih * scale) {
        LOGE("unexpected output size %dx%dx%d (expected %dx%d)", out.w, out.h, out.c, iw * scale, ih * scale);
        return -3;
    }

    const int ox = pad * scale;
    const int oy = pad * scale;
    const int cw = (x1 - x0) * scale;
    const int ch = (y1 - y0) * scale;
    const float* pr = out.channel(0);
    const float* pg = out.channel(1);
    const float* pb = out.channel(2);

    for (int y = 0; y < ch; ++y) {
        const size_t o = (size_t)(oy + y) * out.w + ox;
        const float* r = pr + o;
        const float* g = pg + o;
        const float* b = pb + o;
        uint8_t* d = dst + (size_t)(y0 * scale + y) * dstStride + (size_t)x0 * scale * 4;
        for (int x = 0; x < cw; ++x) {
            d[0] = toByte(r[x]);
            d[1] = toByte(g[x]);
            d[2] = toByte(b[x]);
            d[3] = 255;
            d += 4;
        }
    }
    return 0;
}

struct BitmapLock {
    JNIEnv* env;
    jobject bmp;
    void* pixels = nullptr;
    bool locked = false;
    BitmapLock(JNIEnv* e, jobject b) : env(e), bmp(b) {
        locked = AndroidBitmap_lockPixels(env, bmp, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS && pixels;
    }
    ~BitmapLock() {
        if (locked) AndroidBitmap_unlockPixels(env, bmp);
    }
};

}  // namespace

extern "C" {

// Return: 1 = GPU (Vulkan) active, 0 = CPU, -1 = error
JNIEXPORT jint JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_load(JNIEnv* env, jobject /*thiz*/, jobject assetManager,
                                               jstring model, jint scale, jboolean useGpu) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel = false;

    const char* m = env->GetStringUTFChars(model, nullptr);
    std::string name = m ? m : "";
    if (m) env->ReleaseStringUTFChars(model, m);
    if (name.empty() || scale < 1) return -1;

    // Wahi model dobara: reload ki zaroorat nahi (Vulkan shader cache bhi bach jata hai)
    if (g_net && name == g_model && scale == g_scale && (bool)useGpu == g_wantGpu) {
        return g_gpuActive ? 1 : 0;
    }

    if (!g_assetRef) {
        g_assetRef = env->NewGlobalRef(assetManager);
        g_mgr = AAssetManager_fromJava(env, g_assetRef);
    }
    if (!g_mgr) return -1;

    g_model = name;
    g_scale = scale;
    g_wantGpu = useGpu;
    if (!buildNet(useGpu)) {
        g_model.clear();
        return -1;
    }
    return g_gpuActive ? 1 : 0;
}

// Return: 0 = ok, 1 = cancelled, -1 = model load nahi hua, -2 = bitmap masla,
//         -3 = tile fail, -4 = listener exception
JNIEXPORT jint JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_process(JNIEnv* env, jobject /*thiz*/, jobject inBmp,
                                                  jobject outBmp, jint tile, jobject listener) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_net) return -1;

    AndroidBitmapInfo ii, oi;
    if (AndroidBitmap_getInfo(env, inBmp, &ii) != 0 || AndroidBitmap_getInfo(env, outBmp, &oi) != 0) return -2;
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -2;

    const int scale = g_scale;
    const int w = (int)ii.width;
    const int h = (int)ii.height;
    if ((int)oi.width != w * scale || (int)oi.height != h * scale) return -2;

    BitmapLock inLock(env, inBmp);
    BitmapLock outLock(env, outBmp);
    if (!inLock.locked || !outLock.locked) return -2;

    jmethodID onProgress = nullptr;
    if (listener) {
        jclass lc = env->FindClass("com/farhan/aienhancer/ProgressListener");
        if (lc) {
            onProgress = env->GetMethodID(lc, "onProgress", "(II)V");
            env->DeleteLocalRef(lc);
        }
        if (!onProgress) env->ExceptionClear();
    }

    const int T = std::max(32, (int)tile);
    const int tx = (w + T - 1) / T;
    const int ty = (h + T - 1) / T;
    const int total = tx * ty;
    int done = 0;

    const uint8_t* src = (const uint8_t*)inLock.pixels;
    uint8_t* dst = (uint8_t*)outLock.pixels;

    for (int yi = 0; yi < ty; ++yi) {
        for (int xi = 0; xi < tx; ++xi) {
            if (g_cancel.load()) return 1;

            const int x0 = xi * T, x1 = std::min(x0 + T, w);
            const int y0 = yi * T, y1 = std::min(y0 + T, h);

            int r = runTile(src, w, h, ii.stride, dst, oi.stride, x0, y0, x1, y1, scale);
            if (r != 0 && g_gpuActive) {
                LOGE("GPU tile failed (%d) -> CPU par switch", r);
                if (!buildNet(false)) return -3;
                r = runTile(src, w, h, ii.stride, dst, oi.stride, x0, y0, x1, y1, scale);
            }
            if (r != 0) {
                LOGE("tile failed (%d)", r);
                return -3;
            }

            ++done;
            if (onProgress) {
                env->CallVoidMethod(listener, onProgress, (jint)done, (jint)total);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    return -4;
                }
            }
        }
    }
    return 0;
}

// "Look" layer (strength / skin smooth / vivid / clarity). `orig` null ho sakta hai.
// enh aur out alag bitmaps hon, aur same size. Return 0 = ok, -2 = bitmap masla.
JNIEXPORT jint JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_adjust(JNIEnv* env, jobject /*thiz*/, jobject enhBmp, jobject origBmp,
                                                 jobject outBmp, jint strength, jint smooth, jint vivid,
                                                 jint clarity) {
    if (!enhBmp || !outBmp) return -2;
    AndroidBitmapInfo ei, oi, ri;
    if (AndroidBitmap_getInfo(env, enhBmp, &ei) != 0 || AndroidBitmap_getInfo(env, outBmp, &ri) != 0) return -2;
    if (ei.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ri.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -2;
    if (ei.width != ri.width || ei.height != ri.height) return -2;

    bool haveOrig = false;
    if (origBmp) {
        if (AndroidBitmap_getInfo(env, origBmp, &oi) != 0 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -2;
        haveOrig = true;
    }

    BitmapLock enhLock(env, enhBmp);
    BitmapLock outLock(env, outBmp);
    if (!enhLock.locked || !outLock.locked) return -2;
    std::unique_ptr<BitmapLock> origLock;
    if (haveOrig) origLock.reset(new BitmapLock(env, origBmp));
    const bool origOk = origLock && origLock->locked;

    LookParams p;
    p.strength = strength;
    p.smooth = smooth;
    p.vivid = vivid;
    p.clarity = clarity;

    const bool ok = applyLook((const uint8_t*)enhLock.pixels, ei.stride, (int)ei.width, (int)ei.height,
                              origOk ? (const uint8_t*)origLock->pixels : nullptr, origOk ? oi.stride : 0,
                              origOk ? (int)oi.width : 0, origOk ? (int)oi.height : 0,
                              (uint8_t*)outLock.pixels, ri.stride, p);
    return ok ? 0 : -2;
}

JNIEXPORT void JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_cancel(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancel = true;
}

JNIEXPORT jboolean JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_isGpuActive(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_gpuActive ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_farhan_aienhancer_NativeEnhancer_release(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    destroyNet();
    g_model.clear();
    g_scale = 0;
    if (g_assetRef) {
        env->DeleteGlobalRef(g_assetRef);
        g_assetRef = nullptr;
        g_mgr = nullptr;
    }
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    if (g_gpuInited) {
        ncnn::destroy_gpu_instance();
        g_gpuInited = false;
    }
}

}  // extern "C"
