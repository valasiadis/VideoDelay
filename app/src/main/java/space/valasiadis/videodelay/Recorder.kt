package space.valasiadis.videodelay

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the delayed stream to Movies/VideoDelay/ as MP4 by copying the already-encoded packets
 * out of the ring buffer. No re-encoding, so it costs almost nothing.
 *
 * Records the live (undelayed) stream: from the first keyframe at or after [startPtsUs]
 * (the pipeline requests one when recording starts) to the newest frame when stopped.
 */
class Recorder(
    private val context: Context,
    private val ring: PacketRing,
    format: MediaFormat,
    orientationHint: Int,
    private val startPtsUs: Long,
) {
    private val resolver = context.contentResolver
    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val track: Int

    @Volatile private var stopLimitUs: Long? = null
    private var cursor = -1L
    private var basePts = -1L
    private var samples = 0
    private var lastPts = 0L
    private val thread: Thread

    init {
        val name = "VideoDelay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoDelay")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Could not create the video file")
        try {
            pfd = resolver.openFileDescriptor(uri, "rw") ?: error("Could not open the video file")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationHint)
            track = muxer.addTrack(format)
            muxer.start()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        thread = Thread(::loop, "recorder").apply { start() }
    }

    /** Length of video written so far. */
    val durationUs: Long get() = synchronized(this) { if (basePts < 0) 0 else lastPts - basePts }

    private fun loop() {
        var buf = ByteBuffer.allocateDirect(1 shl 20)
        val meta = PacketRing.Meta()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val stop = stopLimitUs
            val limit = stop ?: Long.MAX_VALUE
            var wrote = false
            if (cursor < 0) cursor = ring.firstKeyframeAtOrAfter(startPtsUs)
            while (cursor >= 0) {
                if (cursor < ring.oldestSeq) {
                    // Fell behind the buffer (shouldn't happen): resume at the next keyframe.
                    cursor = ring.firstKeyframeFrom(ring.oldestSeq).takeIf { it >= 0 } ?: break
                }
                val pts = ring.ptsOf(cursor) ?: break
                if (pts > limit) break
                val size = ring.sizeOf(cursor)
                if (size > buf.capacity()) buf = ByteBuffer.allocateDirect(size * 2)
                buf.clear()
                if (!ring.read(cursor, buf, meta)) break
                buf.flip()
                synchronized(this) {
                    if (basePts < 0) basePts = meta.ptsUs
                    lastPts = meta.ptsUs
                }
                info.set(0, meta.size, meta.ptsUs - basePts, if (meta.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
                samples++
                cursor++
                wrote = true
            }
            if (stop != null) return
            if (!wrote) {
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Writes the remaining packets up to the newest frame, then stops reading the ring. */
    fun stopWriting() {
        stopLimitUs = ring.newestPtsUs() ?: Long.MIN_VALUE
        thread.join(5_000)
    }

    /** Finalizes the file (call after [stopWriting], off the main thread). Returns null if nothing was recorded. */
    fun finish(): Uri? {
        var ok = samples > 0
        try {
            muxer.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "muxer.stop failed", t)
            ok = false
        }
        muxer.release()
        pfd.close()
        return if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }

    private companion object {
        const val TAG = "Recorder"
    }
}
