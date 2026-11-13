package app.msplayer.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** Transactional editor: only OK applies settings to the renderer. */
@Composable
fun SubtitleTextDialog(initial: SubtitleStyle, cancel: () -> Unit, apply: (SubtitleStyle) -> Unit) {
    val context = LocalContext.current
    val glassEnabled by rememberGlassEnabled()
    var draft by remember { mutableStateOf(initial) }
    var savedShadow by remember { mutableStateOf(initial.shadow.takeIf { it > 0 } ?: 140) }
    var savedBorder by remember { mutableStateOf(initial.outline.takeIf { it > 0 } ?: 2) }
    var bookmarks by remember { mutableStateOf(SubtitleStyle.bookmarkedPresets(context)) }
    var showSaveBookmark by remember { mutableStateOf(false) }
    var bookmarkNameInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersivePlayerDialog()
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Color(0xFF131A26), onSurface = Color.White)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                val maxH = (maxHeight - 12.dp).coerceAtLeast(240.dp)
                Column(
                    Modifier
                        .widthIn(max = 500.dp)
                        .fillMaxWidth(.94f)
                        .heightIn(max = maxH)
                        .testTag("subtitle-text-dialog")
                        .frostedGlassChrome(
                            enabled = glassEnabled,
                            shape = RoundedCornerShape(24.dp),
                            tint = Color(0x940B131F),
                            fallbackColor = Color(0xFF1A202A),
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Subtitle Text", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "\u2605 Save Style",
                            color = Accent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Accent.copy(alpha = 0.14f))
                                .clickable {
                                    bookmarkNameInput = "Custom ${bookmarks.size + 1}"
                                    showSaveBookmark = true
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp)
                    ) {
                        Text("Quick Presets", color = Muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SubtitleStyle.quickPresets.forEach { preset ->
                                val active = draft == preset.style
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (active) Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                        .border(1.dp, if (active) Accent else Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                        .clickable { draft = preset.style }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(preset.name, color = if (active) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            bookmarks.forEach { savedPreset ->
                                val active = draft == savedPreset.style
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (active) Accent.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                                        .border(1.dp, if (active) Accent else Color(0x40A8D8FF), RoundedCornerShape(12.dp))
                                        .clickable { draft = savedPreset.style }
                                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("\u2605 ${savedPreset.name}", color = if (active) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "\u00D7",
                                        color = Muted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable {
                                                SubtitleStyle.deleteBookmarkedPreset(context, savedPreset.name)
                                                bookmarks = SubtitleStyle.bookmarkedPresets(context)
                                            }
                                            .padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        SubtitleFontPicker(draft) { draft = it }
                        SubtitleValue("Size", draft.size, 10..60) { draft = draft.copy(size = it) }
                        SubtitleValue("Scale", draft.scale, 50..300, "%") { draft = draft.copy(scale = it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Color", color = Color.White, modifier = Modifier.weight(1f))
                            Checkbox(draft.bold, { draft = draft.copy(bold = it) })
                            Text("Bold", color = Color.White, modifier = Modifier.clickable { draft = draft.copy(bold = !draft.bold) })
                        }
                        SubtitleColors(draft.color) { draft = draft.copy(color = it) }
                        SubtitleToggle("Background", draft.background) { draft = draft.copy(background = it) }
                        if (draft.background) {
                            SubtitleColors(draft.backgroundColor) { draft = draft.copy(backgroundColor = it) }
                            SubtitleValue("Opacity", draft.backgroundOpacity, 0..255) { draft = draft.copy(backgroundOpacity = it) }
                        }
                        SubtitleToggle("Border", draft.outline > 0) {
                            if (!it) savedBorder = draft.outline
                            draft = draft.copy(outline = if (it) savedBorder else 0)
                        }
                        if (draft.outline > 0) {
                            SubtitleColors(draft.borderColor) { draft = draft.copy(borderColor = it) }
                            SubtitleValue("Border opacity", draft.borderOpacity, 0..255) { draft = draft.copy(borderOpacity = it) }
                            SubtitleValue("Border width", draft.outline, 1..6) { draft = draft.copy(outline = it) }
                        }
                        SubtitleToggle("Shadow", draft.shadow > 0) {
                            if (!it) savedShadow = draft.shadow
                            draft = draft.copy(shadow = if (it) savedShadow else 0)
                        }
                        if (draft.shadow > 0) {
                            SubtitleValue("Shadow opacity", draft.shadow, 0..255) { draft = draft.copy(shadow = it) }
                            SubtitleValue("Shadow offset", draft.shadowOffset, 0..10, "px") { draft = draft.copy(shadowOffset = it) }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = .15f))
                        Text("Layout & Dual Track", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        SubtitleValue("Line spacing", draft.lineSpacing, 80..200, "%") { draft = draft.copy(lineSpacing = it) }
                        SubtitleValue("Bottom margin", draft.bottomMargin, 0..40, "%") { draft = draft.copy(bottomMargin = it, legacyMargin = null) }
                        SubtitleToggle("Top secondary track", draft.secondaryTop) { draft = draft.copy(secondaryTop = it) }
                        Spacer(Modifier.height(4.dp))
                        Text("Secondary track color", color = Color.White, fontSize = 13.5.sp)
                        Spacer(Modifier.height(4.dp))
                        SubtitleColors(draft.secondaryColor) { draft = draft.copy(secondaryColor = it) }
                        Spacer(Modifier.height(4.dp))
                        SubtitleValue("Secondary scale", draft.secondaryScale, 50..150, "%") { draft = draft.copy(secondaryScale = it) }
                        SubtitleEncodingPicker(draft.encoding) { draft = draft.copy(encoding = it) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draft = SubtitleStyle() }, modifier = Modifier.testTag("subtitle-reset")) { Text("RESET", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = cancel, modifier = Modifier.testTag("subtitle-cancel")) { Text("CANCEL", color = Muted) }
                        TextButton(onClick = { apply(draft) }, modifier = Modifier.testTag("subtitle-ok")) { Text("OK", color = Accent, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (showSaveBookmark) {
        AlertDialog(
            onDismissRequest = { showSaveBookmark = false },
            title = { Text("Save Subtitle Style") },
            text = {
                OutlinedTextField(
                    value = bookmarkNameInput,
                    onValueChange = { bookmarkNameInput = it },
                    singleLine = true,
                    label = { Text("Preset name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = bookmarkNameInput.trim()
                        if (clean.isNotEmpty()) {
                            SubtitleStyle.saveBookmarkedPreset(context, clean, draft)
                            bookmarks = SubtitleStyle.bookmarkedPresets(context)
                        }
                        showSaveBookmark = false
                    },
                ) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveBookmark = false }) { Text("CANCEL") }
            },
        )
    }
}

@Composable
private fun SubtitleFontPicker(style: SubtitleStyle, change: (SubtitleStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Font: ${style.font}", color = Accent) }
        LiquidGlassPopupMenu(expanded = open, onDismissRequest = { open = false }, forceDark = true) {
            SubtitleFonts.choices().forEach { font ->
                LiquidGlassPopupMenuItem(
                    text = { Text(font.label + if (font.available) "" else " (N/A)") },
                    enabled = font.available,
                    onClick = {
                        change(style.copy(font = font.label, bold = style.bold || font.forceBold, legacyFontFamily = null))
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SubtitleToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Checkbox(checked, change)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleValue(label: String, value: Int, range: IntRange, suffix: String = "", change: (Int) -> Unit) {
    val display = if (label.contains("opacity", ignoreCase = true)) "${(value * 100f / 255).roundToInt()}%" else "$value$suffix"
    Text("$label  $display", color = Color.White, fontSize = 13.5.sp)
    Slider(
        value = value.coerceIn(range).toFloat(),
        onValueChange = { change(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.height(34.dp),
        thumb = { Box(Modifier.size(14.dp).background(Accent, CircleShape)) },
        track = { SliderDefaults.Track(it, modifier = Modifier.height(3.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) }
    )
}

@Composable
private fun SubtitleColors(selected: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0xFFFFFF, 0x000000, 0xFFFF00, 0xFFCC00, 0x88CCFF, 0x00FFFF, 0x00FF00, 0xFF5555).forEach { rgb ->
            Box(
                Modifier.weight(1f).height(34.dp).background(Color(0xFF000000.toInt() or rgb), RoundedCornerShape(6.dp))
                    .clickable { change(rgb) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected == rgb) Text("\u2713", color = if (rgb == 0) Color.White else Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SubtitleEncodingPicker(value: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Encoding: ${SubtitleStyle.encodings.entries.firstOrNull { it.value == value }?.key ?: "Auto"}", color = Accent)
        }
        LiquidGlassPopupMenu(expanded = open, onDismissRequest = { open = false }, forceDark = true) {
            SubtitleStyle.encodings.forEach { (label, encoding) ->
                LiquidGlassPopupMenuItem(text = { Text(label) }, onClick = { change(encoding); open = false })
            }
        }
    }
}
