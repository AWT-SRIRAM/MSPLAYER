@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.msplayer.offline

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream(minOf(limit,8192));val buffer=ByteArray(8192)
    var remaining=limit
    while(remaining>0) { val n=read(buffer,0,minOf(buffer.size,remaining));if(n<0) break;output.write(buffer,0,n);remaining-=n }
    return output.toByteArray()
}

object LocalLyrics {
    data class Line(val time: Long?, val text: String)
    fun parse(text: String): List<Line> {
        val pattern=Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        return text.lineSequence().flatMap { line ->
            val matches=pattern.findAll(line).toList()
            if(matches.isEmpty()) sequenceOf(Line(null,line)) else matches.asSequence().map { m ->
                Line(m.groupValues[1].toLong()*60000 + m.groupValues[2].toLong()*1000 + m.groupValues[3].padEnd(3,'0').take(3).toLong(),line.replace(pattern,"").trim())
            }
        }.filter { it.text.isNotBlank() && !it.text.matches(Regex("\\[(ar|ti|al|by|offset):.*]")) }.toList().let { rows ->
            if(rows.any { it.time!=null }) rows.filter { it.time!=null }.sortedBy { it.time } else rows
        }
    }
    /** Bounded ID3 USLT/SYLT text discovery; other containers can use imported local lyrics. */
    fun embedded(context: android.content.Context, uri: String): String? {
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val header=input.readBounded(10)
                if(header.size<10 || String(header,0,3,Charsets.ISO_8859_1)!="ID3") return@use null
                val version=header[3].toInt()
                if(version !in 3..4 || header[5].toInt() and 0xC0 != 0) return@use null
                fun sync(bytes:ByteArray,start:Int):Int = (0..3).fold(0){v,i -> (v shl 7) or (bytes[start+i].toInt() and 127)}
                val length=sync(header,6)
                if(length !in 1..2_097_152) return@use null
                val data=input.readBounded(length);var at=0
                while(at+10<=data.size) {
                    val tag=String(data,at,4,Charsets.ISO_8859_1)
                    val size=if(version==4) sync(data,at+4) else (0..3).fold(0){v,i -> (v shl 8) or (data[at+4+i].toInt() and 255)}
                    if(size<=0 || at+10L+size>data.size) break
                    if(tag=="USLT" && size>5 && data[at+8].toInt()==0 && data[at+9].toInt()==0) {
                        val payload=data.copyOfRange(at+10,at+10+size)
                        val encoding=payload[0].toInt()
                        val charset=when(encoding){1->Charsets.UTF_16;2->Charsets.UTF_16BE;3->Charsets.UTF_8;else->Charsets.ISO_8859_1}
                        val body=String(payload,4,payload.size-4,charset)
                        return@use body.substringAfter('\u0000',"").takeIf { it.isNotBlank() }
                    }
                    at+=10+size
                }
                null
            }
        }.getOrNull()
    }
}

@Composable
fun MusicPlayerScreen(activity: MainActivity, service: PlaybackService, state: PlayerState) {
    val item=state.item ?: return
    val timeline by service.timeline.collectAsState()
    val queue by service.queueItems.collectAsState()
    val shuffle by service.shuffleEnabled.collectAsState()
    val repeat by service.repeatMode.collectAsState()
    val ab by service.abPoints.collectAsState()
    val effects by service.effectSettings.collectAsState()
    var panel by remember { mutableStateOf<String?>(null) }
    var title by remember(item.uri) { mutableStateOf(item.title) }
    var artist by remember(item.uri) { mutableStateOf("Unknown artist") }
    var artwork by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var lyrics by remember(item.uri) { mutableStateOf<List<LocalLyrics.Line>>(emptyList()) }
    var favourite by remember(item.uri) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _, event -> active=event==androidx.lifecycle.Lifecycle.Event.ON_RESUME || (active && event!=androidx.lifecycle.Lifecycle.Event.ON_PAUSE && event!=androidx.lifecycle.Lifecycle.Event.ON_STOP) }
        activity.lifecycle.addObserver(observer)
        activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity.lifecycle.removeObserver(observer); service.endTemporarySpeed() }
    }
    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            val retriever=MediaMetadataRetriever()
            try {
                activity.contentResolver.openFileDescriptor(Uri.parse(item.uri),"r")?.use { fd ->
                    retriever.setDataSource(fd.fileDescriptor)
                    val t=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val a=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val cover=retriever.embeddedPicture?.takeIf { it.size<=8*1024*1024 }?.let { bytes ->
                        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        val options=android.graphics.BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/720).coerceAtLeast(1) }
                        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    }
                    withContext(Dispatchers.Main) { title=t?.takeIf { it.isNotBlank() } ?: item.title;artist=a?.takeIf { it.isNotBlank() } ?: "Unknown artist";artwork=cover }
                }
            } catch(e:CancellationException) { throw e } catch(_:Exception) { } finally { retriever.release() }
            val stored=Store.prefs(activity).getString("lyrics_uri_${item.uri.hashCode()}",null)
            val words=stored?.let { location -> runCatching { activity.contentResolver.openInputStream(Uri.parse(location))?.use { String(it.readBounded(1024*1024),Charsets.UTF_8) } }.getOrNull() } ?: LocalLyrics.embedded(activity,item.uri)
            val liked=Store.db(activity).dao().entries("Favorites").any { it.uri==item.uri }
            withContext(Dispatchers.Main) { favourite=liked; if(words!=null) lyrics=LocalLyrics.parse(words) }
        }
    }
    val lyricsPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            val target=item.uri
            runCatching { activity.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Store.prefs(activity).edit().putString("lyrics_uri_${target.hashCode()}",uri.toString()).apply()
            scope.launch {
                val text=withContext(Dispatchers.IO) { runCatching { activity.contentResolver.openInputStream(uri)?.use { String(it.readBounded(1024*1024),Charsets.UTF_8) } }.getOrNull() }
                if(service.state.value.item?.uri==target && text!=null) lyrics=LocalLyrics.parse(text)
            }
        }
    }
    BackHandler { if(panel!=null) panel=null else activity.exitPlayer() }
    val rotation=remember { Animatable(0f) }
    LaunchedEffect(state.playing,active) {
        val reduced=android.provider.Settings.Global.getFloat(activity.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
        if(state.playing && active && !reduced) while(isActive) { rotation.animateTo(rotation.value+360, tween(18000,easing=LinearEasing));rotation.snapTo(rotation.value%360) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={activity.exitPlayer()}) { Icon(Icons.Default.KeyboardArrowDown,"Back to library") }
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text(artist,color=Color.LightGray,fontSize=14.sp,maxLines=1)
                }
                IconButton(onClick={ activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="audio/*";putExtra(Intent.EXTRA_STREAM,Uri.parse(item.uri));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Share audio")) }) { Icon(Icons.Default.Share,"Share audio") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=24.dp).heightIn(max=440.dp),contentAlignment=Alignment.Center) {
                val size=maxWidth.coerceAtMost(400.dp)
                Box(Modifier.size(size).graphicsLayer { rotationZ=rotation.value }.clip(CircleShape).background(Color(0xFF151515)),contentAlignment=Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val center=androidx.compose.ui.geometry.Offset(this.size.width/2,this.size.height/2)
                        for(i in 0..22) drawCircle(Color(0xFF303030),this.size.minDimension*(.27f+i*.009f),center,style=androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawCircle(Color.DarkGray,this.size.minDimension*.49f,center,style=androidx.compose.ui.graphics.drawscope.Stroke(8f))
                    }
                    if(artwork!=null) Image(artwork!!.asImageBitmap(),"Album artwork",Modifier.fillMaxSize(.56f).clip(CircleShape),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
                    else Box(Modifier.fillMaxSize(.56f).background(Color(0xFF777777),CircleShape),contentAlignment=Alignment.Center) { Icon(Icons.Default.MusicNote,null,tint=Color(0xFF535353),modifier=Modifier.fillMaxSize(.6f)) }
                }
                Canvas(Modifier.size(size)) {
                    val w=this.size.width;val h=this.size.height
                    val path=Path().apply { moveTo(w*.65f,h*.04f);lineTo(w*.79f,h*.28f);lineTo(w*.9f,h*.37f) }
                    drawPath(path,Color.LightGray,style=androidx.compose.ui.graphics.drawscope.Stroke(w*.018f,cap=StrokeCap.Round))
                    drawCircle(Color.Gray,w*.038f,androidx.compose.ui.geometry.Offset(w*.65f,h*.04f))
                    drawCircle(Color.LightGray,w*.025f,androidx.compose.ui.geometry.Offset(w*.65f,h*.04f))
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={panel="Effects"}) { Icon(Icons.Default.Tune,"Audio effects") }
                IconButton(onClick={service.markAB()}) { Text(if(ab.second!=null) "A↔B" else if(ab.first!=null) "A→" else "A–B",color=if(ab.first!=null) Accent else Color.White) }
                IconButton(onClick={panel="Speed"}) { Icon(Icons.Default.Speed,"Playback speed") }
                IconButton(onClick={
                    favourite=!favourite
                    val liked=favourite
                    scope.launch(Dispatchers.IO) { val dao=Store.db(activity).dao(); if(liked) dao.add(PlaylistEntry().apply { playlist="Favorites";uri=item.uri;title=item.title;added=System.currentTimeMillis() }) else dao.remove("Favorites",item.uri) }
                }) { Icon(if(favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Favourite",tint=if(favourite) Accent else Color.White) }
                IconButton(onClick={panel="Options"}) { Icon(Icons.Default.MoreVert,"Music options") }
            }
            Spacer(Modifier.height(20.dp))
            var target by remember(item.uri) { mutableStateOf<Long?>(null) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(durationText(target?:timeline.position));Text(durationText(timeline.duration)) }
            Slider(thumb={ Box(Modifier.size(15.dp).background(Accent,CircleShape)) },track={ slider ->
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val fraction=(slider.value/slider.valueRange.endInclusive.coerceAtLeast(1f)).coerceIn(0f,1f)
                    drawLine(Color.Gray,androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width,size.height/2),3.dp.toPx())
                    drawLine(Accent,androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width*fraction,size.height/2),3.dp.toPx())
                }
            },value=(target?:timeline.position).toFloat().coerceIn(0f,timeline.duration.coerceAtLeast(1).toFloat()),onValueChange={target=it.toLong()},onValueChangeFinished={target?.let {service.seek(it)};target=null},valueRange=0f..timeline.duration.coerceAtLeast(1).toFloat(),enabled=timeline.seekable)
            Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={service.toggleShuffle()}) { Icon(Icons.Default.Shuffle,"Shuffle",tint=if(shuffle) Accent else Color.White) }
                IconButton(onClick={service.previous()}) { Icon(Icons.Default.SkipPrevious,"Previous",modifier=Modifier.size(32.dp)) }
                OutlinedIconButton(onClick={service.toggle()},modifier=Modifier.size(74.dp),border=BorderStroke(2.dp,Color.White)) { Icon(if(state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,"Play or pause",modifier=Modifier.size(44.dp)) }
                IconButton(onClick={service.next()}) { Icon(Icons.Default.SkipNext,"Next",modifier=Modifier.size(32.dp)) }
                IconButton(onClick={service.setRepeat(when(repeat){"off"->"all";"all"->"one";else->"off"})}) { Icon(if(repeat=="one") Icons.Default.RepeatOne else Icons.Default.Repeat,"Repeat $repeat",tint=if(repeat!="off") Accent else Color.White) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={panel="Lyrics"}) { Icon(Icons.Default.Lyrics,null);Text("Lyrics",color=Color.White) }
                TextButton(onClick={panel="Queue"}) { Icon(Icons.Default.QueueMusic,null);Text("Playing Queue",color=Color.White) }
            }
        }
        state.error?.let { Text(it,color=Color(0xFFFFB4AB),modifier=Modifier.align(Alignment.BottomCenter).background(Color.Black).padding(12.dp)) }
    }
    when(panel) {
        "Effects" -> PlayerSidePanel("Audio effects",{panel=null}) { AudioEffectsControls(service) }
        "Queue" -> PlayerSidePanel("Playing Queue",{panel=null}) { queue.forEach { track -> TrackChoice(track.title,track.uri==item.uri,onClick={service.playQueueItem(track);panel=null}) } }
        "Speed" -> PlayerSidePanel("Playback speed",{panel=null}) { listOf(.25f,.5f,1f,1.5f,2f,2.5f,3f,4f).forEach { rate -> TrackChoice("${rate}×",kotlin.math.abs(state.rate-rate)<.01f,onClick={service.rate(rate)}) } }
        "Lyrics" -> PlayerSidePanel("Lyrics",{panel=null}) {
            TextButton(onClick={lyricsPicker.launch(arrayOf("*/*"))}) { Text("Open local lyrics") }
            if(lyrics.isEmpty()) Text("No embedded or local lyrics available",color=Color.LightGray)
            val current=lyrics.indexOfLast { it.time!=null && it.time<=timeline.position }
            val scroll=androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(current) { if(current>=0) scroll.animateScrollToItem(current) }
            LazyColumn(Modifier.fillMaxWidth().height(340.dp),state=scroll) { items(lyrics.size) { i -> Text(lyrics[i].text,color=if(i==current) Accent else Color.White,modifier=Modifier.padding(vertical=8.dp)) } }
        }
        "Options" -> PlayerSidePanel("Music options",{panel=null}) {
            TextButton(onClick={panel="Effects"}) { Text("Equalizer and effects") }
            TextButton(onClick={service.markAB()}) { Text("${if(ab.first==null) "Set A" else if(ab.second==null) "Set B" else "Clear A–B repeat"}") }
            TextButton(onClick={activity.closeApp()}) { Text("Exit MS PLAYER") }
        }
    }
}
