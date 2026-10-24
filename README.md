# AI Enhancer (Android) — Offline photo upscaler

Light weight Android app jo **Real-ESRGAN (`realesr-animevideov3`) + ncnn + Vulkan GPU** se phone par hi photo ko
2x / 3x / 4x bara aur saaf karti hai. **Internet nahi chahiye, koi permission nahi** (Android 10+).

- Kotlin + C++ (JNI), sirf `appcompat` + `core-ktx` dependency
- Models sirf ~1.2 MB har ek, APK ka size kam rehta hai
- GPU (Vulkan) na ho ya fail ho jaye to khud CPU par chala jata hai
- Bari photo tiles mein process hoti hai, is liye RAM full nahi hoti
- Photo ko dabaye rakhein = Original, chhor dein = Enhanced (before/after)

## Look tab (portrait style)

Enhance ke baad **Look** tab khulta hai. Preview par sliders live chalte hain, aur Save par full size image par lagte hain.

| Slider | Kaam |
|---|---|
| **AI strength** | AI result kitna rakhna hai (baqi original se mix). Kam = natural, 100 = full AI (chehre par cartoon/wax look aa sakta hai) |
| **Skin smooth** | Sirf skin-tone hisson par smoothing, aankhon/hont/baalon ki edges bacha kar |
| **Vivid colour** | Colour boost (kam saturated colours zyada) + halka contrast curve |
| **Clarity** | Local contrast, skin par kam |

Presets: **Portrait** (55/50/35/25, default), **Natural** (70/20/15/15), **Sharp** (100/0/10/30).
Anime, text, logo ya non-face photos ke liye **Sharp** behtar hai.
Code: `app/src/main/cpp/look.cpp` (pure C++, 16 MP par ~1 sec).

> Ye filter hai, face-restoration AI nahi: nayi detail nahi banata, sirf look behtar karta hai.
> Remini jaisa asli face restore chahiye to GFPGAN jaisa alag model chahiye.

## Chalane ka tareeqa (Android Studio)

1. Android Studio (latest) → **Open** → `AIEnhancer` folder chunein.
2. Pehli dafa Gradle sync mein Gradle, **NDK** aur **CMake** auto-install hone ko kahega: **Install / Accept** kar dein.
   (Agar na kahe: *Settings → Languages & Frameworks → Android SDK → SDK Tools* mein
   **NDK (Side by side)** aur **CMake** tick kar ke Apply.)
3. Phone par **USB debugging** on karein, phone connect karein, **Run ▶**.
   Emulator par test na karein (Vulkan emulator par theek nahi chalta). **Real phone** use karein.

## Project ka structure

| File | Kaam |
|---|---|
| `app/src/main/cpp/enhancer_jni.cpp` | AI engine: model load, tiling, GPU→CPU fallback, cancel |
| `app/src/main/cpp/look.cpp` | Look filter: AI strength, skin smooth, vivid, clarity |
| `app/src/main/cpp/ncnn/` | Prebuilt ncnn 20240820 (Vulkan), arm64-v8a + armeabi-v7a |
| `app/src/main/assets/` | Models: `realesr-animevideov3-x2/x3/x4` (`.param` + `.bin`) |
| `.../MainActivity.kt` | UI, photo chunna, save |
| `.../NativeEnhancer.kt` | Kotlin ↔ C++ bridge |

## Settings jo aap badal sakte hain (`MainActivity.kt` ke neeche `companion object`)

- `TILE_GPU` (128) / `TILE_CPU` (96): phone hang ya crash ho to kam karein (64), tez karna ho to barha lein (192).
- `MAX_OUTPUT_PIXELS` (16 million ≈ 4000×4000): kam RAM wale phone ke liye `9_000_000` karein.

## Release (Play Store) ke liye

*Build → Generate Signed App Bundle / APK → Android App Bundle*. Native libs 16 KB page-size ke liye already
configured hain (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`). `play_store_icon_512.png` Play Console ke liye icon hai.

## Model ke baare mein

`realesr-animevideov3` anime/video frames ke liye train hua hai, lekin chhota aur tez hai, aur normal photos par bhi
achha kaam karta hai. Agar sirf real photos ka best result chahiye to Real-ESRGAN ka ncnn `realesr-general-x4v3` model
`.param/.bin` assets mein rakh kar `MainActivity.kt` mein model ka naam badal dein (blob naam wahi `data` / `output` hain).

## Licenses / Credits

- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) & [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan) — BSD-3-Clause
- [ncnn](https://github.com/Tencent/ncnn) (Tencent) — BSD-3-Clause

App ke About / Play listing mein in ka credit zaroor dein.
