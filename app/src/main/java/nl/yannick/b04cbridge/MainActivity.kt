package nl.yannick.b04cbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity:AppCompatActivity(){
    private lateinit var ble:BleManager
    private lateinit var logView:TextView
    private lateinit var status:TextView
    private val req=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ ble.scanAndConnect() }
    override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)
        logView=findViewById(R.id.log);status=findViewById(R.id.status)
        ble=BleManager(this){ s ->
            runOnUiThread {
                logView.append(s+"\n")
                status.text=s
                BridgeState.ble=ble
            }
        }
        findViewById<Button>(R.id.connect).setOnClickListener{
            if(Build.VERSION.SDK_INT>=31) req.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)) else ble.scanAndConnect()
        }
        findViewById<Button>(R.id.notif).setOnClickListener{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
        findViewById<Button>(R.id.left).setOnClickListener{ble.testNav(Protocol.LEFT)}
        findViewById<Button>(R.id.right).setOnClickListener{ble.testNav(Protocol.RIGHT)}
        findViewById<Button>(R.id.straight).setOnClickListener{ble.testNav(Protocol.STRAIGHT)}
        findViewById<Button>(R.id.stop).setOnClickListener{ble.stopNav()}
        BridgeState.ble=ble
    }
}
object BridgeState { @Volatile var ble:BleManager?=null }
