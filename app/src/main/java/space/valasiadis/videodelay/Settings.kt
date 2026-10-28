package space.valasiadis.videodelay

import android.content.Context
import androidx.core.content.edit

const val MAX_DELAY_MS = 30_000
val SLOW_RATES = listOf(0.1f, 0.25f, 0.5f, 0.75f)

data class Settings(
    val facing: Facing = Facing.BACK,
    val delayMs: Int = 5_000,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val stabilization: Boolean = false,
    val mirrorFront: Boolean = true,
    val hevc: Boolean = false,
    val showPreview: Boolean = true,
    /** Replay speed while the picture is held. */
    val slowRate: Float = 0.5f,
) {
    /** Changing any of these needs a camera/encoder restart (which empties the delay buffer). */
    fun captureKey() = listOf(facing, width, height, fps, stabilization, hevc)
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        facing = if (prefs.getString("facing", "BACK") == "FRONT") Facing.FRONT else Facing.BACK,
        delayMs = prefs.getInt("delayMs", 5_000).coerceIn(0, MAX_DELAY_MS),
        width = prefs.getInt("width", 1920),
        height = prefs.getInt("height", 1080),
        fps = prefs.getInt("fps", 30),
        stabilization = prefs.getBoolean("stabilization", false),
        mirrorFront = prefs.getBoolean("mirrorFront", true),
        hevc = prefs.getBoolean("hevc", false),
        showPreview = prefs.getBoolean("showPreview", true),
        slowRate = prefs.getFloat("slowRate", 0.5f).let { if (it in SLOW_RATES) it else 0.5f },
    )

    fun save(s: Settings) = prefs.edit {
        putString("facing", s.facing.name)
        putInt("delayMs", s.delayMs)
        putInt("width", s.width)
        putInt("height", s.height)
        putInt("fps", s.fps)
        putBoolean("stabilization", s.stabilization)
        putBoolean("mirrorFront", s.mirrorFront)
        putBoolean("hevc", s.hevc)
        putBoolean("showPreview", s.showPreview)
        putFloat("slowRate", s.slowRate)
    }
}

/** Snaps the saved settings onto what the chosen camera actually supports. */
fun Settings.resolveFor(camera: CameraOption): Settings {
    val size = camera.sizes.firstOrNull { it.width == width && it.height == height }
        ?: camera.sizes.filter { it.width * it.height <= width * height }.maxByOrNull { it.width * it.height }
        ?: camera.sizes.minBy { it.width * it.height }
    val fps = if (fps in size.fpsOptions) fps else size.fpsOptions.min()
    return copy(
        width = size.width,
        height = size.height,
        fps = fps,
        stabilization = stabilization && camera.hasStabilization,
        hevc = hevc && Codecs.supportsHevc(size.width, size.height, fps),
    )
}
