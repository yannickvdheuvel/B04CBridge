package nl.yannick.b04cbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:AppCompatActivity(){
    private lateinit var ble:BleManager
    private lateinit var logView:TextView
    private lateinit var logScroll:ScrollView
    private lateinit var status:TextView
    private lateinit var statusTitle:TextView
    private lateinit var dot:TextView
    private lateinit var navArrow:TextView
    private lateinit var navTurn:TextView
    private lateinit var navRoute:TextView
    private lateinit var speed:TextView
    private lateinit var battery:TextView
    private lateinit var assist:TextView
    private lateinit var trip:TextView
    private lateinit var odo:TextView
    private lateinit var avg:TextView
    private lateinit var ridetime:TextView

    private val ui=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{
        override fun run(){ render(); ui.postDelayed(this,1000) }
    }

    private val req=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result ->
        val ok=result.values.all { it }
        if(ok) ble.scanAndConnect() else Toast.makeText(this,"Bluetooth-toestemming nodig",Toast.LENGTH_LONG).show()
    }

    private val saveLog=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){ uri ->
        if(uri==null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(BridgeState.snapshot()) }
                ?: error("Kan bestand niet openen")
        }.onSuccess {
            Toast.makeText(this,"Logbestand opgeslagen",Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this,"Opslaan mislukt: ${it.message}",Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        logView=findViewById(R.id.log); logScroll=findViewById(R.id.logScroll)
        status=findViewById(R.id.status); statusTitle=findViewById(R.id.statusTitle); dot=findViewById(R.id.dot)
        navArrow=findViewById(R.id.navArrow); navTurn=findViewById(R.id.navTurn); navRoute=findViewById(R.id.navRoute)
        speed=findViewById(R.id.speed); battery=findViewById(R.id.battery); assist=findViewById(R.id.assist)
        trip=findViewById(R.id.trip); odo=findViewById(R.id.odo); avg=findViewById(R.id.avg); ridetime=findViewById(R.id.ridetime)
        findViewById<TextView>(R.id.build).text=BuildConfig.VERSION_NAME

        val existing=BridgeState.snapshot()
        if(existing.isNotBlank()) logView.text=existing+"\n"
        scrollLogDown()

        BridgeState.uiLog = { s ->
            runOnUiThread {
                logView.append(s+"\n")
                status.text=s
                scrollLogDown()
            }
        }

        ble=BridgeState.ble ?: BleManager(applicationContext){ s -> BridgeState.log(s) }.also { BridgeState.ble=it }

        findViewById<MaterialButton>(R.id.connect).setOnClickListener{ connectOrRequestPermission() }
        findViewById<MaterialButton>(R.id.notif).setOnClickListener{ startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        val toggle=findViewById<MaterialButton>(R.id.toggleLog)
        toggle.setOnClickListener{
            val hidden=logScroll.visibility!=View.VISIBLE
            logScroll.visibility=if(hidden) View.VISIBLE else View.GONE
            toggle.text=if(hidden) "Verberg" else "Toon"
            if(hidden) scrollLogDown()
        }

        findViewById<MaterialButton>(R.id.copyLog).setOnClickListener{
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("B04C Bridge log",BridgeState.snapshot()))
            Toast.makeText(this,"Volledige log gekopieerd",Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.saveLog).setOnClickListener{
            val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
            saveLog.launch("B04CBridge-$stamp.txt")
        }

        // Bij volgende appstarts meteen weer verbinden; alleen de allereerste keer vragen we rechten.
        if(hasBluetoothPermissions()) ble.scanAndConnect()
    }

    override fun onResume(){ super.onResume(); render(); ui.postDelayed(refresh,1000) }
    override fun onPause(){ ui.removeCallbacks(refresh); super.onPause() }

    private fun scrollLogDown(){ logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun render(){
        val connected=BridgeState.ble?.ready==true
        dot.setTextColor(ContextCompat.getColor(this,if(connected) R.color.ok else R.color.off))
        statusTitle.text=if(connected) "Verbonden met B04C" else "Niet verbonden"

        val since=System.currentTimeMillis()-BridgeState.navAt
        val man=BridgeState.navManeuver
        // Na een minuut zonder nieuwe aanwijzing is er geen navigatie meer bezig.
        if(man!=null && since<60_000){
            navArrow.text=arrowFor(man)
            navTurn.text="${maneuverName(man)} · ${meters(BridgeState.navTurnMeters)}"
            navRoute.text="Nog ${meters(BridgeState.navRouteMeters)} te gaan"
        } else {
            navArrow.text="–"
            navTurn.text="Geen navigatie"
            navRoute.text="Start Google Maps of Komoot"
        }

        val kph=BridgeState.bikeSpeedKph
        speed.text=if(kph==null) "–" else withUnit(String.format(Locale.GERMAN,"%.1f",kph)," km/h")
        battery.text=BridgeState.bikeBatteryPercent?.let { "$it%" } ?: "–"
        assist.text=BridgeState.bikeAssist?.let { a -> "assist $a/${BridgeState.bikeAssistMax ?: "?"}" } ?: "–"
        trip.text=BridgeState.bikeTripMeters?.let { String.format(Locale.GERMAN,"%.2f km",it/1000.0) } ?: "–"
        odo.text=BridgeState.bikeOdoMeters?.let { String.format(Locale.GERMAN,"%.0f km",it/1000.0) } ?: "–"
        avg.text=BridgeState.bikeAvgKph?.let { String.format(Locale.GERMAN,"%.1f km/h",it) } ?: "–"
        ridetime.text=BridgeState.bikeRideSeconds?.let { String.format(Locale.US,"%d:%02d:%02d",it/3600,(it/60)%60,it%60) } ?: "–"
    }

    private fun withUnit(value:String,unit:String):CharSequence{
        val s=SpannableString(value+unit)
        s.setSpan(AbsoluteSizeSpan(16,true),value.length,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun meters(m:Int)=
        if(m<1000) "$m m" else String.format(Locale.GERMAN,"%.1f km",m/1000.0)

    private fun arrowFor(m:Int)=when(m){
        Protocol.LEFT->"↰"; Protocol.RIGHT->"↱"
        Protocol.SLIGHT_LEFT->"↖"; Protocol.SLIGHT_RIGHT->"↗"
        Protocol.SHARP_LEFT->"⬋"; Protocol.SHARP_RIGHT->"⬊"
        Protocol.UTURN->"↩"; Protocol.ARRIVE->"⚑"
        else->"↑"
    }

    private fun maneuverName(m:Int)=when(m){
        Protocol.LEFT->"Linksaf"; Protocol.RIGHT->"Rechtsaf"
        Protocol.SLIGHT_LEFT->"Flauw links"; Protocol.SLIGHT_RIGHT->"Flauw rechts"
        Protocol.SHARP_LEFT->"Scherp links"; Protocol.SHARP_RIGHT->"Scherp rechts"
        Protocol.UTURN->"Omkeren"; Protocol.ARRIVE->"Aankomst"
        else->"Rechtdoor"
    }

    private fun hasBluetoothPermissions():Boolean{
        if(Build.VERSION.SDK_INT<31) return true
        return ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    }

    private fun connectOrRequestPermission(){
        if(hasBluetoothPermissions()) ble.scanAndConnect()
        else req.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT))
    }

    override fun onDestroy() {
        BridgeState.uiLog = null
        super.onDestroy()
    }
}

object BridgeState {
    @Volatile var ble:BleManager?=null
    @Volatile var uiLog: ((String)->Unit)? = null

    // Door het B04C-display zelf gemelde fietsdata. 06/01 is op echte ritdata geverifieerd:
    // snelheid = 0,01 km/h, trip = 0,01 km, odo = 0,01 km.
    @Volatile var bikeSpeedKph:Double?=null
    @Volatile var bikeTripMeters:Int?=null
    @Volatile var bikeOdoMeters:Long?=null
    @Volatile var bikeAvgKph:Double?=null
    @Volatile var bikeRideSeconds:Int?=null
    @Volatile var bikeAssist:Int?=null
    @Volatile var bikeAssistMax:Int?=null
    @Volatile var bikeBatteryPercent:Int?=null

    // Laatste aanwijzing die daadwerkelijk naar het display ging, zodat het scherm in de app
    // hetzelfde toont als het stuur.
    @Volatile var navManeuver:Int?=null
    @Volatile var navTurnMeters:Int=0
    @Volatile var navRouteMeters:Int=0
    @Volatile var navAt:Long=0L

    private val logLines=java.util.ArrayDeque<String>()

    @Synchronized fun log(s:String){
        while(logLines.size>=5000) logLines.removeFirst()
        logLines.addLast(s)
        uiLog?.invoke(s)
    }

    @Synchronized fun snapshot():String = logLines.joinToString("\n")
}
