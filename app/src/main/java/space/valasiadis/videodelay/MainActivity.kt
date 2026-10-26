package space.valasiadis.videodelay

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var cameras: Map<Facing, CameraOption>
    private lateinit var textureView: TextureView

    private var settings by mutableStateOf(Settings())
    private var hasPermission by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var stats by mutableStateOf<DelayPipeline.Stats?>(null)
    private var zoom by mutableFloatStateOf(1f)
    /** Width/height of the picture as shown on screen; the live preview box uses it. */
    private var contentAspect by mutableFloatStateOf(9f / 16f)

    private var pipeline: DelayPipeline? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var started = false

    // Live preview (picture-in-picture). Its view comes and goes with the setting.
    private var previewView: TextureView? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var previewSize: android.util.Size? = null

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            previewTexture = st
            attachPreview()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            // TextureView resets the buffer size to its own size here; put the camera size back.
            previewSize?.let { st.setDefaultBufferSize(it.width, it.height) }
            updatePreviewTransform()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            // Make the camera stop drawing into it before it goes away.
            pipeline?.setPreview(null, wait = true)
            previewSurface?.release()
            previewSurface = null
            previewTexture = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }
    private var deviceOrientation = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        maybeStart()
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = (o + 45) / 90 * 90 % 360
            }
        }
    }

    // Catches 180° flips, which don't trigger a configuration change.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) = updateTransform() // also updates the preview
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = SettingsStore(this)
        cameras = try {
            CameraCatalog.load(this)
        } catch (t: Throwable) {
            emptyMap()
        }
        settings = store.load().let { s -> cameras[s.facing]?.let(s::resolveFor) ?: s }
        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    this@MainActivity.surfaceTexture = st
                    maybeStart()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = updateTransform()

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    stopPipeline()
                    this@MainActivity.surfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }
        }

        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(this)) {
                LaunchedEffect(Unit) {
                    while (true) {
                        stats = pipeline?.stats()
                        delay(200)
                    }
                }
                VideoDelayScreen(
                    textureView = textureView,
                    settings = settings,
                    cameras = cameras,
                    stats = stats,
                    error = error,
                    hasPermission = hasPermission,
                    zoom = zoom,
                    zoomRange = cameras[settings.facing]?.zoomRange,
                    contentAspect = contentAspect,
                    createPreviewView = ::createPreviewView,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onSettingsChange = ::applySettings,
                    onZoomBy = ::zoomBy,
                    onToggleRecord = ::toggleRecording,
                    onRetry = ::restart,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        orientationListener.enable()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        maybeStart()
    }

    override fun onStop() {
        started = false
        orientationListener.disable()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        stopPipeline()
        super.onStop()
    }

    // ------------------------------------------------------------ pipeline lifecycle

    private fun maybeStart() {
        if (!started || !hasPermission || pipeline != null) return
        val st = surfaceTexture ?: return
        val camera = cameras[settings.facing] ?: cameras.values.firstOrNull()
        if (camera == null) {
            error = "No usable camera found"
            return
        }
        val s = settings.copy(facing = camera.facing).resolveFor(camera)
        if (s != settings) settings = s

        error = null
        val surf = Surface(st)
        surface = surf
        pipeline = try {
            DelayPipeline(
                this, camera, s, surf, s.delayMs, zoom,
                onError = { msg -> runOnUiThread { error = msg } },
                onNotice = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } },
            )
        } catch (t: Throwable) {
            error = "Could not start: ${t.message}"
            surf.release()
            surface = null
            null
        }
        updateTransform()
        attachPreview()
    }

    private fun createPreviewView(context: android.content.Context) = TextureView(context).apply {
        surfaceTextureListener = previewListener
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePreviewTransform() }
        previewView = this
    }

    /** Hands the preview surface (sized for the current video aspect) to the running pipeline. */
    private fun attachPreview() {
        val p = pipeline ?: return
        val st = previewTexture ?: return
        val camera = cameras[settings.facing] ?: return
        val size = camera.previewSizeFor(settings.width, settings.height)
        previewSize = size
        st.setDefaultBufferSize(size.width, size.height)
        val surf = previewSurface ?: Surface(st).also { previewSurface = it }
        p.setPreview(DelayPipeline.PreviewTarget(surf, st, size))
        updatePreviewTransform()
    }

    private fun stopPipeline() {
        val p = pipeline ?: return
        pipeline = null
        finishRecording(p.stop())
        surface?.release()
        surface = null
        stats = null
    }

    private fun restart() {
        stopPipeline()
        maybeStart()
    }

    private fun applySettings(new: Settings) {
        val old = settings
        val camera = cameras[new.facing]
        val resolved = camera?.let(new::resolveFor) ?: new
        // Keep the user's codec choice even if this size can't do HEVC.
        settings = resolved
        store.save(new.copy(width = resolved.width, height = resolved.height, fps = resolved.fps))
        if (old.captureKey() != resolved.captureKey()) {
            if (old.facing != resolved.facing) zoom = 1f
            restart()
        } else {
            pipeline?.setDelay(resolved.delayMs)
            updateTransform()
        }
    }

    private fun zoomBy(factor: Float) {
        val range = cameras[settings.facing]?.zoomRange ?: return
        val z = (zoom * factor).coerceIn(range.lower, range.upper)
        if (z != zoom) {
            zoom = z
            pipeline?.setZoom(z)
        }
    }

    // ------------------------------------------------------------ recording

    private fun toggleRecording() {
        val p = pipeline ?: return
        if (p.isRecording) {
            finishRecording(p.stopRecording())
            stats = p.stats()
            return
        }
        val camera = cameras[settings.facing] ?: return
        // Standard JPEG/MP4 orientation formula: sensor orientation corrected by how the phone is held.
        val dev = if (camera.facing == Facing.FRONT) -deviceOrientation else deviceOrientation
        val hint = (camera.sensorOrientation + dev + 360) % 360
        try {
            p.startRecording(hint)
            stats = p.stats()
        } catch (t: Throwable) {
            Toast.makeText(this, "Can't record: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishRecording(rec: Recorder?) {
        rec ?: return
        Thread {
            val uri = try {
                rec.finish()
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (uri != null) "Saved to Movies/VideoDelay" else "Recording was empty, nothing saved",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    // ------------------------------------------------------------ preview geometry

    /**
     * The encoded frames are in sensor orientation (landscape on Pixels). Rotate them upright for the
     * current display rotation, letterbox to fit, and mirror for the front camera.
     */
    private fun updateTransform() {
        val camera = cameras[settings.facing] ?: return
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val w = settings.width.toFloat()
        val h = settings.height.toFloat()

        val displayDeg = (display?.rotation ?: Surface.ROTATION_0) * 90
        // Back cameras subtract the display rotation, front cameras add it (checked on a Pixel 10 Pro XL).
        val rotation = if (camera.facing == Facing.FRONT) {
            (camera.sensorOrientation + displayDeg) % 360
        } else {
            (camera.sensorOrientation - displayDeg + 360) % 360
        }
        val (cw, ch) = if (rotation % 180 == 0) w to h else h to w
        val cx = vw / 2
        val cy = vh / 2
        val scale = minOf(vw / cw, vh / ch)

        val m = Matrix()
        m.setScale(w / vw, h / vh, cx, cy) // undo TextureView's stretch-to-fill
        m.postRotate(rotation.toFloat(), cx, cy)
        m.postScale(scale, scale, cx, cy)
        if (camera.facing == Facing.FRONT && settings.mirrorFront) m.postScale(-1f, 1f, cx, cy)
        textureView.setTransform(m)
        contentAspect = cw / ch
        updatePreviewTransform()
    }

    /**
     * Camera preview buffers arrive already rotated upright for the phone's natural (portrait)
     * orientation, so only the display rotation is left to undo. Mirroring is off at the camera
     * and done here, same as the delayed view.
     */
    private fun updatePreviewTransform() {
        val view = previewView ?: return
        val camera = cameras[settings.facing] ?: return
        val size = previewSize ?: return
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw == 0f || vh == 0f) return

        val (nw, nh) = if (camera.sensorOrientation % 180 == 90) {
            size.height.toFloat() to size.width.toFloat()
        } else {
            size.width.toFloat() to size.height.toFloat()
        }
        val displayDeg = (display?.rotation ?: Surface.ROTATION_0) * 90
        val rotation = displayDeg
        val (cw, ch) = if (rotation % 180 == 0) nw to nh else nh to nw
        val cx = vw / 2
        val cy = vh / 2
        val scale = maxOf(vw / cw, vh / ch) // fill the small box

        val m = Matrix()
        m.setScale(nw / vw, nh / vh, cx, cy)
        m.postRotate(rotation.toFloat(), cx, cy)
        m.postScale(scale, scale, cx, cy)
        if (camera.facing == Facing.FRONT && settings.mirrorFront) m.postScale(-1f, 1f, cx, cy)
        view.setTransform(m)
    }
}
