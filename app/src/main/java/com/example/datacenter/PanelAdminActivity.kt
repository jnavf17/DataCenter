package com.example.datacenter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class PanelAdminActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Info.rol != Rol.ADMIN) {             // sin sesión: volver al login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_panel_admin)

        findViewById<TextView>(R.id.tvUsuario).text = "Usuario: " + Info.usuario + " (Administrador)"
        val etClaveEnlace = findViewById<EditText>(R.id.etClaveEnlace)

        // ----- Servidor Wi-Fi (el operador se conecta a este equipo) -----
        findViewById<Button>(R.id.btnIniciar).setOnClickListener {
            aviso(Info.servirWifi(etClaveEnlace.text.toString()))
            actualizar()
        }
        findViewById<Button>(R.id.btnDetener).setOnClickListener { Info.wifi.detener() }

        // ----- Permisos del operador -----
        findViewById<Button>(R.id.btnAutorizar).setOnClickListener { aviso(Info.permiso("abrir=1")) }
        findViewById<Button>(R.id.btnRevocar).setOnClickListener { aviso(Info.permiso("abrir=0")) }
        findViewById<Button>(R.id.btnBloqueo).setOnClickListener { aviso(Info.permiso("bloqueo")) }
        findViewById<Button>(R.id.btnNuevoHc05).setOnClickListener { aviso(Info.permiso("reset_hc05")) }

        findViewById<Button>(R.id.btnRegistro).setOnClickListener {
            startActivity(Intent(this, RegistroActivity::class.java))
        }
        findViewById<Button>(R.id.btnUsuarios).setOnClickListener {
            startActivity(Intent(this, UsuariosActivity::class.java))
        }
        findViewById<Button>(R.id.btnClave).setOnClickListener { dialogoCambiarClave() }
        findViewById<Button>(R.id.btnCerrar).setOnClickListener {
            Info.cerrarSesion()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Info.wifi.estado.collect { actualizar() } }
                launch { Info.cambios.collect { actualizar() } }
            }
        }
    }

    private fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
    }

    // Muestra estado, IP, últimos eventos y habilita los botones de permisos
    private fun actualizar() {
        val wifi = Info.wifi.estado.value

        val tvWifi = findViewById<TextView>(R.id.tvEstadoWifi)
        tvWifi.text = "Wi-Fi: $wifi"
        tvWifi.setTextColor(colorEstado(wifi))

        findViewById<TextView>(R.id.tvIp).text = "IP de este equipo: " + miIp()

        val conectado = wifi == Estado.CONECTADO
        findViewById<Button>(R.id.btnAutorizar).isEnabled = conectado
        findViewById<Button>(R.id.btnRevocar).isEnabled = conectado
        findViewById<Button>(R.id.btnBloqueo).isEnabled = conectado
        findViewById<Button>(R.id.btnNuevoHc05).isEnabled = conectado

        val ultimos = Info.eventos.take(6).joinToString("\n")
        findViewById<TextView>(R.id.tvUltimos).text = if (ultimos.isEmpty()) "Sin eventos todavía" else ultimos
    }
}
