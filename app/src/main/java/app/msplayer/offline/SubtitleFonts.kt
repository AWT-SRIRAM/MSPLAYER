package app.msplayer.offline

import java.io.File
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

data class SubtitleFont(val label: String, val family: String, val available: Boolean, val forceBold: Boolean = false)
object SubtitleFonts {
    private data class Family(val name:String,val faces:MutableList<String> = mutableListOf())
    private val installed: List<SubtitleFont> by lazy {
        // Use Android's actual family mapping; a font file's existence alone is insufficient.
        val families=mutableListOf<Family>()
        listOf("/system/etc/fonts.xml","/system/etc/system_fonts.xml").forEach { path ->
            runCatching {
                File(path).inputStream().use { input ->
                    val parser=Xml.newPullParser();parser.setInput(input,null)
                    var family:Family?=null
                    var inFont=false
                    while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                        if(parser.eventType==XmlPullParser.START_TAG) when(parser.name) {
                            "family" -> { family=parser.getAttributeValue(null,"name")?.let { Family(it) };family?.let {families.add(it)} }
                            "font" -> inFont=true
                        }
                        if(parser.eventType==XmlPullParser.TEXT && inFont) parser.text.trim().takeIf { it.isNotEmpty() }?.let { family?.faces?.add(it.lowercase()) }
                        if(parser.eventType==XmlPullParser.END_TAG && parser.name=="font") inFont=false
                        if(parser.eventType==XmlPullParser.END_TAG && parser.name=="family") family=null
                        parser.next()
                    }
                }
            }
        }
        fun match(name:String)=families.firstOrNull { f -> f.faces.any { it.contains(name) } }
        val roboto=match("roboto");val droid=match("droidsans");val arial=match("arial")
        listOf(
            SubtitleFont("Default", "", true),
            SubtitleFont("Sans Serif", "sans-serif", true),
            SubtitleFont("Roboto", roboto?.name ?: "Roboto", roboto!=null),
            SubtitleFont("Droid Sans", droid?.name ?: "Droid Sans", droid!=null),
            SubtitleFont("Arial", arial?.name ?: "Arial", arial!=null),
            SubtitleFont("Arial Bold", arial?.name ?: "Arial", arial!=null, true),
        )
    }
    fun choices() = installed
    fun resolve(label: String) = choices().firstOrNull { it.label == label && it.available } ?: choices().first()
}
