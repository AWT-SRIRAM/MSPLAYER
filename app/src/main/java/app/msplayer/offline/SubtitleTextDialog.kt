package app.msplayer.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var draft by remember { mutableStateOf(initial) }
    var savedShadow by remember { mutableStateOf(initial.shadow.takeIf {it>0} ?: 140) }
    var savedBorder by remember { mutableStateOf(initial.outline.takeIf {it>0} ?: 2) }
    Dialog(onDismissRequest=cancel, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF00A5F5),surface=Color(0xFF2B2B2B),onSurface=Color.White)) {
        Surface(Modifier.widthIn(max=520.dp).fillMaxWidth(.92f).heightIn(max=620.dp).testTag("subtitle-text-dialog"),
            shape=RoundedCornerShape(4.dp),color=Color(0xFF2B2B2B),contentColor=Color.White) {
            Column(Modifier.padding(20.dp)) {
                Text("Text",fontSize=22.sp,fontWeight=FontWeight.Medium)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(top=12.dp)) {
                    SubtitleFontPicker(draft) {draft=it}
                    SubtitleValue("Size",draft.size,10..60) {draft=draft.copy(size=it)}
                    SubtitleValue("Scale",draft.scale,50..300,"%") {draft=draft.copy(scale=it)}
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Color",Modifier.weight(1f));Checkbox(draft.bold,{draft=draft.copy(bold=it)})
                        Text("Bold",Modifier.clickable {draft=draft.copy(bold=!draft.bold)})
                    }
                    SubtitleColors(draft.color) {draft=draft.copy(color=it)}
                    SubtitleToggle("Background",draft.background) {draft=draft.copy(background=it)}
                    if(draft.background) {
                        SubtitleColors(draft.backgroundColor) {draft=draft.copy(backgroundColor=it)}
                        SubtitleValue("Opacity",draft.backgroundOpacity,0..255) {draft=draft.copy(backgroundOpacity=it)}
                    }
                    SubtitleToggle("Border",draft.outline>0) {if(!it) savedBorder=draft.outline;draft=draft.copy(outline=if(it) savedBorder else 0)}
                    if(draft.outline>0) {
                        SubtitleColors(draft.borderColor) {draft=draft.copy(borderColor=it)}
                        SubtitleValue("Border opacity",draft.borderOpacity,0..255) {draft=draft.copy(borderOpacity=it)}
                        SubtitleValue("Border width",draft.outline,1..6) {draft=draft.copy(outline=it)}
                    }
                    SubtitleToggle("Shadow",draft.shadow>0) {if(!it) savedShadow=draft.shadow;draft=draft.copy(shadow=if(it) savedShadow else 0)}
                    if(draft.shadow>0) SubtitleValue("Shadow opacity",draft.shadow,0..255) {draft=draft.copy(shadow=it)}
                    HorizontalDivider(Modifier.padding(vertical=8.dp),color=Color.White.copy(alpha=.15f))
                    Text("Layout",fontWeight=FontWeight.Medium)
                    SubtitleValue("Bottom clearance",draft.bottomMargin,0..40,"%") {draft=draft.copy(bottomMargin=it,legacyMargin=null)}
                    SubtitleEncodingPicker(draft.encoding) {draft=draft.copy(encoding=it)}
                    
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={draft=SubtitleStyle()},modifier=Modifier.testTag("subtitle-reset")) {Text("RESET")}
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick=cancel,modifier=Modifier.testTag("subtitle-cancel")) {Text("CANCEL")}
                    TextButton(onClick={apply(draft)},modifier=Modifier.testTag("subtitle-ok")) {Text("OK")}
                }
            }
        }
        }
    }
}
@Composable private fun SubtitleFontPicker(style:SubtitleStyle,change:(SubtitleStyle)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {
        TextButton(onClick={open=true}) {Text("Font: ${style.font}")}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            SubtitleFonts.choices().forEach {font-> DropdownMenuItem(
                text={Text(font.label+if(font.available) "" else " (unavailable)")},enabled=font.available,
                onClick={change(style.copy(font=font.label,bold=style.bold||font.forceBold,legacyFontFamily=null));open=false}) }
        }
    }
}
@Composable private fun SubtitleToggle(label:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable {change(!checked)},verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f));Checkbox(checked,change)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SubtitleValue(label:String,value:Int,range:IntRange,suffix:String="",change:(Int)->Unit) {
    val display=if(label.contains("opacity",ignoreCase=true)) "${(value*100f/255).roundToInt()}%" else "$value$suffix"
    Text("$label  $display",fontSize=14.sp)
    Slider(value=value.coerceIn(range).toFloat(),onValueChange={change(it.roundToInt())},
        valueRange=range.first.toFloat()..range.last.toFloat(),modifier=Modifier.height(36.dp),
        thumb={Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary,CircleShape))},
        track={SliderDefaults.Track(it,modifier=Modifier.height(3.dp),thumbTrackGapSize=0.dp,drawStopIndicator=null)})
}
@Composable private fun SubtitleColors(selected:Int,change:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf(0xFFFFFF,0x000000,0xFFFF00,0xFFCC00,0x00FFFF,0x00FF00,0xFF5555,0x88AAFF).forEach {rgb->
            Box(Modifier.weight(1f).height(36.dp).background(Color(0xFF000000.toInt() or rgb),RoundedCornerShape(2.dp))
                .clickable {change(rgb)},contentAlignment=Alignment.Center) {
                if(selected==rgb) Text("\u2713",color=if(rgb==0)Color.White else Color.Black,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable private fun SubtitleEncodingPicker(value:String,change:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {
        TextButton(onClick={open=true}) {Text("Encoding: ${SubtitleStyle.encodings.entries.firstOrNull {it.value==value}?.key ?: "Auto"}")}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            SubtitleStyle.encodings.forEach {(label,encoding)->DropdownMenuItem(text={Text(label)},onClick={change(encoding);open=false})}
        }
    }
}
