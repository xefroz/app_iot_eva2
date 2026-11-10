package cl.inacap.salaautomatizada

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.salaautomatizada.databinding.ActivityMainBinding
import cl.inacap.salaautomatizada.databinding.DialogOperatorPinBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var role = "profesor"
    private var email = "usuario@sala.cl"
    private var arduinoIp = "192.168.4.1"
    private var wifiMode = false
    private var fanState = false
    private var demoTick = 0
    private var updatingSwitch = false
    private val operatorPin = "1234" // PIN de demostración; cambiar antes de usar en un entorno real.

    private val demoRunnable = object : Runnable {
        override fun run() {
            if (!wifiMode) {
                demoTick++
                val temperature = 23.5 + (demoTick % 8) * 0.45
                val humidity = 48.0 + (demoTick % 6) * 1.5
                val light = if (demoTick % 10 < 4) "Baja" else "Óptima"
                updateTelemetry(temperature, humidity, light, fanState)
                binding.tvPeopleCount.text = "DEMO"
            } else {
                pollArduino()
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        email = intent.getStringExtra("email") ?: getSharedPreferences("sala_session", MODE_PRIVATE).getString("email", "usuario@sala.cl").orEmpty()
        role = intent.getStringExtra("role") ?: getSharedPreferences("sala_session", MODE_PRIVATE).getString("role", "profesor").orEmpty()
        binding.tvUserEmail.text = "$email\nRol: ${if (role == "admin") "Administrador" else "Profesor (solo visualización)"}"
        binding.tvPeopleCount.text = "DEMO"
        binding.switchFan.isEnabled = role == "admin"

        binding.btnLogout.setOnClickListener {
            getSharedPreferences("sala_session", MODE_PRIVATE).edit().clear().apply()
            finish()
        }
        binding.switchFan.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (role != "admin") {
                setSwitchSilently(!checked)
                Toast.makeText(this, "Tu perfil solo permite visualizar los sensores", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            setSwitchSilently(!checked)
            showPinDialog(checked)
        }
        binding.btnConnection.setOnClickListener {
            if (wifiMode) disconnectArduino() else showIpDialog()
        }

        handler.post(demoRunnable)
    }

    private fun showIpDialog() {
        val input = EditText(this).apply {
            setText(arduinoIp)
            hint = "Ej.: 192.168.4.1"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar con Arduino")
            .setMessage("Escribe la IP del Arduino y asegúrate de que el teléfono esté conectado a la misma red Wi-Fi.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                val candidate = input.text.toString().trim().removePrefix("http://").trimEnd('/')
                if (candidate.isBlank() || candidate.contains("/")) {
                    Toast.makeText(this, "Dirección IP no válida", Toast.LENGTH_SHORT).show()
                } else {
                    arduinoIp = candidate
                    connectArduino()
                }
            }.show()
    }

    private fun connectArduino() {
        binding.tvConnection.text = "Conectando a $arduinoIp..."
        io.execute {
            try {
                val json = request("GET", "http://$arduinoIp/estado")
                val data = JSONObject(json)
                val temp = data.optDouble("temperatura", 24.0)
                val humidity = data.optDouble("humedad", 50.0)
                val light = when {
                    data.has("luz") -> data.optString("luz", "Sin dato")
                    data.has("luminosidad") -> "Luminosidad: ${data.optInt("luminosidad")}"
                    else -> "Sin dato"
                }
                val fan = data.optBoolean("ventilador", false)
                runOnUiThread {
                    wifiMode = true
                    fanState = fan
                    binding.btnConnection.text = "Desconectar Arduino"
                    binding.tvConnection.text = "Conectado por Wi-Fi: $arduinoIp"
                    binding.tvPeopleCount.text = "Wi-Fi"
                    updateTelemetry(temp, humidity, light, fan)
                    Toast.makeText(this, "Arduino conectado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvConnection.text = "No se pudo conectar. Se mantiene el modo demostración."
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectArduino() {
        wifiMode = false
        binding.btnConnection.text = "Conectar con Arduino por Wi-Fi"
        binding.tvConnection.text = "Modo demostración activo"
        binding.tvPeopleCount.text = "DEMO"
        Toast.makeText(this, "Se activó el modo demostración", Toast.LENGTH_SHORT).show()
    }

    private fun pollArduino() {
        if (!wifiMode) return
        io.execute {
            try {
                val data = JSONObject(request("GET", "http://$arduinoIp/estado"))
                val temp = data.optDouble("temperatura", 24.0)
                val humidity = data.optDouble("humedad", 50.0)
                val light = data.optString("luz", "Sin dato")
                val fan = data.optBoolean("ventilador", false)
                runOnUiThread { if (wifiMode) updateTelemetry(temp, humidity, light, fan) }
            } catch (_: Exception) {
                runOnUiThread { binding.tvConnection.text = "Se perdió la conexión con Arduino" }
            }
        }
    }

    private fun request(method: String, address: String, body: String? = null): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $response")
            response
        } finally { connection.disconnect() }
    }

    private fun showPinDialog(targetState: Boolean) {
        val dialogBinding = DialogOperatorPinBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle("Autorización de operador")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar") { _, _ -> setSwitchSilently(fanState) }
            .setPositiveButton("Autorizar") { _, _ ->
                if (dialogBinding.etOperatorPin.text?.toString() == operatorPin) {
                    changeFanState(targetState)
                } else {
                    setSwitchSilently(fanState)
                    Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_LONG).show()
                }
            }.setOnCancelListener { setSwitchSilently(fanState) }
            .show()
    }

    private fun changeFanState(newState: Boolean) {
        if (!wifiMode) {
            fanState = newState
            updateTelemetry(24.0, 50.0, "Modo demostración", fanState)
            Toast.makeText(this, "Ventilador actualizado en la simulación", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            try {
                request("POST", "http://$arduinoIp/ventilador", JSONObject().put("ventilador", newState).toString())
                runOnUiThread {
                    fanState = newState
                    setSwitchSilently(newState)
                    Toast.makeText(this, "Orden enviada al Arduino", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setSwitchSilently(fanState)
                    Toast.makeText(this, "No se pudo cambiar el ventilador: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateTelemetry(temp: Double, humidity: Double, light: String, fan: Boolean) {
        binding.tvTemperature.text = String.format(Locale.getDefault(), "%.1f °C", temp)
        binding.tvHumidity.text = String.format(Locale.getDefault(), "%.0f %%", humidity)
        binding.tvLightStatus.text = light
        fanState = fan
        setSwitchSilently(fan)
        val critical = temp >= 30.0
        binding.cardAlert.visibility = if (critical) View.VISIBLE else View.GONE
        binding.cardTemp.setCardBackgroundColor(android.graphics.Color.parseColor(if (critical) "#B71C1C" else "#1E1E1E"))
        if (!wifiMode) binding.tvConnection.text = "Modo demostración activo · datos simulados"
    }

    private fun setSwitchSilently(value: Boolean) {
        updatingSwitch = true
        binding.switchFan.isChecked = value
        updatingSwitch = false
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(demoRunnable)
        handler.post(demoRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(demoRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
