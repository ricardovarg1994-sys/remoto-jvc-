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

// ================= Infrarrojo =================
fun MainActivity.numero(t: String): Int {
    val s = t.trim().lowercase()
    return try {
        if (s.startsWith("0x")) s.substring(2).toInt(16) else s.toInt()
    } catch (e: Exception) {
        -1
    }
}

fun MainActivity.ajustesIr() {
    val caja = LinearLayout(this)
    caja.orientation = LinearLayout.VERTICAL
    caja.setPadding(dp(20), dp(10), dp(20), dp(10))

    val proto = EditText(this)
    proto.hint = "Protocolo: NEC o JVC"
    proto.setText(prefs.getString("ir_proto", "NEC"))
    val dir = EditText(this)
    dir.hint = "Dirección (número o 0x..)"
    val d = prefs.getInt("ir_dir", -1)
    if (d >= 0) dir.setText(d.toString())
    val cmd = EditText(this)
    cmd.hint = "Comando de encendido (número o 0x..)"
    val c = prefs.getInt("ir_cmd", -1)
    if (c >= 0) cmd.setText(c.toString())

    caja.addView(proto)
    caja.addView(dir)
    caja.addView(cmd)

    AlertDialog.Builder(this)
        .setTitle("Código IR de encendido")
        .setView(caja)
        .setPositiveButton("Guardar") { _, _ ->
            val p = if (proto.text.toString().trim().uppercase() == "JVC") "JVC" else "NEC"
            prefs.edit()
                .putString("ir_proto", p)
                .putInt("ir_dir", numero(dir.text.toString()))
                .putInt("ir_cmd", numero(cmd.text.toString()))
                .apply()
            aviso("Guardado")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

fun MainActivity.enviarIr() {
    val emisor = ir
    if (emisor == null || !emisor.hasIrEmitter()) {
        aviso("Este teléfono no tiene infrarrojo")
        return
    }
    val dir = prefs.getInt("ir_dir", -1)
    val cmd = prefs.getInt("ir_cmd", -1)
    if (dir < 0 || cmd < 0) {
        aviso("Falta el código IR de encendido")
        ajustesIr()
        return
    }
    val patron = if (prefs.getString("ir_proto", "NEC") == "JVC") jvc(dir, cmd) else nec(dir, cmd)
    emisor.transmit(38000, patron)
    reconectarLuego()
}

fun MainActivity.nec(dir: Int, cmd: Int): IntArray {
    val p = ArrayList<Int>()
    p.add(9000)
    p.add(4500)
    val bytes = intArrayOf(dir and 0xFF, dir.inv() and 0xFF, cmd and 0xFF, cmd.inv() and 0xFF)
    for (b in bytes) {
        for (i in 0 until 8) {
            p.add(560)
            p.add(if (((b shr i) and 1) == 1) 1690 else 560)
        }
    }
    p.add(560)
    return p.toIntArray()
}

fun MainActivity.jvc(dir: Int, cmd: Int): IntArray {
    val p = ArrayList<Int>()
    p.add(8400)
    p.add(4200)
    val bytes = intArrayOf(dir and 0xFF, cmd and 0xFF)
    for (b in bytes) {
        for (i in 0 until 8) {
            p.add(526)
            p.add(if (((b shr i) and 1) == 1) 1578 else 526)
        }
    }
    p.add(526)
    return p.toIntArray()
}
