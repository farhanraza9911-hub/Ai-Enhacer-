#include "look.h"

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

namespace {

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline uint8_t toU8(float v) { return v <= 0.f ? 0 : (v >= 255.f ? 255 : (uint8_t)(v + 0.5f)); }
inline float luma(float r, float g, float b) { return 0.299f * r + 0.587f * g + 0.114f * b; }

// Rows ko 2-4 threads mein baant kar chalata hai. fn(y0, y1) thread-safe hona chahiye.
template <typename F>
void parallelRows(int rows, F&& fn) {
    unsigned hw = std::thread::hardware_concurrency();
    int n = (int)std::min(4u, hw ? hw : 2u);
    if (n <= 1 || rows < 64) {
        fn(0, rows);
        return;
    }
    std::vector<std::thread> threads;
    const int chunk = (rows + n - 1) / n;
    for (int i = 0; i < n; ++i) {
        const int a = i * chunk, b = std::min(rows, a + chunk);
        if (a >= b) break;
        threads.emplace_back([&fn, a, b] { fn(a, b); });
    }
    for (auto& t : threads) t.join();
}

// Bilinear sampling table: n output coords -> source coords (m samples), centres aligned.
struct Axis {
    std::vector<int> i0, i1;
    std::vector<float> f;
};

Axis makeAxis(int n, int m, float scale) {
    Axis a;
    a.i0.resize(n);
    a.i1.resize(n);
    a.f.resize(n);
    for (int i = 0; i < n; ++i) {
        float u = clampf((i + 0.5f) * scale - 0.5f, 0.f, (float)(m - 1));
        const int i0 = (int)u;
        a.i0[i] = i0;
        a.i1[i] = std::min(i0 + 1, m - 1);
        a.f[i] = u - (float)i0;
    }
    return a;
}

void boxBlur(std::vector<float>& img, int w, int h, int r) {
    std::vector<float> tmp(img.size());
    const float inv = 1.f / (float)(2 * r + 1);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                float s = 0.f;
                for (int k = -r; k <= r; ++k) s += img[((size_t)y * w + std::min(std::max(x + k, 0), w - 1)) * 3 + c];
                tmp[((size_t)y * w + x) * 3 + c] = s * inv;
            }
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                float s = 0.f;
                for (int k = -r; k <= r; ++k) s += tmp[((size_t)std::min(std::max(y + k, 0), h - 1) * w + x) * 3 + c];
                img[((size_t)y * w + x) * 3 + c] = s * inv;
            }
        }
    }
}

}  // namespace

bool applyLook(const uint8_t* enh, size_t enhStride, int w, int h,
               const uint8_t* orig, size_t origStride, int ow, int oh,
               uint8_t* out, size_t outStride, const LookParams& p) {
    if (!enh || !out || w <= 0 || h <= 0) return false;

    const float s = clampf(p.strength / 100.f, 0.f, 1.f);
    const float smoothAmt = clampf(p.smooth / 100.f, 0.f, 1.f) * 0.9f;
    const float vivid = clampf(p.vivid / 100.f, 0.f, 1.f);
    const float clarityAmt = clampf(p.clarity / 100.f, 0.f, 1.f) * 0.9f;

    const bool useOrig = orig && ow > 0 && oh > 0 && s < 0.999f;
    Axis ox, oy;
    if (useOrig) {
        ox = makeAxis(w, ow, (float)ow / (float)w);
        oy = makeAxis(h, oh, (float)oh / (float)h);
    }

    // AI result aur original ka mix: base = orig + (enh - orig) * strength
    auto base = [&](int x, int y, float& r, float& g, float& b) {
        const uint8_t* e = enh + (size_t)y * enhStride + (size_t)x * 4;
        if (!useOrig) {
            r = e[0];
            g = e[1];
            b = e[2];
            return;
        }
        const uint8_t* r0 = orig + (size_t)oy.i0[y] * origStride;
        const uint8_t* r1 = orig + (size_t)oy.i1[y] * origStride;
        const int a0 = ox.i0[x] * 4, a1 = ox.i1[x] * 4;
        const float fx = ox.f[x], fy = oy.f[y];
        float v[3];
        for (int c = 0; c < 3; ++c) {
            const float top = r0[a0 + c] * (1.f - fx) + r0[a1 + c] * fx;
            const float bot = r1[a0 + c] * (1.f - fx) + r1[a1 + c] * fx;
            const float o = top * (1.f - fy) + bot * fy;
            v[c] = o + ((float)e[c] - o) * s;
        }
        r = v[0];
        g = v[1];
        b = v[2];
    };

    // Smoothing / clarity ke liye low-frequency (dhundla) version: chhote size par blur, phir bilinear upsample.
    const bool needBlur = smoothAmt > 0.001f || clarityAmt > 0.001f;
    std::vector<float> small;
    int sw = 0, sh = 0, d = 1;
    Axis bx, by;
    if (needBlur) {
        d = std::max(1, std::max(w, h) / 384);
        sw = (w + d - 1) / d;
        sh = (h + d - 1) / d;
        small.assign((size_t)sw * sh * 3, 0.f);
        parallelRows(sh, [&](int a, int b) {
            std::vector<float> acc((size_t)sw * 3);
            std::vector<int> cnt((size_t)sw);
            for (int sy = a; sy < b; ++sy) {
                std::fill(acc.begin(), acc.end(), 0.f);
                std::fill(cnt.begin(), cnt.end(), 0);
                const int y1 = std::min(h, (sy + 1) * d);
                for (int y = sy * d; y < y1; ++y) {
                    for (int x = 0; x < w; ++x) {
                        float r, g, bl;
                        base(x, y, r, g, bl);
                        const int sx = x / d;
                        acc[(size_t)sx * 3 + 0] += r;
                        acc[(size_t)sx * 3 + 1] += g;
                        acc[(size_t)sx * 3 + 2] += bl;
                        cnt[sx]++;
                    }
                }
                for (int sx = 0; sx < sw; ++sx) {
                    const float inv = cnt[sx] ? 1.f / (float)cnt[sx] : 0.f;
                    for (int c = 0; c < 3; ++c) small[((size_t)sy * sw + sx) * 3 + c] = acc[(size_t)sx * 3 + c] * inv;
                }
            }
        });
        boxBlur(small, sw, sh, 2);
        boxBlur(small, sw, sh, 2);
        bx = makeAxis(w, sw, 1.f / (float)d);
        by = makeAxis(h, sh, 1.f / (float)d);
    }

    parallelRows(h, [&](int a, int b) {
        for (int y = a; y < b; ++y) {
            uint8_t* o = out + (size_t)y * outStride;
            for (int x = 0; x < w; ++x, o += 4) {
                float r0, g0, b0;
                base(x, y, r0, g0, b0);
                float r = r0, g = g0, bl = b0;

                if (needBlur) {
                    // low-frequency colour is pixel par
                    const size_t p00 = ((size_t)by.i0[y] * sw + bx.i0[x]) * 3;
                    const size_t p01 = ((size_t)by.i0[y] * sw + bx.i1[x]) * 3;
                    const size_t p10 = ((size_t)by.i1[y] * sw + bx.i0[x]) * 3;
                    const size_t p11 = ((size_t)by.i1[y] * sw + bx.i1[x]) * 3;
                    const float fx = bx.f[x], fy = by.f[y];
                    float lo[3];
                    for (int c = 0; c < 3; ++c) {
                        const float top = small[p00 + c] * (1.f - fx) + small[p01 + c] * fx;
                        const float bot = small[p10 + c] * (1.f - fx) + small[p11 + c] * fx;
                        lo[c] = top * (1.f - fy) + bot * fy;
                    }

                    // skin-tone mask (YCbCr): soft edges
                    const float cb = 128.f - 0.168736f * r0 - 0.331264f * g0 + 0.5f * b0;
                    const float cr = 128.f + 0.5f * r0 - 0.418688f * g0 - 0.081312f * b0;
                    const float cbd = std::max(0.f, std::fabs(cb - 102.f) - 25.f);
                    const float crd = std::max(0.f, std::fabs(cr - 153.f) - 20.f);
                    const float skin = clampf(1.f - (cbd + crd) / 15.f, 0.f, 1.f);

                    // edge-aware: bari tabdeeli (aankh, hont, balon ki line) = edge, wahan smoothing nahi
                    const float diff = std::fabs(luma(r0, g0, b0) - luma(lo[0], lo[1], lo[2]));
                    const float t = diff / 14.f;
                    const float edgeKeep = 1.f / (1.f + t * t);

                    const float k = smoothAmt * skin * edgeKeep;
                    r += (lo[0] - r) * k;
                    g += (lo[1] - g) * k;
                    bl += (lo[2] - bl) * k;

                    // clarity: local contrast, skin par kam
                    const float c = clarityAmt * (1.f - 0.75f * skin);
                    r += c * (r0 - lo[0]);
                    g += c * (g0 - lo[1]);
                    bl += c * (b0 - lo[2]);
                }

                if (vivid > 0.001f) {
                    const float mx = std::max(r, std::max(g, bl));
                    const float mn = std::min(r, std::min(g, bl));
                    const float sat = mx > 1.f ? (mx - mn) / mx : 0.f;
                    const float y2 = luma(r, g, bl);
                    // kam-saturated colours ko zyada boost, skin par aadha
                    float skinK = 0.f;
                    {
                        const float cb = 128.f - 0.168736f * r - 0.331264f * g + 0.5f * bl;
                        const float cr = 128.f + 0.5f * r - 0.418688f * g - 0.081312f * bl;
                        const float cbd = std::max(0.f, std::fabs(cb - 102.f) - 25.f);
                        const float crd = std::max(0.f, std::fabs(cr - 153.f) - 20.f);
                        skinK = clampf(1.f - (cbd + crd) / 15.f, 0.f, 1.f);
                    }
                    const float sf = 1.f + vivid * 0.9f * (1.f - sat) * (1.f - 0.5f * skinK);
                    r = y2 + (r - y2) * sf;
                    g = y2 + (g - y2) * sf;
                    bl = y2 + (bl - y2) * sf;

                    // halka S-curve contrast
                    float* ch[3] = {&r, &g, &bl};
                    for (float* v : ch) {
                        const float tt = clampf(*v / 255.f, 0.f, 1.f);
                        const float curve = tt * tt * (3.f - 2.f * tt);
                        *v = (tt + (curve - tt) * vivid * 0.5f) * 255.f;
                    }
                }

                o[0] = toU8(r);
                o[1] = toU8(g);
                o[2] = toU8(bl);
                o[3] = 255;
            }
        }
    });
    return true;
}
