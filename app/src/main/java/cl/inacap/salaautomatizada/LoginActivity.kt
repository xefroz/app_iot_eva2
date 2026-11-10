package cl.inacap.salaautomatizada

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.salaautomatizada.databinding.ActivityLoginBinding

/** Acceso local para demostración académica; no reemplaza un servidor de autenticación real. */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("sala_session", MODE_PRIVATE)
        if (prefs.getBoolean("logged", false)) {
            openDashboard(prefs.getString("email", "usuario@sala.cl") ?: "usuario@sala.cl",
                prefs.getString("role", "profesor") ?: "profesor")
            return
        }

        binding.btnLogin.setOnClickListener { authenticate(isRegister = false) }
        binding.btnRegister.setOnClickListener { authenticate(isRegister = true) }
    }

    private fun authenticate(isRegister: Boolean) {
        val email = binding.etEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Ingresa un correo válido"
            return
        }
        if (password.length < 4) {
            binding.etPassword.error = "Usa al menos 4 caracteres"
            return
        }

        // Para probar el rol administrador utiliza admin@sala.cl. Los demás correos ingresan como profesor.
        val role = if (email.startsWith("admin@")) "admin" else "profesor"
        getSharedPreferences("sala_session", MODE_PRIVATE).edit()
            .putBoolean("logged", true)
            .putString("email", email)
            .putString("role", role)
            .apply()

        val message = if (isRegister) "Usuario local creado" else "Sesión iniciada"
        Toast.makeText(this, "$message ($role)", Toast.LENGTH_SHORT).show()
        openDashboard(email, role)
    }

    private fun openDashboard(email: String, role: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("email", email)
            putExtra("role", role)
        })
        finish()
    }
}
