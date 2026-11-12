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

// ================= Menús =================
fun MainActivity.menuMas() {
    val items = arrayOf("Buscar televisores", "Conectar por IP…", "Ajustes IR (encendido)")
    AlertDialog.Builder(this)
        .setItems(items) { _, i ->
            when (i) {
                0 -> buscarTv()
                1 -> ipDialogo()
                else -> ajustesIr()
            }
        }
        .show()
}

fun MainActivity.elegirDialogo() {
    if (encontrados.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("Televisores")
            .setMessage("Aún no aparece ningún televisor en tu Wi-Fi.")
            .setPositiveButton("Buscar de nuevo") { _, _ -> buscarTv() }
            .setNegativeButton("Cerrar", null)
            .show()
        return
    }
    val nombres = encontrados.keys.toList()
    val items = Array(nombres.size) { "📺 " + nombres[it] + "  (" + encontrados[nombres[it]] + ")" }
    AlertDialog.Builder(this)
        .setTitle("Elige tu televisor")
        .setItems(items) { _, i ->
            val n = nombres[i]
            elegir(n, encontrados[n] ?: "")
        }
        .show()
}

fun MainActivity.appsDialogo() {
    val nombres = arrayOf("YouTube", "Netflix", "Prime Video", "Disney+")
    val enlaces = arrayOf(
        "https://www.youtube.com",
        "https://www.netflix.com/title",
        "https://app.primevideo.com",
        "https://www.disneyplus.com"
    )
    AlertDialog.Builder(this)
        .setTitle("Abrir app")
        .setItems(nombres) { _, i -> abrir(enlaces[i]) }
        .show()
}

fun MainActivity.ipDialogo() {
    val campo = EditText(this)
    campo.hint = "IP del TV (ej. 192.168.1.50)"
    campo.setText(prefs.getString("ip", ""))
    AlertDialog.Builder(this)
        .setTitle("Conectar por IP")
        .setView(campo)
        .setPositiveButton("Emparejar") { _, _ ->
            val ip = campo.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("tv_nombre", ip).putString("ip", ip).apply()
                actualizarChip()
                emparejar(ip, ip)
            }
        }
        .setNeutralButton("Conectar") { _, _ ->
            val ip = campo.text.toString().trim()
            if (ip.isNotEmpty()) {
                val nombre = prefs.getString("tv_nombre", null)
                if (nombre == null || prefs.getString("ip", "") != ip) {
                    prefs.edit().putString("tv_nombre", ip).putString("ip", ip).apply()
                }
                actualizarChip()
                conectar(ip)
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
