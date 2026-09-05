package nl.yannick.b04cbridge

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class MapsNotificationListener: NotificationListenerService(){

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

        // Google Maps stores the useful turn-by-turn text mostly in custom RemoteViews.
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
        val text=lines.joinToString(" | ")
        BridgeState.log("MAPS: $text")

        val man=parseManeuver(text) ?: Protocol.STRAIGHT
        // Maps sometimes omits the distance while the maneuver is effectively 'now'.
        val dist=parseDistance(text) ?: 0
        val ble=BridgeState.ble
        if(ble?.ready==true){
            BridgeState.log("Maps -> display: ${maneuverName(man)}, ${dist}m")
            ble.testNav(man,dist)
        } else {
            BridgeState.log("Maps ontvangen, maar B04C is niet klaar")
        }
    }

    private fun collectText(v:View,lines:MutableSet<String>){
        if(v is TextView) addLine(lines,v.text)
        if(v is ViewGroup) for(i in 0 until v.childCount) collectText(v.getChildAt(i),lines)
    }

    private fun addLine(lines:MutableSet<String>,cs:CharSequence?){
        val s=cs?.toString()?.trim().orEmpty()
        if(s.isNotEmpty() && !s.equals("Google Maps",true) && !s.equals("Maps",true)) lines.add(s)
    }

    private fun parseManeuver(s:String):Int?{
        val t=s.lowercase()
        return when {
            "u-turn" in t || "u turn" in t || "keer om" in t || "omkeren" in t -> Protocol.UTURN
            "flauw links" in t || "lichte bocht links" in t || "slight left" in t || "houd links" in t -> Protocol.SLIGHT_LEFT
            "flauw rechts" in t || "lichte bocht rechts" in t || "slight right" in t || "houd rechts" in t -> Protocol.SLIGHT_RIGHT
            "scherp links" in t || "sharp left" in t -> Protocol.SHARP_LEFT
            "scherp rechts" in t || "sharp right" in t -> Protocol.SHARP_RIGHT
            "linksaf" in t || "sla links" in t || "turn left" in t || " left onto " in t -> Protocol.LEFT
            "rechtsaf" in t || "sla rechts" in t || "turn right" in t || " right onto " in t -> Protocol.RIGHT
            "bestemming" in t || "aankomst" in t || "arrive" in t || "destination" in t -> Protocol.ARRIVE
            "rechtdoor" in t || "ga verder" in t || "continue" in t || "straight" in t -> Protocol.STRAIGHT
            else -> null
        }
    }

    private fun parseDistance(s:String):Int?{
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b",RegexOption.IGNORE_CASE).find(s)?.let{m->
            val v=m.groupValues[1].replace(',','.').toDoubleOrNull()?:return null
            return if(m.groupValues[2].equals("km",true)) (v*1000).toInt() else v.toInt()
        }
        return null
    }

    private fun maneuverName(m:Int)=when(m){
        Protocol.LEFT->"links"; Protocol.RIGHT->"rechts"; Protocol.SLIGHT_LEFT->"flauw links"
        Protocol.SLIGHT_RIGHT->"flauw rechts"; Protocol.SHARP_LEFT->"scherp links"; Protocol.SHARP_RIGHT->"scherp rechts"
        Protocol.UTURN->"omkeren"; Protocol.ARRIVE->"aankomst"; else->"rechtdoor"
    }

    companion object { const val GMAPS="com.google.android.apps.maps" }
}
