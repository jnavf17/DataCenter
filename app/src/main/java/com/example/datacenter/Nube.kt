package com.example.datacenter

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

// Todo lo que usa Firebase está en este archivo:
//  - Authentication: correo y contraseña
//  - Realtime Database: /usuarios (rol de cada usuario), /eventos (registro) y /config
object Nube {
    private const val URL = "https://acceso-a-datacenter-iot-default-rtdb.firebaseio.com"

    private val db: DatabaseReference by lazy { FirebaseDatabase.getInstance(URL).reference }
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    class UsuarioNube(val uid: String, val correo: String, val rol: Rol, val activo: Boolean)
    class EventoNube(val fecha: Long, val usuario: String, val texto: String)

    // Para dejar de escuchar cambios de la base de datos
    class Escucha(private val consulta: Query, private val oyente: ValueEventListener) {
        fun detener() = consulta.removeEventListener(oyente)
    }

    private fun mensajeAuth(e: Exception?): String = when (e) {
        is FirebaseAuthUserCollisionException -> "Ese correo ya existe"
        is FirebaseAuthWeakPasswordException -> "Contraseña muy débil"
        is FirebaseAuthInvalidCredentialsException -> "Correo no válido"
        is FirebaseNetworkException -> "Sin conexión a internet"
        else -> "Error: " + (e?.message ?: "desconocido")
    }

    // ---------- Primer uso ----------
    // true = ya existe el administrador inicial, false = no existe, null = no se pudo consultar
    fun inicializado(res: (Boolean?) -> Unit) {
        db.child("config").child("inicializado").get()
            .addOnSuccessListener { res(it.getValue(Boolean::class.java) == true) }
            .addOnFailureListener { res(null) }
    }

    // Devuelve null si salió bien, o el texto del error
    fun crearAdministradorInicial(correo: String, clave: String, res: (String?) -> Unit) {
        auth.createUserWithEmailAndPassword(correo, clave).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                res(mensajeAuth(t.exception))
                return@addOnCompleteListener
            }
            val uid = t.result.user!!.uid
            val datos = mapOf("correo" to correo.trim().lowercase(), "rol" to "ADMIN", "activo" to true)
            db.child("usuarios").child(uid).setValue(datos).addOnCompleteListener { t2 ->
                if (!t2.isSuccessful) {
                    res("No se pudo guardar el usuario: " + t2.exception?.message)
                    return@addOnCompleteListener
                }
                db.child("config").child("inicializado").setValue(true).addOnCompleteListener {
                    auth.signOut()
                    res(null)
                }
            }
        }
    }

    // ---------- Login ----------
    // Devuelve el rol del usuario, o un texto de error
    fun iniciarSesion(correo: String, clave: String, res: (Rol?, String?) -> Unit) {
        auth.signInWithEmailAndPassword(correo.trim(), clave).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                val msg = if (t.exception is FirebaseNetworkException) "Sin conexión a internet" else "Correo o contraseña incorrectos"
                res(null, msg)
                return@addOnCompleteListener
            }
            val uid = t.result.user!!.uid
            db.child("usuarios").child(uid).get().addOnSuccessListener { s: DataSnapshot ->
                val rol = s.child("rol").getValue(String::class.java)
                val activo = s.child("activo").getValue(Boolean::class.java) ?: false
                if (rol == null || !activo) {
                    auth.signOut()
                    res(null, "Usuario sin permiso para usar la app")
                } else {
                    res(Rol.valueOf(rol), null)
                }
            }.addOnFailureListener {
                auth.signOut()
                res(null, "No se pudo leer el usuario")
            }
        }
    }

    fun cerrarSesion() { auth.signOut() }

    fun cambiarClave(actual: String, nueva: String, res: (String?) -> Unit) {
        val u = auth.currentUser
        val correo = u?.email
        if (u == null || correo == null) { res("No hay sesión iniciada"); return }
        u.reauthenticate(EmailAuthProvider.getCredential(correo, actual)).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                res("Clave actual incorrecta")
            } else {
                u.updatePassword(nueva).addOnCompleteListener { t2 -> res(if (t2.isSuccessful) null else mensajeAuth(t2.exception)) }
            }
        }
    }

    // ---------- Usuarios (solo administrador) ----------
    // Se crea con una segunda conexión para que el administrador no pierda su sesión
    private fun authSecundaria(ctx: Context): FirebaseAuth {
        val app = try {
            FirebaseApp.getInstance("secundaria")
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(ctx, FirebaseApp.getInstance().options, "secundaria")
        }
        return FirebaseAuth.getInstance(app)
    }

    fun crearUsuario(ctx: Context, correo: String, clave: String, rol: Rol, res: (String?) -> Unit) {
        val sec = authSecundaria(ctx)
        sec.createUserWithEmailAndPassword(correo.trim(), clave).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                res(mensajeAuth(t.exception))
                return@addOnCompleteListener
            }
            val uid = t.result.user!!.uid
            sec.signOut()
            val datos = mapOf("correo" to correo.trim().lowercase(), "rol" to rol.name, "activo" to true)
            db.child("usuarios").child(uid).setValue(datos).addOnCompleteListener { t2 ->
                res(if (t2.isSuccessful) null else "No se pudo guardar el usuario: " + t2.exception?.message)
            }
        }
    }

    fun cambiarActivo(uid: String, activo: Boolean, res: (String?) -> Unit) {
        db.child("usuarios").child(uid).child("activo").setValue(activo)
            .addOnCompleteListener { t -> res(if (t.isSuccessful) null else "No se pudo cambiar: " + t.exception?.message) }
    }

    fun escucharUsuarios(cambio: (List<UsuarioNube>) -> Unit): Escucha {
        val consulta = db.child("usuarios")
        val oyente = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val lista = ArrayList<UsuarioNube>()
                for (h in s.children) {
                    val rol = h.child("rol").getValue(String::class.java) ?: continue
                    val correo = h.child("correo").getValue(String::class.java) ?: ""
                    val activo = h.child("activo").getValue(Boolean::class.java) ?: true
                    lista.add(UsuarioNube(h.key ?: "", correo, Rol.valueOf(rol), activo))
                }
                cambio(lista)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        consulta.addValueEventListener(oyente)
        return Escucha(consulta, oyente)
    }

    // ---------- Registro de eventos ----------
    fun guardarEvento(usuario: String, texto: String) {
        if (auth.currentUser == null) return
        val evento = mapOf("fecha" to ServerValue.TIMESTAMP, "usuario" to usuario, "texto" to texto)
        db.child("eventos").push().setValue(evento)
    }

    // Los últimos 100 eventos, el más nuevo primero (solo lo puede leer el administrador)
    fun escucharEventos(cambio: (List<EventoNube>) -> Unit): Escucha {
        val consulta = db.child("eventos").orderByChild("fecha").limitToLast(100)
        val oyente = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val lista = ArrayList<EventoNube>()
                for (h in s.children) {
                    val fecha = h.child("fecha").getValue(Long::class.java) ?: 0L
                    val usuario = h.child("usuario").getValue(String::class.java) ?: ""
                    val texto = h.child("texto").getValue(String::class.java) ?: ""
                    lista.add(EventoNube(fecha, usuario, texto))
                }
                lista.reverse()
                cambio(lista)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        consulta.addValueEventListener(oyente)
        return Escucha(consulta, oyente)
    }
}
