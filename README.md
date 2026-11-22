# AI Enhancer (Android) — Photo upscaler + AI face restoration

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

> "Look" sirf filter hai (nayi detail nahi banata). Asli chehra restore **GFPGAN** karta hai, neeche dekhein.

## AI Face Restoration (GFPGAN v1.4, on-device)

Pipeline: photo (full res) -> **ML Kit** face detect + landmarks -> 4-point FFHQ alignment -> 512x512 crop ->
**GFPGAN v1.4** (ONNX Runtime) -> low-frequency colour match (tone/lighting original jaisi) -> soft elliptical paste-back
**Real-ESRGAN upscaled** image mein. Background, kapray, composition ESRGAN se aate hain; sirf chehre ka hissa GFPGAN se.

| Cheez | Detail |
|---|---|
| Modes | AI Face Enhance, HD Photo, Old Photo Restore, Blur Fix, 4x AI Upscale (har mode scale/face/sliders ke defaults set karta hai) |
| Face fidelity slider | Look tab mein, live. Kam = zyada restore, zyada = original ke qareeb (identity bachane ke liye) |
| Enhancement slider | Pehle wala "AI strength" (ESRGAN result vs original mix) |
| Compare | Before/After slider; Full screen mein pinch-zoom + pan |
| Save / Share | JPEG (quality 100) ya PNG; EXIF (date, camera, GPS) JPEG mein copy hota hai; Share sheet |
| Photo chunna | Android Photo Picker (koi storage permission nahi) |
| Privacy | Photo kabhi upload nahi hoti. INTERNET permission sirf face model download ke liye (user ki ijazat ke baad) |

### Face model ek dafa publish karein (zaroori)

Face model APK mein nahi (170 MB). Wo is repo ke release `models-v1` se download hota hai.
1. Ye branch `main` mein merge karein.
2. GitHub -> **Actions** -> **Publish face model** -> **Run workflow**. (GFPGAN v1.4 ONNX download karta hai, weights ko fp16 mein
   store kar ke file aadhi karta hai, ONNX Runtime mein sanity-test karta hai, phir release par upload karta hai.)
3. App mein "Enhance" dabane par model download karne ki ijazat poochi jati hai. `FaceModel.kt` mein `URL_STR` aapke repo ka hai.

### RAM / speed (honest)

- Chalte waqt GFPGAN ~1-1.5 GB RAM leta hai (ek dafa ONNX Runtime ne model load kiya to). App **4 GB+ RAM** par face AI allow karti hai, kam RAM par sirf ESRGAN chalta hai.
- Har chehre par x86 CPU (4 threads) par ~2.5 sec lagte hain. **Phone par ye 5-20x tak slow ho sakta hai** (maine real phone par measure nahi kiya, aap test kar ke dekhein).
- Face stage CPU par chalta hai. 8 chehre tak restore hote hain, 24 px se chhote chehre skip.
- Cancel dabane par chal rahi inference bhi ruk jati hai. Kaam ke baad model RAM se hata diya jata hai.

### Limits

- GFPGAN **detail banata** hai (aankhein, daant, baal). Identity bachane ke liye Face fidelity slider barhayein.
- Chehre ka restore 512 px par hota hai, bohat bare chehre (1500 px+) par wo ESRGAN output se kam sharp ho sakta hai.
- Old Photo mode sirf restore + colour/contrast hai: scratches hatana ya black&white ko colourize karna is mein **nahi** hai.
- Ek hi nazar aata hua (frontal) chehra best chalta hai; bohat tirchhe/dhake hue chehre ML Kit pakar nahi pata.

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

- [GFPGAN](https://github.com/TencentARC/GFPGAN) (TencentARC) — Apache-2.0 (v1.4 weights, ONNX export facefusion-assets se)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT
- [Google ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) — ML Kit Terms of Service
- **CodeFormer jaan-boojh kar use nahi kiya**: uska license (S-Lab) non-commercial hai, aur app mein ads hain.

- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) & [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan) — BSD-3-Clause
- [ncnn](https://github.com/Tencent/ncnn) (Tencent) — BSD-3-Clause

App ke About / Play listing mein in ka credit zaroor dein.
