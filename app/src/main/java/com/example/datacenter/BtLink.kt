package com.example.datacenter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import java.io.IOException
import java.util.UUID

val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

enum class Estado { DESCONECTADO, CONECTANDO, CONECTADO, REINTENTANDO }

// Enlace Bluetooth Classic (SPP) con el HC-05 de la puerta. Incluye reconexión automática.
@SuppressLint("MissingPermission")
class BtLink(private val scope: CoroutineScope) {
    private val _estado = MutableStateFlow(Estado.DESCONECTADO)
    val estado = _estado.asStateFlow()
    private val _lineas = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val lineas = _lineas.asSharedFlow()

    @Volatile private var socket: BluetoothSocket? = null
    private var job: Job? = null

    fun conectar(d: BluetoothDevice) {
        detener()
        job = scope.launch(Dispatchers.IO) {
            var intentos = 0
            while (isActive && intentos <= 5) {                  // 1 intento + 5 reintentos
                _estado.value = if (intentos == 0) Estado.CONECTANDO else Estado.REINTENTANDO
                try {
                    val s = d.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    socket = s
                    intentos = 0
                    _estado.value = Estado.CONECTADO
                    val lector = s.inputStream.bufferedReader()
                    while (true) {                                // lee eventos del Arduino
                        val l = lector.readLine() ?: break
                        _lineas.tryEmit(l.trim())
                    }
                } catch (e: Exception) { /* se cayó o no pudo conectar */ }
                cerrar()
                if (!isActive) break
                intentos++
                _estado.value = Estado.REINTENTANDO
                delay(3000)                                       // espera 3 s y reintenta
            }
            if (isActive) _estado.value = Estado.DESCONECTADO
        }
    }

    fun enviar(texto: String) {
        val s = socket ?: return
        scope.launch(Dispatchers.IO) {
            try { s.outputStream.write(texto.toByteArray()); s.outputStream.flush() } catch (e: IOException) { }
        }
    }

    fun detener() {
        job?.cancel(); job = null
        cerrar()
        _estado.value = Estado.DESCONECTADO
    }

    private fun cerrar() {
        try { socket?.close() } catch (e: IOException) { }
        socket = null
    }
}
