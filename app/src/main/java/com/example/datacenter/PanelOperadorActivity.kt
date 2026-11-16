package com.example.datacenter

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class PanelOperadorActivity : AppCompatActivity() {

    private var lista: List<BluetoothDevice> = emptyList()

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { cargarDispositivos() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Info.rol != Rol.OPERADOR) {          // sin sesión: volver al login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_panel_operador)

        findViewById<TextView>(R.id.tvUsuario).text = "Usuario: " + Info.usuario + " (Operador)"
        val etIp = findViewById<EditText>(R.id.etIp)
        val etClaveEnlace = findViewById<EditText>(R.id.etClaveEnlace)
        val spDispositivos = findViewById<Spinner>(R.id.spDispositivos)

        // Permiso de Bluetooth (Android 12 o superior)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            cargarDispositivos()
        }

        // ----- Wi-Fi hacia el administrador -----
        findViewById<Button>(R.id.btnWifiConectar).setOnClickListener {
            aviso(Info.conectarWifi(etIp.text.toString().trim(), etClaveEnlace.text.toString()))
        }
        findViewById<Button>(R.id.btnWifiDesconectar).setOnClickListener { Info.wifi.detener() }

        // ----- Bluetooth hacia la puerta -----
        findViewById<Button>(R.id.btnBtConectar).setOnClickListener {
            val pos = spDispositivos.selectedItemPosition
            if (pos in lista.indices) {
                aviso(Info.conectarPuerta(lista[pos]))
            } else {
                aviso("No hay dispositivos pareados")
            }
        }
        findViewById<Button>(R.id.btnBtDesconectar).setOnClickListener { Info.puerta.detener() }

        // ----- Control de la puerta -----
        findViewById<Button>(R.id.btnAbrir).setOnClickListener { aviso(Info.abrir()) }
        findViewById<Button>(R.id.btnCerrarPuerta).setOnClickListener { aviso(Info.cerrar()) }
        findViewById<Button>(R.id.btnEstado).setOnClickListener { aviso(Info.consultar()) }

        findViewById<Button>(R.id.btnRegistro).setOnClickListener {
            startActivity(Intent(this, RegistroActivity::class.java))
        }
        findViewById<Button>(R.id.btnClave).setOnClickListener { dialogoCambiarClave() }
        findViewById<Button>(R.id.btnCerrar).setOnClickListener {
            Info.cerrarSesion()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Actualiza la pantalla cuando cambia una conexión, el permiso o el registro
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Info.puerta.estado.collect { actualizar() } }
                launch { Info.wifi.estado.collect { actualizar() } }
                launch { Info.cambios.collect { actualizar() } }
            }
        }
    }

    private fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
    }

    private fun cargarDispositivos() {
        lista = Info.dispositivos()
        val nombres = lista.map { Info.nombre(it) }
        val spinner = findViewById<Spinner>(R.id.spDispositivos)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nombres)
    }

    // Muestra el permiso, el estado de las conexiones y habilita los botones
    private fun actualizar() {
        val permiso = findViewById<TextView>(R.id.tvPermiso)
        if (Info.permiteAbrir) {
            permiso.text = "Apertura AUTORIZADA por el administrador"
            permiso.setTextColor(Color.parseColor("#388E3C"))
        } else {
            permiso.text = "Apertura NO autorizada (la define el administrador)"
            permiso.setTextColor(Color.parseColor("#D32F2F"))
        }

        val wifi = Info.wifi.estado.value
        val bt = Info.puerta.estado.value

        val tvWifi = findViewById<TextView>(R.id.tvEstadoWifi)
        tvWifi.text = "Wi-Fi: $wifi"
        tvWifi.setTextColor(colorEstado(wifi))

        val tvBt = findViewById<TextView>(R.id.tvEstadoBt)
        tvBt.text = "Bluetooth: $bt"
        tvBt.setTextColor(colorEstado(bt))

        val conectado = bt == Estado.CONECTADO
        findViewById<Button>(R.id.btnAbrir).isEnabled = conectado
        findViewById<Button>(R.id.btnCerrarPuerta).isEnabled = conectado
        findViewById<Button>(R.id.btnEstado).isEnabled = conectado
    }
}
