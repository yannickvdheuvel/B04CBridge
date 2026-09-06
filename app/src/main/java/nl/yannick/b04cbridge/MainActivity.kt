package nl.yannick.b04cbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:AppCompatActivity(){
    private lateinit var ble:BleManager
    private lateinit var logView:TextView
    private lateinit var status:TextView

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
        logView=findViewById(R.id.log)
        status=findViewById(R.id.status)

        val existing=BridgeState.snapshot()
        if(existing.isNotBlank()) logView.text=existing+"\n"

        BridgeState.uiLog = { s ->
            runOnUiThread {
                logView.append(s+"\n")
                status.text=s
            }
        }

        ble=BridgeState.ble ?: BleManager(applicationContext){ s -> BridgeState.log(s) }.also { BridgeState.ble=it }

        findViewById<Button>(R.id.connect).setOnClickListener{ connectOrRequestPermission() }
        findViewById<Button>(R.id.notif).setOnClickListener{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
        findViewById<Button>(R.id.left).setOnClickListener{ble.testNav(Protocol.LEFT)}
        findViewById<Button>(R.id.right).setOnClickListener{ble.testNav(Protocol.RIGHT)}
        findViewById<Button>(R.id.straight).setOnClickListener{ble.testNav(Protocol.STRAIGHT)}
        findViewById<Button>(R.id.stop).setOnClickListener{ble.stopNav()}

        findViewById<Button>(R.id.copyLog).setOnClickListener{
            val text=BridgeState.snapshot()
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("B04C Bridge log",text))
            Toast.makeText(this,"Volledige log gekopieerd",Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.saveLog).setOnClickListener{
            val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
            saveLog.launch("B04CBridge-$stamp.txt")
        }

        // Bij volgende appstarts meteen weer verbinden; alleen de allereerste keer vragen we rechten.
        if(hasBluetoothPermissions()) ble.scanAndConnect()
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

    private val logLines=java.util.ArrayDeque<String>()

    @Synchronized fun log(s:String){
        while(logLines.size>=5000) logLines.removeFirst()
        logLines.addLast(s)
        uiLog?.invoke(s)
    }

    @Synchronized fun snapshot():String = logLines.joinToString("\n")
}
