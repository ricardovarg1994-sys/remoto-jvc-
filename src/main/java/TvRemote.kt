package com.remoto.jvc

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ============ Protobuf mínimo (a mano, sin librerías) ============
object Pb {
    class Campo(val num: Int, val entero: Long, val datos: ByteArray?)

    fun varint(valor: Long): ByteArray {
        val o = ByteArrayOutputStream()
        var v = valor
        while ((v and 0x7FL.inv()) != 0L) {
            o.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        o.write(v.toInt())
        return o.toByteArray()
    }

    fun campoInt(num: Int, valor: Int): ByteArray =
        varint((num shl 3).toLong()) + varint(valor.toLong())

    fun campoBytes(num: Int, datos: ByteArray): ByteArray =
        varint(((num shl 3) or 2).toLong()) + varint(datos.size.toLong()) + datos

    fun campoTexto(num: Int, texto: String): ByteArray =
        campoBytes(num, texto.toByteArray(Charsets.UTF_8))

    fun parsear(b: ByteArray): List<Campo> {
        val lista = ArrayList<Campo>()
        var i = 0
        fun leerVarint(): Long {
            var r = 0L
            var shift = 0
            while (i < b.size) {
                val x = b[i].toInt() and 0xFF
                i++
                r = r or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) break
                shift += 7
            }
            return r
        }
        while (i < b.size) {
            val tag = leerVarint()
            val num = (tag ushr 3).toInt()
            val tipo = (tag and 7L).toInt()
            if (tipo == 0) {
                lista.add(Campo(num, leerVarint(), null))
            } else if (tipo == 2) {
                val n = leerVarint().toInt()
                lista.add(Campo(num, 0L, b.copyOfRange(i, i + n)))
                i += n
            } else if (tipo == 1) {
                i += 8
            } else if (tipo == 5) {
                i += 4
            } else {
                return lista
            }
        }
        return lista
    }

    fun enviar(out: OutputStream, mensaje: ByteArray) {
        out.write(varint(mensaje.size.toLong()) + mensaje)
        out.flush()
    }

    fun leerMensaje(inp: InputStream): ByteArray {
        var largo = 0
        var shift = 0
        while (true) {
            val b = inp.read()
            if (b < 0) throw IOException("Conexión cerrada")
            largo = largo or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        val datos = ByteArray(largo)
        DataInputStream(inp).readFully(datos)
        return datos
    }
}

// ============ Identidad (certificado propio del teléfono) ============
object ConfiaTodo : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

object Identidad {
    private val CLAVE = "remoto".toCharArray()

    class Datos(val privada: PrivateKey, val cert: X509Certificate)

    fun cargar(ctx: Context): Datos {
        val prefs = ctx.getSharedPreferences("identidad", Context.MODE_PRIVATE)
        val k = prefs.getString("clave", null)
        val c = prefs.getString("cert", null)
        if (k != null && c != null) {
            val priv = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP))) as X509Certificate
            return Datos(priv, cert)
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val par = kpg.generateKeyPair()
        val nombre = X500Name("CN=remoto-jvc")
        val ahora = System.currentTimeMillis()
        val constructor = JcaX509v3CertificateBuilder(
            nombre,
            BigInteger.valueOf(ahora),
            Date(ahora - 86400000L),
            Date(ahora + 3650L * 86400000L),
            nombre,
            par.public
        )
        val firmante = JcaContentSignerBuilder("SHA256withRSA").build(par.private)
        val cert = JcaX509CertificateConverter().getCertificate(constructor.build(firmante))
        prefs.edit()
            .putString("clave", Base64.encodeToString(par.private.encoded, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return Datos(par.private, cert)
    }

    fun contexto(d: Datos): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("remoto", d.privada, CLAVE, arrayOf<Certificate>(d.cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CLAVE)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, arrayOf<TrustManager>(ConfiaTodo), SecureRandom())
        return ssl
    }
}

object Conexion {
    fun abrir(d: Identidad.Datos, ip: String, puerto: Int): SSLSocket {
        val ssl = Identidad.contexto(d)
        val s = ssl.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(ip, puerto), 6000)
        s.startHandshake()
        return s
    }
}

// ============ Emparejamiento (puerto 6467) ============
class Emparejador(ctx: Context, private val ip: String) {
    private val identidad = Identidad.cargar(ctx)
    private var socket: SSLSocket? = null

    private fun mensaje(vararg partes: ByteArray): ByteArray {
        var r = Pb.campoInt(1, 2) + Pb.campoInt(2, 200)
        for (p in partes) r += p
        return r
    }

    private fun leerOk(ent: InputStream) {
        val campos = Pb.parsear(Pb.leerMensaje(ent))
        val estado = campos.firstOrNull { it.num == 2 }?.entero
        if (estado != 200L) throw IOException("El TV rechazó el emparejamiento")
    }

    // Conecta y hace que el TV muestre el código en pantalla
    fun iniciar() {
        val s = Conexion.abrir(identidad, ip, 6467)
        socket = s
        val sal = s.outputStream
        val ent = s.inputStream
        val codificacion = Pb.campoInt(1, 3) + Pb.campoInt(2, 6)
        Pb.enviar(sal, mensaje(Pb.campoBytes(10, Pb.campoTexto(1, "androidtv-remote") + Pb.campoTexto(2, "Remoto JVC"))))
        leerOk(ent)
        Pb.enviar(sal, mensaje(Pb.campoBytes(20, Pb.campoBytes(1, codificacion) + Pb.campoInt(3, 1))))
        leerOk(ent)
        Pb.enviar(sal, mensaje(Pb.campoBytes(30, Pb.campoBytes(1, codificacion) + Pb.campoInt(2, 1))))
        leerOk(ent)
    }

    private fun sinCeros(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    // Envía el código que se ve en el TV. Devuelve true si quedó emparejado
    fun terminar(codigo: String): Boolean {
        val s = socket ?: throw IOException("No hay conexión de emparejamiento")
        val limpio = codigo.trim().uppercase()
        if (limpio.length != 6 || !limpio.all { (it in '0'..'9') || (it in 'A'..'F') }) {
            throw IOException("El código debe tener 6 caracteres (0-9 y A-F)")
        }
        val bytesCodigo = ByteArray(3) { limpio.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val servidor = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
        val cliente = identidad.cert.publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sinCeros(cliente.modulus))
        md.update(sinCeros(cliente.publicExponent))
        md.update(sinCeros(servidor.modulus))
        md.update(sinCeros(servidor.publicExponent))
        md.update(bytesCodigo, 1, 2)
        val hash = md.digest()
        if (hash[0] != bytesCodigo[0]) return false
        Pb.enviar(s.outputStream, mensaje(Pb.campoBytes(40, Pb.campoBytes(1, hash))))
        val campos = Pb.parsear(Pb.leerMensaje(s.inputStream))
        val estado = campos.firstOrNull { it.num == 2 }?.entero
        val ok = estado == 200L && campos.any { it.num == 41 }
        try { s.close() } catch (e: Exception) {}
        return ok
    }

    fun cancelar() {
        try { socket?.close() } catch (e: Exception) {}
    }
}

// ============ Control remoto (puerto 6466) ============
class Control(ctx: Context, private val ip: String, private val aviso: (String) -> Unit) {
    private val identidad = Identidad.cargar(ctx)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var salida: OutputStream? = null
    private val envios = Executors.newSingleThreadExecutor()
    @Volatile var conectado = false

    @Synchronized
    private fun enviarRaw(msg: ByteArray) {
        val o = salida ?: throw IOException("Sin conexión")
        Pb.enviar(o, msg)
    }

    fun conectar() {
        Thread {
            try {
                aviso("Conectando…")
                val s = Conexion.abrir(identidad, ip, 6466)
                socket = s
                salida = s.outputStream
                val ent = s.inputStream
                while (true) {
                    procesar(Pb.leerMensaje(ent))
                }
            } catch (e: Exception) {
                salida = null
                conectado = false
                aviso("Desconectado: " + (e.message ?: "error") + ". ¿Ya emparejaste? ¿IP correcta?")
            }
        }.start()
    }

    private fun procesar(msg: ByteArray) {
        for (c in Pb.parsear(msg)) {
            when (c.num) {
                1 -> {
                    val info = Pb.campoTexto(1, "Remoto JVC") + Pb.campoTexto(2, "Claude") +
                        Pb.campoInt(3, 1) + Pb.campoTexto(4, "1") +
                        Pb.campoTexto(5, "com.remoto.jvc") + Pb.campoTexto(6, "2.0")
                    enviarRaw(Pb.campoBytes(1, Pb.campoInt(1, 622) + Pb.campoBytes(2, info)))
                }
                2 -> {
                    enviarRaw(Pb.campoBytes(2, Pb.campoInt(1, 622)))
                    conectado = true
                    aviso("Conectado ✔")
                }
                8 -> {
                    val dato = c.datos
                    val v = if (dato != null) {
                        Pb.parsear(dato).firstOrNull { it.num == 1 }?.entero ?: 0L
                    } else 0L
                    enviarRaw(Pb.campoBytes(9, Pb.campoInt(1, v.toInt())))
                }
                40 -> {
                    conectado = true
                    aviso("Conectado ✔")
                }
            }
        }
    }

    // direccion 3 = pulsación corta
    fun tecla(codigo: Int) {
        envios.execute {
            try {
                enviarRaw(Pb.campoBytes(10, Pb.campoInt(1, codigo) + Pb.campoInt(2, 3)))
            } catch (e: Exception) {
                aviso("No se pudo enviar. Toca CONECTAR otra vez.")
            }
        }
    }

    fun abrirEnlace(enlace: String) {
        envios.execute {
            try {
                enviarRaw(Pb.campoBytes(90, Pb.campoTexto(1, enlace)))
            } catch (e: Exception) {
                aviso("No se pudo enviar. Toca CONECTAR otra vez.")
            }
        }
    }

    fun cerrar() {
        try { socket?.close() } catch (e: Exception) {}
        salida = null
        conectado = false
    }
}
