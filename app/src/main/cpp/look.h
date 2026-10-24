#pragma once
// "Look" layer: AI result ko natural banane ke liye (cartoon/wax look kam karna) aur portrait style dene ke liye.
// Pure C++ (JNI se azaad), is liye PC par bhi test ho sakta hai.

#include <cstddef>
#include <cstdint>

struct LookParams {
    int strength = 100;  // 0..100: AI result kitna (baqi original se mix)
    int smooth = 0;      // 0..100: skin smoothing (sirf skin-tone par, edges bacha kar)
    int vivid = 0;       // 0..100: vibrance + halka contrast curve
    int clarity = 0;     // 0..100: local contrast (skin par kam)
};

// enh : AI se bara hua image (RGBA8888, w x h)
// orig: asli (chhota) image (RGBA8888, ow x oh) ya nullptr. Bilinear sample hota hai, alag upscale bitmap nahi banta.
// out : w x h RGBA8888. out == enh bhi chalega (in-place), kyunke har pixel sirf apni jagah se parha jata hai.
// Return false agar arguments ghalat hon.
bool applyLook(const uint8_t* enh, size_t enhStride, int w, int h,
               const uint8_t* orig, size_t origStride, int ow, int oh,
               uint8_t* out, size_t outStride, const LookParams& p);
