package com.example.datacenter

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// Pantalla del administrador para agregar usuarios y activar o desactivar su acceso (Firebase)
class UsuariosActivity : AppCompatActivity() {

    private val textos = arrayListOf<String>()
    private var usuarios: List<Nube.UsuarioNube> = emptyList()
    private lateinit var adaptador: ArrayAdapter<String>
    private var escucha: Nube.Escucha? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Info.rol != Rol.ADMIN) {
            finish()
            return
        }
        setContentView(R.layout.activity_usuarios)

        val lista = findViewById<ListView>(R.id.lvUsuarios)
        val etCorreo = findViewById<EditText>(R.id.etNuevoCorreo)
        val etClave = findViewById<EditText>(R.id.etNuevaClave)
        val rbAdmin = findViewById<RadioButton>(R.id.rbNuevoAdmin)

        adaptador = ArrayAdapter(this, android.R.layout.simple_list_item_1, textos)
        lista.adapter = adaptador

        // La lista se actualiza sola cuando cambia algo en Firebase
        escucha = Nube.escucharUsuarios { nuevos ->
            usuarios = nuevos
            textos.clear()
            for (u in nuevos) {
                val estado = if (u.activo) "" else "  (desactivado)"
                textos.add(u.correo + "  -  " + u.rol.nombre + estado)
            }
            adaptador.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btnAgregar).setOnClickListener {
            val rol = if (rbAdmin.isChecked) Rol.ADMIN else Rol.OPERADOR
            val correo = etCorreo.text.toString().trim()
            val clave = etClave.text.toString()
            if (!Auth.correoValido(correo)) {
                Toast.makeText(this, "Correo no válido", Toast.LENGTH_LONG).show()
            } else if (!Auth.claveValida(clave)) {
                Toast.makeText(this, Auth.REGLA_CLAVE, Toast.LENGTH_LONG).show()
            } else {
                Nube.crearUsuario(this, correo, clave, rol) { error ->
                    if (error != null) {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    } else {
                        Info.registrar("agregó al usuario " + correo.lowercase() + " (" + rol.nombre + ")")
                        Toast.makeText(this, "Usuario agregado", Toast.LENGTH_SHORT).show()
                        etCorreo.setText("")
                        etClave.setText("")
                    }
                }
            }
        }

        // Tocar un usuario pregunta si se quiere activar o desactivar su acceso
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val u = usuarios[posicion]
            if (u.uid == Nube.auth.currentUser?.uid) {
                Toast.makeText(this, "No puedes desactivar tu propia cuenta", Toast.LENGTH_SHORT).show()
            } else {
                val accion = if (u.activo) "Desactivar" else "Activar"
                AlertDialog.Builder(this)
                    .setTitle("$accion usuario")
                    .setMessage("¿$accion a ${u.correo}?")
                    .setPositiveButton(accion) { _, _ ->
                        Nube.cambiarActivo(u.uid, !u.activo) { error ->
                            if (error == null) Info.registrar(accion.lowercase() + " al usuario " + u.correo)
                            Toast.makeText(this, error ?: "Listo", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        findViewById<Button>(R.id.btnAtras).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        escucha?.detener()
    }
}
