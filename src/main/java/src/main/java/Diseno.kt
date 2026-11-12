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

// ================= Diseño =================
fun MainActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun MainActivity.redondo(color: Int, radioDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radioDp).toFloat()
    return g
}

fun MainActivity.ovalo(color: Int): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    return g
}

fun MainActivity.pastilla(texto: String): TextView {
    val t = TextView(this)
    t.text = texto
    t.textSize = 16f
    t.setTextColor(Color.parseColor("#B0B0B0"))
    t.gravity = Gravity.CENTER
    t.background = redondo(Color.parseColor("#1E1E1E"), 32)
    return t
}

fun MainActivity.doble(izq: String, codIzq: Int, der: String, codDer: Int): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.HORIZONTAL
    c.background = redondo(Color.parseColor("#1E1E1E"), 32)
    val textos = arrayOf(izq, der)
    val codigos = intArrayOf(codIzq, codDer)
    for (i in 0 until 2) {
        val t = TextView(this)
        t.text = textos[i]
        t.textSize = 16f
        t.setTextColor(Color.parseColor("#B0B0B0"))
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        val codigo = codigos[i]
        pulsable(t, true) { tecla(codigo) }
        c.addView(t)
    }
    return c
}

fun MainActivity.fila(vararg vistas: View): LinearLayout {
    val f = LinearLayout(this)
    f.orientation = LinearLayout.HORIZONTAL
    for (v in vistas) {
        val p = LinearLayout.LayoutParams(0, dp(64), 1f)
        p.setMargins(dp(6), dp(6), dp(6), dp(6))
        v.layoutParams = p
        f.addView(v)
    }
    return f
}

fun MainActivity.flecha(simbolo: String, codigo: Int, tam: Int, gravedad: Int): TextView {
    val t = TextView(this)
    t.text = simbolo
    t.textSize = 26f
    t.setTextColor(Color.parseColor("#8A8A8A"))
    t.gravity = Gravity.CENTER
    t.layoutParams = FrameLayout.LayoutParams(tam, tam, gravedad)
    pulsable(t, true) { tecla(codigo) }
    return t
}

fun MainActivity.crearDpad(): FrameLayout {
    val lado = (resources.displayMetrics.widthPixels * 0.80f).toInt()
    val marco = FrameLayout(this)
    marco.background = ovalo(Color.parseColor("#1A1A1A"))
    val pm = LinearLayout.LayoutParams(lado, lado)
    pm.gravity = Gravity.CENTER_HORIZONTAL
    pm.setMargins(0, dp(20), 0, dp(10))
    marco.layoutParams = pm
    val tercio = lado / 3
    marco.addView(flecha("▲", 19, tercio, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    marco.addView(flecha("▼", 20, tercio, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    marco.addView(flecha("◀", 21, tercio, Gravity.LEFT or Gravity.CENTER_VERTICAL))
    marco.addView(flecha("▶", 22, tercio, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
    val ok = TextView(this)
    ok.text = "OK"
    ok.textSize = 22f
    ok.setTextColor(Color.WHITE)
    ok.gravity = Gravity.CENTER
    ok.background = ovalo(Color.parseColor("#3D3D3D"))
    val tamOk = (lado * 0.38f).toInt()
    ok.layoutParams = FrameLayout.LayoutParams(tamOk, tamOk, Gravity.CENTER)
    pulsable(ok, false) { tecla(23) }
    marco.addView(ok)
    return marco
}

// Botón que responde al toque; si repetir es true, sigue enviando mientras lo mantienes
fun MainActivity.pulsable(v: View, repetir: Boolean, accion: () -> Unit) {
    v.setOnTouchListener { vista, evento ->
        when (evento.action) {
            MotionEvent.ACTION_DOWN -> {
                vista.alpha = 0.6f
                vista.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                accion()
                if (repetir) {
                    val r = object : Runnable {
                        override fun run() {
                            accion()
                            manejador.postDelayed(this, 150)
                        }
                    }
                    vista.tag = r
                    manejador.postDelayed(r, 450)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vista.alpha = 1f
                val r = vista.tag
                if (r is Runnable) manejador.removeCallbacks(r)
                vista.tag = null
                true
            }
            else -> true
        }
    }
}

fun MainActivity.aviso(t: String) {
    Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

fun MainActivity.estado(t: String) {
    runOnUiThread {
        estadoTxt.text = t
        actualizarChip()
    }
}

fun MainActivity.actualizarChip() {
    val nombre = prefs.getString("tv_nombre", null) ?: "Elegir televisor"
    val ok = control?.conectado == true
    chip.text = (if (ok) "●  " else "○  ") + nombre + "  ▾"
    chip.background = redondo(Color.parseColor(if (ok) "#B7E4C7" else "#D9DDE0"), 24)
}
