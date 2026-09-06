package nl.yannick.b04cbridge

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class B04CApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val ble = BridgeState.ble ?: BleManager(applicationContext) { s ->
            BridgeState.log(s)
        }.also { BridgeState.ble = it }

        // De NotificationListener kan het proces starten zonder dat MainActivity ooit geopend is.
        // Zodra de Bluetooth-rechten al verleend zijn, moet de bridge dan zelf weer verbinden.
        if (hasBluetoothPermissions()) {
            BridgeState.log("Achtergrond auto-connect actief")
            ble.scanAndConnect()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
