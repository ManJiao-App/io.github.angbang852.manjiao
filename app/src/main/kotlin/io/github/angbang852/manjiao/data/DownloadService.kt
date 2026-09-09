package io.github.angbang852.manjiao.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.angbang852.manjiao.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object DownloadService {
    private const val CH = "slowkick_dl"
    private var seq = 100

    fun downloadVideo(ctx: Context, info: VideoInfo, dir: String) {
        val candidates = mutableListOf<String>()
        info.bestRepUrl()?.let { candidates.add(it); Logger.d("DL use bestRep: $it") }
        info.url?.let { if (it !in candidates) candidates.add(it) }
        info.domainUrl?.let { if (it !in candidates) candidates.add(it) }
        download(ctx, candidates, info.videoFileName(), dir, info, false)
    }

    fun downloadAudio(ctx: Context, info: VideoInfo, dir: String) {
        val candidates = mutableListOf<String>()
        (info.audioUrl ?: info.url)?.let { candidates.add(it) }
        info.domainUrl?.let { if (it !in candidates) candidates.add(it) }
        download(ctx, candidates, info.audioFileName(), dir, info, true)
    }

    fun downloadImages(ctx: Context, info: VideoInfo, dir: String) {
        val urls = info.imageUrls
        if (urls.isEmpty()) { toast(ctx, "未捕获到图集图片"); return }
        val base = info.baseName()
        toast(ctx, "开始下载图集: $base (${urls.size}张)")
        val nid = seq++
        notify(ctx, nid, "准备下载图集: $base", -1)
        thread {
            try {
                val outDir = File(dir, base)
                outDir.mkdirs()
                var done = 0
                for ((idx, url) in urls.withIndex()) {
                    val name = "${base}_${idx + 1}.jpg"
                    try {
                        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000; readTimeout = 30000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) kwai")
                            setRequestProperty("Referer", "https://www.kuaishou.com/")
                        }
                        conn.connect()
                        if (conn.responseCode != 200) { Logger.d("DL img fail HTTP ${conn.responseCode}"); conn.disconnect(); continue }
                        val bytes = conn.inputStream.use { it.readBytes() }
                        conn.disconnect()
                        if (saveImg(bytes, File(outDir, name))) done++
                        notify(ctx, nid, "下载图集 ${done}/${urls.size}", done * 100 / urls.size)
                    } catch (t: Throwable) { Logger.d("DL img[$idx] error: ${t.message}") }
                }
                if (done == 0) { notify(ctx, nid, "图集下载失败", -2); toast(ctx, "图集下载失败"); return@thread }
                saveMeta(dir, base, info)
                notify(ctx, nid, "图集完成: $base ($done/${urls.size}张)", 100)
                toast(ctx, "图集下载完成: $base ($done/${urls.size}张)")
            } catch (t: Throwable) {
                Logger.d("DL images error: ${t.message}")
                notify(ctx, nid, "图集下载失败: ${t.message}", -2)
                toast(ctx, "图集下载失败: ${t.message}")
            }
        }
    }

    private fun saveImg(bytes: ByteArray, out: File): Boolean {
        try {
            if (bytes.size > 12) {
                val head4 = String(bytes, 0, 4, Charsets.US_ASCII)
                if (head4 == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") { out.writeBytes(bytes); return true }
                val b0 = bytes[0].toInt() and 0xFF
                if (b0 == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) { out.writeBytes(bytes); return true }
                if (b0 == 0x89 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG") { out.writeBytes(bytes); return true }
                if (String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        val bmp = ImageDecoder.decodeBitmap(src) { dec, _, _ -> dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        bmp.recycle()
                        Logger.d("saveImg kvif->jpg ok: ${out.name} ${bytes.size}B")
                        return true
                    }
                }
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bmp.recycle()
                return true
            }
            out.writeBytes(bytes)
            return true
        } catch (t: Throwable) {
            Logger.d("saveImg err: ${t.message}")
            try { out.writeBytes(bytes) } catch (_: Throwable) {}
            return false
        }
    }

    private fun download(ctx: Context, urls: List<String>, name: String, dir: String, info: VideoInfo, audio: Boolean) {
        if (urls.isEmpty()) { toast(ctx, "无可用下载链接"); return }
        val nid = seq++
        toast(ctx, "开始下载: $name")
        notify(ctx, nid, "准备下载: $name", -1)
        thread {
            try {
                File(dir).mkdirs()
                val out = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                var success = false
                var lastErr: String? = null
                for ((idx, url) in urls.withIndex()) {
                    if (success) break
                    Logger.d("DL try[${idx + 1}/${urls.size}]: $url")
                    try {
                        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000; readTimeout = 30000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) kwai")
                            setRequestProperty("Referer", "https://www.kuaishou.com/")
                        }
                        conn.connect()
                        if (conn.responseCode != 200) { lastErr = "HTTP ${conn.responseCode}"; Logger.d("DL fail $lastErr"); conn.disconnect(); continue }
                        val total = conn.contentLengthLong
                        Logger.d("DL start: $url -> ${out.absolutePath} (audio=$audio, size=$total)")
                        conn.inputStream.use { input ->
                            FileOutputStream(tmp).use { fos ->
                                val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                                val step = if (total > 0) total / 100 else 200 * 1024L
                                while (true) {
                                    val n = input.read(buf); if (n <= 0) break
                                    fos.write(buf, 0, n); done += n
                                    if (done - last > step) { last = done; val p = if (total > 0) (done * 100 / total).toInt() else -1; notify(ctx, nid, "下载中 ${done / 1024}KB/${if (total > 0) "${total / 1024}KB" else "?"}", p) }
                                }
                            }
                        }
                        conn.disconnect()
                        success = true
                    } catch (t: Throwable) {
                        lastErr = "${t.javaClass.simpleName}: ${t.message}"
                        Logger.d("DL try[${idx + 1}] error: $lastErr")
                    }
                }
                if (!success) { notify(ctx, nid, "失败: $lastErr", -2); toast(ctx, "下载失败: $lastErr"); return@thread }

                if (audio && info.audioUrl == null) {
                    tmp.renameTo(File(dir, "$name.vid.tmp"))
                    val vidTmp = File(dir, "$name.vid.tmp")
                    extractAudio(vidTmp, out)
                    vidTmp.delete()
                } else {
                    tmp.renameTo(out)
                }
                Logger.d("DL ok: ${out.absolutePath}")
                notify(ctx, nid, "完成: $name (${out.length() / 1024}KB)", 100)
                toast(ctx, "下载完成: $name")
                saveMeta(dir, name, info)
            } catch (t: Throwable) {
                Logger.d("DL error: ${t.javaClass.name}: ${t.message}")
                notify(ctx, nid, "失败: ${t.message}", -2)
                toast(ctx, "下载失败: ${t.message}")
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        try { Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } } catch (_: Throwable) {}
    }

    private fun extractAudio(src: File, dst: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.absolutePath)
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { audioTrack = i; break }
        }
        if (audioTrack < 0) { Logger.d("no audio track"); return }
        extractor.selectTrack(audioTrack)
        val outFmt = extractor.getTrackFormat(audioTrack)
        val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(outFmt)
        muxer.start()
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0; info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            buf.limit(size)
            muxer.writeSampleData(outTrack, buf, info)
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
    }

    private fun saveMeta(dir: String, name: String, info: VideoInfo) {
        try {
            val cap = info.caption ?: ""
            val user = info.userName ?: ""
            if (cap.isBlank() && user.isBlank()) return
            File(dir, "$name.txt").writeText("作者: $user\n描述: $cap\n来源: 快手(无水印)\n")
        } catch (_: Throwable) {}
    }

    private fun notify(ctx: Context, id: Int, text: String, prog: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW))
            }
            val indet = prog == -1
            val ongoing = prog == -1 || prog in 1..99
            val builder = NotificationCompat.Builder(ctx, CH)
                .setContentTitle("ManJiao").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
            if (indet) builder.setProgress(0, 0, true)
            else if (prog in 0..100) builder.setProgress(100, prog, false)
            nm.notify(id, builder.build())
        } catch (_: Throwable) {}
    }
}
