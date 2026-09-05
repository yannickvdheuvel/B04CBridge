package nl.yannick.b04cbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class BleManager(private val context: Context, private val log: (String)->Unit) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var challenge: ByteArray? = null
    var ready = false; private set

    private fun hasPerm() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun scanAndConnect() {
        if (!hasPerm()) { log("Bluetooth-toestemming ontbreekt"); return }
        ready=false
        log("Scannen naar B04C...")
        val scanner=adapter.bluetoothLeScanner ?: run { log("BLE scanner niet beschikbaar"); return }
        val cb=object: ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){
                val n=result.device.name ?: result.scanRecord?.deviceName ?: ""
                if(n.startsWith("B04C",true)){
                    scanner.stopScan(this); log("Gevonden: $n, verbinden...")
                    gatt=result.device.connectGatt(context,false,gattCb,BluetoothDevice.TRANSPORT_LE)
                }
            }
            override fun onScanFailed(errorCode:Int){ log("Scan fout $errorCode") }
        }
        scanner.startScan(listOf(ScanFilter.Builder().setDeviceName("B04C-BF").build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        android.os.Handler(context.mainLooper).postDelayed({ try{scanner.stopScan(cb)}catch(_:Exception){} },15000)
    }

    private val gattCb=object:BluetoothGattCallback(){
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){
            if(newState==BluetoothProfile.STATE_CONNECTED){ log("GATT verbonden; MTU aanvragen..."); g.requestMtu(64) }
            else { ready=false; log("Verbinding weg (status $status)") }
        }
        @SuppressLint("MissingPermission") override fun onMtuChanged(g:BluetoothGatt, mtu:Int,status:Int){ log("MTU=$mtu"); g.discoverServices() }
        @SuppressLint("MissingPermission") override fun onServicesDiscovered(g:BluetoothGatt,status:Int){
            val s=g.getService(UUID.fromString(Protocol.SERVICE))
            if(s==null){
                log("NUS-service niet gevonden. Services: "+g.services.joinToString{it.uuid.toString()}); return
            }
            writeChar=s.getCharacteristic(UUID.fromString(Protocol.WRITE)); notifyChar=s.getCharacteristic(UUID.fromString(Protocol.NOTIFY))
            if(writeChar==null||notifyChar==null){log("NUS characteristics ontbreken");return}
            g.setCharacteristicNotification(notifyChar,true)
            val cccd=notifyChar!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if(cccd!=null){ cccd.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            android.os.Handler(context.mainLooper).postDelayed({ write(Protocol.readChallenge()) },600)
        }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){ handleRx(c.value) }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){ handleRx(value) }
    }

    private fun handleRx(v:ByteArray){
        log("RX "+v.joinToString(" "){"%02X".format(it)})
        if(v.size>=11 && v[0]==0x55.toByte() && v[1]==0xAA.toByte()){
            val len=v[2].toInt() and 255
            val sub=v.getOrNull(5)?.toInt()?.and(255) ?: -1
            val param=v.getOrNull(6)?.toInt()?.and(255) ?: -1
            if(param==0 && len>=4 && challenge==null){
                val p=v.copyOfRange(7, minOf(11,v.size))
                if(p.size==4){ challenge=p; log("Challenge ontvangen; authenticeren..."); authenticate(p) }
            } else if(param==0 && sub in listOf(4,5)) {
                val ok=v.getOrNull(7)?.toInt()?.and(255)==0
                if(ok){ ready=true; log("AUTH OK — display klaar"); write(Protocol.syncTime(System.currentTimeMillis()/1000)) }
                else log("AUTH geweigerd")
            }
        }
    }

    private fun authenticate(ch:ByteArray){
        try{
            val plain=ByteArray(16); ch.copyInto(plain,0)
            val cipher=Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(Protocol.AES_KEY,"AES"))
            write(Protocol.auth(cipher.doFinal(plain)))
        }catch(e:Exception){log("AES fout: ${e.message}")}
    }

    @SuppressLint("MissingPermission") fun write(bytes:ByteArray){
        val c=writeChar ?: run{log("Niet verbonden");return}
        c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value=bytes
        val ok=gatt?.writeCharacteristic(c) ?: false
        log("TX${if(ok) "" else " MISLUKT"} "+bytes.joinToString(" "){"%02X".format(it)})
    }
    fun testNav(man:Int,dist:Int=250){ if(!ready) log("Nog niet geauthenticeerd"); write(Protocol.nav(dist,man,2500)) }
    fun stopNav(){ write(Protocol.stopNav()) }
}
