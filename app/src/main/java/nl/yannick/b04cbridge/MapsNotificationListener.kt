package nl.yannick.b04cbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MapsNotificationListener: NotificationListenerService(){
    override fun onNotificationPosted(sbn: StatusBarNotification){
        if(sbn.packageName!="com.google.android.apps.maps") return
        val e=sbn.notification.extras
        val text=listOf(
            e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ).filterNotNull().joinToString(" | ")
        val man=parseManeuver(text); val dist=parseDistance(text)
        if(man!=null && dist!=null) BridgeState.ble?.testNav(man,dist)
    }
    private fun parseManeuver(s:String):Int?{
        val t=s.lowercase()
        return when {
            "u-turn" in t || "keer om" in t || "omkeren" in t -> Protocol.UTURN
            "slight left" in t || "flauw links" in t -> Protocol.SLIGHT_LEFT
            "slight right" in t || "flauw rechts" in t -> Protocol.SLIGHT_RIGHT
            "sharp left" in t || "scherp links" in t -> Protocol.SHARP_LEFT
            "sharp right" in t || "scherp rechts" in t -> Protocol.SHARP_RIGHT
            "left" in t || "links" in t -> Protocol.LEFT
            "right" in t || "rechts" in t -> Protocol.RIGHT
            "arrive" in t || "bestemming" in t -> Protocol.ARRIVE
            "continue" in t || "rechtdoor" in t -> Protocol.STRAIGHT
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
}
