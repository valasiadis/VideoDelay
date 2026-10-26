package space.valasiadis.videodelay

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size

enum class Facing { BACK, FRONT }

data class VideoSize(val width: Int, val height: Int, val fpsOptions: List<Int>) {
    val label: String
        get() = when (height) {
            2160 -> "4K"
            1440 -> if (width == 2560) "1440p" else "1440p 4:3"
            1080 -> if (width == 1920) "1080p" else "1080p 4:3"
            960 -> "960p 4:3"
            720 -> if (width == 1280) "720p" else "720p 4:3"
            480 -> "480p 4:3"
            else -> "${height}p"
        }
}

data class CameraOption(
    val id: String,
    val facing: Facing,
    val sensorOrientation: Int,
    val sizes: List<VideoSize>,
    val hasStabilization: Boolean,
    val zoomRange: Range<Float>,
    val aeFpsRanges: List<Range<Int>>,
    /** True if sensor timestamps use the elapsedRealtime clock (Pixels do). */
    val realtimeTimestamps: Boolean,
    private val previewSizes: List<Size>,
) {
    /** A small stream for the live preview, same aspect ratio as the video. */
    fun previewSizeFor(videoW: Int, videoH: Int): Size {
        val aspect = videoW.toFloat() / videoH
        val sameAspect = previewSizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - aspect) < 0.02f }
        return sameAspect.filter { it.width <= 1280 }.maxByOrNull { it.width }
            ?: sameAspect.minByOrNull { it.width }
            ?: Size(videoW, videoH)
    }
}

object CameraCatalog {

    // Sizes worth offering; anything else the camera reports is noise for this use case.
    private val CANDIDATES = listOf(
        3840 to 2160, 2560 to 1440, 1920 to 1440, 1920 to 1080,
        1440 to 1080, 1280 to 960, 1280 to 720, 960 to 720, 640 to 480,
    )
    private val FPS_CANDIDATES = listOf(30, 60)

    fun load(context: Context): Map<Facing, CameraOption> {
        val manager = context.getSystemService(CameraManager::class.java)
        val result = mutableMapOf<Facing, CameraOption>()
        // The first camera of each facing is the logical multi-camera on Pixels
        // (wide + ultrawide + tele behind one ID, switched via zoom ratio).
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> Facing.BACK
                CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
                else -> continue
            }
            if (facing in result) continue
            val option = describe(id, facing, c) ?: continue
            result[facing] = option
        }
        return result
    }

    private fun describe(id: String, facing: Facing, c: CameraCharacteristics): CameraOption? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val available = map.getOutputSizes(MediaCodec::class.java)?.toSet() ?: return null
        val aeRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()

        val sizes = CANDIDATES.mapNotNull { (w, h) ->
            if (Size(w, h) !in available) return@mapNotNull null
            val minFrameNs = map.getOutputMinFrameDuration(MediaCodec::class.java, Size(w, h))
            val fps = FPS_CANDIDATES.filter { f ->
                (minFrameNs == 0L || minFrameNs <= 1_000_000_000L / f + 100_000) &&
                    aeRanges.any { it.upper >= f } &&
                    Codecs.anyEncoderSupports(w, h, f)
            }
            if (fps.isEmpty()) null else VideoSize(w, h, fps)
        }
        if (sizes.isEmpty()) return null

        val stabModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        return CameraOption(
            id = id,
            facing = facing,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            sizes = sizes,
            hasStabilization = stabModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f),
            aeFpsRanges = aeRanges,
            realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty(),
        )
    }
}

object Codecs {
    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    private val infos: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && !it.isAlias }
    }

    /** Best encoder for the format, preferring hardware. */
    fun findEncoder(mime: String, w: Int, h: Int, fps: Int): MediaCodecInfo? {
        val candidates = infos.filter { info ->
            mime in info.supportedTypes.map { it.lowercase() } &&
                info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true
        }
        return candidates.firstOrNull { it.isHardwareAccelerated } ?: candidates.firstOrNull()
    }

    fun anyEncoderSupports(w: Int, h: Int, fps: Int) =
        findEncoder(AVC, w, h, fps) != null || findEncoder(HEVC, w, h, fps) != null

    fun supportsHevc(w: Int, h: Int, fps: Int) = findEncoder(HEVC, w, h, fps) != null

    /** Bits per second: generous enough that the delayed picture and recordings look clean. */
    fun bitrateFor(mime: String, w: Int, h: Int, fps: Int): Int {
        val bitsPerPixel = if (mime == HEVC) 0.13 else 0.2
        val fpsFactor = Math.pow(fps / 30.0, 0.6) // 60 fps doesn't need twice the bits
        return (w.toLong() * h * 30 * bitsPerPixel * fpsFactor).toInt()
    }
}
