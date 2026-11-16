package com.example.datacenter

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RegistroActivity : AppCompatActivity() {

    private var escucha: Nube.Escucha? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registro)

        val lista = findViewById<ListView>(R.id.lvRegistro)
        val btnAtras = findViewById<Button>(R.id.btnAtras)

        if (Info.rol == Rol.ADMIN) {
            // El administrador ve el registro guardado en Firebase (todos los usuarios)
            val textos = arrayListOf<String>()
            val adaptador = ArrayAdapter(this, android.R.layout.simple_list_item_1, textos)
            lista.adapter = adaptador
            val formato = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            escucha = Nube.escucharEventos { eventos ->
                textos.clear()
                for (e in eventos) textos.add(formato.format(Date(e.fecha)) + " - " + e.usuario + ": " + e.texto)
                adaptador.notifyDataSetChanged()
            }
        } else {
            // El operador ve los eventos de esta sesión
            val adaptador = ArrayAdapter(this, android.R.layout.simple_list_item_1, Info.eventos)
            lista.adapter = adaptador
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    Info.cambios.collect { adaptador.notifyDataSetChanged() }
                }
            }
        }

        btnAtras.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        escucha?.detener()
    }
}
