package com.remoto.jvc

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.EditText
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

private val escritor = Executors.newSingleThreadExecutor()

// Devuelve [modificador, código] del teclado Bluetooth, o null si no existe
private fun teclaHid(c: Char): IntArray? {
    return when (c) {
        in 'a'..'z' -> intArrayOf(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> intArrayOf(0x02, 0x04 + (c - 'A'))
        in '1'..'9' -> intArrayOf(0, 0x1E + (c - '1'))
        '0' -> intArrayOf(0, 0x27)
        ' ' -> intArrayOf(0, 0x2C)
        '.' -> intArrayOf(0, 0x37)
        ',' -> intArrayOf(0, 0x36)
        '-' -> intArrayOf(0, 0x2D)
        '\'' -> intArrayOf(0, 0x34)
        else -> null
    }
}

@SuppressLint("MissingPermission")
fun MainActivity.escribirEnTv(texto: String, enter: Boolean) {
    val p = Hid.proxy
    val d = Hid.dispositivo
    if (p == null || d == null || !Hid.conectado) {
        aviso("Conecta primero el Bluetooth")
        return
    }
    val limpio = Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    escritor.execute {
        try {
            for (c in limpio) {
                val k = teclaHid(c) ?: continue
                p.sendReport(d, 1, byteArrayOf(k[0].toByte(), 0, k[1].toByte(), 0, 0, 0, 0, 0))
                Thread.sleep(30)
                p.sendReport(d, 1, ByteArray(8))
                Thread.sleep(30)
            }
            if (enter) {
                Thread.sleep(150)
                p.sendReport(d, 1, byteArrayOf(0, 0, 0x28, 0, 0, 0, 0, 0))
                Thread.sleep(30)
                p.sendReport(d, 1, ByteArray(8))
            }
        } catch (e: Exception) {
            runOnUiThread { aviso("Bluetooth: " + (e.message ?: "error")) }
        }
    }
}

fun MainActivity.tecladoTv() {
    val campo = EditText(this)
    campo.hint = "Escribe lo que quieres buscar"
    campo.inputType = InputType.TYPE_CLASS_TEXT
    AlertDialog.Builder(this)
        .setTitle("Escribir en el TV")
        .setView(campo)
        .setPositiveButton("Escribir y buscar") { _, _ ->
            escribirEnTv(campo.text.toString(), true)
        }
        .setNeutralButton("Solo escribir") { _, _ ->
            escribirEnTv(campo.text.toString(), false)
        }
        .setNegativeButton("Cerrar", null)
        .show()
}

fun MainActivity.vozTv() {
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre de la película")
    try {
        startActivityForResult(i, 55)
    } catch (e: ActivityNotFoundException) {
        aviso("Este teléfono no tiene reconocimiento de voz")
    }
}

fun MainActivity.recibirVozTv(codigo: Int, resultado: Int, datos: Intent?) {
    if (codigo != 55 || resultado != Activity.RESULT_OK) return
    val texto = datos?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
    escribirEnTv(texto, true)
}
