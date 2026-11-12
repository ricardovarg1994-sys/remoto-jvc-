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

    private var ir: ConsumerIrManager? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var estadoTxt: TextView
    private lateinit var chip: TextView
    private var control: Control? = null
    private var emparejador: Emparejador? = null

    // Búsqueda de televisores en la red
    private lateinit var nsd: NsdManager
    private var descubrimiento: NsdManager.DiscoveryListener? = null
    private val encontrados = LinkedHashMap<String, String>()
    private val lock = Any()
    private val cola = java.util.LinkedList<NsdServiceInfo>()
    private var resolviendo = false
    private var ultimoIntento = 0L
    private val manejador = Handler(Looper.getMainLooper())

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
        val encendido = pastilla("⏻  ENCENDIDO")
        encendido.setTextColor(Color.parseColor("#E57373"))
        encendido.setOnClickListener { encendido() }
        encendido.setOnLongClickListener {
            enviarIr()
            true
        }
        raiz.addView(fila(fuente, encendido))

        // Fila 2: volumen y canales
        raiz.addView(fila(doble("VOL −", 25, "VOL +", 24), doble("CH −", 167, "CH +", 166)))

        // Fila 3: inicio y atrás
        val inicio = pastilla("⌂  INICIO")
        pulsable(inicio, false) { tecla(3) }
        val atras = pastilla("←  ATRÁS")
        pulsable(atras, false) { tecla(4) }
        raiz.addView(fila(inicio, atras))

        // Fila 4: silencio, menú, apps
        val silencio = pastilla("SILENCIO")
        pulsable(silencio, false) { tecla(164) }
        val menu = pastilla("MENÚ")
        pulsable(menu, false) { tecla(82) }
        val apps = pastilla("APPS")
        apps.setOnClickListener { appsDialogo() }
        raiz.addView(fila(silencio, menu, apps))

        // Círculo de navegación
        raiz.addView(crearDpad())

        // Reproducir / pausa
        val play = pastilla("⏯")
        pulsable(play, false) { tecla(85) }
        raiz.addView(fila(play))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#101010"))
        scroll.addView(raiz)
        setContentView(scroll)

        actualizarChip()

        // Si ya hay un TV emparejado, intenta conectar de inmediato con su última IP
        val nombreGuardado = prefs.getString("tv_nombre", null)
        val ipGuardada = prefs.getString("ip", "") ?: ""
        if (nombreGuardado != null && ipGuardada.isNotEmpty() && prefs.getBoolean("par_" + nombreGuardado, false)) {
            conectar(ipGuardada)
        }
        buscarTv()
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerBusqueda()
        manejador.removeCallbacksAndMessages(null)
        control?.cerrar()
        emparejador?.cancelar()
    }

    // ================= Diseño =================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun redondo(color: Int, radioDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radioDp).toFloat()
        return g
    }

    private fun ovalo(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun pastilla(texto: String): TextView {
        val t = TextView(this)
        t.text = texto
        t.textSize = 16f
        t.setTextColor(Color.parseColor("#B0B0B0"))
        t.gravity = Gravity.CENTER
        t.background = redondo(Color.parseColor("#1E1E1E"), 32)
        return t
    }

    private fun doble(izq: String, codIzq: Int, der: String, codDer: Int): LinearLayout {
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

    private fun fila(vararg vistas: View): LinearLayout {
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

    private fun flecha(simbolo: String, codigo: Int, tam: Int, gravedad: Int): TextView {
        val t = TextView(this)
        t.text = simbolo
        t.textSize = 26f
        t.setTextColor(Color.parseColor("#8A8A8A"))
        t.gravity = Gravity.CENTER
        t.layoutParams = FrameLayout.LayoutParams(tam, tam, gravedad)
        pulsable(t, true) { tecla(codigo) }
        return t
    }

    private fun crearDpad(): FrameLayout {
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
    private fun pulsable(v: View, repetir: Boolean, accion: () -> Unit) {
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

    private fun aviso(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun estado(t: String) {
        runOnUiThread {
            estadoTxt.text = t
            actualizarChip()
        }
    }

    private fun actualizarChip() {
        val nombre = prefs.getString("tv_nombre", null) ?: "Elegir televisor"
        val ok = control?.conectado == true
        chip.text = (if (ok) "●  " else "○  ") + nombre + "  ▾"
        chip.background = redondo(Color.parseColor(if (ok) "#B7E4C7" else "#D9DDE0"), 24)
    }

    // ================= Menús =================
    private fun menuMas() {
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

    private fun elegirDialogo() {
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
