package ru.inikonoff.zerno.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.BuildConfig
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Metering
import ru.inikonoff.zerno.adapter.Settings
import ru.inikonoff.zerno.adapter.UiState
import kotlin.math.roundToInt

private val Background = Color(0xFF0E0E10)
private val Card = Color(0xFF1C1C1E)
private val Track = Color(0x33FFFFFF)
private val Title = TextStyle(color = Color.White, fontSize = 16.sp)
private val Hint = TextStyle(color = Color(0x99FFFFFF), fontSize = 13.sp, lineHeight = 18.sp)

private const val OPEN_CAMERA_URL = "https://opencamera.org.uk/"
private const val REPOSITORY_URL = "https://github.com/inikonoff/Zerno-Camera"
private const val LICENCE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** Zerno's own short settings screen. The full Open Camera settings are behind "Advanced". */
@Composable
fun SettingsScreen(state: UiState, adapter: CameraAdapter) {
    val settings = state.settings
    val context = LocalContext.current
    BackHandler { adapter.showSettings(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .tap {} // swallow touches: the camera preview is underneath
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.zerno_settings),
                modifier = Modifier.weight(1f),
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            Box(Modifier.size(48.dp).tap { adapter.showSettings(false) }, contentAlignment = Alignment.Center) {
                ZIcon(ZernoIcon.CLOSE, Color.White, Modifier.size(22.dp))
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.zerno_metering)) {
                Segmented(
                    options = listOf(
                        Metering.SPOT to stringResource(R.string.zerno_metering_spot),
                        Metering.CENTRE to stringResource(R.string.zerno_metering_centre),
                        Metering.MATRIX to stringResource(R.string.zerno_metering_matrix),
                    ),
                    selected = settings.metering,
                    onSelect = adapter::setMetering,
                )
                BasicText(
                    stringResource(
                        when (settings.metering) {
                            Metering.SPOT -> R.string.zerno_metering_spot_hint
                            Metering.CENTRE -> R.string.zerno_metering_centre_hint
                            Metering.MATRIX -> R.string.zerno_metering_matrix_hint
                        }
                    ),
                    modifier = Modifier.padding(top = 10.dp),
                    style = Hint,
                )
            }

            Section(stringResource(R.string.zerno_chroma)) {
                Segmented(
                    options = listOf(
                        0 to stringResource(R.string.zerno_chroma_off),
                        1 to stringResource(R.string.zerno_chroma_light),
                        2 to stringResource(R.string.zerno_chroma_medium),
                        3 to stringResource(R.string.zerno_chroma_strong),
                    ),
                    selected = settings.chromaDenoise,
                    onSelect = adapter::setChromaDenoise,
                )
                BasicText(stringResource(R.string.zerno_chroma_hint), modifier = Modifier.padding(top = 10.dp), style = Hint)
            }

            Section(stringResource(R.string.zerno_image)) {
                if (state.caps.toneCurve) {
                    Adjustment(stringResource(R.string.zerno_shadows), settings.shadows) { adapter.setTone(it, settings.highlights) }
                    Adjustment(stringResource(R.string.zerno_highlights), settings.highlights) { adapter.setTone(settings.shadows, it) }
                }
                Adjustment(stringResource(R.string.zerno_brightness), settings.brightness) {
                    adapter.setImageAdjustment(it, settings.contrast, settings.saturation)
                }
                Adjustment(stringResource(R.string.zerno_contrast), settings.contrast) {
                    adapter.setImageAdjustment(settings.brightness, it, settings.saturation)
                }
                Adjustment(stringResource(R.string.zerno_saturation), settings.saturation) {
                    adapter.setImageAdjustment(settings.brightness, settings.contrast, it)
                }
                if (state.caps.toneCurve) {
                    LinkRow(stringResource(R.string.zerno_tone_calibrate), stringResource(R.string.zerno_tone_calibrate_hint)) {
                        adapter.recalibrateTone()
                        adapter.showSettings(false)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.zerno_image_hint), modifier = Modifier.weight(1f), style = Hint)
                    if (settings.brightness != 0 || settings.contrast != 0 || settings.saturation != 0 || settings.shadows != 0 || settings.highlights != 0) {
                        Spacer(Modifier.width(10.dp))
                        Pill(stringResource(R.string.zerno_reset), selected = false) { adapter.setImageAdjustment(0, 0, 0); adapter.setTone(0, 0) }
                    }
                }
            }

            Section(stringResource(R.string.zerno_shooting)) {
                ToggleRow(stringResource(R.string.zerno_qr_setting), settings.qrEnabled, adapter::setQrEnabled)
                ToggleRow(stringResource(R.string.zerno_shutter_sound), settings.shutterSound, adapter::setShutterSound)
                ToggleRow(stringResource(R.string.zerno_mirror_front), settings.mirrorFront, adapter::setMirrorFront)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.zerno_jpeg_quality), modifier = Modifier.weight(1f), style = Title)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // a value set in the Open Camera settings that isn't one of ours is shown too
                        for (quality in (Settings.JPEG_QUALITIES + settings.jpegQuality).distinct().sorted()) {
                            Pill("$quality", selected = quality == settings.jpegQuality) { adapter.setJpegQuality(quality) }
                        }
                    }
                }
            }

            Section(null) {
                LinkRow(stringResource(R.string.zerno_trash), stringResource(R.string.zerno_trash_hint)) { adapter.openTrash() }
                LinkRow(stringResource(R.string.zerno_advanced), stringResource(R.string.zerno_advanced_hint)) { adapter.openAdvancedSettings() }
                LinkRow(stringResource(R.string.zerno_diagnostics), stringResource(R.string.zerno_diagnostics_hint)) { adapter.showDiagnostics(true) }
            }

            Section(stringResource(R.string.zerno_about)) {
                BasicText("Zerno ${BuildConfig.VERSION_NAME}", style = Title.copy(fontWeight = FontWeight.SemiBold))
                BasicText(stringResource(R.string.zerno_about_text), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp), style = Hint)
                LinkRow("Open Camera", OPEN_CAMERA_URL.removePrefix("https://")) { open(context, OPEN_CAMERA_URL) }
                LinkRow(stringResource(R.string.zerno_about_source), REPOSITORY_URL.removePrefix("https://")) { open(context, REPOSITORY_URL) }
                LinkRow(stringResource(R.string.zerno_about_licence), "GNU GPL v3") { open(context, LICENCE_URL) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column {
        if (title != null) {
            BasicText(
                title.uppercase(),
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 4.dp),
                style = TextStyle(color = ZernoColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
            content()
        }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Track).padding(3.dp)) {
        for ((value, name) in options) {
            val isSelected = value == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50))
                    .background(if (isSelected) ZernoColors.Accent else Color.Transparent)
                    .tap(role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    name, maxLines = 1,
                    style = TextStyle(color = if (isSelected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) ZernoColors.Accent else Track).tap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(text, maxLines = 1, style = TextStyle(color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

/** A slider from -[Settings.ADJUST_RANGE] to +[Settings.ADJUST_RANGE], with a mark at zero. */
@Composable
private fun Adjustment(name: String, value: Int, onChange: (Int) -> Unit) {
    val range = Settings.ADJUST_RANGE
    val currentOnChange by rememberUpdatedState(onChange)
    val currentValue by rememberUpdatedState(value)
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(name, modifier = Modifier.width(118.dp), maxLines = 1, style = Title)
        Canvas(
            Modifier
                .weight(1f)
                .height(46.dp)
                .pointerInput(Unit) {
                    fun pick(x: Float) {
                        val inset = 12.dp.toPx()
                        val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                        val picked = (fraction * 2 * range).roundToInt() - range
                        if (picked != currentValue) currentOnChange(picked)
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(Unit) {
                    fun pick(x: Float) {
                        val inset = 12.dp.toPx()
                        val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                        val picked = (fraction * 2 * range).roundToInt() - range
                        if (picked != currentValue) currentOnChange(picked)
                    }
                    detectDragGestures(onDragStart = { pick(it.x) }) { change, _ ->
                        change.consume()
                        pick(change.position.x)
                    }
                }
        ) {
            val inset = 12.dp.toPx()
            val y = size.height / 2f
            val width = size.width - 2 * inset
            val zeroX = inset + width / 2f
            val thumbX = inset + width * (value + range) / (2f * range)
            drawLine(Track, Offset(inset, y), Offset(inset + width, y), 4.dp.toPx(), StrokeCap.Round)
            // the filled part runs from zero to the thumb
            drawLine(ZernoColors.Accent, Offset(zeroX, y), Offset(thumbX, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0x99FFFFFF), Offset(zeroX, y - 6.dp.toPx()), Offset(zeroX, y + 6.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 9.dp.toPx(), Offset(thumbX, y))
        }
        BasicText(
            if (value > 0) "+$value" else if (value < 0) "−${-value}" else "0",
            modifier = Modifier.width(30.dp),
            style = TextStyle(color = if (value == 0) Color(0x99FFFFFF) else ZernoColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ToggleRow(name: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val thumb by animateFloatAsState(if (checked) 1f else 0f, label = "toggle")
    Row(
        Modifier.fillMaxWidth().height(48.dp).tap(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(name, modifier = Modifier.weight(1f), style = Title)
        Box(
            Modifier.size(width = 46.dp, height = 26.dp).clip(CircleShape).background(if (checked) ZernoColors.Accent else Track).padding(3.dp)
        ) {
            Box(
                Modifier.padding(start = 20.dp * thumb).size(20.dp).clip(CircleShape).background(Color.White)
            )
        }
    }
}

@Composable
private fun LinkRow(name: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(name, style = Title)
            BasicText(detail, style = Hint)
        }
        BasicText("›", style = TextStyle(color = Color(0x99FFFFFF), fontSize = 22.sp))
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // no browser
    }
}
