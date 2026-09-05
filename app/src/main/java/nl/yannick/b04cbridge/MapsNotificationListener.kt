package nl.yannick.b04cbridge

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class MapsNotificationListener: NotificationListenerService(){
    private var lastSignature = ""
    private var lastProgressSignature = ""
    private var lastKnownTotalDistance: Int? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        BridgeState.log("Google Maps meldingstoegang actief")
        runCatching {
            activeNotifications
                .filter { it.packageName == GMAPS }
                .forEach { handle(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification){
        if(sbn.packageName!=GMAPS) return
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification){
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

        // Maps changes notification keys between Android/Maps versions, so inspect all text extras.
        for(key in e.keySet()){
            when(val value=e.get(key)){
                is CharSequence -> addLine(lines,value)
                is Array<*> -> value.filterIsInstance<CharSequence>().forEach { addLine(lines,it) }
            }
        }

        // Android 16 Maps uses ProgressStyle. Log its numeric metadata separately so we can see
        // whether its progress units correspond to route distance even when the visible text says 0 m.
        inspectProgressExtras(e)

        runCatching {
            val mapsCtx=createPackageContext(sbn.packageName,Context.CONTEXT_IGNORE_SECURITY)
            val builder=Notification.Builder.recoverBuilder(this,sbn.notification)
            val remote=builder.createBigContentView() ?: builder.createContentView()
            if(remote!=null){
                val inflater=mapsCtx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val root=inflater.inflate(remote.layoutId,null) as? ViewGroup
                if(root!=null){
                    remote.reapply(mapsCtx,root)
                    collectText(root,lines)
                }
            }
        }.onFailure { BridgeState.log("Maps RemoteViews lezen mislukt: ${it.javaClass.simpleName}") }

        if(lines.isEmpty()) return
        val cleanLines=lines.filter { it.isNotBlank() }
        val signature=cleanLines.joinToString(" | ")
        if(signature!=lastSignature){
            lastSignature=signature
            BridgeState.log("MAPS: $signature")
        }

        val distances = findDistances(cleanLines)
        val currentDistance = chooseCurrentDistance(cleanLines,distances) ?: 0
        chooseRemainingDistance(cleanLines,distances)?.let { lastKnownTotalDistance=it }
        val totalDistance = lastKnownTotalDistance ?: 0
        val maneuver=parseManeuver(cleanLines) ?: Protocol.STRAIGHT

        val ble=BridgeState.ble
        if(ble?.ready==true){
            BridgeState.log("Maps -> display: ${maneuverName(maneuver)}, nu ${currentDistance}m, resterend ${totalDistance}m")
            ble.sendMapsNav(maneuver,currentDistance,totalDistance)
        } else {
            BridgeState.log("Maps ontvangen, maar B04C is niet klaar")
        }
    }

    private fun inspectProgressExtras(e:Bundle){
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
        if(sig!=lastProgressSignature){
            lastProgressSignature=sig
            BridgeState.log("PROGRESS: $sig")
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
        if(s.isNotEmpty() && !s.equals("Google Maps",true) && !s.equals("Maps",true)) lines.add(s)
    }

    private fun parseManeuver(lines:List<String>):Int?{
        for(line in lines){
            val t=line.lowercase().trim()
            if(isMetadataLine(t)) continue
            when {
                "u-turn" in t || "u turn" in t || "keer om" in t || "omkeren" in t -> return Protocol.UTURN
                "flauw links" in t || "lichte bocht links" in t || "slight left" in t || "houd links" in t -> return Protocol.SLIGHT_LEFT
                "flauw rechts" in t || "lichte bocht rechts" in t || "slight right" in t || "houd rechts" in t -> return Protocol.SLIGHT_RIGHT
                "scherp links" in t || "sharp left" in t -> return Protocol.SHARP_LEFT
                "scherp rechts" in t || "sharp right" in t -> return Protocol.SHARP_RIGHT
                "linksaf" in t || "sla links" in t || "turn left" in t || " left onto " in t -> return Protocol.LEFT
                "rechtsaf" in t || "sla rechts" in t || "turn right" in t || " right onto " in t -> return Protocol.RIGHT
                "bestemming bereikt" in t || "je bent aangekomen" in t || "aangekomen bij" in t ||
                    "destination reached" in t || "you have arrived" in t || "arrived at" in t -> return Protocol.ARRIVE
                "rechtdoor" in t || "ga verder" in t || "continue" in t || "straight" in t ||
                    "volg" in t || "fiets naar" in t || "rij naar" in t || "head " in t -> return Protocol.STRAIGHT
            }
        }
        return null
    }

    private fun isMetadataLine(t:String):Boolean{
        if(t.isBlank() || t=="•" || t=="·") return true
        if("navigatie afsluiten" in t || "stop navigation" in t) return true
        if("aankomst om" in t || "arrival at" in t || "arrive at" in t) return true
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
        return out
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

    private fun chooseRemainingDistance(lines:List<String>, distances:List<Int>):Int?{
        val tripLine=lines.firstOrNull { line ->
            val t=line.lowercase()
            ("min" in t || "uur" in t || "hr" in t || Regex("\\b\\d{1,2}[:.]\\d{2}\\b").containsMatchIn(t)) &&
                Regex("\\d+(?:[.,]\\d+)?\\s*(km|m)\\b",RegexOption.IGNORE_CASE).containsMatchIn(line)
        }
        if(tripLine!=null){
            return findDistances(listOf(tripLine)).maxOrNull()
        }
        return if(distances.size>=2) distances.maxOrNull() else null
    }

    private fun maneuverName(m:Int)=when(m){
        Protocol.LEFT->"links"; Protocol.RIGHT->"rechts"; Protocol.SLIGHT_LEFT->"flauw links"
        Protocol.SLIGHT_RIGHT->"flauw rechts"; Protocol.SHARP_LEFT->"scherp links"; Protocol.SHARP_RIGHT->"scherp rechts"
        Protocol.UTURN->"omkeren"; Protocol.ARRIVE->"aankomst"; else->"rechtdoor"
    }

    companion object { const val GMAPS="com.google.android.apps.maps" }
}
