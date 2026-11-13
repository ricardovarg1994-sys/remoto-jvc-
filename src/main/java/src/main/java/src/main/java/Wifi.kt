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

fun MainActivity.elegir(nombre: String, ip: String) {
    prefs.edit().putString("tv_nombre", nombre).putString("ip", ip).apply()
    actualizarChip()
    if (prefs.getBoolean("par_" + nombre, false)) {
        conectar(ip)
    } else {
        emparejar(nombre, ip)
    }
}

// ================= Wi-Fi =================
fun MainActivity.emparejar(nombre: String, ip: String) {
    estado("Emparejando con " + nombre + "… mira la pantalla del TV")
    emparejador?.cancelar()
    Thread {
        try {
            val e = Emparejador(this, ip)
            e.iniciar()
            emparejador = e
            runOnUiThread { pedirCodigo(nombre, ip) }
        } catch (ex: Exception) {
            estado("Error al emparejar: " + (ex.message ?: "desconocido"))
        }
    }.start()
}

fun MainActivity.pedirCodigo(nombre: String, ip: String) {
    estado("Escribe el código que salió en el TV")
    val campo = EditText(this)
    campo.hint = "Código de 6 caracteres"
    AlertDialog.Builder(this)
        .setTitle("Código del TV")
        .setView(campo)
        .setPositiveButton("Enviar") { _, _ ->
            val codigo = campo.text.toString()
            Thread {
                try {
                    val ok = emparejador?.terminar(codigo) ?: false
                    if (ok) {
                        prefs.edit().putBoolean("par_" + nombre, true).apply()
                        runOnUiThread { conectar(ip) }
                    } else {
                        estado("Código incorrecto. Vuelve a elegir tu televisor")
                    }
                } catch (ex: Exception) {
                    estado("Error: " + (ex.message ?: "desconocido"))
                }
            }.start()
        }
        .setNegativeButton("Cancelar") { _, _ -> emparejador?.cancelar() }
        .show()
}

fun MainActivity.conectar(ip: String) {
    ultimoIntento = System.currentTimeMillis()
    control?.cerrar()
    val c = Control(this, ip) { msg -> estado(msg) }
    control = c
    c.conectar()
}

fun MainActivity.tecla(codigo: Int) {
    if (Hid.conectado && Hid.enviar(codigo)) return
    val c = control
    if (c == null) {
        aviso("Primero elige tu televisor")
        return
    }
    c.tecla(codigo)
}

fun MainActivity.abrir(enlace: String) {
    val c = control
    if (c == null) {
        aviso("Primero elige tu televisor")
        return
    }
    c.abrirEnlace(enlace)
}

// Si hay conexión Wi-Fi, apaga/enciende por Wi-Fi; si no, enciende por infrarrojo
fun MainActivity.encendido() {
    if (control?.conectado == true) {
        tecla(26)
    } else {
        enviarIr()
    }
}

// Después de encender por IR, intenta reconectar por Wi-Fi unas cuantas veces
fun MainActivity.reconectarLuego() {
    val nombre = prefs.getString("tv_nombre", null) ?: return
    if (!prefs.getBoolean("par_" + nombre, false)) return
    estado("Encendiendo… esperando que el TV conecte")
    for (i in 1..10) {
        manejador.postDelayed({
            if (control?.conectado != true) {
                val ip = prefs.getString("ip", "") ?: ""
                if (ip.isNotEmpty()) conectar(ip)
                buscarTv()
            }
        }, i * 8000L)
    }
}
