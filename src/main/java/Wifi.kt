package com.remoto.jvc

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private var wifiSocket: Socket? = null
private var wifiSalida: OutputStream? = null
private var wifiIp: String = ""

fun MainActivity.conectar(ip: String) {
    wifiIp = ip
    Thread {
        try {
            wifiSocket?.close()
            val s = Socket()
            s.connect(InetSocketAddress(ip, 6466), 3000)
            wifiSocket = s
            wifiSalida = s.getOutputStream()
            wifiAviso("Conectado a $ip")
        } catch (e: Exception) {
            wifiSalida = null
            wifiAviso("No se pudo conectar")
            reconectarLuego()
        }
    }.start()
}

fun MainActivity.reconectarLuego() {
    if (wifiIp.isEmpty()) return
    Handler(Looper.getMainLooper()).postDelayed({ conectar(wifiIp) }, 5000)
}

fun MainActivity.tecla(codigo: Int) {
    val out = wifiSalida
    if (out == null) {
        wifiAviso("Sin conexión")
        return
    }
    Thread {
        try {
            out.write(byteArrayOf(codigo.toByte()))
            out.flush()
        } catch (e: Exception) {
            wifiSalida = null
            wifiAviso("Conexión perdida")
            reconectarLuego()
        }
    }.start()
}

private fun MainActivity.wifiAviso(msg: String) {
    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
