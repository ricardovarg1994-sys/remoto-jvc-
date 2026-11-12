package com.remoto.jvc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Accion(val texto: String, val alPulsar: () -> Unit)

    private var ir: ConsumerIrManager? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var campoIp: EditText
    private lateinit var estadoTxt: TextView
    private lateinit var listaTv: LinearLayout
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
        raiz.setPadding(dp(12), dp(24), dp(12), dp(24))

        estadoTxt = TextView(this)
        estadoTxt.setTextColor(Color.WHITE)
        estadoTxt.textSize = 16f
        estadoTxt.text = "Sin conectar"
        estadoTxt.setPadding(dp(6), dp(6), dp(6), dp(6))
        raiz.addView(estadoTxt)

        raiz.addView(fila(Accion("ENCENDER (IR)") { enviarIr() }, Accion("AJUSTES IR") { ajustesIr() }))
        raiz.addView(fila(Accion("BUSCAR TELEVISORES") { buscarTv() }))

        listaTv = LinearLayout(this)
        listaTv.orientation = LinearLayout.VERTICAL
        raiz.addView(listaTv)

        raiz.addView(fila(Accion("ENCENDIDO (Wi-Fi)") { tecla(26) }))
        raiz.addView(fila(Accion("VOL +") { tecla(24) }, Accion("SILENCIO") { tecla(164) }, Accion("CANAL +") { tecla(166) }))
        raiz.addView(fila(Accion("VOL -") { tecla(25) }, Accion("FUENTE") { tecla(178) }, Accion("CANAL -") { tecla(167) }))
        raiz.addView(fila(null, Accion("▲") { tecla(19) }, null))
        raiz.addView(fila(Accion("◀") { tecla(21) }, Accion("OK") { tecla(23) }, Accion("▶") { tecla(22) }))
        raiz.addView(fila(null, Accion("▼") { tecla(20) }, null))
        raiz.addView(fila(Accion("ATRÁS") { tecla(4) }, Accion("INICIO") { tecla(3) }, Accion("MENÚ") { tecla(82) }))
        raiz.addView(fila(Accion("⏯") { tecla(85) }))
        raiz.addView(fila(Accion("YouTube") { abrir("https://www.youtube.com") }, Accion("Netflix") { abrir("https://www.netflix.com/title") }))
        raiz.addView(fila(Accion("Prime Video") { abrir("https://app.primevideo.com") }, Accion("Disney+") { abrir("https://www.disneyplus.com") }))

        val manual = TextView(this)
        manual.text = "Si no aparece tu TV, escribe su IP:"
        manual.setTextColor(Color.GRAY)
        manual.setPadding(dp(6), dp(16), dp(6), 0)
        raiz.addView(manual)

        campoIp = EditText(this)
        campoIp.hint = "IP del TV (ej. 192.168.1.50)"
        campoIp.setTextColor(Color.WHITE)
        campoIp.setHintTextColor(Color.GRAY)
        campoIp.setText(prefs.getString("ip", ""))
        raiz.addView(campoIp)
        raiz.addView(fila(Accion("EMPAREJAR IP") { manualEmparejar() }, Accion("CONECTAR IP") { manualConectar() }))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#101418"))
        scroll.addView(raiz)
        setContentView(scroll)

        refrescarLista()

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun aviso(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun estado(t: String) {
        runOnUiThread { estadoTxt.text = t }
    }

    private fun fila(vararg acciones: Accion?): LinearLayout {
        val f = LinearLayout(this)
        f.orientation = LinearLayout.HORIZONTAL
        for (a in acciones) {
            val params = LinearLayout.LayoutParams(0, dp(64), 1f)
            params.setMargins(dp(6), dp(6), dp(6), dp(6))
            if (a == null) {
                val vacio = View(this)
                vacio.layoutParams = params
                f.addView(vacio)
            } else {
                val b = Button(this)
                b.text = a.texto
                b.textSize = 15f
                b.layoutParams = params
                b.setOnClickListener { a.alPulsar() }
                f.addView(b)
            }
        }
        return f
    }

    // ================= Buscar televisores en la red =================
    private fun buscarTv() {
        detenerBusqueda()
        synchronized(lock) {
            cola.clear()
            resolviendo = false
        }
        encontrados.clear()
        refrescarLista()
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
                estado("No se pudo buscar (error " + codigo + "). Escribe la IP a mano.")
            }
            override fun onStopDiscoveryFailed(tipo: String?, codigo: Int) {}
        }
        descubrimiento = l
        try {
            nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            estado("No se pudo buscar. Escribe la IP a mano.")
        }
    }

    private fun detenerBusqueda() {
        val d = descubrimiento
        if (d != null) {
            try { nsd.stopServiceDiscovery(d) } catch (e: Exception) {}
        }
        descubrimiento = null
    }

    private fun siguienteResolver() {
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

    private fun terminoResolver() {
        synchronized(lock) { resolviendo = false }
        siguienteResolver()
    }

    private fun hallado(nombre: String, ip: String) {
        runOnUiThread {
            encontrados[nombre] = ip
            refrescarLista()
            if (encontrados.size == 1 && prefs.getString("tv_nombre", null) == null) {
                estado("Toca tu televisor en la lista")
            }
            // Si es el TV ya emparejado y no hay conexión, conecta solo (aunque haya cambiado de IP)
            val guardado = prefs.getString("tv_nombre", null)
            val conectado = control?.conectado == true
            val ahora = System.currentTimeMillis()
            if (nombre == guardado && prefs.getBoolean("par_" + nombre, false) && !conectado && ahora - ultimoIntento > 4000) {
                prefs.edit().putString("ip", ip).apply()
                campoIp.setText(ip)
                conectar(ip)
            }
        }
    }

    private fun refrescarLista() {
        listaTv.removeAllViews()
        if (encontrados.isEmpty()) {
            val t = TextView(this)
            t.text = "Aún no aparece ningún televisor"
            t.setTextColor(Color.GRAY)
            t.setPadding(dp(6), dp(6), dp(6), dp(6))
            listaTv.addView(t)
            return
        }
        for ((nombre, ip) in encontrados) {
            val b = Button(this)
            b.text = "📺 " + nombre + "\n" + ip
            b.isAllCaps = false
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(dp(6), dp(6), dp(6), dp(6))
            b.layoutParams = params
            b.setOnClickListener { elegir(nombre, ip) }
            listaTv.addView(b)
        }
    }

    private fun elegir(nombre: String, ip: String) {
        prefs.edit().putString("tv_nombre", nombre).putString("ip", ip).apply()
        campoIp.setText(ip)
        if (prefs.getBoolean("par_" + nombre, false)) {
            conectar(ip)
        } else {
            emparejar(nombre, ip)
        }
    }

    // ================= Wi-Fi =================
    private fun ipTexto(): String? {
        val ip = campoIp.text.toString().trim()
        if (ip.isEmpty()) {
            aviso("Escribe la IP del TV")
            return null
        }
        return ip
    }

    private fun manualEmparejar() {
        val ip = ipTexto() ?: return
        prefs.edit().putString("tv_nombre", ip).putString("ip", ip).apply()
        emparejar(ip, ip)
    }

    private fun manualConectar() {
        val ip = ipTexto() ?: return
        val nombre = prefs.getString("tv_nombre", null)
        if (nombre == null || prefs.getString("ip", "") != ip) {
            prefs.edit().putString("tv_nombre", ip).putString("ip", ip).apply()
        }
        conectar(ip)
    }

    private fun emparejar(nombre: String, ip: String) {
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

    private fun pedirCodigo(nombre: String, ip: String) {
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
                            estado("Código incorrecto. Vuelve a tocar tu televisor")
                        }
                    } catch (ex: Exception) {
                        estado("Error: " + (ex.message ?: "desconocido"))
                    }
                }.start()
            }
            .setNegativeButton("Cancelar") { _, _ -> emparejador?.cancelar() }
            .show()
    }

    private fun conectar(ip: String) {
        ultimoIntento = System.currentTimeMillis()
        control?.cerrar()
        val c = Control(this, ip) { msg -> estado(msg) }
        control = c
        c.conectar()
    }

    private fun tecla(codigo: Int) {
        val c = control
        if (c == null) {
            aviso("Primero elige tu televisor")
            return
        }
        c.tecla(codigo)
    }

    private fun abrir(enlace: String) {
        val c = control
        if (c == null) {
            aviso("Primero elige tu televisor")
            return
        }
        c.abrirEnlace(enlace)
    }

    // Después de encender por IR, intenta reconectar por Wi-Fi unas cuantas veces
    private fun reconectarLuego() {
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

    // ================= Infrarrojo =================
    private fun numero(t: String): Int {
        val s = t.trim().lowercase()
        return try {
            if (s.startsWith("0x")) s.substring(2).toInt(16) else s.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun ajustesIr() {
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

    private fun enviarIr() {
        val emisor = ir
        if (emisor == null || !emisor.hasIrEmitter()) {
            aviso("Este teléfono no tiene infrarrojo")
            return
        }
        val dir = prefs.getInt("ir_dir", -1)
        val cmd = prefs.getInt("ir_cmd", -1)
        if (dir < 0 || cmd < 0) {
            aviso("Falta el código IR: toca AJUSTES IR")
            return
        }
        val patron = if (prefs.getString("ir_proto", "NEC") == "JVC") jvc(dir, cmd) else nec(dir, cmd)
        emisor.transmit(38000, patron)
        reconectarLuego()
    }

    private fun nec(dir: Int, cmd: Int): IntArray {
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

    private fun jvc(dir: Int, cmd: Int): IntArray {
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
}
