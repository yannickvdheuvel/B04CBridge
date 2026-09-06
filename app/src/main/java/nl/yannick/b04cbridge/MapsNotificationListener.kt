package nl.yannick.b04cbridge

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class MapsNotificationListener: NotificationListenerService(){
    private val lastSignature = mutableMapOf<String,String>()
    private val lastProgressSignature = mutableMapOf<String,String>()
    private val lastIconSignature = mutableMapOf<String,String>()
    private val lastKnownTotalDistance = mutableMapOf<String,Int>()
    private val suppressedSent = mutableMapOf<String,String>()

    private data class ProgressMeta(
        val current:Int?,
        val max:Int?,
        val segmentLengths:List<Int>,
        val pointPositions:List<Int>
    ){
        fun remainingMeters():Int?{
            if(current!=null && max!=null && current>=0 && max>=current) return max-current
            if(segmentLengths.isNotEmpty()) return segmentLengths.last()
            return null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        BridgeState.log("Google Maps + Komoot meldingstoegang actief")
        runCatching {
            activeNotifications
                .filter { isSupported(it.packageName) }
                .forEach { handle(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification){
        if(!isSupported(sbn.packageName)) return
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification){
        val pkg=sbn.packageName
        val source=sourceName(pkg)
        val lines = LinkedHashSet<String>()
        val e=sbn.notification.extras

        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        ).forEach { key -> addLine(lines,e.getCharSequence(key)) }

        for(key in e.keySet()){
            when(val value=e.get(key)){
                is CharSequence -> addLine(lines,value)
                is Array<*> -> value.filterIsInstance<CharSequence>().forEach { addLine(lines,it) }
            }
        }

        val progress=inspectProgressExtras(source,e)
        val iconManeuver=inspectNavigationIcons(source,pkg,sbn.notification,e)

        runCatching {
            val appCtx=createPackageContext(pkg,0)
            val builder=Notification.Builder.recoverBuilder(appCtx,sbn.notification)
            val remote=builder.createBigContentView() ?: builder.createContentView()
            val root=remote?.apply(appCtx,null)
            if(root!=null) collectText(root,lines)
        }.onFailure { BridgeState.log("$source RemoteViews lezen mislukt: ${it.javaClass.simpleName}") }

        if(lines.isEmpty()) return
        val cleanLines=lines.filter { it.isNotBlank() }
        val signature=cleanLines.joinToString(" | ")
        if(signature!=lastSignature[pkg]){
            lastSignature[pkg]=signature
            BridgeState.log("$source: $signature")
        }

        val ble=BridgeState.ble
        if(pkg==KOMOOT){
            val suppressReason=komootSuppressReason(cleanLines)
            if(suppressReason!=null){
                lastKnownTotalDistance.remove(pkg)
                if(ble?.ready==true){
                    if(suppressedSent[pkg]!=suppressReason){
                        suppressedSent[pkg]=suppressReason
                        ble.stopNav()
                        BridgeState.log("KOMOOT $suppressReason -> B04C navigatie gepauzeerd")
                    }
                } else {
                    suppressedSent.remove(pkg)
                }
                return
            }
            suppressedSent.remove(pkg)
        }

        val distances = findDistances(cleanLines)
        val currentDistance = chooseCurrentDistance(cleanLines,distances) ?: 0

        if(pkg==GMAPS){
            val progressRemaining=progress.remainingMeters()
            if(progressRemaining!=null && progressRemaining>=0){
                lastKnownTotalDistance[pkg]=progressRemaining
            } else {
                chooseRemainingDistance(cleanLines,distances,true)?.let { lastKnownTotalDistance[pkg]=it }
            }
        } else {
            val komootTotal=chooseRemainingDistance(cleanLines,distances,false)
            if(komootTotal!=null && komootTotal>currentDistance){
                lastKnownTotalDistance[pkg]=komootTotal
            } else {
                lastKnownTotalDistance.remove(pkg)
            }
        }

        val totalDistance = lastKnownTotalDistance[pkg] ?: 0
        val textManeuver=parseManeuver(cleanLines)
        val maneuver=textManeuver ?: iconManeuver ?: Protocol.STRAIGHT
        val directionSource=when {
            textManeuver!=null -> "tekst"
            iconManeuver!=null -> "icoon"
            else -> "fallback"
        }

        if(ble?.ready==true){
            BridgeState.log("$source -> display: ${maneuverName(maneuver)}, nu ${currentDistance}m, resterend ${totalDistance}m, bron=$directionSource")
            ble.sendMapsNav(maneuver,currentDistance,totalDistance)
        } else {
            BridgeState.log("$source ontvangen, maar B04C is niet klaar")
        }
    }

    private fun inspectProgressExtras(source:String,e:Bundle):ProgressMeta{
        val current = numberValue(e.get("android.progress"))
        val max = numberValue(e.get("android.progressMax"))
        val indeterminate = e.get("android.progressIndeterminate")
        val segments = bundleList(e.get("android.progressSegments"))
        val points = bundleList(e.get("android.progressPoints"))
        val segmentLengths = segments.mapNotNull { numberValue(it.get("length")) }
        val pointPositions = points.mapNotNull { numberValue(it.get("position")) }
        val segmentSum = if(segmentLengths.isNotEmpty()) segmentLengths.sum() else null

        val interestingNumbers = e.keySet()
            .mapNotNull { key ->
                val value=e.get(key)
                val n=numberValue(value)
                if(n!=null && key !in setOf("android.progress","android.progressMax")) "$key=$n" else null
            }
            .sorted()
            .take(12)

        val sig="current=$current max=$max ind=$indeterminate seg=$segmentLengths sum=$segmentSum points=$pointPositions nums=$interestingNumbers"
        if(sig!=lastProgressSignature[source]){
            lastProgressSignature[source]=sig
            if(current!=null || max!=null || segmentLengths.isNotEmpty() || pointPositions.isNotEmpty()){
                BridgeState.log("${source}_PROGRESS: $sig")
            }
        }
        return ProgressMeta(current,max,segmentLengths,pointPositions)
    }

    private fun inspectNavigationIcons(source:String,pkg:String,n:Notification,e:Bundle):Int?{
        val parts=mutableListOf<String>()
        var maneuver:Int?=null

        fun probe(label:String,value:Any?,allowDirection:Boolean){
            if(value==null) return
            val desc=describeIconValue(label,value,pkg)
            parts.add(desc.first)
            if(allowDirection && maneuver==null) maneuver=desc.second
        }

        probe("tracker",e.get("android.progressTrackerIcon"),true)
        probe("start",e.get("android.progressStartIcon"),false)
        probe("end",e.get("android.progressEndIcon"),false)
        probe("largeExtra",e.get(Notification.EXTRA_LARGE_ICON),true)
        probe("largeBig",e.get(Notification.EXTRA_LARGE_ICON_BIG),true)
        probe("large",n.getLargeIcon(),true)
        probe("small",n.smallIcon,false)

        val sig=parts.joinToString(" | ")
        if(sig.isNotBlank() && sig!=lastIconSignature[source]){
            lastIconSignature[source]=sig
            BridgeState.log("${source}_ICONS: $sig")
        }
        return maneuver
    }

    private fun describeIconValue(label:String,value:Any,pkg:String):Pair<String,Int?>{
        if(value is Icon){
            var resourceName:String?=null
            val details=mutableListOf<String>()
            if(Build.VERSION.SDK_INT>=28){
                details.add("type=${value.type}")
                if(value.type==Icon.TYPE_RESOURCE){
                    val resPkg=value.resPackage?.takeIf { it.isNotBlank() } ?: pkg
                    val resId=value.resId
                    resourceName=runCatching {
                        packageManager.getResourcesForApplication(resPkg).getResourceName(resId)
                    }.getOrNull()
                    details.add("res=${resourceName ?: "$resPkg:0x${resId.toString(16)}"}")
                }
            }
            iconFingerprint(value)?.let { details.add("fp=$it") }
            if(resourceName==null) details.add("raw=${value.toString().take(100)}")
            val raw=listOfNotNull(resourceName,value.toString()).joinToString(" ")
            return "$label{${details.joinToString(",")}}" to parseManeuverFromIconText(raw)
        }
        if(value is Bitmap){
            val fp=bitmapFingerprint(value)
            return "$label{bitmap=${value.width}x${value.height},fp=$fp}" to null
        }
        val raw=value.toString()
        return "$label{${value.javaClass.simpleName}:${raw.take(100)}}" to parseManeuverFromIconText(raw)
    }

    private fun iconFingerprint(icon:Icon):String? = runCatching {
        val d=icon.loadDrawable(this) ?: return@runCatching null
        val bmp=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bmp)
        d.setBounds(0,0,48,48)
        d.draw(canvas)
        bitmapFingerprint(bmp)
    }.getOrNull()

    private fun bitmapFingerprint(bitmap:Bitmap):String{
        val sample=if(bitmap.width==48 && bitmap.height==48) bitmap else {
            Bitmap.createScaledBitmap(bitmap,48,48,true)
        }
        var bits=0L
        for(gy in 0 until 8){
            for(gx in 0 until 8){
                var active=0
                for(y in gy*6 until gy*6+6){
                    for(x in gx*6 until gx*6+6){
                        if((sample.getPixel(x,y) ushr 24) and 0xFF > 40) active++
                    }
                }
                if(active>=4){
                    val bit=gy*8+gx
                    bits=bits or (1L shl bit)
                }
            }
        }
        return java.lang.Long.toUnsignedString(bits,16).padStart(16,'0')
    }

    private fun parseManeuverFromIconText(raw:String):Int?{
        val t=raw.lowercase().replace('-','_').replace(' ','_')
        return when {
            "u_turn" in t || "uturn" in t || "turnaround" in t -> Protocol.UTURN
            ("slight" in t || "keep" in t) && ("left" in t || "links" in t) -> Protocol.SLIGHT_LEFT
            ("slight" in t || "keep" in t) && ("right" in t || "rechts" in t) -> Protocol.SLIGHT_RIGHT
            "sharp" in t && ("left" in t || "links" in t) -> Protocol.SHARP_LEFT
            "sharp" in t && ("right" in t || "rechts" in t) -> Protocol.SHARP_RIGHT
            "left" in t || "links" in t -> Protocol.LEFT
            "right" in t || "rechts" in t -> Protocol.RIGHT
            "arrive" in t || "destination" in t || "finish" in t -> Protocol.ARRIVE
            "straight" in t || "ahead" in t -> Protocol.STRAIGHT
            else -> null
        }
    }

    private fun numberValue(v:Any?):Int? = when(v){
        is Int -> v
        is Long -> v.coerceIn(Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong()).toInt()
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Float -> v.toInt()
        is Double -> v.toInt()
        else -> null
    }

    private fun bundleList(v:Any?):List<Bundle>{
        return when(v){
            is ArrayList<*> -> v.filterIsInstance<Bundle>()
            is Array<*> -> v.filterIsInstance<Bundle>()
            else -> emptyList()
        }
    }

    private fun collectText(v:View,lines:MutableSet<String>){
        if(v is TextView) addLine(lines,v.text)
        addLine(lines,v.contentDescription)
        if(v is ViewGroup) for(i in 0 until v.childCount) collectText(v.getChildAt(i),lines)
    }

    private fun addLine(lines:MutableSet<String>,cs:CharSequence?){
        val s=cs?.toString()?.trim().orEmpty()
        if(s.isNotEmpty() && !s.equals("Google Maps",true) && !s.equals("Maps",true) && !s.equals("Komoot",true)) lines.add(s)
    }

    private fun komootSuppressReason(lines:List<String>):String?{
        val text=lines.joinToString(" ").lowercase()
        return when {
            "buiten de route" in text || "off route" in text || "off-route" in text || "outside the route" in text || "außerhalb der route" in text -> "buiten route"
            "naar het startpunt" in text || "to the start point" in text || "to the starting point" in text || "zum startpunkt" in text -> "naar startpunt"
            else -> null
        }
    }

    private fun parseManeuver(lines:List<String>):Int?{
        for(line in lines){
            val t=line.lowercase().trim()
            if(isMetadataLine(t)) continue
            when {
                "u-turn" in t || "u turn" in t || "keer om" in t || "omkeren" in t || "wenden" in t -> return Protocol.UTURN
                "flauw links" in t || "lichte bocht links" in t || "slight left" in t || "houd links" in t || "keep left" in t || "leicht links" in t -> return Protocol.SLIGHT_LEFT
                "flauw rechts" in t || "lichte bocht rechts" in t || "slight right" in t || "houd rechts" in t || "keep right" in t || "leicht rechts" in t -> return Protocol.SLIGHT_RIGHT
                "scherp links" in t || "sharp left" in t || "scharf links" in t -> return Protocol.SHARP_LEFT
                "scherp rechts" in t || "sharp right" in t || "scharf rechts" in t -> return Protocol.SHARP_RIGHT
                "linksaf" in t || "sla links" in t || "ga links" in t || "neem links" in t || "bocht naar links" in t || "turn left" in t || " left onto " in t || "links abbiegen" in t -> return Protocol.LEFT
                "rechtsaf" in t || "sla rechts" in t || "ga rechts" in t || "neem rechts" in t || "bocht naar rechts" in t || "turn right" in t || " right onto " in t || "rechts abbiegen" in t -> return Protocol.RIGHT
                "bestemming bereikt" in t || "je bent aangekomen" in t || "aangekomen bij" in t ||
                    "destination reached" in t || "you have arrived" in t || "arrived at" in t ||
                    "ziel erreicht" in t || "ziel angekommen" in t -> return Protocol.ARRIVE
                "rechtdoor" in t || "ga verder" in t || "continue" in t || "straight" in t ||
                    "volg" in t || "fiets naar" in t || "rij naar" in t || "head " in t ||
                    "geradeaus" in t || "weiter auf" in t -> return Protocol.STRAIGHT
            }
        }
        return null
    }

    private fun isMetadataLine(t:String):Boolean{
        if(t.isBlank() || t=="•" || t=="·") return true
        if("navigatie afsluiten" in t || "stop navigation" in t || "navigation beenden" in t) return true
        if("aankomst om" in t || "arrival at" in t || "arrive at" in t || "ankunft" in t) return true
        if(Regex("^\\d{1,2}[:.]\\d{2}$").matches(t)) return true
        return false
    }

    private fun findDistances(lines:List<String>):List<Int>{
        val out=mutableListOf<Int>()
        val re=Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b",RegexOption.IGNORE_CASE)
        lines.forEach { line ->
            re.findAll(line).forEach { m ->
                val v=m.groupValues[1].replace(',','.').toDoubleOrNull() ?: return@forEach
                val meters=if(m.groupValues[2].equals("km",true)) (v*1000.0).toInt() else v.toInt()
                if(meters>=0) out.add(meters)
            }
        }
        return out.distinct()
    }

    private fun chooseCurrentDistance(lines:List<String>, distances:List<Int>):Int?{
        val re=Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\s*$",RegexOption.IGNORE_CASE)
        lines.forEach { line ->
            val m=re.find(line) ?: return@forEach
            val v=m.groupValues[1].replace(',','.').toDoubleOrNull() ?: return@forEach
            return if(m.groupValues[2].equals("km",true)) (v*1000).toInt() else v.toInt()
        }
        return distances.minOrNull()
    }

    private fun chooseRemainingDistance(lines:List<String>, distances:List<Int>, allowMaxFallback:Boolean):Int?{
        val tripLine=lines.firstOrNull { line ->
            val t=line.lowercase()
            ("min" in t || "uur" in t || "hr" in t || "std" in t || Regex("\\b\\d{1,2}[:.]\\d{2}\\b").containsMatchIn(t)) &&
                Regex("\\d+(?:[.,]\\d+)?\\s*(km|m)\\b",RegexOption.IGNORE_CASE).containsMatchIn(line)
        }
        if(tripLine!=null){
            return findDistances(listOf(tripLine)).maxOrNull()
        }
        return if(allowMaxFallback && distances.size>=2) distances.maxOrNull() else null
    }

    private fun maneuverName(m:Int)=when(m){
        Protocol.LEFT->"links"; Protocol.RIGHT->"rechts"; Protocol.SLIGHT_LEFT->"flauw links"
        Protocol.SLIGHT_RIGHT->"flauw rechts"; Protocol.SHARP_LEFT->"scherp links"; Protocol.SHARP_RIGHT->"scherp rechts"
        Protocol.UTURN->"omkeren"; Protocol.ARRIVE->"aankomst"; else->"rechtdoor"
    }

    private fun isSupported(pkg:String)=pkg==GMAPS || pkg==KOMOOT
    private fun sourceName(pkg:String)=if(pkg==KOMOOT) "KOMOOT" else "MAPS"

    companion object {
        const val GMAPS="com.google.android.apps.maps"
        const val KOMOOT="de.komoot.android"
    }
}
