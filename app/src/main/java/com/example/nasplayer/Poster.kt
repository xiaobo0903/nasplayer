package com.example.nasplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")
private val FOLDER_POSTER = listOf("poster", "folder", "cover", "movie", "thumb")

/** 过滤出文件夹和视频，并为视频匹配同目录下的海报图（同名图、-poster、-thumb；目录内只有一个视频时可用 poster.jpg 等）。 */
fun withPosters(all: List<NasItem>): List<NasItem> {
    val imgs = all.filter { !it.isDir && it.ext in IMG_EXT }.associateBy { it.name.lowercase() }
    val folderImg = if (all.count { it.isVideo } == 1)
        FOLDER_POSTER.firstNotNullOfOrNull { n -> IMG_EXT.firstNotNullOfOrNull { imgs["$n.$it"] } } else null
    return all.filter { it.isDir || it.isVideo }.map { v ->
        if (v.isDir) v else {
            val b = v.name.substringBeforeLast('.').lowercase()
            val own = IMG_EXT.firstNotNullOfOrNull { imgs["$b.$it"] ?: imgs["$b-poster.$it"] ?: imgs["$b-thumb.$it"] }
            v.copy(poster = (own ?: folderImg)?.path)
        }
    }
}

/**
 * 海报加载：内存 LRU → 磁盘缓存 → NAS。
 * 海报来源优先级：同目录图片 → 文件夹内 poster/folder/cover 图 → 视频截帧。
 * 无海报的结果也会缓存（.none），避免反复扫描；网络错误不缓存，下次会重试。
 */
class PosterLoader(ctx: Context, private val cfg: () -> NasConfig?) {
    private val dir = File(ctx.cacheDir, "posters").apply { mkdirs() }
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sem = Semaphore(3)
    private val lock = Any()
    private var session: SmbSession? = null
    private var closed = false

    private fun sess(): SmbSession = synchronized(lock) {
        if (closed) throw IOException("closed")
        session ?: SmbSession(cfg() ?: throw IOException("no config")).also { session = it }
    }
    private fun resetSession() = synchronized(lock) { val s = session; session = null; if (s != null) Thread { s.close() }.start() }

    suspend fun load(item: NasItem): Bitmap? = withContext(Dispatchers.IO) {
        val c = cfg() ?: return@withContext null
        val key = md5("${c.host}/${c.share}/${item.path}/${item.size}/${item.time}")
        mem.get(key)?.let { return@withContext it }
        val jpg = File(dir, "$key.jpg"); val none = File(dir, "$key.none")
        if (none.exists()) return@withContext null
        if (jpg.exists()) return@withContext BitmapFactory.decodeFile(jpg.path)?.also { mem.put(key, it) }
        sem.withPermit {
            val bmp = try { generate(item) } catch (e: Exception) { resetSession(); return@withPermit null }
            if (bmp == null) { none.createNewFile(); null }
            else { jpg.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; mem.put(key, bmp); bmp }
        }
    }

    private fun generate(item: NasItem): Bitmap? {
        val s = sess()
        if (item.poster != null) return decode(readAll(s, item.poster))
        if (!item.isDir) return frame(s, item.path)
        val kids = s.list(item.path)
        val img = FOLDER_POSTER.firstNotNullOfOrNull { n ->
            kids.firstOrNull { !it.isDir && it.ext in IMG_EXT && it.name.substringBeforeLast('.').equals(n, true) } }
        if (img != null) return decode(readAll(s, img.path))
        val vid = kids.filter { it.isVideo }.minByOrNull { it.name } ?: return null
        return frame(s, vid.path)
    }

    private fun readAll(s: SmbSession, p: String): ByteArray = s.openRead(p).use { f ->
        if (f.fileInformation.standardInformation.endOfFile > 10_000_000) ByteArray(0) else f.inputStream.use { it.readBytes() }
    }

    private fun decode(b: ByteArray): Bitmap? {
        if (b.isEmpty()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var ss = 1; while (o.outWidth / ss > 480) ss *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
    }

    /** 从视频约 10% 处截取一帧；用 1MB 预读块减少 SMB 往返。 */
    private fun frame(s: SmbSession, p: String): Bitmap? {
        val f = s.openRead(p)
        val r = MediaMetadataRetriever()
        try {
            val len = f.fileInformation.standardInformation.endOfFile
            r.setDataSource(object : MediaDataSource() {
                private val blk = ByteArray(1 shl 20); private var bs = -1L; private var bl = 0
                override fun getSize() = len
                @Synchronized override fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
                    var done = 0
                    while (done < size && pos + done < len) {
                        val cur = pos + done
                        if (bs < 0 || cur < bs || cur >= bs + bl) {
                            bs = cur - cur % blk.size; bl = 0
                            val n = f.read(blk, bs, 0, minOf(blk.size.toLong(), len - bs).toInt())
                            if (n <= 0) break
                            bl = n
                        }
                        val a = (cur - bs).toInt(); val c = minOf(size - done, bl - a)
                        System.arraycopy(blk, a, buf, off + done, c); done += c
                    }
                    return if (done == 0) -1 else done
                }
                override fun close() {}
            })
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val raw = r.getFrameAtTime((dur / 10).coerceIn(0, 300_000) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return if (raw.width > 480) Bitmap.createScaledBitmap(raw, 480, raw.height * 480 / raw.width, true) else raw
        } catch (e: RuntimeException) { return null }      // 设备不支持该格式截帧
        finally { runCatching { r.release() }; runCatching { f.close() } }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun close() { synchronized(lock) { closed = true }; mem.evictAll(); resetSession() }
}
