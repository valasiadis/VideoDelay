package space.valasiadis.videodelay

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * camera ──► hardware encoder ──► [PacketRing: ~33 s of compressed video] ──► decoder ──► screen
 *    │                                                  └─ newest packets ──► Recorder (MP4, live)
 *    └──► optional live preview surface (no delay)
 *
 * Holding compressed packets instead of raw frames is what makes 30 s at 4K fit in memory.
 * The playback thread feeds the decoder with packets captured `delay` ago and releases each
 * decoded frame to the screen when its capture time + delay is reached.
 */
class DelayPipeline(
    private val context: Context,
    private val camera: CameraOption,
    private val settings: Settings,
    private val displaySurface: Surface,
    initialDelayMs: Int,
    initialZoom: Float,
    /** Called from a background thread. */
    private val onError: (String) -> Unit,
    /** Non-fatal message, called from a background thread. */
    private val onNotice: (String) -> Unit,
) {
    /** Where the live preview goes. The size is re-applied before every session (TextureView resets it). */
    class PreviewTarget(val surface: Surface, val texture: SurfaceTexture, val size: Size)

    data class Stats(
        /** False until the first delayed frame is on screen (the UI shows black until then). */
        val showing: Boolean,
        val bufferedUs: Long,
        val delayUs: Long,
        val recordingUs: Long?,
        val codec: String,
    )

    val width = settings.width
    val height = settings.height
    private val fps = settings.fps

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }
    private val encoderThread = HandlerThread("encoder").apply { start() }

    private val mime: String
    private val encoder: MediaCodec
    private val encoderSurface: Surface
    private val ring: PacketRing

    @Volatile private var running = true
    @Volatile private var encoderFormat: MediaFormat? = null
    @Volatile private var delayUs = initialDelayMs * 1000L
    private val seekRequested = AtomicBoolean(false)

    /** Capture time (pts) of the frame currently on screen. */
    @Volatile private var shownPtsUs = Long.MIN_VALUE

    /** Correction if the encoder's timestamps aren't on the clock we expect. */
    @Volatile private var clockOffsetUs = 0L
    private var clockChecked = false

    // Camera objects: only touched on the camera thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var zoom = initialZoom
    private val cameraClosed = CountDownLatch(1)
    private var preview: PreviewTarget? = null
    private var sessionGen = 0
    private var sessionReady: CountDownLatch? = null

    private val playbackThread: Thread
    private var recorder: Recorder? = null

    init {
        val wantHevc = settings.hevc && Codecs.supportsHevc(width, height, fps)
        mime = if (wantHevc) Codecs.HEVC else Codecs.AVC
        val info = Codecs.findEncoder(mime, width, height, fps)
        encoder = info?.let { MediaCodec.createByCodecName(it.name) } ?: MediaCodec.createEncoderByType(mime)

        var bitrate = Codecs.bitrateFor(mime, width, height, fps)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Keyframe every second: seeking (delay changes) and recording start snap to keyframes.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // No B-frames, so decode order == display order; the ring relies on that.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            info?.getCapabilitiesForType(mime)?.let { caps ->
                caps.videoCapabilities?.bitrateRange?.let { bitrate = bitrate.coerceIn(it.lower, it.upper) }
                if (caps.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
                ) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }

        // Room for the max delay plus slack for keyframes and VBR peaks.
        val keepUs = (MAX_DELAY_MS + 3_000) * 1000L
        val bytes = (bitrate / 8.0 * (keepUs / 1e6) * 1.35).toLong() + (8 shl 20)
        ring = PacketRing(bytes.coerceAtMost(900L shl 20).toInt(), keepUs)

        encoder.setCallback(EncoderCallback(), Handler(encoderThread.looper))
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = encoder.createInputSurface()
        encoder.start()

        cameraHandler.post(::openCamera)
        playbackThread = Thread(::playbackLoop, "playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // ---------------------------------------------------------------- clock

    private fun rawClockUs(): Long =
        if (camera.realtimeTimestamps) SystemClock.elapsedRealtimeNanos() / 1000 else System.nanoTime() / 1000

    private fun clockUs() = rawClockUs() - clockOffsetUs

    // ---------------------------------------------------------------- camera

    @SuppressLint("MissingPermission") // checked by MainActivity before starting
    private fun openCamera() {
        if (!running) {
            cameraClosed.countDown()
            return
        }
        try {
            context.getSystemService(CameraManager::class.java)
                .openCamera(camera.id, cameraExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        if (!running) d.close() else createSession(d)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        if (running) onError("Camera was taken by another app")
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        if (running) onError("Camera error ($error)")
                    }

                    override fun onClosed(d: CameraDevice) {
                        cameraClosed.countDown()
                    }
                })
        } catch (t: Throwable) {
            cameraClosed.countDown()
            onError("Could not open camera: ${t.message}")
        }
    }

    private fun createSession(d: CameraDevice) {
        val gen = ++sessionGen
        val target = preview
        target?.texture?.setDefaultBufferSize(target.size.width, target.size.height)
        val previewSurface = target?.surface
        val outputs = buildList {
            add(OutputConfiguration(encoderSurface))
            // Mirroring is done in the view transform (like the delayed view), so toggling it is instant.
            if (previewSurface != null) {
                add(OutputConfiguration(previewSurface).apply { mirrorMode = OutputConfiguration.MIRROR_MODE_NONE })
            }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running || gen != sessionGen) return
                    session = s
                    request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(encoderSurface)
                        if (previewSurface != null) addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange())
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            if (settings.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                        )
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    }
                    submitRequest()
                    sessionReady?.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != sessionGen) return
                    sessionReady?.countDown()
                    if (previewSurface != null) {
                        // Some size/fps combos can't carry a second stream; drop the preview, keep the delay.
                        preview = null
                        onNotice("Live preview isn't supported at ${width}×$height @ $fps fps")
                        createSession(d)
                    } else {
                        onError("Camera doesn't support ${width}×$height @ $fps fps")
                    }
                }
            },
        )
        try {
            d.createCaptureSession(config)
        } catch (t: Throwable) {
            onError("Camera session failed: ${t.message}")
        }
    }

    private fun pickFpsRange(): Range<Int> {
        val ranges = camera.aeFpsRanges
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps)
    }

    private fun submitRequest() {
        val s = session ?: return
        val r = request ?: return
        try {
            s.setRepeatingRequest(r.build(), null, cameraHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "setRepeatingRequest failed", t)
        }
    }

    /**
     * Adds or removes the live preview output. This rebuilds the capture session (a short gap in
     * the delayed stream), but the buffer is kept. With [wait], blocks until the old session has
     * let go of the previous surface, so it can be released safely.
     */
    fun setPreview(target: PreviewTarget?, wait: Boolean = false) {
        val latch = CountDownLatch(1)
        cameraHandler.post {
            val old = preview
            if (old?.surface == target?.surface && old?.size == target?.size) {
                latch.countDown()
                return@post
            }
            preview = target
            val d = device
            if (d == null || session == null || !running) {
                latch.countDown()
                return@post
            }
            sessionReady = latch
            try {
                session?.stopRepeating()
            } catch (_: Throwable) {
            }
            createSession(d)
        }
        if (wait) latch.await(1, TimeUnit.SECONDS)
    }

    fun setZoom(ratio: Float) {
        cameraHandler.post {
            zoom = ratio.coerceIn(camera.zoomRange.lower, camera.zoomRange.upper)
            request?.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            submitRequest()
        }
    }

    // ---------------------------------------------------------------- encoder → ring

    private inner class EncoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        if (!clockChecked) {
                            clockChecked = true
                            val diff = rawClockUs() - info.presentationTimeUs
                            // Expected: a few tens of ms of encoder latency. Anything else means
                            // another time base; re-anchor so "now" lines up with the first frame.
                            if (diff !in 0..1_000_000) clockOffsetUs = diff
                        }
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        ring.add(buf, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // Codec is being stopped.
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            encoderFormat = format
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (running) onError("Encoder error: ${e.diagnosticInfo}")
        }
    }

    // ---------------------------------------------------------------- ring → decoder → screen

    fun setDelay(ms: Int) {
        val us = ms.coerceIn(0, MAX_DELAY_MS) * 1000L
        if (us != delayUs) {
            delayUs = us
            seekRequested.set(true)
        }
    }

    private fun playbackLoop() {
        var decoder: MediaCodec? = null
        try {
            var format: MediaFormat? = null
            while (running && format == null) {
                format = encoderFormat
                if (format == null) LockSupport.parkNanos(5_000_000)
            }
            if (!running || format == null) return

            val dfmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                for (key in listOf("csd-0", "csd-1", "csd-2")) {
                    format.getByteBuffer(key)?.let { setByteBuffer(key, it) }
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(dfmt, displaySurface, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val meta = PacketRing.Meta()
            var cursor = -1L // next packet to feed
            var pending = -1 // decoded buffer waiting for its display time
            var pendingPts = 0L
            var skipUntil = Long.MIN_VALUE
            var fed = false

            while (running) {
                val target = clockUs() - delayUs // capture time that should be on screen now

                if (seekRequested.getAndSet(false) || cursor < 0 || cursor < ring.oldestSeq) {
                    val seq = ring.keyframeAtOrBefore(target)
                    if (seq < 0) {
                        LockSupport.parkNanos(5_000_000)
                        continue
                    }
                    if (fed) {
                        decoder.flush()
                        pending = -1
                        fed = false
                    }
                    cursor = seq
                    // Frames between the keyframe and the target are decoded but not shown.
                    skipUntil = target - 1_000_000 / fps / 2
                }

                // Feed packets that will be due soon.
                while (true) {
                    val pts = ring.ptsOf(cursor) ?: break
                    if (pts > target + FEED_AHEAD_US) break
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = decoder.getInputBuffer(inIndex)!!
                    buf.clear()
                    if (ring.read(cursor, buf, meta)) {
                        decoder.queueInputBuffer(
                            inIndex, 0, meta.size, meta.ptsUs,
                            if (meta.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        fed = true
                        cursor++
                    } else {
                        // Evicted under us or too big for the codec buffer: hand back an empty buffer and resync.
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        seekRequested.set(true)
                        break
                    }
                }

                // Pull the next decoded frame.
                if (pending < 0) {
                    val outIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (outIndex >= 0) {
                        if (info.presentationTimeUs < skipUntil || info.size == 0 && info.flags != 0) {
                            decoder.releaseOutputBuffer(outIndex, false)
                        } else {
                            pending = outIndex
                            pendingPts = info.presentationTimeUs
                        }
                        continue
                    }
                }

                // Show it when its time comes.
                val now = clockUs() - delayUs
                if (pending >= 0 && pendingPts <= now) {
                    decoder.releaseOutputBuffer(pending, true)
                    shownPtsUs = pendingPts
                    pending = -1
                    continue
                }

                val waitUs = if (pending >= 0) pendingPts - now else 4_000
                LockSupport.parkNanos(waitUs.coerceIn(500, 4_000) * 1000)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback failed", t)
            if (running) onError("Playback failed: ${t.message}")
        } finally {
            try {
                decoder?.stop()
            } catch (_: Throwable) {
            }
            decoder?.release()
        }
    }

    // ---------------------------------------------------------------- recording

    val isRecording get() = recorder != null

    /** Starts recording the live camera stream (not the delayed one). Main thread. */
    fun startRecording(orientationHint: Int) {
        if (recorder != null) return
        val format = encoderFormat ?: throw IllegalStateException("Camera isn't running yet")
        val now = ring.newestPtsUs() ?: throw IllegalStateException("Camera isn't running yet")
        // Ask for a keyframe now so the recording starts immediately, not at the next 1 s boundary.
        try {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
        }
        recorder = Recorder(context, ring, format, orientationHint, startPtsUs = now + 1)
    }

    /**
     * Stops writing and returns the recorder. Call [Recorder.finish] on it (off the main thread)
     * to finalize the file. That step no longer touches the ring, so it can outlive this pipeline.
     */
    fun stopRecording(): Recorder? {
        val r = recorder ?: return null
        recorder = null
        r.stopWriting()
        return r
    }

    fun stats() = Stats(
        showing = shownPtsUs != Long.MIN_VALUE,
        bufferedUs = ring.spanUs(),
        delayUs = delayUs,
        recordingUs = recorder?.durationUs,
        codec = if (mime == Codecs.HEVC) "HEVC" else "H.264",
    )

    // ---------------------------------------------------------------- teardown

    /** Stops everything. Returns an in-progress recording that still needs [Recorder.finish]. Main thread. */
    fun stop(): Recorder? {
        val rec = stopRecording()
        running = false
        cameraHandler.post {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            device?.close() ?: cameraClosed.countDown()
        }
        cameraClosed.await(2, TimeUnit.SECONDS)
        playbackThread.join(2_000)
        try {
            encoder.stop()
        } catch (_: Throwable) {
        }
        encoder.release()
        encoderSurface.release()
        cameraThread.quitSafely()
        encoderThread.quitSafely()
        encoderThread.join(1_000)
        // Only unmap the buffer once nothing can touch it any more.
        if (!playbackThread.isAlive && !encoderThread.isAlive) ring.close()
        return rec
    }

    private companion object {
        const val TAG = "DelayPipeline"
        const val FEED_AHEAD_US = 150_000L
    }
}
