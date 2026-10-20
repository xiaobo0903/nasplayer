@file:OptIn(UnstableApi::class)
package com.example.nasplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class NasConfig(val host: String, val share: String, val user: String, val pass: String, val startPath: String)

val VIDEO_EXT = setOf("mp4", "mkv", "avi", "ts", "m2ts", "mts", "mov", "wmv", "flv", "rmvb", "rm", "mpg", "mpeg", "webm", "m4v", "3gp", "vob")
val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

data class NasItem(val name: String, val path: String, val isDir: Boolean, val size: Long, val time: Long, val poster: String? = null) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isVideo get() = !isDir && ext in VIDEO_EXT
}

private fun String.smb() = trim('/').replace('/', '\\')

/** 一个 SMB 会话。路径内部统一用 "/" 分隔。 */
class SmbSession(val cfg: NasConfig) : Closeable {
    private val client = SMBClient(
        SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build()
    )
    val share: DiskShare

    init {
        try {
            val conn = client.connect(cfg.host)
            val auth = if (cfg.user.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(cfg.user, cfg.pass.toCharArray(), "")
            share = conn.authenticate(auth).connectShare(cfg.share) as DiskShare
        } catch (e: Exception) {
            runCatching { client.close() }; throw e
        }
    }

    fun list(dir: String): List<NasItem> = share.list(dir.smb())
        .filter { it.fileName != "." && it.fileName != ".." }
        .map {
            val isDir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            NasItem(it.fileName, if (dir.isEmpty()) it.fileName else "$dir/${it.fileName}",
                isDir, it.endOfFile, it.lastWriteTime.toEpochMillis())
        }

    fun openRead(path: String) = share.openFile(path.smb(), EnumSet.of(AccessMask.GENERIC_READ), null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE), SMB2CreateDisposition.FILE_OPEN, null)

    override fun close() {
        runCatching { share.close() }
        runCatching { client.close() }
    }
}

fun smbUri(cfg: NasConfig, path: String): Uri =
    Uri.Builder().scheme("smb").authority(cfg.host).path("/$path").build()

/** ExoPlayer 数据源：随机读取 SMB 文件，支持任意位置拖动。 */
class SmbDataSource(private val session: SmbSession) : BaseDataSource(true) {
    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val p = dataSpec.uri.path.orEmpty().smb()
        try {
            val f = session.share.openFile(p, EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN, null)
            file = f
            val len = f.fileInformation.standardInformation.endOfFile
            pos = dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) len - pos else minOf(dataSpec.length, len - pos)
        } catch (e: Exception) {
            throw IOException(e)
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = try {
            file!!.read(buffer, pos, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) { throw IOException(e) }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { runCatching { file?.close() } } finally {
            file = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }
}

/** 配置、排序偏好、播放进度的持久化。 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nas", Context.MODE_PRIVATE)
    var cfg: NasConfig?
        get() = sp.getString("host", null)?.let {
            NasConfig(it, sp.getString("share", "")!!, sp.getString("user", "")!!, sp.getString("pass", "")!!, sp.getString("start", "")!!)
        }
        set(v) { sp.edit().apply { if (v == null) remove("host") else {
            putString("host", v.host); putString("share", v.share); putString("user", v.user)
            putString("pass", v.pass); putString("start", v.startPath) } }.apply() }
    var sortKey: Int get() = sp.getInt("sortKey", 0); set(v) { sp.edit().putInt("sortKey", v).apply() }
    var wall: Boolean get() = sp.getBoolean("wall", true); set(v) { sp.edit().putBoolean("wall", v).apply() }
    var asc: Boolean get() = sp.getBoolean("asc", true); set(v) { sp.edit().putBoolean("asc", v).apply() }

    /** 返回 (位置ms, 总时长ms) */
    fun pos(path: String): Pair<Long, Long>? = sp.getString("p:$path", null)?.split("|")?.let {
        if (it.size == 2) (it[0].toLongOrNull() ?: 0L) to (it[1].toLongOrNull() ?: 0L) else null }
    fun savePos(path: String, pos: Long, dur: Long) { sp.edit().putString("p:$path", "$pos|$dur").apply() }
    fun clearPos(path: String) { sp.edit().remove("p:$path").apply() }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
