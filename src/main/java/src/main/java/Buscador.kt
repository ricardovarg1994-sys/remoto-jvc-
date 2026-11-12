package com.remoto.jvc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// ================= Buscar televisores en la red =================
fun MainActivity.buscarTv() {
    detenerBusqueda()
    synchronized(lock) {
        cola.clear()
        resolviendo = false
    }
    encontrados.clear()
    estado("Buscando televisores en tu red Wi-Fi…")
    val l = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(tipo: String?) {}
        override fun onServiceFound(info: NsdServiceInfo?) {
            if (info == null) return
            synchronized(lock) { cola.add(info) }
            siguienteResolver()
        }
        override fun onServiceLost(info: NsdServiceInfo?) {}
        override fun onDiscoveryStopped(tipo: String?) {}
        override fun onStartDiscoveryFailed(tipo: String?, codigo: Int) {
            estado("No se pudo buscar (error " + codigo + "). Usa + → Conectar por IP.")
        }
        override fun onStopDiscoveryFailed(tipo: String?, codigo: Int) {}
    }
    descubrimiento = l
    try {
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
    } catch (e: Exception) {
        estado("No se pudo buscar. Usa + → Conectar por IP.")
    }
}

fun MainActivity.detenerBusqueda() {
    val d = descubrimiento
    if (d != null) {
        try { nsd.stopServiceDiscovery(d) } catch (e: Exception) {}
    }
    descubrimiento = null
}

fun MainActivity.siguienteResolver() {
    val info: NsdServiceInfo? = synchronized(lock) {
        if (resolviendo || cola.isEmpty()) {
            null
        } else {
            resolviendo = true
            cola.removeFirst()
        }
    }
    if (info == null) return
    try {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo?, codigo: Int) {
                terminoResolver()
            }
            override fun onServiceResolved(si: NsdServiceInfo?) {
                if (si != null) {
                    val ip = si.host?.hostAddress
                    if (ip != null && !ip.contains(":")) {
                        hallado(si.serviceName, ip)
                    }
                }
                terminoResolver()
            }
        })
    } catch (e: Exception) {
        terminoResolver()
    }
}

fun MainActivity.terminoResolver() {
    synchronized(lock) { resolviendo = false }
    siguienteResolver()
}

fun MainActivity.hallado(nombre: String, ip: String) {
    runOnUiThread {
        encontrados[nombre] = ip
        if (prefs.getString("tv_nombre", null) == null) {
            estadoTxt.text = "Toca arriba y elige tu televisor"
        }
        // Si es el TV ya emparejado y no hay conexión, conecta solo (aunque haya cambiado de IP)
        val guardado = prefs.getString("tv_nombre", null)
        val conectado = control?.conectado == true
        val ahora = System.currentTimeMillis()
        if (nombre == guardado && prefs.getBoolean("par_" + nombre, false) && !conectado && ahora - ultimoIntento > 4000) {
            prefs.edit().putString("ip", ip).apply()
            conectar(ip)
        }
    }
}
