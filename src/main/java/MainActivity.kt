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

class MainActivity : Activity() {

    var ir: ConsumerIrManager? = null
    lateinit var prefs: SharedPreferences
    lateinit var estadoTxt: TextView
    lateinit var chip: TextView
    var control: Control? = null
    var emparejador: Emparejador? = null

    // Búsqueda de televisores en la red
    lateinit var nsd: NsdManager
    var descubrimiento: NsdManager.DiscoveryListener? = null
    val encontrados = LinkedHashMap<String, String>()
    val lock = Any()
    val cola = java.util.LinkedList<NsdServiceInfo>()
    var resolviendo = false
    var ultimoIntento = 0L
    val manejador = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(14), dp(28), dp(14), dp(28))

        // Barra superior: + y selector de televisor
        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        val mas = TextView(this)
        mas.text = "+"
        mas.textSize = 32f
        mas.setTextColor(Color.WHITE)
        mas.setPadding(dp(8), 0, dp(20), 0)
        mas.setOnClickListener { menuMas() }
        barra.addView(mas)
        chip = TextView(this)
        chip.textSize = 16f
        chip.setTextColor(Color.parseColor("#202124"))
        chip.setPadding(dp(20), dp(12), dp(20), dp(12))
        chip.setOnClickListener { elegirDialogo() }
        barra.addView(chip)
        raiz.addView(barra)

        estadoTxt = TextView(this)
        estadoTxt.setTextColor(Color.parseColor("#8A8A8A"))
        estadoTxt.textSize = 13f
        estadoTxt.text = "Sin conectar"
        estadoTxt.setPadding(dp(10), dp(10), dp(10), dp(10))
        raiz.addView(estadoTxt)

        // Fila 1: fuente y encendido
        val fuente = pastilla("FUENTE")
        pulsable(fuente, false) { tecla(178) }
        val botonEncendido = pastilla("⏻  ENCENDIDO")
        botonEncendido.setTextColor(Color.parseColor("#E57373"))
        botonEncendido.setOnClickListener { encendido() }
        botonEncendido.setOnLongClickListener {
            enviarIr()
            true
        }
        raiz.addView(fila(fuente, botonEncendido))

        // Fila 2: volumen y canales
        raiz.addView(fila(doble("VOL −", 25, "VOL +", 24), doble("CH −", 167, "CH +", 166)))

        // Fila 3: inicio y atrás
        val inicio = pastill
