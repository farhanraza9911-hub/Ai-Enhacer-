package com.farhan.aienhancer

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GFPGAN v1.4 model ki download/verification. Ye model APK mein nahi hai (~170 MB), user ki ijazat se
 * ek dafa download hota hai. Internet SIRF isi file ke liye use hota hai; user ki photo kabhi upload nahi hoti.
 *
 * Model aapke apne GitHub release par rehta hai (workflow `publish-models.yml` use bana kar upload karta hai).
 * Saath mein `.sha256` file bhi hoti hai jisse download verify hota hai.
 */
object FaceModel {
    const val NAME = "gfpgan_1.4_fp16w.onnx"
    const val URL_STR = "https://github.com/farhanraza9911-hub/Ai-Enhacer-/releases/download/models-v1/gfpgan_1.4_fp16w.onnx"
    private const val SHA_URL = "$URL_STR.sha256"

    /** Sirf user ko dikhane ke liye. */
    const val APPROX_MB = 170

    fun file(ctx: Context) = File(File(ctx.filesDir, "models"), NAME)
    private fun marker(ctx: Context) = File(file(ctx).path + ".ok")

    /** Download poora aur verify ho chuka hai? (verify ke baad hi marker banta hai) */
    fun isReady(ctx: Context): Boolean {
        val f = file(ctx)
        val m = marker(ctx)
        return f.isFile && m.isFile && m.readText().trim() == f.length().toString()
    }

    fun deleteModel(ctx: Context) {
        file(ctx).delete()
        marker(ctx).delete()
        File(file(ctx).path + ".part").delete()
    }

    sealed class Result {
        object Ok : Result()
        object Cancelled : Result()
        object NoSpace : Result()
        object BadChecksum : Result()
        data class Error(val msg: String) : Result()
    }

    /** Blocking: worker thread par chalayein. Adhoori download resume hoti hai. */
    fun download(ctx: Context, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Result {
        val dst = file(ctx)
        val dir = dst.parentFile!!
        if (!dir.exists() && !dir.mkdirs()) return Result.Error("mkdirs")
        if (isReady(ctx)) return Result.Ok
        val part = File(dst.path + ".part")

        try {
            val expectedSha = fetchSha() ?: return Result.Error("checksum file")

            var have = if (part.isFile) part.length() else 0L
            var total = -1L
            var conn: HttpURLConnection? = null
            try {
                conn = open(URL_STR).apply { if (have > 0) setRequestProperty("Range", "bytes=$have-") }
                var code = conn.responseCode
                if (code == 416) {          // purana .part kharab/poora: shuru se
                    conn.disconnect()
                    part.delete()
                    have = 0L
                    conn = open(URL_STR)
                    code = conn.responseCode
                }
                val append: Boolean
                when (code) {
                    206 -> {
                        append = true
                        total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    }
                    200 -> {
                        append = false          // server ne Range nahi mana: shuru se
                        have = 0L
                        total = conn.contentLengthLong
                    }
                    else -> return Result.Error("HTTP $code")
                }
                if (total > 0 && StatFs(dir.path).availableBytes < total - have + 64L * 1024 * 1024) {
                    return Result.NoSpace
                }
                FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = have
                        var lastReport = 0L
                        while (true) {
                            if (isCancelled()) return Result.Cancelled
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastReport > 200_000_000L) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            } finally {
                conn?.disconnect()
            }

            if (total > 0 && part.length() != total) return Result.Error("incomplete")
            if (!sha256(part).equals(expectedSha, ignoreCase = true)) {
                part.delete()
                return Result.BadChecksum
            }
            val len = part.length()
            if (!part.renameTo(dst)) return Result.Error("rename")
            marker(ctx).writeText(len.toString())
            return Result.Ok
        } catch (t: Throwable) {
            return Result.Error(t.javaClass.simpleName)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AIEnhancer-Android")
        }

    private fun fetchSha(): String? {
        val c = open(SHA_URL)
        try {
            if (c.responseCode != 200) return null
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length == 64 }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
