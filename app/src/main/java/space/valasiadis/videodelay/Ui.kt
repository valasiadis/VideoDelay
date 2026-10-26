package space.valasiadis.videodelay

import android.content.Context
import android.util.Range
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import androidx.compose.animation.core.animate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val Scrim = Color(0xFF2A2A2A).copy(alpha = 0.7f) // grey, so pills stay visible over letterbox bars
private val RecordRed = Color(0xFFE53935)

@Composable
fun VideoDelayScreen(
    textureView: TextureView,
    settings: Settings,
    cameras: Map<Facing, CameraOption>,
    stats: DelayPipeline.Stats?,
    error: String?,
    hasPermission: Boolean,
    zoom: Float,
    zoomRange: Range<Float>?,
    contentAspect: Float,
    pictureTop: Float,
    createPreviewView: (Context) -> TextureView,
    onRequestPermission: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onZoomBy: (Float) -> Unit,
    onToggleRecord: () -> Unit,
    onRetry: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val currentZoomBy by rememberUpdatedState(onZoomBy)
    val recording = stats?.recordingUs != null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
        // Black while the buffer (re)fills, instead of the last frame from before.
        if (stats?.showing != true) Box(Modifier.fillMaxSize().background(Color.Black))

        // Pinch to zoom. Note the zoom shows up after the delay, like everything else.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ -> currentZoomBy(zoomChange) }
                },
        )

        if (!hasPermission) {
            CenterMessage("Video Delay needs the camera to work.", "Grant camera access", onRequestPermission)
            return@Box
        }

        var area by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 28.dp)
                .onSizeChanged { area = it },
        ) {
            // Buffer progress, centered on the (black) picture while it fills.
            if (stats != null && stats.bufferedUs + 150_000 < stats.delayUs) {
                BufferingIndicator(stats.bufferedUs, stats.delayUs, Modifier.align(Alignment.Center))
            }

            // Live (undelayed) preview: top right in portrait, top left in landscape, draggable.
            if (settings.showPreview) {
                LivePreview(contentAspect, createPreviewView, area, Modifier.align(Alignment.TopStart))
            }

            // Bottom: [delay]  (record)  [flip][settings], with zoom and REC above the record button.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Sit a little above the controls, slightly into the picture (like Pixel Camera's zoom chip).
                stats?.recordingUs?.let {
                    Pill("● REC  ${clock(it)}", color = RecordRed)
                    Spacer(Modifier.height(8.dp))
                }
                if (zoom != 1f && zoomRange != null) {
                    Pill(String.format(Locale.US, "%.1f×", zoom))
                }
                Spacer(Modifier.height(36.dp))
                // Record button dead center; each side control centered in its half, like Pixel Camera.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        RoundIconButton(R.drawable.ic_settings, "Settings") { showSettings = true }
                    }
                    RecordButton(recording = recording, enabled = stats != null, onClick = onToggleRecord)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (cameras.size > 1) {
                            RoundIconButton(R.drawable.ic_flip_camera, "Switch camera", enabled = !recording) {
                                onSettingsChange(
                                    settings.copy(facing = if (settings.facing == Facing.BACK) Facing.FRONT else Facing.BACK),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Delay control, top left, vertically centered in the black band above the picture.
        val density = LocalDensity.current
        val stepperHeight = with(density) { 40.dp.toPx() }
        val minTop = with(density) { 12.dp.toPx() }
        val stepperTop = maxOf(minTop, (pictureTop - stepperHeight) / 2)
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                .padding(start = 20.dp)
                .offset { IntOffset(0, stepperTop.roundToInt()) },
        ) {
            DelayStepper(
                delayMs = settings.delayMs,
                onChange = { onSettingsChange(settings.copy(delayMs = it)) },
            )
        }

        if (error != null) CenterMessage(error, "Retry", onRetry)

        SettingsPanel(
            visible = showSettings,
            settings = settings,
            cameras = cameras,
            recording = recording,
            formatLabel = stats?.let { "${settings.height}p${settings.fps} · ${it.codec}" },
            onChange = onSettingsChange,
            onDismiss = { showSettings = false },
        )
    }
}

/** Picture-in-picture live view; drag it anywhere inside [area]. */
@Composable
private fun LivePreview(aspect: Float, createView: (Context) -> TextureView, area: IntSize, modifier: Modifier) {
    // Long side 150 dp, short side follows the picture's on-screen aspect ratio.
    val long = 150.dp
    val (w, h) = if (aspect >= 1f) long to long / aspect else long * aspect to long
    val density = LocalDensity.current
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    // Position from the top-left of [area], remembered separately per orientation (NaN = default spot).
    val landscape = area.width > area.height
    var portraitX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var portraitY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var landscapeX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var landscapeY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    val maxX = (area.width - wPx).coerceAtLeast(0f)
    val maxY = (area.height - hPx).coerceAtLeast(0f)
    val x = (if (landscape) landscapeX.orIfNaN(0f) else portraitX.orIfNaN(maxX)).coerceIn(0f, maxX)
    val y = (if (landscape) landscapeY else portraitY).orIfNaN(0f).coerceIn(0f, maxY)
    val currentX by rememberUpdatedState(x)
    val currentY by rememberUpdatedState(y)
    Box(
        modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(w, h)
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
    ) {
        AndroidView(factory = createView, modifier = Modifier.fillMaxSize())
        // Drag handle over the whole preview (the TextureView itself doesn't take touches).
        Box(
            Modifier.fillMaxSize().pointerInput(landscape, maxX, maxY) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val nx = (currentX + drag.x).coerceIn(0f, maxX)
                    val ny = (currentY + drag.y).coerceIn(0f, maxY)
                    if (landscape) {
                        landscapeX = nx
                        landscapeY = ny
                    } else {
                        portraitX = nx
                        portraitY = ny
                    }
                }
            },
        )
        Text(
            "LIVE",
            Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                .background(Scrim).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BufferingIndicator(bufferedUs: Long, delayUs: Long, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Scrim).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Filling buffer", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            String.format(Locale.US, "%.1f / %.1f s", bufferedUs / 1e6, delayUs / 1e6),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (bufferedUs.toFloat() / delayUs).coerceIn(0f, 1f) },
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
private fun DelayStepper(delayMs: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(Scrim).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−") { onChange((delayMs - 1000).coerceAtLeast(0)) }
        Text(
            secs(delayMs * 1000L),
            Modifier.width(60.dp),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        StepButton("+") { onChange((delayMs + 1000).coerceAtMost(MAX_DELAY_MS)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 22.sp) }
}

@Composable
private fun RoundIconButton(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(60.dp).clip(CircleShape).background(Scrim),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(30.dp),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(RecordRed))
        } else {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(if (enabled) RecordRed else Color.Gray))
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = Color.White) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(16.dp)).background(Scrim).padding(horizontal = 12.dp, vertical = 6.dp),
        color = color,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun CenterMessage(text: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

/**
 * Bottom sheet drawn in the same window as the camera view. Material's ModalBottomSheet opens its
 * own window, which fought with the full-screen mode and made swipe-to-close jumpy.
 * Closes by dragging down (handle, or content when scrolled to the top), tapping outside, or back.
 */
@Composable
private fun BoxScope.SettingsPanel(
    visible: Boolean,
    settings: Settings,
    cameras: Map<Facing, CameraOption>,
    recording: Boolean,
    formatLabel: String?,
    onChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val dismissPx = with(density) { 96.dp.toPx() }
        val maxHeight = with(density) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
        // How far the sheet is pulled down, in px. Plain state (not Animatable) so drags apply synchronously.
        var pull by remember { mutableFloatStateOf(0f) }

        fun settle(velocity: Float) {
            if (pull > dismissPx || (velocity > 1200f && pull > 0f)) {
                onDismiss()
            } else {
                scope.launch { animate(pull, 0f) { v, _ -> pull = v } }
            }
        }

        // Lets a downward drag on the content pull the sheet once the content is scrolled to the top.
        val nested = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && pull > 0f) {
                        val used = maxOf(available.y, -pull)
                        pull += used
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        pull += available.y
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull > 0f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Surface(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .offset { IntOffset(0, pull.roundToInt()) }
                .nestedScroll(nested),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp,
        ) {
            Column {
                // Drag handle.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            rememberDraggableState { d -> pull = (pull + d).coerceAtLeast(0f) },
                            Orientation.Vertical,
                            onDragStopped = { v -> settle(v) },
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp, 4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        formatLabel?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SettingsContent(settings, cameras, recording, onChange)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsContent(
    settings: Settings,
    cameras: Map<Facing, CameraOption>,
    recording: Boolean,
    onChange: (Settings) -> Unit,
) {
    val camera = cameras[settings.facing]
    val size = camera?.sizes?.firstOrNull { it.width == settings.width && it.height == settings.height }
    val restartNote = if (recording) " (stops recording)" else ""

    // Delay: applied when the finger lifts, since each change makes the player re-seek.
    Section("Delay") {
        var dragging by remember { mutableStateOf(false) }
        var value by remember { mutableFloatStateOf(settings.delayMs / 1000f) }
        val shown = if (dragging) value else settings.delayMs / 1000f
        Text(String.format(Locale.US, "%.1f s", shown), style = MaterialTheme.typography.titleLarge)
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                dragging = false
                onChange(settings.copy(delayMs = (value * 1000).toInt()))
            },
            valueRange = 0f..(MAX_DELAY_MS / 1000f),
            steps = MAX_DELAY_MS / 500 - 1, // 0.5 s steps
        )
    }

    SwitchRow(
        "Live preview",
        "Small undelayed picture in the corner.",
        checked = settings.showPreview,
    ) { onChange(settings.copy(showPreview = it)) }

    if (cameras.size > 1) {
        Section("Camera$restartNote") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(Facing.BACK, Facing.FRONT).filter { it in cameras }
                options.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = settings.facing == f,
                        onClick = { onChange(settings.copy(facing = f)) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(if (f == Facing.BACK) "Back" else "Front") }
                }
            }
        }
    }

    if (camera != null) {
        Section("Resolution$restartNote") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                camera.sizes.forEach { s ->
                    FilterChip(
                        selected = s.width == settings.width && s.height == settings.height,
                        onClick = { onChange(settings.copy(width = s.width, height = s.height)) },
                        label = { Text("${s.label}  ${s.width}×${s.height}") },
                    )
                }
            }
        }

        if (size != null && size.fpsOptions.size > 1) {
            Section("Frame rate$restartNote") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    size.fpsOptions.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = settings.fps == f,
                            onClick = { onChange(settings.copy(fps = f)) },
                            shape = SegmentedButtonDefaults.itemShape(i, size.fpsOptions.size),
                        ) { Text("$f fps") }
                    }
                }
            }
        }

        val hevcOk = Codecs.supportsHevc(settings.width, settings.height, settings.fps)
        SwitchRow(
            "HEVC (H.265)",
            if (hevcOk) "Smaller files and a cleaner picture. H.264 plays everywhere."
            else "Not available at this resolution.",
            checked = settings.hevc,
            enabled = hevcOk,
        ) { onChange(settings.copy(hevc = it)) }

        if (camera.hasStabilization) {
            SwitchRow(
                "Video stabilization",
                "Steadier handheld picture, slightly narrower view.",
                checked = settings.stabilization,
            ) { onChange(settings.copy(stabilization = it)) }
        }
    }

    if (Facing.FRONT in cameras) {
        SwitchRow(
            "Mirror front camera",
            "Like a mirror. Recordings are never mirrored.",
            checked = settings.mirrorFront,
        ) { onChange(settings.copy(mirrorFront = it)) }
    }

    Text(
        "Recordings capture the live camera (not the delayed picture) and are saved to " +
            "Movies/VideoDelay, video only. Changing camera, resolution, frame rate, codec or " +
            "stabilization restarts the delay buffer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun secs(us: Long) = String.format(Locale.US, "%.1f s", us / 1e6)

private fun clock(us: Long): String {
    val s = us / 1_000_000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun Float.orIfNaN(fallback: Float) = if (isNaN()) fallback else this
