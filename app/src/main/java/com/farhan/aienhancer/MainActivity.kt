package com.farhan.aienhancer

import android.Manifest
import android.content.ContentValues
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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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

    private enum class State { IDLE, ENHANCING, SAVING }

    /** "Look" sliders ki values (0..100). */
    private data class Look(val strength: Int, val smooth: Int, val vivid: Int, val clarity: Int) {
        val isNeutral get() = strength >= 100 && smooth == 0 && vivid == 0 && clarity == 0
    }

    private val enhancer = NativeEnhancer()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("enhancer", MODE_PRIVATE) }

    private lateinit var preview: ImageView
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
    private var afterBitmap: Bitmap? = null      // chhodne par ye dikhta hai (touch compare)
    private var holdingOriginal = false
    private val lookVersion = AtomicInteger(0)
    private var workerIdx = 0                    // sirf worker thread par use hota hai

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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

        pickBtn.setOnClickListener { pickImage.launch("image/*") }
        enhanceBtn.setOnClickListener {
            if (state == State.ENHANCING) {
                cancelRequested = true
                enhancer.cancel()
                status.setText(R.string.status_cancelling)
            } else {
                startEnhance()
            }
        }
        saveBtn.setOnClickListener { onSaveClicked() }

        // Dabaye rakhein = original, chhor dein = enhanced (Look ke saath)
        preview.setOnTouchListener { v, ev ->
            val before = originalPreview
            val after = afterBitmap
            if (before != null && after != null) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holdingOriginal = true
                        preview.setImageBitmap(before)
                        badge.setText(R.string.badge_original)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holdingOriginal = false
                        preview.setImageBitmap(after)
                        badge.setText(R.string.badge_enhanced)
                        if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
            }
            true
        }

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
                    presetGroup.clearCheck()
                    scheduleLook()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekStrength.setOnSeekBarChangeListener(seekListener)
        seekSmooth.setOnSeekBarChangeListener(seekListener)
        seekVivid.setOnSeekBarChangeListener(seekListener)
        seekClarity.setOnSeekBarChangeListener(seekListener)
        setSliders(55, 50, 35, 25)  // default: Portrait

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

    private fun updateLookLabels() {
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
        val version = lookVersion.incrementAndGet()
        worker.execute {
            if (version != lookVersion.get()) return@execute
            val idx = workerIdx xor 1
            val buf = lookBufs[idx] ?: return@execute
            workerIdx = idx
            if (enhancer.adjust(enh, orig, buf, look.strength, look.smooth, look.vivid, look.clarity) != 0) {
                return@execute
            }
            runOnUiThread {
                if (isDestroyed || version != lookVersion.get() || previewEnh !== enh) return@runOnUiThread
                afterBitmap = buf
                if (!holdingOriginal) preview.setImageBitmap(buf)
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
        holdingOriginal = false
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
                preview.setImageBitmap(prev)
                badge.setText(R.string.badge_original)
                info.text = getString(R.string.info_original, sourceW, sourceH)
                status.setText(R.string.status_idle)
                progress.progress = 0
                updateUi()
            }
        }
    }

    // ---------------------------------------------------------------- enhance

    private fun startEnhance() {
        val uri = sourceUri ?: return
        if (state != State.IDLE) return

        val scale = when (scaleGroup.checkedRadioButtonId) {
            R.id.scale3 -> 3
            R.id.scale4 -> 4
            else -> 2
        }
        val wantGpu = gpuCheck.isChecked
        val look = currentLook()

        // Pichla result free karein taake naye ke liye memory mile
        clearResult()
        originalPreview?.let { preview.setImageBitmap(it) }
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
                        // Live Look preview ke liye chhote bitmaps (enhanced, original usi size par, 2 output buffers)
                        val rp = makePreview(out, PREVIEW_SIDE)
                        val origUp = Bitmap.createScaledBitmap(inp, rp.width, rp.height, true)
                            .let { if (it === inp) inp.copy(Bitmap.Config.ARGB_8888, false) else it }
                        val bufs = arrayOf(
                            Bitmap.createBitmap(rp.width, rp.height, Bitmap.Config.ARGB_8888),
                            Bitmap.createBitmap(rp.width, rp.height, Bitmap.Config.ARGB_8888)
                        )
                        workerIdx = 0
                        val adj = enhancer.adjust(rp, origUp, bufs[0], look.strength, look.smooth, look.vivid, look.clarity)
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
                            lookBufs[0] = bufs[0]
                            lookBufs[1] = bufs[1]
                            afterBitmap = bufs[0]
                            preview.setImageBitmap(bufs[0])
                            badge.setText(R.string.badge_enhanced)
                            val sb = StringBuilder(
                                getString(
                                    R.string.info_done, result.width, result.height, seconds,
                                    getString(if (gpuUsed) R.string.backend_gpu else R.string.backend_cpu)
                                )
                            )
                            if (downscaled) sb.append('\n').append(getString(R.string.info_downscaled))
                            info.text = sb.toString()
                            progress.progress = 100
                            status.setText(R.string.status_done)
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

    private fun onSaveClicked() {
        if (resultBitmap == null || state != State.IDLE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave()
    }

    private fun doSave() {
        val bmp = resultBitmap ?: return
        if (state != State.IDLE) return
        val src = sourceBitmap
        val look = currentLook()
        val png = pngCheck.isChecked
        state = State.SAVING
        status.setText(R.string.status_saving)
        updateUi()

        worker.execute {
            val name = "AIEnhanced_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}"
            var styled: Bitmap? = null
            val ok = try {
                val toSave = if (look.isNeutral) {
                    bmp
                } else {
                    // Look full size par lagta hai (kuch second). Copy par, taake slider baad mein bhi kaam karein.
                    val c = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    styled = c
                    check(enhancer.adjust(bmp, src, c, look.strength, look.smooth, look.vivid, look.clarity) == 0) {
                        "adjust failed"
                    }
                    c
                }
                saveToGallery(toSave, name, png)
                true
            } catch (t: Throwable) {
                false
            } finally {
                styled?.recycle()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                state = State.IDLE
                status.text = if (ok) getString(R.string.status_saved, name) else getString(R.string.err_save)
                updateUi()
            }
        }
    }

    private fun saveToGallery(bmp: Bitmap, name: String, png: Boolean) {
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (png) "image/png" else "image/jpeg"

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
                stream.use { out -> check(bmp.compress(format, 95, out)) { "compress failed" } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AIEnhancer")
            if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed")
            val file = File(dir, name)
            FileOutputStream(file).use { out -> check(bmp.compress(format, 95, out)) { "compress failed" } }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
    }

    // ---------------------------------------------------------------- UI state

    private fun setEnabledDeep(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    private fun updateUi() {
        val idle = state == State.IDLE
        val haveResult = resultBitmap != null
        pickBtn.isEnabled = idle
        enhanceBtn.isEnabled = state == State.ENHANCING || (idle && sourceUri != null)
        enhanceBtn.setText(if (state == State.ENHANCING) R.string.btn_cancel else R.string.btn_enhance)
        saveBtn.isEnabled = idle && haveResult
        setEnabledDeep(scaleGroup, idle)
        gpuCheck.isEnabled = idle
        pngCheck.isEnabled = idle

        // Look tab sirf result ke baad
        tabLook.isEnabled = idle && haveResult
        setEnabledDeep(presetGroup, idle && haveResult)
        seekStrength.isEnabled = idle && haveResult
        seekSmooth.isEnabled = idle && haveResult
        seekVivid.isEnabled = idle && haveResult
        seekClarity.isEnabled = idle && haveResult

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

        /** Output (enhanced) image ke pixels ki hadd. 16M pixel = ~64 MB RAM. Kam RAM wale phone ke liye 9_000_000 karein. */
        private const val MAX_OUTPUT_PIXELS = 16_000_000L

        private const val PREVIEW_PIXELS = 2_000_000L
        private const val PREVIEW_SIDE = 1600

        /** Tile size (input pixels). Bara = tez magar zyada RAM. Phone hang/crash ho to kam karein. */
        private const val TILE_GPU = 128
        private const val TILE_CPU = 96
    }
}
