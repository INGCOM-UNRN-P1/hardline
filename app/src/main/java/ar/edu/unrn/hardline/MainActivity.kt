package ar.edu.unrn.hardline

import android.hardware.*
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private var revision by mutableIntStateOf(0)
    private var host by mutableStateOf("")
    private var port by mutableStateOf("8767")
    private var pin by mutableStateOf("")
    private var error by mutableStateOf("")
    private var hz by mutableIntStateOf(30)
    private var reading by mutableStateOf("Esperando acelerómetro")
    private lateinit var bridge: BridgeClient
    private lateinit var sensors: SensorManager
    private lateinit var discovery: Discovery
    private var listening = false
    private var resumed = false
    private var lastSample = 0L
    private var lastShake = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        bridge = BridgeClient { revision++; updateSensors() }
        discovery = Discovery(this) { address, servicePort -> runOnUiThread {
            if (!bridge.active) { host = address; port = servicePort.toString() }
        } }
        setContent {
            revision
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Hardline · Hardboiled", style = MaterialTheme.typography.headlineSmall)
                        Text(bridge.status)
                        if (!bridge.active) {
                            OutlinedTextField(host, { host = it }, label = { Text("IP del emulador") })
                            OutlinedTextField(port, { port = it }, label = { Text("Puerto") })
                            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN de seis dígitos") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                            Button(onClick = { discovery.start() }) { Text("Buscar en Wi-Fi") }
                            Button(onClick = { scan() }) { Text("Escanear QR") }
                            Button(onClick = {
                                runCatching { Pairing(host.trim(), port.toInt(), pin) }
                                    .onSuccess { error = ""; bridge.connect(it) }
                                    .onFailure { error = it.message ?: "Datos inválidos" }
                            }) { Text("Conectar") }
                            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Acelerómetro → ${bridge.device}, ADC 0 / 1 / 2")
                            Text(reading)
                            Text("Muestreo: $hz Hz · Vref: ${bridge.vref} mV")
                            Slider(hz.toFloat(), { hz = it.toInt(); restartSensors() }, valueRange = 20f..60f, steps = 39)
                            repeat(bridge.buttons) { index ->
                                Button(onClick = {
                                    bridge.button(index)
                                    getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                                }) { Text("Pulsador $index") }
                            }
                            if (bridge.irqDevice != null) Text("Agitá el teléfono (>2,5g) para disparar una IRQ")
                        }
                        Button(onClick = { bridge.disconnect(); pin = "" }) { Text("Desconectar") }
                    }
                }
            }
        }
    }
    private fun scan() {
        GmsBarcodeScanning.getClient(this).startScan().addOnSuccessListener { barcode ->
            runCatching { Pairing.parse(barcode.rawValue ?: "") }
                .onSuccess { host = it.host; port = it.port.toString(); pin = it.pin; bridge.connect(it) }
                .onFailure { error = it.message ?: "QR inválido" }
        }.addOnFailureListener { error = "No se pudo abrir el escáner: ${it.localizedMessage}" }
    }
    override fun onResume() { super.onResume(); resumed = true; updateSensors() }
    override fun onPause() { resumed = false; updateSensors(); super.onPause() }
    override fun onStop() { discovery.stop(); bridge.disconnect(); pin = ""; super.onStop() }
    private fun restartSensors() {
        sensors.unregisterListener(this); listening = false; updateSensors()
    }
    private fun updateSensors() {
        if (!resumed || !bridge.active) {
            sensors.unregisterListener(this); listening = false; return
        }
        if (listening) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        listening = sensor != null && sensors.registerListener(this, sensor, 1_000_000 / hz)
        if (!listening) reading = "Este dispositivo no tiene acelerómetro disponible"
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!bridge.active || !resumed || event.timestamp - lastSample < 1_000_000_000L / hz) return
        lastSample = event.timestamp
        bridge.sample(event.values)
        reading = (0..2).joinToString(" / ") { "${SensorMapping.millivolts(event.values[it], bridge.vref)} mV" }
        val magnitude = sqrt(event.values.take(3).sumOf { (it * it).toDouble() })
        if (magnitude > 2.5 * SensorMapping.GRAVITY && event.timestamp - lastShake > 1_000_000_000L) {
            lastShake = event.timestamp; bridge.shake()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
