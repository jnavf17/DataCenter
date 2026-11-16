package com.example.datacenter

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Estado compartido de toda la app (igual que el objeto Info del proyecto base).
// Aquí viven las conexiones Bluetooth (puerta) y Wi-Fi (administrador <-> operador).
@SuppressLint("MissingPermission")
object Info {
    lateinit var contexto: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val puerta = BtLink(scope)          // Bluetooth -> módulo de la puerta (Operador)
    val wifi = WifiLink(scope)          // Wi-Fi -> Administrador <-> Operador

    val eventos = arrayListOf<String>() // registro de eventos
    val cambios = MutableStateFlow(0)   // sube cada vez que cambia el registro o el permiso

    var rol: Rol? = null
    var usuario: String? = null         // correo del usuario con sesión iniciada
    var permiteAbrir = false            // por defecto el operador NO puede abrir

    private var iniciado = false

    fun iniciar(c: Context) {
        if (iniciado) return
        iniciado = true
        contexto = c.applicationContext
        scope.launch { puerta.lineas.collect { registrar("Puerta: $it") } }
        scope.launch { puerta.estado.collect { registrar("Bluetooth: $it") } }
        scope.launch { wifi.estado.collect { registrar("Wi-Fi: $it") } }
        scope.launch { wifi.mensajes.collect { (tipo, datos) -> recibir(tipo, datos) } }
        scope.launch {                  // latido: si se corta, la puerta se cierra sola
            while (true) {
                delay(2000)
                if (puerta.estado.value == Estado.CONECTADO) puerta.enviar("P")
            }
        }
    }

    // ---------- Registro ----------
    private fun agregar(texto: String) {
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        eventos.add(0, formato.format(Date()) + " - " + texto)
        if (eventos.size > 200) eventos.removeAt(eventos.size - 1)
        cambios.value = cambios.value + 1
    }

    // Cada evento queda con el correo de quien lo generó
    fun registrar(texto: String) {
        val u = usuario
        val linea = if (u != null) "$u: $texto" else texto
        agregar(linea)
        if (rol == Rol.OPERADOR) wifi.enviar("EVT", linea)   // el admin lo ve en tiempo real
        if (u != null) Nube.guardarEvento(u, texto)          // queda guardado en Firebase
    }

    private fun setPermiso(valor: Boolean) {
        permiteAbrir = valor
        cambios.value = cambios.value + 1
    }

    private fun recibir(tipo: String, datos: String) {
        if (rol == Rol.ADMIN && tipo == "EVT") agregar(datos)
        if (rol == Rol.OPERADOR && tipo == "PERM") {
            when (datos) {
                "abrir=1" -> { setPermiso(true); registrar("Administrador AUTORIZÓ la apertura") }
                "abrir=0" -> { setPermiso(false); registrar("Administrador REVOCÓ la apertura") }
                "bloqueo" -> { setPermiso(false); puerta.enviar("C"); registrar("BLOQUEO TOTAL ordenado por el Administrador") }
                "reset_hc05" -> { Seguridad.olvidarMac(contexto); registrar("Módulo Bluetooth desvinculado por el Administrador") }
            }
        }
    }

    // ---------- Operador ----------
    fun dispositivos(): List<BluetoothDevice> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) { emptyList() }

    fun nombre(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    fun conectarPuerta(d: BluetoothDevice): String {
        val autorizada = Seguridad.macAutorizada(contexto)
        if (autorizada != null && autorizada != d.address) {
            registrar("Dispositivo NO autorizado rechazado: " + d.address)
            return "Dispositivo no autorizado"
        }
        if (autorizada == null) Seguridad.autorizarMac(contexto, d.address)  // primer uso: queda registrado
        puerta.conectar(d)
        return "Conectando con la puerta..."
    }

    fun conectarWifi(ip: String, clave: String): String {
        if (ip.isEmpty()) return "Escribe la IP del administrador"
        if (clave.length < 12) return "La clave de enlace debe tener al menos 12 caracteres"
        wifi.conectar(ip, clave)
        return "Conectando con el administrador..."
    }

    fun abrir(): String {
        if (!permiteAbrir) {
            registrar("Apertura DENEGADA: falta autorización del administrador")
            return "Apertura denegada: falta autorización"
        }
        puerta.enviar("A")
        registrar("Operador ordenó ABRIR")
        return "Orden de apertura enviada"
    }

    fun cerrar(): String {
        puerta.enviar("C")
        registrar("Operador ordenó CERRAR")
        return "Orden de cierre enviada"
    }

    fun consultar(): String {
        puerta.enviar("1")
        return "Consultando estado..."
    }

    // ---------- Administrador ----------
    fun servirWifi(clave: String): String {
        if (clave.length < 12) return "La clave de enlace debe tener al menos 12 caracteres"
        wifi.servir(clave)
        return "Esperando al operador..."
    }

    fun permiso(valor: String): String {
        if (wifi.estado.value != Estado.CONECTADO) return "No hay operador conectado"
        wifi.enviar("PERM", valor)
        agregar((usuario ?: "Admin") + ": envió permiso " + valor)
        return "Enviado al operador"
    }

    fun cerrarSesion() {
        puerta.detener()
        wifi.detener()
        permiteAbrir = false
        Nube.cerrarSesion()
        rol = null
        usuario = null
        eventos.clear()
        cambios.value = cambios.value + 1
    }
}

fun miIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "sin red"
} catch (e: Exception) { "sin red" }

fun colorEstado(e: Estado): Int = when (e) {
    Estado.CONECTADO -> Color.parseColor("#388E3C")
    Estado.DESCONECTADO -> Color.parseColor("#D32F2F")
    else -> Color.parseColor("#F57C00")
}

// Ventana para cambiar la clave del usuario que inició sesión (Firebase)
fun Activity.dialogoCambiarClave() {
    val caja = LinearLayout(this)
    caja.orientation = LinearLayout.VERTICAL
    caja.setPadding(48, 24, 48, 0)
    val tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    val actual = EditText(this)
    actual.hint = "Clave actual"
    actual.inputType = tipo
    val nueva = EditText(this)
    nueva.hint = "Clave nueva"
    nueva.inputType = tipo
    caja.addView(actual)
    caja.addView(nueva)

    AlertDialog.Builder(this)
        .setTitle("Cambiar mi clave")
        .setView(caja)
        .setPositiveButton("Guardar") { _, _ ->
            val clave = nueva.text.toString()
            if (!Auth.claveValida(clave)) {
                Toast.makeText(this, Auth.REGLA_CLAVE, Toast.LENGTH_LONG).show()
            } else {
                Nube.cambiarClave(actual.text.toString(), clave) { error ->
                    Toast.makeText(this, error ?: "Clave actualizada", Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
