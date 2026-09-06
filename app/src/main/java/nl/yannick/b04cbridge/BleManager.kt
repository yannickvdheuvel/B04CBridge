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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.ArrayDeque
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class BleManager(private val context: Context, private val log: (String)->Unit) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var challenge: ByteArray? = null
    private var awaitingAuthReply = false
    private val txQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private var lastDevice: BluetoothDevice? = null
    private var activeScan: ScanCallback? = null
    private var connectionWanted = false
    private var connecting = false
    private var reconnectScheduled = false
    private var reconnectAttempt = 0
    private var lastTelemetryLogAt = 0L
    private var lastStatsLogAt = 0L
    var ready = false; private set

    init { log("B04C Bridge BLE code r22-auto-reconnect+telemetry") }

    private fun hasPerm() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun hasScanPerm() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED

    private fun resetSessionState(){
        ready=false; challenge=null; awaitingAuthReply=false; writeBusy=false; txQueue.clear(); writeChar=null; notifyChar=null
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan(){
        val cb=activeScan ?: return
        activeScan=null
        runCatching { adapter.bluetoothLeScanner?.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    fun scanAndConnect() {
        if (!hasPerm() || !hasScanPerm()) { log("Bluetooth-toestemming ontbreekt"); return }
        connectionWanted=true; reconnectAttempt=0; reconnectScheduled=false
        startScan(manual=true)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(manual:Boolean=false){
        if(!connectionWanted || ready || connecting) return
        if (!hasPerm() || !hasScanPerm()) { log("Bluetooth-toestemming ontbreekt"); return }
        stopActiveScan(); resetSessionState()
        try { gatt?.close() } catch(_:Exception) {}
        gatt=null
        log(if(manual) "Scannen naar B04C..." else "Auto-reconnect: scannen naar B04C...")
        val scanner=adapter.bluetoothLeScanner ?: run { log("BLE scanner niet beschikbaar"); scheduleReconnect(); return }

        lateinit var cb: ScanCallback
        cb=object: ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){
                val n=runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: ""
                if(n.startsWith("B04C",true)){
                    if(activeScan===this) activeScan=null
                    runCatching { scanner.stopScan(this) }
                    lastDevice=result.device
                    log("Gevonden: $n, verbinden...")
                    connectDevice(result.device,false)
                }
            }
            override fun onScanFailed(errorCode:Int){
                if(activeScan===this) activeScan=null
                log("Scan fout $errorCode")
                scheduleReconnect()
            }
        }
        activeScan=cb
        scanner.startScan(listOf(ScanFilter.Builder().setDeviceName("B04C-BF").build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),cb)
        handler.postDelayed({
            if(activeScan===cb){
                activeScan=null
                runCatching { scanner.stopScan(cb) }
                if(connectionWanted && !ready && !connecting){ log("B04C niet gevonden; automatisch opnieuw proberen"); scheduleReconnect() }
            }
        },12000)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device:BluetoothDevice,reconnect:Boolean){
        if(!connectionWanted || ready || connecting || !hasPerm()) return
        stopActiveScan(); resetSessionState(); connecting=true
        if(reconnect) log("Auto-reconnect: direct opnieuw verbinden...")
        val newGatt=runCatching { device.connectGatt(context,false,gattCb,BluetoothDevice.TRANSPORT_LE) }.getOrElse {
            connecting=false; log("Verbinden mislukt: ${it.message}"); scheduleReconnect(); return
        }
        gatt=newGatt
        handler.postDelayed({
            if(connectionWanted && connecting && !ready && gatt===newGatt){
                log("Verbinding duurt te lang; opnieuw proberen...")
                connecting=false
                runCatching { newGatt.disconnect() }; runCatching { newGatt.close() }
                if(gatt===newGatt) gatt=null
                scheduleReconnect()
            }
        },12000)
    }

    private fun scheduleReconnect(){
        if(!connectionWanted || ready || connecting || reconnectScheduled) return
        val delays=longArrayOf(1500,3000,5000,10000,15000,30000)
        val delay=delays[minOf(reconnectAttempt,delays.lastIndex)]
        val attempt=++reconnectAttempt
        reconnectScheduled=true
        log("Auto-reconnect poging $attempt over ${delay/1000.0}s")
        handler.postDelayed({
            reconnectScheduled=false
            if(!connectionWanted || ready || connecting) return@postDelayed
            val device=lastDevice
            if(device!=null && attempt<=2) connectDevice(device,true) else startScan(false)
        },delay)
    }

    private val gattCb=object:BluetoothGattCallback(){
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){
            if(newState==BluetoothProfile.STATE_CONNECTED){
                if(gatt!==g){ runCatching { g.close() }; return }
                connecting=false; reconnectScheduled=false; reconnectAttempt=0
                log("GATT verbonden; MTU aanvragen..."); g.requestMtu(64)
            } else if(newState==BluetoothProfile.STATE_DISCONNECTED) {
                val current=(gatt===g)
                if(current) gatt=null
                connecting=false; resetSessionState(); runCatching { g.close() }
                log("Verbinding weg (status $status)")
                if(current) scheduleReconnect()
            }
        }
        @SuppressLint("MissingPermission") override fun onMtuChanged(g:BluetoothGatt,mtu:Int,status:Int){ log("MTU=$mtu"); g.discoverServices() }
        @SuppressLint("MissingPermission") override fun onServicesDiscovered(g:BluetoothGatt,status:Int){
            val s=g.getService(UUID.fromString(Protocol.SERVICE))
            if(s==null){ log("NUS-service niet gevonden. Services: "+g.services.joinToString{it.uuid.toString()}); return }
            writeChar=s.getCharacteristic(UUID.fromString(Protocol.WRITE)); notifyChar=s.getCharacteristic(UUID.fromString(Protocol.NOTIFY))
            if(writeChar==null||notifyChar==null){log("NUS characteristics ontbreken");return}
            g.setCharacteristicNotification(notifyChar,true)
            val cccd=notifyChar!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if(cccd!=null){ cccd.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            handler.postDelayed({ write(Protocol.readChallenge()) },1000)
        }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){ handleRx(c.value) }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){ handleRx(value) }
        @SuppressLint("MissingPermission") override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){
            writeBusy=false
            if(status!=BluetoothGatt.GATT_SUCCESS) log("Schrijffout GATT status=$status")
            handler.postDelayed({ sendNext() },80)
        }
    }

    private fun u16le(v:ByteArray,i:Int):Int=(v[i].toInt() and 255) or ((v[i+1].toInt() and 255) shl 8)
    private fun u32le(v:ByteArray,i:Int):Long=(v[i].toLong() and 255) or ((v[i+1].toLong() and 255) shl 8) or ((v[i+2].toLong() and 255) shl 16) or ((v[i+3].toLong() and 255) shl 24)

    private fun decodeTelemetry(v:ByteArray,target:Int,sub:Int,param:Int){
        // Tijdens de echte rit gecorreleerd met het scherm: 06/01 bevat snelheid, TRIP en ODO.
        // Byte 12/13/14 zijn daarna live vastgesteld door vanaf de laptop mee te lezen terwijl
        // het assistniveau werd omgeschakeld: byte 12 liep 00..05 mee met de knoppen, byte 13
        // bleef op de bovengrens staan en byte 14 was 0x12 terwijl het scherm 18% accu toonde.
        if(target==0x11 && sub==0x06 && param==0x01 && v.size>=28){
            val speedRaw=u16le(v,16)
            val tripRaw=u32le(v,18)
            val odoRaw=u32le(v,22)
            val speed=speedRaw/100.0
            val tripMeters=(tripRaw*10L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val odoMeters=odoRaw*10L
            val assist=v[12].toInt() and 255
            val assistMax=v[13].toInt() and 255
            val battery=v[14].toInt() and 255
            BridgeState.bikeSpeedKph=speed
            BridgeState.bikeTripMeters=tripMeters
            BridgeState.bikeOdoMeters=odoMeters
            BridgeState.bikeAssist=assist
            BridgeState.bikeAssistMax=assistMax
            BridgeState.bikeBatteryPercent=battery
            val now=System.currentTimeMillis()
            if(now-lastTelemetryLogAt>=5000){
                lastTelemetryLogAt=now
                log("FIETSDATA: %.1f km/h, trip %.2f km, odo %.2f km, assist %d/%d, accu %d%%"
                    .format(speed,tripMeters/1000.0,odoMeters/1000.0,assist,assistMax,battery))
            }
        }

        // 06/09 = ritstatistiek. Twee velden zijn op echte ritdata gecontroleerd:
        //   payload[0..1] rijtijd in seconden  (30,23 km / 18,53 km/h = 5873 s, gemeten 5870)
        //   payload[4..5] gemiddelde snelheid in 0,01 km/h (3D 07 = 18,53 -> scherm toonde AVG 18,5)
        // payload[6..7] staat over twee sessies onveranderd op 0x1178 (44,72?) en is mogelijk de
        // maximumsnelheid van de rit; die en de resterende bytes worden nog gelogd om te herleiden.
        if(target==0x11 && sub==0x06 && param==0x09 && v.size>=25){
            val rideSeconds=u16le(v,7)
            val avgKph=u16le(v,11)/100.0
            BridgeState.bikeRideSeconds=rideSeconds
            BridgeState.bikeAvgKph=avgKph
            val now=System.currentTimeMillis()
            if(now-lastStatsLogAt>=15000){
                lastStatsLogAt=now
                val rest=v.copyOfRange(13,minOf(23,v.size)).joinToString(" "){"%02X".format(it)}
                log("RITSTAT: gem %.2f km/h, rijtijd %d:%02d:%02d, onbekend[6..15]=%s"
                    .format(avgKph,rideSeconds/3600,(rideSeconds/60)%60,rideSeconds%60,rest))
            }
        }
    }

    private fun handleRx(v:ByteArray){
        log("RX "+v.joinToString(" "){"%02X".format(it)})
        if(v.size>=8 && v[0]==0x55.toByte() && v[1]==0xAA.toByte()){
            val len=v[2].toInt() and 255
            val direction=v.getOrNull(3)?.toInt()?.and(255) ?: -1
            val target=v.getOrNull(4)?.toInt()?.and(255) ?: -1
            val sub=v.getOrNull(5)?.toInt()?.and(255) ?: -1
            val param=v.getOrNull(6)?.toInt()?.and(255) ?: -1
            decodeTelemetry(v,target,sub,param)

            if(direction==0x10 && target==0x11 && sub==0x04 && param==0 && len>=4 && !awaitingAuthReply){
                val p=v.copyOfRange(7,minOf(11,v.size))
                if(p.size==4){ challenge=p; awaitingAuthReply=true; log("Challenge ontvangen; authenticeren..."); authenticate(p) }
            } else if(direction==0x10 && target==0x11 && param==0 && awaitingAuthReply) {
                val reply=v.getOrNull(7)?.toInt()?.and(255) ?: -1
                if(reply==0){
                    awaitingAuthReply=false; ready=true; reconnectAttempt=0
                    log("AUTH OK — display klaar"); write(Protocol.syncTime(System.currentTimeMillis()/1000))
                } else log("Auth-antwoord ontvangen: sub=%02X payload=%02X".format(sub,reply))
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

    @Synchronized fun write(bytes:ByteArray){ txQueue.addLast(bytes.copyOf()); sendNext() }

    @SuppressLint("MissingPermission") @Synchronized private fun sendNext(){
        if(writeBusy || txQueue.isEmpty()) return
        val c=writeChar ?: run{ log("Niet verbonden"); txQueue.clear(); return }
        val bytes=txQueue.removeFirst(); c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value=bytes; writeBusy=true
        val ok=gatt?.writeCharacteristic(c) ?: false
        if(!ok){
            writeBusy=false; txQueue.addFirst(bytes); log("BLE bezet; TX opnieuw proberen..."); handler.postDelayed({ sendNext() },250)
        } else log("TX "+bytes.joinToString(" "){"%02X".format(it)})
    }

    fun testNav(man:Int,dist:Int=250){ if(!ready){ log("Nog niet geauthenticeerd — test niet verstuurd"); return }; write(Protocol.nav(dist,man,2500)) }
    // Het B04C toont de tweede manoeuvre echt: op de laptop-rig gaf nav(350,links,1200,rechts,...)
    // een grote linkerpijl met 0,3 km en daarboven een kleine rechterpijl met 1,2 km. Het derde
    // slot komt nergens op het scherm terug. Manoeuvrecode 0 is géén bruikbare "onbekend"-waarde:
    // daarmee zet het display zowel de bochtafstand als de totale route op 0,0 km.
    fun sendMapsNav(man:Int,dist:Int,total:Int,nextMan:Int=Protocol.STRAIGHT,nextDist:Int=0){
        if(!ready){ log("Navigatie ontvangen, maar B04C is niet klaar"); return }
        write(Protocol.navDetailed(dist,man,nextDist,nextMan,0,Protocol.STRAIGHT,total))
    }
    fun stopNav(){ if(ready) write(Protocol.stopNav()) }
}
