package com.example.datacenter

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    // null = todavía no se sabe, false = primer uso, true = ya existe el administrador
    private var inicializado: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Nube.cerrarSesion()          // cada vez que se abre la app hay que iniciar sesión

        val etCorreo = findViewById<EditText>(R.id.etCorreo)
        val etPass = findViewById<EditText>(R.id.etPass)
        val etPass2 = findViewById<EditText>(R.id.etPass2)
        val tvSubtitulo = findViewById<TextView>(R.id.tvSubtitulo)
        val tvAviso = findViewById<TextView>(R.id.tvAviso)
        val btnEntrar = findViewById<Button>(R.id.btnEntrar)

        fun mostrar(texto: String, error: Boolean) {
            tvAviso.text = texto
            tvAviso.setTextColor(Color.parseColor(if (error) "#D32F2F" else "#388E3C"))
            tvAviso.visibility = View.VISIBLE
        }

        // Ajusta la pantalla según si es primer uso o inicio de sesión normal
        fun actualizar() {
            btnEntrar.isEnabled = true
            tvAviso.visibility = View.GONE
            when (inicializado) {
                null -> {
                    etPass2.visibility = View.GONE
                    tvSubtitulo.text = "No se pudo conectar con Firebase. Revisa tu internet"
                    btnEntrar.text = "Reintentar"
                }
                false -> {
                    etPass2.visibility = View.VISIBLE
                    tvSubtitulo.text = "Primer uso: crea la cuenta del administrador"
                    btnEntrar.text = "Crear administrador"
                }
                true -> {
                    etPass2.visibility = View.GONE
                    tvSubtitulo.text = "Inicia sesión con tu correo"
                    btnEntrar.text = "Iniciar sesión"
                }
            }
        }

        fun consultar() {
            btnEntrar.isEnabled = false
            tvSubtitulo.text = "Conectando con Firebase..."
            Nube.inicializado { r ->
                inicializado = r
                actualizar()
            }
        }
        consultar()

        btnEntrar.setOnClickListener {
            val correo = etCorreo.text.toString().trim()
            val clave = etPass.text.toString()

            when (inicializado) {
                null -> consultar()

                false -> {
                    // ----- Primer uso: crear el administrador inicial -----
                    if (!Auth.correoValido(correo)) {
                        mostrar("Correo no válido", true)
                    } else if (!Auth.claveValida(clave)) {
                        mostrar(Auth.REGLA_CLAVE, true)
                    } else if (clave != etPass2.text.toString()) {
                        mostrar("Las contraseñas no coinciden", true)
                    } else {
                        btnEntrar.isEnabled = false
                        Nube.crearAdministradorInicial(correo, clave) { error ->
                            btnEntrar.isEnabled = true
                            if (error != null) {
                                mostrar(error, true)
                            } else {
                                etPass.setText("")
                                etPass2.setText("")
                                inicializado = true
                                actualizar()
                                mostrar("Administrador creado. Ahora inicia sesión", false)
                            }
                        }
                    }
                }

                true -> {
                    // ----- Inicio de sesión normal -----
                    val ahora = System.currentTimeMillis()
                    val bloqueo = Auth.bloqueadoHasta(this, correo)
                    if (ahora < bloqueo) {
                        mostrar("Bloqueado. Espera " + ((bloqueo - ahora) / 1000 + 1) + " s", true)
                    } else if (correo.isEmpty() || clave.isEmpty()) {
                        mostrar("Escribe tu correo y contraseña", true)
                    } else {
                        btnEntrar.isEnabled = false
                        Nube.iniciarSesion(correo, clave) { rol, error ->
                            btnEntrar.isEnabled = true
                            if (rol != null) {
                                Auth.ok(this, correo)
                                Info.rol = rol
                                Info.usuario = correo.trim().lowercase()
                                val destino = if (rol == Rol.ADMIN) PanelAdminActivity::class.java else PanelOperadorActivity::class.java
                                startActivity(Intent(this, destino))
                                finish()
                            } else {
                                if (error == "Correo o contraseña incorrectos") Auth.fallo(this, correo)
                                etPass.setText("")
                                mostrar(error ?: "Error al iniciar sesión", true)
                            }
                        }
                    }
                }
            }
        }
    }
}
