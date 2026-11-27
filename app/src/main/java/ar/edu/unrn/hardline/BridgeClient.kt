package ar.edu.unrn.hardline

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un socket por sesión; callbacks y reconexiones serializados en el hilo principal. */
class BridgeClient(private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var pairing: Pairing? = null
    private var token: String? = null
    private var generation = 0
    private var retrySeconds = 1L
    var status = "Sin conexión"; private set
    var device: String? = null; private set
    var vref = 3300; private set
    var buttons = 0; private set
    var irqDevice: String? = null; private set
    val active: Boolean get() = device != null

    fun connect(value: Pairing) {
        disconnect()
        pairing = value
        open(generation)
    }
    fun disconnect() {
        generation++
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "pantalla inactiva")
        socket = null; pairing = null; token = null
        device = null; buttons = 0; irqDevice = null
        status = "Sin conexión"; retrySeconds = 1
        changed()
    }
    private fun send(action: String, payload: JSONObject = JSONObject()): Boolean =
        socket?.send(JSONObject().put("version", "1.3").put("sessionId", "hardboiled")
            .put("source", "android").put("type", "command").put("action", action)
            .put("payload", payload).toString()) ?: false

    private fun open(epoch: Int) {
        val target = pairing ?: return
        status = "Conectando…"; changed()
        socket = http.newWebSocket(Request.Builder().url(target.endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { handler.post {
                if (epoch != generation) { webSocket.close(1000, "sesión anterior"); return@post }
                status = "Validando PIN…"; changed()
                val credentials = JSONObject()
                if (token != null) credentials.put("token", token) else credentials.put("pin", target.pin)
                send("PAIR_REQUEST", credentials)
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { handler.post {
                if (epoch != generation) return@post
                try { receive(JSONObject(text)) } catch (_: Exception) {
                    disconnect(); status = "Respuesta incompatible"; changed()
                }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { handler.post { lost(epoch) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handler.post {
                if (epoch != generation) return@post
                if (code == 1008) { disconnect(); status = "Acceso rechazado; revisá el PIN"; changed() }
                else lost(epoch)
            } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }
    private fun lost(epoch: Int) {
        if (epoch != generation || pairing == null) return
        generation++
        val nextEpoch = generation
        device = null; buttons = 0; irqDevice = null
        status = "Sin red. Reintento en ${retrySeconds}s"; changed()
        handler.postDelayed({ open(nextEpoch) }, retrySeconds * 1000)
        retrySeconds = (retrySeconds * 2).coerceAtMost(30)
    }
    private fun receive(message: JSONObject) {
        require(message.getString("version") == "1.3")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (message.optString("action")) {
            "PAIRING_FAILED" -> {
                if (token != null) { token = null; send("PAIR_REQUEST", JSONObject().put("pin", pairing!!.pin)) }
                else { disconnect(); status = "PIN incorrecto"; changed() }
            }
            "PAIRING_SUCCESS" -> {
                require(payload.getString("service") == "hardboiled")
                token = payload.getString("token")
                send("GET_CAPABILITIES")
            }
            "CAPABILITIES_LIST" -> {
                require(payload.getString("service") == "hardboiled")
                val capabilities = payload.getJSONArray("capabilities")
                device = null; buttons = 0; irqDevice = null
                for (i in 0 until capabilities.length()) {
                    val cap = capabilities.getJSONObject(i)
                    if (!cap.optBoolean("enabled")) continue
                    when (cap.optString("controlType")) {
                        "accelerometer" -> { device = cap.getString("device"); vref = cap.getInt("vref_mv"); require(vref > 0) }
                        "button_bank" -> { buttons = cap.getInt("count").coerceIn(0, 32) }
                        "shake" -> { irqDevice = cap.getString("device") }
                    }
                }
                require(device != null)
                status = "Sensores conectados"; retrySeconds = 1; changed()
            }
            "INVALID_COMMAND" -> { status = "Muestra rechazada por el emulador"; changed() }
        }
    }
    fun sample(values: FloatArray) {
        val adc = device ?: return
        if (values.size < 3 || values.take(3).any { !it.isFinite() }) return
        send("ADC_SAMPLE", JSONObject().put("device", adc).put("millivolts",
            JSONArray((0..2).map { SensorMapping.millivolts(values[it], vref) })))
    }
    fun button(pin: Int) { if (active && pin in 0 until buttons) send("BUTTON_PRESS", JSONObject().put("pin", pin)) }
    fun shake() {
        val device = irqDevice ?: return
        if (!active) return
        send("IRQ_RAISE", JSONObject().put("device", device))
        handler.postDelayed({ if (active) send("IRQ_LOWER", JSONObject().put("device", device)) }, 100)
    }
}
