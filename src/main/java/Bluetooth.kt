package com.remoto.jvc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.Executors

// El teléfono se presenta ante el TV como un teclado / control remoto Bluetooth
@SuppressLint("MissingPermission")
object Hid {
    class Uso(val consumo: Boolean, val codigo: Int)

    private val USOS: Map<Int, Uso> = mapOf(
        19 to Uso(false, 0x52), 20 to Uso(false, 0x51),
        21 to Uso(false, 0x50), 22 to Uso(false, 0x4F),
        23 to Uso(false, 0x28),
        4 to Uso(true, 0x0224), 3 to Uso(true, 0x0223), 82 to Uso(true, 0x0040),
        24 to Uso(true, 0x00E9), 25 to Uso(true, 0x00EA), 164 to Uso(true, 0x00E2),
        166 to Uso(true, 0x009C), 167 to Uso(true, 0x009D),
        85 to Uso(true, 0x00CD), 26 to Uso(true, 0x0030)
    )

    private val DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()

    var proxy: BluetoothHidDevice? = null
    var registrada = false
    var dispositivo: BluetoothDevice? = null
    @Volatile var conectado = false
    var aviso: (String) -> Unit = {}
    private val envios = Executors.newSingleThreadExecutor()
    private val llamadas = Executors.newSingleThreadExecutor()

    fun iniciar(ctx: Context, alEstado: (String) -> Unit) {
        aviso = alEstado
        if (proxy != null) return
        val adaptador = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adaptador == null) {
            aviso("Este teléfono no tiene Bluetooth")
            return
        }
        adaptador.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    proxy = p as BluetoothHidDevice
                    registrar()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                proxy = null
                registrada = false
                conectado = false
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registrar() {
        val p = proxy ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Remoto JVC", "Control remoto", "Remoto",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
        )
        val ok = p.registerApp(sdp, null, null, llamadas, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                registrada = registered
                if (registered) {
                    val d = dispositivo
                    if (d != null) p.connect(d)
                }
            }
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                conectado = state == BluetoothProfile.STATE_CONNECTED
                if (conectado) {
                    aviso("Bluetooth conectado ✔")
                } else if (state == BluetoothProfile.STATE_CONNECTING) {
                    aviso("Conectando por Bluetooth…")
                } else {
                    aviso("Bluetooth desconectado")
                }
            }
        })
        if (!ok) {
            aviso("No se pudo activar el control Bluetooth. Cierra la otra app de control e inténtalo de nuevo.")
        }
    }

    fun conectar(d: BluetoothDevice) {
        dispositivo = d
        val p = proxy
        if (p == null) {
            aviso("Preparando Bluetooth… vuelve a elegir el TV en un momento")
            return
        }
        if (!registrada) {
            aviso("Preparando Bluetooth…")
            registrar()
            return
        }
        aviso("Conectando por Bluetooth…")
        p.connect(d)
    }

    // Devuelve true si la tecla se pudo enviar por Bluetooth
    fun enviar(tecla: Int): Boolean {
        val p = proxy ?: return false
        val d = dispositivo ?: return false
        if (!conectado) return false
        val uso = USOS[tecla] ?: return false
        envios.execute {
            try {
                if (uso.consumo) {
                    p.sendReport(d, 2, byteArrayOf((uso.codigo and 0xFF).toByte(), ((uso.codigo shr 8) and 0xFF).toByte()))
                    Thread.sleep(40)
                    p.sendReport(d, 2, byteArrayOf(0, 0))
                } else {
                    p.sendReport(d, 1, byteArrayOf(0, 0, uso.codigo.toByte(), 0, 0, 0, 0, 0))
                    Thread.sleep(40)
                    p.sendReport(d, 1, ByteArray(8))
                }
            } catch (e: Exception) {
                aviso("Bluetooth: " + (e.message ?: "error"))
            }
        }
        return true
    }
}

@SuppressLint("MissingPermission")
fun MainActivity.bluetoothDialogo() {
    if (Build.VERSION.SDK_INT >= 31 &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
        aviso("Acepta el permiso de Bluetooth y vuelve a tocar esta opción")
        return
    }
    val adaptador = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    if (adaptador == null || !adaptador.isEnabled) {
        aviso("Enciende el Bluetooth del teléfono")
        return
    }
    Hid.iniciar(this) { msg -> estado(msg) }
    val lista = adaptador.bondedDevices.toList()
    if (lista.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth")
            .setMessage("Primero empareja tu TV en Ajustes → Bluetooth del teléfono.")
            .setPositiveButton("Cerrar", null)
            .show()
        return
    }
    val nombres = Array(lista.size) { lista[it].name ?: lista[it].address }
    AlertDialog.Builder(this)
        .setTitle("Elige tu TV (Bluetooth)")
        .setItems(nombres) { _, i -> Hid.conectar(lista[i]) }
        .show()
}
