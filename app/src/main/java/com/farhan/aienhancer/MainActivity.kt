package com.farhan.aienhancer

import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private enum class State { IDLE, ENHANCING, SAVING, DOWNLOADING }

    /** "Look" sliders ki values (0..100). */
    private data class Look(val strength: Int, val smooth: Int, val vivid: Int, val clarity: Int) {
        val isNeutral get() = strength >= 100 && smooth == 0 && vivid == 0 && clarity == 0
    }

    /**
     * Enhancement modes. [look] = (enhancement, smooth, vivid, clarity), [fidelity] = face fidelity slider
     * (kam = zyada restore, zyada = original ke qareeb).
     */
    private enum class Mode(val scale: Int, val faces: Boolean, val fidelity: Int, val look: IntArray) {
        FACE(2, true, 35, intArrayOf(70, 25, 20, 15)),       // natural chehra, halka polish
        HD(2, true, 50, intArrayOf(100, 0, 15, 25)),         // poori photo: kapray, background, chehra
        OLD(2, true, 30, intArrayOf(85, 10, 40, 20)),        // faded/dhundli purani photo
        BLUR(2, true, 55, intArrayOf(60, 0, 10, 20)),        // soft/blur, nakli detail kam
        UP4(4, false, 50, intArrayOf(100, 0, 0, 10))         // sirf AI super-resolution
    }

    private val enhancer = NativeEnhancer()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("enhancer", MODE_PRIVATE) }

    private lateinit var preview: CompareView
    private lateinit var modeGroup: RadioGroup
    private lateinit var faceCheck: CheckBox
    private lateinit var seekFidelity: SeekBar
    private lateinit var lblFidelity: TextView
    private lateinit var shareBtn: Button
    private lateinit var fullBtn: Button
    private lateinit var hint: TextView
    private lateinit var badge: TextView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scaleGroup: RadioGroup
    private lateinit var gpuCheck: CheckBox
    private lateinit var pngCheck: CheckBox
    private lateinit var pickBtn: Button
    private lateinit var enhanceBtn: Button
    private lateinit var saveBtn: Button

    private lateinit var tabGroup: RadioGroup
    private lateinit var tabLook: View
    private lateinit var settingsPanel: LinearLayout
    private lateinit var lookPanel: LinearLayout
    private lateinit var presetGroup: RadioGroup
    private lateinit var seekStrength: SeekBar
    private lateinit var seekSmooth: SeekBar
    private lateinit var seekVivid: SeekBar
    private lateinit var seekClarity: SeekBar
    private lateinit var lblStrength: TextView
    private lateinit var lblSmooth: TextView
    private lateinit var lblVivid: TextView
    private lateinit var lblClarity: TextView

    private var state = State.IDLE

    @Volatile
    private var cancelRequested = false

    private var sourceUri: Uri? = null
    private var sourceW = 0
    private var sourceH = 0
    private var originalPreview: Bitmap? = null

    // Enhance ka result (full size) aur uska decoded input (Look ke "AI strength" mix ke liye)
    private var resultBitmap: Bitmap? = null
    private var sourceBitmap: Bitmap? = null

    // Live Look preview (chhote size par): enhanced preview + original (usi size par) + do output buffers
    private var previewEnh: Bitmap? = null
    private var previewOrigUp: Bitmap? = null
    private val lookBufs = arrayOfNulls<Bitmap>(2)
    private var afterBitmap: Bitmap? = null      // compare slider mein "enhanced" taraf
    private val lookVersion = AtomicInteger(0)
    private var workerIdx = 0                    // sirf worker thread par use hota hai

    // Face restoration (GFPGAN) ka result: chehre + preview ke liye composite buffer
    private var faces: List<RestoredFace> = emptyList()
    private var previewFaced: Bitmap? = null     // sirf worker thread par likha jata hai
    private var previewPerSrc = 1.0              // preview.width / input.width

    @Volatile
    private var activeRestorer: FaceRestorer? = null
    private var lastSaved: Uri? = null
    private var lastSavedMime = "image/jpeg"
    private var pendingShare = false

    // Android Photo Picker (permission ki zaroorat nahi)
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onImagePicked(uri)
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) doSave() else toast(R.string.err_permission)
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left + dp(16), bars.top + dp(12), bars.right + dp(16), bars.bottom + dp(12))
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        preview = findViewById(R.id.preview)
        hint = findViewById(R.id.hint)
        badge = findViewById(R.id.badge)
        info = findViewById(R.id.info)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        scaleGroup = findViewById(R.id.scaleGroup)
        gpuCheck = findViewById(R.id.gpuCheck)
        pngCheck = findViewById(R.id.pngCheck)
        pickBtn = findViewById(R.id.pickBtn)
        enhanceBtn = findViewById(R.id.enhanceBtn)
        saveBtn = findViewById(R.id.saveBtn)

        tabGroup = findViewById(R.id.tabGroup)
        tabLook = findViewById(R.id.tabLook)
        settingsPanel = findViewById(R.id.settingsPanel)
        lookPanel = findViewById(R.id.lookPanel)
        presetGroup = findViewById(R.id.presetGroup)
        seekStrength = findViewById(R.id.seekStrength)
        seekSmooth = findViewById(R.id.seekSmooth)
        seekVivid = findViewById(R.id.seekVivid)
        seekClarity = findViewById(R.id.seekClarity)
        lblStrength = findViewById(R.id.lblStrength)
        lblSmooth = findViewById(R.id.lblSmooth)
        lblVivid = findViewById(R.id.lblVivid)
        lblClarity = findViewById(R.id.lblClarity)

        modeGroup = findViewById(R.id.modeGroup)
        faceCheck = findViewById(R.id.faceCheck)
        seekFidelity = findViewById(R.id.seekFidelity)
        lblFidelity = findViewById(R.id.lblFidelity)
        shareBtn = findViewById(R.id.shareBtn)
        fullBtn = findViewById(R.id.fullBtn)
        faceCheck.text = getString(R.string.face_restore, FaceModel.APPROX_MB)

        pickBtn.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        enhanceBtn.setOnClickListener {
            if (state == State.ENHANCING || state == State.DOWNLOADING) {
                cancelRequested = true
                enhancer.cancel()
                activeRestorer?.cancel()
                status.setText(R.string.status_cancelling)
            } else {
                startEnhance()
            }
        }
        saveBtn.setOnClickListener { onSaveClicked(share = false) }
        shareBtn.setOnClickListener { onSaveClicked(share = true) }
        fullBtn.setOnClickListener { showFullscreen() }

        // Modes: scale, face restore, fidelity aur Look sliders ke defaults set karte hain
        modeGroup.setOnCheckedChangeListener { _, id -> applyMode(modeFor(id)) }
        applyMode(Mode.FACE)

        // Tabs: Settings | Look
        tabGroup.setOnCheckedChangeListener { _, id ->
            val look = id == R.id.tabLook
            settingsPanel.visibility = if (look) View.GONE else View.VISIBLE
            lookPanel.visibility = if (look) View.VISIBLE else View.GONE
        }

        // Look presets aur sliders
        presetGroup.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.presetPortrait -> applyPreset(55, 50, 35, 25)
                R.id.presetNatural -> applyPreset(70, 20, 15, 15)
                R.id.presetSharp -> applyPreset(100, 0, 10, 30)
            }
        }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateLookLabels()
                if (fromUser) {
                    if (sb?.id != R.id.seekFidelity) presetGroup.clearCheck()
                    scheduleLook()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekFidelity.setOnSeekBarChangeListener(seekListener)
        seekStrength.setOnSeekBarChangeListener(seekListener)
        seekSmooth.setOnSeekBarChangeListener(seekListener)
        seekVivid.setOnSeekBarChangeListener(seekListener)
        seekClarity.setOnSeekBarChangeListener(seekListener)
        // (default sliders applyMode(FACE) se set hote hain; ye labels dobara taza karta hai)
        updateLookLabels()

        // Pichli dafa GPU (Vulkan) par app crash hui thi? To is dafa CPU se shuru karein.
        if (prefs.getBoolean(KEY_GPU_PENDING, false)) {
            prefs.edit().putBoolean(KEY_GPU_PENDING, false).apply()
            gpuCheck.isChecked = false
            toast(R.string.gpu_crashed)
        }

        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        enhancer.cancel()
        worker.execute { enhancer.release() }
        worker.shutdown()
    }

    // ---------------------------------------------------------------- Look (live preview)

    private fun currentLook() =
        Look(seekStrength.progress, seekSmooth.progress, seekVivid.progress, seekClarity.progress)

    private fun setSliders(strength: Int, smooth: Int, vivid: Int, clarity: Int) {
        seekStrength.progress = strength
        seekSmooth.progress = smooth
        seekVivid.progress = vivid
        seekClarity.progress = clarity
        updateLookLabels()
    }

    private fun applyPreset(strength: Int, smooth: Int, vivid: Int, clarity: Int) {
        setSliders(strength, smooth, vivid, clarity)
        scheduleLook()
    }

    private fun modeFor(id: Int) = when (id) {
        R.id.modeHd -> Mode.HD
        R.id.modeOld -> Mode.OLD
        R.id.modeBlur -> Mode.BLUR
        R.id.modeUp4 -> Mode.UP4
        else -> Mode.FACE
    }

    private fun applyMode(m: Mode) {
        scaleGroup.check(
            when (m.scale) {
                4 -> R.id.scale4
                3 -> R.id.scale3
                else -> R.id.scale2
            }
        )
        faceCheck.isChecked = m.faces
        seekFidelity.progress = m.fidelity
        setSliders(m.look[0], m.look[1], m.look[2], m.look[3])
        presetGroup.clearCheck()
        scheduleLook()
    }

    /** Face fidelity slider -> restored chehre ka hissa (1.0 = poora GFPGAN, 0.15 = original ke bohat qareeb). */
    private fun faceWeight() = 1f - 0.85f * seekFidelity.progress / 100f

    /** Preview par chehre milata hai (worker thread). Faces na hon to [enh] hi wapas. */
    private fun withFaces(enh: Bitmap, faced: Bitmap?, fs: List<RestoredFace>, perSrc: Double, weight: Float): Bitmap {
        if (faced == null || fs.isEmpty() || weight <= 0f) return enh
        Canvas(faced).drawBitmap(enh, 0f, 0f, null)
        FaceBlender.blend(faced, perSrc, fs, weight)
        return faced
    }

    private fun updateLookLabels() {
        lblFidelity.text = getString(R.string.lbl_fidelity, seekFidelity.progress)
        lblStrength.text = getString(R.string.lbl_strength, seekStrength.progress)
        lblSmooth.text = getString(R.string.lbl_smooth, seekSmooth.progress)
        lblVivid.text = getString(R.string.lbl_vivid, seekVivid.progress)
        lblClarity.text = getString(R.string.lbl_clarity, seekClarity.progress)
    }

    /** Slider hilne par chhoti preview par Look dobara lagata hai (sirf aakhri request chalti hai). */
    private fun scheduleLook() {
        val enh = previewEnh ?: return
        val orig = previewOrigUp ?: return
        val look = currentLook()
        val fs = faces
        val faced = previewFaced
        val perSrc = previewPerSrc
        val weight = faceWeight()
        val version = lookVersion.incrementAndGet()
        worker.execute {
            if (version != lookVersion.get()) return@execute
            val idx = workerIdx xor 1
            val buf = lookBufs[idx] ?: return@execute
            workerIdx = idx
            val input = withFaces(enh, faced, fs, perSrc, weight)
            if (enhancer.adjust(input, orig, buf, look.strength, look.smooth, look.vivid, look.clarity) != 0) {
                return@execute
            }
            runOnUiThread {
                if (isDestroyed || version != lookVersion.get() || previewEnh !== enh) return@runOnUiThread
                afterBitmap = buf
                preview.setBitmaps(originalPreview, buf)
            }
        }
    }

    // ---------------------------------------------------------------- pick image

    private fun clearResult() {
        lookVersion.incrementAndGet()  // pending slider tasks bekaar
        resultBitmap?.recycle()
        resultBitmap = null
        sourceBitmap?.recycle()
        sourceBitmap = null
        previewEnh = null
        previewOrigUp = null
        lookBufs[0] = null
        lookBufs[1] = null
        afterBitmap = null
        faces = emptyList()
        previewFaced = null
        lastSaved = null
        tabGroup.check(R.id.tabSettings)
    }

    private fun onImagePicked(uri: Uri) {
        if (state != State.IDLE) return
        status.setText(R.string.status_loading)
        worker.execute {
            val dims = readDimensions(uri)
            val prev = decodeBitmap(uri, PREVIEW_PIXELS)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (dims == null || prev == null) {
                    status.setText(R.string.err_open)
                    return@runOnUiThread
                }
                clearResult()
                sourceUri = uri
                sourceW = dims.first
                sourceH = dims.second
                originalPreview = prev
                preview.setBitmaps(prev, null)
                badge.setText(R.string.badge_original)
                info.text = getString(R.string.info_original, sourceW, sourceH)
                status.setText(R.string.status_idle)
                progress.progress = 0
                updateUi()
            }
        }
    }

    // ---------------------------------------------------------------- enhance

    private fun ramAllowsFaceAi(): Boolean {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem >= MIN_RAM_FOR_FACE_AI
    }

    /** Face model mojood na ho to pehle user se ijazat le kar download karta hai, phir [onReady]. */
    private fun ensureFaceModel(onReady: () -> Unit) {
        if (FaceModel.isReady(this)) {
            onReady()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dl_title)
            .setMessage(getString(R.string.dl_message, FaceModel.APPROX_MB))
            .setPositiveButton(R.string.dl_yes) { _, _ -> downloadModel(onReady) }
            .setNegativeButton(R.string.dl_no) { _, _ ->
                faceCheck.isChecked = false
                startEnhance()
            }
            .show()
    }

    private fun downloadModel(onReady: () -> Unit) {
        state = State.DOWNLOADING
        cancelRequested = false
        progress.progress = 0
        status.text = getString(R.string.status_downloading, 0, FaceModel.APPROX_MB)
        updateUi()
        worker.execute {
            val r = FaceModel.download(applicationContext, { cancelRequested }) { done, total ->
                runOnUiThread {
                    if (isDestroyed || state != State.DOWNLOADING) return@runOnUiThread
                    val t = if (total > 0) total else FaceModel.APPROX_MB * MB
                    progress.progress = (done * 100 / t).toInt().coerceIn(0, 100)
                    status.text = getString(R.string.status_downloading, (done / MB).toInt(), (t / MB).toInt())
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                state = State.IDLE
                progress.progress = 0
                when (r) {
                    is FaceModel.Result.Ok -> {
                        status.setText(R.string.status_download_done)
                        updateUi()
                        onReady()
                        return@runOnUiThread
                    }
                    is FaceModel.Result.Cancelled -> status.setText(R.string.status_cancelled)
                    is FaceModel.Result.NoSpace ->
                        status.text = getString(R.string.dl_nospace, FaceModel.APPROX_MB + 100)
                    is FaceModel.Result.BadChecksum -> status.setText(R.string.dl_checksum)
                    is FaceModel.Result.Error -> status.setText(R.string.dl_failed)
                }
                updateUi()
            }
        }
    }

    private fun startEnhance() {
        val uri = sourceUri ?: return
        if (state != State.IDLE) return

        var wantFaces = faceCheck.isChecked
        if (wantFaces && !ramAllowsFaceAi()) {
            wantFaces = false
            faceCheck.isChecked = false
            toast(R.string.err_ram)
        }
        if (wantFaces && !FaceModel.isReady(this)) {
            ensureFaceModel { startEnhance() }
            return
        }

        val scale = when (scaleGroup.checkedRadioButtonId) {
            R.id.scale3 -> 3
            R.id.scale4 -> 4
            else -> 2
        }
        val wantGpu = gpuCheck.isChecked
        val look = currentLook()
        val weight = faceWeight()

        // Pichla result free karein taake naye ke liye memory mile
        clearResult()
        originalPreview?.let { preview.setBitmaps(it, null) }
        badge.setText(R.string.badge_original)

        state = State.ENHANCING
        progress.progress = 0
        status.setText(R.string.status_loading_model)
        cancelRequested = false
        updateUi()

        worker.execute {
            var input: Bitmap? = null
            var output: Bitmap? = null
            try {
                if (wantGpu) prefs.edit().putBoolean(KEY_GPU_PENDING, true).commit()

                val mode = enhancer.load(applicationContext.assets, "realesr-animevideov3-x$scale", scale, wantGpu)
                if (mode < 0) {
                    fail(R.string.err_model)
                    return@execute
                }
                val usingGpu = mode == 1
                postStatus(
                    if (wantGpu && !usingGpu) R.string.status_no_gpu else R.string.status_preparing
                )

                // Output ki total size limit mein rakhne ke liye bari photo pehle chhoti ki jati hai
                val maxInput = MAX_OUTPUT_PIXELS / (scale * scale)
                val inp: Bitmap = decodeBitmap(uri, maxInput) ?: run {
                    fail(R.string.err_open)
                    return@execute
                }
                input = inp
                val downscaled = inp.width.toLong() * inp.height < sourceW.toLong() * sourceH * 0.98

                val out = Bitmap.createBitmap(inp.width * scale, inp.height * scale, Bitmap.Config.ARGB_8888)
                output = out

                if (cancelRequested) {
                    fail(R.string.status_cancelled)
                    return@execute
                }

                val t0 = SystemClock.elapsedRealtime()
                val code = enhancer.process(
                    inp, out, if (usingGpu) TILE_GPU else TILE_CPU,
                    ProgressListener { done, total ->
                        runOnUiThread {
                            progress.progress = done * 100 / max(1, total)
                            status.text = getString(R.string.status_progress, done, total)
                        }
                    }
                )
                prefs.edit().putBoolean(KEY_GPU_PENDING, false).commit()
                val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val gpuUsed = enhancer.isGpuActive()

                when (code) {
                    0 -> {
                        // Face restoration (GFPGAN): original photo ke chehre, phir ESRGAN output mein milte hain
                        var restored: List<RestoredFace> = emptyList()
                        var faceMsg = 0
                        if (wantFaces) {
                            val r = restoreFaces(inp)
                            restored = r.first
                            faceMsg = r.second
                            if (cancelRequested) {
                                fail(R.string.status_cancelled)
                                return@execute
                            }
                        }

                        // Live Look preview ke liye chhote bitmaps (enhanced, original usi size par, 2 output buffers)
                        val rp = makePreview(out, PREVIEW_SIDE)
                        val origUp = Bitmap.createScaledBitmap(inp, rp.width, rp.height, true)
                            .let { if (it === inp) inp.copy(Bitmap.Config.ARGB_8888, false) else it }
                        val bufs = arrayOf(
                            Bitmap.createBitmap(rp.width, rp.height, Bitmap.Config.ARGB_8888),
                            Bitmap.createBitmap(rp.width, rp.height, Bitmap.Config.ARGB_8888)
                        )
                        val faced = if (restored.isNotEmpty()) {
                            Bitmap.createBitmap(rp.width, rp.height, Bitmap.Config.ARGB_8888)
                        } else {
                            null
                        }
                        val perSrc = rp.width.toDouble() / inp.width
                        workerIdx = 0
                        val adjInput = withFaces(rp, faced, restored, perSrc, weight)
                        val adj = enhancer.adjust(adjInput, origUp, bufs[0], look.strength, look.smooth, look.vivid, look.clarity)
                        if (adj != 0) {
                            fail(R.string.err_process)
                            return@execute
                        }

                        val result = out
                        val source = inp
                        output = null  // ab UI ke paas hain, finally mein recycle na hon
                        input = null
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            resultBitmap = result
                            sourceBitmap = source
                            previewEnh = rp
                            previewOrigUp = origUp
                            faces = restored
                            previewFaced = faced
                            previewPerSrc = perSrc
                            lookBufs[0] = bufs[0]
                            lookBufs[1] = bufs[1]
                            afterBitmap = bufs[0]
                            preview.resetZoom()
                            preview.setBitmaps(originalPreview, bufs[0])
                            badge.setText(R.string.badge_enhanced)
                            val sb = StringBuilder(
                                getString(
                                    R.string.info_done, result.width, result.height, seconds,
                                    getString(if (gpuUsed) R.string.backend_gpu else R.string.backend_cpu)
                                )
                            )
                            if (restored.isNotEmpty()) sb.append('\n').append(getString(R.string.info_faces, restored.size))
                            if (downscaled) sb.append('\n').append(getString(R.string.info_downscaled))
                            info.text = sb.toString()
                            progress.progress = 100
                            if (faceMsg != 0) status.setText(faceMsg) else status.setText(R.string.status_done)
                            state = State.IDLE
                            updateUi()
                            tabGroup.check(R.id.tabLook)
                        }
                    }
                    1 -> fail(R.string.status_cancelled)
                    else -> fail(R.string.err_process)
                }
            } catch (t: Throwable) {
                prefs.edit().putBoolean(KEY_GPU_PENDING, false).commit()
                fail(if (t is OutOfMemoryError) R.string.err_memory else R.string.err_process)
            } finally {
                input?.recycle()
                output?.recycle()
            }
        }
    }

    /**
     * Chehre dhoondta aur GFPGAN se restore karta hai (worker thread). Koi bhi masla ho to crash nahi hota:
     * @return (restore hue chehre, status message: 0 = koi nahi)
     */
    private fun restoreFaces(inp: Bitmap): Pair<List<RestoredFace>, Int> {
        postStatus(R.string.status_faces_detect)
        runOnUiThread { progress.progress = 0 }
        val landmarks = try {
            FaceFinder().use { it.find(inp) }
        } catch (t: Throwable) {
            return Pair(emptyList(), R.string.status_faces_failed)
        }
        if (cancelRequested) return Pair(emptyList(), 0)
        if (landmarks.isEmpty()) return Pair(emptyList(), R.string.status_no_faces)

        postStatus(R.string.status_faces_loading)
        var restorer: FaceRestorer? = null
        try {
            val big = Runtime.getRuntime().availableProcessors()
            restorer = FaceRestorer(FaceModel.file(applicationContext).path, big.coerceIn(2, 4))
            activeRestorer = restorer
            if (cancelRequested) restorer.cancel()
            val list = FacePipeline.restoreAll(inp, landmarks, restorer, { cancelRequested }) { done, total ->
                runOnUiThread {
                    progress.progress = done * 100 / max(1, total)
                    status.text = getString(R.string.status_faces_restore, done, total)
                }
            }
            return Pair(list, if (list.isEmpty() && !cancelRequested) R.string.status_faces_failed else 0)
        } catch (t: Throwable) {
            // Model kharab ho to dobara download ke liye hata dein; OOM par model rehne dein
            if (restorer == null && t !is OutOfMemoryError) FaceModel.deleteModel(applicationContext)
            return Pair(emptyList(), R.string.status_faces_failed)
        } finally {
            activeRestorer = null
            restorer?.close()   // ~1 GB RAM wapas
        }
    }

    private fun fail(@StringRes msg: Int) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            state = State.IDLE
            status.setText(msg)
            progress.progress = 0
            updateUi()
        }
    }

    private fun postStatus(@StringRes msg: Int) {
        runOnUiThread { if (!isDestroyed) status.setText(msg) }
    }

    // ---------------------------------------------------------------- save

    /** [share] = true: save (agar abhi tak nahi hua) karke share sheet kholta hai. */
    private fun onSaveClicked(share: Boolean) {
        if (resultBitmap == null || state != State.IDLE) return
        if (share && lastSaved != null && lastSavedLook == currentLookKey()) {
            shareUri(lastSaved!!, lastSavedMime)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingShare = share
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave(share)
    }

    /** Slider/format badla to purani saved file dobara share nahi hoti. */
    private var lastSavedLook = ""

    private fun currentLookKey() =
        "${seekStrength.progress},${seekSmooth.progress},${seekVivid.progress},${seekClarity.progress}," +
            "${seekFidelity.progress},${pngCheck.isChecked}"

    private fun doSave(share: Boolean = pendingShare) {
        pendingShare = false
        val bmp = resultBitmap ?: return
        if (state != State.IDLE) return
        val src = sourceBitmap
        val look = currentLook()
        val png = pngCheck.isChecked
        val fs = faces
        val weight = faceWeight()
        val key = currentLookKey()
        val origUri = sourceUri
        state = State.SAVING
        status.setText(R.string.status_saving)
        updateUi()

        worker.execute {
            val name = "AIEnhanced_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}"
            var work: Bitmap? = null
            var styled: Bitmap? = null
            var saved: Uri? = null
            var oom = false
            try {
                // Full size par pehle chehre (fidelity ke mutabiq), phir Look. Copy par, taake slider baad mein bhi kaam karein.
                var toSave = bmp
                if (fs.isNotEmpty() && src != null && weight > 0f) {
                    val w = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    work = w
                    FaceBlender.blend(w, bmp.width.toDouble() / src.width, fs, weight)
                    toSave = w
                }
                if (!look.isNeutral) {
                    val c = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    styled = c
                    check(enhancer.adjust(toSave, src, c, look.strength, look.smooth, look.vivid, look.clarity) == 0) {
                        "adjust failed"
                    }
                    work?.recycle()   // RAM jaldi wapas
                    work = null
                    toSave = c
                }
                saved = saveToGallery(toSave, name, png, origUri)
            } catch (t: Throwable) {
                oom = t is OutOfMemoryError
                saved = null
            } finally {
                work?.recycle()
                styled?.recycle()
            }
            val uri = saved
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                state = State.IDLE
                if (uri != null) {
                    lastSaved = uri
                    lastSavedMime = if (png) "image/png" else "image/jpeg"
                    lastSavedLook = key
                    status.text = getString(R.string.status_saved, name)
                } else {
                    status.setText(if (oom) R.string.err_memory else R.string.err_save)
                }
                updateUi()
                if (uri != null && share) shareUri(uri, lastSavedMime)
            }
        }
    }

    private fun shareUri(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    /** Full-screen compare: pinch-zoom + pan, aur slider. */
    private fun showFullscreen() {
        val before = originalPreview ?: return
        val after = afterBitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this)
        val cv = CompareView(this).apply {
            zoomable = true
            setBitmaps(before, after)
        }
        frame.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = Button(this).apply {
            text = getString(R.string.btn_close)
            isAllCaps = false
            setOnClickListener { dlg.dismiss() }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(24)
        frame.addView(close, lp)
        dlg.setContentView(frame)
        dlg.setOnDismissListener { after.recycle() }
        dlg.show()
    }

    /** @return saved file ka Uri, ya error par exception. */
    private fun saveToGallery(bmp: Bitmap, name: String, png: Boolean, origUri: Uri?): Uri {
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (png) "image/png" else "image/jpeg"
        val quality = 100   // JPEG: sab se zyada quality (PNG mein ignore hota hai)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AIEnhancer")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            try {
                val stream = resolver.openOutputStream(uri) ?: error("stream failed")
                stream.use { out -> check(bmp.compress(format, quality, out)) { "compress failed" } }
                if (!png) {
                    try {
                        resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            copyExif(origUri, ExifInterface(pfd.fileDescriptor))
                        }
                    } catch (t: Throwable) {
                        // EXIF optional hai: fail ho to photo phir bhi save rehti hai
                    }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AIEnhancer")
            if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed")
            val file = File(dir, name)
            FileOutputStream(file).use { out -> check(bmp.compress(format, quality, out)) { "compress failed" } }
            if (!png) {
                try {
                    copyExif(origUri, ExifInterface(file.absolutePath))
                } catch (t: Throwable) {
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
        }
    }

    /**
     * Original photo ke EXIF tags (date, camera, exposure, GPS...) naye JPEG mein copy karta hai.
     * Orientation copy nahi hoti kyunke pixels pehle hi sahi rotate ho chuke hain.
     */
    private fun copyExif(from: Uri?, to: ExifInterface) {
        if (from != null) {
            val src = contentResolver.openInputStream(from)?.use { ExifInterface(it) }
            if (src != null) {
                for (tag in EXIF_TAGS) {
                    val v = src.getAttribute(tag)
                    if (v != null) to.setAttribute(tag, v)
                }
            }
        }
        to.setAttribute(ExifInterface.TAG_SOFTWARE, "AI Enhancer")
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }

    // ---------------------------------------------------------------- UI state

    private fun setEnabledDeep(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    private fun updateUi() {
        val idle = state == State.IDLE
        val busyCancelable = state == State.ENHANCING || state == State.DOWNLOADING
        val haveResult = resultBitmap != null
        pickBtn.isEnabled = idle
        enhanceBtn.isEnabled = busyCancelable || (idle && sourceUri != null)
        enhanceBtn.setText(if (busyCancelable) R.string.btn_cancel else R.string.btn_enhance)
        saveBtn.isEnabled = idle && haveResult
        shareBtn.isEnabled = idle && haveResult
        fullBtn.isEnabled = idle && haveResult
        setEnabledDeep(scaleGroup, idle)
        setEnabledDeep(modeGroup, idle)
        gpuCheck.isEnabled = idle
        pngCheck.isEnabled = idle
        faceCheck.isEnabled = idle

        // Look tab sirf result ke baad
        tabLook.isEnabled = idle && haveResult
        setEnabledDeep(presetGroup, idle && haveResult)
        seekStrength.isEnabled = idle && haveResult
        seekSmooth.isEnabled = idle && haveResult
        seekVivid.isEnabled = idle && haveResult
        seekClarity.isEnabled = idle && haveResult
        seekFidelity.isEnabled = idle && haveResult && faces.isNotEmpty()

        progress.visibility = if (idle) View.INVISIBLE else View.VISIBLE
        hint.visibility = if (originalPreview == null) View.VISIBLE else View.GONE
        badge.visibility = if (originalPreview == null) View.GONE else View.VISIBLE
    }

    private fun toast(@StringRes msg: Int) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- bitmap helpers (worker thread)

    /** EXIF rotation ke baad (width, height). */
    private fun readDimensions(uri: Uri): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = contentResolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return when (readExifOrientation(uri)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE ->
                Pair(opts.outHeight, opts.outWidth)
            else -> Pair(opts.outWidth, opts.outHeight)
        }
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (t: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Photo ko decode karta hai: pixels <= [maxPixels], EXIF rotation lagi hui, transparent hissa safed par. */
    private fun decodeBitmap(uri: Uri, maxPixels: Long): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val s1 = contentResolver.openInputStream(uri) ?: return null
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth.toLong() / (sample * 2)) * (bounds.outHeight.toLong() / (sample * 2)) >= maxPixels) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val s2 = contentResolver.openInputStream(uri) ?: return null
        var bmp = s2.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        bmp = fitToPixels(bmp, maxPixels)
        bmp = applyExif(bmp, readExifOrientation(uri))
        return flattenAlpha(bmp)
    }

    private fun fitToPixels(src: Bitmap, maxPixels: Long): Bitmap {
        val px = src.width.toLong() * src.height
        if (px <= maxPixels) return src
        val f = sqrt(maxPixels.toDouble() / px)
        val out = Bitmap.createScaledBitmap(
            src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true
        )
        if (out !== src) src.recycle()
        return out
    }

    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun flattenAlpha(src: Bitmap): Bitmap {
        if (!src.hasAlpha() && src.config == Bitmap.Config.ARGB_8888) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, null)
        src.recycle()
        return out
    }

    private fun makePreview(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src.copy(Bitmap.Config.ARGB_8888, false)
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true
        )
    }

    companion object {
        private const val KEY_GPU_PENDING = "gpu_pending"
        private const val MB = 1024L * 1024L

        /** Face AI (~1 GB RAM) ke liye kam az kam total RAM: "4 GB" phones ~3.6 GB report karte hain. */
        private const val MIN_RAM_FOR_FACE_AI = 3_300_000_000L

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
        )

        /** Output (enhanced) image ke pixels ki hadd. 16M pixel = ~64 MB RAM. Kam RAM wale phone ke liye 9_000_000 karein. */
        private const val MAX_OUTPUT_PIXELS = 16_000_000L

        private const val PREVIEW_PIXELS = 2_000_000L
        private const val PREVIEW_SIDE = 1600

        /** Tile size (input pixels). Bara = tez magar zyada RAM. Phone hang/crash ho to kam karein. */
        private const val TILE_GPU = 128
        private const val TILE_CPU = 96
    }
}
