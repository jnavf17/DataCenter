package com.example.datacenter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.crypto.SecretKey

// Enlace Wi-Fi Administrador <-> Operador. Cada línea viaja cifrada con AES-256-GCM.
//  - El Administrador actúa como servidor (servir) y el Operador como cliente (conectar).
//  - Al conectar hay un reto-respuesta: solo quien conoce la clave de enlace entra.
//  - Cada mensaje lleva un contador: un mensaje repetido (replay) corta la conexión.
class WifiLink(private val scope: CoroutineScope) {
    private val _estado = MutableStateFlow(Estado.DESCONECTADO)
    val estado = _estado.asStateFlow()
    private val _mensajes = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val mensajes = _mensajes.asSharedFlow()

    @Volatile private var socket: Socket? = null
    @Volatile private var servidor: ServerSocket? = null
    @Volatile private var salida: PrintWriter? = null
    @Volatile private var clave: SecretKey? = null
    private var job: Job? = null
    private var enviados = 0L
    private var recibidos = 0L

    // ---------- ADMINISTRADOR: espera al operador ----------
    fun servir(claveEnlace: String, puerto: Int = 5050) {
        detener()
        val k = Seguridad.claveEnlace(claveEnlace)
        clave = k
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _estado.value = Estado.CONECTANDO
                try {
                    val srv = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(puerto)) }
                    servidor = srv
                    val s = srv.accept(); srv.close()
                    socket = s
                    s.soTimeout = 5000
                    val w = PrintWriter(s.getOutputStream(), true); salida = w
                    val r = s.getInputStream().bufferedReader()
                    val reto = UUID.randomUUID().toString()
                    w.println(Seguridad.cifrar(k, "CH|$reto"))
                    val resp = r.readLine()?.let { Seguridad.descifrar(k, it) }
                    if (resp != "OK|$reto") throw IOException("Autenticación fallida")
                    s.soTimeout = 0
                    enviados = 0; recibidos = 0
                    _estado.value = Estado.CONECTADO
                    leer(r, k)
                } catch (e: Exception) { }
                cerrar()
                if (!isActive) break
                delay(1000)
            }
        }
    }

    // ---------- OPERADOR: se conecta al administrador ----------
    fun conectar(ip: String, claveEnlace: String, puerto: Int = 5050) {
        detener()
        val k = Seguridad.claveEnlace(claveEnlace)
        clave = k
        job = scope.launch(Dispatchers.IO) {
            var intentos = 0
            while (isActive && intentos <= 5) {
                _estado.value = if (intentos == 0) Estado.CONECTANDO else Estado.REINTENTANDO
                try {
                    val s = Socket(); socket = s
                    s.connect(InetSocketAddress(ip, puerto), 4000)
                    s.soTimeout = 5000
                    val w = PrintWriter(s.getOutputStream(), true); salida = w
                    val r = s.getInputStream().bufferedReader()
                    val ch = r.readLine()?.let { Seguridad.descifrar(k, it) }
                    if (ch == null || !ch.startsWith("CH|")) throw IOException("Servidor no válido")
                    w.println(Seguridad.cifrar(k, "OK|" + ch.removePrefix("CH|")))
                    s.soTimeout = 0
                    enviados = 0; recibidos = 0
                    intentos = 0
                    _estado.value = Estado.CONECTADO
                    leer(r, k)
                } catch (e: Exception) { }
                cerrar()
                if (!isActive) break
                intentos++
                _estado.value = Estado.REINTENTANDO
                delay(3000)
            }
            if (isActive) _estado.value = Estado.DESCONECTADO
        }
    }

    private fun leer(r: BufferedReader, k: SecretKey) {
        while (true) {
            val linea = r.readLine() ?: break
            val txt = Seguridad.descifrar(k, linea) ?: break      // alterado o clave distinta: se corta
            val p = txt.split("|", limit = 3)
            val n = p[0].toLongOrNull() ?: break
            if (n <= recibidos) break                              // mensaje repetido (replay): se corta
            recibidos = n
            _mensajes.tryEmit((p.getOrElse(1) { "" }) to (p.getOrElse(2) { "" }))
        }
    }

    fun enviar(tipo: String, datos: String) {
        val w = salida ?: return
        val k = clave ?: return
        scope.launch(Dispatchers.IO) {
            synchronized(this@WifiLink) {
                enviados++
                w.println(Seguridad.cifrar(k, "$enviados|$tipo|$datos"))
            }
        }
    }

    fun detener() {
        job?.cancel(); job = null
        cerrar()
        _estado.value = Estado.DESCONECTADO
    }

    private fun cerrar() {
        try { socket?.close() } catch (e: IOException) { }
        try { servidor?.close() } catch (e: IOException) { }
        socket = null; servidor = null; salida = null
    }
}
